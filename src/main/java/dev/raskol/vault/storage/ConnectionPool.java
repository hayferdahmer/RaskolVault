// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фиксированный пул SQLite-соединений собственного производства:
 * ноль внешних зависимостей (ничего не шейдим), детерминированное поведение.
 *
 * PRAGMAs, живущие на соединении (foreign_keys, busy_timeout, synchronous),
 * выставляются ОДИН раз при создании соединения — не на каждом checkout.
 * journal_mode=WAL — свойство файла БД, ставится отдельно в SQLiteLedger.init().
 *
 * Borrow: быстрый poll без ожидания; только при пустом пуле — ожидание с таймаутом.
 * Метрика waiting = число потоков, реально заблокированных на ожидании соединения.
 * Таймаут borrow = громкий LedgerException вместо тихого зависания потока.
 */
public final class ConnectionPool {

    private final Plugin plugin;
    private final String jdbcUrl;
    private final int size;
    private final long borrowTimeoutMillis;
    private final String synchronous;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger waiting = new AtomicInteger(0);
    private volatile boolean closed = false;

    public ConnectionPool(Plugin plugin, File dbFile, int poolSize,
                          String synchronous, long borrowTimeoutMillis) throws SQLException {
        this.plugin = plugin;
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.size = Math.max(1, Math.min(16, poolSize));
        this.synchronous = "FULL".equalsIgnoreCase(synchronous) ? "FULL" : "NORMAL";
        this.borrowTimeoutMillis = Math.max(500L, borrowTimeoutMillis);
        this.idle = new ArrayBlockingQueue<>(this.size);
        try {
            for (int i = 0; i < this.size; i++) {
                idle.add(newConnection());
            }
        } catch (SQLException e) {
            close();
            throw e;
        }
        plugin.getLogger().info("RaskolVault: пул SQLite открыт (" + this.size
                + " соединений, synchronous=" + this.synchronous + ")");
    }

    private Connection newConnection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA synchronous=" + synchronous);
        }
        return c;
    }

    /** Взять соединение. Быстрый путь без метрики ожидания; медленный — с метрикой и таймаутом. */
    public Connection borrow() {
        if (closed) {
            throw new LedgerException("Пул SQLite уже закрыт", null);
        }
        Connection c = idle.poll();
        if (c == null) {
            waiting.incrementAndGet();
            try {
                c = idle.poll(borrowTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LedgerException("Поток прерван при ожидании соединения SQLite", e);
            } finally {
                waiting.decrementAndGet();
            }
            if (c == null) {
                throw new LedgerException("Пул SQLite исчерпан (ожидание "
                        + borrowTimeoutMillis + " мс) — проверь нагрузку и pool-size", null);
            }
        }
        try {
            if (c.isClosed() || !c.isValid(1)) {
                closeQuietly(c);
                c = newConnection();
            }
        } catch (SQLException e) {
            closeQuietly(c);
            throw new LedgerException("Битое соединение в пуле: " + e.getMessage(), e);
        }
        return c;
    }

    /** Вернуть соединение в пул. После неудачной транзакции вызывай discard(), не release(). */
    public void release(Connection c) {
        if (c == null) {
            return;
        }
        if (closed || !idle.offer(c)) {
            closeQuietly(c);
        }
    }

    /** Уничтожить соединение после сбоя (не возвращать в пул). */
    public void discard(Connection c) {
        closeQuietly(c);
    }

    public void close() {
        closed = true;
        Connection c;
        while ((c = idle.poll()) != null) {
            closeQuietly(c);
        }
    }

    public int size() {
        return size;
    }

    public int idleCount() {
        return idle.size();
    }

    public int waitingCount() {
        return waiting.get();
    }

    private void closeQuietly(Connection c) {
        try {
            if (c != null && !c.isClosed()) {
                c.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
