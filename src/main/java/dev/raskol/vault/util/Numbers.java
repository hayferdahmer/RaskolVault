// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

import java.util.Locale;

/**
 * Единые числовые утилиты для всей системы.
 * Заменяет дубли round2() / fmt() из AuctionService, BankService, AuctionGui, BankGui, AuctionFilter.
 */
public final class Numbers {

    private Numbers() {}

    public static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    public static double round(double v, int decimals) {
        if (decimals < 0 || decimals > 10) decimals = 2;
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    public static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    public static double parseDouble(String s, double fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Double.parseDouble(s.replace(",", ".").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean isPositive(double v) { return Double.isFinite(v) && v > 0.0D; }
    public static boolean isNonNegative(double v) { return Double.isFinite(v) && v >= 0.0D; }
}
