// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.currency.CurrencyRegistry;
import dev.raskol.vault.wallet.WalletService;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Обмен валют. Атомарно: списал from → начислил to (с комиссией) → две транзакции
 * в леджер (CONVERT_WITHDRAW + CONVERT_DEPOSIT) с одинаковым reason для склейки в аудите.
 * Самосвал (from == to) блокируется.
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

    /**
     * Расчёт без применения: для /rv convert preview и /rv confirm.
     * Возвращает ExchangeResult с applied=false.
     */
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

    /**
     * Применение: списал from → начислил to → две транзакции CONVERT.
     * Под монитором WalletService (через transfer/deposit/withdraw) — атомарно.
     */
    public ExchangeResult execute(UUID owner, String fromId, String toId, double amount) {
        ExchangeResult preview = preview(owner, fromId, toId, amount);
        if (!preview.reason().equals("preview")) {
            return preview;
        }
        String reason = "convert:" + preview.fromId() + "->" + preview.toId();
        // Атомарная тройка: списали gross+fee, начислили net, две транзакции в леджер.
        // WalletService.withdraw/deposit сами пишут TRANZ-записи, reason помечаем CONVERT.
        boolean withdrawn = wallets.withdraw(owner, preview.fromId(), preview.gross(),
                TransactionType.CONVERT, reason + ":withdraw");
        if (!withdrawn) {
            return ExchangeResult.failure(preview.fromId(), preview.toId(), "withdraw-failed");
        }
        boolean deposited = wallets.deposit(owner, preview.toId(), preview.net(),
                TransactionType.CONVERT, reason + ":deposit");
        if (!deposited) {
            // Откат: вернули from, логируем критично (деньги не должны теряться).
            wallets.deposit(owner, preview.fromId(), preview.gross(),
                    TransactionType.CONVERT, reason + ":rollback");
            plugin.getLogger().severe("RaskolVault: обмен " + preview.gross() + " " + preview.fromId()
                    + " → " + preview.toId() + " откатан: deposit не прошёл, средства возвращены");
            return ExchangeResult.failure(preview.fromId(), preview.toId(), "deposit-failed-rollback");
        }
        return new ExchangeResult(true, preview.fromId(), preview.toId(),
                preview.gross(), preview.feeAmount(), preview.net(), preview.rate(), reason);
    }
}
