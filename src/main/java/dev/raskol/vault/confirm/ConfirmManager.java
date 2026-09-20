// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import dev.raskol.vault.exchange.ExchangeResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pending-обменов. TTL = 30 сек (по умолчанию).
 * /rv convert кладёт сюда ExchangeResult, /rv confirm читает и исполняет.
 * Без persistence: рестарт сервера сбрасывает pending — это норма, обмен не критичен.
 */
public final class ConfirmManager {

    public record Pending(ExchangeResult preview, long expiresAtMillis) {
    }

    private final long ttlMillis;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ConfirmManager(long ttlSeconds) {
        this.ttlMillis = Math.max(5L, ttlSeconds) * 1000L;
    }

    public void put(UUID owner, ExchangeResult preview) {
        pending.put(owner, new Pending(preview, System.currentTimeMillis() + ttlMillis));
    }

    public Optional<Pending> consume(UUID owner) {
        Pending value = pending.remove(owner);
        if (value == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() > value.expiresAtMillis()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }
}
