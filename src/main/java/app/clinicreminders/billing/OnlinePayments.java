package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.billing.Billing.Quote;
import app.clinicreminders.billing.PaymentGateway.Checkout;
import app.clinicreminders.billing.PaymentGateway.Result;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.AppProperties;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.ops.AlertProperties;
import app.clinicreminders.ops.OperatorAlerts;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A clinic owner paying the subscription online (SPEC section 5.7). Pressing Pay creates a pending
 * attempt for the quote of that moment and sends the owner to the provider's page. The attempt is
 * settled by asking the provider how it stands, when the owner comes back and, as a safety net, by
 * {@link #sweep} every minute (an owner who closes the browser after paying). A paid attempt is
 * recorded once, as an ordinary payment through {@link Billing#record}, and the operator is told.
 */
@Service
public class OnlinePayments {

    private static final Logger log = LoggerFactory.getLogger(OnlinePayments.class);

    /** Who a payment made online is recorded by: how the operator's pages tell it from one entered by hand. */
    public static final String RECORDED_BY = "whish";

    /** An attempt not paid by then is abandoned. */
    static final Duration EXPIRE_AFTER = Duration.ofHours(24);

    /** The sweep leaves attempts younger than this to the owner's return. */
    static final Duration SWEEP_AFTER = Duration.ofMinutes(1);

    /** How an attempt stands, for the page the owner comes back to. */
    public enum Outcome { PAID, PENDING, FAILED }

    /** An attempt of a clinic's, as it is stored. */
    public record Attempt(UUID id, long clinicId, int months, BigDecimal amountUsd, String status, Instant createdAt) {
    }

    private final Billing billing;
    private final @Nullable PaymentGateway gateway;
    private final ClinicSettings clinics;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AppProperties app;
    private final OperatorAlerts alerts;
    private final MessageSource messages;
    private final AlertProperties alertProperties;

    OnlinePayments(Billing billing, ObjectProvider<PaymentGateway> gateway, ClinicSettings clinics, JdbcClient jdbc,
                   TransactionTemplate transactions, AppProperties app, OperatorAlerts alerts, MessageSource messages,
                   AlertProperties alertProperties) {
        this.billing = billing;
        this.gateway = gateway.getIfAvailable();
        this.clinics = clinics;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.app = app;
        this.alerts = alerts;
        this.messages = messages;
        this.alertProperties = alertProperties;
    }

    /** Whether owners can pay online at all: a provider is configured. */
    public boolean available() {
        return gateway != null;
    }

    /**
     * Starts a payment for {@code months} months at today's quote.
     *
     * @return the provider's page to send the owner to
     * @throws IllegalStateException    when online payment is off, or the provider can't start it
     * @throws NotFoundException        when the clinic has no subscription
     * @throws IllegalArgumentException for months other than the periods on offer (1, 3, 6, 12), or nothing to pay
     */
    public URI start(long clinicId, int months, String by, Instant now) {
        PaymentGateway provider = requireGateway();
        if (!Subscription.PERIODS.contains(months)) {
            throw new IllegalArgumentException("Months must be one of " + Subscription.PERIODS);
        }
        Subscription subscription = billing.subscription(clinicId).orElseThrow(() -> new NotFoundException("Subscription", clinicId));
        Quote quote = billing.quote(subscription, months, now);
        BigDecimal amount = quote.total();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Nothing to pay");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into online_payment (id, clinic_id, provider, months, amount_usd, status, created_by, created_at)
                        values (:id, :clinicId, 'WHISH', :months, :amount, 'PENDING', :by, :now)""")
                .param("id", id).param("clinicId", clinicId).param("months", months).param("amount", amount)
                .param("by", by).param("now", utc(now))
                .update();
        Clinic clinic = clinics.get(clinicId);
        URI payUrl;
        try {
            payUrl = provider.checkout(new Checkout(id, amount, "Dorak · " + clinic.getName() + " · " + months
                    + (months == 1 ? " month" : " months"), URI.create(app.link("/billing/whish/return/" + id))));
        } catch (RuntimeException e) {
            log.error("Whish could not start payment {} ({})", id, e.getClass().getSimpleName());
            setStatus(id, "FAILED", now);
            throw new IllegalStateException("The provider could not start the payment", e);
        }
        jdbc.sql("update online_payment set pay_url = :url where id = :id").param("url", payUrl.toString()).param("id", id).update();
        return payUrl;
    }

    /** The clinic's own attempt; another clinic's id is not found. */
    public Optional<Attempt> find(long clinicId, UUID id) {
        return jdbc.sql("""
                        select id, clinic_id, months, amount_usd, status, created_at from online_payment
                        where id = :id and clinic_id = :clinicId""")
                .param("id", id).param("clinicId", clinicId)
                .query((row, n) -> new Attempt(row.getObject("id", UUID.class), row.getLong("clinic_id"), row.getInt("months"),
                        row.getBigDecimal("amount_usd"), row.getString("status"), row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Asks the provider how the attempt stands and, if it was paid, records the payment, once. Safe to
     * call any number of times, also at the same moment (the owner's return and the sweep).
     */
    public Outcome settle(UUID id, Instant now) {
        PaymentGateway provider = requireGateway();
        Optional<String> before = statusOf(id);
        if (before.isEmpty()) {
            throw new NotFoundException("Online payment", id);
        }
        if (!before.get().equals("PENDING")) {
            return outcome(before.get());
        }
        Result result = provider.status(id);
        Settled settled = transactions.execute(tx -> settleLocked(id, result, now));
        if (settled != null && settled.paid() != null) {
            alerts.send(paidAlert(settled.paid()));
        }
        return settled == null ? Outcome.PENDING : settled.outcome();
    }

    /** Settles the attempts the owners did not come back for, and gives up on the abandoned ones. */
    public void sweep(Instant now) {
        if (gateway == null) {
            return;
        }
        List<UUID> pending = jdbc.sql("""
                        select id from online_payment where status = 'PENDING' and created_at < :before
                        order by created_at limit 50""")
                .param("before", utc(now.minus(SWEEP_AFTER)))
                .query(UUID.class).list();
        for (UUID id : pending) {
            try {
                settle(id, now);
            } catch (RuntimeException e) {
                log.warn("Could not settle online payment {} ({}); trying again next time", id, e.getClass().getSimpleName());
            }
        }
    }

    private record Paid(Attempt attempt, String clinic) {
    }

    private record Settled(Outcome outcome, @Nullable Paid paid) {
    }

    private Settled settleLocked(UUID id, Result result, Instant now) {
        Attempt attempt = jdbc.sql("""
                        select id, clinic_id, months, amount_usd, status, created_at from online_payment
                        where id = :id for update""")
                .param("id", id)
                .query((row, n) -> new Attempt(row.getObject("id", UUID.class), row.getLong("clinic_id"), row.getInt("months"),
                        row.getBigDecimal("amount_usd"), row.getString("status"), row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
        if (!attempt.status().equals("PENDING")) {
            return new Settled(outcome(attempt.status()), null);
        }
        switch (result.status()) {
            case PAID -> {
                Clinic clinic = clinics.get(attempt.clinicId());
                // At the quote of the moment the owner pressed Pay: message costs sent since then are
                // left for the next invoice, not billed as an underpayment of this one.
                SubscriptionPayment payment = billing.record(attempt.clinicId(), attempt.months(), attempt.amountUsd(), Method.WHISH,
                        result.reference(), LocalDate.ofInstant(now, clinic.getTimezone()), RECORDED_BY, attempt.createdAt());
                jdbc.sql("""
                                update online_payment set status = 'PAID', completed_at = :now, provider_reference = :reference,
                                    subscription_payment_id = :payment
                                where id = :id""")
                        .param("now", utc(now)).param("reference", result.reference()).param("payment", payment.getId()).param("id", id)
                        .update();
                return new Settled(Outcome.PAID, new Paid(attempt, clinic.getName()));
            }
            case FAILED -> {
                setStatus(id, "FAILED", now);
                return new Settled(Outcome.FAILED, null);
            }
            default -> {
                if (!now.isBefore(attempt.createdAt().plus(EXPIRE_AFTER))) {
                    setStatus(id, "EXPIRED", now);
                    return new Settled(Outcome.FAILED, null);
                }
                return new Settled(Outcome.PENDING, null);
            }
        }
    }

    private String paidAlert(Paid paid) {
        String months = messages.getMessage("billing.months." + paid.attempt().months(), null,
                paid.attempt().months() + " months", alertProperties.language());
        return messages.getMessage("alert.whish.paid", new Object[] {serverName(), paid.clinic(),
                "$" + paid.attempt().amountUsd().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), months},
                alertProperties.language());
    }

    private String serverName() {
        return app.baseUrl().getHost() == null ? app.baseUrl().toString() : app.baseUrl().getHost();
    }

    private Optional<String> statusOf(UUID id) {
        return jdbc.sql("select status from online_payment where id = :id").param("id", id).query(String.class).optional();
    }

    private void setStatus(UUID id, String status, Instant now) {
        jdbc.sql("update online_payment set status = :status, completed_at = :now where id = :id and status = 'PENDING'")
                .param("status", status).param("now", utc(now)).param("id", id).update();
    }

    private PaymentGateway requireGateway() {
        if (gateway == null) {
            throw new IllegalStateException("Online payment is off (WHISH_CLIENT)");
        }
        return gateway;
    }

    private static Outcome outcome(String status) {
        return switch (status) {
            case "PAID" -> Outcome.PAID;
            case "PENDING" -> Outcome.PENDING;
            default -> Outcome.FAILED;
        };
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
