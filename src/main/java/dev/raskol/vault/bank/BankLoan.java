// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import java.util.UUID;

/**
 * Кредит (1.2.5-b).
 * Залог: CURRENCY (замороженная валюта) или ITEM (предмет, base64, оценка по конфигу).
 * Проценты простые; начисляются до dueAt. После dueAt — просрочка → ликвидация залога.
 * Кредитная история влияет на лимит и ставку (creditScore).
 */
public final class BankLoan {

    public enum CollateralType { CURRENCY, ITEM }
    public enum Status { ACTIVE, REPAID, DEFAULTED, LIQUIDATED }

    private final String id;
    private final UUID borrower;
    private final String borrowerName;
    private final String nation;
    private final double principal;
    private final String currencyId;
    private final double rateAnnual;
    private final int termDays;
    private final long openedAt;
    private final long dueAt;
    private double repaid;
    private Status status;
    private final CollateralType collateralType;
    private final String collateralCurrency;   // для CURRENCY
    private final String collateralItemBase64; // для ITEM
    private final double collateralValue;
    private final int creditScoreAtOpen;

    public BankLoan(String id, UUID borrower, String borrowerName, String nation,
                    double principal, String currencyId, double rateAnnual, int termDays,
                    long openedAt, long dueAt,
                    CollateralType collateralType, String collateralCurrency,
                    String collateralItemBase64, double collateralValue, int creditScoreAtOpen) {
        this.id = id; this.borrower = borrower; this.borrowerName = borrowerName;
        this.nation = nation; this.principal = principal; this.currencyId = currencyId;
        this.rateAnnual = rateAnnual; this.termDays = termDays;
        this.openedAt = openedAt; this.dueAt = dueAt;
        this.repaid = 0.0D; this.status = Status.ACTIVE;
        this.collateralType = collateralType;
        this.collateralCurrency = collateralCurrency;
        this.collateralItemBase64 = collateralItemBase64;
        this.collateralValue = collateralValue;
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
    public Status status() { return status; }
    public CollateralType collateralType() { return collateralType; }
    public String collateralCurrency() { return collateralCurrency; }
    public String collateralItemBase64() { return collateralItemBase64; }
    public double collateralValue() { return collateralValue; }
    public int creditScoreAtOpen() { return creditScoreAtOpen; }

    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isOverdue(long now) { return status == Status.ACTIVE && now > dueAt; }

    /** Начисленные проценты на момент now (кап до dueAt). */
    public double accruedInterest(long now) {
        long to = Math.min(now, dueAt);
        if (to <= openedAt) return 0.0D;
        double days = (to - openedAt) / 86_400_000.0D;
        return principal * rateAnnual * days / 365.0D;
    }

    /** Полная сумма к возврату на момент now = тело + проценты − уже выплачено. */
    public double outstanding(long now) {
        double total = principal + accruedInterest(now);
        return Math.max(0.0D, total - repaid);
    }

    public void addRepayment(double amount) { this.repaid += amount; }
    public void markRepaid() { this.status = Status.REPAID; }
    public void markDefaulted() { this.status = Status.DEFAULTED; }
    public void markLiquidated() { this.status = Status.LIQUIDATED; }
}
