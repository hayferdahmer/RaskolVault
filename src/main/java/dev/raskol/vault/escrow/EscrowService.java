// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.escrow;

import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Эскроу-примитив (1.1.5, фундамент 1.2.0): заморозка средств до расчёта.
 * Используется будущей биржей/аукционом/облигациями. Средства уходят со счёта
 * владельца в детерминированный эскроу-аккаунт; release/refund возвращают их.
 *
 * Персистентность: строка в таблице escrow (schema v2) + движение балансов через WalletService.
 */
public final class EscrowService {

    private final Plugin plugin;
    private final WalletService wallets;
    private final SQLiteLedger ledger;

    public EscrowService(Plugin plugin, WalletService wallets, SQLiteLedger ledger) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.ledger = ledger;
    }

    public static UUID escrowUuid(String ticket) {
        return UUID.nameUUIDFromBytes(("escrow:" + ticket).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Заморозить amount валюты владельца под ticket. */
    public boolean hold(UUID owner, String currencyId, double amount, String ticket) {
        if (!(amount > 0.0D)) {
            return false;
        }
        if (!wallets.has(owner, currencyId, amount)) {
            return false;
        }
        if (!wallets.withdraw(owner, currencyId, amount, TransactionType.PAY, "escrow:hold:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(escrowUuid(ticket), currencyId, amount, TransactionType.PAY, "escrow:hold:" + ticket)) {
            wallets.deposit(owner, currencyId, amount, TransactionType.PAY, "escrow:hold:rollback:" + ticket);
            return false;
        }
        ledger.escrowInsert(ticket, owner, currencyId, amount);
        return true;
    }

    /** Освободить эскроу целевому получателю. */
    public boolean release(String ticket, UUID to) {
        var row = ledger.escrowGet(ticket);
        if (row == null) {
            return false;
        }
        if (!wallets.withdraw(escrowUuid(ticket), row.currencyId(), row.amount(),
                TransactionType.PAY, "escrow:release:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(to, row.currencyId(), row.amount(), TransactionType.PAY, "escrow:release:" + ticket)) {
            wallets.deposit(escrowUuid(ticket), row.currencyId(), row.amount(),
                    TransactionType.PAY, "escrow:release:rollback:" + ticket);
            return false;
        }
        ledger.escrowDelete(ticket);
        return true;
    }

    /** Вернуть эскроу владельцу. */
    public boolean refund(String ticket) {
        var row = ledger.escrowGet(ticket);
        if (row == null) {
            return false;
        }
        if (!wallets.withdraw(escrowUuid(ticket), row.currencyId(), row.amount(),
                TransactionType.PAY, "escrow:refund:" + ticket)) {
            return false;
        }
        if (!wallets.deposit(row.owner(), row.currencyId(), row.amount(),
                TransactionType.PAY, "escrow:refund:" + ticket)) {
            wallets.deposit(escrowUuid(ticket), row.currencyId(), row.amount(),
                    TransactionType.PAY, "escrow:refund:rollback:" + ticket);
            return false;
        }
        ledger.escrowDelete(ticket);
        return true;
    }
}
