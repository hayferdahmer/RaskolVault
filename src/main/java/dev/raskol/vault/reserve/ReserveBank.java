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
import java.util.UUID;

/**
 * Валютный совет (1.1.1): резерв живёт в таблице reserves леджера, НЕ в Essentials.
 * Депозит: списание GLD у игрока через Essentials → кредит строки резерва (компенсация при сбое).
 * Вывод: дебет резерва → начисление GLD игроку (компенсация при сбое).
 * Конверты национальных валют проходят через резерв (ConvertEngine) — печать GLD закрыта.
 */
public final class ReserveBank {

    public record Advice(String title, List<String> lore) {
    }

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final SQLiteLedger ledger;

    public ReserveBank(RaskolVault plugin, WalletService wallets,
                       CurrencyRegistry currencies, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.ledger = ledger;
    }

    /** Детерминированный UUID резерва нации (для аудита транзакций). */
    public static UUID reserveUuid(String nation) {
        return UUID.nameUUIDFromBytes(
                ("reserve:" + nation.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    public double reserveOf(String nation) {
        return ledger.reserveGet(nation);
    }

    public boolean reserveCredit(String nation, double gold) {
        return gold <= 0.0D || ledger.reserveAdd(nation, gold);
    }

    public boolean reserveDebit(String nation, double gold) {
        return gold <= 0.0D || ledger.reserveAdd(nation, -gold);
    }

    public void reserveSet(String nation, double amount) {
        ledger.reserveSet(nation, amount);
    }

    /** Депозит личного золота игрока в резерв нации. */
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
        return true;
    }

    /** Вывод золота из резерва нации в личное золото игрока (суточный лимит). */
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
        return true;
    }

    public double supplyOf(String currencyId) {
        double sum = 0.0D;
        for (var row : wallets.cacheSnapshot().values()) {
            sum += row.getOrDefault(currencyId, 0.0D);
        }
        return sum;
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
        double supply = supplyOf(currency.id());
        double parity = parityOf(currency.nationId());
        if (supply <= 0.0D) {
            return parity;
        }
        return Math.min(parity, reserveOf(currency.nationId()) / supply);
    }

    public double coverageOf(String nation, String currencyId) {
        double supply = supplyOf(currencyId);
        if (supply <= 0.0D) {
            return 1.0D;
        }
        return reserveOf(nation) / (supply * parityOf(nation));
    }

    public boolean setParity(String nation, double value) {
        if (value < parityMin() || value > parityMax()) {
            return false;
        }
        plugin.getConfig().set("reserve.parity." + nation.toLowerCase(Locale.ROOT), round2(value));
        plugin.saveConfig();
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

    // ---------- ЭКОНОМИЧЕСКИЙ СОВЕТНИК ----------

    public List<Advice> advise(String nation) {
        List<Advice> out = new ArrayList<>();
        Currency national = nationalOf(nation);
        if (national == null) {
            out.add(new Advice("&cНет национальной валюты",
                    List.of("&7Создай её: /rv admin currency create", "&7или дождись авто-создания")));
            return out;
        }
        double R = reserveOf(nation);
        double S = supplyOf(national.id());
        double P = parityOf(nation);
        double T = taxOf(nation);
        double price = priceAt(R, S, P);
        double cov = S <= 0.0D ? 1.0D : R / (S * P);

        double R2 = R + 1000.0D;
        out.add(adviceReserve("&6Депозит +1000 GLD", R, R2, S, P, cov, true));

        double amt = Math.min(1000.0D, R);
        if (R <= 0.0D) {
            out.add(new Advice("&cВывод −1000 GLD",
                    List.of("&7Резерв пуст — выводить нечего.", "&7Сначала внеси золото депозитом.")));
        } else {
            out.add(adviceReserve("&cВывод −" + fmt0(amt) + " GLD", R, R - amt, S, P, cov, false));
        }

        double P2 = Math.min(parityMax(), round2(P + 0.10D));
        out.add(adviceParity("&6Паритет → " + fmt2(P2), P2, R, S, P, cov));

        double P3 = Math.max(parityMin(), round2(P - 0.10D));
        out.add(adviceParity("&cПаритет → " + fmt2(P3), P3, R, S, P, cov));

        double T2 = Math.min(taxMax(), round4(T + 0.005D));
        out.add(adviceTax("&6Налог → " + fmtPct(T2), T2, price, T));

        double T3 = Math.max(0.0D, round4(T - 0.005D));
        out.add(adviceTax("&cНалог → " + fmtPct(T3), T3, price, T));

        return out;
    }

    private Advice adviceReserve(String title, double R, double R2, double S, double P, double cov, boolean deposit) {
        double price1 = priceAt(R, S, P);
        double price2 = priceAt(R2, S, P);
        double cov2 = S <= 0.0D ? 1.0D : R2 / (S * P);
        List<String> lore = new ArrayList<>();
        lore.add("&7Резерв: &f" + fmt0(R) + " → " + fmt0(R2) + " GLD");
        lore.add("&7Покрытие: " + covColor(cov) + fmtPct(cov) + " → " + covColor(cov2) + fmtPct(cov2));
        lore.add("&7Цена: &f" + fmt4(price1) + " → " + (price2 > price1 ? "&a" : (price2 < price1 ? "&c" : "&7")) + fmt4(price2) + " GLD");
        if (deposit) {
            lore.add(price2 > price1 ? "&a✔ Укрепляет валюту" : "&7Цена не изменится (покрытие уже ≥ 100%)");
        } else {
            lore.add(cov2 < coverageFloor() ? "&c⚠ ПОКРЫТИЕ НИЖЕ ПОЛА → КРИЗИС" :
                    (price2 < price1 ? "&c Ослабляет валюту" : "&7Цена не изменится (покрытие ≥ 100%)"));
        }
        lore.add("&7Применяется в кабинете (слоты 29–32)");
        return new Advice(title, lore);
    }

    private Advice adviceParity(String title, double P2, double R, double S, double P, double cov) {
        double price1 = priceAt(R, S, P);
        double price2 = priceAt(R, S, P2);
        double cov2 = S <= 0.0D ? 1.0D : R / (S * P2);
        List<String> lore = new ArrayList<>();
        lore.add("&7Паритет: &f" + fmt2(P) + " → " + fmt2(P2));
        lore.add("&7Цена: &f" + fmt4(price1) + " → " + (price2 > price1 ? "&a" : (price2 < price1 ? "&c" : "&7")) + fmt4(price2) + " GLD");
        lore.add("&7Покрытие: " + covColor(cov) + fmtPct(cov) + " → " + covColor(cov2) + fmtPct(cov2));
        if (cov < 1.0D && Math.abs(price2 - price1) < 1.0E-9D) {
            lore.add("&7Пока покрытие < 100%, цену держит резерв,");
            lore.add("&7а не паритет — сначала пополняй резерв.");
        } else {
            lore.add(price2 > price1 ? "&a✔ Дороже для покупателей" : (price2 < price1 ? "&c⚠ Дешевле для покупателей" : "&7Без изменений"));
        }
        lore.add("&7Применяется в кабинете (слоты 19–25)");
        return new Advice(title, lore);
    }

    private Advice adviceTax(String title, double T2, double price, double T) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Налог конвертации В твою валюту:");
        lore.add("&f" + fmtPct(T) + " → " + fmtPct(T2));
        if (price > 0.0D) {
            double per1kNow = 1000.0D / price * T;
            double per1kNew = 1000.0D / price * T2;
            lore.add("&7Казна с каждых 1000 GLD входа:");
            lore.add("&f" + fmt2(per1kNow) + " → " + (per1kNew > per1kNow ? "&a" : (per1kNew < per1kNow ? "&c" : "&7")) + fmt2(per1kNew));
        }
        lore.add(T2 > T ? "&a✔ Больше дохода казны, но обмен дороже" :
                (T2 < T ? "&c⚠ Меньше дохода казны, но обмен дешевле" : "&7Без изменений"));
        lore.add("&7Применяется в кабинете (слоты 19–25)");
        return new Advice(title, lore);
    }

    private Currency nationalOf(String nation) {
        for (Currency c : currencies.all()) {
            if (c.type() == CurrencyType.NATIONAL && nation.equalsIgnoreCase(c.nationId())) {
                return c;
            }
        }
        return null;
    }

    private double priceAt(double reserve, double supply, double parity) {
        if (supply <= 0.0D) {
            return parity;
        }
        return Math.min(parity, reserve / supply);
    }

    private String covColor(double cov) {
        if (cov >= 1.0D) return "&a";
        if (cov >= coverageFloor()) return "&e";
        return "&c";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0D) / 10000.0D;
    }

    private static String fmt0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt4(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String fmtPct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100.0D);
    }
}
