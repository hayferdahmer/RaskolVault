// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.wallet.WalletService;

import java.util.Optional;
import java.util.UUID;

/**
 * Движок конвертации (1.2.6-b): блок конверта валюты в саму себя (from == to).
 */
public final class ConvertEngine {

    public record Quote(
            UUID player, String fromId, String toId,
            double amount, double rate, double feeBase, double feeTax, double net
    ) {}

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final dev.raskol.vault.api.currency.CurrencyRegistry currencies;
    private final ReserveBank bank;

    public ConvertEngine(RaskolVault plugin, WalletService wallets,
                         dev.raskol.vault.api.currency.CurrencyRegistry currencies, ReserveBank bank) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.bank = bank;
    }

    public double rate(String fromId, String toId) {
        if (fromId.equalsIgnoreCase(toId)) return 1.0D;
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null) return 0.0D;
        double fp = bank.priceOf(from);
        double tp = bank.priceOf(to);
        if (!(fp > 0) || !(tp > 0)) return 0.0D;
        return fp / tp;
    }

    public Optional<Quote> quote(UUID player, String fromId, String toId, double amount) {
        if (fromId.equalsIgnoreCase(toId)) return Optional.empty();
        if (!(amount > 0.0D)) return Optional.empty();
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null) return Optional.empty();
        double r = rate(fromId, toId);
        if (!(r > 0)) return Optional.empty();
        double fee = plugin.getRates().feeFor(fromId, toId);
        double gross = amount * r;
        double feeBase = Math.round(gross * fee * 100.0D) / 100.0D;
        double feeTax = Math.round(feeBase * plugin.getConfig().getDouble("reserve.tax-share", 0.5D) * 100.0D) / 100.0D;
        double net = Math.round((gross - feeBase) * 100.0D) / 100.0D;
        if (!(net > 0)) return Optional.empty();
        return Optional.of(new Quote(player, fromId, toId, amount, r, feeBase, feeTax, net));
    }

    public boolean execute(Quote q) {
        if (q.fromId().equalsIgnoreCase(q.toId())) return false;
        if (!wallets.withdraw(q.player(), q.fromId(), q.amount(),
                dev.raskol.vault.api.transaction.TransactionType.CONVERT, "convert:from:" + q.toId())) return false;
        if (!wallets.deposit(q.player(), q.toId(), q.net(),
                dev.raskol.vault.api.transaction.TransactionType.CONVERT, "convert:to:" + q.fromId())) {
            wallets.deposit(q.player(), q.fromId(), q.amount(),
                    dev.raskol.vault.api.transaction.TransactionType.CONVERT, "convert:rollback:" + q.toId());
            return false;
        }
        return true;
    }
}
