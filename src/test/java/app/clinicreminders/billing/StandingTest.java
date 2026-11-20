package app.clinicreminders.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import app.clinicreminders.billing.Standing.Status;
import org.junit.jupiter.api.Test;

/** A payment is due on 1 December 2026, with 14 days of grace: paused from 16 December. */
class StandingTest {

    private static final LocalDate DUE = LocalDate.of(2026, 12, 1);

    private static Standing on(String today, boolean anyPayment) {
        return Standing.of(DUE, 14, anyPayment, LocalDate.parse(today));
    }

    @Test
    void aTrialUntilTheFirstPaymentAndActiveAfterIt() {
        assertThat(on("2026-11-01", false).status()).isEqualTo(Status.TRIAL);
        assertThat(on("2026-11-01", true).status()).isEqualTo(Status.ACTIVE);
        assertThat(on("2026-11-01", true).daysLeft()).isEqualTo(30);
        assertThat(on("2026-11-01", true).needsNotice()).isFalse();
    }

    @Test
    void dueSoonInTheLastWeekTrialOrNot() {
        assertThat(on("2026-11-23", true).status()).as("8 days ahead").isEqualTo(Status.ACTIVE);
        assertThat(on("2026-11-24", true).status()).as("7 days ahead").isEqualTo(Status.DUE_SOON);
        assertThat(on("2026-11-30", false).status()).isEqualTo(Status.DUE_SOON);
        assertThat(on("2026-11-30", true).daysLeft()).isEqualTo(1);
        assertThat(on("2026-11-30", true).needsNotice()).isTrue();
    }

    @Test
    void overdueFromTheDueDayThroughTheGraceThenPaused() {
        Standing dueDay = on("2026-12-01", true);
        assertThat(dueDay.status()).isEqualTo(Status.OVERDUE);
        assertThat(dueDay.daysOverdue()).isZero();
        assertThat(dueDay.graceLeft()).isEqualTo(15);
        assertThat(dueDay.pausesOn()).isEqualTo(LocalDate.of(2026, 12, 16));

        Standing lastDayOfGrace = on("2026-12-15", true);
        assertThat(lastDayOfGrace.status()).isEqualTo(Status.OVERDUE);
        assertThat(lastDayOfGrace.daysOverdue()).isEqualTo(14);
        assertThat(lastDayOfGrace.graceLeft()).isEqualTo(1);
        assertThat(lastDayOfGrace.paused()).isFalse();

        Standing after = on("2026-12-16", true);
        assertThat(after.status()).isEqualTo(Status.PAUSED);
        assertThat(after.paused()).isTrue();
        assertThat(after.graceLeft()).isZero();
        assertThat(on("2027-03-01", true).graceLeft()).isZero();
    }

    @Test
    void withoutGraceThePauseStartsTheDayAfterTheDueDay() {
        assertThat(Standing.of(DUE, 0, true, DUE).status()).isEqualTo(Status.OVERDUE);
        assertThat(Standing.of(DUE, 0, true, DUE.plusDays(1)).status()).isEqualTo(Status.PAUSED);
    }
}
