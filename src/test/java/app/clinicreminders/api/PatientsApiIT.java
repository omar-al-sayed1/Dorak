package app.clinicreminders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.balance.Balances;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.DashboardTest;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The patients table over the JSON API. Now is Wednesday 14 October 2026, 12:00 in Beirut. Rana
 * (two past visits with Dr. Khoury, next on Friday with Dr. Saab) shares her number with her son
 * Sami; Karim is booked tomorrow and owes $40; Nour opted out.
 */
class PatientsApiIT extends DashboardTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Autowired
    Balances balances;

    @Autowired
    PatientRepository patientRepository;

    private Clinic clinic;
    private Doctor khoury;
    private Patient nour;
    private StaffPrincipal secretary;

    @BeforeEach
    void patients() {
        clinic = data.clinic("A");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        Doctor saab = data.doctor(clinic, "Dr. Saab", 30);
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.patient(clinic, "Sami Haddad", "+96170123456", Language.AR);
        Patient karim = data.patient(clinic, "Karim", "+96171222333", Language.EN);
        nour = data.patient(clinic, "Nour", "+96176444555", Language.AR);
        nour.optOut();
        patientRepository.save(nour);
        data.appointment(khoury, rana, NOW.minus(Duration.ofDays(13)), HALF_HOUR);
        data.appointment(khoury, rana, NOW.minus(Duration.ofDays(7)), HALF_HOUR);
        data.cancelledAppointment(khoury, rana, NOW.minus(Duration.ofDays(3)), HALF_HOUR);
        data.appointment(saab, rana, NOW.plus(Duration.ofDays(2)), HALF_HOUR);
        data.appointment(khoury, karim, NOW.plus(Duration.ofDays(1)), HALF_HOUR);
        balances.addOwed(clinic.getId(), karim.getId(), new BigDecimal("40"), NOW);
        data.patient(data.clinic("B"), "Rana Elsewhere", "+96170123456", Language.AR);
        secretary = secretaryOf(clinic);
    }

    @Test
    void everyPatientWithTheirAppointmentsMoneyAndFamily() {
        assertThat(get(secretary, "/api/patients")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.total").isEqualTo(4);
            json.assertThat().extractingPath("$.counts").isEqualTo(
                    java.util.Map.of("all", 4, "booked", 2, "unbooked", 2, "owes", 1, "family", 2, "optedOut", 1));
            json.assertThat().extractingPath("$.patients[*].name").asArray()
                    .containsExactly("Karim", "Nour", "Rana Haddad", "Sami Haddad");
            json.assertThat().extractingPath("$.patients[0].balance").isEqualTo(40.0);
            json.assertThat().extractingPath("$.patients[0].next.days").isEqualTo(1);
            json.assertThat().extractingPath("$.patients[1].optedOut").isEqualTo(true);
            json.assertThat().extractingPath("$.patients[2].visits").as("the cancelled one isn't a visit").isEqualTo(2);
            json.assertThat().extractingPath("$.patients[2].lastVisitDays").isEqualTo(7);
            json.assertThat().extractingPath("$.patients[2].next.doctor").isEqualTo("Dr. Saab");
            json.assertThat().extractingPath("$.patients[2].next.days").isEqualTo(2);
            json.assertThat().extractingPath("$.patients[2].family").asArray().containsExactly("Sami Haddad");
            json.assertThat().extractingPath("$.patients[3].lastVisit").isNull();
            json.assertThat().extractingPath("$.nextPage").isNull();
        });
    }

    @Test
    void filtersSearchAndSorting() {
        assertThat(names("/api/patients?filter=owes")).containsExactly("Karim");
        assertThat(names("/api/patients?filter=family")).containsExactly("Rana Haddad", "Sami Haddad");
        assertThat(names("/api/patients?filter=opted-out")).containsExactly("Nour");
        assertThat(names("/api/patients?filter=unbooked")).containsExactly("Nour", "Sami Haddad");
        assertThat(names("/api/patients?sort=next")).as("no appointment: last").containsExactly("Karim", "Rana Haddad", "Nour", "Sami Haddad");
        assertThat(names("/api/patients?sort=visits&dir=desc")).first().isEqualTo("Rana Haddad");
        assertThat(names("/api/patients?sort=balance&dir=desc")).first().isEqualTo("Karim");
        assertThat(names("/api/patients?sort=name&dir=desc")).containsExactly("Sami Haddad", "Rana Haddad", "Nour", "Karim");
        assertThat(names("/api/patients?q=0123")).as("by any part of the number").containsExactly("Rana Haddad", "Sami Haddad");
        assertThat(names("/api/patients?q=KAR")).containsExactly("Karim");
        assertThat(get(secretary, "/api/patients?q=haddad&filter=booked")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.total").isEqualTo(1);
            json.assertThat().extractingPath("$.counts.all").as("the counts follow the search").isEqualTo(2);
        });
        assertThat(names("/api/patients?filter=nonsense&sort=nonsense")).as("unknown: all, by name").hasSize(4);
    }

    @Test
    void aPageAtATime() {
        assertThat(get(secretary, "/api/patients?size=3")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.patients").asArray().hasSize(3);
            json.assertThat().extractingPath("$.nextPage").isEqualTo(1);
        });
        assertThat(get(secretary, "/api/patients?size=3&page=1")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.patients[*].name").asArray().containsExactly("Sami Haddad");
            json.assertThat().extractingPath("$.nextPage").isNull();
        });
    }

    @Test
    void aDoctorSeesTheirOwnPatientsWithoutMoneyOrFamilies() {
        StaffPrincipal doctor = doctorLoginOf(khoury);

        assertThat(get(doctor, "/api/patients")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.patients[*].name").asArray().containsExactly("Karim", "Rana Haddad");
            json.assertThat().extractingPath("$.patients[0].balance").isNull();
            json.assertThat().extractingPath("$.patients[1].next").as("her next is with Dr. Saab").isNull();
            json.assertThat().extractingPath("$.patients[1].family").asArray().isEmpty();
            json.assertThat().extractingPath("$.counts.owes").isEqualTo(0);
            json.assertThat().extractingPath("$.counts.family").isEqualTo(0);
        });
        assertThat(get(doctor, "/api/patients?filter=owes")).bodyJson().extractingPath("$.total").isEqualTo(2);
        assertThat(mvc.delete().uri("/api/patients/{id}", nour.getId()).with(user(doctor)).with(csrf())).hasStatus(403);
    }

    @Test
    void eachClinicItsOwnPatientsAndDeletingOnRequest() {
        StaffPrincipal elsewhere = secretaryOf(data.clinic("C"));
        assertThat(get(elsewhere, "/api/patients")).bodyJson().extractingPath("$.total").isEqualTo(0);
        assertThat(mvc.delete().uri("/api/patients/{id}", nour.getId()).with(user(elsewhere)).with(csrf())).hasStatus(404);

        assertThat(mvc.delete().uri("/api/patients/{id}", nour.getId()).with(user(secretary)).with(csrf())).hasStatus(204);

        assertThat(names("/api/patients")).containsExactly("Karim", "Rana Haddad", "Sami Haddad");
    }

    private java.util.List<Object> names(String uri) {
        java.util.List<Object> names = new java.util.ArrayList<>();
        assertThat(get(secretary, uri)).hasStatusOk().bodyJson().extractingPath("$.patients[*].name").asArray()
                .satisfies(array -> names.addAll(java.util.Arrays.asList(array)));
        return names;
    }
}
