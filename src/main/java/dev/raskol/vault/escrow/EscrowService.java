// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.escrow;

import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Эскроу (1.2.4.1): releasePartial без пыли (G5) — округление по decimals валюты,
 * пыль уходит получателю, escrow-аккаунт остаётся точным.
 */
public final class EscrowService {

    private final Plugin plugin;
    private final WalletService wallets;
    private final SQLiteLedger ledger;
    private final CurrencyRegistry currencies;

    public EscrowService(Plugin plugin, WalletService wallets, SQLiteLedger ledger, CurrencyRegistry currencies) {
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

    private static double round(double v, int dec) {
        double f = Math.pow(10, dec);
        return Math.round(v * f) / f;
    }

    public boolean hold(UUID owner, String currencyId, double amount, String ticket) {
        if (!(amount > 0.0D)) return false;
        if (!wallets.has(owner, currencyId, amount)) return false;
        if (!wallets.withdraw(owner, currencyId, amount, TransactionType.PAY, "escrow:hold:" + ticket)) return false;
        if (!wallets.deposit(escrowUuid(ticket), currencyId, amount, TransactionType.PAY, "escrow:hold:" + ticket)) {
            wallets.deposit(owner, currencyId, amount, TransactionType.PAY, "escrow:hold:rollback:" + ticket);
            return false;
        }
        ledger.escrowInsert(ticket, owner, currencyId, amount);
        return true;
    }

    public boolean release(String ticket, UUID to) {
        var row = ledger.escrowGet(ticket);
        if (row == null) return false;
        if (!wallets.withdraw(escrowUuid(ticket), row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:" + ticket)) return false;
        if (!wallets.deposit(to, row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:" + ticket)) {
            wallets.deposit(escrowUuid(ticket), row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:rollback:" + ticket);
            return false;
        }
        ledger.escrowDelete(ticket);
        return true;
    }

    /** G5: частичное освобождение без пыли. */
    public boolean releasePartial(String ticket, UUID to, double amount) {
        var row = ledger.escrowGet(ticket);
        if (row == null) return false;
        if (!(amount > 0.0D) || amount > row.amount() + 1.0E-9D) return false;
        int dec = decimals(row.currencyId());
        double newRemaining = round(row.amount() - amount, dec);
        if (newRemaining < 0.0D) return false;
        double toRecipient = round(row.amount() - newRemaining, dec); // включает пыль
        if (!(toRecipient > 0.0D)) return false;
        if (!wallets.withdraw(escrowUuid(ticket), row.currencyId(), toRecipient, TransactionType.PAY, "escrow:release-partial:" + ticket)) return false;
        if (!wallets.deposit(to, row.currencyId(), toRecipient, TransactionType.PAY, "escrow:release-partial:" + ticket)) {
            wallets.deposit(escrowUuid(ticket), row.currencyId(), toRecipient, TransactionType.PAY, "escrow:release-partial:rollback:" + ticket);
            return false;
        }
        ledger.escrowDelete(ticket);
        if (newRemaining > 0.0D) ledger.escrowInsert(ticket, row.owner(), row.currencyId(), newRemaining);
        return true;
    }

    public boolean refund(String ticket) {
        var row = ledger.escrowGet(ticket);
        if (row == null) return false;
        if (!wallets.withdraw(escrowUuid(ticket), row.currencyId(), row.amount(), TransactionType.PAY, "escrow:refund:" + ticket)) return false;
        if (!wallets.deposit(row.owner(), row.currencyId(), row.amount(), TransactionType.PAY, "escrow:refund:" + ticket)) {
            wallets.deposit(escrowUuid(ticket), row.currencyId(), row.amount(), TransactionType.PAY, "escrow:refund:rollback:" + ticket);
            return false;
        }
        ledger.escrowDelete(ticket);
        return true;
    }
}
