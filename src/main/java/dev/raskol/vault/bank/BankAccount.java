// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.bank;

import java.util.UUID;

/**
 * Вклад (1.2.5-b).
 * Term: DEMAND (до востребования, низкая ставка, снятие в любой момент),
 *       TERM_7/30/90 (срочные, ставка выше, снятие в срок без потерь).
 * Проценты — простые (simple interest), начисляются ежедневно в accrued.
 * Досрочное закрытие срочного вклада: потеря начисленных процентов + штраф X% от тела.
 */
public final class BankAccount {

    public enum Term { DEMAND, TERM_7, TERM_30, TERM_90 }
    public enum Status { ACTIVE, CLOSED }

    private final String id;
    private final UUID owner;
    private final String ownerName;
    private final String nation;
    private final String currencyId;
    private final double principal;
    private final Term term;
    private final double rateAnnual;
    private final long openedAt;
    private final long maturesAt; // 0 для DEMAND
    private Status status;
    private double accrued;
    private long lastAccrualAt;

    public BankAccount(String id, UUID owner, String ownerName, String nation, String currencyId,
                       double principal, Term term, double rateAnnual, long openedAt, long maturesAt) {
        this.id = id; this.owner = owner; this.ownerName = ownerName;
        this.nation = nation; this.currencyId = currencyId;
        this.principal = principal; this.term = term; this.rateAnnual = rateAnnual;
        this.openedAt = openedAt; this.maturesAt = maturesAt;
        this.status = Status.ACTIVE;
        this.accrued = 0.0D;
        this.lastAccrualAt = openedAt;
    }

    public String id() { return id; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public String nation() { return nation; }
    public String currencyId() { return currencyId; }
    public double principal() { return principal; }
    public Term term() { return term; }
    public double rateAnnual() { return rateAnnual; }
    public long openedAt() { return openedAt; }
    public long maturesAt() { return maturesAt; }
    public Status status() { return status; }
    public double accrued() { return accrued; }

    public boolean isDemand() { return term == Term.DEMAND; }
    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isMatured(long now) { return !isDemand() && now >= maturesAt; }

    public int termDays() {
        return switch (term) {
            case DEMAND -> 0;
            case TERM_7 -> 7;
            case TERM_30 -> 30;
            case TERM_90 -> 90;
        };
    }

    /** Начислить простые проценты за прошедшее время. demandRate — текущая ставка до востребования. */
    public void accrue(long now, double demandRate) {
        if (status != Status.ACTIVE) return;
        double rate = isDemand() ? demandRate : rateAnnual;
        long from = lastAccrualAt;
        long to = isDemand() ? now : Math.min(now, maturesAt);
        if (to <= from) {
            lastAccrualAt = isDemand() ? now : maturesAt;
            return;
        }
        double days = (to - from) / 86_400_000.0D;
        accrued += principal * rate * days / 365.0D;
        lastAccrualAt = to;
    }

    public void payOutAccrued() { accrued = 0.0D; }
    public void close() { status = Status.CLOSED; }

    /** Штраф досрочного закрытия срочного вклада: потеря процентов + earlyPenaltyRate от тела. */
    public double earlyPenalty(double earlyPenaltyRate) {
        if (isDemand()) return 0.0D;
        return principal * earlyPenaltyRate;
    }
}
