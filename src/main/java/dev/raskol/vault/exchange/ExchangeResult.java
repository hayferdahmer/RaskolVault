// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

/**
 * Результат расчёта обмена.
 * gross — сколько игрок сдал (до комиссии), net — сколько получил.
 * applied = true только при успешном выполнении (списал+начислил+транзакция).
 */
public record ExchangeResult(
        boolean applied,
        String fromId,
        String toId,
        double gross,
        double feeAmount,
        double net,
        double rate,
        String reason
) {
    public static ExchangeResult failure(String fromId, String toId, String reason) {
        return new ExchangeResult(false, fromId, toId, 0.0D, 0.0D, 0.0D, 0.0D, reason);
    }
}
