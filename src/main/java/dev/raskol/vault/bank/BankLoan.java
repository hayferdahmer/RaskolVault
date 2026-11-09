// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import java.util.UUID;

/**
 * Кредит (1.2.6 part2): добавлен CollateralType.NONE для необеспеченных кредитов.
 */
public final class BankLoan {

    public enum CollateralType { NONE, CURRENCY, ITEM }
    public enum Status { ACTIVE, REPAID, DEFAULTED, LIQUIDATED }

    private final String id;
    private final UUID borrower;
    private final String borrowerName;
    private final String nation;
    private double principal;
    private final String currencyId;
    private final double rateAnnual;
    private final int termDays;
    private final long openedAt;
    private final long dueAt;
    private double repaid;
    private double accrued;
    private long lastAccrualAt;
    private Status status;
    private final CollateralType collateralType;
    private final String collateralCurrency;
    private final String collateralItemBase64;
    private final double collateralValue;
    private final double collateralAmount;
    private final double collateralPrice;
    private final int creditScoreAtOpen;

    public BankLoan(String id, UUID borrower, String borrowerName, String nation,
                    double principal, String currencyId, double rateAnnual, int termDays,
                    long openedAt, long dueAt,
                    CollateralType collateralType, String collateralCurrency,
                    String collateralItemBase64, double collateralValue, int creditScoreAtOpen) {
        this(id, borrower, borrowerName, nation, principal, currencyId, rateAnnual, termDays,
                openedAt, dueAt, collateralType, collateralCurrency, collateralItemBase64,
                collateralValue, collateralValue, 1.0D, creditScoreAtOpen);
    }

    public BankLoan(String id, UUID borrower, String borrowerName, String nation,
                    double principal, String currencyId, double rateAnnual, int termDays,
                    long openedAt, long dueAt, CollateralType collateralType, String collateralCurrency,
                    String collateralItemBase64, double collateralValue, double collateralAmount,
                    double collateralPrice, int creditScoreAtOpen) {
        this.id = id; this.borrower = borrower; this.borrowerName = borrowerName;
        this.nation = nation; this.principal = principal; this.currencyId = currencyId;
        this.rateAnnual = rateAnnual; this.termDays = termDays;
        this.openedAt = openedAt; this.dueAt = dueAt;
        this.repaid = 0.0D; this.accrued = 0.0D; this.lastAccrualAt = openedAt;
        this.status = Status.ACTIVE;
        this.collateralType = collateralType;
        this.collateralCurrency = collateralCurrency;
        this.collateralItemBase64 = collateralItemBase64;
        this.collateralValue = collateralValue;
        this.collateralAmount = collateralAmount;
        this.collateralPrice = collateralPrice;
        this.creditScoreAtOpen = creditScoreAtOpen;
    }

    public String id() { return id; }
    public UUID borrower() { return borrower; }
    public String borrowerName() { return borrowerName; }
    public String nation() { return nation; }
    public double principal() { return principal; }
    public String currencyId() { return currencyId; }
    public double rateAnnual() { return rateAnnual; }
    public int termDays() { return termDays; }
    public long openedAt() { return openedAt; }
    public long dueAt() { return dueAt; }
    public double repaid() { return repaid; }
    public double accrued() { return accrued; }
    public long lastAccrualAt() { return lastAccrualAt; }
    public Status status() { return status; }
    public CollateralType collateralType() { return collateralType; }
    public String collateralCurrency() { return collateralCurrency; }
    public String collateralItemBase64() { return collateralItemBase64; }
    public double collateralValue() { return collateralValue; }
    public double collateralAmount() { return collateralAmount; }
    public double collateralPrice() { return collateralPrice; }
    public int creditScoreAtOpen() { return creditScoreAtOpen; }

    public void setRepaid(double v) { this.repaid = v; }
    public void setAccrued(double v) { this.accrued = v; }
    public void setLastAccrualAt(long v) { this.lastAccrualAt = v; }

    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isOverdue(long now) { return status == Status.ACTIVE && now > dueAt; }

    public void accrueTo(long now) {
        if (status != Status.ACTIVE) return;
        long to = Math.min(now, dueAt);
        if (to <= lastAccrualAt) return;
        double days = (to - lastAccrualAt) / 86_400_000.0D;
        accrued += principal * rateAnnual * days / 365.0D;
        lastAccrualAt = to;
    }

    public double outstanding(long now) {
        accrueTo(now);
        // principal уже уменьшается в applyRepayment; repaid хранится только как история выплат.
        return Math.max(0.0D, principal + accrued);
    }

    public void applyRepayment(double pay) {
        double interestPart = Math.min(pay, accrued);
        double principalPart = pay - interestPart;
        accrued -= interestPart;
        principal = Math.max(0.0D, principal - principalPart);
        repaid += pay;
    }

    public record LiquidationSettlement(double interestRecovered, double principalRecovered,
                                         double borrowerSurplus, double uncoveredDebt) {}

    /**
     * Распределяет стоимость изъятого залога: сначала проценты, затем тело кредита.
     * Излишек возвращается заёмщику, а не остаётся доходом банка.
     */
    public LiquidationSettlement liquidationSettlement(double proceeds) {
        if (!Double.isFinite(proceeds) || proceeds < 0.0D) {
            throw new IllegalArgumentException("Стоимость залога должна быть конечной и неотрицательной");
        }
        double interestRecovered = Math.min(accrued, proceeds);
        double afterInterest = Math.max(0.0D, proceeds - interestRecovered);
        double principalRecovered = Math.min(principal, afterInterest);
        double borrowerSurplus = Math.max(0.0D, afterInterest - principalRecovered);
        double uncoveredDebt = Math.max(0.0D, principal + accrued - proceeds);
        return new LiquidationSettlement(interestRecovered, principalRecovered,
                borrowerSurplus, uncoveredDebt);
    }

    public void markRepaid() { this.status = Status.REPAID; }
    public void markDefaulted() { this.status = Status.DEFAULTED; }
    public void markLiquidated() { this.status = Status.LIQUIDATED; }
}
