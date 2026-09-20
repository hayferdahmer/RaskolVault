// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.security;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-player token bucket для rate-limit команд кошелька (1.0.5).
 *
 * Модель: у каждого UUID ведро ёмкостью `capacity` токенов, пополняется
 * `refillPerSecond` токенов в секунду. Одна команда = 1 токен.
 * capacity <= 0 = лимит выключен (tryConsume всегда true).
 *
 * Потокобезопасность: состояние каждого ведра guarded своим монитором,
 * карта — ConcurrentHashMap. Простой idle не держит память вечно:
 * purgeIdle() вызывается планировщиком InflationCheckpoint.
 */
public final class TokenBucket {

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final double capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<UUID, Bucket> state = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong(0L);

    public TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = Math.max(0.0D, refillPerSecond);
    }

    public boolean isEnabled() {
        return capacity > 0.0D;
    }

    /** Съесть 1 токен. true = операция разрешена. */
    public boolean tryConsume(UUID key) {
        if (!isEnabled() || key == null) {
            return true;
        }
        Bucket bucket = state.computeIfAbsent(key,
                k -> new Bucket(capacity, System.nanoTime()));
        synchronized (bucket) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - bucket.lastNanos) / 1_000_000_000.0D;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1.0D) {
                bucket.tokens -= 1.0D;
                return true;
            }
            rejected.incrementAndGet();
            return false;
        }
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Убрать вёдра, простаивающие дольше idleMillis (вызывается планировщиком). */
    public void purgeIdle(long idleMillis) {
        long idleNanos = idleMillis * 1_000_000L;
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, Bucket>> it = state.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Bucket> entry = it.next();
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                if (now - bucket.lastNanos > idleNanos) {
                    it.remove();
                }
            }
        }
    }
}
