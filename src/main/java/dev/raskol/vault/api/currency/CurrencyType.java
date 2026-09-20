// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.api.currency;

/**
 * Тип валюты по привязке.
 * GLOBAL   — единственная проксируемая в EssentialsX (⚜).
 * NATIONAL — привязана к Towny-нации, резиденты получают автоматически.
 * WORLD    — свободная валюта без привязки к нациям.
 */
public enum CurrencyType {
    GLOBAL,
    NATIONAL,
    WORLD
}
