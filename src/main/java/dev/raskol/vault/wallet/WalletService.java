// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.api.wallet.Wallet;
import dev.raskol.vault.hook.EssentialsHook;
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
import java.util.function.Consumer;

/**
 * Кошельки игроков (1.0.3): проекция кэша на потоке вызова + асинхронный коммит.
 *
 * Поток вызова (main для команд) НЕ касается SQLite: под projectionLock делается
 * мгновенная математика кэша, затем атомарный коммит уходит в LedgerWriter.
 * Синхронный контракт (Core-хук, админки, казна) = тот же путь + join с таймаутом.
 *
 * GLOBAL (⚜): мутация Essentials на потоке вызова (in-memory, быстро),
 * в очередь уходит только аудиторская запись; при провале записи — компенсация Essentials.
 * NATIONAL/WORLD: абсолютное значение баланса уходит в коммит; кэш обновляется в проекции.
 *
 * Отказ записи: SEVERE с полными данными для ручного восстановления; кэш не откатывается
 * (откат затёр бы более поздние проекции). Счётчик failed — материал алертов 1.0.4.
 */
public final class WalletService {

    private static final double EPS = 1.0E-9D;

    private final Plugin plugin;
    private final SQLiteLedger ledger;
    private final LedgerWriter writer;
    private final CurrencyRegistry currencies;
    private final EssentialsHook essentials;
    private final Map<UUID, Map<String, Double>> cache = new ConcurrentHashMap<>();
    private final Object projectionLock = new Object();
    private final long joinTimeoutMillis;

