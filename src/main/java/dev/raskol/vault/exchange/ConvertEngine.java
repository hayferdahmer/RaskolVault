// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.currency.CurrencyType;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.reserve.ReserveBank;
import dev.raskol.vault.wallet.WalletService;

import java.util.Optional;
import java.util.UUID;

/**
 * Движок конвертации (1.2.6-b fix): ВОССТАНОВЛЕН прежний контракт
 * blockReason(UUID,String,String,double) / quote(UUID,...) / execute(UUID,...),
 * который используют ConvertSubcommand, RaskolVaultAPI, GuiListener.
 * Сохранено улучшение 1.2.6-b: блок конверта валюты в саму себя.
 */
public final class ConvertEngine {

    public record Quote(
            String fromId, String toId, double amount, double rate,
            double feeBase, double feeTax, double net,
            String taxNation, double taxInTo, double goldFlow
    ) {}

    private final RaskolVault plugin;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final ReserveBank bank;

    public ConvertEngine(RaskolVault plugin, WalletService wallets,
                         CurrencyRegistry currencies, ReserveBank bank) {
        this.plugin = plugin;
        this.wallets = wallets;
        this.currencies = currencies;
        this.bank = bank;
    }

    private double minAmount() { return plugin.getConfig().getDouble("exchange.min-amount", 1.0D); }
    private double minFee() { return plugin.getConfig().getDouble("exchange.min-fee", 0.01D); }
    private double maxTx() { return plugin.getConfig().getDouble("safety.max-transaction", 1_000_000_000.0D); }

