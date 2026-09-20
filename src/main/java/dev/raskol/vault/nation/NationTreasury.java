// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.nation;

import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.wallet.WalletService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Казна нации = виртуальный UUID, детерминированно вычисляемый по nationId.
 * Без отдельной таблицы: казна живёт в общей таблице balances, но привязана
 * к псевдо-UUID. Это даёт бесплатно: аудит через queryTransactions,
 * backup через yaml, персистентность в SQLite.
 *
 * Операции: deposit/withdraw в казну → обычный WalletService с виртуальным UUID.
 */
public final class NationTreasury {

    public static final String OWNER_PREFIX = "nation:";

    private final WalletService wallets;

    public NationTreasury(WalletService wallets) {
        this.wallets = wallets;
    }

    /** Детерминированный UUID казны по ID нации. */
    public static UUID treasuryUuid(String nationIdLower) {
        return UUID.nameUUIDFromBytes((OWNER_PREFIX + nationIdLower).getBytes(StandardCharsets.UTF_8));
    }

    public double balance(String nationIdLower, String currencyId) {
        return wallets.getBalance(treasuryUuid(nationIdLower), currencyId);
    }

    public boolean deposit(String nationIdLower, String currencyId, double amount, String reason) {
        return wallets.deposit(treasuryUuid(nationIdLower), currencyId, amount,
                TransactionType.MINT, "nation:" + nationIdLower + ":" + reason);
    }

    public boolean withdraw(String nationIdLower, String currencyId, double amount, String reason) {
        return wallets.withdraw(treasuryUuid(nationIdLower), currencyId, amount,
                TransactionType.BURN, "nation:" + nationIdLower + ":" + reason);
    }
}
