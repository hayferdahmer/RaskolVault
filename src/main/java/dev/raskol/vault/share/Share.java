// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.share;

import java.util.UUID;

/**
 * Доля (средневековый пай) — доля игрока в резерве нации.
 *
 * Поля:
 *  - id: UUID сертификата доли
 *  - nation: нация-эмитент
 *  - owner: держатель (UUID игрока)
 *  - grams: вес доли в золотниках (условных граммах резерва)
 *  - issuedAt: дата выпуска
 *  - lastPayout: дата последнего Ужитка (дивиденда)
 *
 * Один золотник = право на 1/суммарного_массы_долей часть резерва.
 * Покрытие долей = резерв_нации / суммарная_масса_долей (должно быть ≥ 1.0).
 */
public record Share(
        String id,
        String nation,
        UUID owner,
        double grams,
        long issuedAt,
        long lastPayout
) {
    public Share withOwner(UUID newOwner) {
        return new Share(id, nation, newOwner, grams, issuedAt, lastPayout);
    }

    public Share withLastPayout(long timestamp) {
        return new Share(id, nation, owner, grams, issuedAt, timestamp);
    }
}
