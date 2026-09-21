// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.reserve;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Валютный совет (1.1.4): + TTL-кэш цен/покрытия/сапплая (5 с) —
 * PAPI и GUI становятся O(1) при частых запросах.
 */
public final class ReserveBank {

    public record Advice(String title, List<String> lore) {
    }

    private static final long CACHE_TTL_MS = 5000L;

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    // TTL-кэш: ключ → вычисленное значение, ключ → expiry (ms)
    private final Map<String, Double> cacheValues = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheExpiry = new ConcurrentHashMap<>();

    public ReserveBank(RaskolVault plugin, WalletService wallets,
                       CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    private double cached(String key, DoubleSupplier compute) {
        long now = System.currentTimeMillis();
        Long exp = cacheExpiry.get(key);
        if (exp != null && now < exp) {
            Double v = cacheValues.get(key);
            if (v != null) {
                return v;
            }
        }
        double v = compute.getAsDouble();
        cacheValues.put(key, v);
        cacheExpiry.put(key, now + CACHE_TTL_MS);
        return v;
    }

    /** Инвалидация кэша после мутаций резерва/паритета/налога. */
    public void invalidateCache() {
        cacheExpiry.clear();
        cacheValues.clear();
    }

    public static UUID reserveUuid(String nation) {
        return UUID.nameUUIDFromBytes(
                ("reserve:" + nation.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID treasuryUuid(String nation) {
        return UUID.nameUUIDFromBytes(
                ("treasury:" + nation.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public double reserveOf(String nation) {
        return cached("res:" + nation, () -> ledger.reserveGet(nation));
    }

    public boolean reserveCredit(String nation, double gold) {
        boolean ok = gold <= 0.0D || ledger.reserveAdd(nation, gold);
        if (ok) invalidateCache();
        return ok;
    }

    public boolean reserveDebit(String nation, double gold) {
        boolean ok = gold <= 0.0D || ledger.reserveAdd(nation, -gold);
        if (ok) invalidateCache();
        return ok;
    }

    public void reserveSet(String nation, double amount) {
        ledger.reserveSet(nation, amount);
        invalidateCache();
    }

    public boolean depositToReserve(UUID player, String nation, double amount, String reason) {
        if (!(amount > 0.0D)) {
            return false;
        }
        String glb = currencies.globalId();
        if (!wallets.has(player, glb, amount)) {
            return false;
        }
        if (!wallets.withdraw(player, glb, amount, TransactionType.PAY, "reserve:deposit:" + reason)) {
            return false;
        }
        if (!ledger.reserveAdd(nation, amount)) {
            wallets.deposit(player, glb, amount, TransactionType.PAY, "reserve:deposit:rollback");
            return false;
        }
        invalidateCache();
        return true;
    }

    public boolean withdrawFromReserve(UUID player, String nation, double amount, String reason) {
        if (!(amount > 0.0D) || amount > dailyWithdrawLimit(nation) + 1.0E-9D) {
            return false;
        }
        if (!ledger.reserveAdd(nation, -amount)) {
            return false;
        }
        String glb = currencies.globalId();
        if (!wallets.deposit(player, glb, amount, TransactionType.PAY, "reserve:withdraw:" + reason)) {
            ledger.reserveAdd(nation, amount);
            return false;
        }
        invalidateCache();
        return true;
    }

    public boolean mintToTreasury(String nation, Currency currency, double amount) {
        if (!(amount > 0.0D) || !canMint(nation, currency.id(), amount)) {
            return false;
        }
        double seigniorage = seigniorageRate();
        double fee = round2dec(amount * seigniorage, currency);
        double net = round2dec(amount - fee, currency);
        UUID treasury = treasuryUuid(nation);
        if (!wallets.deposit(treasury, currency.id(), net, TransactionType.MINT, "mint:" + nation)) {
            return false;
        }
        if (fee > 0.0D) {
            wallets.withdraw(treasury, currency.id(), fee, TransactionType.BURN, "seigniorage:" + nation);
        }
        invalidateCache();
        return true;
    }

    public boolean burnFromTreasury(String nation, Currency currency, double amount) {
        if (!(amount > 0.0D)) {
            return false;
        }
        boolean ok = wallets.withdraw(treasuryUuid(nation), currency.id(), amount,
                TransactionType.BURN, "burn:" + nation);
        if (ok) invalidateCache();
        return ok;
    }

    public double seigniorageRate() {
        return plugin.getConfig().getDouble("reserve.seigniorage", 0.02D);
    }

    public double supplyOf(String currencyId) {
        return cached("sup:" + currencyId, () -> {
            double sum = 0.0D;
            for (var row : wallets.cacheSnapshot().values()) {
                sum += row.getOrDefault(currencyId, 0.0D);
            }
            return sum;
        });
    }

    public double parityOf(String nation) {
        return plugin.getConfig().getDouble("reserve.parity." + nation.toLowerCase(Locale.ROOT), 1.0D);
    }

    public double taxOf(String nation) {
        return plugin.getConfig().getDouble("reserve.tax." + nation.toLowerCase(Locale.ROOT), 0.0D);
    }

    public double coverageFloor() {
        return plugin.getConfig().getDouble("reserve.coverage-floor", 0.5D);
    }

    private double parityMin() {
        return plugin.getConfig().getDouble("reserve.parity-min", 0.5D);
    }

    private double parityMax() {
        return plugin.getConfig().getDouble("reserve.parity-max", 2.0D);
    }

    private double taxMax() {
        return plugin.getConfig().getDouble("reserve.tax-max", 0.05D);
    }

    public double priceOf(Currency currency) {
        if (currency.type() == CurrencyType.GLOBAL) {
            return 1.0D;
        }
        return cached("price:" + currency.id(), () -> {
            double supply = supplyOf(currency.id());
            double parity = parityOf(currency.nationId());
            if (supply <= 0.0D) {
                return parity;
            }
            return Math.min(parity, reserveOf(currency.nationId()) / supply);
        });
    }

    public double coverageOf(String nation, String currencyId) {
        return cached("cov:" + nation + ":" + currencyId, () -> {
            double supply = supplyOf(currencyId);
            if (supply <= 0.0D) {
                return 1.0D;
            }
            return reserveOf(nation) / (supply * parityOf(nation));
        });
    }

    public boolean setParity(String nation, double value) {
        if (value < parityMin() || value > parityMax()) {
            return false;
        }
        plugin.getConfig().set("reserve.parity." + nation.toLowerCase(Locale.ROOT), round2(value));
        plugin.saveConfig();
        invalidateCache();
        return true;
    }

    public boolean setTax(String nation, double value) {
        if (value < 0.0D || value > taxMax()) {
            return false;
        }
        plugin.getConfig().set("reserve.tax." + nation.toLowerCase(Locale.ROOT), round4(value));
        plugin.saveConfig();
        return true;
    }

    public double maxMint(String nation, String currencyId) {
        double cap = reserveOf(nation) / (parityOf(nation) * coverageFloor());
        return Math.max(0.0D, cap - supplyOf(currencyId));
    }

    public boolean canMint(String nation, String currencyId, double extra) {
        return extra <= maxMint(nation, currencyId) + 1.0E-9D;
    }

    public double dailyWithdrawLimit(String nation) {
        return reserveOf(nation) * plugin.getConfig().getDouble("reserve.withdraw-daily-share", 0.25D);
    }

    private double round2dec(double v, Currency c) {
        int scale = c == null ? 2 : c.decimals();
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }
}
