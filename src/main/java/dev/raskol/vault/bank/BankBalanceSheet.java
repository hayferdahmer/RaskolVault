// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

/**
 * Сверка банковских активов и обязательств в одной валюте.
 * Удерживаемый залог является справочной оценкой и не включается в активы.
 */
public record BankBalanceSheet(
        String nation,
        String currencyId,
        double liquidityPool,
        double interestReserve,
        double depositPrincipal,
        double depositAccruedInterest,
        double loanPrincipal,
        double loanAccruedInterest,
        double unpaidInterestClaims,
        double heldCollateralAppraisal,
        int heldCollateralLots
) {
    public double cashAssets() {
        return liquidityPool + interestReserve;
    }

    public double loanReceivables() {
        return loanPrincipal + loanAccruedInterest;
    }

    public double totalAssets() {
        return cashAssets() + loanReceivables();
    }

    public double depositLiabilities() {
        return depositPrincipal + depositAccruedInterest;
    }

    public double totalLiabilities() {
        return depositLiabilities() + unpaidInterestClaims;
    }

    public double equity() {
        return totalAssets() - totalLiabilities();
    }
}
