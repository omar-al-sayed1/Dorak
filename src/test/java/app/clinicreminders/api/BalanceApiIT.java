package app.clinicreminders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * Balance over the JSON API: the front desk's only, each clinic its own, and the public receipt.
 * Now is Wednesday 14 October 2026, 12:00 in Beirut.
 */
class BalanceApiIT extends DashboardTest {

    private static final Instant TEN_THIRTY = Instant.parse("2026-10-14T07:30:00Z");

    @Autowired
    StaffUserRepository users;

    private Clinic clinic;
    private Doctor khoury;
    private Appointment visit;
    private StaffPrincipal secretary;

    @BeforeEach
    void aVisitToday() {
        clinic = data.clinic("Dr. Demo");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        visit = data.appointment(khoury, rana, TEN_THIRTY, Duration.ofMinutes(30));
        secretary = secretaryOf(clinic);
    }

    @Test
    void theFrontDeskRecordsAPaymentInLiraAndGetsTheReceiptLink() {
        assertThat(send(secretary, "/api/money/appointments/{id}", visit.getId(),
                "{\"fee\":30,\"payment\":{\"amount\":1342500,\"currency\":\"LBP\",\"method\":\"CASH\"}}"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.visit.status").isEqualTo("PARTLY_PAID");
                    json.assertThat().extractingPath("$.visit.due").convertTo(BIG_DECIMAL).isEqualByComparingTo("15");
                    json.assertThat().extractingPath("$.visit.payments[0].amountLbp").convertTo(BIG_DECIMAL)
                            .isEqualByComparingTo("1342500");
                    json.assertThat().extractingPath("$.visit.patientName").isEqualTo("Rana Haddad");
                    json.assertThat().extractingPath("$.receiptUrl").asString().matches(".*/r/[0-9a-f-]{36}");
                });

        assertThat(get(secretary, "/api/today")).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.doctors[0].rows[0].money.status").isEqualTo("PARTLY_PAID");
            json.assertThat().extractingPath("$.doctors[0].rows[0].money.due").convertTo(BIG_DECIMAL)
                    .isEqualByComparingTo("15");
        });
        assertThat(get(doctorLoginOf(khoury), "/api/today")).as("money is the front desk's").bodyJson()
                .extractingPath("$.doctors[0].rows[0].money").isNull();
    }

    @Test
    void laterSavesTheFeeAndTheAccountShowsWhoOwes() {
        assertThat(send(secretary, "/api/money/appointments/{id}", visit.getId(), "{\"fee\":40,\"payment\":null}"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.visit.status").isEqualTo("UNPAID");
                    json.assertThat().extractingPath("$.receiptUrl").isNull();
                });

        assertThat(get(secretary, "/api/money/owes")).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.total").convertTo(BIG_DECIMAL).isEqualByComparingTo("40");
            json.assertThat().extractingPath("$.patients[0].name").isEqualTo("Rana Haddad");
            json.assertThat().extractingPath("$.patients[0].days").isEqualTo(0);
        });
        long patientId = jdbc.queryForObject("select patient_id from appointment where id = ?", Long.class, visit.getId());
        assertThat(get(secretary, "/api/money/patients/{id}", patientId)).hasStatusOk().bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.balance").convertTo(BIG_DECIMAL).isEqualByComparingTo("40");
            json.assertThat().extractingPath("$.entries[0].type").isEqualTo("CHARGE");
            json.assertThat().extractingPath("$.entries[0].doctor").isEqualTo("Dr. Khoury");
        });
    }

    @Test
    void theDayIsClosedWithTheCashCounted() {
        send(secretary, "/api/money/appointments/{id}", visit.getId(),
                "{\"fee\":30,\"payment\":{\"amount\":30,\"currency\":\"USD\",\"method\":\"CASH\"}}").assertThat().hasStatusOk();

        assertThat(mvc.put().uri("/api/money/day").with(user(secretary)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-14\",\"countedCash\":28}")).hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.byMethod.CASH").convertTo(BIG_DECIMAL).isEqualByComparingTo("30");
                    json.assertThat().extractingPath("$.byMethod.WHISH").convertTo(BIG_DECIMAL).isEqualByComparingTo("0");
                    json.assertThat().extractingPath("$.expectedCash").convertTo(BIG_DECIMAL).isEqualByComparingTo("30");
                    json.assertThat().extractingPath("$.close.difference").convertTo(BIG_DECIMAL).isEqualByComparingTo("-2");
                    json.assertThat().extractingPath("$.payments[0].time").isEqualTo("12:00 PM");
                });
        assertThat(get(secretary, "/api/money/day?date=2026-10-13")).bodyJson().extractingPath("$.close").isNull();
    }

    @Test
    void onlyTheFrontDeskAndOnlyTheirClinic() {
        StaffPrincipal doctor = doctorLoginOf(khoury);
        assertThat(get(doctor, "/api/money/owes")).hasStatus(403);
        assertThat(get(doctor, "/api/money/appointments/{id}", visit.getId())).hasStatus(403);
        assertThat(send(doctor, "/api/money/appointments/{id}", visit.getId(), "{\"fee\":30,\"payment\":null}")).hasStatus(403);
        StaffPrincipal operator = StaffPrincipal.of(users.save(StaffUser.operator("op.money", "{noop}x", Language.EN)));
        assertThat(get(operator, "/api/money/owes")).hasStatus(403);
        assertThat(mvc.get().uri("/api/money/owes").accept(MediaType.APPLICATION_JSON)).hasStatus(401);

        StaffPrincipal elsewhere = secretaryOf(data.clinic("B"));
        assertThat(get(elsewhere, "/api/money/appointments/{id}", visit.getId())).hasStatus(404);
        assertThat(send(elsewhere, "/api/money/appointments/{id}", visit.getId(), "{\"fee\":30,\"payment\":null}"))
                .hasStatus(404);
        assertThat(mvc.put().uri("/api/money/doctors/{id}/fee", khoury.getId()).with(user(elsewhere)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30}")).hasStatus(404);
        assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isZero();
    }

    @Test
    void theReceiptIsPublicInThePatientsLanguage() {
        send(secretary, "/api/money/appointments/{id}", visit.getId(),
                "{\"fee\":30,\"payment\":{\"amount\":895000,\"currency\":\"LBP\",\"method\":\"CASH\"}}").assertThat().hasStatusOk();
        String token = jdbc.queryForObject("select receipt_token::text from payment", String.class);

        assertThat(mvc.get().uri("/r/{token}", token)).hasStatusOk()
                .hasHeader("X-Robots-Tag", "noindex, nofollow")
                .bodyText().contains("Dr. Demo", "Rana Haddad", "$10.00", "895,000 LBP", "المتبقي للدفع: $20.00", "dir=\"rtl\"");
        assertThat(get(secretary, "/r/" + token)).as("staff read it in their own language").bodyText()
                .contains("dir=\"ltr\"", "Left to pay: $20.00", "Received from", "href=\"?lang=ar\"");
        assertThat(mvc.get().uri("/r/" + token + "?lang=en")).bodyText().contains("dir=\"ltr\"", "Receipt");
        assertThat(mvc.get().uri("/r/{token}", "00000000-0000-0000-0000-000000000000")).hasStatus(404);
        assertThat(mvc.get().uri("/r/{token}", "not-a-token")).hasStatus(404);
    }

    @Test
    void addPaymentPutsMoneyOnTheAccountWithoutAVisit() {
        long patientId = jdbc.queryForObject("select patient_id from appointment where id = ?", Long.class, visit.getId());

        assertThat(send(secretary, "/api/money/patients/{id}", patientId, "{\"kind\":\"OWES\",\"amount\":50}"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.balance").convertTo(BIG_DECIMAL).isEqualByComparingTo("50");
                    json.assertThat().extractingPath("$.receiptUrl").isNull();
                });
        assertThat(send(secretary, "/api/money/patients/{id}", patientId,
                "{\"kind\":\"PAID\",\"amount\":20,\"currency\":\"USD\",\"method\":\"OMT\"}"))
                .hasStatusOk().bodyJson().satisfies(json -> {
                    json.assertThat().extractingPath("$.balance").convertTo(BIG_DECIMAL).isEqualByComparingTo("30");
                    json.assertThat().extractingPath("$.receiptUrl").asString().matches(".*/r/[0-9a-f-]{36}");
                });
        assertThat(send(secretary, "/api/money/patients/{id}", patientId, "{\"kind\":\"PAID\",\"amount\":20}"))
                .as("a payment needs its method").hasStatus(400);

        assertThat(get(secretary, "/api/money/patients/{id}", patientId)).bodyJson().satisfies(json -> {
            json.assertThat().extractingPath("$.entries[*].type").asArray().containsExactlyInAnyOrder("CHARGE", "PAYMENT");
            json.assertThat().extractingPath("$.entries[?(@.type == 'CHARGE')].visit").asArray().containsExactly((Object) null);
        });
        assertThat(get(secretary, "/api/money/owes")).bodyJson().extractingPath("$.patients[0].balance")
                .convertTo(BIG_DECIMAL).isEqualByComparingTo("30");
        assertThat(get(secretary, "/api/today")).as("not a visit's money").bodyJson()
                .extractingPath("$.doctors[0].rows[0].money").isNull();

        StaffPrincipal elsewhere = secretaryOf(data.clinic("B"));
        assertThat(send(elsewhere, "/api/money/patients/{id}", patientId, "{\"kind\":\"OWES\",\"amount\":5}")).hasStatus(404);
        assertThat(send(doctorLoginOf(khoury), "/api/money/patients/{id}", patientId, "{\"kind\":\"OWES\",\"amount\":5}"))
                .hasStatus(403);
    }

    private MockMvcRequestBuilder send(StaffPrincipal staff, String uri, Object id, String body) {
        return post(staff, uri, id).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
