// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.wallet;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.api.wallet.Wallet;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.hook.EssentialsHook;
import dev.raskol.vault.storage.SQLiteLedger;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кошельки игроков.
 *
 * Двойная природа балансов:
 * - GLOBAL (⚜): источник правды — Essentials; здесь только чтение/запись через хук
 *   и запись транзакции в леджер. Кэш не ведём, рассинхрон невозможен по построению.
 * - NATIONAL/WORLD: источник правды — наш леджер; оперативный кэш греется на старте,
 *   каждая мутация пишется в SQLite синхронно (до async-оптимизаций этапа 5).
 *
 * Все мутации под одним монитором сервиса: объёмы пре-лаунча малы, а атомарность
 * «списал-начислил-записал транзакцию» важнее параллельности.
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

    /** Прогрев кэша неблобальными балансами из леджера. */
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

    /**
     * Хватает ли средств у игрока (с эпсилон-допуском от ошибок double-арифметики).
     * Нужна ExchangeService для проверки возможности обмена ДО списания.
     */
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
            } else {
                write(uuid, currencyId, raw(uuid, currencyId) + rounded);
            }
            ledger.recordTransaction(Transaction.fresh(System.currentTimeMillis(),
                    null, uuid, currencyId, rounded, type, reason, null));
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
            } else {
                write(uuid, currencyId, old - rounded);
            }
            ledger.recordTransaction(Transaction.fresh(System.currentTimeMillis(),
                    uuid, null, currencyId, rounded, type, reason, null));
        }
        return true;
    }

    /** Атомарный перевод: одна транзакция PAY с from и to. */
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
                    return false;
                }
            } else {
                write(from, currencyId, oldFrom - rounded);
                write(to, currencyId, oldTo + rounded);
            }
            ledger.recordTransaction(Transaction.fresh(System.currentTimeMillis(),
                    from, to, currencyId, rounded, TransactionType.PAY, reason, null));
        }
        return true;
    }

    /** Снимок для команд и PAPI: все валюты реестра. */
    public Wallet snapshot(UUID uuid) {
        Map<String, Double> balances = new HashMap<>();
        for (Currency currency : currencies.all()) {
            balances.put(currency.id(), getBalance(uuid, currency.id()));
        }
        return new Wallet(uuid, balances);
    }

    /** Глубокая копия неблобального кэша для yaml-бекапа на выключении. */
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

    private void write(UUID uuid, String currencyId, double value) {
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currencyId, value);
        ledger.setBalance(uuid, currencyId, value);
    }

    private double round(double value, Currency currency) {
        return BigDecimal.valueOf(value)
                .setScale(currency.decimals(), RoundingMode.HALF_EVEN)
                .doubleValue();
    }
}
