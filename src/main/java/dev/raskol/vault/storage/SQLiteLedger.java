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

    public static final int SCHEMA_VERSION = 7;

    public record AbsoluteBalance(UUID owner, String currencyId, double newAmount) {}
    public record CheckedBalance(UUID owner, String currencyId, double expectedOld, double newAmount) {}
    public record CheckedReserve(String nation, double expectedOld, double newAmount) {}
    public record CheckedEscrow(String ticket, UUID owner, String currencyId,
                                double expectedOld, double newAmount, boolean delete) {}
    public record EscrowRow(String ticket, UUID owner, String currencyId, double amount) {}
    public record BankAccountRow(String id, UUID owner, String ownerName, String nation,
                                 String currencyId, double principal, String term, double rateAnnual,
                                 long openedAt, long maturesAt, double accrued, long lastAccrualAt, String status) {}
    public record BankLoanRow(String id, UUID borrower, String borrowerName, String nation,
                              double principal, String currencyId, double rateAnnual, int termDays,
                              long openedAt, long dueAt, double repaid, double accrued, long lastAccrualAt,
                              String status, String collateralType, String collateralCurrency,
                              String collateralItem, double collateralValue, double collateralAmount,
                              double collateralPrice, int creditScoreAtOpen) {
        public BankLoanRow(String id, UUID borrower, String borrowerName, String nation,
                           double principal, String currencyId, double rateAnnual, int termDays,
                           long openedAt, long dueAt, double repaid, double accrued, long lastAccrualAt,
                           String status, String collateralType, String collateralCurrency,
                           String collateralItem, double collateralValue, int creditScoreAtOpen) {
            this(id, borrower, borrowerName, nation, principal, currencyId, rateAnnual, termDays,
                    openedAt, dueAt, repaid, accrued, lastAccrualAt, status, collateralType,
                    collateralCurrency, collateralItem, collateralValue,
                    "CURRENCY".equals(collateralType) ? collateralValue : 0.0D, 1.0D, creditScoreAtOpen);
        }
    }
    public record BankPoolRow(String nation, String currencyId, double pool, double interestReserve) {}
    public record BankCreditRow(UUID player, int repaidCount, int defaultedCount) {}
    public record BankPendingRow(String returnId, UUID player, String itemBase64) {}
    public record BankSnapshot(List<BankAccountRow> accounts, List<BankLoanRow> loans,
                               List<BankPoolRow> pools, List<BankCreditRow> credit,
                               List<BankPendingRow> pendingReturns) {
        public BankSnapshot {
            accounts = List.copyOf(accounts);
            loans = List.copyOf(loans);
            pools = List.copyOf(pools);
            credit = List.copyOf(credit);
            pendingReturns = List.copyOf(pendingReturns);
        }
    }
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
                st.execute("CREATE TABLE IF NOT EXISTS bank_accounts ("
                        + "id TEXT PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT NOT NULL,"
                        + "nation TEXT NOT NULL, currency_id TEXT NOT NULL, principal REAL NOT NULL,"
                        + "term TEXT NOT NULL, rate_annual REAL NOT NULL, opened_at INTEGER NOT NULL,"
                        + "matures_at INTEGER NOT NULL, accrued REAL NOT NULL, last_accrual_at INTEGER NOT NULL,"
                        + "status TEXT NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_bank_accounts_owner ON bank_accounts(owner)");
                st.execute("CREATE TABLE IF NOT EXISTS bank_loans ("
                        + "id TEXT PRIMARY KEY, borrower TEXT NOT NULL, borrower_name TEXT NOT NULL,"
                        + "nation TEXT NOT NULL, principal REAL NOT NULL, currency_id TEXT NOT NULL,"
                        + "rate_annual REAL NOT NULL, term_days INTEGER NOT NULL, opened_at INTEGER NOT NULL,"
                        + "due_at INTEGER NOT NULL, repaid REAL NOT NULL, accrued REAL NOT NULL,"
                        + "last_accrual_at INTEGER NOT NULL, status TEXT NOT NULL, collateral_type TEXT NOT NULL,"
                        + "collateral_currency TEXT, collateral_item TEXT, collateral_value REAL NOT NULL,"
                        + "credit_score_at_open INTEGER NOT NULL, collateral_amount REAL NOT NULL DEFAULT 0,"
                        + "collateral_price REAL NOT NULL DEFAULT 1)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_bank_loans_borrower ON bank_loans(borrower)");
                boolean hasCollateralAmount = false;
                boolean hasCollateralPrice = false;
                try (ResultSet columns = st.executeQuery("PRAGMA table_info(bank_loans)")) {
                    while (columns.next()) {
                        String column = columns.getString("name");
                        if ("collateral_amount".equalsIgnoreCase(column)) hasCollateralAmount = true;
                        if ("collateral_price".equalsIgnoreCase(column)) hasCollateralPrice = true;
                    }
                }
                if (!hasCollateralAmount) {
                    st.execute("ALTER TABLE bank_loans ADD COLUMN collateral_amount REAL NOT NULL DEFAULT 0");
                }
                if (!hasCollateralPrice) {
                    st.execute("ALTER TABLE bank_loans ADD COLUMN collateral_price REAL NOT NULL DEFAULT 1");
                }
                // Для старых валютных кредитов сохраняем историческую оценку и
                // восстанавливаем прежнее количество залога с legacy-курсом 1:1.
                st.execute("UPDATE bank_loans SET collateral_amount=collateral_value "
                        + "WHERE collateral_type='CURRENCY' AND collateral_amount=0 "
                        + "AND collateral_value>0");
                st.execute("CREATE TABLE IF NOT EXISTS bank_pools ("
                        + "nation TEXT NOT NULL, currency_id TEXT NOT NULL, pool REAL NOT NULL,"
                        + "interest_reserve REAL NOT NULL, PRIMARY KEY(nation,currency_id))");
                st.execute("CREATE TABLE IF NOT EXISTS bank_credit ("
                        + "player TEXT PRIMARY KEY, repaid_count INTEGER NOT NULL, defaulted_count INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS bank_pending_returns ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, return_id TEXT, player TEXT NOT NULL, item_base64 TEXT NOT NULL)");
                boolean hasReturnId = false;
                try (ResultSet columns = st.executeQuery("PRAGMA table_info(bank_pending_returns)")) {
                    while (columns.next()) {
                        if ("return_id".equalsIgnoreCase(columns.getString("name"))) hasReturnId = true;
                    }
                }
                if (!hasReturnId) st.execute("ALTER TABLE bank_pending_returns ADD COLUMN return_id TEXT");
                try (Statement rows = c.createStatement();
                     ResultSet pending = rows.executeQuery("SELECT id FROM bank_pending_returns WHERE return_id IS NULL")) {
                    List<Long> legacyIds = new ArrayList<>();
                    while (pending.next()) legacyIds.add(pending.getLong(1));
                    try (PreparedStatement update = c.prepareStatement(
                            "UPDATE bank_pending_returns SET return_id=? WHERE id=?")) {
                        for (Long legacyId : legacyIds) {
                            update.setString(1, UUID.randomUUID().toString());
                            update.setLong(2, legacyId);
                            update.addBatch();
                        }
                        update.executeBatch();
                    }
                }
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_bank_pending_return_id ON bank_pending_returns(return_id)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_bank_pending_player ON bank_pending_returns(player)");
                st.execute("CREATE TABLE IF NOT EXISTS bank_storage_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS operation_requests ("
                        + "operation_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL)");
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
            if (b.owner() == null || b.currencyId() == null || b.currencyId().isBlank()
                    || !Double.isFinite(b.newAmount()) || b.newAmount() < -1.0E-9D) {
                throw new LedgerException("invalid balance rejected: "
                        + b.owner() + "/" + b.currencyId() + "=" + b.newAmount());
            }
        }
    }

    private void guardTransactions(List<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            if (transaction == null || transaction.currencyId() == null
                    || transaction.currencyId().isBlank() || transaction.type() == null
                    || !Double.isFinite(transaction.amount()) || transaction.amount() <= 0.0D) {
                throw new LedgerException("invalid ledger transaction rejected");
            }
        }
    }

    public void commitAbsolute(List<AbsoluteBalance> balances, List<Transaction> txs) {
        guardNonNegative(balances);
        guardTransactions(txs);
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
        validateCheckedBalances(balances);
        guardTransactions(txs);

        Connection c = pool.borrow();
        boolean ok = false;
        try {
            c.setAutoCommit(false);
            applyCheckedBalances(c, balances);
            for (Transaction transaction : txs) {
                insertTx(c, transaction);
            }
            c.commit();
            ok = true;
        } catch (SQLException e) {
            rollbackQuiet(c);
            throw new LedgerException("commitAbsoluteChecked: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    private void validateCheckedBalances(List<CheckedBalance> balances) {
        List<AbsoluteBalance> absolute = new ArrayList<>();
        for (CheckedBalance balance : balances) {
            absolute.add(new AbsoluteBalance(balance.owner(), balance.currencyId(), balance.newAmount()));
            if (!Double.isFinite(balance.expectedOld()) || balance.expectedOld() < 0.0D) {
                throw new LedgerException("invalid expected balance: "
                        + balance.owner() + "/" + balance.currencyId());
            }
        }
        guardNonNegative(absolute);
    }

    /** Проверяет наличие завершённой операции по уникальному ключу. */
    public boolean operationExists(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128) {
            return false;
        }
        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM operation_requests WHERE operation_id=?")) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerException("operationExists: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    /** Проверяет, был ли уже подтверждён запрос с данным ключом. */
    public boolean operationCommitted(String operationId, String fingerprint) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128
                || fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 512) {
            throw new LedgerException("invalid idempotency key or fingerprint");
        }

        Connection c = pool.borrow();
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT fingerprint FROM operation_requests WHERE operation_id=?")) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                ok = true;
                if (!rs.next()) {
                    return false;
                }
                if (!fingerprint.equals(rs.getString(1))) {
                    throw new LedgerException("idempotency key reused with a different payload");
                }
                return true;
            }
        } catch (SQLException e) {
            throw new LedgerException("operationCommitted: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    /**
     * Идемпотентная транзакция кошелька. Повтор с тем же ключом и отпечатком
     * подтверждает уже совершённую операцию, не меняя балансы повторно.
     */
    public boolean commitAbsoluteCheckedOnce(String operationId, String fingerprint,
                                             List<CheckedBalance> balances,
                                             List<Transaction> txs) {
        return commitAbsoluteCheckedOnce(operationId, fingerprint, balances,
                List.of(), List.of(), txs);
    }

    public boolean commitAbsoluteCheckedOnce(String operationId, String fingerprint,
                                             List<CheckedBalance> balances,
                                             List<CheckedReserve> reserves,
                                             List<Transaction> txs) {
        return commitAbsoluteCheckedOnce(operationId, fingerprint, balances,
                reserves, List.of(), txs);
    }

    public boolean commitAbsoluteCheckedOnce(String operationId, String fingerprint,
                                             List<CheckedBalance> balances,
                                             List<CheckedReserve> reserves,
                                             List<CheckedEscrow> escrows,
                                             List<Transaction> txs) {
        return commitAbsoluteCheckedOnce(operationId, fingerprint, balances, reserves, escrows, null, txs);
    }

    /**
     * Единый коммит кошельков, резервов, escrow, банковского снимка и финансового журнала.
     * Банковский снимок записывается тем же соединением и откатывается вместе с кошельком.
     */
    public boolean commitAbsoluteCheckedOnce(String operationId, String fingerprint,
                                             List<CheckedBalance> balances,
                                             List<CheckedReserve> reserves,
                                             List<CheckedEscrow> escrows,
                                             BankSnapshot bankSnapshot,
                                             List<Transaction> txs) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128
                || fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 512) {
            throw new LedgerException("invalid idempotency key or fingerprint");
        }

        if (bankSnapshot != null) {
            validateBankSnapshot(bankSnapshot);
        }
        Connection c = pool.borrow();
        boolean ok = false;
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT fingerprint FROM operation_requests WHERE operation_id=?")) {
                ps.setString(1, operationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (!fingerprint.equals(rs.getString(1))) {
                            throw new SQLException("idempotency key reused with a different payload");
                        }
                        c.commit();
                        ok = true;
                        return true;
                    }
                }
            }

            validateCheckedBalances(balances);
            validateCheckedReserves(reserves);
            validateCheckedEscrows(escrows);
            guardTransactions(txs);
            applyCheckedBalances(c, balances);
            applyCheckedReserves(c, reserves);
            applyCheckedEscrows(c, escrows);
            if (bankSnapshot != null) {
                replaceBankSnapshot(c, bankSnapshot);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO operation_requests(operation_id, fingerprint, created_at) VALUES(?,?,?)")) {
                ps.setString(1, operationId);
                ps.setString(2, fingerprint);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            for (Transaction transaction : txs) {
                insertTx(c, transaction);
            }
            c.commit();
            ok = true;
            return true;
        } catch (SQLException e) {
            rollbackQuiet(c);
            throw new LedgerException("commitAbsoluteCheckedOnce: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
    }

    private void validateBankSnapshot(BankSnapshot snapshot) {
        for (BankAccountRow row : snapshot.accounts()) {
            if (row == null || blank(row.id()) || row.owner() == null || blank(row.nation())
                    || blank(row.currencyId()) || blank(row.term()) || blank(row.status())
                    || !Double.isFinite(row.principal()) || row.principal() <= 0.0D
                    || !Double.isFinite(row.rateAnnual()) || row.rateAnnual() < 0.0D
                    || !Double.isFinite(row.accrued()) || row.accrued() < 0.0D) {
                throw new LedgerException("invalid bank account row in atomic snapshot");
            }
        }
        for (BankLoanRow row : snapshot.loans()) {
            if (row == null || blank(row.id()) || row.borrower() == null || blank(row.nation())
                    || blank(row.currencyId()) || blank(row.status()) || blank(row.collateralType())
                    || !Double.isFinite(row.principal()) || row.principal() < 0.0D
                    || ("ACTIVE".equals(row.status()) && row.principal() <= 0.0D)
                    || !Double.isFinite(row.rateAnnual()) || row.rateAnnual() < 0.0D
                    || !Double.isFinite(row.repaid()) || row.repaid() < 0.0D
                    || !Double.isFinite(row.accrued()) || row.accrued() < 0.0D
                    || !Double.isFinite(row.collateralValue()) || row.collateralValue() < 0.0D
                    || !Double.isFinite(row.collateralAmount()) || row.collateralAmount() < 0.0D
                    || !Double.isFinite(row.collateralPrice()) || row.collateralPrice() <= 0.0D) {
                throw new LedgerException("invalid bank loan row in atomic snapshot");
            }
        }
        for (BankPoolRow row : snapshot.pools()) {
            if (row == null || blank(row.nation()) || blank(row.currencyId())
                    || !Double.isFinite(row.pool()) || row.pool() < 0.0D
                    || !Double.isFinite(row.interestReserve()) || row.interestReserve() < 0.0D) {
                throw new LedgerException("invalid bank pool row in atomic snapshot");
            }
        }
        for (BankCreditRow row : snapshot.credit()) {
            if (row == null || row.player() == null || row.repaidCount() < 0 || row.defaultedCount() < 0) {
                throw new LedgerException("invalid bank credit row in atomic snapshot");
            }
        }
        for (BankPendingRow row : snapshot.pendingReturns()) {
            if (row == null || blank(row.returnId()) || row.player() == null || blank(row.itemBase64())) {
                throw new LedgerException("invalid pending collateral row in atomic snapshot");
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void replaceBankSnapshot(Connection c, BankSnapshot snapshot) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM bank_accounts");
            st.executeUpdate("DELETE FROM bank_loans");
            st.executeUpdate("DELETE FROM bank_pools");
            st.executeUpdate("DELETE FROM bank_credit");
            st.executeUpdate("DELETE FROM bank_pending_returns");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_accounts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (BankAccountRow r : snapshot.accounts()) {
                ps.setString(1,r.id()); ps.setString(2,r.owner().toString()); ps.setString(3,r.ownerName());
                ps.setString(4,r.nation()); ps.setString(5,r.currencyId()); ps.setDouble(6,r.principal());
                ps.setString(7,r.term()); ps.setDouble(8,r.rateAnnual()); ps.setLong(9,r.openedAt());
                ps.setLong(10,r.maturesAt()); ps.setDouble(11,r.accrued()); ps.setLong(12,r.lastAccrualAt());
                ps.setString(13,r.status()); ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_loans(id,borrower,borrower_name,nation,principal,currency_id,rate_annual,term_days,opened_at,due_at,repaid,accrued,last_accrual_at,status,collateral_type,collateral_currency,collateral_item,collateral_value,credit_score_at_open,collateral_amount,collateral_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (BankLoanRow r : snapshot.loans()) {
                ps.setString(1,r.id()); ps.setString(2,r.borrower().toString()); ps.setString(3,r.borrowerName());
                ps.setString(4,r.nation()); ps.setDouble(5,r.principal()); ps.setString(6,r.currencyId());
                ps.setDouble(7,r.rateAnnual()); ps.setInt(8,r.termDays()); ps.setLong(9,r.openedAt());
                ps.setLong(10,r.dueAt()); ps.setDouble(11,r.repaid()); ps.setDouble(12,r.accrued());
                ps.setLong(13,r.lastAccrualAt()); ps.setString(14,r.status()); ps.setString(15,r.collateralType());
                ps.setString(16,r.collateralCurrency()); ps.setString(17,r.collateralItem());
                ps.setDouble(18,r.collateralValue()); ps.setInt(19,r.creditScoreAtOpen());
                ps.setDouble(20,r.collateralAmount()); ps.setDouble(21,r.collateralPrice()); ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_pools VALUES(?,?,?,?)")) {
            for (BankPoolRow r : snapshot.pools()) {
                ps.setString(1,r.nation()); ps.setString(2,r.currencyId());
                ps.setDouble(3,r.pool()); ps.setDouble(4,r.interestReserve()); ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_credit VALUES(?,?,?)")) {
            for (BankCreditRow r : snapshot.credit()) {
                ps.setString(1,r.player().toString()); ps.setInt(2,r.repaidCount());
                ps.setInt(3,r.defaultedCount()); ps.addBatch();
            }
            ps.executeBatch();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bank_pending_returns(return_id,player,item_base64) VALUES(?,?,?)")) {
            for (BankPendingRow r : snapshot.pendingReturns()) {
                ps.setString(1,r.returnId()); ps.setString(2,r.player().toString());
                ps.setString(3,r.itemBase64()); ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void validateCheckedEscrows(List<CheckedEscrow> escrows) {
        for (CheckedEscrow escrow : escrows) {
            if (escrow.ticket() == null || escrow.ticket().isBlank()
                    || escrow.owner() == null || escrow.currencyId() == null
                    || escrow.currencyId().isBlank()
                    || !Double.isFinite(escrow.expectedOld()) || escrow.expectedOld() < 0.0D
                    || !Double.isFinite(escrow.newAmount()) || escrow.newAmount() < 0.0D
                    || (escrow.delete() && escrow.newAmount() != 0.0D)
                    || (!escrow.delete() && escrow.newAmount() <= 0.0D)) {
                throw new LedgerException("invalid checked escrow: "
                        + (escrow.ticket() == null ? "null" : escrow.ticket()));
            }
        }
    }

    private void applyCheckedEscrows(Connection c, List<CheckedEscrow> escrows) throws SQLException {
        for (CheckedEscrow escrow : escrows) {
            int changed;
            if (Double.compare(escrow.expectedOld(), 0.0D) == 0) {
                if (escrow.delete()) {
                    throw new SQLException("cannot delete a missing escrow ticket: " + escrow.ticket());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO escrow(ticket, owner, currency_id, amount, created_at) "
                                + "VALUES(?,?,?,?,?) ON CONFLICT(ticket) DO NOTHING")) {
                    ps.setString(1, escrow.ticket());
                    ps.setString(2, escrow.owner().toString());
                    ps.setString(3, escrow.currencyId());
                    ps.setDouble(4, escrow.newAmount());
                    ps.setLong(5, System.currentTimeMillis());
                    changed = ps.executeUpdate();
                }
            } else if (escrow.delete()) {
                // Сохраняем tombstone: ticket нельзя повторно использовать после закрытия.
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE escrow SET amount=0 WHERE ticket=? AND owner=? AND currency_id=? AND amount=?")) {
                    ps.setString(1, escrow.ticket());
                    ps.setString(2, escrow.owner().toString());
                    ps.setString(3, escrow.currencyId());
                    ps.setDouble(4, escrow.expectedOld());
                    changed = ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE escrow SET amount=? WHERE ticket=? AND owner=? AND currency_id=? AND amount=?")) {
                    ps.setDouble(1, escrow.newAmount());
                    ps.setString(2, escrow.ticket());
                    ps.setString(3, escrow.owner().toString());
                    ps.setString(4, escrow.currencyId());
                    ps.setDouble(5, escrow.expectedOld());
                    changed = ps.executeUpdate();
                }
            }

            if (changed == 0) {
                throw new SQLException("escrow optimistic lock failed: " + escrow.ticket());
            }
        }
    }

    private void validateCheckedReserves(List<CheckedReserve> reserves) {
        for (CheckedReserve reserve : reserves) {
            if (reserve.nation() == null || reserve.nation().isBlank()
                    || !Double.isFinite(reserve.expectedOld()) || reserve.expectedOld() < 0.0D
                    || !Double.isFinite(reserve.newAmount()) || reserve.newAmount() < -1.0E-9D) {
                throw new LedgerException("invalid checked reserve: " + reserve.nation());
            }
        }
    }

    private void applyCheckedReserves(Connection c, List<CheckedReserve> reserves) throws SQLException {
        for (CheckedReserve reserve : reserves) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reserves SET amount=?, updated_at=? WHERE nation=? AND amount=?")) {
                ps.setDouble(1, Math.max(0.0D, reserve.newAmount()));
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, reserve.nation());
                ps.setDouble(4, reserve.expectedOld());
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                if (Double.compare(reserve.expectedOld(), 0.0D) != 0) {
                    throw new SQLException("reserve optimistic lock failed: " + reserve.nation());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO reserves(nation, amount, updated_at) VALUES(?,?,?) "
                                + "ON CONFLICT(nation) DO NOTHING")) {
                    ps.setString(1, reserve.nation());
                    ps.setDouble(2, Math.max(0.0D, reserve.newAmount()));
                    ps.setLong(3, System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("reserve optimistic insert conflict: " + reserve.nation());
                    }
                }
            }
        }
    }

    private void applyCheckedBalances(Connection c, List<CheckedBalance> balances) throws SQLException {
        for (CheckedBalance balance : balances) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET amount=?, updated_at=? WHERE uuid=? AND currency_id=? AND amount=?")) {
                ps.setDouble(1, balance.newAmount());
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, balance.owner().toString());
                ps.setString(4, balance.currencyId());
                ps.setDouble(5, balance.expectedOld());
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                if (Double.compare(balance.expectedOld(), 0.0D) != 0) {
                    throw new SQLException("optimistic lock failed: "
                            + balance.owner() + "/" + balance.currencyId());
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO balances(uuid, currency_id, amount, updated_at) "
                                + "VALUES(?,?,?,?) ON CONFLICT(uuid, currency_id) DO NOTHING")) {
                    ps.setString(1, balance.owner().toString());
                    ps.setString(2, balance.currencyId());
                    ps.setDouble(3, balance.newAmount());
                    ps.setLong(4, System.currentTimeMillis());
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("optimistic insert conflict: "
                                + balance.owner() + "/" + balance.currencyId());
                    }
                }
            }
        }
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

    // ---------- банковское хранилище ----------
    public boolean bankStorageInitialized() { return "1".equals(bankMeta("yaml_import_v1")); }

    public void markBankStorageInitialized() {
        Connection c=pool.borrow(); boolean ok=false;
        try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_storage_meta(key,value) VALUES('yaml_import_v1','1') ON CONFLICT(key) DO UPDATE SET value='1'")) {
            ps.executeUpdate(); ok=true;
        } catch(SQLException e) { throw new LedgerException("markBankStorageInitialized: "+e.getMessage(),e); }
        finally { finish(c,ok); }
    }

    private String bankMeta(String key) {
        Connection c=pool.borrow(); boolean ok=false;
        try(PreparedStatement ps=c.prepareStatement("SELECT value FROM bank_storage_meta WHERE key=?")) {
            ps.setString(1,key);
            try(ResultSet rs=ps.executeQuery()) { ok=true; return rs.next()?rs.getString(1):null; }
        } catch(SQLException e) { throw new LedgerException("bankMeta: "+e.getMessage(),e); }
        finally { finish(c,ok); }
    }

    public void replaceBankAccounts(List<BankAccountRow> rows) {
        Connection c=pool.borrow(); boolean ok=false;
        try {
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM bank_accounts");}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_accounts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for(BankAccountRow r:rows) {
                    ps.setString(1,r.id()); ps.setString(2,r.owner().toString()); ps.setString(3,r.ownerName());
                    ps.setString(4,r.nation()); ps.setString(5,r.currencyId()); ps.setDouble(6,r.principal());
                    ps.setString(7,r.term()); ps.setDouble(8,r.rateAnnual()); ps.setLong(9,r.openedAt());
                    ps.setLong(10,r.maturesAt()); ps.setDouble(11,r.accrued()); ps.setLong(12,r.lastAccrualAt());
                    ps.setString(13,r.status()); ps.addBatch();
                } ps.executeBatch();
            }
            c.commit(); ok=true;
        } catch(SQLException e){rollbackQuiet(c);throw new LedgerException("replaceBankAccounts: "+e.getMessage(),e);}
        finally{finish(c,ok);}
    }

    public List<BankAccountRow> bankAccountsAll() {
        List<BankAccountRow> out=new ArrayList<>(); Connection c=pool.borrow(); boolean ok=false;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM bank_accounts")) {
            while(rs.next()) out.add(new BankAccountRow(rs.getString("id"),UUID.fromString(rs.getString("owner")),
                    rs.getString("owner_name"),rs.getString("nation"),rs.getString("currency_id"),rs.getDouble("principal"),
                    rs.getString("term"),rs.getDouble("rate_annual"),rs.getLong("opened_at"),rs.getLong("matures_at"),
                    rs.getDouble("accrued"),rs.getLong("last_accrual_at"),rs.getString("status")));
            ok=true;
        } catch(SQLException e){throw new LedgerException("bankAccountsAll: "+e.getMessage(),e);}
        finally{finish(c,ok);} return out;
    }

    public void replaceBankLoans(List<BankLoanRow> rows) {
        Connection c=pool.borrow(); boolean ok=false;
        try {
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM bank_loans");}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_loans(id,borrower,borrower_name,nation,principal,currency_id,rate_annual,term_days,opened_at,due_at,repaid,accrued,last_accrual_at,status,collateral_type,collateral_currency,collateral_item,collateral_value,credit_score_at_open,collateral_amount,collateral_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for(BankLoanRow r:rows) {
                    ps.setString(1,r.id()); ps.setString(2,r.borrower().toString()); ps.setString(3,r.borrowerName());
                    ps.setString(4,r.nation()); ps.setDouble(5,r.principal()); ps.setString(6,r.currencyId());
                    ps.setDouble(7,r.rateAnnual()); ps.setInt(8,r.termDays()); ps.setLong(9,r.openedAt());
                    ps.setLong(10,r.dueAt()); ps.setDouble(11,r.repaid()); ps.setDouble(12,r.accrued());
                    ps.setLong(13,r.lastAccrualAt()); ps.setString(14,r.status()); ps.setString(15,r.collateralType());
                    ps.setString(16,r.collateralCurrency()); ps.setString(17,r.collateralItem());
                    ps.setDouble(18,r.collateralValue()); ps.setInt(19,r.creditScoreAtOpen());
                    ps.setDouble(20,r.collateralAmount()); ps.setDouble(21,r.collateralPrice()); ps.addBatch();
                } ps.executeBatch();
            }
            c.commit();ok=true;
        } catch(SQLException e){rollbackQuiet(c);throw new LedgerException("replaceBankLoans: "+e.getMessage(),e);}
        finally{finish(c,ok);}
    }

    public List<BankLoanRow> bankLoansAll() {
        List<BankLoanRow> out=new ArrayList<>();Connection c=pool.borrow();boolean ok=false;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM bank_loans")) {
            while(rs.next()) out.add(new BankLoanRow(rs.getString("id"),UUID.fromString(rs.getString("borrower")),
                    rs.getString("borrower_name"),rs.getString("nation"),rs.getDouble("principal"),rs.getString("currency_id"),
                    rs.getDouble("rate_annual"),rs.getInt("term_days"),rs.getLong("opened_at"),rs.getLong("due_at"),
                    rs.getDouble("repaid"),rs.getDouble("accrued"),rs.getLong("last_accrual_at"),rs.getString("status"),
                    rs.getString("collateral_type"),rs.getString("collateral_currency"),rs.getString("collateral_item"),
                    rs.getDouble("collateral_value"),rs.getDouble("collateral_amount"),
                    rs.getDouble("collateral_price"),rs.getInt("credit_score_at_open")));
            ok=true;
        } catch(SQLException e){throw new LedgerException("bankLoansAll: "+e.getMessage(),e);}
        finally{finish(c,ok);}return out;
    }

    public void replaceBankPools(List<BankPoolRow> rows) {
        Connection c=pool.borrow();boolean ok=false;
        try {
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM bank_pools");}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_pools VALUES(?,?,?,?)")) {
                for(BankPoolRow r:rows){ps.setString(1,r.nation());ps.setString(2,r.currencyId());ps.setDouble(3,r.pool());ps.setDouble(4,r.interestReserve());ps.addBatch();}
                ps.executeBatch();
            } c.commit();ok=true;
        } catch(SQLException e){rollbackQuiet(c);throw new LedgerException("replaceBankPools: "+e.getMessage(),e);}
        finally{finish(c,ok);}
    }

    public List<BankPoolRow> bankPoolsAll() {
        List<BankPoolRow> out=new ArrayList<>();Connection c=pool.borrow();boolean ok=false;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM bank_pools")) {
            while(rs.next())out.add(new BankPoolRow(rs.getString("nation"),rs.getString("currency_id"),rs.getDouble("pool"),rs.getDouble("interest_reserve")));
            ok=true;
        }catch(SQLException e){throw new LedgerException("bankPoolsAll: "+e.getMessage(),e);}
        finally{finish(c,ok);}return out;
    }

    public void replaceBankCredit(List<BankCreditRow> rows) {
        Connection c=pool.borrow();boolean ok=false;
        try {
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM bank_credit");}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_credit VALUES(?,?,?)")) {
                for(BankCreditRow r:rows){ps.setString(1,r.player().toString());ps.setInt(2,r.repaidCount());ps.setInt(3,r.defaultedCount());ps.addBatch();}
                ps.executeBatch();
            } c.commit();ok=true;
        }catch(SQLException e){rollbackQuiet(c);throw new LedgerException("replaceBankCredit: "+e.getMessage(),e);}
        finally{finish(c,ok);}
    }

    public List<BankCreditRow> bankCreditAll() {
        List<BankCreditRow> out=new ArrayList<>();Connection c=pool.borrow();boolean ok=false;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT * FROM bank_credit")) {
            while(rs.next())out.add(new BankCreditRow(UUID.fromString(rs.getString("player")),rs.getInt("repaid_count"),rs.getInt("defaulted_count")));
            ok=true;
        }catch(SQLException e){throw new LedgerException("bankCreditAll: "+e.getMessage(),e);}
        finally{finish(c,ok);}return out;
    }

    public void replaceBankPending(List<BankPendingRow> rows) {
        Connection c=pool.borrow();boolean ok=false;
        try {
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM bank_pending_returns");}
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO bank_pending_returns(return_id,player,item_base64) VALUES(?,?,?)")) {
                for(BankPendingRow r:rows){ps.setString(1,r.returnId());ps.setString(2,r.player().toString());ps.setString(3,r.itemBase64());ps.addBatch();}
                ps.executeBatch();
            }c.commit();ok=true;
        }catch(SQLException e){rollbackQuiet(c);throw new LedgerException("replaceBankPending: "+e.getMessage(),e);}
        finally{finish(c,ok);}
    }

    public List<BankPendingRow> bankPendingAll() {
        List<BankPendingRow> out=new ArrayList<>();Connection c=pool.borrow();boolean ok=false;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT return_id,player,item_base64 FROM bank_pending_returns ORDER BY id")) {
            while(rs.next())out.add(new BankPendingRow(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getString(3)));
            ok=true;
        }catch(SQLException e){throw new LedgerException("bankPendingAll: "+e.getMessage(),e);}
        finally{finish(c,ok);}return out;
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
        if (nation == null || nation.isBlank() || !Double.isFinite(delta)) {
            return false;
        }
        if (delta == 0.0D) {
            return true;
        }
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
        if (nation == null || nation.isBlank() || !Double.isFinite(amount) || amount < 0.0D) {
            throw new LedgerException("invalid reserve value: " + nation);
        }
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
        if (ticket == null || ticket.isBlank() || owner == null
                || currencyId == null || currencyId.isBlank()
                || !Double.isFinite(amount) || amount <= 0.0D) {
            throw new LedgerException("invalid escrow insert: " + ticket);
        }
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO escrow(ticket, owner, currency_id, amount, created_at) VALUES(?,?,?,?,?)")) {
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
        try (PreparedStatement ps = c.prepareStatement("UPDATE escrow SET amount=0 WHERE ticket=?")) {
            ps.setString(1, ticket); ps.executeUpdate(); ok = true;
        } catch (SQLException e) { throw new LedgerException("escrowDelete: " + e.getMessage(), e); }
        finally { finish(c, ok); }
    }

    public void escrowUpdate(String ticket, UUID owner, String currencyId,
                            double expectedOld, double newAmount) {
        if (ticket == null || ticket.isBlank() || owner == null
                || currencyId == null || currencyId.isBlank()
                || !Double.isFinite(expectedOld) || expectedOld <= 0.0D
                || !Double.isFinite(newAmount) || newAmount < 0.0D) {
            throw new LedgerException("invalid escrow update: " + ticket);
        }
        Connection c = pool.borrow(); boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE escrow SET amount=? WHERE ticket=? AND owner=? AND currency_id=? AND amount=?")) {
            ps.setDouble(1, newAmount);
            ps.setString(2, ticket);
            ps.setString(3, owner.toString());
            ps.setString(4, currencyId);
            ps.setDouble(5, expectedOld);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("escrow optimistic lock failed: " + ticket);
            }
            ok = true;
        } catch (SQLException e) {
            throw new LedgerException("escrowUpdate: " + e.getMessage(), e);
        } finally {
            finish(c, ok);
        }
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
