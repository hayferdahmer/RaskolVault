// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private BankLoan loan(double principal) {
        return new BankLoan("loan-test", UUID.randomUUID(), "Игрок", "raskol",
                principal, "GLD", 0.0D, 30, 1_000L, 3_000L,
                BankLoan.CollateralType.NONE, null, null, 0.0D, 0);
    }
}