    public WalletService(Plugin plugin, SQLiteLedger ledger, LedgerWriter writer,
                         CurrencyRegistry currencies, EssentialsHook essentials,
                         long joinTimeoutMillis) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.writer = writer;
        this.currencies = currencies;
        this.essentials = essentials;
        this.joinTimeoutMillis = Math.max(1000L, joinTimeoutMillis);
    }

    public void init() {
        for (Map.Entry<UUID, Map<String, Double>> entry : ledger.loadAllBalances().entrySet()) {
            cache.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
        }
        plugin.getLogger().info("RaskolVault: кэш кошельков прогрет, строк: " + cache.size());
    }

    // ---------- чтение (всегда быстрое: кэш или Essentials) ----------

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
            double old = essentials.getBalance(uuid);
            if (!essentials.setBalance(uuid, round(old + rounded, currency))) {
                cb.accept(false);
                return;
            }
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    essentials.setBalance(uuid, old);
                    plugin.getLogger().severe("RaskolVault: аудит не записан — откат Essentials для "
                            + uuid + " (" + reason + ", " + rounded + " " + currencyId + ")");
                }
                cb.accept(ok);
            });
            return;
        }
        double old;
        double neu;
        synchronized (projectionLock) {
            old = raw(uuid, currencyId);
            neu = round(old + rounded, currency);
            cachePut(uuid, currencyId, neu);
        }
        List<SQLiteLedger.AbsoluteBalance> writes =
                List.of(new SQLiteLedger.AbsoluteBalance(uuid, currencyId, neu));
        writer.submit(() -> ledger.commitAbsolute(writes, List.of(tx)), ok -> {
            if (!ok) {
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
            double old = essentials.getBalance(uuid);
            if (old + EPS < rounded) {
                cb.accept(false);
                return;
            }
            if (!essentials.setBalance(uuid, round(old - rounded, currency))) {
                cb.accept(false);
                return;
            }
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    essentials.setBalance(uuid, old);
                    plugin.getLogger().severe("RaskolVault: аудит не записан — откат Essentials для "
                            + uuid + " (" + reason + ", " + rounded + " " + currencyId + ")");
                }
                cb.accept(ok);
            });
            return;
        }
        double old;
        double neu;
        synchronized (projectionLock) {
            old = raw(uuid, currencyId);
            if (old + EPS < rounded) {
                cb.accept(false);
                return;
            }
            neu = round(old - rounded, currency);
            cachePut(uuid, currencyId, neu);
        }
        List<SQLiteLedger.AbsoluteBalance> writes =
                List.of(new SQLiteLedger.AbsoluteBalance(uuid, currencyId, neu));
        writer.submit(() -> ledger.commitAbsolute(writes, List.of(tx)), ok -> {
            if (!ok) {
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
            writer.submit(() -> ledger.commitAbsolute(List.of(), List.of(tx)), ok -> {
                if (!ok) {
                    essentials.setBalance(from, oldFrom);
                    essentials.setBalance(to, oldTo);
                    plugin.getLogger().severe("RaskolVault: аудит не записан — полный откат перевода "
                            + from + "→" + to + " (" + rounded + " " + currencyId + ")");
                }
                cb.accept(ok);
            });
            return;
        }
        double oldFrom;
        double oldTo;
        double neuFrom;
        double neuTo;
        synchronized (projectionLock) {
            oldFrom = raw(from, currencyId);
            if (oldFrom + EPS < rounded) {
                cb.accept(false);
                return;
            }
            oldTo = raw(to, currencyId);
            neuFrom = round(oldFrom - rounded, currency);
            neuTo = round(oldTo + rounded, currency);
            cachePut(from, currencyId, neuFrom);
            cachePut(to, currencyId, neuTo);
        }
        List<SQLiteLedger.AbsoluteBalance> writes = List.of(
                new SQLiteLedger.AbsoluteBalance(from, currencyId, neuFrom),
                new SQLiteLedger.AbsoluteBalance(to, currencyId, neuTo));
        writer.submit(() -> ledger.commitAbsolute(writes, List.of(tx)), ok -> {
            if (!ok) {
                logWriteLoss("transfer " + from + "→" + to, from, currencyId, rounded, oldFrom, neuFrom, reason);
            }
            cb.accept(ok);
        });
    }

    /** Обмен: две аудиторские записи + абсолютные балансы по неблобальным сторонам. */
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
        double oldFromGlobal = 0.0D;
        double oldToGlobal = 0.0D;

        if (fromGlobal) {
            if (!essentials.isAvailable()) {
                cb.accept(false);
                return;
            }
            oldFromGlobal = essentials.getBalance(owner);
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
                    essentials.setBalance(owner, oldFromGlobal);
                }
                cb.accept(false);
                return;
            }
            oldToGlobal = essentials.getBalance(owner);
            if (!essentials.setBalance(owner, round(oldToGlobal + toAmount, to))) {
                if (fromGlobal) {
                    essentials.setBalance(owner, oldFromGlobal);
                }
                cb.accept(false);
                return;
            }
        }

        List<SQLiteLedger.AbsoluteBalance> writes = new ArrayList<>(2);
        double oldFromCache = 0.0D;
        double neuFromCache = 0.0D;
        synchronized (projectionLock) {
            if (!fromGlobal) {
                oldFromCache = raw(owner, fromId);
                if (oldFromCache + EPS < fromAmount) {
                    compensateGlobal(fromGlobal, toGlobal, owner, oldFromGlobal, oldToGlobal, from, to, fromAmount, toAmount);
                    cb.accept(false);
                    return;
                }
                neuFromCache = round(oldFromCache - fromAmount, from);
                cachePut(owner, fromId, neuFromCache);
                writes.add(new SQLiteLedger.AbsoluteBalance(owner, fromId, neuFromCache));
            }
            if (!toGlobal) {
                double oldToCache = raw(owner, toId);
                double neuToCache = round(oldToCache + toAmount, to);
                cachePut(owner, toId, neuToCache);
                writes.add(new SQLiteLedger.AbsoluteBalance(owner, toId, neuToCache));
            }
        }
        
        // ФИКС: создаём final-копии для использования в лямбде
        final double finalOldFromGlobal = oldFromGlobal;
        final double finalOldToGlobal = oldToGlobal;
        final double finalOldFromCache = oldFromCache;
        final double finalNeuFromCache = neuFromCache;
        
        writer.submit(() -> ledger.commitAbsolute(writes, txs), ok -> {
            if (!ok) {
                compensateGlobal(fromGlobal, toGlobal, owner, finalOldFromGlobal, finalOldToGlobal, from, to, fromAmount, toAmount);
                logWriteLoss("convert", owner, fromId, fromAmount, finalOldFromCache, finalNeuFromCache, reason);
            }
            cb.accept(ok);
        });
    }

    // ---------- sync API (Core-хук, админки, казна нации) ----------

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

    // ---------- служебное ----------

    private void compensateGlobal(boolean fromGlobal, boolean toGlobal, UUID owner,
                                  double oldFromGlobal, double oldToGlobal,
                                  Currency from, Currency to, double fromAmount, double toAmount) {
        if (toGlobal) {
            essentials.setBalance(owner, oldToGlobal);
        }
        if (fromGlobal) {
            essentials.setBalance(owner, oldFromGlobal);
        }
    }

    private void logWriteLoss(String op, UUID uuid, String currencyId, double amount,
                              double oldBalance, double newBalance, String reason) {
        plugin.getLogger().severe("RaskolVault: ПОТЕРЯ ЗАПИСИ (восстановить вручную): op=" + op
                + ", uuid=" + uuid + ", currency=" + currencyId + ", amount=" + amount
                + ", old=" + oldBalance + ", new=" + newBalance + ", reason=" + reason);
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
