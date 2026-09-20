// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.wallet;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable-снимок кошелька: UUID владельца + балансы по валютам.
 * Карта копируется в конструкторе — наружить состояние нельзя.
 */
public record Wallet(UUID owner, Map<String, Double> balances) {

    public Wallet {
        Objects.requireNonNull(owner, "owner");
        balances = balances == null ? Map.of() : Map.copyOf(balances);
    }

    /** Баланс валюты; 0.0, если записи нет. */
    public double get(String currencyId) {
        Double value = balances.get(currencyId);
        return value == null ? 0.0D : value;
    }

    /** Хватает ли средств (с эпсилон-допуском от ошибок double-арифметики). */
    public boolean has(String currencyId, double amount) {
        return get(currencyId) >= amount - 1.0E-9D;
    }
}
