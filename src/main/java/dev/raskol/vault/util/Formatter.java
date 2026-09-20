// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Формат сумм под русскую локаль: группировка пробелом, дробная часть запятой.
 * 1234.5 → «1 234,50 ⚜» через withSymbol.
 */
public final class Formatter {

    private static final Locale RU = Locale.forLanguageTag("ru");

    private Formatter() {
    }

    public static String amount(double value, int decimals) {
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }
        DecimalFormat format = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance(RU));
        return format.format(value);
    }

    public static String withSymbol(double value, int decimals, String symbol) {
        return amount(value, decimals) + " " + symbol;
    }
}
