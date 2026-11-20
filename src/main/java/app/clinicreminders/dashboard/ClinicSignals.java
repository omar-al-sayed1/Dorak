package app.clinicreminders.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Standing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.clinic.Clinic;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the operator wants to spot among the clinics at a glance, as quick filters on the clinics
 * and billing pages: whose subscription ends soon, who has never paid, and who has gone quiet.
 * Operator area only: it reads across clinics on purpose.
 */
@Component
class ClinicSignals {

    /** No appointment booked for this long: the clinic may have stopped using the service. */
    static final Duration QUIET_AFTER = Duration.ofDays(14);

    /**
     * @param endsSoon  its payment is due within a week
     * @param neverPaid no payment recorded yet: still on its trial, or past it
     * @param quiet     no appointment booked in the last {@link #QUIET_AFTER}
     */
    record Signals(boolean endsSoon, boolean neverPaid, boolean quiet) {
    }

    /** Which quick filters are on; a clinic must match every one that is. */
    record Quick(boolean soon, boolean unpaid, boolean quiet) {

        boolean any() {
            return soon || unpaid || quiet;
        }

        boolean matches(Signals signals) {
            return (!soon || signals.endsSoon()) && (!unpaid || signals.neverPaid()) && (!quiet || signals.quiet());
        }
    }

    /** How many clinics each quick filter would show, for its card. */
    record Counts(long soon, long unpaid, long quiet) {
    }

    private final Billing billing;
    private final JdbcClient jdbc;
    private final Clock clock;

    ClinicSignals(Billing billing, JdbcClient jdbc, Clock clock) {
        this.billing = billing;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Map<Long, Signals> of(List<Clinic> clinics, Map<Long, Subscription> subscriptions) {
        Instant now = clock.instant();
        Set<Long> booking = new HashSet<>(jdbc.sql("select distinct clinic_id from appointment where created_at >= :since")
                .param("since", OffsetDateTime.ofInstant(now.minus(QUIET_AFTER), ZoneOffset.UTC)).query(Long.class).list());
        Map<Long, Signals> signals = new HashMap<>();
        for (Clinic clinic : clinics) {
            Subscription subscription = subscriptions.get(clinic.getId());
            boolean endsSoon = subscription != null && billing.standing(subscription,
                    LocalDate.ofInstant(now, clinic.getTimezone())).status() == Standing.Status.DUE_SOON;
            signals.put(clinic.getId(), new Signals(endsSoon, subscription != null && !billing.hasPaid(clinic.getId()),
                    !booking.contains(clinic.getId())));
        }
        return signals;
    }

    static Counts counts(Map<Long, Signals> signals) {
        return new Counts(signals.values().stream().filter(Signals::endsSoon).count(),
                signals.values().stream().filter(Signals::neverPaid).count(),
                signals.values().stream().filter(Signals::quiet).count());
    }
}
