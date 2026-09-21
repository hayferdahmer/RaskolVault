// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.observability.SparkHook;
import dev.raskol.vault.storage.LedgerWriter;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кошельки (1.1.3): + setCache + reconcile() для периодической сверки кэш↔леджер.
 */
public final class WalletService {

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final LedgerWriter writer;
    private final CurrencyRegistry currencies;
    private final EssentialsHook essentials;
    private final SparkHook spark;
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();

    public WalletService(Plugin plugin, SQLiteLedger ledger, LedgerWriter writer,
                         CurrencyRegistry currencies, EssentialsHook essentials,
                         long joinTimeoutMillis, SparkHook spark) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.writer = writer;
        this.currencies = currencies;
        this.essentials = essentials;
        this.spark = spark;
    }

    public void init() {
        for (Map.Entry<UUID, Map<String, Double>> e : ledger.loadAllBalances().entrySet()) {
            cache.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
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
        return getBalance(uuid, currencyId) >= amount - 1.0E-9D;
    }

    public Map<UUID, Map<String, Double>> cacheSnapshot() {
        Map<UUID, Map<String, Double>> out = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Double>> e : cache.entrySet()) {
            out.put(e.getKey(), new HashMap<>(e.getValue()));
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
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currencyId, value);
    }

    /**
     * Сверка кэш↔леджер: лечит расхождения >1e-6 и кэш-записи, отсутствующие в БД.
     * Возвращает число вылеченных ячеек. Глобальную валюту не трогаем (источник Essentials).
     */
    public int reconcile() {
        Map<UUID, Map<String, Double>> db = ledger.loadAllBalances();
        int healed = 0;
        String glb = currencies.globalId();
        for (Map.Entry<UUID, Map<String, Double>> e : db.entrySet()) {
            for (Map.Entry<String, Double> ce : e.getValue().entrySet()) {
                if (glb.equals(ce.getKey())) {
                    continue;
                }
                double cached = getBalance(e.getKey(), ce.getKey());
                if (Math.abs(cached - ce.getValue()) > 1.0E-6D) {
                    setCache(e.getKey(), ce.getKey(), ce.getValue());
                    healed++;
                }
            }
        }
        for (Map.Entry<UUID, Map<String, Double>> e : cache.entrySet()) {
            Map<String, Double> dbRow = db.get(e.getKey());
            for (Map.Entry<String, Double> ce : e.getValue().entrySet()) {
                if (glb.equals(ce.getKey())) {
                    continue;
                }
                if (dbRow == null || !dbRow.containsKey(ce.getKey())) {
                    setCache(e.getKey(), ce.getKey(), 0.0D);
                    healed++;
                }
            }
        }
        return healed;
    }

    public boolean deposit(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        if (!(amount > 0.0D)) {
            return false;
        }
        if (currencies.globalId().equals(currencyId)) {
            return essentials.isAvailable()
                    && essentials.setBalance(uuid, essentials.getBalance(uuid) + amount);
        }
        double old = getBalance(uuid, currencyId);
        double neu = old + amount;
        setCache(uuid, currencyId, neu);
        writer.submit(() -> ledger.commitAbsolute(
                java.util.List.of(new SQLiteLedger.AbsoluteBalance(uuid, currencyId, neu)),
                java.util.List.of(Transaction.of(uuid, null, currencyId, amount, type, reason))), ok -> {
            if (!ok) {
                setCache(uuid, currencyId, old);
            }
        });
        return true;
    }

    public boolean withdraw(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        if (!(amount > 0.0D)) {
            return false;
        }
        if (currencies.globalId().equals(currencyId)) {
            if (!essentials.isAvailable() || essentials.getBalance(uuid) < amount - 1.0E-9D) {
                return false;
            }
            return essentials.setBalance(uuid, essentials.getBalance(uuid) - amount);
        }
        double old = getBalance(uuid, currencyId);
        if (old < amount - 1.0E-9D) {
            return false;
        }
        double neu = old - amount;
        setCache(uuid, currencyId, neu);
        writer.submit(() -> ledger.commitAbsolute(
                java.util.List.of(new SQLiteLedger.AbsoluteBalance(uuid, currencyId, neu)),
                java.util.List.of(Transaction.of(uuid, null, currencyId, amount, type, reason))), ok -> {
            if (!ok) {
                setCache(uuid, currencyId, old);
            }
        });
        return true;
    }

    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        if (from.equals(to) || !(amount > 0.0D)) {
            return false;
        }
        if (currencies.globalId().equals(currencyId)) {
            if (!essentials.isAvailable()) {
                return false;
            }
            double f = essentials.getBalance(from);
            double t = essentials.getBalance(to);
            if (f < amount - 1.0E-9D) {
                return false;
            }
            return essentials.setBalance(from, f - amount) && essentials.setBalance(to, t + amount);
        }
        double oldF = getBalance(from, currencyId);
        if (oldF < amount - 1.0E-9D) {
            return false;
        }
        double oldT = getBalance(to, currencyId);
        double neuF = oldF - amount;
        double neuT = oldT + amount;
        setCache(from, currencyId, neuF);
        setCache(to, currencyId, neuT);
        writer.submit(() -> ledger.commitAbsolute(
                java.util.List.of(
                        new SQLiteLedger.AbsoluteBalance(from, currencyId, neuF),
                        new SQLiteLedger.AbsoluteBalance(to, currencyId, neuT)),
                java.util.List.of(Transaction.of(from, to, currencyId, amount, TransactionType.PAY, reason))), ok -> {
            if (!ok) {
                setCache(from, currencyId, oldF);
                setCache(to, currencyId, oldT);
            }
        });
        return true;
    }
}
