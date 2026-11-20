package app.clinicreminders.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.billing.Billing.Quote;
import app.clinicreminders.billing.Standing.Status;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Subscriptions and their payments. Now is Wednesday 14 October 2026. */
class BillingIT extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 1);

    @Autowired
    Billing billing;

    private Clinic clinic;
    private Clinic other;

    @BeforeEach
    void twoClinics() {
        clinic = data.clinic("A");
        other = data.clinic("B");
    }

    private Subscription subscribe(Clinic clinic, MessageCosts costs) {
        // Its own terms: $50 a month, 10% off for 6 months at once and 20% for a year, 14 days of grace. Usually pays 6 months.
        return billing.save(clinic.getId(), 6, DUE, "+96170123456", new BigDecimal("50"), 10, 20, 14, costs, clock.instant());
    }

    @Test
    void aClinicWithoutASubscriptionIsNotBilledAndNeverPaused() {
        assertThat(billing.subscription(clinic.getId())).isEmpty();
        assertThat(billing.standing(clinic.getId(), TODAY)).isEmpty();
        assertThat(billing.paused(clinic.getId(), TODAY.plusYears(5))).isFalse();
    }

    @Test
    void everyClinicWithoutASubscriptionGetsOneOnTheGeneralPlan() {
        Subscription own = subscribe(other, MessageCosts.INCLUDED);

        billing.enrolAll(TODAY, clock.instant());
        billing.enrolAll(TODAY.plusDays(5), clock.instant());

        Subscription enrolled = billing.subscription(clinic.getId()).orElseThrow();
        assertThat(enrolled.getPaidUntil()).as("a month's trial from the day it was enrolled, once").isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(enrolled.getPeriodMonths()).isEqualTo(1);
        assertThat(enrolled.hasOwnTerms()).isFalse();
        assertThat(billing.standing(clinic.getId(), TODAY).orElseThrow().status()).isEqualTo(Status.TRIAL);
        assertThat(billing.subscription(other.getId()).orElseThrow().getPaidUntil()).as("one that has a subscription keeps it")
                .isEqualTo(own.getPaidUntil());
        assertThat(billing.all()).hasSize(2);
    }

    @Test
    void theDiscountsAreForPayingSixMonthsOrAYearAtOnce() {
        Subscription subscription = subscribe(clinic, MessageCosts.INCLUDED);

        Quote oneMonth = billing.quote(subscription, 1, clock.instant());
        assertThat(oneMonth.total()).isEqualByComparingTo("50");
        assertThat(oneMonth.discount()).isEqualByComparingTo("0");
        assertThat(oneMonth.from()).isEqualTo(DUE);
        assertThat(oneMonth.to()).isEqualTo(LocalDate.of(2026, 12, 1));

        Quote threeMonths = billing.quote(subscription, 3, clock.instant());
        assertThat(threeMonths.fee()).isEqualByComparingTo("150");
        assertThat(threeMonths.discount()).as("nothing off for 3 months").isEqualByComparingTo("0");
        assertThat(threeMonths.total()).isEqualByComparingTo("150");
        assertThat(threeMonths.to()).isEqualTo(LocalDate.of(2027, 2, 1));

        Quote sixMonths = billing.quote(subscription, 6, clock.instant());
        assertThat(sixMonths.discount()).isEqualByComparingTo("30");
        assertThat(sixMonths.total()).isEqualByComparingTo("270");
        assertThat(sixMonths.to()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(billing.quote(subscription, 12, clock.instant()).total()).as("20% off a year").isEqualByComparingTo("480");
        assertThat(billing.netMonthlyUsd(subscription)).as("it usually pays 6 months").isEqualByComparingTo("45");
    }

    @Test
    void aClinicFollowsTheGeneralPlanExceptWhereItHasItsOwnTerms() {
        billing.savePlan(new BigDecimal("40"), 5, 15, 10, MessageCosts.ON_TOP);
        Subscription plain = billing.save(clinic.getId(), 12, DUE, null, null, null, null, null, null, clock.instant());
        Subscription special = billing.save(other.getId(), 6, DUE, null, new BigDecimal("25"), null, 50, null, MessageCosts.INCLUDED,
                clock.instant());

        assertThat(plain.hasOwnTerms()).isFalse();
        assertThat(billing.terms(plain)).isEqualTo(new Terms(new BigDecimal("40.00"), 5, 15, 10, MessageCosts.ON_TOP));
        assertThat(billing.quote(plain, 6, clock.instant()).total()).isEqualByComparingTo("228");
        assertThat(billing.quote(plain, 12, clock.instant()).total()).isEqualByComparingTo("408");
        assertThat(billing.paused(clinic.getId(), DUE.plusDays(11))).as("the plan's 10 days of grace").isTrue();

        assertThat(special.hasOwnTerms()).isTrue();
        assertThat(billing.terms(special)).as("its fee, yearly discount and costs; the plan's 6-month discount and grace")
                .isEqualTo(new Terms(new BigDecimal("25.00"), 5, 50, 10, MessageCosts.INCLUDED));
        assertThat(billing.quote(special, 12, clock.instant()).total()).isEqualByComparingTo("150");

        // The plan changes: clinics on it follow at once, a clinic's own terms stay.
        billing.savePlan(new BigDecimal("60"), 0, 0, 30, MessageCosts.INCLUDED);
        assertThat(billing.quote(plain, 12, clock.instant()).total()).isEqualByComparingTo("720");
        assertThat(billing.paused(clinic.getId(), DUE.plusDays(11))).isFalse();
        assertThat(billing.quote(special, 12, clock.instant()).total()).isEqualByComparingTo("150");
        assertThat(billing.plan().monthlyFeeUsd()).isEqualByComparingTo("60");

        assertThatThrownBy(() -> billing.savePlan(new BigDecimal("60"), 0, 101, 30, MessageCosts.INCLUDED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPaymentMovesTheDueDayAndUndoingItPutsItBack() {
        subscribe(clinic, MessageCosts.INCLUDED);
        assertThat(billing.standing(clinic.getId(), TODAY).orElseThrow().status()).isEqualTo(Status.TRIAL);

        SubscriptionPayment first = billing.record(clinic.getId(), 3, null, Method.WHISH, "  W-123  ", TODAY, "omar", clock.instant());
        assertThat(first.getAmountUsd()).isEqualByComparingTo("150");
        assertThat(first.getReference()).isEqualTo("W-123");
        assertThat(first.getPeriodStart()).isEqualTo(DUE);
        assertThat(first.getPeriodEnd()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getPaidUntil()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(billing.standing(clinic.getId(), TODAY).orElseThrow().status()).isEqualTo(Status.ACTIVE);
        assertThat(billing.invoice(first.getInvoiceToken())).isPresent();

        // Less than asked was received: recorded as it is.
        SubscriptionPayment second = billing.record(clinic.getId(), 1, new BigDecimal("40"), Method.CASH, null, TODAY, "omar", clock.instant());
        assertThat(second.getAmountUsd()).isEqualByComparingTo("40");
        assertThat(second.totalUsd()).isEqualByComparingTo("50");
        assertThat(billing.payments(clinic.getId())).extracting(SubscriptionPayment::getId).containsExactly(second.getId(), first.getId());

        assertThatThrownBy(() -> billing.undo(clinic.getId(), first.getId())).as("only the latest")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> billing.undo(other.getId(), second.getId())).as("another clinic's")
                .isInstanceOf(NotFoundException.class);

        billing.undo(clinic.getId(), second.getId());
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getPaidUntil()).isEqualTo(LocalDate.of(2027, 2, 1));
        billing.undo(clinic.getId(), first.getId());
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getPaidUntil()).isEqualTo(DUE);
        assertThat(billing.payments(clinic.getId())).isEmpty();
        assertThat(billing.invoice(first.getInvoiceToken())).isEmpty();
    }

    @Test
    void messageCostsOnTopAreInvoicedOnceFromTheClinicsOwnMessages() {
        Subscription subscription = subscribe(clinic, MessageCosts.ON_TOP);
        clock.setInstant(DEFAULT_NOW.plusSeconds(3600));
        message(clinic, "SENT", "1.2345");
        message(clinic, "DELIVERED", "0.5000");
        message(clinic, "FAILED", "9.0000");
        message(other, "SENT", "7.0000");
        clock.setInstant(DEFAULT_NOW.plusSeconds(7200));

        assertThat(billing.quote(subscription, 1, clock.instant()).messageCosts()).isEqualByComparingTo("1.73");
        SubscriptionPayment payment = billing.record(clinic.getId(), 1, null, Method.OMT, null, TODAY, "omar", clock.instant());
        assertThat(payment.getMessageCostsUsd()).isEqualByComparingTo("1.73");
        assertThat(payment.getAmountUsd()).isEqualByComparingTo("51.73");

        Subscription after = billing.subscription(clinic.getId()).orElseThrow();
        assertThat(billing.quote(after, 1, clock.instant().plusSeconds(60)).messageCosts()).as("already invoiced").isEqualByComparingTo("0");

        billing.undo(clinic.getId(), payment.getId());
        Subscription undone = billing.subscription(clinic.getId()).orElseThrow();
        assertThat(billing.quote(undone, 1, clock.instant()).messageCosts()).as("owed again").isEqualByComparingTo("1.73");

        Subscription included = subscribe(other, MessageCosts.INCLUDED);
        assertThat(billing.quote(included, 1, clock.instant()).messageCosts()).isEqualByComparingTo("0");
    }

    @Test
    void pausedOnlyOnceTheGraceIsOverAndOnlyThatClinic() {
        subscribe(clinic, MessageCosts.INCLUDED);
        subscribe(other, MessageCosts.INCLUDED);
        billing.record(other.getId(), 3, null, Method.CASH, null, TODAY, "omar", clock.instant());

        assertThat(billing.paused(clinic.getId(), DUE.plusDays(14))).isFalse();
        assertThat(billing.paused(clinic.getId(), DUE.plusDays(15))).isTrue();
        assertThat(billing.paused(other.getId(), DUE.plusDays(15))).isFalse();
    }

    @Test
    void incomeByMonthCountsEveryClinicsPaymentsByTheDayTheyWerePaid() {
        subscribe(clinic, MessageCosts.INCLUDED);
        subscribe(other, MessageCosts.INCLUDED);
        billing.record(clinic.getId(), 1, null, Method.CASH, null, LocalDate.of(2026, 9, 30), "omar", clock.instant());
        billing.record(clinic.getId(), 1, null, Method.CASH, null, TODAY, "omar", clock.instant());
        billing.record(other.getId(), 3, null, Method.BANK, null, TODAY, "omar", clock.instant());

        assertThat(billing.income(YearMonth.of(2026, 10), 3)).extracting(Billing.MonthIncome::month)
                .containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(billing.income(YearMonth.of(2026, 10), 3)).extracting(month -> month.collected().intValueExact())
                .containsExactly(0, 50, 200);
    }

    @Test
    void refusesFeesPeriodsDiscountsAndGraceThatMakeNoSense() {
        Instant now = clock.instant();
        assertThatThrownBy(() -> billing.save(clinic.getId(), 1, DUE, null, new BigDecimal("-1"), null, null, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.save(clinic.getId(), 2, DUE, null, null, null, null, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.save(clinic.getId(), 1, DUE, null, null, 0, 101, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.save(clinic.getId(), 1, DUE, null, null, null, null, 91, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> billing.record(clinic.getId(), 1, null, Method.CASH, null, TODAY, "omar", now)).as("no subscription")
                .isInstanceOf(NotFoundException.class);
        assertThat(billing.subscription(clinic.getId())).isEmpty();
    }

    private void message(Clinic clinic, String status, String cost) {
        jdbc.update("insert into message_log (clinic_id, direction, kind, status, est_cost_usd, created_at) values (?, 'OUT', 'TEMPLATE', ?, ?, ?)",
                clinic.getId(), status, new BigDecimal(cost), clock.instant().atOffset(ZoneOffset.UTC));
    }
}
