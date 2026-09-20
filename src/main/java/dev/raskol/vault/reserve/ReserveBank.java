// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.reserve;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.storage.SafeStorage;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Валютный совет (1.1.0-b): золотое покрытие национальных валют.
 *
 * Резерв нации = баланс GLD на псевдо-UUID reserve:<nation> (хранится в общем леджере,
 * двигается только через WalletService.transfer → всё попадает в аудит).
 * Паритет и налог нации хранятся в nations.yml (SafeStorage: temp → .bak → rename).
 *
 * Модель: цена(N) = min(паритет, резерв/эмиссия); покрытие = резерв/(эмиссия×паритет).
 */
public final class ReserveBank {

    private final Plugin plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final File file;
    private final double coverageFloor;
    private final double parityMin;
    private final double parityMax;
    private final double taxMax;
    private final double withdrawDailyShare;

    private final Map<String, Double> parity = new ConcurrentHashMap<>();
    private final Map<String, Double> tax = new ConcurrentHashMap<>();
    /** nation → {dayEpoch, baseReserve, withdrawnToday} — лимит вывода резерва в сутки. */
    private final Map<String, double[]> dayLimit = new ConcurrentHashMap<>();

    public ReserveBank(Plugin plugin, WalletService wallets, CurrencyRegistry currencies) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.file = new File(plugin.getDataFolder(),
                plugin.getConfig().getString("reserve.file", "nations.yml"));
        this.coverageFloor = plugin.getConfig().getDouble("reserve.coverage-floor", 0.5);
        this.parityMin = plugin.getConfig().getDouble("reserve.parity-min", 0.5);
        this.parityMax = plugin.getConfig().getDouble("reserve.parity-max", 2.0);
        this.taxMax = plugin.getConfig().getDouble("reserve.tax-max", 0.05);
        this.withdrawDailyShare = plugin.getConfig().getDouble("reserve.withdraw-daily-share", 0.25);
        load();
    }

    public static UUID reserveUuid(String nation) {
        return UUID.nameUUIDFromBytes(
                ("reserve:" + nation.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("nations");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection ns = section.getConfigurationSection(key);
            if (ns == null) {
                continue;
            }
            parity.put(key, clamp(ns.getDouble("parity", 1.0), parityMin, parityMax));
            tax.put(key, clamp(ns.getDouble("tax", 0.0), 0.0, taxMax));
        }
        plugin.getLogger().info("RaskolVault: Валютный совет загружен, наций: " + parity.size());
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("nations");
        for (Map.Entry<String, Double> e : parity.entrySet()) {
            section.set(e.getKey() + ".parity", e.getValue());
            section.set(e.getKey() + ".tax", tax.getOrDefault(e.getKey(), 0.0));
        }
        SafeStorage.saveAtomic(yaml, file, plugin);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Резерв нации в GLD. */
    public double reserveOf(String nation) {
        return wallets.getBalance(reserveUuid(nation), currencies.globalId());
    }

    /** Эмиссия валюты = сумма всех её балансов (неглобальных) в кэше кошельков. */
    public double supplyOf(String currencyId) {
        double sum = 0.0D;
        for (Map.Entry<UUID, Map<String, Double>> row : wallets.cacheSnapshot().entrySet()) {
            sum += row.getValue().getOrDefault(currencyId, 0.0D);
        }
        return sum;
    }

    public double parityOf(String nation) {
        return parity.getOrDefault(nation.toLowerCase(Locale.ROOT), 1.0D);
    }

    public boolean setParity(String nation, double value) {
        if (value < parityMin || value > parityMax) {
            return false;
        }
        parity.put(nation.toLowerCase(Locale.ROOT), value);
        save();
        return true;
    }

    public double taxOf(String nation) {
        return tax.getOrDefault(nation.toLowerCase(Locale.ROOT), 0.0D);
    }

    public boolean setTax(String nation, double value) {
        if (value < 0.0D || value > taxMax) {
            return false;
        }
        tax.put(nation.toLowerCase(Locale.ROOT), value);
        save();
        return true;
    }

    /** Цена валюты в золоте: GLD = 1; национальная = min(паритет, резерв/эмиссия). */
    public double priceOf(Currency currency) {
        if (currency.type() == CurrencyType.GLOBAL) {
            return 1.0D;
        }
        String nation = currency.nationId() == null ? "" : currency.nationId();
        double supply = supplyOf(currency.id());
        if (supply <= 0.0D) {
            return parityOf(nation);
        }
        return Math.min(parityOf(nation), reserveOf(nation) / supply);
    }

    /** Покрытие = резерв / (эмиссия × паритет). Пустая эмиссия = полное доверие (1.0). */
    public double coverageOf(String nation, String currencyId) {
        double supply = supplyOf(currencyId);
        if (supply <= 0.0D) {
            return 1.0D;
        }
        return reserveOf(nation) / (supply * parityOf(nation));
    }

    public double coverageFloor() {
        return coverageFloor;
    }

    /** Сколько ещё можно минтить: (эмиссия+X)×паритет×floor ≤ резерв. */
    public double maxMint(String nation, String currencyId) {
        double supply = supplyOf(currencyId);
        double cap = reserveOf(nation) / (parityOf(nation) * coverageFloor);
        return Math.max(0.0D, cap - supply);
    }

    public boolean canMint(String nation, String currencyId, double extra) {
        return extra <= maxMint(nation, currencyId) + 1.0E-9D;
    }

    /** Король вносит своё золото в резерв нации. */
    public boolean depositToReserve(UUID from, String nation, double amount, String reason) {
        if (!(amount > 0.0D)) {
            return false;
        }
        return wallets.transfer(from, reserveUuid(nation), currencies.globalId(), amount,
                "reserve:deposit:" + reason);
    }

    /** Король выводит золото из резерва; лимит: withdraw-daily-share от резерва в сутки. */
    public boolean withdrawFromReserve(UUID to, String nation, double amount, String reason) {
        if (!(amount > 0.0D)) {
            return false;
        }
        String key = nation.toLowerCase(Locale.ROOT);
        long day = System.currentTimeMillis() / 86_400_000L;
        double[] st = dayLimit.computeIfAbsent(key, k -> new double[]{day, reserveOf(key), 0.0D});
        if ((long) st[0] != day) {
            st[0] = day;
            st[1] = reserveOf(key);
            st[2] = 0.0D;
        }
        double limit = st[1] * withdrawDailyShare;
        if (st[2] + amount > limit + 1.0E-9D) {
            return false;
        }
        boolean ok = wallets.transfer(reserveUuid(key), to, currencies.globalId(), amount,
                "reserve:withdraw:" + reason);
        if (ok) {
            st[2] += amount;
        }
        return ok;
    }

    public double dailyWithdrawLimit(String nation) {
        String key = nation.toLowerCase(Locale.ROOT);
        long day = System.currentTimeMillis() / 86_400_000L;
        double[] st = dayLimit.get(key);
        double base = (st == null || (long) st[0] != day) ? reserveOf(key) : st[1];
        double used = (st == null || (long) st[0] != day) ? 0.0D : st[2];
        return Math.max(0.0D, base * withdrawDailyShare - used);
    }
}
