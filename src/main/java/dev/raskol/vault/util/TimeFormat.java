// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

/**
 * Форматирование времени для GUI и сообщений.
 * Заменяет дубли timeLeft() / daysLeft() из AuctionGui и BankGui.
 */
public final class TimeFormat {

    private TimeFormat() {}

    /** «Xч Yмин» до момента expiresAt; если истёк — «истёк». */
    public static String timeLeft(long expiresAt) {
        long ms = expiresAt - System.currentTimeMillis();
        if (ms <= 0) return "истёк";
        long totalSec = ms / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        if (h > 24) {
            long d = h / 24;
            h = h % 24;
            return d + "д " + h + "ч";
        }
        return h + "ч " + m + "мин";
    }

    /** «X дн» до момента dueAt; минимум 0. */
    public static String daysLeft(long dueAt, long now) {
        long d = (dueAt - now) / 86_400_000L;
        return Math.max(0, d) + " дн";
    }

    /** Человекочитаемая длительность миллисекунд: «2д 5ч 17мин». */
    public static String duration(long ms) {
        if (ms <= 0) return "0мин";
        long sec = ms / 1000L;
        long d = sec / 86_400L; sec %= 86_400L;
        long h = sec / 3600L;   sec %= 3600L;
        long m = sec / 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("д ");
        if (h > 0) sb.append(h).append("ч ");
        if (m > 0 || sb.length() == 0) sb.append(m).append("мин");
        return sb.toString().trim();
    }

    /** Римские цифры для уровней 1..10 (энчанты). */
    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";    case 2 -> "II";   case 3 -> "III";
            case 4 -> "IV";   case 5 -> "V";    case 6 -> "VI";
            case 7 -> "VII";  case 8 -> "VIII"; case 9 -> "IX";
            case 10 -> "X";   default -> String.valueOf(level);
        };
    }
}
