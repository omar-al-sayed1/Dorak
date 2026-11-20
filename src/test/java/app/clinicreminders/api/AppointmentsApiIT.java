package app.clinicreminders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.DashboardTest;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Today's actions over the JSON API. Now is Wednesday 14 October 2026, 12:00 in Beirut. */
class AppointmentsApiIT extends DashboardTest {

    private static final Instant TEN_THIRTY = Instant.parse("2026-10-14T07:30:00Z");

    @Autowired
    AppointmentRepository appointments;

    private Clinic clinic;
    private Doctor khoury;
    private Appointment morning;
    private Appointment evening;
    private StaffPrincipal secretary;

    @BeforeEach
    void appointmentsToday() {
        clinic = data.clinic("A");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        morning = data.appointment(khoury, rana, TEN_THIRTY, Duration.ofMinutes(30));
        evening = data.appointment(khoury, rana, TEN_THIRTY.plus(Duration.ofHours(8)), Duration.ofMinutes(30));
        secretary = secretaryOf(clinic);
    }

    @Test
    void arrivedAndNoShowCanBeUndone() {
        assertThat(post(secretary, "/api/appointments/{id}/arrived", morning.getId())).hasStatus(204);
        assertThat(status(morning)).isEqualTo(AppointmentStatus.ARRIVED);

        assertThat(post(secretary, "/api/appointments/{id}/unmark", morning.getId())).hasStatus(204);
        assertThat(status(morning)).isEqualTo(AppointmentStatus.BOOKED);

        assertThat(post(secretary, "/api/appointments/{id}/no-show", morning.getId())).hasStatus(204);
        assertThat(status(morning)).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(post(secretary, "/api/appointments/{id}/unmark", morning.getId())).hasStatus(204);
        assertThat(status(morning)).isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    void actionsThatNoLongerApplyAre409() {
        assertThat(post(secretary, "/api/appointments/{id}/no-show", evening.getId())).as("not started yet").hasStatus(409);
        assertThat(post(secretary, "/api/appointments/{id}/unmark", evening.getId())).as("nothing to undo").hasStatus(409);

        assertThat(post(secretary, "/api/appointments/{id}/cancel", evening.getId())).hasStatus(204);
        assertThat(status(evening)).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(post(secretary, "/api/appointments/{id}/arrived", evening.getId())).as("cancelled").hasStatus(409);
    }

    @Test
    void aDoctorActsOnTheirOwnAppointmentsAndAClinicOnItsOwn() {
        Doctor saab = data.doctor(clinic, "Dr. Saab", 30);
        assertThat(post(doctorLoginOf(khoury), "/api/appointments/{id}/arrived", morning.getId())).hasStatus(204);
        assertThat(post(doctorLoginOf(saab), "/api/appointments/{id}/unmark", morning.getId())).hasStatus(404);
        assertThat(post(secretaryOf(data.clinic("B")), "/api/appointments/{id}/cancel", morning.getId())).hasStatus(404);
        assertThat(status(morning)).isEqualTo(AppointmentStatus.ARRIVED);
    }

    private AppointmentStatus status(Appointment appointment) {
        return appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getStatus();
    }
}
