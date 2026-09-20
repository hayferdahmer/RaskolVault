// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Конвертация по курсам Валютного совета (1.1.0-b).
 *
 * курс A→B = цена(A)/цена(B), цены из ReserveBank (золотое покрытие).
 * Комиссия = база (exchange.default-fee, сжигается) + налог нации-цели
 * (уходит в казну нации-цели В ВАЛЮТЕ ЦЕЛИ).
 * Откат при частичном сбое: компенсационный депозит + SEVERE в лог.
 */
public final class ConvertEngine {

    public record Quote(
            String fromId,
            String toId,
            double amount,
            double rate,
            double feeBase,
            double feeTax,
            double net,
            String taxNation,
            double taxInTo
    ) {
    }

    private final Plugin plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final ReserveBank reserveBank;

    public ConvertEngine(Plugin plugin, WalletService wallets,
                         CurrencyRegistry currencies, ReserveBank reserveBank) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.reserveBank = reserveBank;
    }

    public double rate(String fromId, String toId) {
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null) {
            return 0.0D;
        }
        double priceTo = reserveBank.priceOf(to);
        if (priceTo <= 0.0D) {
            return 0.0D;
        }
        return reserveBank.priceOf(from) / priceTo;
    }

    public Optional<Quote> quote(UUID owner, String fromId, String toId, double amount) {
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null || from.id().equals(to.id()) || !(amount > 0.0D)) {
            return Optional.empty();
        }
        if (!wallets.has(owner, from.id(), amount)) {
            return Optional.empty();
        }
        double rate = rate(from.id(), to.id());
        if (rate <= 0.0D) {
            return Optional.empty();
        }
        double baseRate = plugin.getConfig().getDouble("exchange.default-fee", 0.02);
        double taxRate = to.type() == CurrencyType.NATIONAL && to.nationId() != null
                ? reserveBank.taxOf(to.nationId())
                : 0.0D;
        double feeBase = amount * baseRate;
        double feeTax = amount * taxRate;
        double net = (amount - feeBase - feeTax) * rate;
        double taxInTo = feeTax * rate;
        String taxNation = to.type() == CurrencyType.NATIONAL ? to.nationId() : null;
        return Optional.of(new Quote(from.id(), to.id(), amount, rate, feeBase, feeTax, net, taxNation, taxInTo));
    }

    /** Исполняет конвертацию по текущему курсу (между preview и confirm курс мог двигаться). */
    public Optional<Quote> execute(UUID owner, String fromId, String toId, double amount) {
        Optional<Quote> quoted = quote(owner, fromId, toId, amount);
        if (quoted.isEmpty()) {
            return quoted;
        }
        Quote q = quoted.get();
        String reason = "convert:" + q.fromId() + "->" + q.toId();
        boolean withdrawn = wallets.withdraw(owner, q.fromId(), q.amount(), TransactionType.CONVERT, reason);
        if (!withdrawn) {
            return Optional.empty();
        }
        boolean deposited = wallets.deposit(owner, q.toId(), q.net(), TransactionType.CONVERT, reason);
        if (!deposited) {
            wallets.deposit(owner, q.fromId(), q.amount(), TransactionType.CONVERT, reason + ":rollback");
            plugin.getLogger().severe("RaskolVault: конвертация " + q.fromId() + "->" + q.toId()
                    + " откатана: депозит не прошёл, средства возвращены");
            return Optional.empty();
        }
        if (q.taxInTo() > 0.0D && q.taxNation() != null) {
            boolean taxed = wallets.deposit(ReserveBank.reserveUuid(q.taxNation()), q.toId(), q.taxInTo(),
                    TransactionType.CONVERT, "convert:tax:" + q.taxNation());
            if (!taxed) {
                plugin.getLogger().warning("RaskolVault: налог конвертации в казну " + q.taxNation()
                        + " не зачислен (" + q.taxInTo() + " " + q.toId() + ") — разобрать вручную");
            }
        }
        return quoted;
    }
}
