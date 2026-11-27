package app.clinicreminders.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.billing.OnlinePayments.Outcome;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.clinic.Clinic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Owners paying online, against the fake Whish. Now is Wednesday 14 October 2026; the clinic pays
 * $50 a month on its own terms (10% off 6 months, 20% off a year), next due on 1 November 2026.
 */
class OnlinePaymentsIT extends IntegrationTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 1);

    @Autowired
    OnlinePayments payments;

    @Autowired
    FakeWhish whish;

    @Autowired
    Billing billing;

    private Clinic clinic;

    @BeforeEach
    void aBilledClinic() {
        clinic = data.clinic("Clinique Zahle");
        subscribe(clinic, MessageCosts.INCLUDED);
    }

    private void subscribe(Clinic to, MessageCosts costs) {
        billing.save(to.getId(), 3, DUE, null, new BigDecimal("50"), 10, 20, 14, costs, clock.instant());
    }

    private UUID start(int months) {
        URI page = payments.start(clinic.getId(), months, "owner", clock.instant());
        return UUID.fromString(page.getPath().substring(page.getPath().lastIndexOf('/') + 1));
    }

    @Test
    void startingAPaymentKeepsItPendingAtTodaysQuoteAndSendsTheOwnerToWhish() {
        URI page = payments.start(clinic.getId(), 6, "owner", clock.instant());
        UUID id = UUID.fromString(page.getPath().substring("/dev/whish/".length()));

        assertThat(page.toString()).isEqualTo("https://reminders.test/dev/whish/" + id);
        assertThat(whish.checkoutOf(id)).hasValueSatisfying(checkout -> {
            assertThat(checkout.amountUsd()).as("6 months at $50, 10% off").isEqualByComparingTo("270");
            assertThat(checkout.description()).isEqualTo("Dorak · Clinique Zahle · 6 months");
            assertThat(checkout.returnUrl()).hasToString("https://reminders.test/billing/whish/return/" + id);
        });
        assertThat(payments.find(clinic.getId(), id)).hasValueSatisfying(attempt -> {
            assertThat(attempt.status()).isEqualTo("PENDING");
            assertThat(attempt.amountUsd()).isEqualByComparingTo("270");
        });
        assertThat(billing.payments(clinic.getId())).as("nothing recorded until Whish says it is paid").isEmpty();
    }

    @Test
    void aPaidPaymentIsRecordedOnceAndTheOperatorIsTold() {
        UUID id = start(3);
        whish.pay(id);
        clock.setInstant(clock.instant().plusSeconds(90));

        assertThat(payments.settle(id, clock.instant())).isEqualTo(Outcome.PAID);
        assertThat(payments.settle(id, clock.instant())).as("again: still paid, nothing more").isEqualTo(Outcome.PAID);

        assertThat(billing.payments(clinic.getId())).singleElement().satisfies(payment -> {
            assertThat(payment.getAmountUsd()).isEqualByComparingTo("150");
            assertThat(payment.totalUsd()).isEqualByComparingTo("150");
            assertThat(payment.getMethod()).isEqualTo(Method.WHISH);
            assertThat(payment.getReference()).startsWith("FAKE-");
            assertThat(payment.getRecordedBy()).isEqualTo("whish");
            assertThat(payment.getPaidOn()).isEqualTo(LocalDate.of(2026, 10, 14));
        });
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getPaidUntil()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(alerts.sent()).containsExactly("""
                💰 Dorak (reminders.test)
                Clinique Zahle paid $150.00 with Whish (3 months).""");
    }

    @Test
    void theOwnersReturnAndTheSweepAtTheSameMomentRecordItOnce() throws Exception {
        UUID id = start(3);
        whish.pay(id);
        Callable<Outcome> settle = () -> payments.settle(id, clock.instant());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Outcome>> outcomes = pool.invokeAll(List.of(settle, settle, settle, settle));
            for (Future<Outcome> outcome : outcomes) {
                assertThat(outcome.get()).isEqualTo(Outcome.PAID);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(billing.payments(clinic.getId())).hasSize(1);
        assertThat(alerts.sent()).hasSize(1);
    }

    @Test
    void notPaidYetStaysPendingAndACancelledPaymentRecordsNothing() {
        UUID id = start(1);
        assertThat(payments.settle(id, clock.instant())).isEqualTo(Outcome.PENDING);

        whish.decline(id);
        assertThat(payments.settle(id, clock.instant())).isEqualTo(Outcome.FAILED);
        whish.pay(id);
        assertThat(payments.settle(id, clock.instant())).as("failed is final").isEqualTo(Outcome.FAILED);

        assertThat(billing.payments(clinic.getId())).isEmpty();
        assertThat(alerts.sent()).isEmpty();
    }

    @Test
    void anAttemptNotPaidWithinADayIsAbandoned() {
        UUID id = start(1);

        assertThat(payments.settle(id, clock.instant().plus(Duration.ofHours(23)))).isEqualTo(Outcome.PENDING);
        assertThat(payments.settle(id, clock.instant().plus(Duration.ofHours(24)))).isEqualTo(Outcome.FAILED);
        assertThat(payments.find(clinic.getId(), id).orElseThrow().status()).isEqualTo("EXPIRED");
    }

    @Test
    void theSweepRecordsPaymentsTheOwnerNeverCameBackFor() {
        UUID paid = start(1);
        UUID unpaid = start(12);
        whish.pay(paid);

        payments.sweep(clock.instant().plusSeconds(30));
        assertThat(billing.payments(clinic.getId())).as("too soon: left to the owner's return").isEmpty();

        payments.sweep(clock.instant().plusSeconds(90));
        assertThat(billing.payments(clinic.getId())).singleElement()
                .satisfies(payment -> assertThat(payment.getMonths()).isEqualTo(1));
        assertThat(payments.find(clinic.getId(), unpaid).orElseThrow().status()).isEqualTo("PENDING");

        payments.sweep(clock.instant().plus(Duration.ofDays(2)));
        assertThat(payments.find(clinic.getId(), unpaid).orElseThrow().status()).isEqualTo("EXPIRED");
    }

    @Test
    void messagesSentAfterPressingPayAreLeftForTheNextInvoice() {
        Clinic onTop = data.clinic("Dr. Haddad");
        subscribe(onTop, MessageCosts.ON_TOP);
        message(onTop, "2.00");
        clock.setInstant(clock.instant().plusSeconds(60));
        URI page = payments.start(onTop.getId(), 1, "owner", clock.instant());
        UUID id = UUID.fromString(page.getPath().substring("/dev/whish/".length()));
        assertThat(whish.checkoutOf(id).orElseThrow().amountUsd()).as("$50 and the $2 of messages so far").isEqualByComparingTo("52");

        clock.setInstant(clock.instant().plusSeconds(60));
        message(onTop, "5.00");
        whish.pay(id);
        payments.settle(id, clock.instant().plusSeconds(60));

        SubscriptionPayment payment = billing.payments(onTop.getId()).getFirst();
        assertThat(payment.getAmountUsd()).isEqualByComparingTo("52");
        assertThat(payment.totalUsd()).as("paid in full: not a $5 underpayment").isEqualByComparingTo("52");
        Subscription after = billing.subscription(onTop.getId()).orElseThrow();
        assertThat(billing.quote(after, 1, clock.instant().plusSeconds(120)).messageCosts()).as("on the next invoice")
                .isEqualByComparingTo("5");
    }

    @Test
    void onlyTheClinicsOwnPaymentIsFoundAndNothingToPayIsRefused() {
        UUID id = start(1);
        Clinic other = data.clinic("Other");
        billing.save(other.getId(), 1, DUE, null, BigDecimal.ZERO, null, null, null, null, clock.instant());

        assertThat(payments.find(other.getId(), id)).isEmpty();
        assertThatThrownBy(() -> payments.start(clinic.getId(), 2, "owner", clock.instant())).as("not a period on offer")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payments.start(other.getId(), 1, "owner", clock.instant())).isInstanceOf(IllegalArgumentException.class);
        Clinic unbilled = data.clinic("Unbilled");
        jdbc.update("delete from subscription where clinic_id = ?", unbilled.getId());
        assertThatThrownBy(() -> payments.start(unbilled.getId(), 1, "owner", clock.instant()))
                .isInstanceOf(app.clinicreminders.common.NotFoundException.class);
    }

    private void message(Clinic to, String cost) {
        jdbc.update("insert into message_log (clinic_id, direction, kind, status, est_cost_usd, created_at) values (?, 'OUT', 'TEMPLATE', 'SENT', ?, ?)",
                to.getId(), new BigDecimal(cost), clock.instant().atOffset(ZoneOffset.UTC));
    }
}
