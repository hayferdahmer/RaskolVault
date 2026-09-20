// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.observability.TxPerMinuteCounter;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SQLite-леджер на пуле соединений (1.0.2) + атомарный коммит (1.0.3)
 * + оптимистичная блокировка commitAbsoluteChecked и агрегаты инварианта (1.0.5).
 */
public final class SQLiteLedger {

    public static final int SCHEMA_VERSION = 1;

    /** Абсолютная запись баланса без проверки (служебные пути). */
    public record AbsoluteBalance(UUID owner, String currencyId, double newAmount) {
    }

    /**
     * Абсолютная запись с оптимистичной проверкой (1.0.5):
     * коммит применится ТОЛЬКО если текущее значение в БД == expectedOld.
     * Иначе 0 затронутых строк → LedgerException → лечение кэша наверху.
     */
    public record CheckedBalance(UUID owner, String currencyId, double expectedOld, double newAmount) {
    }

    private static final String INSERT_TX =
            "INSERT INTO transactions(timestamp, from_uuid, to_uuid, currency_id, amount, type, reason, metadata) "
                    + "VALUES(?,?,?,?,?,?,?,?)";
    private static final String UPSERT_BALANCE =
            "INSERT INTO balances(uuid, currency_id, amount, updated_at) VALUES(?,?,?,?) "
                    + "ON CONFLICT(uuid, currency_id) DO UPDATE SET "
                    + "amount=excluded.amount, updated_at=excluded.updated_at";
    private static final String UPSERT_BALANCE_CHECKED =
            "INSERT INTO balances(uuid, currency_id, amount, updated_at) VALUES(?,?,?,?) "
                    + "ON CONFLICT(uuid, currency_id) DO UPDATE SET "
                    + "amount=excluded.amount, updated_at=excluded.updated_at "
                    + "WHERE balances.amount=?";

    private final Plugin plugin;
    private final File dbFile;
    private final ConnectionPool pool;
    private volatile Supplier<String> writerStats;
    private volatile TxPerMinuteCounter txCounter;

