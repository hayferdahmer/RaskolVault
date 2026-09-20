// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Обмен валют (1.0.3): preview из кэша (мгновенно), исполнение — асинхронно
 * через WalletService.exchangeAsync (один атомарный коммит на обе стороны).
 */
public final class ExchangeService {

    private final Plugin plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final RatesService rates;

    public ExchangeService(Plugin plugin, WalletService wallets,
                           CurrencyRegistry currencies, RatesService rates) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.rates = rates;
    }

    public ExchangeResult preview(UUID owner, String fromId, String toId, double amount) {
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null) {
            return ExchangeResult.failure(fromId, toId, "unknown-currency:" + fromId);
        }
        if (to == null) {
            return ExchangeResult.failure(fromId, toId, "unknown-currency:" + toId);
        }
        if (from.id().equals(to.id())) {
            return ExchangeResult.failure(fromId, toId, "same-currency");
        }
        if (!(amount > 0.0D)) {
            return ExchangeResult.failure(fromId, toId, "invalid-amount");
        }
        if (!wallets.has(owner, from.id(), amount)) {
            return ExchangeResult.failure(fromId, toId, "insufficient");
        }
        Double rate = rates.rate(from.id(), to.id()).orElse(null);
        if (rate == null) {
            return ExchangeResult.failure(fromId, toId, "no-rate");
        }
        double fee = rates.fee(from.id(), to.id());
        double feeAmount = amount * fee;
        double gross = amount - feeAmount;
        double net = gross * rate;
        return new ExchangeResult(false, from.id(), to.id(), amount, feeAmount, net, rate, "preview");
    }

    /** Асинхронное исполнение: callback получает финальный ExchangeResult. */
    public void executeAsync(UUID owner, String fromId, String toId, double amount,
                             Consumer<ExchangeResult> cb) {
        ExchangeResult preview = preview(owner, fromId, toId, amount);
        if (!"preview".equals(preview.reason())) {
            cb.accept(preview);
            return;
        }
        String reason = "convert:" + preview.fromId() + "->" + preview.toId();
        wallets.exchangeAsync(owner, preview.fromId(), preview.toId(),
                preview.gross(), preview.net(), reason, ok -> cb.accept(ok
                        ? new ExchangeResult(true, preview.fromId(), preview.toId(),
                        preview.gross(), preview.feeAmount(), preview.net(), preview.rate(), reason)
                        : ExchangeResult.failure(preview.fromId(), preview.toId(), "write-failed")));
    }

    /** Синхронное исполнение (админ-пути, тесты). */
    public ExchangeResult execute(UUID owner, String fromId, String toId, double amount) {
        ExchangeResult preview = preview(owner, fromId, toId, amount);
        if (!"preview".equals(preview.reason())) {
            return preview;
        }
        String reason = "convert:" + preview.fromId() + "->" + preview.toId();
        boolean ok = wallets.exchange(owner, preview.fromId(), preview.toId(),
                preview.gross(), preview.net(), reason);
        return ok
                ? new ExchangeResult(true, preview.fromId(), preview.toId(),
                preview.gross(), preview.feeAmount(), preview.net(), preview.rate(), reason)
                : ExchangeResult.failure(preview.fromId(), preview.toId(), "write-failed");
    }
}
