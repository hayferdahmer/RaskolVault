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
}
