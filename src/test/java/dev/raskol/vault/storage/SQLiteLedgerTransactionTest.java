// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.storage;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SQLiteLedgerTransactionTest {

    @TempDir
    Path tempDir;

    private SQLiteLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("RaskolVault-test");
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0D;
                    if (type == float.class) return 0.0F;
                    if (type == short.class) return (short) 0;
                    if (type == byte.class) return (byte) 0;
                    if (type == char.class) return (char) 0;
                    return null;
                });

        ledger = new SQLiteLedger(plugin, tempDir.resolve("ledger.db").toFile(),
                2, "FULL", 2_000L);
        ledger.init();
        ledger.upsertCurrency(new Currency("GLD", "Золото", "G",
                CurrencyType.WORLD, null, 2, true));
    }

    @AfterEach
    void tearDown() {
        if (ledger != null) {
            ledger.close();
        }
    }

    @Test
    void checkedCommitRejectsStaleBalance() {
        UUID player = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(player, "GLD", 100.0D)), List.of());

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteChecked(
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 0.0D, 90.0D)),
                List.of()));

        assertEquals(100.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
    }

    @Test
    void checkedCommitDoesNotInsertMissingWalletWithNonZeroExpectedBalance() {
        UUID player = UUID.randomUUID();

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteChecked(
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 50.0D, 10.0D)),
                List.of()));

        assertEquals(0.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
    }

    @Test
    void checkedCommitRollsBackAllWalletsWhenOneBalanceIsStale() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(sender, "GLD", 100.0D),
                new SQLiteLedger.AbsoluteBalance(recipient, "GLD", 20.0D)), List.of());

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteChecked(
                List.of(
                        new SQLiteLedger.CheckedBalance(sender, "GLD", 100.0D, 50.0D),
                        new SQLiteLedger.CheckedBalance(recipient, "GLD", 10.0D, 70.0D)),
                List.of()));

        assertEquals(100.0D, ledger.getBalance(sender, "GLD"), 0.000_001D);
        assertEquals(20.0D, ledger.getBalance(recipient, "GLD"), 0.000_001D);
    }

    @Test
    void checkedCommitCanCreateNewWalletWhenExpectedBalanceIsZero() {
        UUID player = UUID.randomUUID();

        ledger.commitAbsoluteChecked(List.of(
                new SQLiteLedger.CheckedBalance(player, "GLD", 0.0D, 25.0D)), List.of());

        assertEquals(25.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
    }

    @Test
    void idempotentCommitDoesNotApplyTheSameOperationTwice() {
        UUID player = UUID.randomUUID();
        String operationId = "wallet-deposit-001";
        String fingerprint = "deposit:player:001";

        ledger.commitAbsoluteCheckedOnce(operationId, fingerprint,
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 0.0D, 100.0D)),
                List.of());

        // Повторный запрос должен распознаться до проверки нового ожидаемого баланса.
        ledger.commitAbsoluteCheckedOnce(operationId, fingerprint,
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 100.0D, 200.0D)),
                List.of());

        assertEquals(100.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
        org.junit.jupiter.api.Assertions.assertTrue(
                ledger.operationCommitted(operationId, fingerprint));
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentPayload() {
        UUID player = UUID.randomUUID();
        String operationId = "wallet-deposit-002";

        ledger.commitAbsoluteCheckedOnce(operationId, "deposit:100",
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 0.0D, 100.0D)),
                List.of());

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteCheckedOnce(
                operationId, "deposit:200",
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 100.0D, 300.0D)),
                List.of()));

        assertEquals(100.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
    }

    @Test
    void failedIdempotentCommitDoesNotReserveTheOperationKey() {
        UUID player = UUID.randomUUID();
        String operationId = "wallet-withdraw-001";
        String fingerprint = "withdraw:25";

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteCheckedOnce(
                operationId, fingerprint,
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 25.0D, 0.0D)),
                List.of()));

        org.junit.jupiter.api.Assertions.assertFalse(
                ledger.operationCommitted(operationId, fingerprint));
    }

    @Test
    void walletAndReserveCommitAtomically() {
        UUID player = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(player, "GLD", 100.0D)), List.of());
        ledger.reserveSet("test-nation", 100.0D);

        ledger.commitAbsoluteCheckedOnce("reserve-transfer-001", "wallet-and-reserve",
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 100.0D, 50.0D)),
                List.of(new SQLiteLedger.CheckedReserve("test-nation", 100.0D, 50.0D)),
                List.of());

        assertEquals(50.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.reserveGet("test-nation"), 0.000_001D);
    }

    @Test
    void reserveConflictRollsBackWalletAndDoesNotRecordOperation() {
        UUID player = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(player, "GLD", 100.0D)), List.of());
        ledger.reserveSet("test-nation", 100.0D);

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteCheckedOnce(
                "reserve-transfer-002", "wallet-and-reserve-conflict",
                List.of(new SQLiteLedger.CheckedBalance(player, "GLD", 100.0D, 50.0D)),
                List.of(new SQLiteLedger.CheckedReserve("test-nation", 10.0D, 20.0D)),
                List.of()));

        assertEquals(100.0D, ledger.getBalance(player, "GLD"), 0.000_001D);
        assertEquals(100.0D, ledger.reserveGet("test-nation"), 0.000_001D);
        org.junit.jupiter.api.Assertions.assertFalse(
                ledger.operationCommitted("reserve-transfer-002", "wallet-and-reserve-conflict"));
    }

    @Test
    void escrowAndWalletBalancesCommitInOneTransaction() {
        UUID owner = UUID.randomUUID();
        UUID escrowWallet = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(owner, "GLD", 100.0D),
                new SQLiteLedger.AbsoluteBalance(escrowWallet, "GLD", 0.0D),
                new SQLiteLedger.AbsoluteBalance(recipient, "GLD", 0.0D)), List.of());

        ledger.commitAbsoluteCheckedOnce("escrow-hold-test", "escrow-hold-fingerprint",
                List.of(
                        new SQLiteLedger.CheckedBalance(owner, "GLD", 100.0D, 50.0D),
                        new SQLiteLedger.CheckedBalance(escrowWallet, "GLD", 0.0D, 50.0D)),
                List.of(), List.of(new SQLiteLedger.CheckedEscrow(
                        "ticket-atomic", owner, "GLD", 0.0D, 50.0D, false)), List.of());

        assertEquals(50.0D, ledger.getBalance(owner, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.getBalance(escrowWallet, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.escrowGet("ticket-atomic").amount(), 0.000_001D);

        ledger.commitAbsoluteCheckedOnce("escrow-release-test", "escrow-release-fingerprint",
                List.of(
                        new SQLiteLedger.CheckedBalance(escrowWallet, "GLD", 50.0D, 0.0D),
                        new SQLiteLedger.CheckedBalance(recipient, "GLD", 0.0D, 50.0D)),
                List.of(), List.of(new SQLiteLedger.CheckedEscrow(
                        "ticket-atomic", owner, "GLD", 50.0D, 0.0D, true)), List.of());

        assertEquals(0.0D, ledger.getBalance(escrowWallet, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.getBalance(recipient, "GLD"), 0.000_001D);
        org.junit.jupiter.api.Assertions.assertNull(ledger.escrowGet("ticket-atomic"));
    }

    @Test
    void staleEscrowMetadataRollsBackWalletChanges() {
        UUID owner = UUID.randomUUID();
        UUID escrowWallet = UUID.randomUUID();
        ledger.commitAbsolute(List.of(
                new SQLiteLedger.AbsoluteBalance(owner, "GLD", 100.0D),
                new SQLiteLedger.AbsoluteBalance(escrowWallet, "GLD", 50.0D)), List.of());
        ledger.escrowInsert("ticket-stale", owner, "GLD", 50.0D);

        assertThrows(LedgerException.class, () -> ledger.commitAbsoluteCheckedOnce(
                "escrow-stale-test", "escrow-stale-fingerprint",
                List.of(
                        new SQLiteLedger.CheckedBalance(owner, "GLD", 100.0D, 50.0D),
                        new SQLiteLedger.CheckedBalance(escrowWallet, "GLD", 50.0D, 100.0D)),
                List.of(), List.of(new SQLiteLedger.CheckedEscrow(
                        "ticket-stale", owner, "GLD", 25.0D, 75.0D, false)), List.of()));

        assertEquals(100.0D, ledger.getBalance(owner, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.getBalance(escrowWallet, "GLD"), 0.000_001D);
        assertEquals(50.0D, ledger.escrowGet("ticket-stale").amount(), 0.000_001D);
    }
}
