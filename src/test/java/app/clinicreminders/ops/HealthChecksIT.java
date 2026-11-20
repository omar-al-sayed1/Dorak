package app.clinicreminders.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.AppProperties;
import app.clinicreminders.ops.AlertState.Problem;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The operator's checks against the database. The app started at {@link #STARTED}; now is ten minutes later unless a test says otherwise. */
class HealthChecksIT extends IntegrationTest {

    private static final Instant STARTED = Instant.parse("2026-11-20T08:00:00Z");
    private static final Instant NOW = STARTED.plus(Duration.ofMinutes(10));

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MessageSource messages;

    @Autowired
    AppProperties app;

    @Autowired
    AlertProperties properties;

    private HealthChecks checks;
    private Clinic clinic;
    private Doctor doctor;
    private Patient patient;
    private int appointments;

    @BeforeEach
    void aClinicWithAnAppointment() {
        jdbc.update("delete from ops_heartbeat");
        checks = new HealthChecks(jdbcClient, properties, messages, app, Clock.fixed(STARTED, ZoneOffset.UTC));
        clinic = data.clinic("Clinique Zahle");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        patient = data.patient(clinic);
    }

    @Test
    void allQuietIsNoProblem() {
        reminder("PENDING", NOW.plus(Duration.ofHours(2)));
        reminder("SENT", NOW.minus(Duration.ofHours(1)));
        jdbc.update("insert into webhook_inbox (payload, received_at) values ('{}', ?)", utc(NOW.minusSeconds(30)));

        HealthChecks.Result result = checks.check(NOW);

        assertThat(result.problems()).isEmpty();
        assertThat(result.complete()).isTrue();
    }

    @Test
    void remindersStillPendingTenMinutesAfterTheirTimeMeanTheSchedulerStalled() {
        reminder("PENDING", NOW.minus(Duration.ofMinutes(9)));
        assertThat(checks.check(NOW).problems()).as("within the margin").isEmpty();

        reminder("PENDING", NOW.minus(Duration.ofMinutes(25)));
        reminder("PENDING", NOW.minus(Duration.ofMinutes(12)));

        assertThat(checks.check(NOW).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.key()).isEqualTo("reminders");
            assertThat(problem.alert()).isEqualTo("""
                    ⚠️ Dorak (reminders.test)
                    Reminders are late: 2 still waiting, the oldest due 25 minutes ago. The reminder scheduler may have stalled.""");
            assertThat(problem.resolved()).isEqualTo("✅ Dorak (reminders.test)\nReminders are going out again.");
        });
    }

    @Test
    void webhookDeliveriesLeftUnprocessedAreAProblemButOnesGivenUpOnAreNot() {
        jdbc.update("insert into webhook_inbox (payload, received_at) values ('{}', ?)", utc(NOW.minus(Duration.ofMinutes(15))));
        jdbc.update("insert into webhook_inbox (payload, received_at, failed) values ('{}', ?, true)", utc(NOW.minus(Duration.ofHours(3))));

        assertThat(checks.check(NOW).problems()).extracting(Problem::key).containsExactly("inbox");
        assertThat(checks.check(NOW).problems().getFirst().alert()).contains("1 waiting, the oldest received 15 minutes ago");
    }

    @Test
    void sendsAreFailingWhenAtLeastThreeAndHalfOfThemFailed() {
        Clinic other = data.clinic("Dr. Haddad");
        sent(clinic, "FAILED", 2);
        sent(clinic, "SENT", 5);
        assertThat(checks.check(NOW).problems()).as("two failures: one patient's number, not a problem").isEmpty();

        sent(other, "FAILED", 4);
        assertThat(checks.check(NOW).problems()).as("6 of 11 failed").singleElement()
                .extracting(Problem::alert).asString()
                .contains("WhatsApp sends are failing: 6 of 11 in the last 15 minutes. Most failures: Dr. Haddad (4), Clinique Zahle (2).");

        sent(clinic, "DELIVERED", 3);
        assertThat(checks.check(NOW).problems()).as("6 of 14: fewer than half failed").isEmpty();
    }

    @Test
    void oldFailuresAndIncomingMessagesDoNotCount() {
        jdbc.update("""
                        insert into message_log (clinic_id, direction, kind, status, created_at)
                        select ?, 'OUT', 'TEMPLATE', 'FAILED', ? from generate_series(1, 5)""",
                clinic.getId(), utc(NOW.minus(Duration.ofMinutes(16))));
        jdbc.update("""
                        insert into message_log (clinic_id, direction, kind, status, created_at)
                        select ?, 'IN', 'TEXT', 'FAILED', ? from generate_series(1, 5)""",
                clinic.getId(), utc(NOW.minusSeconds(60)));

        assertThat(checks.check(NOW).problems()).isEmpty();
    }

    @Test
    void noBackupForOverADayIsAProblemCountedFromTheLastOneOrFromStart() {
        Instant tomorrow = STARTED.plus(Duration.ofHours(27));
        assertThat(checks.check(STARTED.plus(Duration.ofHours(25))).problems()).as("a new server: not yet").isEmpty();
        assertThat(checks.check(tomorrow).problems()).singleElement().extracting(Problem::alert).asString()
                .contains("No database backup since the app started 27 hours ago.");

        jdbc.update("insert into ops_heartbeat (name, at) values ('backup', ?)", utc(tomorrow.minus(Duration.ofHours(2))));
        assertThat(checks.check(tomorrow).problems()).as("the backup ran last night").isEmpty();

        assertThat(checks.check(tomorrow.plus(Duration.ofHours(25))).problems()).singleElement().satisfies(problem -> {
            assertThat(problem.key()).isEqualTo("backup");
            assertThat(problem.alert()).contains("The last database backup is 27 hours old.");
        });
    }

    @Test
    void backupsAreNotWatchedWhenTheCheckIsOff() {
        AlertProperties off = new AlertProperties(null, null, null, null, null, 0, Duration.ZERO, null);
        HealthChecks noBackups = new HealthChecks(jdbcClient, off, messages, app, Clock.fixed(STARTED, ZoneOffset.UTC));

        assertThat(noBackups.check(STARTED.plus(Duration.ofDays(30))).problems()).isEmpty();
    }

    @Test
    void alertsCanBeInArabicWithWesternDigits() {
        AlertProperties arabic = new AlertProperties(null, null, null, null, null, 0, null, Locale.forLanguageTag("ar"));
        HealthChecks inArabic = new HealthChecks(jdbcClient, arabic, messages, app, Clock.fixed(STARTED, ZoneOffset.UTC));
        reminder("PENDING", NOW.minus(Duration.ofMinutes(25)));

        assertThat(inArabic.check(NOW).problems().getFirst().alert()).contains("دورك (reminders.test)", "التذكيرات متأخرة: 1", "قبل 25 دقيقة");
    }

    /** A reminder for an appointment of its own (one pending reminder per appointment and kind). */
    private void reminder(String status, Instant due) {
        Appointment visit = data.appointment(doctor, patient, NOW.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(30L * appointments++)),
                Duration.ofMinutes(30));
        jdbc.update("insert into reminder (clinic_id, appointment_id, kind, due_at, status) values (?, ?, 'H24', ?, ?)",
                clinic.getId(), visit.getId(), utc(due), status);
    }

    private void sent(Clinic to, String status, int count) {
        jdbc.update("""
                        insert into message_log (clinic_id, direction, kind, status, created_at)
                        select ?, 'OUT', 'TEMPLATE', ?, ? from generate_series(1, ?)""",
                to.getId(), status, utc(NOW.minus(Duration.ofMinutes(5))), count);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
