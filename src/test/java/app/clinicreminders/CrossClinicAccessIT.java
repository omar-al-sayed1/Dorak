package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.balance.Balances;
import app.clinicreminders.balance.Balances.PaymentEntry;
import app.clinicreminders.balance.Currency;
import app.clinicreminders.balance.Payment;
import app.clinicreminders.balance.PaymentMethod;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.DashboardTest;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.ScheduleException;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * Clinic B's staff try every clinic endpoint that takes an id with clinic A's ids, as an attacker
 * would by changing a number in the address or the request. Each must answer 404 (as if the thing
 * did not exist: nothing about A is confirmed), and A's data must be exactly as it was. The ids are
 * valid in every other way, so a 404 is the tenant check and not a missing parameter.
 */
class CrossClinicAccessIT extends DashboardTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired
    Balances balances;

    private Doctor doctorA;
    private Patient patientA;
    private Appointment appointmentA;
    private Payment paymentA;
    private ScheduleRule ruleA;
    private ScheduleException dayOffA;
    private long conversationA;
    private Doctor doctorB;
    private StaffPrincipal ownerB;
    private StaffPrincipal secretaryB;

    @BeforeEach
    void twoClinics() {
        Clinic a = data.clinic("Clinic A");
        doctorA = data.doctor(a, "Dr. A", 30);
        ruleA = data.weekly(doctorA, DayOfWeek.THURSDAY, "09:00", "12:00");
        dayOffA = data.closedOn(doctorA, LocalDate.of(2026, 10, 20));
        patientA = data.patient(a, "Rana A", "+96170123456", Language.AR);
        appointmentA = data.appointment(doctorA, patientA, NOW.plus(Duration.ofDays(1)), Duration.ofMinutes(30));
        paymentA = balances.record(a.getId(), appointmentA.getId(), new BigDecimal("30"),
                new PaymentEntry(new BigDecimal("10"), Currency.USD, PaymentMethod.CASH), NOW).orElseThrow();
        jdbc.update("insert into conversation (clinic_id, patient_phone, state, needs_human, expires_at) values (?, ?, 'MENU', true, now())",
                a.getId(), "+96170123456");
        conversationA = jdbc.queryForObject("select id from conversation", Long.class);
        staffOf(a, StaffRole.OWNER, Language.EN);

        Clinic b = data.clinic("Clinic B");
        doctorB = data.doctor(b, "Dr. B", 30);
        ownerB = staffOf(b, StaffRole.OWNER, Language.EN);
        secretaryB = secretaryOf(b);
    }

    @Test
    void theJsonApiNeverReachesAnotherClinicsRecords() {
        long appointment = appointmentA.getId();
        for (String action : new String[] {"arrived", "no-show", "cancel", "unmark"}) {
            assertThat(post(secretaryB, "/api/appointments/{id}/" + action, appointment)).as(action).hasStatus(404);
        }
        assertThat(get(secretaryB, "/api/money/appointments/{id}", appointment)).hasStatus(404);
        assertThat(json(post(secretaryB, "/api/money/appointments/{id}", appointment), "{\"fee\":99,\"payment\":null}")).hasStatus(404);
        assertThat(mvc.delete().uri("/api/money/payments/{id}", paymentA.getId()).with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.user(secretaryB)).with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.csrf())).hasStatus(404);
        assertThat(get(secretaryB, "/api/money/patients/{id}", patientA.getId())).hasStatus(404);
        assertThat(json(post(secretaryB, "/api/money/patients/{id}", patientA.getId()), "{\"kind\":\"OWES\",\"amount\":50}")).hasStatus(404);
        assertThat(get(secretaryB, "/api/patients/{id}/card", patientA.getId())).hasStatus(404);
        assertThat(mvc.delete().uri("/api/patients/{id}", patientA.getId()).with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.user(secretaryB)).with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.csrf())).hasStatus(404);
        assertThat(get(secretaryB, "/api/patients?q=Rana")).bodyText().doesNotContain("Rana A", "+96170123456");
        assertThat(get(secretaryB, "/api/today")).bodyText().doesNotContain("Dr. A", "Rana A");

        assertClinicAUntouched();
    }

    @Test
    void theDashboardPagesNeverReachAnotherClinicsRecords() {
        long appointment = appointmentA.getId();
        long doctor = doctorA.getId();
        assertThat(get(ownerB, "/appointments/{id}", appointment)).hasStatus(404);
        assertThat(get(ownerB, "/appointments/{id}/reschedule", appointment)).hasStatus(404);
        assertThat(post(ownerB, "/appointments/{id}/reschedule", appointment).param("date", "2026-10-16").param("otherTime", "10:00"))
                .hasStatus(404);
        for (String action : new String[] {"arrived", "no-show", "cancel"}) {
            assertThat(post(ownerB, "/appointments/{id}/" + action, appointment)).as(action).hasStatus(404);
        }
        assertThat(post(ownerB, "/attention/conversations/{id}/handled", conversationA)).hasStatus(404);

        assertThat(get(ownerB, "/doctors/{id}", doctor)).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}", doctor).param("displayName", "Taken over").param("active", "false")).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/hours", doctor).param("day", "MONDAY").param("start", "09:00").param("end", "10:00"))
                .hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/hours/{rule}", doctor, ruleA.getId()).param("start", "08:00").param("end", "13:00"))
                .hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/hours/copy", doctor).param("from", "THURSDAY").param("to", "FRIDAY")).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/hours/{rule}/delete", doctor, ruleA.getId())).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/days", doctor).param("date", "2026-10-21").param("closed", "true")).hasStatus(404);
        assertThat(get(ownerB, "/doctors/{id}/days/review", doctor).param("date", "2026-10-15").param("closed", "true")).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/days/{day}/delete", doctor, dayOffA.getId())).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/login", doctor).param("username", "intruder").param("password", "long-enough-pass"))
                .hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/delete", doctor)).hasStatus(404);

        // B's own doctor in the address, A's rule or day off as the inner id.
        assertThat(post(ownerB, "/doctors/{id}/hours/{rule}/delete", doctorB.getId(), ruleA.getId())).hasStatus(404);
        assertThat(post(ownerB, "/doctors/{id}/days/{day}/delete", doctorB.getId(), dayOffA.getId())).hasStatus(404);
        assertThat(get(ownerB, "/week?doctorId=" + doctor)).bodyText().doesNotContain("Dr. A", "Rana A");

        assertClinicAUntouched();
    }

    private void assertClinicAUntouched() {
        Map<String, Object> appointment = jdbc.queryForMap("select status, starts_at from appointment where id = ?", appointmentA.getId());
        assertThat(appointment.get("status")).isEqualTo("BOOKED");
        assertThat(count("select count(*) from payment where id = ?", paymentA.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount_usd from charge where appointment_id = ?", BigDecimal.class, appointmentA.getId()))
                .isEqualByComparingTo("30");
        assertThat(count("select count(*) from charge where patient_id = ?", patientA.getId())).as("no debt added").isEqualTo(1);
        assertThat(count("select count(*) from patient where id = ?", patientA.getId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("select display_name, active from doctor where id = ?", doctorA.getId()))
                .containsEntry("display_name", "Dr. A").containsEntry("active", true);
        assertThat(count("select count(*) from schedule_rule where doctor_id = ?", doctorA.getId())).as("no hours added or removed").isEqualTo(1);
        assertThat(jdbc.queryForObject("select start_time from schedule_rule where id = ?", java.sql.Time.class, ruleA.getId()))
                .hasToString("09:00:00");
        assertThat(count("select count(*) from schedule_exception where doctor_id = ?", doctorA.getId())).isEqualTo(1);
        assertThat(count("select count(*) from conversation where id = ? and needs_human", conversationA)).isEqualTo(1);
        assertThat(count("select count(*) from staff_user where username = 'intruder'", null)).isZero();
    }

    private int count(String sql, Object id) {
        return id == null ? jdbc.queryForObject(sql, Integer.class) : jdbc.queryForObject(sql, Integer.class, id);
    }

    private static MockMvcRequestBuilder json(MockMvcRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
