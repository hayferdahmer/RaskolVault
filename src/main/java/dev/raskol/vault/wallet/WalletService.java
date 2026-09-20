// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.api.wallet.Wallet;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.observability.SparkHook;
import dev.raskol.vault.storage.LedgerWriter;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Кошельки игроков (1.0.3 async + 1.0.4 observability + 1.0.5 anti-dupe).
 *
 * 1.0.5:
 *  - мутации GLOBAL-валюты (чтение+запись Essentials) теперь под projectionLock —
 *    закрыта гонка lost-update между параллельными вызовами;
 *  - неблобальные коммиты идут через commitAbsoluteChecked (оптимистичная блокировка);
 *    при её срабатывании кэш лечится чтением из БД (heal), операция отклоняется;
 *  - счётчики cache hit/miss и Spark-тайминги сохранены из 1.0.4.
 */
public final class WalletService {

    private static final double EPS = 1.0E-9D;

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final LedgerWriter writer;
    private final CurrencyRegistry currencies;
    private final EssentialsHook essentials;
    private final SparkHook spark;
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();
    private final Object projectionLock = new Object();
    private final long joinTimeoutMillis;

    private final AtomicLong cacheHits = new AtomicLong(0L);
    private final AtomicLong cacheMisses = new AtomicLong(0L);

