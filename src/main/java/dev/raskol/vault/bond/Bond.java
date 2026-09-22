// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bond;

import java.util.UUID;

/**
 * Королевская рента (облигация) / Заёмная грамота (1.2.3).
 *
 * Для наций с валютой: Королевская рента (долгосрочный долг, купонный доход).
 * Для вольных городов: Заёмная грамота (краткосрочный долг, фиксированный возврат).
 *
 * Поля:
 *  - id: уникальный идентификатор (UUID)
 *  - nation: имя нации-эмитента
 *  - face: номинал (сумма возврата в GLD)
 *  - couponRate: купонная ставка (0.05 = 5% в год)
 *  - issuedAt: дата эмиссии (ms)
 *  - maturesAt: дата погашения (ms)
 *  - holder: текущий держатель (null если в казне)
 */
public record Bond(
        String id,
        String nation,
        double face,
        double couponRate,
        long issuedAt,
        long maturesAt,
        UUID holder
) {
    public boolean isMatured(long now) {
        return now >= maturesAt;
    }

    public long remainingDays(long now) {
        long remaining = maturesAt - now;
        return Math.max(0, remaining / (1000L * 60 * 60 * 24));
    }

    public double accruedCoupon(long now) {
        if (now <= issuedAt) return 0.0D;
        long elapsed = Math.min(now, maturesAt) - issuedAt;
        double years = elapsed / (1000.0 * 60 * 60 * 24 * 365);
        return face * couponRate * years;
    }
}
