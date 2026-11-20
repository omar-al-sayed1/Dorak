package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import app.clinicreminders.billing.Subscription.MessageCosts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The general plan: what every clinic pays unless its own subscription says otherwise. The
 * operator's, not a clinic's; a single row.
 */
@Entity
@Table(name = "billing_plan")
public class BillingPlan {

    static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "monthly_fee_usd")
    private BigDecimal monthlyFeeUsd;

    @Column(name = "discount_6_percent")
    private int discount6Percent;

    @Column(name = "discount_12_percent")
    private int discount12Percent;

    @Column(name = "grace_days")
    private int graceDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_costs")
    private MessageCosts messageCosts;

    protected BillingPlan() {
    }

    /** @throws IllegalArgumentException see {@link Terms#check} */
    void change(BigDecimal monthlyFeeUsd, int discount6Percent, int discount12Percent, int graceDays, MessageCosts messageCosts) {
        Terms.check(monthlyFeeUsd, discount6Percent, discount12Percent, graceDays);
        this.monthlyFeeUsd = monthlyFeeUsd.setScale(2, RoundingMode.HALF_UP);
        this.discount6Percent = discount6Percent;
        this.discount12Percent = discount12Percent;
        this.graceDays = graceDays;
        this.messageCosts = messageCosts;
    }

    public Terms terms() {
        return new Terms(monthlyFeeUsd, discount6Percent, discount12Percent, graceDays, messageCosts);
    }
}
