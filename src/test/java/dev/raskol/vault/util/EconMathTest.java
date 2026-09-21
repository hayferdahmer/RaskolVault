// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit-тесты экономической математики (1.1.5). Без Bukkit. */
final class EconMathTest {

    private static final double EPS = 1.0E-9D;

    @Test
    void roundHalfUp() {
        assertEquals(1.25D, EconMath.round(1.245D, 2), EPS);
        assertEquals(1.24D, EconMath.round(1.244D, 2), EPS);
        assertEquals(0.1D, EconMath.round(0.05D, 1), EPS);
    }

    @Test
    void coverageFullAndPartial() {
        // резерв 1000, эмиссия 1000, паритет 1.0 → покрытие 100%
        assertEquals(1.0D, EconMath.coverage(1000, 1000, 1.0), EPS);
        // резерв 500 → 50%
        assertEquals(0.5D, EconMath.coverage(500, 1000, 1.0), EPS);
        // эмиссия 0 → полное доверие
        assertEquals(1.0D, EconMath.coverage(0, 0, 1.0), EPS);
    }

    @Test
    void priceCappedByParity() {
        // переобеспечение: резерв/эмиссия = 2.0, паритет 1.0 → цена = паритет 1.0
        assertEquals(1.0D, EconMath.priceFromReserve(2000, 1000, 1.0), EPS);
        // недообеспечение: резерв/эмиссия = 0.5, паритет 1.0 → цена 0.5
        assertEquals(0.5D, EconMath.priceFromReserve(500, 1000, 1.0), EPS);
        // эмиссия 0 → паритет
        assertEquals(1.5D, EconMath.priceFromReserve(0, 0, 1.5), EPS);
    }

    @Test
    void feesAndNet() {
        double amount = 100.0D;
        double base = EconMath.feeBase(amount, 0.02D);   // 2
        double tax = EconMath.feeTax(amount, 0.05D);     // 5
        double net = EconMath.net(amount, base, tax, 2.0D); // (100-2-5)*2 = 186
        assertEquals(2.0D, base, EPS);
        assertEquals(5.0D, tax, EPS);
        assertEquals(186.0D, net, EPS);
    }

    @Test
    void rateFromPricesGuards() {
        assertEquals(2.0D, EconMath.rateFromPrices(2.0D, 1.0D), EPS);
        assertEquals(0.0D, EconMath.rateFromPrices(0.0D, 1.0D), EPS);
        assertEquals(0.0D, EconMath.rateFromPrices(2.0D, 0.0D), EPS);
    }

    @Test
    void roundTripNoArbitrageAfterFees() {
        // Симметричные курсы без комиссий дают 0 прибыли
        assertEquals(0.0D, EconMath.roundTripProfit(2.0D, 0.5D), EPS);
        // С комиссиями реальная прибыль отрицательна (проверяется на уровне ConvertEngine),
        // здесь лишь убеждаемся, что формула корректна.
        assertTrue(EconMath.roundTripProfit(2.0D, 0.4D) < 0.0D);
    }

    @Test
    void maxMintRespectsCoverageFloor() {
        // резерв 1000, паритет 1.0, floor 0.5 → cap = 2000; эмиссия 500 → maxMint 1500
        assertEquals(1500.0D, EconMath.maxMint(1000, 1.0, 0.5, 500), EPS);
        // эмиссия уже на капе → 0
        assertEquals(0.0D, EconMath.maxMint(1000, 1.0, 0.5, 2000), EPS);
    }
}
