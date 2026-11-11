// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankBalanceSheetTest {

    @Test
    void calculatesAssetsLiabilitiesAndEquityWithoutCountingHeldCollateralAsCash() {
        BankBalanceSheet sheet = new BankBalanceSheet(
                "raskol", "GLD",
                30.0D, 5.0D,
                50.0D, 2.0D,
                40.0D, 4.0D,
                3.0D,
                100.0D, 2);

        assertEquals(35.0D, sheet.cashAssets(), 0.000_001D);
        assertEquals(44.0D, sheet.loanReceivables(), 0.000_001D);
        assertEquals(79.0D, sheet.totalAssets(), 0.000_001D);
        assertEquals(52.0D, sheet.depositLiabilities(), 0.000_001D);
        assertEquals(55.0D, sheet.totalLiabilities(), 0.000_001D);
        assertEquals(24.0D, sheet.equity(), 0.000_001D);
        assertEquals(100.0D, sheet.heldCollateralAppraisal(), 0.000_001D);
        assertEquals(2, sheet.heldCollateralLots());
    }

    @Test
    void reportsInsolvencyWhenLiabilitiesExceedRecognizedAssets() {
        BankBalanceSheet sheet = new BankBalanceSheet(
                "raskol", "SILVER",
                2.0D, 0.0D,
                10.0D, 1.0D,
                3.0D, 0.0D,
                1.0D,
                500.0D, 1);

        assertEquals(5.0D, sheet.totalAssets(), 0.000_001D);
        assertEquals(12.0D, sheet.totalLiabilities(), 0.000_001D);
        assertEquals(-7.0D, sheet.equity(), 0.000_001D);
    }
}
