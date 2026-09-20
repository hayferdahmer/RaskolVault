// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.api.wallet.Wallet;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.storage.LedgerException;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кошельки игроков (1.0.2).
 *
 * Двойная природа балансов:
 * - GLOBAL (⚜): источник правды — Essentials; в леджер пишется ТОЛЬКО аудит.
 *   Если аудит-коммит провален — компенсационный откат Essentials (деньги не движутся без записи).
 * - NATIONAL/WORLD: источник правды — леджер; баланс+аудит одной SQL-транзакцией.
 *
 * Кэш неблобальных балансов обновляется ТОЛЬКО после успешного коммита.
 * Все мутации под монитором сервиса: атомарность бизнес-операции важнее параллельности.
 */
public final class WalletService {

    private static final double EPS = 1.0E-9D;

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final CurrencyRegistry currencies;
    private final EssentialsHook essentials;
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();

    public WalletService(Plugin plugin, SQLiteLedger ledger,
                         CurrencyRegistry currencies, EssentialsHook essentials) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.currencies = currencies;
        this.essentials = essentials;
    }

    public void init() {
        for (Map.Entry<UUID, Map<String, Double>> entry : ledger.loadAllBalances().entrySet()) {
            cache.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
        }
        plugin.getLogger().info("RaskolVault: кэш кошельков прогрет, строк: " + cache.size());
    }

    public double getBalance(UUID uuid, String currencyId) {
        if (currencies.globalId().equals(currencyId)) {
            return essentials.isAvailable() ? essentials.getBalance(uuid) : 0.0D;
        }
        Map<String, Double> row = cache.get(uuid);
        return row == null ? 0.0D : row.getOrDefault(currencyId, 0.0D);
    }

    public boolean has(UUID uuid, String currencyId, double amount) {
        return getBalance(uuid, currencyId) + EPS >= amount;
    }

    public boolean deposit(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            return false;
        }
        double rounded = round(amount, currency);
        synchronized (this) {
            if (currency.isGlobal()) {
                if (!essentials.isAvailable()) {
                    return false;
                }
                double old = essentials.getBalance(uuid);
                if (!essentials.setBalance(uuid, round(old + rounded, currency))) {
                    return false;
                }
                try {
                    ledger.commitTransaction(Transaction.fresh(System.currentTimeMillis(),
                            null, uuid, currencyId, rounded, type, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан — компенсационный откат Essentials для "
                            + uuid + ": " + e.getMessage());
                    essentials.setBalance(uuid, old);
                    return false;
                }
            } else {
                double neu = round(raw(uuid, currencyId) + rounded, currency);
                try {
                    ledger.commitBalanceAndTransaction(uuid, currencyId, neu,
                            Transaction.fresh(System.currentTimeMillis(),
                                    null, uuid, currencyId, rounded, type, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: депозит отклонён (коммит провален) для "
                            + uuid + "/" + currencyId + ": " + e.getMessage());
                    return false;
                }
                cachePut(uuid, currencyId, neu);
            }
        }
        return true;
    }

    public boolean withdraw(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            return false;
        }
        double rounded = round(amount, currency);
        synchronized (this) {
            double old = getBalance(uuid, currencyId);
            if (old + EPS < rounded) {
                return false;
            }
            if (currency.isGlobal()) {
                if (!essentials.isAvailable()) {
                    return false;
                }
                if (!essentials.setBalance(uuid, round(old - rounded, currency))) {
                    return false;
                }
                try {
                    ledger.commitTransaction(Transaction.fresh(System.currentTimeMillis(),
                            uuid, null, currencyId, rounded, type, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан — компенсационный откат Essentials для "
                            + uuid + ": " + e.getMessage());
                    essentials.setBalance(uuid, old);
                    return false;
                }
            } else {
                double neu = round(old - rounded, currency);
                try {
                    ledger.commitBalanceAndTransaction(uuid, currencyId, neu,
                            Transaction.fresh(System.currentTimeMillis(),
                                    uuid, null, currencyId, rounded, type, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: снятие отклонено (коммит провален) для "
                            + uuid + "/" + currencyId + ": " + e.getMessage());
                    return false;
                }
                cachePut(uuid, currencyId, neu);
            }
        }
        return true;
    }

    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        if (from.equals(to)) {
            return false;
        }
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            return false;
        }
        double rounded = round(amount, currency);
        synchronized (this) {
            double oldFrom = getBalance(from, currencyId);
            if (oldFrom + EPS < rounded) {
                return false;
            }
            double oldTo = getBalance(to, currencyId);
            if (currency.isGlobal()) {
                if (!essentials.isAvailable()) {
                    return false;
                }
                if (!essentials.setBalance(from, round(oldFrom - rounded, currency))) {
                    return false;
                }
                if (!essentials.setBalance(to, round(oldTo + rounded, currency))) {
                    plugin.getLogger().severe("RaskolVault: перевод оборван на зачислении — откат списания для " + from);
                    essentials.setBalance(from, oldFrom);
                    return false;
                }
                try {
                    ledger.commitTransaction(Transaction.fresh(System.currentTimeMillis(),
                            from, to, currencyId, rounded, TransactionType.PAY, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан — полный откат перевода "
                            + from + "→" + to + ": " + e.getMessage());
                    essentials.setBalance(from, oldFrom);
                    essentials.setBalance(to, oldTo);
                    return false;
                }
            } else {
                double neuFrom = round(oldFrom - rounded, currency);
                double neuTo = round(oldTo + rounded, currency);
                try {
                    ledger.commitTransferAndTransaction(from, to, currencyId, neuFrom, neuTo,
                            Transaction.fresh(System.currentTimeMillis(),
                                    from, to, currencyId, rounded, TransactionType.PAY, reason, null));
                } catch (LedgerException e) {
                    plugin.getLogger().severe("RaskolVault: перевод отклонён (коммит провален) "
                            + from + "→" + to + "/" + currencyId + ": " + e.getMessage());
                    return false;
                }
                cachePut(from, currencyId, neuFrom);
                cachePut(to, currencyId, neuTo);
            }
        }
        return true;
    }

    public Wallet snapshot(UUID uuid) {
        Map<String, Double> balances = new HashMap<>();
        for (Currency currency : currencies.all()) {
            balances.put(currency.id(), getBalance(uuid, currency.id()));
        }
        return new Wallet(uuid, balances);
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

    private double raw(UUID uuid, String currencyId) {
        Map<String, Double> row = cache.get(uuid);
        return row == null ? 0.0D : row.getOrDefault(currencyId, 0.0D);
    }

    private void cachePut(UUID uuid, String currencyId, double value) {
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currencyId, value);
    }

    private double round(double value, Currency currency) {
        return BigDecimal.valueOf(value)
                .setScale(currency.decimals(), RoundingMode.HALF_EVEN)
                .doubleValue();
    }
}
