// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.escrow.EscrowService;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.storage.SQLiteLedger.ExchangeOrderRow;
import dev.raskol.vault.tax.TaxService;
import dev.raskol.vault.wallet.WalletService;

import java.util.List;
import java.util.UUID;

/**
 * Сервис ордеров межгосударственной биржи (1.2.2-b).
 * Новое:
 *  - частичные исполнения (takePartial: забирает min(remaining, доступная сумма));
 *  - авто-матчинг встречных ордеров (createOrder → matchCounterparty);
 *  - налоги на сделки через TaxService.
 */
public final class ExchangeOrderService {

    public record CreateResult(boolean success, String orderId, String error) {
        public static CreateResult ok(String id) { return new CreateResult(true, id, null); }
        public static CreateResult fail(String err) { return new CreateResult(false, null, err); }
    }

    public record MatchResult(boolean success, double filled, double price, String error) {
        public static MatchResult ok(double filled, double price) { return new MatchResult(true, filled, price, null); }
        public static MatchResult fail(String err) { return new MatchResult(false, 0.0D, 0.0D, err); }
    }

    private final RaskolVault plugin;
    private final SQLiteLedger ledger;
    private final WalletService wallets;
    private final CurrencyRegistry currencies;
    private final EscrowService escrow;

    public ExchangeOrderService(RaskolVault plugin, SQLiteLedger ledger,
                                WalletService wallets, CurrencyRegistry currencies,
                                EscrowService escrow) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.wallets = wallets;
        this.currencies = currencies;
        this.escrow = escrow;
    }

    public CreateResult createSellOrder(UUID owner, String nation, String sellCurrency, double amount, double price) {
        return createOrder(owner, nation, sellCurrency, currencies.globalId(), amount, round2(amount * price));
    }

    public CreateResult createBuyOrder(UUID owner, String nation, String buyCurrency, double amount, double price) {
        return createOrder(owner, nation, currencies.globalId(), buyCurrency, round2(amount * price), amount);
    }

    /**
     * Создание ордера + попытка авто-матчинга встречных.
     * Если найден встречный по цене (sell↔buy, maker_price ≥ taker_price), исполняется
     * автоматически (полностью или частично). Остаток размещается как OPEN-ордер.
     */
    public CreateResult createOrder(UUID owner, String nation, String sellCur, String buyCur,
                                    double sellAmount, double buyAmount) {
        if (!plugin.getTownyHook().isAvailable()
                || !plugin.getTownyHook().isKing(owner, nation)) {
            return CreateResult.fail("только король нации может выставлять ордера");
        }
        Currency sell = currencies.get(sellCur).orElse(null);
        Currency buy = currencies.get(buyCur).orElse(null);
        if (sell == null || buy == null) return CreateResult.fail("валюта не найдена");
        if (sell.id().equals(buy.id())) return CreateResult.fail("нельзя торговать валюту за саму себя");
        if (!sell.tradeable() || !buy.tradeable()) return CreateResult.fail("одна из валют неторгуемая");
        if (!(sellAmount > 0.0D) || !(buyAmount > 0.0D)) return CreateResult.fail("некорректная сумма");

        // 1) авто-матчинг: ищем встречные (maker sells buyCur за sellCur, maker_price <= my price)
        double myPrice = buyAmount / sellAmount; // buy per 1 sell
        double filledSell = 0.0D;
        double filledBuy = 0.0D;
        List<ExchangeOrderRow> candidates = ledger.exchangeOrdersByStatus("OPEN", 500);
        for (ExchangeOrderRow c : candidates) {
            if (c.owner().equals(owner)) continue;
            if (!c.sellCurrency().equalsIgnoreCase(buy.id()) || !c.buyCurrency().equalsIgnoreCase(sell.id())) continue;
            double makerPrice = c.price(); // buy per 1 sell (в их ордере: buyCurrency = my sellCur)
            if (makerPrice > myPrice + 1e-9D) continue; // maker просит больше, чем я готов дать
            double maxSellFromMaker = c.remaining(); // сколько sellCur я получу
            double maxBuyFromMaker = maxSellFromMaker * makerPrice; // сколько buyCur maker возьмёт у меня
            double wantSell = sellAmount - filledSell;
            double wantBuy = buyAmount - filledBuy;
            double takeSell = Math.min(maxSellFromMaker, wantSell);
            double takeBuy = Math.min(maxBuyFromMaker, wantBuy);
            if (!(takeSell > 0.0D) || !(takeBuy > 0.0D)) continue;
            // пропорция по меньшей стороне
            double ratio = Math.min(takeSell / maxSellFromMaker, takeBuy / maxBuyFromMaker);
            double executedSell = round2(maxSellFromMaker * ratio);
            double executedBuy = round2(maxBuyFromMaker * ratio);
            MatchResult mr = executePartial(c, owner, executedSell, executedBuy);
            if (mr.success() && mr.filled() > 0.0D) {
                filledSell += executedSell;
                filledBuy += executedBuy;
                if (filledSell >= sellAmount - 1e-9D && filledBuy >= buyAmount - 1e-9D) {
                    plugin.getLogger().info("RaskolVault: авто-матчинг исполнил ордер " + nation + " полностью (" + fmt(filledSell) + " " + sell.id() + ")");
                    return CreateResult.ok("auto-matched");
                }
            }
        }

        // 2) остаток размещается как OPEN-ордер
        double remainSell = round2(sellAmount - filledSell);
        double remainBuy = round2(buyAmount - filledBuy);
        if (!(remainSell > 0.0D) || !(remainBuy > 0.0D)) {
            return CreateResult.ok("fully-matched");
        }

        String orderId = UUID.randomUUID().toString();
        if (!escrow.hold(owner, sell.id(), remainSell, orderId)) {
            return CreateResult.fail("недостаточно средств для заморозки");
        }
        double price = remainBuy / remainSell;
        long now = System.currentTimeMillis();
        try {
            ledger.exchangeOrderInsert(new SQLiteLedger.ExchangeOrderRow(
                    orderId, owner, nation, sell.id(), buy.id(),
                    remainSell, remainBuy, price, remainSell, "OPEN", now, now));
        } catch (RuntimeException e) {
            escrow.refund(orderId);
            return CreateResult.fail("ошибка БД: " + e.getMessage());
        }
        return CreateResult.ok(orderId);
    }

    /** Частичное исполнение существующего ордера (take). */
    public MatchResult takeOrder(String orderId, UUID taker, double requestedSellAmount) {
        ExchangeOrderRow order = ledger.exchangeOrderGet(orderId);
        if (order == null) return MatchResult.fail("ордер не найден");
        if (!"OPEN".equals(order.status())) return MatchResult.fail("ордер уже закрыт");
        if (order.owner().equals(taker)) return MatchResult.fail("нельзя забрать собственный ордер");
        String takerNation = plugin.getTownyHook().nationOf(taker);
        if (!plugin.getTownyHook().isAvailable()
                || takerNation == null
                || !plugin.getTownyHook().isKing(taker, takerNation)) {
            return MatchResult.fail("только короли наций могут забирать ордера");
        }
        double maxTakeSell = order.remaining();
        double takeSell = Math.min(maxTakeSell, requestedSellAmount);
        if (!(takeSell > 0.0D)) return MatchResult.fail("ордер пуст");
        double takeBuy = round2(takeSell * order.price());
        return executePartial(order, taker, takeSell, takeBuy);
    }

    /** Полное исполнение (обёртка над takeOrder с amount = remaining). */
    public MatchResult takeOrderFull(String orderId, UUID taker) {
        ExchangeOrderRow order = ledger.exchangeOrderGet(orderId);
        if (order == null) return MatchResult.fail("ордер не найден");
        return takeOrder(orderId, taker, order.remaining());
    }

    /** Отмена своего OPEN-ордера. */
    public MatchResult cancelOrder(String orderId, UUID owner) {
        ExchangeOrderRow order = ledger.exchangeOrderGet(orderId);
        if (order == null) return MatchResult.fail("ордер не найден");
        if (!order.owner().equals(owner)) return MatchResult.fail("это не ваш ордер");
        if (!"OPEN".equals(order.status())) return MatchResult.fail("ордер уже закрыт");
        escrow.refund(orderId);
        ledger.exchangeOrderUpdateRemaining(orderId, 0.0D, "CANCELLED");
        return MatchResult.ok(0.0D, 0.0D);
    }

    // ---------- Внутреннее: атомарное исполнение куска ----------
    private MatchResult executePartial(ExchangeOrderRow order, UUID taker, double takeSell, double takeBuy) {
        // 1) taker платит takeBuy buyCur владельцу
        if (!wallets.withdraw(taker, order.buyCurrency(), takeBuy, TransactionType.PAY, "exchange:take:" + order.id())) {
            return MatchResult.fail("недостаточно " + order.buyCurrency() + " для исполнения");
        }
        if (!wallets.deposit(order.owner(), order.buyCurrency(), takeBuy, TransactionType.PAY, "exchange:take:" + order.id())) {
            wallets.deposit(taker, order.buyCurrency(), takeBuy, TransactionType.PAY, "exchange:rollback:" + order.id());
            return MatchResult.fail("ошибка зачисления владельцу");
        }
        // 2) замороженная sellCur уходит taker'у (частично)
        if (!escrow.releasePartial(order.id(), taker, takeSell)) {
            wallets.withdraw(order.owner(), order.buyCurrency(), takeBuy, TransactionType.PAY, "exchange:rollback:" + order.id());
            wallets.deposit(taker, order.buyCurrency(), takeBuy, TransactionType.PAY, "exchange:rollback:" + order.id());
            return MatchResult.fail("ошибка передачи заморозки");
        }
        // 3) налог нации-продавца (если включён)
        TaxService tax = plugin.getTaxService();
        if (tax != null) {
            tax.collectExchange(order.nation(), order.buyCurrency(), takeBuy);
        }
        double newRemaining = round2(order.remaining() - takeSell);
        String newStatus = newRemaining <= 1e-9D ? "MATCHED" : "OPEN";
        ledger.exchangeOrderUpdateRemaining(order.id(), Math.max(0.0D, newRemaining), newStatus);
        return MatchResult.ok(takeSell, order.price());
    }

    public List<ExchangeOrderRow> openOrders(int limit) {
        return ledger.exchangeOrdersByStatus("OPEN", limit);
    }

    public List<ExchangeOrderRow> myOrders(UUID owner) {
        return ledger.exchangeOrdersByOwner(owner);
    }

    public ExchangeOrderRow get(String orderId) {
        return ledger.exchangeOrderGet(orderId);
    }

    private static double round2(double v) { return Math.round(v * 100.0D) / 100.0D; }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
