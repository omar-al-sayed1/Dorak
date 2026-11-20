package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * A clinic's payment for the service, as the operator recorded it: the months it covers, how its
 * amount was made up, how it was paid. Also the clinic's invoice (the public page at /i/{token}).
 */
@Entity
@Table(name = "subscription_payment")
public class SubscriptionPayment {

    /** How a clinic pays the operator. Whish and OMT are Lebanon's money-transfer apps and agents. */
    public enum Method { CASH, WHISH, OMT, BANK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    private int months;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "fee_usd")
    private BigDecimal feeUsd;

    @Column(name = "discount_usd")
    private BigDecimal discountUsd;

    @Column(name = "message_costs_usd")
    private BigDecimal messageCostsUsd;

    @Column(name = "amount_usd")
    private BigDecimal amountUsd;

    @Enumerated(EnumType.STRING)
    private Method method;

    private String reference;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "costs_from")
    private Instant costsFrom;

    @Column(name = "recorded_by")
    private String recordedBy;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    @Column(name = "invoice_token")
    private UUID invoiceToken;

    protected SubscriptionPayment() {
    }

    /**
     * @param costsFrom the subscription's uninvoiced-costs mark before this payment
     */
    SubscriptionPayment(long clinicId, Billing.Quote quote, BigDecimal amountUsd, Method method, @Nullable String reference,
                        LocalDate paidOn, Instant costsFrom, String recordedBy, Instant now) {
        this.clinicId = clinicId;
        this.months = quote.months();
        this.periodStart = quote.from();
        this.periodEnd = quote.to();
        this.feeUsd = quote.fee();
        this.discountUsd = quote.discount();
        this.messageCostsUsd = quote.messageCosts();
        this.amountUsd = amountUsd;
        this.method = method;
        this.reference = reference;
        this.paidOn = paidOn;
        this.costsFrom = costsFrom;
        this.recordedBy = recordedBy;
        this.recordedAt = now;
        this.invoiceToken = UUID.randomUUID();
    }

    /** What the invoice asked for: the fee less the discount, plus message costs. */
    public BigDecimal totalUsd() {
        return feeUsd.subtract(discountUsd).add(messageCostsUsd);
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public int getMonths() {
        return months;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BigDecimal getFeeUsd() {
        return feeUsd;
    }

    public BigDecimal getDiscountUsd() {
        return discountUsd;
    }

    public BigDecimal getMessageCostsUsd() {
        return messageCostsUsd;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public Method getMethod() {
        return method;
    }

    public @Nullable String getReference() {
        return reference;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public Instant getCostsFrom() {
        return costsFrom;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public UUID getInvoiceToken() {
        return invoiceToken;
    }
}
