// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.escrow;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.Transaction;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Эскроу. Для внутренних валют баланс кошельков и состояние билета
 * фиксируются одной SQLite-транзакцией. GLOBAL остаётся внешним сценарием
 * с компенсацией, поскольку его баланс хранится в EssentialsX.
 */
public final class EscrowService {

    private final Plugin plugin;
    private final WalletService wallets;
    private final SQLiteLedger ledger;
    private final CurrencyRegistry currencies;

    public EscrowService(Plugin plugin, WalletService wallets, SQLiteLedger ledger,
                         CurrencyRegistry currencies) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.ledger = ledger;
        this.currencies = currencies;
    }

    public static UUID escrowUuid(String ticket) {
        return UUID.nameUUIDFromBytes(("escrow:" + ticket).getBytes(StandardCharsets.UTF_8));
    }

    private int decimals(String currencyId) {
        return currencies.get(currencyId).map(c -> c.decimals()).orElse(2);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private boolean isGlobal(String currencyId) {
        return currencyId != null && currencyId.equalsIgnoreCase(currencies.globalId());
    }

    private String operationId(String action, String ticket, UUID recipient, Double amount) {
        String canonical = "escrow-op:" + action + ":" + ticket + ":"
                + (recipient == null ? "" : recipient)
                + ":" + (amount == null ? "" : Double.toHexString(amount));
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public boolean hold(UUID owner, String currencyId, double amount, String ticket) {
        if (owner == null || currencyId == null || ticket == null || ticket.isBlank()
                || !Double.isFinite(amount) || !(amount > 0.0D)) {
            return false;
        }

        if (isGlobal(currencyId)) {
            return holdGlobal(owner, currencyId, amount, ticket);
        }

        String normalizedCurrency = currencyId.toUpperCase(Locale.ROOT);
        String operationId = operationId("hold:" + normalizedCurrency, ticket, owner, amount);
        SQLiteLedger.EscrowRow existing = ledger.escrowGet(ticket);
        if (existing != null) {
            return existing.amount() > 0.0D
                    && existing.owner().equals(owner)
                    && existing.currencyId().equalsIgnoreCase(normalizedCurrency)
                    && Double.compare(existing.amount(), amount) == 0
                    && ledger.operationExists(operationId);
        }
        if (ledger.operationExists(operationId)) {
            // Исторический ключ есть, а запись билета отсутствует: повторное создание запрещено.
            return false;
        }

        UUID escrow = escrowUuid(ticket);
        List<WalletService.WalletDelta> deltas = List.of(
                new WalletService.WalletDelta(owner, normalizedCurrency, -amount),
                new WalletService.WalletDelta(escrow, normalizedCurrency, amount));
        List<SQLiteLedger.CheckedEscrow> escrowChanges = List.of(
                new SQLiteLedger.CheckedEscrow(ticket, owner, normalizedCurrency,
                        0.0D, amount, false));
        List<Transaction> transactions = List.of(
                new Transaction(-1L, System.currentTimeMillis(), owner, null,
                        normalizedCurrency, amount, TransactionType.PAY, "escrow:hold:" + ticket, null),
                new Transaction(-1L, System.currentTimeMillis(), null, escrow,
                        normalizedCurrency, amount, TransactionType.PAY, "escrow:hold:" + ticket, null));

        return wallets.applyBatchOnce(operationId, deltas, List.of(), escrowChanges, transactions);
    }

    public boolean release(String ticket, UUID to) {
        if (ticket == null || ticket.isBlank() || to == null) {
            return false;
        }
        String operationId = operationId("release", ticket, to, null);
        if (ledger.operationExists(operationId)) {
            return true;
        }

        SQLiteLedger.EscrowRow row = ledger.escrowGet(ticket);
        if (row == null || row.amount() <= 0.0D) {
            return false;
        }
        if (isGlobal(row.currencyId())) {
            return releaseGlobal(ticket, to, row);
        }

        UUID escrow = escrowUuid(ticket);
        List<WalletService.WalletDelta> deltas = List.of(
                new WalletService.WalletDelta(escrow, row.currencyId(), -row.amount()),
                new WalletService.WalletDelta(to, row.currencyId(), row.amount()));
        List<SQLiteLedger.CheckedEscrow> escrowChanges = List.of(
                new SQLiteLedger.CheckedEscrow(ticket, row.owner(), row.currencyId(),
                        row.amount(), 0.0D, true));
        List<Transaction> transactions = List.of(
                new Transaction(-1L, System.currentTimeMillis(), escrow, null,
                        row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:" + ticket, null),
                new Transaction(-1L, System.currentTimeMillis(), null, to,
                        row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:" + ticket, null));

        return wallets.applyBatchOnce(operationId, deltas, List.of(), escrowChanges, transactions);
    }

    /** Частичное освобождение: остаток и перевод обновляются одной транзакцией. */
    public boolean releasePartial(String ticket, UUID to, double amount) {
        if (ticket == null || ticket.isBlank() || to == null
                || !Double.isFinite(amount) || !(amount > 0.0D)) {
            return false;
        }
        String operationId = operationId("release-partial", ticket, to, amount);
        if (ledger.operationExists(operationId)) {
            return true;
        }

        SQLiteLedger.EscrowRow row = ledger.escrowGet(ticket);
        if (row == null || row.amount() <= 0.0D || amount > row.amount() + 1.0E-9D) {
            return false;
        }
        if (isGlobal(row.currencyId())) {
            return releasePartialGlobal(ticket, to, amount, row);
        }

        int decimalPlaces = decimals(row.currencyId());
        double newRemaining = round(row.amount() - amount, decimalPlaces);
        if (newRemaining < 0.0D) {
            return false;
        }
        double toRecipient = round(row.amount() - newRemaining, decimalPlaces);
        if (!(toRecipient > 0.0D)) {
            return false;
        }

        UUID escrow = escrowUuid(ticket);
        List<WalletService.WalletDelta> deltas = List.of(
                new WalletService.WalletDelta(escrow, row.currencyId(), -toRecipient),
                new WalletService.WalletDelta(to, row.currencyId(), toRecipient));
        List<SQLiteLedger.CheckedEscrow> escrowChanges = List.of(
                new SQLiteLedger.CheckedEscrow(ticket, row.owner(), row.currencyId(),
                        row.amount(), newRemaining, newRemaining <= 0.0D));
        List<Transaction> transactions = List.of(
                new Transaction(-1L, System.currentTimeMillis(), escrow, null,
                        row.currencyId(), toRecipient, TransactionType.PAY,
                        "escrow:release-partial:" + ticket, null),
                new Transaction(-1L, System.currentTimeMillis(), null, to,
                        row.currencyId(), toRecipient, TransactionType.PAY,
                        "escrow:release-partial:" + ticket, null));

        return wallets.applyBatchOnce(operationId, deltas, List.of(), escrowChanges, transactions);
    }

    public boolean refund(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        String operationId = operationId("refund", ticket, null, null);
        if (ledger.operationExists(operationId)) {
            return true;
        }

        SQLiteLedger.EscrowRow row = ledger.escrowGet(ticket);
        if (row == null || row.amount() <= 0.0D) {
            return false;
        }
        if (isGlobal(row.currencyId())) {
            return refundGlobal(ticket, row);
        }

        UUID escrow = escrowUuid(ticket);
        List<WalletService.WalletDelta> deltas = List.of(
                new WalletService.WalletDelta(escrow, row.currencyId(), -row.amount()),
                new WalletService.WalletDelta(row.owner(), row.currencyId(), row.amount()));
        List<SQLiteLedger.CheckedEscrow> escrowChanges = List.of(
                new SQLiteLedger.CheckedEscrow(ticket, row.owner(), row.currencyId(),
                        row.amount(), 0.0D, true));
        List<Transaction> transactions = List.of(
                new Transaction(-1L, System.currentTimeMillis(), escrow, null,
                        row.currencyId(), row.amount(), TransactionType.PAY, "escrow:refund:" + ticket, null),
                new Transaction(-1L, System.currentTimeMillis(), null, row.owner(),
                        row.currencyId(), row.amount(), TransactionType.PAY, "escrow:refund:" + ticket, null));

        return wallets.applyBatchOnce(operationId, deltas, List.of(), escrowChanges, transactions);
    }

    private boolean holdGlobal(UUID owner, String currencyId, double amount, String ticket) {
        if (ledger.escrowGet(ticket) != null || !wallets.has(owner, currencyId, amount)) {
            return false;
        }
        if (!wallets.withdraw(owner, currencyId, amount, TransactionType.PAY,
                "escrow:hold:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(escrowUuid(ticket), currencyId, amount, TransactionType.PAY,
                "escrow:hold:" + ticket)) {
            wallets.deposit(owner, currencyId, amount, TransactionType.PAY,
                    "escrow:hold:rollback:" + ticket);
            return false;
        }
        try {
            ledger.escrowInsert(ticket, owner, currencyId, amount);
            return true;
        } catch (RuntimeException exception) {
            wallets.withdraw(escrowUuid(ticket), currencyId, amount, TransactionType.PAY,
                    "escrow:hold:rollback-escrow:" + ticket);
            wallets.deposit(owner, currencyId, amount, TransactionType.PAY,
                    "escrow:hold:rollback-owner:" + ticket);
            plugin.getLogger().severe("RaskolVault: не удалось сохранить GLOBAL escrow "
                    + ticket + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean releaseGlobal(String ticket, UUID to, SQLiteLedger.EscrowRow row) {
        UUID escrow = escrowUuid(ticket);
        if (!wallets.withdraw(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                "escrow:release:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(to, row.currencyId(), row.amount(), TransactionType.PAY,
                "escrow:release:" + ticket)) {
            wallets.deposit(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:release:rollback:" + ticket);
            return false;
        }
        try {
            ledger.escrowDelete(ticket);
            return true;
        } catch (RuntimeException exception) {
            wallets.withdraw(to, row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:release:rollback-recipient:" + ticket);
            wallets.deposit(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:release:rollback-escrow:" + ticket);
            plugin.getLogger().severe("RaskolVault: не удалось закрыть GLOBAL escrow "
                    + ticket + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean releasePartialGlobal(String ticket, UUID to, double amount,
                                         SQLiteLedger.EscrowRow row) {
        if (amount > row.amount() + 1.0E-9D) {
            return false;
        }
        int decimalPlaces = decimals(row.currencyId());
        double newRemaining = round(row.amount() - amount, decimalPlaces);
        if (newRemaining < 0.0D) {
            return false;
        }
        double toRecipient = round(row.amount() - newRemaining, decimalPlaces);
        if (!(toRecipient > 0.0D)) {
            return false;
        }
        UUID escrow = escrowUuid(ticket);
        if (!wallets.withdraw(escrow, row.currencyId(), toRecipient, TransactionType.PAY,
                "escrow:release-partial:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(to, row.currencyId(), toRecipient, TransactionType.PAY,
                "escrow:release-partial:" + ticket)) {
            wallets.deposit(escrow, row.currencyId(), toRecipient, TransactionType.PAY,
                    "escrow:release-partial:rollback:" + ticket);
            return false;
        }
        try {
            ledger.escrowUpdate(ticket, row.owner(), row.currencyId(),
                    row.amount(), newRemaining);
            return true;
        } catch (RuntimeException exception) {
            wallets.withdraw(to, row.currencyId(), toRecipient, TransactionType.PAY,
                    "escrow:release-partial:rollback-recipient:" + ticket);
            wallets.deposit(escrow, row.currencyId(), toRecipient, TransactionType.PAY,
                    "escrow:release-partial:rollback-escrow:" + ticket);
            plugin.getLogger().severe("RaskolVault: не удалось обновить GLOBAL escrow "
                    + ticket + ": " + exception.getMessage());
            return false;
        }
    }

    private boolean refundGlobal(String ticket, SQLiteLedger.EscrowRow row) {
        UUID escrow = escrowUuid(ticket);
        if (!wallets.withdraw(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                "escrow:refund:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(row.owner(), row.currencyId(), row.amount(), TransactionType.PAY,
                "escrow:refund:" + ticket)) {
            wallets.deposit(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:refund:rollback:" + ticket);
            return false;
        }
        try {
            ledger.escrowDelete(ticket);
            return true;
        } catch (RuntimeException exception) {
            wallets.withdraw(row.owner(), row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:refund:rollback-owner:" + ticket);
            wallets.deposit(escrow, row.currencyId(), row.amount(), TransactionType.PAY,
                    "escrow:refund:rollback-escrow:" + ticket);
            plugin.getLogger().severe("RaskolVault: не удалось закрыть GLOBAL escrow "
                    + ticket + ": " + exception.getMessage());
            return false;
        }
    }
}
