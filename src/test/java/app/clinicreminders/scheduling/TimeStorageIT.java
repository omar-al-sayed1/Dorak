package app.clinicreminders.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import org.junit.jupiter.api.Test;

/**
 * Times of day and dates are stored exactly as entered, and instants as the same moment in UTC,
 * whatever the server's own time zone. Saved here from a JVM in Beirut (UTC+2 or +3), where the
 * old jdbc.time_zone setting stored 15:30 as 13:30.
 */
class TimeStorageIT extends IntegrationTest {

    @Test
    void hoursDatesAndMomentsAreStoredAsTheyAreFromAServerOutsideUtc() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Beirut"));
        try {
            Clinic clinic = data.clinic("A");
            Doctor doctor = data.doctor(clinic, "Dr. Khoury", 30);
            data.weekly(doctor, DayOfWeek.MONDAY, "15:30", "17:00");
            data.closedOn(doctor, LocalDate.of(2026, 11, 20));
            data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                    Instant.parse("2026-11-20T07:30:00Z"), Duration.ofMinutes(30));
        } finally {
            TimeZone.setDefault(original);
        }

        assertThat(jdbc.queryForObject("select start_time::text || '-' || end_time::text from schedule_rule", String.class))
                .isEqualTo("15:30:00-17:00:00");
        assertThat(jdbc.queryForObject("select exception_date::text from schedule_exception", String.class))
                .isEqualTo("2026-11-20");
        assertThat(jdbc.queryForObject("select to_char(starts_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI') from appointment",
                String.class)).isEqualTo("2026-11-20 07:30");
    }
}