    public double rate(String fromId, String toId) {
        if (fromId == null || toId == null) return 0.0D;
        if (fromId.equalsIgnoreCase(toId)) return 1.0D;
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null) return 0.0D;
        double pf = bank.priceOf(from);
        double pt = bank.priceOf(to);
        if (!(pf > 0.0D) || !(pt > 0.0D)) return 0.0D;
        double r = pf / pt;
        return Double.isFinite(r) && r > 0.0D ? r : 0.0D;
    }

    /** Причина блокировки конверта; пустая строка = можно конвертировать. */
    public String blockReason(UUID owner, String fromId, String toId, double amount) {
        Currency from = currencies.get(fromId).orElse(null);
        Currency to = currencies.get(toId).orElse(null);
        if (from == null || to == null) return "валюта не найдена";
        // 1.2.6-b: блок конверта в саму себя
        if (from.id().equalsIgnoreCase(to.id())) return "нельзя менять валюту на саму себя";
        if (!Double.isFinite(amount) || !(amount > 0.0D)) return "некорректная сумма";
        if (amount < minAmount()) return "слишком маленькая сумма (минимум " + fmt(minAmount()) + ")";
        if (amount > maxTx()) return "слишком большая сумма";
        if (plugin.getRates().isEmbargoed(from.id(), to.id()))
            return "эмбарго: пара " + from.id() + "↔" + to.id() + " закрыта политикой";
        double pf = bank.priceOf(from);
        double pt = bank.priceOf(to);
        if (from.type() != CurrencyType.GLOBAL && pf <= 0.0D)
            return from.id() + " не обеспечена резервом — конверт закрыт";
        if (to.type() != CurrencyType.GLOBAL && pt <= 0.0D)
            return to.id() + " не обеспечена резервом — конверт закрыт";
        double r = pf / pt;
        if (!Double.isFinite(r) || r <= 0.0D) return "курс не определён";
        if (from.type() != CurrencyType.GLOBAL) {
            double goldOut = goldOutFor(from, amount);
            if (bank.reserveOf(from.nationId()) + 1e-9D < goldOut)
                return "резерв нации " + from.nationId() + " исчерпан для этой суммы";
        }
        if (owner != null && !wallets.has(owner, from.id(), amount)) return "недостаточно " + from.id();
        return "";
    }

    public Optional<Quote> quote(UUID owner, String fromId, String toId, double amount) {
        if (!blockReason(owner, fromId, toId, amount).isEmpty()) return Optional.empty();
        Currency from = currencies.get(fromId).orElseThrow();
        Currency to = currencies.get(toId).orElseThrow();
        double baseRate = plugin.getRates().feeFor(fromId, toId);
        double taxRate = (to.type() == CurrencyType.NATIONAL && to.nationId() != null)
                ? bank.taxOf(to.nationId()) : 0.0D;
        double feeBase = round2dec(Math.max(amount * baseRate, minFee()), from);
        double feeTax = round2dec(amount * taxRate, from);
        if (feeBase + feeTax >= amount) return Optional.empty();
        double pf = bank.priceOf(from);
        double pt = bank.priceOf(to);
        double rate = pf / pt;
        double net = round2dec((amount - feeBase - feeTax) * rate, to);
        double taxInTo = round2dec(feeTax * rate, to);
        double goldFlow = goldOutFor(from, amount);
        return Optional.of(new Quote(from.id(), to.id(), amount, rate, feeBase, feeTax, net,
                (to.type() == CurrencyType.NATIONAL) ? to.nationId() : null, taxInTo, goldFlow));
    }

    public Optional<Quote> execute(UUID owner, String fromId, String toId, double amount) {
        Optional<Quote> q = quote(owner, fromId, toId, amount);
        if (q.isEmpty()) return q;
        Quote quote = q.get();
        boolean fromNat = !fromId.equalsIgnoreCase(currencies.globalId());
        boolean toNat = !toId.equalsIgnoreCase(currencies.globalId());

        if (fromNat && !bank.reserveDebit(fromNation(fromId), quote.goldFlow())) return Optional.empty();
        if (toNat && !bank.reserveCredit(toNation(toId), quote.goldFlow())) {
            if (fromNat) bank.reserveCredit(fromNation(fromId), quote.goldFlow());
            return Optional.empty();
        }
        if (!wallets.withdraw(owner, fromId, amount, TransactionType.CONVERT, "convert:" + fromId + "->" + toId)) {
            if (toNat) bank.reserveDebit(toNation(toId), quote.goldFlow());
            if (fromNat) bank.reserveCredit(fromNation(fromId), quote.goldFlow());
            return Optional.empty();
        }
        if (!wallets.deposit(owner, toId, quote.net(), TransactionType.CONVERT, "convert:" + fromId + "->" + toId)) {
            wallets.deposit(owner, fromId, amount, TransactionType.CONVERT, "convert:rollback");
            if (toNat) bank.reserveDebit(toNation(toId), quote.goldFlow());
            if (fromNat) bank.reserveCredit(fromNation(fromId), quote.goldFlow());
            return Optional.empty();
        }
        if (quote.taxNation() != null && quote.taxInTo() > 0.0D) {
            if (!wallets.deposit(ReserveBank.treasuryUuid(quote.taxNation()), toId, quote.taxInTo(),
                    TransactionType.CONVERT, "convert:tax:" + quote.taxNation())) {
                plugin.getLogger().warning("RaskolVault: налог конвертации в казну "
                        + quote.taxNation() + " не зачислен (" + quote.taxInTo() + " " + toId + ")");
            }
        }
        return q;
    }

    private String fromNation(String currencyId) {
        return currencies.get(currencyId).map(Currency::nationId).orElse("");
    }
    private String toNation(String currencyId) {
        return currencies.get(currencyId).map(Currency::nationId).orElse("");
    }

    private double goldOutFor(Currency from, double amount) {
        if (from.type() == CurrencyType.GLOBAL) return amount;
        double baseRate = plugin.getRates().feeFor(from.id(), from.id());
        return (amount - amount * baseRate) * bank.priceOf(from);
    }

    private double round2dec(double v, Currency c) {
        int scale = c == null ? 2 : c.decimals();
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
