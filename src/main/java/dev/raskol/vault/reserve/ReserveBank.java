// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.reserve;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;

/**
 * Валютный совет (1.2.2-b): + перегрузки mint(nation, currencyId, amount, reason)
 * и setParity(nation, value, reason) для CabinetGui.
 */
public final class ReserveBank {

    public record Advice(String title, List<String> lore) {
    }

    public record CrisisSnapshot(ReserveCrisisPolicy.Level previous,
                                 ReserveCrisisPolicy.Level current,
                                 double coverage, boolean changed) {
    }

    private static final long CACHE_TTL_MS = 5000L;

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    private final Map<String, Double> cacheValues = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheExpiry = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> mintLocks = new ConcurrentHashMap<>();
    private final Map<String, ReserveCrisisPolicy.Level> crisisStates = new ConcurrentHashMap<>();

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
            if (v != null) return v;
        }
        double v = compute.getAsDouble();
        cacheValues.put(key, v);
        cacheExpiry.put(key, now + CACHE_TTL_MS);
        return v;
    }

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

    /**
     * Пересчитывает состояние валюты. После входа в кризис ограничения сохраняются,
     * пока покрытие не достигнет recovery-threshold.
     */
    public synchronized CrisisSnapshot refreshCrisisState(String nation, String currencyId) {
        if (nation == null || nation.isBlank() || currencyId == null || currencyId.isBlank()) {
            return new CrisisSnapshot(null, ReserveCrisisPolicy.Level.CRISIS, 0.0D, false);
        }
        String key = crisisKey(nation, currencyId);
        double coverage = coverageOf(nation, currencyId);
        ReserveCrisisPolicy.Level previous = crisisStates.get(key);
        ReserveCrisisPolicy.Level current = ReserveCrisisPolicy.next(previous, coverage,
                plugin.getConfig().getDouble("reserve.crisis-threshold", coverageFloor()),
                plugin.getConfig().getDouble("reserve.recovery-threshold", 0.60D));
        crisisStates.put(key, current);
        return new CrisisSnapshot(previous, current, coverage,
                previous != null && previous != current);
    }

    public ReserveCrisisPolicy.Level crisisState(String nation, String currencyId) {
        return refreshCrisisState(nation, currencyId).current();
    }

    public boolean isInCrisis(String nation, String currencyId) {
        return crisisState(nation, currencyId) == ReserveCrisisPolicy.Level.CRISIS;
    }

    public double coverageOfNation(String nation) {
        double coverage = 1.0D;
        boolean found = false;
        for (Currency currency : currencies.all()) {
            if (currency.type() == CurrencyType.NATIONAL && nation != null
                    && nation.equalsIgnoreCase(currency.nationId())) {
                coverage = Math.min(coverage, coverageOf(nation, currency.id()));
                found = true;
            }
        }
        return found ? coverage : 1.0D;
    }

    public boolean isNationInCrisis(String nation) {
        return crisisState(nation) == ReserveCrisisPolicy.Level.CRISIS;
    }

    public ReserveCrisisPolicy.Level crisisState(String nation) {
        ReserveCrisisPolicy.Level worst = ReserveCrisisPolicy.Level.NORMAL;
        for (Currency currency : currencies.all()) {
            if (currency.type() == CurrencyType.NATIONAL && nation != null
                    && nation.equalsIgnoreCase(currency.nationId())) {
                ReserveCrisisPolicy.Level level = crisisState(nation, currency.id());
                if (level.ordinal() > worst.ordinal()) worst = level;
            }
        }
        return worst;
    }

    private static String crisisKey(String nation, String currencyId) {
        return nation.toLowerCase(Locale.ROOT) + "|" + currencyId.toLowerCase(Locale.ROOT);
    }

    public boolean reserveCredit(String nation, double gold) {
        if (gold == 0.0D) return true;
        if (nation == null || nation.isBlank() || !Double.isFinite(gold) || gold < 0.0D) return false;
        boolean ok = ledger.reserveAdd(nation, gold);
        if (ok) invalidateCache();
        return ok;
    }

    public boolean reserveDebit(String nation, double gold) {
        if (gold == 0.0D) return true;
        if (nation == null || nation.isBlank() || !Double.isFinite(gold) || gold < 0.0D) return false;
        boolean ok = ledger.reserveAdd(nation, -gold);
        if (ok) invalidateCache();
        return ok;
    }

    public void reserveSet(String nation, double amount) {
        if (nation == null || nation.isBlank() || !Double.isFinite(amount) || amount < 0.0D) {
            throw new IllegalArgumentException("Некорректное значение резерва");
        }
        ledger.reserveSet(nation, amount);
        invalidateCache();
    }

    public boolean depositToReserve(UUID player, String nation, double amount, String reason) {
        if (player == null || nation == null || nation.isBlank()
                || !Double.isFinite(amount) || !(amount > 0.0D)) return false;
        String glb = currencies.globalId();
        if (!wallets.has(player, glb, amount)) return false;
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
        if (player == null || nation == null || nation.isBlank()
                || !Double.isFinite(amount) || !(amount > 0.0D)
                || isNationInCrisis(nation)
                || amount > dailyWithdrawLimit(nation) + 1.0E-9D) return false;
        if (!ledger.reserveAdd(nation, -amount)) return false;
        String glb = currencies.globalId();
        if (!wallets.deposit(player, glb, amount, TransactionType.PAY, "reserve:withdraw:" + reason)) {
            ledger.reserveAdd(nation, amount);
            return false;
        }
        invalidateCache();
        return true;
    }

    /** Эмиссия в казну с сеньоражем (базовая форма). */
    public boolean mintToTreasury(String nation, Currency currency, double amount) {
        if (nation == null || currency == null || !Double.isFinite(amount) || !(amount > 0.0D)) {
            return false;
        }

        String lockKey = nation.toLowerCase(Locale.ROOT) + "|" + currency.id();
        ReentrantLock lock = mintLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (!canMint(nation, currency.id(), amount)) {
                return false;
            }

            double seigniorage = seigniorageRate();
            double fee = round2dec(amount * seigniorage, currency);
            double net = round2dec(amount - fee, currency);
            if (!Double.isFinite(fee) || fee < 0.0D || !Double.isFinite(net) || net < 0.0D) {
                return false;
            }
            UUID treasury = treasuryUuid(nation);
            if (currency.type() == CurrencyType.GLOBAL) {
                if (!wallets.deposit(treasury, currency.id(), net,
                        TransactionType.MINT, "mint:" + nation)) {
                    return false;
                }
                if (fee > 0.0D && !wallets.withdraw(treasury, currency.id(), fee,
                        TransactionType.BURN, "seigniorage:" + nation)) {
                    plugin.getLogger().severe("RaskolVault: сеньораж GLOBAL не удалось списать "
                            + "после начисления; требуется ручная сверка казны " + nation);
                }
                invalidateCache();
                return true;
            }

            // Сохраняем текущую семантику последовательных операций:
            // сначала начисляется net, затем из казны списывается fee.
            List<WalletService.WalletDelta> deltas = new java.util.ArrayList<>();
            deltas.add(new WalletService.WalletDelta(treasury, currency.id(), net));
            List<Transaction> transactions = new java.util.ArrayList<>();
            transactions.add(new Transaction(-1L, System.currentTimeMillis(), null, treasury,
                    currency.id(), net, TransactionType.MINT, "mint:" + nation, null));
            if (fee > 0.0D) {
                deltas.add(new WalletService.WalletDelta(treasury, currency.id(), -fee));
                transactions.add(new Transaction(-1L, System.currentTimeMillis(), treasury, null,
                        currency.id(), fee, TransactionType.BURN, "seigniorage:" + nation, null));
            }

            boolean committed = wallets.applyBatchOnce(UUID.randomUUID().toString(),
                    deltas, List.of(), transactions);
            if (committed) {
                invalidateCache();
            }
            return committed;
        } finally {
            lock.unlock();
        }
    }

    /** ПЕРЕГРУЗКА (1.2.2-b) для CabinetGui: mint(nation, currencyId, amount, reason). */
    public boolean mint(String nation, String currencyId, double amount, String reason) {
        Currency cur = currencies.get(currencyId).orElse(null);
        if (cur == null) return false;
        return mintToTreasury(nation, cur, amount);
    }

    public boolean burnFromTreasury(String nation, Currency currency, double amount) {
        if (!(amount > 0.0D)) return false;
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
        if (currency.type() == CurrencyType.GLOBAL) return 1.0D;
        return cached("price:" + currency.id(), () -> {
            double supply = supplyOf(currency.id());
            double parity = parityOf(currency.nationId());
            if (supply <= 0.0D) return parity;
            return Math.min(parity, reserveOf(currency.nationId()) / supply);
        });
    }

    public double coverageOf(String nation, String currencyId) {
        return cached("cov:" + nation + ":" + currencyId, () -> {
            double supply = supplyOf(currencyId);
            if (supply <= 0.0D) return 1.0D;
            return reserveOf(nation) / (supply * parityOf(nation));
        });
    }

    public boolean setParity(String nation, double value) {
        if (nation == null || nation.isBlank() || !Double.isFinite(value)
                || value < parityMin() || value > parityMax()) return false;
        plugin.getConfig().set("reserve.parity." + nation.toLowerCase(Locale.ROOT), round2(value));
        plugin.saveConfig();
        invalidateCache();
        return true;
    }

    /** ПЕРЕГРУЗКА (1.2.2-b) для CabinetGui: setParity(nation, value, reason). reason игнорируется (логирование на стороне GUI). */
    public boolean setParity(String nation, double value, String reason) {
        return setParity(nation, value);
    }

    public boolean setTax(String nation, double value) {
        if (nation == null || nation.isBlank() || !Double.isFinite(value)
                || value < 0.0D || value > taxMax()) return false;
        plugin.getConfig().set("reserve.tax." + nation.toLowerCase(Locale.ROOT), round4(value));
        plugin.saveConfig();
        return true;
    }

    public double maxMint(String nation, String currencyId) {
        double parity = parityOf(nation);
        double floor = coverageFloor();
        if (!Double.isFinite(parity) || !Double.isFinite(floor) || parity <= 0.0D || floor <= 0.0D) {
            return 0.0D;
        }
        double cap = reserveOf(nation) / (parity * floor);
        double maximum = cap - supplyOf(currencyId);
        return Double.isFinite(maximum) ? Math.max(0.0D, maximum) : 0.0D;
    }

    public boolean canMint(String nation, String currencyId, double extra) {
        return !isInCrisis(nation, currencyId)
                && Double.isFinite(extra) && extra >= 0.0D
                && extra <= maxMint(nation, currencyId) + 1.0E-9D;
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
