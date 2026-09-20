// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite-леджер: валюты, балансы, журнал транзакций (аудит, анти-дюп, откаты).
 * Одно соединение + synchronized: пре-лаунч объёмы не требуют пула.
 * WAL + synchronous=NORMAL: скорость без риска потерять коммит при краше.
 * Миграции через PRAGMA user_version (текущая схема v1).
 *
 * 1.0.1: migrateLegacyCurrencyIds логируется только при фактическом переносе строк.
 */
public final class SQLiteLedger {

    public static final int SCHEMA_VERSION = 1;

    private final Plugin plugin;
    private final File dbFile;
    private Connection connection;

    public SQLiteLedger(Plugin plugin, File dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    public synchronized void init() throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Не могу создать папку леджера: " + parent.getAbsolutePath());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Драйвер org.sqlite.JDBC не найден в jar (проверь shade в pom): " + e.getMessage(), e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        int version;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        }
        if (version < SCHEMA_VERSION) {
            try (Statement st = connection.createStatement()) {
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
    }

    // ---------- currencies ----------

    public synchronized void upsertCurrency(Currency currency) {
        try (PreparedStatement ps = connection.prepareStatement(
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
        } catch (SQLException e) {
            throw new LedgerException("Не могу записать валюту " + currency.id() + ": " + e.getMessage(), e);
        }
    }

    public synchronized List<Currency> loadCurrencies() {
        List<Currency> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, display_name, symbol, type, nation_id, decimals, tradeable FROM currencies")) {
            while (rs.next()) {
                out.add(new Currency(rs.getString(1), rs.getString(2), rs.getString(3),
                        CurrencyType.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getInt(6), rs.getInt(7) == 1));
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать валюты: " + e.getMessage(), e);
        }
        return out;
    }

    public synchronized void deleteCurrency(String id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM currencies WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("Не могу удалить валюту " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Миграция старых строчных ID (gold/denarius/crown) в 3-буквенные капсом (GLD/RAS/VLR).
     * Идемпотентна: повторный вызов ничего не переносит и молчит.
     */
    public synchronized void migrateLegacyCurrencyIds(Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return;
        }
        try {
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String from = entry.getKey();
                String to = entry.getValue();
                if (from.equals(to)) {
                    continue;
                }
                int balanceUpdates;
                int txUpdates;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE balances SET currency_id=? WHERE currency_id=?")) {
                    ps.setString(1, to);
                    ps.setString(2, from);
                    balanceUpdates = ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE transactions SET currency_id=? WHERE currency_id=?")) {
                    ps.setString(1, to);
                    ps.setString(2, from);
                    txUpdates = ps.executeUpdate();
                }
                if (balanceUpdates == 0 && txUpdates == 0) {
                    // Нечего переносить: не шумим в лог на каждом старте
                    continue;
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM currencies WHERE id=?")) {
                    ps.setString(1, from);
                    ps.executeUpdate();
                }
                plugin.getLogger().info("RaskolVault: миграция '" + from + "' → '" + to
                        + "' (балансов " + balanceUpdates + ", транзакций " + txUpdates + ")");
            }
        } catch (SQLException e) {
            throw new LedgerException("Миграция ID валют провалена: " + e.getMessage(), e);
        }
    }

    // ---------- balances ----------

    public synchronized Map<UUID, Map<String, Double>> loadAllBalances() {
        Map<UUID, Map<String, Double>> out = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, currency_id, amount FROM balances")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                out.computeIfAbsent(uuid, k -> new HashMap<>()).put(rs.getString(2), rs.getDouble(3));
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать балансы: " + e.getMessage(), e);
        }
        return out;
    }

    public synchronized double getBalance(UUID owner, String currencyId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT amount FROM balances WHERE uuid=? AND currency_id=?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0.0D;
            }
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать баланс " + owner + "/" + currencyId + ": " + e.getMessage(), e);
        }
    }

    public synchronized void setBalance(UUID owner, String currencyId, double amount) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO balances(uuid, currency_id, amount, updated_at) VALUES(?,?,?,?) "
                        + "ON CONFLICT(uuid, currency_id) DO UPDATE SET "
                        + "amount=excluded.amount, updated_at=excluded.updated_at")) {
            ps.setString(1, owner.toString());
            ps.setString(2, currencyId);
            ps.setDouble(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerException("Не могу записать баланс " + owner + "/" + currencyId + ": " + e.getMessage(), e);
        }
    }

    // ---------- transactions ----------

    public synchronized long recordTransaction(Transaction tx) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO transactions(timestamp, from_uuid, to_uuid, currency_id, amount, type, reason, metadata) "
                        + "VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
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
        } catch (SQLException e) {
            throw new LedgerException("Не могу записать транзакцию " + tx.type() + ": " + e.getMessage(), e);
        }
    }

    public synchronized List<Transaction> queryTransactions(UUID owner, int limit) {
        List<Transaction> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
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
        } catch (SQLException e) {
            throw new LedgerException("Не могу прочитать историю " + owner + ": " + e.getMessage(), e);
        }
        return out;
    }

    public synchronized int countBalances() {
        return count("SELECT COUNT(*) FROM balances");
    }

    public synchronized long countTransactions() {
        return count("SELECT COUNT(*) FROM transactions");
    }

    private int count(String sql) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new LedgerException("Не могу посчитать строки: " + e.getMessage(), e);
        }
    }

    public synchronized String describeStats() {
        return "балансов " + countBalances() + " · транзакций " + countTransactions();
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("SQLiteLedger: ошибка закрытия соединения: " + e.getMessage());
            }
            connection = null;
        }
    }
}
