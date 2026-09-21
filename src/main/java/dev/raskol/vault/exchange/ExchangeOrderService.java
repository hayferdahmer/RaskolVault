// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.api.currency.Currency;
import dev.raskol.vault.api.currency.CurrencyRegistry;
import dev.raskol.vault.api.transaction.TransactionType;
import dev.raskol.vault.escrow.EscrowService;
import dev.raskol.vault.storage.SQLiteLedger;
import dev.raskol.vault.storage.SQLiteLedger.ExchangeOrderRow;
import dev.raskol.vault.wallet.WalletService;

import java.util.List;
import java.util.UUID;

/**
 * Сервис ордеров межгосударственной биржи (1.2.1).
 *
 * Семантика ордера:
 *  - sell_currency / sell_amount — что продаёт владелец (заморожено в escrow);
 *  - buy_currency / buy_amount — что владелец хочет получить суммарно;
 *  - price = buy_amount / sell_amount (цена за единицу продаваемой валюты);
 *  - статусы: OPEN → MATCHED (исполнен целиком) / CANCELLED (отменён владельцем).
 *
 * В 1.2.1 исполнение — all-or-nothing (ордер забирается целиком).
 * Частичные исполнения — в 1.2.2.
 * Выставлять ордера могут ТОЛЬКО короли наций (временное ограничение).
 */
public final class ExchangeOrderService {

    // FIX: переименованы ok()/fail() → success()/failure() во избежание
    // конфликта с автоматическим аксессором поля `ok` в record.
    public record CreateResult(boolean success, String orderId, String error) {
        public static CreateResult success(String id) { return new CreateResult(true, id, null); }
        public static CreateResult failure(String err) { return new CreateResult(false, null, err); }
    }

    public record MatchResult(boolean success, String error) {
        public static MatchResult success() { return new MatchResult(true, null); }
        public static MatchResult failure(String err) { return new MatchResult(false, err); }
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

    /** Продать amount валюты sellCurrency по price GLD за единицу. */
    public CreateResult createSellOrder(UUID owner, String nation, String sellCurrency,
                                        double amount, double price) {
        String glb = currencies.globalId();
        return createOrder(owner, nation, sellCurrency, glb, amount, round2(amount * price));
    }

    /** Купить amount валюты buyCurrency по price GLD за единицу (замораживается GLD). */
    public CreateResult createBuyOrder(UUID owner, String nation, String buyCurrency,
                                       double amount, double price) {
        String glb = currencies.globalId();
        return createOrder(owner, nation, glb, buyCurrency, round2(amount * price), amount);
    }

    private CreateResult createOrder(UUID owner, String nation, String sellCur, String buyCur,
                                     double sellAmount, double buyAmount) {
        if (!plugin.getTownyHook().isAvailable()
                || !plugin.getTownyHook().isKing(owner, nation)) {
            return CreateResult.failure("только король нации может выставлять ордера");
        }
        Currency sell = currencies.get(sellCur).orElse(null);
        Currency buy = currencies.get(buyCur).orElse(null);
        if (sell == null || buy == null) {
            return CreateResult.failure("валюта не найдена");
        }
        if (sell.id().equals(buy.id())) {
            return CreateResult.failure("нельзя торговать валюту за саму себя");
        }
        if (!sell.tradeable() || !buy.tradeable()) {
            return CreateResult.failure("одна из валют неторгуемая");
        }
        if (!(sellAmount > 0.0D) || !(buyAmount > 0.0D)) {
            return CreateResult.failure("некорректная сумма");
        }
        String orderId = UUID.randomUUID().toString();
        // Заморозка продаваемой валюты в escrow (билет = id ордера)
        if (!escrow.hold(owner, sell.id(), sellAmount, orderId)) {
            return CreateResult.failure("недостаточно средств для заморозки");
        }
        double price = buyAmount / sellAmount;
        long now = System.currentTimeMillis();
        try {
            ledger.exchangeOrderInsert(new ExchangeOrderRow(
                    orderId, owner, nation, sell.id(), buy.id(),
                    sellAmount, buyAmount, price, sellAmount, "OPEN", now, now));
        } catch (RuntimeException e) {
            escrow.refund(orderId);
            return CreateResult.failure("ошибка БД: " + e.getMessage());
        }
        return CreateResult.success(orderId);
    }

    /** Отмена своего OPEN-ордера: возврат заморозки. */
    public MatchResult cancelOrder(String orderId, UUID owner) {
        ExchangeOrderRow order = ledger.exchangeOrderGet(orderId);
        if (order == null) {
            return MatchResult.failure("ордер не найден");
        }
        if (!order.owner().equals(owner)) {
            return MatchResult.failure("это не ваш ордер");
        }
        if (!"OPEN".equals(order.status())) {
            return MatchResult.failure("ордер уже закрыт");
        }
        escrow.refund(orderId);
        ledger.exchangeOrderUpdateRemaining(orderId, 0.0D, "CANCELLED");
        return MatchResult.success();
    }

    /**
     * Исполнение ордера целиком: taker отдаёт buy_amount своей валюты владельцу,
     * получает замороженную sell_amount из escrow.
     */
    public MatchResult takeOrder(String orderId, UUID taker) {
        ExchangeOrderRow order = ledger.exchangeOrderGet(orderId);
        if (order == null) {
            return MatchResult.failure("ордер не найден");
        }
        if (!"OPEN".equals(order.status())) {
            return MatchResult.failure("ордер уже закрыт");
        }
        if (order.owner().equals(taker)) {
            return MatchResult.failure("нельзя забрать собственный ордер");
        }
        if (!plugin.getTownyHook().isAvailable()
                || !plugin.getTownyHook().isKing(taker,
                plugin.getTownyHook().nationOf(taker) == null ? "" : plugin.getTownyHook().nationOf(taker))) {
            return MatchResult.failure("только короли наций могут забирать ордера (временное ограничение)");
        }
        // 1) taker платит buy_amount владельцу
        if (!wallets.withdraw(taker, order.buyCurrency(), order.buyAmount(),
                TransactionType.PAY, "exchange:take:" + orderId)) {
            return MatchResult.failure("недостаточно " + order.buyCurrency() + " для исполнения");
        }
        if (!wallets.deposit(order.owner(), order.buyCurrency(), order.buyAmount(),
                TransactionType.PAY, "exchange:take:" + orderId)) {
            wallets.deposit(taker, order.buyCurrency(), order.buyAmount(),
                    TransactionType.PAY, "exchange:take:rollback:" + orderId);
            return MatchResult.failure("ошибка зачисления владельцу");
        }
        // 2) замороженная sell-валюта уходит taker'у
        if (!escrow.release(orderId, taker)) {
            // откат платежа
            wallets.withdraw(order.owner(), order.buyCurrency(), order.buyAmount(),
                    TransactionType.PAY, "exchange:take:rollback:" + orderId);
            wallets.deposit(taker, order.buyCurrency(), order.buyAmount(),
                    TransactionType.PAY, "exchange:take:rollback:" + orderId);
            return MatchResult.failure("ошибка передачи заморозки");
        }
        ledger.exchangeOrderUpdateRemaining(orderId, 0.0D, "MATCHED");
        return MatchResult.success();
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

    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}
