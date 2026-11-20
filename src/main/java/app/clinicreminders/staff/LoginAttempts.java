package app.clinicreminders.staff;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Guessing passwords: after {@link #MAX_FAILURES} wrong ones for a username within {@link #WINDOW},
 * that username is locked for {@link #LOCK}, even with the right password ({@link LoginThrottleFilter}).
 * The login form's handlers report each attempt ({@link SecurityConfig}).
 * Unknown usernames count the same, so a lock says nothing about who exists. Kept in memory: a
 * restart clears it, and each instance counts its own attempts.
 */
@Component
public class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    /** Past this many usernames, old entries are dropped first: random names must not fill memory. */
    private static final int MAX_TRACKED = 10_000;

    private record Attempts(int failures, Instant since, Instant lockedUntil) {
    }

    private final Map<String, Attempts> byUsername = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginAttempts(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String username) {
        Attempts attempts = byUsername.get(key(username));
        return attempts != null && attempts.lockedUntil() != null && clock.instant().isBefore(attempts.lockedUntil());
    }

    /** A good password: the count starts again. */
    void succeeded(String username) {
        byUsername.remove(key(username));
    }

    /** A wrong password, or an unknown username. */
    void failed(String username) {
        Instant now = clock.instant();
        if (byUsername.size() > MAX_TRACKED) {
            byUsername.values().removeIf(old -> old.since().isBefore(now.minus(WINDOW))
                    && (old.lockedUntil() == null || old.lockedUntil().isBefore(now)));
        }
        byUsername.compute(key(username), (name, before) -> {
            Attempts current = before == null || before.since().isBefore(now.minus(WINDOW)) && !isLockedAt(before, now)
                    ? new Attempts(0, now, null)
                    : before;
            int failures = current.failures() + 1;
            return new Attempts(failures, current.since(), failures >= MAX_FAILURES ? now.plus(LOCK) : current.lockedUntil());
        });
    }

    private static boolean isLockedAt(Attempts attempts, Instant now) {
        return attempts.lockedUntil() != null && now.isBefore(attempts.lockedUntil());
    }

    private static String key(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