    public SQLiteLedger(Plugin plugin, File dbFile, int poolSize,
                        String synchronous, long borrowTimeoutMillis) throws SQLException {
        this.plugin = plugin;
        this.dbFile = dbFile;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Не могу создать папку леджера: " + parent.getAbsolutePath());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Драйвер org.sqlite.JDBC не найден в jar (проверь shade в pom): " + e.getMessage(), e);
        }
        this.pool = new ConnectionPool(plugin, dbFile, poolSize, synchronous, borrowTimeoutMillis);
    }

    public void attachTxCounter(TxPerMinuteCounter counter) {
        this.txCounter = counter;
    }

    public synchronized void init() throws SQLException {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            ok = true;
        } finally {
            finish(c, ok);
        }
        migrate();
    }

    private void migrate() throws SQLException {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            int version;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.getInt(1);
            }
            if (version < SCHEMA_VERSION) {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS currencies ("
                            + "id TEXT PRIMARY KEY,"
                            + "display_name TEXT NOT NULL,"
                            + "symbol TEXT NOT NULL,"
                            + "type TEXT NOT NULL CHECK (type IN ('GLOBAL','NATIONAL','WORLD')),"
                            + "nation_id TEXT,"
                            + "decimals INTEGER NOT NULL DEFAULT 2,"
                            + "tradeable INTEGER NOT NULL DEFAULT 1,"
                            + "created_at INTEGER NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_currencies_type ON currencies(type)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_currencies_nation ON currencies(nation_id)");
                    st.execute("CREATE TABLE IF NOT EXISTS balances ("
                            + "uuid TEXT NOT NULL,"
                            + "currency_id TEXT NOT NULL,"
                            + "amount REAL NOT NULL DEFAULT 0,"
                            + "updated_at INTEGER NOT NULL,"
                            + "PRIMARY KEY (uuid, currency_id),"
                            + "FOREIGN KEY (currency_id) REFERENCES currencies(id) ON DELETE CASCADE)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_balances_uuid ON balances(uuid)");
                    st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "timestamp INTEGER NOT NULL,"
                            + "from_uuid TEXT,"
                            + "to_uuid TEXT,"
                            + "currency_id TEXT NOT NULL,"
                            + "amount REAL NOT NULL,"
                            + "type TEXT NOT NULL CHECK (type IN "
                            + "('PAY','CONVERT','MINT','BURN','ADMIN_SET','ADMIN_GIVE','ADMIN_TAKE','SYNC')),"
                            + "reason TEXT NOT NULL,"
                            + "metadata TEXT,"
                            + "FOREIGN KEY (currency_id) REFERENCES currencies(id) ON DELETE CASCADE)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_from ON transactions(from_uuid)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_to ON transactions(to_uuid)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_currency ON transactions(currency_id)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(type)");
                    st.execute("CREATE TABLE IF NOT EXISTS nations ("
                            + "id TEXT PRIMARY KEY,"
                            + "name TEXT NOT NULL,"
                            + "updated_at INTEGER NOT NULL)");
                    st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                }
                plugin.getLogger().info("SQLiteLedger: схема создана с нуля (v" + SCHEMA_VERSION + ")");
            } else if (version > SCHEMA_VERSION) {
                plugin.getLogger().warning("SQLiteLedger: схема v" + version + " новее поддерживаемой v"
                        + SCHEMA_VERSION + " — откати jar или восстанови БД из бекапа, данные не трогаю");
            }
            ok = true;
        } finally {
            finish(c, ok);
        }
    }

    // ---------- currencies ----------

    public void upsertCurrency(Currency currency) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO currencies(id, display_name, symbol, type, nation_id, decimals, tradeable, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                        + "display_name=excluded.display_name, symbol=excluded.symbol, type=excluded.type, "
                        + "nation_id=excluded.nation_id, decimals=excluded.decimals, tradeable=excluded.tradeable")) {
            ps.setString(1, currency.id());
            ps.setString(2, currency.displayName());
            ps.setString(3, currency.symbol());
            ps.setString(4, currency.type().name());
            ps.setString(5, currency.nationId());
            ps.setInt(6, currency.decimals());
            ps.setInt(7, currency.tradeable() ? 1 : 0);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("Не могу записать валюту " + currency.id() + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    public List<Currency> loadCurrencies() {
        List<Currency> out = new ArrayList<>();
        Connection c = pool.borrow();
        boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, display_name, symbol, type, nation_id, decimals, tradeable FROM currencies")) {
            while (rs.next()) {
                out.add(new Currency(rs.getString(1), rs.getString(2), rs.getString(3),
                        CurrencyType.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getInt(6), rs.getInt(7) == 1));
            }
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать валюты: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
        return out;
    }

    public void deleteCurrency(String id) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM currencies WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("Не могу удалить валюту " + id + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    public synchronized void migrateLegacyCurrencyIds(Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            c.setAutoCommit(false);
            int totalBalances = 0;
            int totalTx = 0;
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String from = entry.getKey();
                String to = entry.getValue();
                if (from.equals(to)) {
                    continue;
                }
                int bal;
                int tx;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE balances SET currency_id=? WHERE currency_id=?")) {
                    ps.setString(1, to);
                    ps.setString(2, from);
                    bal = ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE transactions SET currency_id=? WHERE currency_id=?")) {
                    ps.setString(1, to);
                    ps.setString(2, from);
                    tx = ps.executeUpdate();
                }
                if (bal == 0 && tx == 0) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM currencies WHERE id=?")) {
                    ps.setString(1, from);
                    ps.executeUpdate();
                }
                totalBalances += bal;
                totalTx += tx;
                plugin.getLogger().info("RaskolVault: миграция '" + from + "' → '" + to
                        + "' (балансов " + bal + ", транзакций " + tx + ")");
            }
            c.commit();
            ok = true;
        } catch (SQLException e) {
            rollbackQuiet(c);
            throw new LedgerException("Миграция ID валют провалена: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    // ---------- balances ----------

    public Map<UUID, Map<String, Double>> loadAllBalances() {
        Map<UUID, Map<String, Double>> out = new HashMap<>();
        Connection c = pool.borrow();
        boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, currency_id, amount FROM balances")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                out.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), rs.getDouble(3));
            }
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать балансы: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
        return out;
    }

    public double getBalance(UUID owner, String currencyId) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT amount FROM balances WHERE uuid=? AND currency_id=?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                return rs.next() ? rs.getDouble(1) : 0.0D;
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать баланс " + owner + "/" + currencyId + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    public void commitAbsolute(List<AbsoluteBalance> balances, List<Transaction> txs) {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            c.setAutoCommit(false);
            for (AbsoluteBalance b : balances) {
                try (PreparedStatement ps = c.prepareStatement(UPSERT_BALANCE)) {
                    ps.setString(1, b.owner().toString());
                    ps.setString(2, b.currencyId());
                    ps.setDouble(3, b.newAmount());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            for (Transaction t : txs) {
                insertTx(c, t);
            }
            c.commit();
            ok = true;
            TxPerMinuteCounter counter = txCounter;
            if (counter != null) {
                counter.record(txs.size());
            }
        } catch (SQLException e) {
            rollbackQuiet(c);
            throw new LedgerException("Атомарный коммит провален ("
                    + balances.size() + " балансов, " + txs.size() + " транзакций): " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    /**
     * 1.0.5: коммит с оптимистичной проверкой старых значений.
     * Каждая строка баланса применяется только если текущее amount в БД совпадает
     * с expectedOld. Любое расхождение (гонка, ручная правка БД, restore во время
     * работы) = LedgerException, транзакция откатывается целиком.
     */
    public void commitAbsoluteChecked(List<CheckedBalance> balances, List<Transaction> txs) {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            c.setAutoCommit(false);
            for (CheckedBalance b : balances) {
                try (PreparedStatement ps = c.prepareStatement(UPSERT_BALANCE_CHECKED)) {
                    ps.setString(1, b.owner().toString());
                    ps.setString(2, b.currencyId());
                    ps.setDouble(3, b.newAmount());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.setDouble(5, b.expectedOld());
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        throw new SQLException("optimistic lock failed: " + b.owner() + "/" + b.currencyId()
                                + " expected=" + b.expectedOld());
                    }
                }
            }
            for (Transaction t : txs) {
                insertTx(c, t);
            }
            c.commit();
            ok = true;
            TxPerMinuteCounter counter = txCounter;
            if (counter != null) {
                counter.record(txs.size());
            }
        } catch (SQLException e) {
            rollbackQuiet(c);
            throw new LedgerException("Проверенный коммит отклонён ("
                    + balances.size() + " балансов, " + txs.size() + " транзакций): " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    private long insertTx(Connection c, Transaction tx) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_TX, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, tx.timestampMillis());
            ps.setString(2, tx.from() == null ? null : tx.from().toString());
            ps.setString(3, tx.to() == null ? null : tx.to().toString());
            ps.setString(4, tx.currencyId());
            ps.setDouble(5, tx.amount());
            ps.setString(6, tx.type().name());
            ps.setString(7, tx.reason());
            ps.setString(8, tx.metadataJson());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    // ---------- агрегаты инварианта (1.0.5) ----------

    public double sumBalances(String currencyId) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM balances WHERE currency_id=?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                return rs.next() ? rs.getDouble(1) : 0.0D;
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу посчитать сумму балансов " + currencyId + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    /**
     * Ожидаемая эмиссия: +amount для (from NULL, to NOT NULL),
     * −amount для (from NOT NULL, to NULL), 0 для PAY (оба заполнены).
     */
    public double expectedSupply(String currencyId) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(CASE "
                        + "WHEN from_uuid IS NULL AND to_uuid IS NOT NULL THEN amount "
                        + "WHEN from_uuid IS NOT NULL AND to_uuid IS NULL THEN -amount "
                        + "ELSE 0 END),0) FROM transactions WHERE currency_id=?")) {
            ps.setString(1, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                return rs.next() ? rs.getDouble(1) : 0.0D;
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу посчитать ожидаемую эмиссию " + currencyId + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    // ---------- аудит-чтение ----------

    public List<Transaction> queryTransactions(UUID owner, int limit) {
        List<Transaction> out = new ArrayList<>();
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, timestamp, from_uuid, to_uuid, currency_id, amount, type, reason, metadata "
                        + "FROM transactions WHERE from_uuid=? OR to_uuid=? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, owner.toString());
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String from = rs.getString(3);
                    String to = rs.getString(4);
                    out.add(new Transaction(
                            rs.getLong(1),
                            rs.getLong(2),
                            from == null ? null : UUID.fromString(from),
                            to == null ? null : UUID.fromString(to),
                            rs.getString(5),
                            rs.getDouble(6),
                            TransactionType.valueOf(rs.getString(7)),
                            rs.getString(8),
                            rs.getString(9)));
                }
            }
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать историю " + owner + ": " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
        return out;
    }

    public int countBalances() {
        return count("SELECT COUNT(*) FROM balances");
    }

    public long countTransactions() {
        return count("SELECT COUNT(*) FROM transactions");
    }

    private int count(String sql) {
        Connection c = pool.borrow();
        boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ok = true;
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new LedgerException("Не могу посчитать строки: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    public long checkpoint() {
        Connection c = pool.borrow();
        boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            long checkpointed = -1L;
            if (rs.next()) {
                checkpointed = rs.getLong(3);
            }
            ok = true;
            return checkpointed;
        } catch (SQLException e) {
            plugin.getLogger().warning("RaskolVault: WAL checkpoint не удался: " + e.getMessage());
            return -1L;
        } finally {
            finish(c, ok);
        }
    }

    public long lastTransactionTimestamp() {
        Connection c = pool.borrow();
        boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(timestamp) FROM transactions")) {
            ok = true;
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        } finally {
            finish(c, ok);
        }
    }

    public void attachWriterStats(Supplier<String> stats) {
        this.writerStats = stats;
    }

    public String describeStats() {
        String base = "балансов " + countBalances()
                + " · транзакций " + countTransactions()
                + " · пул " + pool.size() + "/" + pool.idleCount() + " idle"
                + " · wait " + pool.waitingCount();
        Supplier<String> extra = writerStats;
        return extra == null ? base : base + extra.get();
    }

    public int poolWaiting() {
        return pool.waitingCount();
    }

    public int poolIdle() {
        return pool.idleCount();
    }

    public int poolSize() {
        return pool.size();
    }

    public File dbFile() {
        return dbFile;
    }

    private void finish(Connection c, boolean ok) {
        if (!ok) {
            pool.discard(c);
            return;
        }
        try {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            pool.release(c);
        } catch (SQLException e) {
            pool.discard(c);
        }
    }

    private void rollbackQuiet(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
        }
    }

    public void close() {
        checkpoint();
        pool.close();
    }
}
