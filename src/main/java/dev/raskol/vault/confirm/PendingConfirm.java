// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import dev.raskol.vault.exchange.ExchangeResult;

/**
 * Ожидающий подтверждения обмен валют.
 *
 * Живёт в ConfirmManager до TTL (30 сек по умолчанию из config.yml).
 * После /rv confirm берётся через take() и отправляется в асинхронный коммит.
 */
public record PendingConfirm(long expiresAtMillis, ExchangeResult preview) {
    
    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
