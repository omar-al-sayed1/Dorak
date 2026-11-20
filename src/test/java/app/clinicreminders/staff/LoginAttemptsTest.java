package app.clinicreminders.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptsTest {

    private Instant now = Instant.parse("2026-11-15T09:00:00Z");
    private final LoginAttempts attempts = new LoginAttempts(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void fiveWrongPasswordsLockTheUsernameForFifteenMinutes() {
        for (int i = 0; i < 4; i++) {
            attempts.failed("Rana");
        }
        assertThat(attempts.isLocked("rana")).as("four: not yet").isFalse();

        attempts.failed(" RANA ");

        assertThat(attempts.isLocked("rana")).as("any case, any spaces").isTrue();
        assertThat(attempts.isLocked("karim")).isFalse();
        now = now.plus(Duration.ofMinutes(14));
        assertThat(attempts.isLocked("rana")).isTrue();
        now = now.plus(Duration.ofMinutes(1));
        assertThat(attempts.isLocked("rana")).as("the lock runs out").isFalse();
    }

    @Test
    void failuresSpreadOverMoreThanFifteenMinutesStartAgain() {
        for (int i = 0; i < 4; i++) {
            attempts.failed("rana");
        }
        now = now.plus(Duration.ofMinutes(16));

        attempts.failed("rana");

        assertThat(attempts.isLocked("rana")).isFalse();
    }
}
