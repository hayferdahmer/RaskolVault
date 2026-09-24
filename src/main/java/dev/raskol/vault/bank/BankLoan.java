// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import java.util.UUID;

/**
 * Кредит (1.2.6-a fix): проценты накапливаются в accrued через accrueTo(),
 * а не пересчитываются от начального тела → корректное частичное погашение (Баг 6).
 *
 * 1.2.6-a дополнение: сеттеры setRepaid / setAccrued / setLastAccrualAt
 * для восстановления состояния из YAML (BankService.restoreLoanState).
 */
public final class BankLoan {

    public enum CollateralType { CURRENCY, ITEM }
    public enum Status { ACTIVE, REPAID, DEFAULTED, LIQUIDATED }

    private final String id;
    private final UUID borrower;
    private final String borrowerName;
    private final String nation;
    private double principal;          // mutable: уменьшается при погашении
    private final String currencyId;
    private final double rateAnnual;
    private final int termDays;
    private final long openedAt;
    private final long dueAt;
    private double repaid;
    private double accrued;            // накопленные проценты (Баг 6)
    private long lastAccrualAt;        // момент последнего начисления
    private Status status;
    private final CollateralType collateralType;
    private final String collateralCurrency;
    private final String collateralItemBase64;
    private final double collateralValue;
    private final int creditScoreAtOpen;

    public BankLoan(String id, UUID borrower, String borrowerName, String nation,
                    double principal, String currencyId, double rateAnnual, int termDays,
                    long openedAt, long dueAt,
                    CollateralType collateralType, String collateralCurrency,
                    String collateralItemBase64, double collateralValue, int creditScoreAtOpen) {
        this.id = id;
        this.borrower = borrower;
        this.borrowerName = borrowerName;
        this.nation = nation;
        this.principal = principal;
        this.currencyId = currencyId;
        this.rateAnnual = rateAnnual;
        this.termDays = termDays;
        this.openedAt = openedAt;
        this.dueAt = dueAt;
        this.repaid = 0.0D;
        this.accrued = 0.0D;
        this.lastAccrualAt = openedAt;
        this.status = Status.ACTIVE;
        this.collateralType = collateralType;
        this.collateralCurrency = collateralCurrency;
        this.collateralItemBase64 = collateralItemBase64;
        this.collateralValue = collateralValue;
        this.creditScoreAtOpen = creditScoreAtOpen;
    }

    // ---------- геттеры ----------
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
    public int creditScoreAtOpen() { return creditScoreAtOpen; }

    // ---------- сеттеры для restoreLoanState (1.2.6-a) ----------
    public void setRepaid(double v) { this.repaid = v; }
    public void setAccrued(double v) { this.accrued = v; }
    public void setLastAccrualAt(long v) { this.lastAccrualAt = v; }

    // ---------- состояние ----------
    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isOverdue(long now) { return status == Status.ACTIVE && now > dueAt; }

    /** Начислить проценты от ТЕКУЩЕГО тела за период с lastAccrualAt до min(now, dueAt). */
    public void accrueTo(long now) {
        if (status != Status.ACTIVE) return;
        long to = Math.min(now, dueAt);
        if (to <= lastAccrualAt) return;
        double days = (to - lastAccrualAt) / 86_400_000.0D;
        accrued += principal * rateAnnual * days / 365.0D;
        lastAccrualAt = to;
    }

    /** Полная сумма к возврату: тело + накопленные проценты − выплачено. */
    public double outstanding(long now) {
        accrueTo(now);
        return Math.max(0.0D, principal + accrued - repaid);
    }

    /** Применить погашение: сначала гасит накопленные проценты, остаток уменьшает тело. */
    public void applyRepayment(double pay) {
        double interestPart = Math.min(pay, accrued);
        double principalPart = pay - interestPart;
        accrued -= interestPart;
        principal = Math.max(0.0D, principal - principalPart);
        repaid += pay;
    }

    public void markRepaid() { this.status = Status.REPAID; }
    public void markDefaulted() { this.status = Status.DEFAULTED; }
    public void markLiquidated() { this.status = Status.LIQUIDATED; }
}
