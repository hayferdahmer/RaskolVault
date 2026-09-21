// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

/**
 * Чистая экономическая математика (1.1.5): без Bukkit, полностью тестируемо.
 * Канонические формулы валютного совета; ReserveBank/ConvertEngine делегируют сюда.
 */
public final class EconMath {

    private EconMath() {
    }

    /** Округление до scale знаков (HALF_UP). */
    public static double round(double value, int scale) {
        double factor = Math.pow(10, Math.max(0, scale));
        return Math.round(value * factor) / factor;
    }

    /** Покрытие = резерв / (эмиссия × паритет). Эмиссия<=0 → 1.0 (полное доверие). */
    public static double coverage(double reserve, double supply, double parity) {
        if (supply <= 0.0D || parity <= 0.0D) {
            return 1.0D;
        }
        return reserve / (supply * parity);
    }

    /** Цена в золоте = min(паритет, резерв/эмиссия). Эмиссия<=0 → паритет. */
    public static double priceFromReserve(double reserve, double supply, double parity) {
        if (supply <= 0.0D) {
            return parity;
        }
        return Math.min(parity, reserve / supply);
    }

    /** Базовая комиссия (сжигается). */
    public static double feeBase(double amount, double baseRate) {
        return amount * baseRate;
    }

    /** Налог нации-цели. */
    public static double feeTax(double amount, double taxRate) {
        return amount * taxRate;
    }

    /** Чистый выход конверта. */
    public static double net(double amount, double feeBase, double feeTax, double rate) {
        return (amount - feeBase - feeTax) * rate;
    }

    /** Курс A→B из цен в золоте. Некорректные входы → 0. */
    public static double rateFromPrices(double priceA, double priceB) {
        if (priceA <= 0.0D || priceB <= 0.0D) {
            return 0.0D;
        }
        double r = priceA / priceB;
        return Double.isFinite(r) ? r : 0.0D;
    }

    /**
     * Прибыль круговой конвертации A→B→A (до комиссий).
     * После комиссий должна быть <= 0 (иначе арбитраж).
     */
    public static double roundTripProfit(double rateAB, double rateBA) {
        return rateAB * rateBA - 1.0D;
    }

    /** Максимальный минт при данном резерве/паритете/поле покрытия. */
    public static double maxMint(double reserve, double parity, double coverageFloor, double supply) {
        if (parity <= 0.0D || coverageFloor <= 0.0D) {
            return 0.0D;
        }
        double cap = reserve / (parity * coverageFloor);
        return Math.max(0.0D, cap - supply);
    }
}
