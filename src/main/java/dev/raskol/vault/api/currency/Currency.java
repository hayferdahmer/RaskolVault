// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.currency;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable-модель валюты.
 * ID строго 3 буквы капсом ([A-Z]{3}): GLD, RAS, VLR, DEN и т.д.
 * Это требование бренда и гарантия уникальности в PAPI-плейсхолдерах.
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
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Z]{3}");

    public Currency {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "currency id must be exactly 3 uppercase letters [A-Z]{3}, got: '" + id + "'");
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
