// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import dev.raskol.vault.storage.SQLiteLedger;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Транзакций в минуту (1.1.0-b): сэмплирует счётчик леджера каждые 5 секунд,
 * держит кольцо за 60 секунд. Только чтение существующего API (countTransactions).
 */
public final class TxCounter implements Runnable {

    private final SQLiteLedger ledger;
    private final Deque<long[]> ring = new ArrayDeque<>();
    private long lastTotal = -1L;

    public TxCounter(SQLiteLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public synchronized void run() {
        long total;
        try {
            total = ledger.countTransactions();
        } catch (Exception e) {
            return;
        }
        if (lastTotal < 0L) {
            lastTotal = total;
            return;
        }
        long delta = Math.max(0L, total - lastTotal);
        lastTotal = total;
        long now = System.currentTimeMillis();
        ring.addLast(new long[]{now, delta});
        while (!ring.isEmpty() && now - ring.peekFirst()[0] > 60_000L) {
            ring.removeFirst();
        }
    }

    public synchronized int perMinute() {
        long sum = 0L;
        for (long[] entry : ring) {
            sum += entry[1];
        }
        return (int) sum;
    }
}
