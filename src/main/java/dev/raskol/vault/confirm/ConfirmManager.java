// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import dev.raskol.vault.exchange.ConvertEngine;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер ожидающих подтверждений конвертации (1.1.1).
 * API: store(UUID, Quote), take(UUID) -> Optional<PendingExchange>, clear().
 * TTL по времени создания записи (exchange.confirm-timeout-seconds).
 */
public final class ConfirmManager {

    public record PendingExchange(UUID owner, ConvertEngine.Quote quote, long createdAtMillis) {
    }

    private final Map<UUID, PendingExchange> pending = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    public ConfirmManager(long timeoutSeconds) {
        this.timeoutMillis = Math.max(5L, timeoutSeconds) * 1000L;
    }

    public void store(UUID owner, ConvertEngine.Quote quote) {
        pending.put(owner, new PendingExchange(owner, quote, System.currentTimeMillis()));
    }

    public Optional<PendingExchange> take(UUID owner) {
        PendingExchange p = pending.remove(owner);
        if (p == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - p.createdAtMillis() > timeoutMillis) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public Optional<PendingExchange> peek(UUID owner) {
        PendingExchange p = pending.get(owner);
        if (p == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - p.createdAtMillis() > timeoutMillis) {
            pending.remove(owner);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }

    /** Удаляет просроченные записи (для периодического вызова). */
    public int evictExpired() {
        long now = System.currentTimeMillis();
        int evicted = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue().createdAtMillis() > timeoutMillis) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }
}
