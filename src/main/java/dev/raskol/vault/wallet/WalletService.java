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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    public record WalletDelta(UUID owner, String currencyId, double delta) {}
    public record ReserveDelta(String nation, double delta) {}

    private record WalletKey(UUID owner, String currencyId) {}

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
        for (ReentrantLock lock : walletLocks) {
            lock.lock();
        }
        try {
            for (Map.Entry<UUID, Map<String, Double>> entry : cache.entrySet()) {
                out.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            return out;
        } finally {
            for (int i = walletLocks.length - 1; i >= 0; i--) {
                walletLocks[i].unlock();
            }
        }
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
        // Снимок БД и замена кэша выполняются под всеми блокировками кошельков.
        // Иначе устаревший снимок мог бы затереть результат операции,
        // завершившейся после чтения БД, но до обновления кэша.
        for (ReentrantLock lock : walletLocks) {
            lock.lock();
        }
        try {
            Map<UUID, Map<String, Double>> database = ledger.loadAllBalances();
            int healed = 0;

            for (Map.Entry<UUID, Map<String, Double>> entry : database.entrySet()) {
                Map<String, Double> oldRow = cache.get(entry.getKey());
                Map<String, Double> newRow = entry.getValue();
                if (oldRow == null) {
                    healed += newRow.size();
                    continue;
                }
                for (Map.Entry<String, Double> currency : newRow.entrySet()) {
                    if (Math.abs(oldRow.getOrDefault(currency.getKey(), 0.0D)
                            - currency.getValue()) > 1.0E-6D) {
                        healed++;
                    }
                }
                for (String currencyId : oldRow.keySet()) {
                    if (!newRow.containsKey(currencyId)) {
                        healed++;
                    }
                }
            }

            for (Map.Entry<UUID, Map<String, Double>> entry : cache.entrySet()) {
                if (!database.containsKey(entry.getKey())) {
                    healed += entry.getValue().size();
                }
            }

            cache.clear();
            for (Map.Entry<UUID, Map<String, Double>> entry : database.entrySet()) {
                cache.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
            return healed;
        } finally {
            for (int i = walletLocks.length - 1; i >= 0; i--) {
                walletLocks[i].unlock();
            }
        }
    }

    public boolean deposit(UUID uuid, String currencyId, double amount,
                           TransactionType type, String reason) {
        return depositOnce(uuid, currencyId, amount, type, reason, UUID.randomUUID().toString());
    }

    /**
     * Начисляет средства с защитой от повторного выполнения по стабильному ключу запроса.
     */
    public boolean depositOnce(UUID uuid, String currencyId, double amount,
                               TransactionType type, String reason, String operationId) {
        if (uuid == null || currencyId == null || type == null || !validAmount(amount)
                || operationId == null || operationId.isBlank()) {
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

            String fingerprint = operationFingerprint("DEPOSIT", uuid, null,
                    currencyId, amount, type, reason);
            if (ledger.operationCommitted(operationId, fingerprint)) {
                setCache(uuid, currencyId, ledger.getBalance(uuid, currencyId));
                return true;
            }

            double old = getBalance(uuid, currencyId);
            double updated = old + amount;
            if (!Double.isFinite(updated)) {
                return false;
            }

            Transaction transaction = new Transaction(-1L, System.currentTimeMillis(),
                    null, uuid, currencyId, amount, type, safeReason(reason), null);
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteCheckedOnce(
                    operationId, fingerprint,
                    List.of(new SQLiteLedger.CheckedBalance(uuid, currencyId, old, updated)),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(uuid, currencyId, ledger.getBalance(uuid, currencyId));
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
        return withdrawOnce(uuid, currencyId, amount, type, reason, UUID.randomUUID().toString());
    }

    /**
     * Списывает средства с защитой от повторного выполнения по стабильному ключу запроса.
     */
    public boolean withdrawOnce(UUID uuid, String currencyId, double amount,
                                TransactionType type, String reason, String operationId) {
        if (uuid == null || currencyId == null || type == null || !validAmount(amount)
                || operationId == null || operationId.isBlank()) {
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

            String fingerprint = operationFingerprint("WITHDRAW", uuid, null,
                    currencyId, amount, type, reason);
            if (ledger.operationCommitted(operationId, fingerprint)) {
                setCache(uuid, currencyId, ledger.getBalance(uuid, currencyId));
                return true;
            }

            double old = getBalance(uuid, currencyId);
            if (old < amount - 1.0E-9D) {
                return false;
            }
            double updated = Math.max(0.0D, old - amount);

            Transaction transaction = new Transaction(-1L, System.currentTimeMillis(),
                    uuid, null, currencyId, amount, type, safeReason(reason), null);
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteCheckedOnce(
                    operationId, fingerprint,
                    List.of(new SQLiteLedger.CheckedBalance(uuid, currencyId, old, updated)),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(uuid, currencyId, ledger.getBalance(uuid, currencyId));
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("RaskolVault: не удалось списать средства "
                    + uuid + "/" + currencyId + ": " + exception.getMessage());
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean transfer(UUID from, UUID to, String currencyId,
                            double amount, String reason) {
        return transferOnce(from, to, currencyId, amount, reason, UUID.randomUUID().toString());
    }

    /**
     * Переводит средства одной SQLite-транзакцией и защищает запрос от повторного выполнения.
     */
    public boolean transferOnce(UUID from, UUID to, String currencyId,
                                double amount, String reason, String operationId) {
        if (from == null || to == null || currencyId == null
                || !validAmount(amount) || from.equals(to)
                || operationId == null || operationId.isBlank()) {
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

            String fingerprint = operationFingerprint("TRANSFER", from, to,
                    currencyId, amount, TransactionType.PAY, reason);
            if (ledger.operationCommitted(operationId, fingerprint)) {
                setCache(from, currencyId, ledger.getBalance(from, currencyId));
                setCache(to, currencyId, ledger.getBalance(to, currencyId));
                return true;
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
            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteCheckedOnce(
                    operationId, fingerprint,
                    List.of(
                            new SQLiteLedger.CheckedBalance(from, currencyId, oldFrom, newFrom),
                            new SQLiteLedger.CheckedBalance(to, currencyId, oldTo, newTo)
                    ),
                    List.of(transaction)));
            if (!committed) {
                return false;
            }

            setCache(from, currencyId, ledger.getBalance(from, currencyId));
            setCache(to, currencyId, ledger.getBalance(to, currencyId));
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

    /**
     * Атомарно применяет несколько изменений внутренних кошельков и резервов наций.
     * GLOBAL/EssentialsX намеренно не поддерживается: его нельзя включить в SQLite-коммит.
     */
    public boolean applyBatchOnce(String operationId, List<WalletDelta> walletDeltas,
                                  List<ReserveDelta> reserveDeltas, List<Transaction> transactions) {
        return applyBatchOnce(operationId, walletDeltas, reserveDeltas, List.of(), transactions);
    }

    public boolean applyBatchOnce(String operationId, List<WalletDelta> walletDeltas,
                                  List<ReserveDelta> reserveDeltas,
                                  List<SQLiteLedger.CheckedEscrow> escrowChanges,
                                  List<Transaction> transactions) {
        return applyBatchOnce(operationId, walletDeltas, reserveDeltas, escrowChanges, null, transactions);
    }

    public boolean applyBatchOnce(String operationId, List<WalletDelta> walletDeltas,
                                  List<ReserveDelta> reserveDeltas,
                                  List<SQLiteLedger.CheckedEscrow> escrowChanges,
                                  SQLiteLedger.BankSnapshot bankSnapshot,
                                  List<Transaction> transactions) {
        return applyBatchOnce(operationId, null, walletDeltas, reserveDeltas,
                escrowChanges, bankSnapshot, transactions);
    }

    /**
     * Атомарно применяет пакет с отпечатком пользовательского намерения.
     * Он остаётся неизменным при повторе после перезапуска, даже если банковский snapshot
     * или временные метки уже изменились.
     */
    public boolean applyBatchOnce(String operationId, String requestFingerprint,
                                  List<WalletDelta> walletDeltas,
                                  List<ReserveDelta> reserveDeltas,
                                  List<SQLiteLedger.CheckedEscrow> escrowChanges,
                                  SQLiteLedger.BankSnapshot bankSnapshot,
                                  List<Transaction> transactions) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128
                || (requestFingerprint != null
                    && (requestFingerprint.isBlank() || requestFingerprint.length() > 512))
                || walletDeltas == null || reserveDeltas == null
                || escrowChanges == null || transactions == null) {
            return false;
        }

        Map<WalletKey, Double> normalizedWallets = new TreeMap<>(
                Comparator.comparing((WalletKey key) -> key.owner().toString())
                        .thenComparing(WalletKey::currencyId));
        for (WalletDelta delta : walletDeltas) {
            if (delta == null || delta.owner() == null || delta.currencyId() == null
                    || delta.currencyId().isBlank() || !Double.isFinite(delta.delta())
                    || currencies.globalId().equals(delta.currencyId())) {
                return false;
            }
            normalizedWallets.merge(new WalletKey(delta.owner(), delta.currencyId()),
                    delta.delta(), Double::sum);
        }

        for (double delta : normalizedWallets.values()) {
            if (!Double.isFinite(delta) || Math.abs(delta) > maxTx()) {
                return false;
            }
        }

        Map<String, Double> normalizedReserves = new TreeMap<>();
        for (ReserveDelta delta : reserveDeltas) {
            if (delta == null || delta.nation() == null || delta.nation().isBlank()
                    || !Double.isFinite(delta.delta())) {
                return false;
            }
            normalizedReserves.merge(delta.nation(), delta.delta(), Double::sum);
        }
        if (normalizedWallets.isEmpty() && normalizedReserves.isEmpty() && bankSnapshot == null
                && escrowChanges.isEmpty()) {
            return false;
        }

        String fingerprint = requestFingerprint == null
                ? batchFingerprint(normalizedWallets, normalizedReserves, escrowChanges, bankSnapshot, transactions)
                : requestFingerprint;
        Set<Integer> lockIndexes = new TreeSet<>();
        for (WalletKey key : normalizedWallets.keySet()) {
            lockIndexes.add(lockIndex(key.owner(), key.currencyId()));
        }
        List<ReentrantLock> acquired = new ArrayList<>();
        for (int index : lockIndexes) {
            ReentrantLock lock = walletLocks[index];
            lock.lock();
            acquired.add(lock);
        }

        try {
            if (ledger.operationCommitted(operationId, fingerprint)) {
                for (WalletKey key : normalizedWallets.keySet()) {
                    setCache(key.owner(), key.currencyId(),
                            ledger.getBalance(key.owner(), key.currencyId()));
                }
                return true;
            }

            List<SQLiteLedger.CheckedBalance> checkedBalances = new ArrayList<>();
            for (Map.Entry<WalletKey, Double> entry : normalizedWallets.entrySet()) {
                WalletKey key = entry.getKey();
                double old = getBalance(key.owner(), key.currencyId());
                double updated = old + entry.getValue();
                if (!Double.isFinite(updated) || updated < -1.0E-9D) {
                    return false;
                }
                checkedBalances.add(new SQLiteLedger.CheckedBalance(
                        key.owner(), key.currencyId(), old, Math.max(0.0D, updated)));
            }

            List<SQLiteLedger.CheckedReserve> checkedReserves = new ArrayList<>();
            for (Map.Entry<String, Double> entry : normalizedReserves.entrySet()) {
                double old = ledger.reserveGet(entry.getKey());
                double updated = old + entry.getValue();
                if (!Double.isFinite(updated) || updated < -1.0E-9D) {
                    return false;
                }
                checkedReserves.add(new SQLiteLedger.CheckedReserve(
                        entry.getKey(), old, Math.max(0.0D, updated)));
            }

            boolean committed = writer.submitSync(() -> ledger.commitAbsoluteCheckedOnce(
                    operationId, fingerprint, checkedBalances, checkedReserves, escrowChanges, bankSnapshot, transactions));
            if (!committed) {
                return false;
            }

            for (WalletKey key : normalizedWallets.keySet()) {
                setCache(key.owner(), key.currencyId(),
                        ledger.getBalance(key.owner(), key.currencyId()));
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("RaskolVault: пакетная финансовая операция не выполнена: "
                    + exception.getMessage());
            return false;
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
        }
    }

    /** Проверяет подтверждение запроса и синхронизирует кэш кошелька после повтора. */
    public boolean operationCommitted(String operationId, String fingerprint, UUID owner, String currencyId) {
        if (owner == null || currencyId == null || currencyId.isBlank()) return false;
        boolean committed = ledger.operationCommitted(operationId, fingerprint);
        if (committed && !currencies.globalId().equals(currencyId)) {
            setCache(owner, currencyId, ledger.getBalance(owner, currencyId));
        }
        return committed;
    }

    private String batchFingerprint(Map<WalletKey, Double> wallets,
                                    Map<String, Double> reserves,
                                    List<SQLiteLedger.CheckedEscrow> escrows,
                                    SQLiteLedger.BankSnapshot bankSnapshot,
                                    List<Transaction> transactions) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<WalletKey, Double> entry : wallets.entrySet()) {
            canonical.append(fingerprintPart("W"))
                    .append(fingerprintPart(entry.getKey().owner().toString()))
                    .append(fingerprintPart(entry.getKey().currencyId()))
                    .append(fingerprintPart(Double.toHexString(entry.getValue())));
        }
        for (Map.Entry<String, Double> entry : reserves.entrySet()) {
            canonical.append(fingerprintPart("R"))
                    .append(fingerprintPart(entry.getKey()))
                    .append(fingerprintPart(Double.toHexString(entry.getValue())));
        }
        for (SQLiteLedger.CheckedEscrow escrow : escrows) {
            if (escrow == null) {
                return "";
            }
            canonical.append(fingerprintPart("E"))
                    .append(fingerprintPart(escrow.ticket()))
                    .append(fingerprintPart(escrow.owner() == null ? "" : escrow.owner().toString()))
                    .append(fingerprintPart(escrow.currencyId()))
                    .append(fingerprintPart(Double.toHexString(escrow.expectedOld())))
                    .append(fingerprintPart(Double.toHexString(escrow.newAmount())))
                    .append(fingerprintPart(Boolean.toString(escrow.delete())));
        }
        if (bankSnapshot != null) {
            canonical.append(fingerprintPart("BANK_SNAPSHOT"));
            for (SQLiteLedger.BankAccountRow row : bankSnapshot.accounts()) {
                canonical.append(fingerprintPart("BA")).append(fingerprintPart(row.toString()));
            }
            for (SQLiteLedger.BankLoanRow row : bankSnapshot.loans()) {
                canonical.append(fingerprintPart("BL")).append(fingerprintPart(row.toString()));
            }
            for (SQLiteLedger.BankPoolRow row : bankSnapshot.pools()) {
                canonical.append(fingerprintPart("BP")).append(fingerprintPart(row.toString()));
            }
            for (SQLiteLedger.BankCreditRow row : bankSnapshot.credit()) {
                canonical.append(fingerprintPart("BC")).append(fingerprintPart(row.toString()));
            }
            for (SQLiteLedger.BankPendingRow row : bankSnapshot.pendingReturns()) {
                canonical.append(fingerprintPart("BR")).append(fingerprintPart(row.toString()));
            }
        }
        for (Transaction transaction : transactions) {
            if (transaction == null) {
                return "";
            }
            canonical.append(fingerprintPart("T"))
                    .append(fingerprintPart(transaction.from() == null ? "" : transaction.from().toString()))
                    .append(fingerprintPart(transaction.to() == null ? "" : transaction.to().toString()))
                    .append(fingerprintPart(transaction.currencyId()))
                    .append(fingerprintPart(Double.toHexString(transaction.amount())))
                    .append(fingerprintPart(transaction.type() == null ? "" : transaction.type().name()))
                    .append(fingerprintPart(safeReason(transaction.reason())))
                    .append(fingerprintPart(transaction.metadataJson() == null ? "" : transaction.metadataJson()));
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
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

    private String operationFingerprint(String operation, UUID from, UUID to,
                                         String currencyId, double amount,
                                         TransactionType type, String reason) {
        String canonical = fingerprintPart(operation)
                + fingerprintPart(from == null ? "" : from.toString())
                + fingerprintPart(to == null ? "" : to.toString())
                + fingerprintPart(currencyId)
                + fingerprintPart(Double.toHexString(amount))
                + fingerprintPart(type == null ? "" : type.name())
                + fingerprintPart(safeReason(reason));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private String fingerprintPart(String value) {
        return value.length() + ":" + value;
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
