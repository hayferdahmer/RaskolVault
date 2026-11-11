// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.storage.SQLiteLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterestPayableSettlementTest {

    @Test
    void reservePaysOldestClaimsFirstAndPartiallyPaysNextClaim() {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        SQLiteLedger.BankInterestPayableRow newer = claim(
                "newer", secondOwner, "raskol", "GLD", 30.0D, 200L);
        SQLiteLedger.BankInterestPayableRow older = claim(
                "older", firstOwner, "raskol", "GLD", 20.0D, 100L);

        InterestPayableSettlement.Result result = InterestPayableSettlement.settle(
                List.of(newer, older), "RASKOL", "gld", 25.0D);

        assertEquals(2, result.payments().size());
        assertEquals("older", result.payments().get(0).claim().id());
        assertEquals(20.0D, result.payments().get(0).amount(), 0.000_001D);
        assertEquals(0.0D, result.payments().get(0).remaining(), 0.000_001D);
        assertEquals("newer", result.payments().get(1).claim().id());
        assertEquals(5.0D, result.payments().get(1).amount(), 0.000_001D);
        assertEquals(25.0D, result.payments().get(1).remaining(), 0.000_001D);
        assertEquals(0.0D, result.reserveRemaining(), 0.000_001D);
    }

    @Test
    void claimsFromOtherNationOrCurrencyDoNotConsumeReserve() {
        SQLiteLedger.BankInterestPayableRow otherNation = claim(
                "other-nation", UUID.randomUUID(), "other", "GLD", 10.0D, 100L);
        SQLiteLedger.BankInterestPayableRow otherCurrency = claim(
                "other-currency", UUID.randomUUID(), "raskol", "SILVER", 10.0D, 100L);
        SQLiteLedger.BankInterestPayableRow matching = claim(
                "matching", UUID.randomUUID(), "raskol", "GLD", 7.0D, 100L);

        InterestPayableSettlement.Result result = InterestPayableSettlement.settle(
                List.of(otherNation, otherCurrency, matching), "raskol", "GLD", 12.0D);

        assertEquals(1, result.payments().size());
        assertEquals("matching", result.payments().get(0).claim().id());
        assertEquals(7.0D, result.payments().get(0).amount(), 0.000_001D);
        assertEquals(5.0D, result.reserveRemaining(), 0.000_001D);
    }

    @Test
    void equalTimestampsUseStableIdOrdering() {
        SQLiteLedger.BankInterestPayableRow second = claim(
                "b-claim", UUID.randomUUID(), "raskol", "GLD", 5.0D, 100L);
        SQLiteLedger.BankInterestPayableRow first = claim(
                "a-claim", UUID.randomUUID(), "raskol", "GLD", 5.0D, 100L);

        InterestPayableSettlement.Result result = InterestPayableSettlement.settle(
                List.of(second, first), "raskol", "GLD", 5.0D);

        assertEquals(1, result.payments().size());
        assertEquals("a-claim", result.payments().get(0).claim().id());
        assertEquals(0.0D, result.reserveRemaining(), 0.000_001D);
    }

    private SQLiteLedger.BankInterestPayableRow claim(
            String id, UUID owner, String nation, String currency, double amount, long createdAt) {
        return new SQLiteLedger.BankInterestPayableRow(
                id, owner, "Игрок", nation, currency, amount, createdAt);
    }
}
