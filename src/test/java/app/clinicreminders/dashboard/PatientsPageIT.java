package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.Reminder;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Patients, now the React table: the old address leads there, and what the old page did (search as
 * typed, deleting on request) goes through the JSON API. The table itself: PatientsApiIT.
 */
class PatientsPageIT extends DashboardTest {

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    PatientRepository patients;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ReminderRepository reminders;

    private Clinic clinic;
    private Patient rana;
    private StaffPrincipal staff;

    @BeforeEach
    void clinicWithPatients() {
        clinic = data.connectedClinic("A", "pn-a");
        rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.patient(clinic, "Karim", "+9613111222", Language.EN);
        Clinic other = data.clinic("B");
        data.patient(other, "Rania Elsewhere", "+96170123457", Language.AR);
        staff = secretaryOf(clinic);
    }

    @Test
    void theOldAddressOpensTheTableWithItsSearch() {
        assertThat(get(staff, "/patients")).hasRedirectedUrl("/app/patients");
        assertThat(get(staff, "/patients?q=70 123")).hasRedirectedUrl("/app/patients?q=70%20123");
        assertThat(get(staff, "/")).bodyText().as("the classic sidebar too").contains("href=\"/app/patients\"");
    }

    @Test
    void searchesByNameOrByPhoneAsTyped() {
        assertThat(get(staff, "/api/patients?q=ran")).bodyJson().extractingPath("$.patients[*].name").asArray()
                .containsExactly("Rana Haddad");
        assertThat(get(staff, "/api/patients?q=70 123")).bodyJson().extractingPath("$.patients[*].name").asArray()
                .as("spaces in the number").containsExactly("Rana Haddad");
        assertThat(get(staff, "/api/patients?q=03 111")).bodyJson().extractingPath("$.patients[*].name").asArray()
                .as("the leading 0 of a local number").containsExactly("Karim");
    }

    @Test
    void theNextAppointmentIsTheNextActiveOne() {
        Doctor doctor = data.doctor(clinic, "Dr. Khoury", 30);
        // Wednesday 21 October 10:30 in Beirut; a cancelled and a past one do not count.
        data.appointment(doctor, rana, Instant.parse("2026-10-21T07:30:00Z"), Duration.ofMinutes(30));
        data.appointment(doctor, rana, Instant.parse("2026-10-28T07:30:00Z"), Duration.ofMinutes(30));
        data.appointment(doctor, rana, Instant.parse("2026-10-01T07:30:00Z"), Duration.ofMinutes(30));
        Patient karim = patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), "+9613111222").getFirst();
        data.cancelledAppointment(doctor, karim, Instant.parse("2026-10-22T07:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/api/patients")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.patients[0].name").isEqualTo("Karim");
            json.assertThat().extractingPath("$.patients[0].next").isNull();
            json.assertThat().extractingPath("$.patients[1].next.label").isEqualTo("Wed 21 Oct, 10:30 AM");
            json.assertThat().extractingPath("$.patients[1].next.doctor").isEqualTo("Dr. Khoury");
        });
    }

    @Test
    void deletingAPatientCancelsTheFutureKeepsThePastAndForgetsTheConversation() {
        Doctor doctor = data.doctor(clinic, 30);
        Appointment past = data.appointment(doctor, rana, Instant.parse("2026-10-01T07:00:00Z"), Duration.ofMinutes(30));
        Appointment future = appointmentService.book(clinic.getId(), doctor.getId(), rana.getId(),
                Instant.parse("2026-11-03T08:30:00Z"), AppointmentSource.WHATSAPP);
        conversations.save(new Conversation(clinic.getId(), rana.getPhoneE164(), ConversationState.MENU,
                Instant.parse("2026-10-14T09:30:00Z")));

        assertThat(mvc.delete().uri("/api/patients/{id}", rana.getId()).with(user(staff)).with(csrf())).hasStatus(204);

        assertThat(patients.findByIdAndClinicId(rana.getId(), clinic.getId())).isEmpty();
        Appointment cancelled = appointments.findByIdAndClinicId(future.getId(), clinic.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getPatientId()).isNull();
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), future.getId()))
                .extracting(Reminder::getStatus).doesNotContain(ReminderStatus.PENDING);
        Map<String, Object> kept = jdbc.queryForMap("select status, patient_id from appointment where id = ?", past.getId());
        assertThat(kept.get("status")).isEqualTo("BOOKED");
        assertThat(kept.get("patient_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isZero();
    }

    @Test
    void anotherClinicsPatientCannotBeDeleted() {
        Patient theirs = patients.findByClinicIdAndPhoneE164OrderById(
                jdbc.queryForObject("select id from clinic where name = 'B'", Long.class), "+96170123457").getFirst();

        assertThat(mvc.delete().uri("/api/patients/{id}", theirs.getId()).with(user(staff)).with(csrf())).hasStatus(404);
        assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isEqualTo(3);
    }
}
