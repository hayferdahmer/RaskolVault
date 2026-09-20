// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Скользящее окно 60 секунд для подсчёта транзакций в минуту.
 *
 * Вызывается из SQLiteLedger.commitAbsolute после успешного коммита
 * (счётчик инкрементируется именно записанными транзакциями, а не API-вызовами).
 * Потокобезопасен: synchronized на всех публичных методах.
 */
public final class TxPerMinuteCounter {

    private static final long WINDOW_MS = 60_000L;

    private final Deque<Long> timestamps = new ArrayDeque<>();

    /** Записать N транзакций, случившихся в момент вызова. */
    public synchronized void record(int count) {
        if (count <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            timestamps.addLast(now);
        }
        prune(now);
    }

    /** Количество транзакций за последние 60 секунд. */
    public synchronized int count() {
        prune(System.currentTimeMillis());
        return timestamps.size();
    }

    private void prune(long now) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
            timestamps.removeFirst();
        }
    }
}
