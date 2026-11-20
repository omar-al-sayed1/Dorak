package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A clinic's billing: how many months it usually pays at once, the day its next payment is due, and
 * its own terms where they differ from the general plan ({@link BillingPlan}). One per clinic; a
 * clinic without one is not billed.
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    /** How many months one payment can cover. */
    public static final Set<Integer> PERIODS = Set.of(1, 3, 6, 12);

    /** Meta's message costs: part of the fee, or added to each invoice from the estimates. */
    public enum MessageCosts { INCLUDED, ON_TOP }

    @Id
    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "period_months")
    private int periodMonths;

    @Column(name = "paid_until")
    private LocalDate paidUntil;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "costs_from")
    private Instant costsFrom;

    // The clinic's own terms; null for "as the general plan".

    @Column(name = "monthly_fee_usd")
    private BigDecimal monthlyFeeUsd;

    @Column(name = "discount_6_percent")
    private Integer discount6Percent;

    @Column(name = "discount_12_percent")
    private Integer discount12Percent;

    @Column(name = "grace_days")
    private Integer graceDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_costs")
    private MessageCosts messageCosts;

    protected Subscription() {
    }

    Subscription(long clinicId, Instant now) {
        this.clinicId = clinicId;
        this.costsFrom = now;
    }

    /**
     * @param paidUntil the day the next payment is due
     * @throws IllegalArgumentException for a period other than 1, 3, 6 or 12 months
     */
    void change(int periodMonths, LocalDate paidUntil, @Nullable String contactPhone) {
        if (!PERIODS.contains(periodMonths)) {
            throw new IllegalArgumentException("period");
        }
        this.periodMonths = periodMonths;
        this.paidUntil = paidUntil;
        this.contactPhone = contactPhone;
    }

    /**
     * The clinic's own terms; null leaves that one to the general plan.
     *
     * @throws IllegalArgumentException see {@link Terms#check}
     */
    void changeOwnTerms(@Nullable BigDecimal monthlyFeeUsd, @Nullable Integer discount6Percent, @Nullable Integer discount12Percent,
                        @Nullable Integer graceDays, @Nullable MessageCosts messageCosts) {
        Terms.check(monthlyFeeUsd, discount6Percent, discount12Percent, graceDays);
        this.monthlyFeeUsd = monthlyFeeUsd == null ? null : monthlyFeeUsd.setScale(2, RoundingMode.HALF_UP);
        this.discount6Percent = discount6Percent;
        this.discount12Percent = discount12Percent;
        this.graceDays = graceDays;
        this.messageCosts = messageCosts;
    }

    /** A payment was recorded: the next one is due later, and message costs up to now are invoiced. */
    void paidUntil(LocalDate paidUntil, Instant costsFrom) {
        this.paidUntil = paidUntil;
        this.costsFrom = costsFrom;
    }

    /** What this clinic is billed by: the general plan, with what the clinic's own terms change. */
    public Terms terms(Terms general) {
        return new Terms(monthlyFeeUsd != null ? monthlyFeeUsd : general.monthlyFeeUsd(),
                discount6Percent != null ? discount6Percent : general.discount6Percent(),
                discount12Percent != null ? discount12Percent : general.discount12Percent(),
                graceDays != null ? graceDays : general.graceDays(),
                messageCosts != null ? messageCosts : general.messageCosts());
    }

    /** True if the clinic has terms of its own, whatever the general plan says. */
    public boolean hasOwnTerms() {
        return monthlyFeeUsd != null || discount6Percent != null || discount12Percent != null || graceDays != null
                || messageCosts != null;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public int getPeriodMonths() {
        return periodMonths;
    }

    public LocalDate getPaidUntil() {
        return paidUntil;
    }

    public @Nullable String getContactPhone() {
        return contactPhone;
    }

    public Instant getCostsFrom() {
        return costsFrom;
    }

    public @Nullable BigDecimal getOwnMonthlyFeeUsd() {
        return monthlyFeeUsd;
    }

    public @Nullable Integer getOwnDiscount6Percent() {
        return discount6Percent;
    }

    public @Nullable Integer getOwnDiscount12Percent() {
        return discount12Percent;
    }

    public @Nullable Integer getOwnGraceDays() {
        return graceDays;
    }

    public @Nullable MessageCosts getOwnMessageCosts() {
        return messageCosts;
    }
}
