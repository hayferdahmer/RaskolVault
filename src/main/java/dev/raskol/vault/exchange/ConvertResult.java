// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.exchange;

/**
 * Публичный результат конвертации для API (1.2.0).
 * Обёртка над внутренним ConvertEngine.Quote — стабильный контракт для плагинов-друзей.
 *
 * Поля:
 * - success: успешна ли конвертация
 * - fromId/toId: ID валют (uppercase)
 * - amount: сумма во входной валюте
 * - net: сумма, полученная в целевой валюте (после комиссий)
 * - rate: курс from→to (без комиссий)
 * - fee: общая комиссия (base + tax)
 * - errorMessage: причина провала (если success=false)
 */
public final class ConvertResult {

    private final boolean success;
    private final String fromId;
    private final String toId;
    private final double amount;
    private final double net;
    private final double rate;
    private final double fee;
    private final String errorMessage;

    private ConvertResult(boolean success, String fromId, String toId,
                          double amount, double net, double rate, double fee,
                          String errorMessage) {
        this.success = success;
        this.fromId = fromId;
        this.toId = toId;
        this.amount = amount;
        this.net = net;
        this.rate = rate;
        this.fee = fee;
        this.errorMessage = errorMessage;
    }

    public static ConvertResult success(String fromId, String toId, double amount,
                                        double net, double rate, double fee) {
        return new ConvertResult(true, fromId, toId, amount, net, rate, fee, null);
    }

    public static ConvertResult failure(String errorMessage) {
        return new ConvertResult(false, null, null, 0.0D, 0.0D, 0.0D, 0.0D, errorMessage);
    }

    public static ConvertResult fromQuote(ConvertEngine.Quote quote) {
        if (quote == null) {
            return failure("null quote");
        }
        return success(
                quote.fromId(),
                quote.toId(),
                quote.amount(),
                quote.net(),
                quote.rate(),
                quote.feeBase() + quote.feeTax()
        );
    }

    public boolean success() { return success; }
    public String fromId() { return fromId; }
    public String toId() { return toId; }
    public double amount() { return amount; }
    public double net() { return net; }
    public double rate() { return rate; }
    public double fee() { return fee; }
    public String errorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return success
                ? String.format("ConvertResult[%s→%s: %.2f→%.2f, rate=%.4f, fee=%.2f]",
                fromId, toId, amount, net, rate, fee)
                : "ConvertResult[failed: " + errorMessage + "]";
    }
}