    public WalletService(Plugin plugin, SQLiteLedger ledger, LedgerWriter writer,
                         CurrencyRegistry currencies, EssentialsHook essentials,
                         long joinTimeoutMillis, SparkHook spark) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.writer = writer;
        this.currencies = currencies;
        this.essentials = essentials;
        this.joinTimeoutMillis = Math.max(1000L, joinTimeoutMillis);
        this.spark = spark;
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
        if (row == null || !row.containsKey(currencyId)) {
            cacheMisses.incrementAndGet();
            return 0.0D;
        }
        cacheHits.incrementAndGet();
        return row.get(currencyId);
    }

    public boolean has(UUID uuid, String currencyId, double amount) {
        return getBalance(uuid, currencyId) + EPS >= amount;
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

    public long cacheHits() {
        return cacheHits.get();
    }

    public long cacheMisses() {
        return cacheMisses.get();
    }

    public double cacheHitRate() {
        long h = cacheHits.get();
        long m = cacheMisses.get();
        long total = h + m;
        return total == 0 ? 100.0D : (100.0D * h / total);
    }

    // ---------- async API ----------

    public void depositAsync(UUID uuid, String currencyId, double amount,
                             TransactionType type, String reason, Consumer<Boolean> cb) {
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            cb.accept(false);
            return;
        }
        double rounded = round(amount, currency);
        Transaction tx = Transaction.fresh(System.currentTimeMillis(),
                null, uuid, currencyId, rounded, type, reason, null);
        if (currency.isGlobal()) {
            if (!essentials.isAvailable()) {
                cb.accept(false);
                return;
            }
            // 1.0.5: чтение+запись Essentials атомарно под локом
            synchronized (projectionLock) {
                double old = essentials.getBalance(uuid);
                if (!essentials.setBalance(uuid, round(old + rounded, currency))) {
                    cb.accept(false);
                    return;
                }
            }
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан для "
                            + uuid + " (" + reason + ", " + rounded + " " + currencyId + ") — "
                            + "баланс Essentials уже изменён, сверь вручную");
                }
                cb.accept(ok);
            });
            return;
        }
        final double[] projected = new double[2];
        try (AutoCloseable ignored = spark.time("deposit.projection")) {
            synchronized (projectionLock) {
                projected[0] = raw(uuid, currencyId);
                projected[1] = round(projected[0] + rounded, currency);
                cachePut(uuid, currencyId, projected[1]);
            }
        } catch (Exception ignored) {
        }
        final double old = projected[0];
        final double neu = projected[1];
        List<SQLiteLedger.CheckedBalance> writes = List.of(
                new SQLiteLedger.CheckedBalance(uuid, currencyId, old, neu));
        writer.submit(() -> ledger.commitAbsoluteChecked(writes, List.of(tx)), ok -> {
            if (!ok) {
                heal(uuid, currencyId);
                logWriteLoss("deposit", uuid, currencyId, rounded, old, neu, reason);
            }
            cb.accept(ok);
        });
    }

    public void withdrawAsync(UUID uuid, String currencyId, double amount,
                              TransactionType type, String reason, Consumer<Boolean> cb) {
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            cb.accept(false);
            return;
        }
        double rounded = round(amount, currency);
        Transaction tx = Transaction.fresh(System.currentTimeMillis(),
                uuid, null, currencyId, rounded, type, reason, null);
        if (currency.isGlobal()) {
            if (!essentials.isAvailable()) {
                cb.accept(false);
                return;
            }
            synchronized (projectionLock) {
                double old = essentials.getBalance(uuid);
                if (old + EPS < rounded) {
                    cb.accept(false);
                    return;
                }
                if (!essentials.setBalance(uuid, round(old - rounded, currency))) {
                    cb.accept(false);
                    return;
                }
            }
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан для "
                            + uuid + " (" + reason + ", " + rounded + " " + currencyId + ") — "
                            + "баланс Essentials уже изменён, сверь вручную");
                }
                cb.accept(ok);
            });
            return;
        }
        final double[] projected = new double[2];
        final boolean[] insufficient = new boolean[1];
        try (AutoCloseable ignored = spark.time("withdraw.projection")) {
            synchronized (projectionLock) {
                projected[0] = raw(uuid, currencyId);
                if (projected[0] + EPS < rounded) {
                    insufficient[0] = true;
                    return;
                }
                projected[1] = round(projected[0] - rounded, currency);
                cachePut(uuid, currencyId, projected[1]);
            }
        } catch (Exception ignored) {
        }
        if (insufficient[0]) {
            cb.accept(false);
            return;
        }
        final double old = projected[0];
        final double neu = projected[1];
        List<SQLiteLedger.CheckedBalance> writes = List.of(
                new SQLiteLedger.CheckedBalance(uuid, currencyId, old, neu));
        writer.submit(() -> ledger.commitAbsoluteChecked(writes, List.of(tx)), ok -> {
            if (!ok) {
                heal(uuid, currencyId);
                logWriteLoss("withdraw", uuid, currencyId, rounded, old, neu, reason);
            }
            cb.accept(ok);
        });
    }

    public void transferAsync(UUID from, UUID to, String currencyId, double amount,
                              String reason, Consumer<Boolean> cb) {
        if (from.equals(to)) {
            cb.accept(false);
            return;
        }
        Currency currency = currencies.get(currencyId).orElse(null);
        if (currency == null || !(amount > 0.0D)) {
            cb.accept(false);
            return;
        }
        double rounded = round(amount, currency);
        Transaction tx = Transaction.fresh(System.currentTimeMillis(),
                from, to, currencyId, rounded, TransactionType.PAY, reason, null);
        if (currency.isGlobal()) {
            if (!essentials.isAvailable()) {
                cb.accept(false);
                return;
            }
            synchronized (projectionLock) {
                double oldFrom = essentials.getBalance(from);
                if (oldFrom + EPS < rounded) {
                    cb.accept(false);
                    return;
                }
                double oldTo = essentials.getBalance(to);
                if (!essentials.setBalance(from, round(oldFrom - rounded, currency))) {
                    cb.accept(false);
                    return;
                }
                if (!essentials.setBalance(to, round(oldTo + rounded, currency))) {
                    essentials.setBalance(from, oldFrom);
                    cb.accept(false);
                    return;
                }
            }
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    plugin.getLogger().severe("RaskolVault: аудит не записан для перевода "
                            + from + "→" + to + " (" + rounded + " " + currencyId + ") — "
                            + "балансы Essentials уже изменены, сверь вручную");
                }
                cb.accept(ok);
            });
            return;
        }
        final double[] projected = new double[4];
        final boolean[] insufficient = new boolean[1];
        try (AutoCloseable ignored = spark.time("transfer.projection")) {
            synchronized (projectionLock) {
                projected[0] = raw(from, currencyId);
                if (projected[0] + EPS < rounded) {
                    insufficient[0] = true;
                    return;
                }
                projected[1] = raw(to, currencyId);
                projected[2] = round(projected[0] - rounded, currency);
                projected[3] = round(projected[1] + rounded, currency);
                cachePut(from, currencyId, projected[2]);
                cachePut(to, currencyId, projected[3]);
            }
        } catch (Exception ignored) {
        }
        if (insufficient[0]) {
            cb.accept(false);
            return;
        }
        final double oldFrom = projected[0];
        final double oldTo = projected[1];
        final double neuFrom = projected[2];
        final double neuTo = projected[3];
        List<SQLiteLedger.CheckedBalance> writes = List.of(
                new SQLiteLedger.CheckedBalance(from, currencyId, oldFrom, neuFrom),
                new SQLiteLedger.CheckedBalance(to, currencyId, oldTo, neuTo));
        writer.submit(() -> ledger.commitAbsoluteChecked(writes, List.of(tx)), ok -> {
            if (!ok) {
                heal(from, currencyId);
                heal(to, currencyId);
                logWriteLoss("transfer " + from + "→" + to, from, currencyId, rounded, oldFrom, neuFrom, reason);
            }
            cb.accept(ok);
        });
    }

    public void exchangeAsync(UUID owner, String fromId, String toId,
                              double fromAmount, double toAmount, String reason,
                              Consumer<Boolean> cb) {
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null || from.id().equals(to.id())
                || !(fromAmount > 0.0D) || !(toAmount > 0.0D)) {
            cb.accept(false);
            return;
        }
        Transaction txWithdraw = Transaction.fresh(System.currentTimeMillis(),
                owner, null, fromId, fromAmount, TransactionType.CONVERT, reason + ":withdraw", null);
        Transaction txDeposit = Transaction.fresh(System.currentTimeMillis(),
                null, owner, toId, toAmount, TransactionType.CONVERT, reason + ":deposit", null);
        List<Transaction> txs = List.of(txWithdraw, txDeposit);

        boolean fromGlobal = from.isGlobal();
        boolean toGlobal = to.isGlobal();

        // 1.0.5: все мутации Essentials под локом, до проекции кэша
        synchronized (projectionLock) {
            if (fromGlobal) {
                if (!essentials.isAvailable()) {
                    cb.accept(false);
                    return;
                }
                double oldFromGlobal = essentials.getBalance(owner);
                if (oldFromGlobal + EPS < fromAmount) {
                    cb.accept(false);
                    return;
                }
                if (!essentials.setBalance(owner, round(oldFromGlobal - fromAmount, from))) {
                    cb.accept(false);
                    return;
                }
            }
            if (toGlobal) {
                if (!essentials.isAvailable()) {
                    if (fromGlobal) {
                        essentials.setBalance(owner, essentials.getBalance(owner) + fromAmount);
                    }
                    cb.accept(false);
                    return;
                }
                double oldToGlobal = essentials.getBalance(owner);
                if (!essentials.setBalance(owner, round(oldToGlobal + toAmount, to))) {
                    if (fromGlobal) {
                        essentials.setBalance(owner, essentials.getBalance(owner) + fromAmount);
                    }
                    cb.accept(false);
                    return;
                }
            }
        }

        List<SQLiteLedger.CheckedBalance> writes = new ArrayList<>(2);
        double oldFromCache = 0.0D;
        double neuFromCache = 0.0D;
        try (AutoCloseable ignored = spark.time("convert.projection")) {
            synchronized (projectionLock) {
                if (!fromGlobal) {
                    oldFromCache = raw(owner, fromId);
                    if (oldFromCache + EPS < fromAmount) {
                        compensateGlobal(fromGlobal, toGlobal, owner, from, to, fromAmount, toAmount);
                        cb.accept(false);
                        return;
                    }
                    neuFromCache = round(oldFromCache - fromAmount, from);
                    cachePut(owner, fromId, neuFromCache);
                    writes.add(new SQLiteLedger.CheckedBalance(owner, fromId, oldFromCache, neuFromCache));
                }
                if (!toGlobal) {
                    double oldToCache = raw(owner, toId);
                    double neuToCache = round(oldToCache + toAmount, to);
                    cachePut(owner, toId, neuToCache);
                    writes.add(new SQLiteLedger.CheckedBalance(owner, toId, oldToCache, neuToCache));
                }
            }
        } catch (Exception ignored) {
        }

        final double finalOldFromCache = oldFromCache;
        final double finalNeuFromCache = neuFromCache;
        final String finalFromId = fromId;
        final String finalToId = toId;
        final boolean fFromGlobal = fromGlobal;
        final boolean fToGlobal = toGlobal;

        writer.submit(() -> ledger.commitAbsoluteChecked(writes, txs), ok -> {
            if (!ok) {
                if (!fFromGlobal) {
                    heal(owner, finalFromId);
                }
                if (!fToGlobal) {
                    heal(owner, finalToId);
                }
                compensateGlobal(fFromGlobal, fToGlobal, owner, from, to, fromAmount, toAmount);
                logWriteLoss("convert", owner, finalFromId, fromAmount, finalOldFromCache, finalNeuFromCache, reason);
            }
            cb.accept(ok);
        });
    }

    // ---------- sync API ----------

    public boolean deposit(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        return join(cb -> depositAsync(uuid, currencyId, amount, type, reason, cb));
    }

    public boolean withdraw(UUID uuid, String currencyId, double amount, TransactionType type, String reason) {
        return join(cb -> withdrawAsync(uuid, currencyId, amount, type, reason, cb));
    }

    public boolean transfer(UUID from, UUID to, String currencyId, double amount, String reason) {
        return join(cb -> transferAsync(from, to, currencyId, amount, reason, cb));
    }

    public boolean exchange(UUID owner, String fromId, String toId,
                            double fromAmount, double toAmount, String reason) {
        return join(cb -> exchangeAsync(owner, fromId, toId, fromAmount, toAmount, reason, cb));
    }

    private boolean join(Consumer<Consumer<Boolean>> launcher) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        launcher.accept(future::complete);
        try {
            return future.get(joinTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 1.0.5: лечение кэша после отказа оптимистичной блокировки:
     * перечитываем строку из БД и возвращаем кэш к источнику правды.
     */
    private void heal(UUID uuid, String currencyId) {
        try {
            double dbValue = ledger.getBalance(uuid, currencyId);
            cachePut(uuid, currencyId, dbValue);
        } catch (Exception ignored) {
        }
    }

    private void compensateGlobal(boolean fromGlobal, boolean toGlobal, UUID owner,
                                  Currency from, Currency to, double fromAmount, double toAmount) {
        synchronized (projectionLock) {
            if (toGlobal && essentials.isAvailable()) {
                essentials.setBalance(owner, round(essentials.getBalance(owner) - toAmount, to));
            }
            if (fromGlobal && essentials.isAvailable()) {
                essentials.setBalance(owner, round(essentials.getBalance(owner) + fromAmount, from));
            }
        }
    }

    private void logWriteLoss(String op, UUID uuid, String currencyId, double amount,
                              double oldBalance, double newBalance, String reason) {
        plugin.getLogger().severe("RaskolVault: ОТКАЗ КОММИТА (кэш вылечен из БД): op=" + op
                + ", uuid=" + uuid + ", currency=" + currencyId + ", amount=" + amount
                + ", projectedOld=" + oldBalance + ", projectedNew=" + newBalance + ", reason=" + reason
                + " — проверь БД на ручные правки/restore");
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
