// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.observability.SparkHook;
import dev.raskol.vault.storage.LedgerWriter;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Сервис кошельков с подтверждением записи до публикации нового баланса.
 *
 * Операции над одним кошельком сериализуются. Для перевода блокировки
 * захватываются в стабильном порядке, чтобы исключить взаимную блокировку.
 */
public final class WalletService {

    private static final int LOCK_STRIPES = 256;

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final LedgerWriter writer;
    private final CurrencyRegistry currencies;
    private final EssentialsHook essentials;
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();
    private final ReentrantLock[] walletLocks = new ReentrantLock[LOCK_STRIPES];

    public WalletService(Plugin plugin, SQLiteLedger ledger, LedgerWriter writer,
                         CurrencyRegistry currencies, EssentialsHook essentials,
                         long joinTimeoutMillis, SparkHook spark) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.writer = writer;
        this.currencies = currencies;
        this.essentials = essentials;
        for (int i = 0; i < walletLocks.length; i++) {
            walletLocks[i] = new ReentrantLock();
        }
    }

    private double maxTx() {
        return plugin.getConfig().getDouble("safety.max-transaction", 1_000_000_000.0D);
    }

    /** Сумма должна быть конечной, положительной и не выше установленного лимита. */
    private boolean validAmount(double value) {
        return Double.isFinite(value) && value > 0.0D && value <= maxTx();
    }

    public void init() {
        for (Map.Entry<UUID, Map<String, Double>> entry : ledger.loadAllBalances().entrySet()) {
            cache.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
        }
    }

    public double getBalance(UUID uuid, String currencyId) {
        if (uuid == null || currencyId == null) {
            return 0.0D;
        }
        ReentrantLock lock = lockFor(uuid, currencyId);
        lock.lock();
        try {
            if (currencies.globalId().equals(currencyId)) {
                return essentials.isAvailable() ? essentials.getBalance(uuid) : 0.0D;
            }
            Map<String, Double> row = cache.get(uuid);
            return row == null ? 0.0D : row.getOrDefault(currencyId, 0.0D);
        } finally {
            lock.unlock();
        }
    }

    public boolean has(UUID uuid, String currencyId, double amount) {
        return Double.isFinite(amount) && amount >= 0.0D
                && getBalance(uuid, currencyId) >= amount - 1.0E-9D;
    }

    public Map<UUID, Map<String, Double>> cacheSnapshot() {
        Map<UUID, Map<String, Double>> out = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Double>> entry : cache.entrySet()) {
            out.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return out;
    }

    public int cachedRows() {
        return cache.size();
    }

    public long cacheHits() {
        return 0L;
    }

    public long cacheMisses() {
        return 0L;
    }

    public double cacheHitRate() {
        return 100.0D;
    }

    public void setCache(UUID uuid, String currencyId, double value) {
        if (uuid == null || currencyId == null || !Double.isFinite(value) || value < 0.0D) {
            return;
        }
        ReentrantLock lock = lockFor(uuid, currencyId);
        lock.lock();
        try {
            cache.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(currencyId, value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Сверяет кэш с подтверждённым состоянием SQLite.
     * Каждая запись кэша обновляется под той же блокировкой, что и операции кошелька.
     */
    public int reconcile() {
        Map<UUID, Map<String, Double>> database = ledger.loadAllBalances();
        int healed = 0;

        for (Map.Entry<UUID, Map<String, Double>> entry : database.entrySet()) {
            for (Map.Entry<String, Double> currency : entry.getValue().entrySet()) {
                UUID owner = entry.getKey();
                String currencyId = currency.getKey();
                ReentrantLock lock = lockFor(owner, currencyId);
                lock.lock();
                try {
                    Map<String, Double> row = cache.computeIfAbsent(owner,
                            ignored -> new ConcurrentHashMap<>());
                    double cached = row.getOrDefault(currencyId, 0.0D);
                    if (Math.abs(cached - currency.getValue()) > 1.0E-6D) {
                        row.put(currencyId, currency.getValue());
                        healed++;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        for (Map.Entry<UUID, Map<String, Double>> entry : cache.entrySet()) {
            for (String currencyId : entry.getValue().keySet()) {
                Map<String, Double> databaseRow = database.get(entry.getKey());
                if (databaseRow == null || !databaseRow.containsKey(currencyId)) {
                    ReentrantLock lock = lockFor(entry.getKey(), currencyId);
                    lock.lock();
                    try {
                        Map<String, Double> row = cache.get(entry.getKey());
                        if (row != null && row.containsKey(currencyId)) {
                            row.put(currencyId, 0.0D);
                            healed++;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
        return healed;
    }

    public boolean deposit(UUID uuid, String currencyId, double amount,
                           TransactionType type, String reason) {
        if (uuid == null || currencyId == null || !validAmount(amount)) {
            return false;
        }

        ReentrantLock lock = lockFor(uuid, currencyId);
        lock.lock();
        try {
            if (currencies.globalId().equals(currencyId)) {
                if (!essentials.isAvailable()) {
                    return false;
                }
                double old = essentials.getBalance(uuid);
                double updated = old + amount;
                return Double.isFinite(updated) && essentials.setBalance(uuid, updated);
            }

            double old = getBalance(uuid, currencyId);
            double updated = old + amount;
            if (!Double.isFinite(updated)) {
                return false;
            }

            Transaction transaction = new Transaction(-1L, System.currentTimeMillis(),
                    null, uuid, currencyId, amount, type, safeReason(reason), null);
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteChecked(
                    List.of(new SQLiteLedger.CheckedBalance(uuid, currencyId, old, updated)),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(uuid, currencyId, updated);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("RaskolVault: не удалось начислить средства "
                    + uuid + "/" + currencyId + ": " + exception.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean withdraw(UUID uuid, String currencyId, double amount,
                             TransactionType type, String reason) {
        if (uuid == null || currencyId == null || !validAmount(amount)) {
            return false;
        }

        ReentrantLock lock = lockFor(uuid, currencyId);
        lock.lock();
        try {
            if (currencies.globalId().equals(currencyId)) {
                if (!essentials.isAvailable()) {
                    return false;
                }
                double old = essentials.getBalance(uuid);
                if (!Double.isFinite(old) || old < amount - 1.0E-9D) {
                    return false;
                }
                return essentials.setBalance(uuid, old - amount);
            }

            double old = getBalance(uuid, currencyId);
            if (old < amount - 1.0E-9D) {
                return false;
            }
            double updated = Math.max(0.0D, old - amount);

            Transaction transaction = new Transaction(-1L, System.currentTimeMillis(),
                    uuid, null, currencyId, amount, type, safeReason(reason), null);
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteChecked(
                    List.of(new SQLiteLedger.CheckedBalance(uuid, currencyId, old, updated)),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(uuid, currencyId, updated);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("RaskolVault: не удалось списать средства "
                    + uuid + "/" + currencyId + ": " + exception.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        if (from == null || to == null || currencyId == null
                || !validAmount(amount) || from.equals(to)) {
            return false;
        }

        int fromIndex = lockIndex(from, currencyId);
        int toIndex = lockIndex(to, currencyId);
        int firstIndex = Math.min(fromIndex, toIndex);
        int secondIndex = Math.max(fromIndex, toIndex);
        ReentrantLock first = walletLocks[firstIndex];
        ReentrantLock second = walletLocks[secondIndex];

        first.lock();
        if (secondIndex != firstIndex) {
            second.lock();
        }
        try {
            if (currencies.globalId().equals(currencyId)) {
                return transferGlobal(from, to, amount);
            }

            double oldFrom = getBalance(from, currencyId);
            if (oldFrom < amount - 1.0E-9D) {
                return false;
            }
            double oldTo = getBalance(to, currencyId);
            double newFrom = Math.max(0.0D, oldFrom - amount);
            double newTo = oldTo + amount;
            if (!Double.isFinite(newTo)) {
                return false;
            }

            Transaction transaction = new Transaction(-1L, System.currentTimeMillis(),
                    from, to, currencyId, amount, TransactionType.PAY, safeReason(reason), null);
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteChecked(
                    List.of(
                            new SQLiteLedger.CheckedBalance(from, currencyId, oldFrom, newFrom),
                            new SQLiteLedger.CheckedBalance(to, currencyId, oldTo, newTo)
                    ),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(from, currencyId, newFrom);
            setCache(to, currencyId, newTo);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("RaskolVault: перевод не выполнен "
                    + from + " -> " + to + " (" + currencyId + "): "
                    + exception.getMessage());
            return false;
        } finally {
            if (secondIndex != firstIndex) {
                second.unlock();
            }
            first.unlock();
        }
    }

    private boolean transferGlobal(UUID from, UUID to, double amount) {
        if (!essentials.isAvailable()) {
            return false;
        }
        double fromBalance = essentials.getBalance(from);
        double toBalance = essentials.getBalance(to);
        if (!Double.isFinite(fromBalance) || !Double.isFinite(toBalance)
                || fromBalance < amount - 1.0E-9D
                || !Double.isFinite(toBalance + amount)) {
            return false;
        }
        if (!essentials.setBalance(from, fromBalance - amount)) {
            return false;
        }
        if (essentials.setBalance(to, toBalance + amount)) {
            return true;
        }

        // Компенсация для внешнего Essentials-бэкенда; полноценная атомарность
        // внешнего провайдера будет отдельно проверена в фазе интеграций.
        if (!essentials.setBalance(from, fromBalance)) {
            plugin.getLogger().severe("RaskolVault: критическая ошибка компенсации GLOBAL-перевода "
                    + from + " -> " + to);
        }
        return false;
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "unspecified" : reason;
    }

    private ReentrantLock lockFor(UUID owner, String currencyId) {
        return walletLocks[lockIndex(owner, currencyId)];
    }

    private int lockIndex(UUID owner, String currencyId) {
        return Math.floorMod(31 * owner.hashCode() + currencyId.hashCode(), LOCK_STRIPES);
    }
}
