// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.reserve;

/**
 * Политика состояний валютного резерва с гистерезисом восстановления.
 */
public final class ReserveCrisisPolicy {

    public enum Level {
        NORMAL,
        WARNING,
        CRISIS
    }

    private ReserveCrisisPolicy() {
    }

    public static Level next(Level previous, double coverage,
                             double crisisThreshold, double recoveryThreshold) {
        if (!Double.isFinite(coverage) || coverage < 0.0D
                || !Double.isFinite(crisisThreshold)
                || !Double.isFinite(recoveryThreshold)
                || crisisThreshold <= 0.0D
                || recoveryThreshold <= crisisThreshold) {
            return Level.CRISIS;
        }

        if (previous == Level.CRISIS) {
            if (coverage < recoveryThreshold) {
                return Level.CRISIS;
            }
            return Level.NORMAL;
        }

        if (coverage < crisisThreshold) {
            return Level.CRISIS;
        }
        if (coverage < recoveryThreshold) {
            return Level.WARNING;
        }
        return Level.NORMAL;
    }
}
