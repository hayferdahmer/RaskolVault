// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Единственный упорядоченный писатель SQLite-леджера.
 *
 * Все принятые задания выполняются одним потоком FIFO. Переполнение очереди
 * создаёт backpressure: отправитель ждёт свободного места, но не пишет в БД
 * самостоятельно. Это сохраняет единственного владельца записи.
 */
public final class LedgerWriter {

    public record Job(Runnable write, Consumer<Boolean> callback) {
    }

    private final Plugin plugin;
    private final BlockingQueue<Job> queue;
    private final Thread thread;
    private final Object lifecycleLock = new Object();
    private final AtomicLong applied = new AtomicLong(0L);
    private final AtomicLong failed = new AtomicLong(0L);
    private volatile boolean running = true;

    public LedgerWriter(Plugin plugin, int capacity) {
        this.plugin = plugin;
        this.queue = new ArrayBlockingQueue<>(Math.max(64, capacity));
        this.thread = new Thread(this::loop, "RaskolVault-LedgerWriter");
        this.thread.setDaemon(true);
        this.thread.start();
        plugin.getLogger().info("RaskolVault: писатель леджера запущен (очередь до "
                + Math.max(64, capacity) + " задач)");
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            try {
                Job job = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (job != null) {
                    execute(job);
                }
            } catch (InterruptedException e) {
                if (!running && queue.isEmpty()) {
                    return;
                }
            }
        }
    }

    private void execute(Job job) {
        boolean success = false;
        try {
            job.write().run();
            applied.incrementAndGet();
            success = true;
        } catch (Throwable t) {
            failed.incrementAndGet();
            plugin.getLogger().severe("RaskolVault: запись леджера провалена: " + t);
        }

        if (job.callback() != null) {
            try {
                job.callback().accept(success);
            } catch (Throwable callbackError) {
                plugin.getLogger().severe("RaskolVault: ошибка callback писателя леджера: "
                        + callbackError);
            }
        }
    }

    /**
     * Асинхронная постановка. При переполнении очередь создаёт backpressure.
     * Запись никогда не переносится на поток Bukkit-вызова.
     */
    public void submit(Runnable write, Consumer<Boolean> callback) {
        Job job = new Job(write, callback);

        // Вложенная постановка из потока писателя выполняется сразу, иначе
        // единственный поток мог бы ждать место в собственной очереди.
        if (Thread.currentThread() == thread) {
            execute(job);
            return;
        }

        synchronized (lifecycleLock) {
            if (!running) {
                notifyRejected(job);
                return;
            }
            try {
                queue.put(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyRejected(job);
            }
        }
    }

    /**
     * Синхронная постановка с подтверждением фактического завершения записи.
     * Метод не возвращает false по тайм-ауту, пока задание ещё может записаться.
     */
    public boolean submitSync(Runnable write) {
        if (Thread.currentThread() == thread) {
            try {
                write.run();
                applied.incrementAndGet();
                return true;
            } catch (Throwable t) {
                failed.incrementAndGet();
                plugin.getLogger().severe("RaskolVault: запись леджера провалена: " + t);
                return false;
            }
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        submit(write, result::complete);
        try {
            return result.get();
        } catch (InterruptedException e) {
            // Прерывание вызывающего потока не отменяет уже принятую запись.
            // Дожидаемся результата, чтобы вызывающий код не повторил операцию
            // с неопределённым исходом.
            boolean interrupted = true;
            while (true) {
                try {
                    boolean completed = result.get();
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return completed;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException impossible) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
            }
        } catch (ExecutionException e) {
            plugin.getLogger().severe("RaskolVault: не удалось получить результат записи: " + e);
            return false;
        }
    }

    private void notifyRejected(Job job) {
        if (job.callback() != null) {
            try {
                job.callback().accept(false);
            } catch (Throwable callbackError) {
                plugin.getLogger().severe("RaskolVault: ошибка callback отклонённой записи: "
                        + callbackError);
            }
        }
    }

    /**
     * Останавливает приём новых заданий и дожидается завершения всех принятых.
     * Не выполняет остаток очереди параллельно с рабочим потоком.
     */
    public void close(long drainTimeoutMillis) {
        synchronized (lifecycleLock) {
            running = false;
        }
        thread.interrupt();
        try {
            // Корректное завершение важнее тайм-аута: нельзя закрывать пул БД,
            // пока писатель ещё использует соединение.
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("RaskolVault: ожидание остановки писателя прервано; "
                    + "пул SQLite нельзя безопасно закрывать до завершения потока.");
        }
    }

    public int queueSize() {
        return queue.size();
    }

    public long applied() {
        return applied.get();
    }

    public long failed() {
        return failed.get();
    }
}
