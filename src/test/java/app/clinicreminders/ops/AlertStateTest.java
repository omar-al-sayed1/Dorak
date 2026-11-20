package app.clinicreminders.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import app.clinicreminders.ops.AlertState.Problem;
import org.junit.jupiter.api.Test;

/** A problem is alerted once, again every six hours while it lasts, and once more when it clears. */
class AlertStateTest {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");

    private final AlertState state = new AlertState(Duration.ofHours(6));

    private static Problem late(int count) {
        return new Problem("reminders", "late: " + count, "reminders ok");
    }

    private static Problem failing() {
        return new Problem("sends", "sends failing", "sends ok");
    }

    @Test
    void aNewProblemIsAlertedAtOnceAndNotAgainEveryMinute() {
        assertThat(state.update(List.of(late(3)), true, T0)).containsExactly("late: 3");
        assertThat(state.update(List.of(late(4)), true, T0.plusSeconds(60))).isEmpty();
        assertThat(state.update(List.of(late(5)), true, T0.plus(Duration.ofHours(5)))).isEmpty();
    }

    @Test
    void aLastingProblemIsAlertedAgainAfterSixHoursWithTheLatestFigures() {
        state.update(List.of(late(3)), true, T0);
        state.update(List.of(late(9)), true, T0.plus(Duration.ofHours(3)));

        assertThat(state.update(List.of(late(12)), true, T0.plus(Duration.ofHours(6)))).containsExactly("late: 12");
        assertThat(state.update(List.of(late(12)), true, T0.plus(Duration.ofHours(7)))).isEmpty();
    }

    @Test
    void aProblemThatClearsSaysSoOnce() {
        state.update(List.of(late(3), failing()), true, T0);

        assertThat(state.update(List.of(failing()), true, T0.plusSeconds(60))).containsExactly("reminders ok");
        assertThat(state.update(List.of(), true, T0.plusSeconds(120))).containsExactly("sends ok");
        assertThat(state.update(List.of(), true, T0.plusSeconds(180))).isEmpty();
        assertThat(state.update(List.of(late(1)), true, T0.plusSeconds(240))).as("back again: a new alert").containsExactly("late: 1");
    }

    @Test
    void whenTheCheckCouldNotSeeEverythingNothingElseCountsAsResolved() {
        state.update(List.of(late(3)), true, T0);
        Problem database = new Problem("database", "database down", "database ok");

        assertThat(state.update(List.of(database), false, T0.plusSeconds(60))).containsExactly("database down");
        assertThat(state.update(List.of(late(3)), true, T0.plusSeconds(120))).as("the reminders were never seen as fixed")
                .containsExactly("database ok");
    }
}
