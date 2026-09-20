// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.nation;

import dev.raskol.vault.wallet.WalletService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Казна нации: детерминированный UUID по имени нации.
 */
public final class NationTreasury {

    private final WalletService wallets;

    public NationTreasury(WalletService wallets) {
        this.wallets = wallets;
    }

    public UUID treasuryUuid(String nationId) {
        return UUID.nameUUIDFromBytes(("nation:" + nationId).getBytes(StandardCharsets.UTF_8));
    }

    public double balance(String nationId, String currencyId) {
        return wallets.getBalance(treasuryUuid(nationId), currencyId);
    }

    public boolean deposit(String nationId, String currencyId, double amount, String reason) {
        return wallets.deposit(treasuryUuid(nationId), currencyId, amount,
                dev.raskol.vault.api.transaction.TransactionType.PAY, reason);
    }

    public boolean withdraw(String nationId, String currencyId, double amount, String reason) {
        return wallets.withdraw(treasuryUuid(nationId), currencyId, amount,
                dev.raskol.vault.api.transaction.TransactionType.PAY, reason);
    }
}
