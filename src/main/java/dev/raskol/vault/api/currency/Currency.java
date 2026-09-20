// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.currency;

import java.util.Objects;

/**
 * Immutable-модель валюты. Валидация в компактном конструкторе:
 * NATIONAL без nationId — ошибка конфигурации, падаем сразу при загрузке.
 */
public record Currency(
        String id,
        String displayName,
        String symbol,
        CurrencyType type,
        String nationId,
        int decimals,
        boolean tradeable
) {
    public Currency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
        if (id.isBlank()) {
            throw new IllegalArgumentException("currency id is blank");
        }
        if (decimals < 0 || decimals > 4) {
            throw new IllegalArgumentException("decimals out of 0..4 for currency " + id + ": " + decimals);
        }
        if (type == CurrencyType.NATIONAL && (nationId == null || nationId.isBlank())) {
            throw new IllegalArgumentException("NATIONAL currency requires nation-id: " + id);
        }
    }

    public boolean isGlobal() {
        return type == CurrencyType.GLOBAL;
    }
}
