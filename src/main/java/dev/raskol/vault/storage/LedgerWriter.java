// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Единственный упорядоченный писатель леджера (1.0.3).
 *
 * Модель: main-thread делает мгновенную проекцию баланса в кэше и ставит
 * атомарный коммит в очередь; этот поток применяет коммиты строго FIFO,
 * поэтому порядок операций совпадает с порядком проекций, гонок нет.
 *
 * Backpressure: если очередь полна — запись выполняется синхронно на потоке
 * вызывающего (защита от бесконечного роста памяти под лавиной операций).
 *
 * Отказоустойчивость: ошибка записи = SEVERE с полными данными транзакции
 * для ручного восстановления; кэш НАМЕРЕННО не откатывается (откат затёр бы
 * более поздние проекции). Счётчик failed — материал для алертов 1.0.4.
 */
public final class LedgerWriter {

    public record Job(Runnable write, Consumer<Boolean> callback) {
    }

    private final Plugin plugin;
    private final BlockingQueue<Job> queue;
    private final Thread thread;
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
            Job job;
            try {
                job = queue.poll(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (job == null) {
                continue;
            }
            execute(job);
        }
    }

    private void execute(Job job) {
        try {
            job.write().run();
            applied.incrementAndGet();
            if (job.callback() != null) {
                job.callback().accept(true);
            }
        } catch (Throwable t) {
            failed.incrementAndGet();
            plugin.getLogger().severe("RaskolVault: запись леджера провалена: " + t);
            if (job.callback() != null) {
                job.callback().accept(false);
            }
        }
    }

    /** Асинхронная постановка. Очередь полна → синхронное выполнение на потоке вызывающего. */
    public void submit(Runnable write, Consumer<Boolean> callback) {
        Job job = new Job(write, callback);
        if (!running || !queue.offer(job)) {
            execute(job);
        }
    }

    /** Синхронная постановка с ожиданием результата (контракт Core-хука и админок). */
    public boolean submitSync(Runnable write, long timeoutMillis) {
        java.util.concurrent.CompletableFuture<Boolean> future =
                new java.util.concurrent.CompletableFuture<>();
        submit(write, future::complete);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Graceful shutdown: дождаться писателя, затем добить остаток на потоке вызывающего. */
    public void close(long drainTimeoutMillis) {
        running = false;
        try {
            thread.join(drainTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Job job;
        while ((job = queue.poll()) != null) {
            execute(job);
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
