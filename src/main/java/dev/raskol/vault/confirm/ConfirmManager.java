// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище отложенных обменов (TTL из config: exchange.confirm-timeout-seconds).
 * store() — положить preview, take() — забрать одноразово с проверкой срока.
 */
public final class ConfirmManager {

    private final Map<UUID, PendingExchange> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ConfirmManager(long ttlSeconds) {
        this.ttlMillis = Math.max(5L, ttlSeconds) * 1000L;
    }

    public void store(PendingExchange exchange) {
        pending.put(exchange.owner(), exchange);
    }

    public Optional<PendingExchange> take(UUID owner) {
        PendingExchange exchange = pending.remove(owner);
        if (exchange == null || exchange.expiresAt() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(exchange);
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }
}
