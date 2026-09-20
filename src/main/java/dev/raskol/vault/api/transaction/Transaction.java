// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable-транзакция аудита.
 * id = -1 для ещё не записанных в леджер (id назначает SQLite AUTOINCREMENT).
 * from/to nullable: MINT без from, BURN без to.
 */
public record Transaction(
        long id,
        long timestampMillis,
        UUID from,
        UUID to,
        String currencyId,
        double amount,
        TransactionType type,
        String reason,
        String metadataJson
) {
    public Transaction {
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }

    /** Фабрика новой транзакции до записи в леджер. */
    public static Transaction fresh(long timestampMillis, UUID from, UUID to, String currencyId,
                                    double amount, TransactionType type, String reason, String metadataJson) {
        return new Transaction(-1L, timestampMillis, from, to, currencyId, amount, type, reason, metadataJson);
    }
}
