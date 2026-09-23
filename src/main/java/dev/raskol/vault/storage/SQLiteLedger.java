// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
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
 * SQLite-леджер (1.2.4.1): + G6 guard (отрицательный баланс отклоняется)
 * + escrowRows()/reservesAll() для EconomicInvariantAuditor.
 */
public final class SQLiteLedger {

    public static final int SCHEMA_VERSION = 3;

    public record AbsoluteBalance(UUID owner, String currencyId, double newAmount) {}
    public record CheckedBalance(UUID owner, String currencyId, double expectedOld, double newAmount) {}
    public record EscrowRow(String ticket, UUID owner, String currencyId, double amount) {}
    public record ExchangeOrderRow(String id, UUID owner, String nation, String sellCurrency,
                                   String buyCurrency, double sellAmount, double buyAmount,
                                   double price, double remaining, String status,
                                   long createdAt, long updatedAt) {}

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

    public SQLiteLedger(Plugin plugin, File dbFile, int poolSize,
                        String synchronous, long borrowTimeoutMillis) throws SQLException {
        this.plugin = plugin;
        this.dbFile = dbFile;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new SQLException("Не могу создать папку леджера: " + parent.getAbsolutePath());
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new SQLException("Драйвер org.sqlite.JDBC не найден: " + e.getMessage(), e); }
        this.pool = new ConnectionPool(plugin, dbFile, poolSize, synchronous, borrowTimeoutMillis);
    }

    public void attachWriterStats(Supplier<String> stats) { this.writerStats = stats; }

    public synchronized void init() throws SQLException {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            try (Statement st = c.createStatement()) { st.execute("PRAGMA journal_mode=WAL"); }
            ok = true;
        } finally { finish(c, ok); }
        migrate();
    }

    private void migrate() throws SQLException {
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            int version;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.getInt(1);
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS currencies ("
                        + "id TEXT PRIMARY KEY, display_name TEXT NOT NULL, symbol TEXT NOT NULL,"
                        + "type TEXT NOT NULL CHECK (type IN ('GLOBAL','NATIONAL','WORLD')),"
                        + "nation_id TEXT, decimals INTEGER NOT NULL DEFAULT 2,"
                        + "tradeable INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS balances ("
                        + "uuid TEXT NOT NULL, currency_id TEXT NOT NULL, amount REAL NOT NULL DEFAULT 0,"
                        + "updated_at INTEGER NOT NULL, PRIMARY KEY (uuid, currency_id),"
                        + "FOREIGN KEY (currency_id) REFERENCES currencies(id) ON DELETE CASCADE)");
                st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,"
                        + "from_uuid TEXT, to_uuid TEXT, currency_id TEXT NOT NULL, amount REAL NOT NULL,"
                        + "type TEXT NOT NULL, reason TEXT NOT NULL, metadata TEXT,"
                        + "FOREIGN KEY (currency_id) REFERENCES currencies(id) ON DELETE CASCADE)");
                st.execute("CREATE TABLE IF NOT EXISTS reserves ("
                        + "nation TEXT PRIMARY KEY, amount REAL NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS escrow ("
                        + "ticket TEXT PRIMARY KEY, owner TEXT NOT NULL, currency_id TEXT NOT NULL,"
                        + "amount REAL NOT NULL, created_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS exchange_orders ("
                        + "id TEXT PRIMARY KEY, owner TEXT NOT NULL, nation TEXT NOT NULL,"
                        + "sell_currency TEXT NOT NULL, buy_currency TEXT NOT NULL,"
                        + "sell_amount REAL NOT NULL, buy_amount REAL NOT NULL, price REAL NOT NULL,"
                        + "remaining REAL NOT NULL, status TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS bonds ("
                        + "id TEXT PRIMARY KEY, nation TEXT NOT NULL, face REAL NOT NULL,"
                        + "coupon_rate REAL NOT NULL, issued_at INTEGER NOT NULL,"
                        + "matures_at INTEGER NOT NULL, holder TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS shares ("
                        + "id TEXT PRIMARY KEY, nation TEXT NOT NULL, owner TEXT NOT NULL,"
                        + "grams REAL NOT NULL, issued_at INTEGER NOT NULL, last_payout INTEGER NOT NULL)");
                if (version < SCHEMA_VERSION) st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
            ok = true;
        } finally { finish(c, ok); }
    }

    // ---------- currencies ----------
    public void upsertCurrency(Currency currency) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO currencies(id, display_name, symbol, type, nation_id, decimals, tradeable, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,"
                        + "symbol=excluded.symbol, type=excluded.type, nation_id=excluded.nation_id,"
                        + "decimals=excluded.decimals, tradeable=excluded.tradeable")) {
            ps.setString(1, currency.id()); ps.setString(2, currency.displayName());
            ps.setString(3, currency.symbol()); ps.setString(4, currency.type().name());
            ps.setString(5, currency.nationId()); ps.setInt(6, currency.decimals());
            ps.setInt(7, currency.tradeable() ? 1 : 0); ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("upsertCurrency: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public List<Currency> loadCurrencies() {
        List<Currency> out = new ArrayList<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, display_name, symbol, type, nation_id, decimals, tradeable FROM currencies")) {
            while (rs.next()) out.add(new Currency(rs.getString(1), rs.getString(2), rs.getString(3),
                    CurrencyType.valueOf(rs.getString(4)), rs.getString(5), rs.getInt(6), rs.getInt(7) == 1));
            ok = true;
        } catch (SQLException e) { throw new LedgerException("loadCurrencies: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    public void deleteCurrency(String id) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM currencies WHERE id=?")) {
            ps.setString(1, id); ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("deleteCurrency: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    // ---------- balances ----------
    public Map<UUID, Map<String, Double>> loadAllBalances() {
        Map<UUID, Map<String, Double>> out = new HashMap<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, currency_id, amount FROM balances")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                out.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), rs.getDouble(3));
            }
            ok = true;
        } catch (SQLException e) { throw new LedgerException("loadAllBalances: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    public double getBalance(UUID owner, String currencyId) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM balances WHERE uuid=? AND currency_id=?")) {
            ps.setString(1, owner.toString()); ps.setString(2, currencyId);
            try (ResultSet rs = ps.executeQuery()) { ok = true; return rs.next() ? rs.getDouble(1) : 0.0D; }
        } catch (SQLException e) { throw new LedgerException("getBalance: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    /** G6: отрицательный баланс отклоняется до записи. */
    private void guardNonNegative(List<AbsoluteBalance> balances) {
        for (AbsoluteBalance b : balances) {
            if (b.newAmount() < -1.0E-9D)
                throw new LedgerException("negative balance rejected: " + b.owner() + "/" + b.currencyId());
        }
    }

    public void commitAbsolute(List<AbsoluteBalance> balances, List<Transaction> txs) {
        guardNonNegative(balances);
        Connection c = pool.borrow(); boolean ok = false;
        try {
            c.setAutoCommit(false);
            for (AbsoluteBalance b : balances) {
                try (PreparedStatement ps = c.prepareStatement(UPSERT_BALANCE)) {
                    ps.setString(1, b.owner().toString()); ps.setString(2, b.currencyId());
                    ps.setDouble(3, b.newAmount()); ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            for (Transaction t : txs) insertTx(c, t);
            c.commit(); ok = true;
        } catch (SQLException e) { rollbackQuiet(c); throw new LedgerException("commitAbsolute: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public void commitAbsoluteChecked(List<CheckedBalance> balances, List<Transaction> txs) {
        List<AbsoluteBalance> abs = new ArrayList<>();
        for (CheckedBalance cb : balances) abs.add(new AbsoluteBalance(cb.owner(), cb.currencyId(), cb.newAmount()));
        guardNonNegative(abs);
        Connection c = pool.borrow(); boolean ok = false;
        try {
            c.setAutoCommit(false);
            for (CheckedBalance b : balances) {
                try (PreparedStatement ps = c.prepareStatement(UPSERT_BALANCE_CHECKED)) {
                    ps.setString(1, b.owner().toString()); ps.setString(2, b.currencyId());
                    ps.setDouble(3, b.newAmount()); ps.setLong(4, System.currentTimeMillis());
                    ps.setDouble(5, b.expectedOld());
                    if (ps.executeUpdate() == 0)
                        throw new SQLException("optimistic lock failed: " + b.owner() + "/" + b.currencyId());
                }
            }
            for (Transaction t : txs) insertTx(c, t);
            c.commit(); ok = true;
        } catch (SQLException e) { rollbackQuiet(c); throw new LedgerException("commitAbsoluteChecked: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    private long insertTx(Connection c, Transaction tx) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_TX, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, tx.timestampMillis());
            ps.setString(2, tx.from() == null ? null : tx.from().toString());
            ps.setString(3, tx.to() == null ? null : tx.to().toString());
            ps.setString(4, tx.currencyId()); ps.setDouble(5, tx.amount());
            ps.setString(6, tx.type().name()); ps.setString(7, tx.reason()); ps.setString(8, tx.metadataJson());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : -1L; }
        }
    }

    // ---------- reserves ----------
    public double reserveGet(String nation) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM reserves WHERE nation=?")) {
            ps.setString(1, nation);
            try (ResultSet rs = ps.executeQuery()) { ok = true; return rs.next() ? rs.getDouble(1) : 0.0D; }
        } catch (SQLException e) { throw new LedgerException("reserveGet: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public boolean reserveAdd(String nation, double delta) {
        Connection c = pool.borrow(); boolean ok = false;
        try {
            long now = System.currentTimeMillis(); int rows;
            if (delta >= 0.0D) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO reserves(nation, amount, updated_at) VALUES(?,?,?) "
                                + "ON CONFLICT(nation) DO UPDATE SET amount=amount+excluded.amount, updated_at=excluded.updated_at")) {
                    ps.setString(1, nation); ps.setDouble(2, delta); ps.setLong(3, now);
                    rows = ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE reserves SET amount=amount+?, updated_at=? WHERE nation=? AND amount+? >= 0")) {
                    ps.setDouble(1, delta); ps.setLong(2, now); ps.setString(3, nation); ps.setDouble(4, delta);
                    rows = ps.executeUpdate();
                }
            }
            ok = true; return rows > 0;
        } catch (SQLException e) { throw new LedgerException("reserveAdd: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public void reserveSet(String nation, double amount) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reserves(nation, amount, updated_at) VALUES(?,?,?) "
                        + "ON CONFLICT(nation) DO UPDATE SET amount=excluded.amount, updated_at=excluded.updated_at")) {
            ps.setString(1, nation); ps.setDouble(2, amount); ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("reserveSet: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public Map<String, Double> reservesAll() {
        Map<String, Double> out = new HashMap<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT nation, amount FROM reserves")) {
            while (rs.next()) out.put(rs.getString(1), rs.getDouble(2));
            ok = true;
        } catch (SQLException e) { throw new LedgerException("reservesAll: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    // ---------- escrow ----------
    public void escrowInsert(String ticket, UUID owner, String currencyId, double amount) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO escrow(ticket, owner, currency_id, amount, created_at) VALUES(?,?,?,?,?) "
                        + "ON CONFLICT(ticket) DO UPDATE SET amount=excluded.amount, created_at=excluded.created_at")) {
            ps.setString(1, ticket); ps.setString(2, owner.toString());
            ps.setString(3, currencyId); ps.setDouble(4, amount); ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("escrowInsert: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public EscrowRow escrowGet(String ticket) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT ticket, owner, currency_id, amount FROM escrow WHERE ticket=?")) {
            ps.setString(1, ticket);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                if (!rs.next()) return null;
                return new EscrowRow(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getDouble(4));
            }
        } catch (SQLException e) { throw new LedgerException("escrowGet: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public List<EscrowRow> escrowRows() {
        List<EscrowRow> out = new ArrayList<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ticket, owner, currency_id, amount FROM escrow")) {
            while (rs.next()) out.add(new EscrowRow(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getDouble(4)));
            ok = true;
        } catch (SQLException e) { throw new LedgerException("escrowRows: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    public void escrowDelete(String ticket) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM escrow WHERE ticket=?")) {
            ps.setString(1, ticket); ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("escrowDelete: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    // ---------- exchange orders ----------
    public void exchangeOrderInsert(ExchangeOrderRow o) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO exchange_orders(id, owner, nation, sell_currency, buy_currency, sell_amount, buy_amount,"
                        + "price, remaining, status, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, o.id()); ps.setString(2, o.owner().toString()); ps.setString(3, o.nation());
            ps.setString(4, o.sellCurrency()); ps.setString(5, o.buyCurrency());
            ps.setDouble(6, o.sellAmount()); ps.setDouble(7, o.buyAmount()); ps.setDouble(8, o.price());
            ps.setDouble(9, o.remaining()); ps.setString(10, o.status());
            ps.setLong(11, o.createdAt()); ps.setLong(12, o.updatedAt());
            ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("exchangeOrderInsert: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public boolean exchangeOrderUpdateRemaining(String orderId, double remaining, String status) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE exchange_orders SET remaining=?, status=?, updated_at=? WHERE id=?")) {
            ps.setDouble(1, remaining); ps.setString(2, status);
            ps.setLong(3, System.currentTimeMillis()); ps.setString(4, orderId);
            ok = true; return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw new LedgerException("exchangeOrderUpdateRemaining: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public ExchangeOrderRow exchangeOrderGet(String orderId) {
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner, nation, sell_currency, buy_currency, sell_amount, buy_amount, price, remaining,"
                        + "status, created_at, updated_at FROM exchange_orders WHERE id=?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                if (!rs.next()) return null;
                return new ExchangeOrderRow(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8),
                        rs.getDouble(9), rs.getString(10), rs.getLong(11), rs.getLong(12));
            }
        } catch (SQLException e) { throw new LedgerException("exchangeOrderGet: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public List<ExchangeOrderRow> exchangeOrdersByStatus(String status, int limit) {
        List<ExchangeOrderRow> out = new ArrayList<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner, nation, sell_currency, buy_currency, sell_amount, buy_amount, price, remaining,"
                        + "status, created_at, updated_at FROM exchange_orders WHERE status=? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, status); ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ExchangeOrderRow(rs.getString(1), UUID.fromString(rs.getString(2)),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7),
                        rs.getDouble(8), rs.getDouble(9), rs.getString(10), rs.getLong(11), rs.getLong(12)));
            }
            ok = true;
        } catch (SQLException e) { throw new LedgerException("exchangeOrdersByStatus: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    public List<ExchangeOrderRow> exchangeOrdersByOwner(UUID owner) {
        List<ExchangeOrderRow> out = new ArrayList<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner, nation, sell_currency, buy_currency, sell_amount, buy_amount, price, remaining,"
                        + "status, created_at, updated_at FROM exchange_orders WHERE owner=? ORDER BY created_at DESC")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ExchangeOrderRow(rs.getString(1), UUID.fromString(rs.getString(2)),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getDouble(7),
                        rs.getDouble(8), rs.getDouble(9), rs.getString(10), rs.getLong(11), rs.getLong(12)));
            }
            ok = true;
        } catch (SQLException e) { throw new LedgerException("exchangeOrdersByOwner: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    // ---------- аудит / статистика ----------
    public List<Transaction> queryTransactions(UUID owner, int limit) {
        List<Transaction> out = new ArrayList<>();
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, timestamp, from_uuid, to_uuid, currency_id, amount, type, reason, metadata "
                        + "FROM transactions WHERE from_uuid=? OR to_uuid=? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, owner.toString()); ps.setString(2, owner.toString()); ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String from = rs.getString(3), to = rs.getString(4);
                    out.add(new Transaction(rs.getLong(1), rs.getLong(2),
                            from == null ? null : UUID.fromString(from),
                            to == null ? null : UUID.fromString(to),
                            rs.getString(5), rs.getDouble(6), TransactionType.valueOf(rs.getString(7)),
                            rs.getString(8), rs.getString(9)));
                }
            }
            ok = true;
        } catch (SQLException e) { throw new LedgerException("queryTransactions: " + e.getMessage(), e); }
        finally { finish(c, ok); }
        return out;
    }

    public int countBalances() { return count("SELECT COUNT(*) FROM balances"); }
    public long countTransactions() { return count("SELECT COUNT(*) FROM transactions"); }
    private int count(String sql) {
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ok = true; return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new LedgerException("count: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public long checkpoint() {
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            long cp = -1L; if (rs.next()) cp = rs.getLong(3);
            ok = true; return cp;
        } catch (SQLException e) { plugin.getLogger().warning("WAL checkpoint: " + e.getMessage()); return -1L; }
        finally { finish(c, ok); }
    }

    public long lastTransactionTimestamp() {
        Connection c = pool.borrow(); boolean ok = false;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(timestamp) FROM transactions")) {
            ok = true; return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) { return 0L; }
        finally { finish(c, ok); }
    }

    public String describeStats() {
        String base = "балансов " + countBalances() + " · транзакций " + countTransactions()
                + " · пул " + pool.size() + "/" + pool.idleCount() + " idle · wait " + pool.waitingCount();
        Supplier<String> extra = writerStats;
        return extra == null ? base : base + extra.get();
    }

    public int poolIdle() { return pool.idleCount(); }
    public int poolWaiting() { return pool.waitingCount(); }
    public int poolSize() { return pool.size(); }
    public File dbFile() { return dbFile; }

    private void finish(Connection c, boolean ok) {
        if (!ok) { pool.discard(c); return; }
        try {
            if (!c.getAutoCommit()) c.setAutoCommit(true);
            pool.release(c);
        } catch (SQLException e) { pool.discard(c); }
    }
    private void rollbackQuiet(Connection c) { try { c.rollback(); } catch (SQLException ignored) {} }

    public void close() { checkpoint(); pool.close(); }
}
