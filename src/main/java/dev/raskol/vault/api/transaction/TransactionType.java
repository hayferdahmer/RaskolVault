// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.transaction;

/**
 * Типы движений средств для аудита.
 * MINT/BURN — эмиссия и сжигание (админ и казна наций).
 * SYNC — служебная синхронизация с EssentialsX.
 */
public enum TransactionType {
    PAY,
    CONVERT,
    MINT,
    BURN,
    ADMIN_SET,
    ADMIN_GIVE,
    ADMIN_TAKE,
    SYNC
}
