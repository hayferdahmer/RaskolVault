// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import java.util.UUID;

/**
 * Отложенный обмен, ожидающий подтверждения через /rv confirm.
 * Живёт в ConfirmManager до expiresAt, одноразовый (take удаляет).
 */
public record PendingExchange(
        UUID owner,
        String fromId,
        String toId,
        double amount,
        double netIn,
        double feeAmount,
        double rate,
        long expiresAt
) {
}
