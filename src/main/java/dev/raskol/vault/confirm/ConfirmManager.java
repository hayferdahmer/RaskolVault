// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.confirm;

import dev.raskol.vault.exchange.ExchangeResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер pending-обменов с TTL.
 *
 * Игрок делает /rv convert → preview сохраняется здесь с дедлайном.
 * Игрок делает /rv confirm → take() возвращает preview, если не истёк.
 */
public final class ConfirmManager {

    private final Map<UUID, PendingConfirm> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ConfirmManager(long ttlSeconds) {
        this.ttlMillis = Math.max(5L, ttlSeconds) * 1000L;
    }

    /** Сохранить preview с TTL от момента вызова. */
    public void put(UUID uuid, ExchangeResult preview) {
        pending.put(uuid, new PendingConfirm(System.currentTimeMillis() + ttlMillis, preview));
    }

    /**
     * Взять pending-preview, если не истёк.
     * Возвращает Optional.empty() если:
     * - нет записи для uuid
     * - запись истекла
     * После take() запись удаляется (одноразовое использование).
     */
    public Optional<PendingConfirm> take(UUID uuid) {
        PendingConfirm p = pending.remove(uuid);
        if (p == null || p.isExpired(System.currentTimeMillis())) {
            return Optional.empty();
        }
        return Optional.of(p);
    }

    /** Очистить все pending (используется в onDisable). */
    public void clear() {
        pending.clear();
    }

    /** Количество активных pending-записей (для /rv debug). */
    public int size() {
        return pending.size();
    }
}
