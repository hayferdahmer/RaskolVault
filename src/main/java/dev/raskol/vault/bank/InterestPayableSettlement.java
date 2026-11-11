// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import dev.raskol.vault.storage.SQLiteLedger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Распределяет доступный процентный резерв между признанными требованиями вкладчиков по FIFO.
 */
final class InterestPayableSettlement {

    record Payment(SQLiteLedger.BankInterestPayableRow claim, double amount, double remaining) {}

    record Result(List<Payment> payments, double reserveRemaining) {
        Result {
            payments = List.copyOf(payments);
        }
    }

    private InterestPayableSettlement() {}

    static Result settle(List<SQLiteLedger.BankInterestPayableRow> claims,
                         String nation, String currency, double availableReserve) {
        if (claims == null || nation == null || currency == null
                || !Double.isFinite(availableReserve) || availableReserve < 0.0D) {
            throw new IllegalArgumentException("Некорректные параметры распределения процентного резерва");
        }
        List<SQLiteLedger.BankInterestPayableRow> ordered = new ArrayList<>(claims);
        ordered.sort(Comparator.comparingLong(SQLiteLedger.BankInterestPayableRow::createdAt)
                .thenComparing(SQLiteLedger.BankInterestPayableRow::id));
        double remainingReserve = availableReserve;
        List<Payment> payments = new ArrayList<>();
        for (SQLiteLedger.BankInterestPayableRow claim : ordered) {
            if (claim == null || !claim.nation().equalsIgnoreCase(nation)
                    || !claim.currencyId().equalsIgnoreCase(currency)
                    || remainingReserve <= 1.0E-8D) {
                continue;
            }
            if (!Double.isFinite(claim.amount()) || claim.amount() <= 0.0D) {
                throw new IllegalArgumentException("Некорректная сумма обязательства по процентам: " + claim.id());
            }
            double paid = Math.min(remainingReserve, claim.amount());
            if (paid <= 1.0E-8D) continue;
            remainingReserve = Math.max(0.0D, remainingReserve - paid);
            payments.add(new Payment(claim, paid, Math.max(0.0D, claim.amount() - paid)));
        }
        return new Result(payments, remainingReserve);
    }
}
