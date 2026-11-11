package app.clinicreminders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.DashboardTest;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The JSON API the React dashboard uses, and the app itself at /app/. Now is Wednesday 14 October
 * 2026, 12:00 in Beirut.
 */
class ApiIT extends DashboardTest {

    private static final Instant TEN_THIRTY = Instant.parse("2026-10-14T07:30:00Z");

    @Autowired
    ConversationRepository conversations;

    @Autowired
    StaffUserRepository users;

    private Clinic clinic;
    private Doctor khoury;
    private Doctor saab;
    private StaffPrincipal secretary;

    @BeforeEach
    void aClinicWithAppointmentsToday() {
        clinic = data.connectedClinic("Dr. Demo", "pn-a");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        saab = data.doctor(clinic, "Dr. Saab", 30);
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.appointment(khoury, rana, TEN_THIRTY, Duration.ofMinutes(30));
        data.appointment(saab, data.patient(clinic, "Karim", "+96171222333", Language.EN), TEN_THIRTY, Duration.ofMinutes(30));
        Conversation waiting = new Conversation(clinic.getId(), "+96170000009", ConversationState.MENU, TEN_THIRTY);
        waiting.flagForHuman();
        conversations.save(waiting);
        secretary = secretaryOf(clinic);
    }

    @Test
    void theSessionSaysWhoIsLoggedInWithTheCsrfToken() {
        assertThat(get(secretary, "/api/session")).hasStatusOk().bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.role").isEqualTo("SECRETARY");
                    json.assertThat().extractingPath("$.clinicName").isEqualTo("Dr. Demo");
                    json.assertThat().extractingPath("$.doctorId").isNull();
                    json.assertThat().extractingPath("$.language").isEqualTo("en");
                    json.assertThat().extractingPath("$.attentionCount").isEqualTo(1);
                    json.assertThat().extractingPath("$.csrf.headerName").isEqualTo("X-CSRF-TOKEN");
                    json.assertThat().extractingPath("$.csrf.token").asString().isNotBlank();
                });
        assertThat(get(doctorLoginOf(khoury), "/api/session")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.doctorId").isEqualTo(khoury.getId().intValue());
            json.assertThat().extractingPath("$.attentionCount").as("Needs attention is the front desk's").isNull();
        });
        StaffPrincipal operator = StaffPrincipal.of(users.save(StaffUser.operator("op.api", "{noop}x", Language.EN)));
        assertThat(get(operator, "/api/session")).bodyJson().extractingPath("$.clinicId").isNull();
    }

    @Test
    void withoutASessionTheApiAnswers401NotTheLoginPage() {
        assertThat(mvc.get().uri("/api/session").accept(MediaType.APPLICATION_JSON)).hasStatus(401);
        assertThat(mvc.get().uri("/api/today").accept(MediaType.APPLICATION_JSON)).hasStatus(401);
    }

    @Test
    void theTextsInEitherLanguageForAnyone() {
        assertThat(mvc.get().uri("/api/messages?lang=ar")).hasStatusOk().bodyJson()
                .extractingPath("$['nav.today']").isEqualTo("اليوم");
        assertThat(mvc.get().uri("/api/messages?lang=en")).bodyJson().extractingPath("$['nav.today']").isEqualTo("Today");
    }

    @Test
    void todayHasTheClinicsDoctorsOnly() {
        Clinic other = data.clinic("B");
        data.appointment(data.doctor(other, "Dr. Elsewhere", 30), data.patient(other, "Nour", "+96103456789", Language.AR),
                TEN_THIRTY, Duration.ofMinutes(30));

        assertThat(get(secretary, "/api/today")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.date").isEqualTo("2026-10-14");
            json.assertThat().extractingPath("$.dateLabel").isEqualTo("Wednesday 14 October 2026");
            json.assertThat().extractingPath("$.today").isEqualTo(true);
            json.assertThat().extractingPath("$.stats.total").isEqualTo(2);
            json.assertThat().extractingPath("$.doctors[*].name").asArray().containsExactly("Dr. Khoury", "Dr. Saab");
            json.assertThat().extractingPath("$.doctors[0].rows[0].patientName").isEqualTo("Rana Haddad");
            json.assertThat().extractingPath("$.doctors[0].rows[0].time").isEqualTo("10:30 AM");
            json.assertThat().extractingPath("$.doctors[0].nowIndex").as("10:30 is over at 12:00").isEqualTo(1);
        });
        assertThat(get(secretary, "/api/today?date=2026-10-15")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.relativeDayKey").isEqualTo("today.relative.tomorrow");
            json.assertThat().extractingPath("$.stats.total").isEqualTo(0);
        });
    }

    @Test
    void aDoctorsTodayIsTheirOwnAndTheOperatorHasNone() {
        assertThat(get(doctorLoginOf(saab), "/api/today")).bodyJson()
                .extractingPath("$.doctors[*].name").asArray().containsExactly("Dr. Saab");
        StaffPrincipal operator = StaffPrincipal.of(users.save(StaffUser.operator("op.today", "{noop}x", Language.EN)));
        assertThat(get(operator, "/api/today")).hasStatus(403);
    }

    @Test
    void switchingTheLanguageNeedsTheCsrfTokenAndIsRemembered() {
        assertThat(mvc.put().uri("/api/language").with(user(secretary)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"ar\"}")).hasStatus(403);

        assertThat(mvc.put().uri("/api/language").with(user(secretary)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"ar\"}")).hasStatusOk();

        assertThat(jdbc.queryForObject("select language from staff_user where username = ?", String.class,
                secretary.username())).isEqualTo("ar");
    }

    @Test
    void theReactAppIsServedAtAppForEveryPageAddress() {
        // MockMvc doesn't follow a forward, as a server does: the forward, then the page it serves.
        assertThat(get(secretary, "/app/")).hasForwardedUrl("/app/index.html");
        assertThat(get(secretary, "/app/index.html")).hasStatusOk().bodyText().contains("<div id=\"root\"></div>");
        assertThat(get(secretary, "/app/week")).as("a reloaded page of the app").hasStatusOk()
                .bodyText().contains("<div id=\"root\"></div>");
        assertThat(get(secretary, "/app")).hasRedirectedUrl("/app/");
        assertThat(mvc.get().uri("/app/").accept(MediaType.TEXT_HTML)).as("logged out: to the login page")
                .hasStatus3xxRedirection().redirectedUrl().endsWith("/login");
    }
}
