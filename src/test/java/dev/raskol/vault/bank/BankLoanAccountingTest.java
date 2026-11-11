// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLoanAccountingTest {

    @Test
    void partialRepaymentReducesOutstandingOnlyByPaidAmount() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(10.0D);
        loan.applyRepayment(15.0D);
        assertEquals(95.0D, loan.principal(), 0.000_001D);
        assertEquals(0.0D, loan.accrued(), 0.000_001D);
        assertEquals(15.0D, loan.repaid(), 0.000_001D);
        assertEquals(95.0D, loan.outstanding(1_000L), 0.000_001D);
    }

    @Test
    void fullRepaymentClearsPrincipalAndAccruedInterest() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(10.0D);
        loan.applyRepayment(110.0D);
        assertEquals(0.0D, loan.principal(), 0.000_001D);
        assertEquals(0.0D, loan.accrued(), 0.000_001D);
        assertEquals(110.0D, loan.repaid(), 0.000_001D);
        assertEquals(0.0D, loan.outstanding(1_000L), 0.000_001D);
    }

    @Test
    void accruedInterestIsPaidBeforePrincipal() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(20.0D);
        loan.applyRepayment(10.0D);
        assertEquals(100.0D, loan.principal(), 0.000_001D);
        assertEquals(10.0D, loan.accrued(), 0.000_001D);
        assertEquals(10.0D, loan.repaid(), 0.000_001D);
        assertEquals(110.0D, loan.outstanding(1_000L), 0.000_001D);
    }

    @Test
    void liquidationReturnsCollateralSurplusToBorrower() {
        BankLoan loan = loan(60.0D);
        loan.setAccrued(10.0D);
        BankLoan.LiquidationSettlement settlement = loan.liquidationSettlement(100.0D);
        assertEquals(10.0D, settlement.interestRecovered(), 0.000_001D);
        assertEquals(60.0D, settlement.principalRecovered(), 0.000_001D);
        assertEquals(30.0D, settlement.borrowerSurplus(), 0.000_001D);
        assertEquals(0.0D, settlement.uncoveredDebt(), 0.000_001D);
    }

    @Test
    void liquidationReportsUncoveredDebtWhenCollateralIsInsufficient() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(20.0D);
        BankLoan.LiquidationSettlement settlement = loan.liquidationSettlement(70.0D);
        assertEquals(20.0D, settlement.interestRecovered(), 0.000_001D);
        assertEquals(50.0D, settlement.principalRecovered(), 0.000_001D);
        assertEquals(0.0D, settlement.borrowerSurplus(), 0.000_001D);
        assertEquals(50.0D, settlement.uncoveredDebt(), 0.000_001D);
    }

    @Test
    void recoveryDebtRemainsInLoanBookAndCanBeRepaid() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(20.0D);
        loan.markRecovery();
        assertTrue(loan.hasOutstandingBalance());
        assertTrue(loan.isRepayable());
        assertFalse(loan.isActive());
        assertEquals(120.0D, loan.outstanding(90_000_000L), 0.000_001D);
        loan.applyRepayment(70.0D);
        assertEquals(50.0D, loan.principal(), 0.000_001D);
        assertEquals(0.0D, loan.accrued(), 0.000_001D);
        assertEquals(50.0D, loan.outstanding(180_000_000L), 0.000_001D);
        assertTrue(loan.hasOutstandingBalance());
    }

    @Test
    void recoveryInterestIsFrozenAfterCollateralEnforcement() {
        BankLoan loan = loan(100.0D);
        loan.setAccrued(5.0D);
        loan.setLastAccrualAt(1_000L);
        loan.markRecovery();
        loan.accrueTo(100_000_000L);
        assertEquals(5.0D, loan.accrued(), 0.000_001D);
        assertEquals(1_000L, loan.lastAccrualAt());
    }

    @Test
    void legacyLiquidatedDebtIsStillRecognizedAsOutstanding() {
        BankLoan loan = loan(25.0D);
        loan.markLiquidated();
        assertTrue(loan.hasOutstandingBalance());
        assertTrue(loan.isRepayable());
    }

    @Test
    void currencyCollateralKeepsActualAmountAndLockedPriceSeparatelyFromValuation() {
        BankLoan loan = new BankLoan("fx-loan", UUID.randomUUID(), "Игрок", "raskol",
                100.0D, "GLD", 0.10D, 30, 1_000L, 3_000L,
                BankLoan.CollateralType.CURRENCY, "SILVER", null,
                120.0D, 60.0D, 2.0D, 0);
        assertEquals(120.0D, loan.collateralValue(), 0.000_001D);
        assertEquals(60.0D, loan.collateralAmount(), 0.000_001D);
        assertEquals(2.0D, loan.collateralPrice(), 0.000_001D);
    }

    private BankLoan loan(double principal) {
        return new BankLoan("loan-test", UUID.randomUUID(), "Игрок", "raskol",
                principal, "GLD", 0.0D, 30, 1_000L, 3_000L,
                BankLoan.CollateralType.NONE, null, null, 0.0D, 0);
    }
}
