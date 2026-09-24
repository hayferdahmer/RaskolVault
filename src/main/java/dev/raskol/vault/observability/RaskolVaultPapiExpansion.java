// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.observability;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.auction.AuctionService;
import dev.raskol.vault.bank.BankAccount;
import dev.raskol.vault.bank.BankLoan;
import dev.raskol.vault.bank.BankService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * PlaceholderAPI-экспаншн (1.2.6-c).
 *
 * Доступные плейсхолдеры:
 *   %raskolvault_balance_<CUR>%           — баланс игрока в валюте
 *   %raskolvault_balance_gld%             — баланс в глобальной валюте
 *   %raskolvault_auction_active%          — активных лотов игрока
 *   %raskolvault_auction_reputation%      — репутация продавца
 *   %raskolvault_bank_deposits%           — сумма активных вкладов (в GLD)
 *   %raskolvault_bank_deposits_count%     — количество вкладов
 *   %raskolvault_bank_loans%              — сумма остатков кредитов (в GLD)
 *   %raskolvault_bank_loans_count%        — количество кредитов
 *   %raskolvault_bank_credit_score%       — кредитный скор
 *   %raskolvault_nation%                  — нация игрока (Towny) или «—»
 */
public final class RaskolVaultPapiExpansion extends PlaceholderExpansion {

    private final RaskolVault plugin;

    public RaskolVaultPapiExpansion(RaskolVault plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "raskolvault"; }
    @Override public @NotNull String getAuthor() { return "hayferdahmer"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        if (player == null) return null;

        // balance_<CUR>
        if (params.startsWith("balance_")) {
            String cur = params.substring("balance_".length()).toUpperCase();
            double bal = plugin.getWallets().getBalance(player.getUniqueId(), cur);
            return String.format(java.util.Locale.ROOT, "%.2f", bal);
        }

        // auction_active
        if (params.equals("auction_active")) {
            int c = 0;
            AuctionService a = plugin.getAuctionService();
            if (a != null) {
                for (var l : a.myListings(player.getUniqueId()))
                    if (l.status() == dev.raskol.vault.auction.AuctionLot.Status.ACTIVE) c++;
            }
            return String.valueOf(c);
        }

        // auction_reputation
        if (params.equals("auction_reputation")) {
            var rep = plugin.getAuctionReputation();
            return rep == null ? "0" : String.valueOf(rep.getSuccessCount(player.getUniqueId()));
        }

        // bank_deposits / bank_deposits_count
        if (params.equals("bank_deposits") || params.equals("bank_deposits_count")) {
            BankService b = plugin.getBankService();
            if (b == null) return params.equals("bank_deposits") ? "0.00" : "0";
            List<BankAccount> list = b.myDeposits(player.getUniqueId());
            long active = list.stream().filter(BankAccount::isActive).count();
            if (params.equals("bank_deposits_count")) return String.valueOf(active);
            double sum = list.stream().filter(BankAccount::isActive).mapToDouble(BankAccount::principal).sum();
            return String.format(java.util.Locale.ROOT, "%.2f", sum);
        }

        // bank_loans / bank_loans_count
        if (params.equals("bank_loans") || params.equals("bank_loans_count")) {
            BankService b = plugin.getBankService();
            if (b == null) return params.equals("bank_loans") ? "0.00" : "0";
            List<BankLoan> list = b.myLoans(player.getUniqueId());
            long active = list.stream().filter(BankLoan::isActive).count();
            if (params.equals("bank_loans_count")) return String.valueOf(active);
            long now = System.currentTimeMillis();
            double sum = list.stream().filter(BankLoan::isActive).mapToDouble(l -> l.outstanding(now)).sum();
            return String.format(java.util.Locale.ROOT, "%.2f", sum);
        }

        // bank_credit_score
        if (params.equals("bank_credit_score")) {
            BankService b = plugin.getBankService();
            return b == null ? "0" : String.valueOf(b.creditScore(player.getUniqueId()));
        }

        // nation
        if (params.equals("nation")) {
            var t = plugin.getTownyHook();
            if (t == null || !t.isAvailable()) return "—";
            String n = t.nationOf(player.getUniqueId());
            return n == null ? "—" : n;
        }

        return null;
    }
}
