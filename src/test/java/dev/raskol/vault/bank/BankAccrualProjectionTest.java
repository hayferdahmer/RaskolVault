// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankAccrualProjectionTest {

    @Test
    void depositProjectionDoesNotMutatePersistedAccrualState() {
        BankAccount account = new BankAccount("deposit", UUID.randomUUID(), "Игрок",
                "raskol", "GLD", 100.0D, BankAccount.Term.DEMAND,
                0.10D, 1_000L, 0L);

        double projected = account.projectedAccrued(86_401_000L, 0.10D);

        assertEquals(0.0273972602739726D, projected, 0.000_000_001D);
        assertEquals(0.0D, account.accrued(), 0.000_001D);
        assertEquals(1_000L, account.lastAccrualAt());
    }

    @Test
    void loanProjectionDoesNotMutateAccrualStateAndStopsAtDueDate() {
        BankLoan loan = new BankLoan("loan", UUID.randomUUID(), "Игрок",
                "raskol", 100.0D, "GLD", 0.365D, 30,
                1_000L, 86_401_000L, BankLoan.CollateralType.NONE,
                null, null, 0.0D, 0);

        double projected = loan.projectedOutstanding(200_000_000L);

        assertEquals(100.1D, projected, 0.000_001D);
        assertEquals(0.0D, loan.accrued(), 0.000_001D);
        assertEquals(1_000L, loan.lastAccrualAt());
    }
}
