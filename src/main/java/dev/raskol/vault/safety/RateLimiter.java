// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.safety;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate-limit на игрока (1.1.0-b): анти-спам конвертаций и манипуляций курсом.
 * capacity ≤ 0 = лимит выключен.
 */
public final class RateLimiter {

    private final double capacity;
    private final double refillPerSec;
    private final Map<UUID, double[]> state = new ConcurrentHashMap<>();

    public RateLimiter(double capacity, double refillPerSec) {
        this.capacity = capacity;
        this.refillPerSec = Math.max(0.0D, refillPerSec);
    }

    public boolean tryConsume(UUID uuid) {
        if (capacity <= 0.0D || uuid == null) {
            return true;
        }
        double[] st = state.computeIfAbsent(uuid, k -> new double[]{capacity, System.currentTimeMillis()});
        synchronized (st) {
            long now = System.currentTimeMillis();
            double elapsed = (now - st[1]) / 1000.0D;
            st[0] = Math.min(capacity, st[0] + elapsed * refillPerSec);
            st[1] = now;
            if (st[0] >= 1.0D) {
                st[0] -= 1.0D;
                return true;
            }
            return false;
        }
    }
}
