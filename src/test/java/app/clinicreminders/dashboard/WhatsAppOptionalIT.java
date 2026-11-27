package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.WhatsAppMessenger.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * WhatsApp is optional per clinic. Off: no reminders and no messages, nothing WhatsApp on the
 * clinic's dashboard, and the general plan's fee without WhatsApp. Now is Wednesday 14 October 2026;
 * the plan is $40 a month with WhatsApp and $20 without.
 */
class WhatsAppOptionalIT extends DashboardTest {

    @Autowired
    ClinicRepository clinics;

    @Autowired
    StaffUserRepository users;

    @Autowired
    Billing billing;

    @Autowired
    WhatsAppMessenger messenger;

    private StaffPrincipal operator;

    @BeforeEach
    void anOperatorAndThePlan() {
        operator = StaffPrincipal.of(users.save(StaffUser.operator("omar", "{noop}operator-pass", Language.EN)));
        billing.savePlan(new BigDecimal("40"), new BigDecimal("20"), 5, 10, 2, MessageCosts.INCLUDED);
    }

    private Clinic created(String name) {
        return clinics.findAllByOrderByNameAscIdAsc().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    private BigDecimal monthlyFee(Clinic clinic) {
        Subscription subscription = billing.subscription(clinic.getId()).orElseThrow();
        return billing.terms(subscription).monthlyFeeUsd();
    }

    @Test
    void aClinicIsAddedWithOrWithoutWhatsAppAndPaysThePlanFeeForIt() {
        assertThat(get(operator, "/operator")).bodyText()
                .contains("name=\"whatsAppShown\"", "name=\"whatsApp\" value=\"true\" checked", "$40.00 a month", "$20.00 a month");

        post(operator, "/operator/clinics").param("name", "Dr. Saab").param("timezone", "Asia/Beirut").param("defaultLanguage", "ar")
                .param("whatsAppShown", "true").param("whatsApp", "true").exchange();
        post(operator, "/operator/clinics").param("name", "Dr. Nassar").param("timezone", "Asia/Beirut").param("defaultLanguage", "ar")
                .param("whatsAppShown", "true").exchange();

        Clinic saab = created("Dr. Saab");
        Clinic nassar = created("Dr. Nassar");
        assertThat(saab.isWhatsAppEnabled()).isTrue();
        assertThat(nassar.isWhatsAppEnabled()).as("the switch left off").isFalse();
        assertThat(get(operator, "/operator")).bodyText().contains("chip-dashboard-only", "1 of 2 done");
        assertThat(monthlyFee(saab)).isEqualByComparingTo("40");
        assertThat(monthlyFee(nassar)).isEqualByComparingTo("20");
        assertThat(get(operator, "/operator?whatsapp=off")).bodyText().contains(">Dr. Nassar<").doesNotContain(">Dr. Saab<");

        // A fee of its own wins either way.
        billing.save(nassar.getId(), 1, java.time.LocalDate.of(2026, 11, 14), null, new BigDecimal("15"), null, null, null, null,
                clock.instant());
        assertThat(monthlyFee(nassar)).isEqualByComparingTo("15");
    }

    @Test
    void turningWhatsAppOffStopsRemindersAndMessagesAndOnPlansThemAgain() {
        Clinic clinic = data.connectedClinic("Clinique Zahle", "pn-z");
        Doctor doctor = data.doctor(clinic, "Dr. Khoury", 30);
        StaffPrincipal secretary = secretaryOf(clinic);
        assertThat(book(secretary, doctor, "2026-10-16", "11:00")).hasStatus3xxRedirection();
        assertThat(pendingReminders()).isEqualTo(2);

        String page = "/operator/clinics/" + clinic.getId();
        assertThat(get(operator, page)).bodyText().contains("data-wa-mode", "data-confirm-off=\"Turn WhatsApp off for Clinique Zahle?");
        assertThat(post(operator, page + "/whatsapp-mode").param("enabled", "false")).hasRedirectedUrl(page)
                .flash().containsEntry("notice", "notice.whatsapp-off");

        assertThat(pendingReminders()).as("cancelled with WhatsApp").isZero();
        assertThat(messenger.send(clinic.getId(), new TextMessage("+96170123456", "hello"), null).result()).isEqualTo(Result.DISABLED);
        assertThat(jdbc.queryForObject("select count(*) from message_log", Integer.class)).as("nothing sent, nothing logged").isZero();
        assertThat(book(secretary, doctor, "2026-10-17", "11:00")).hasStatus3xxRedirection();
        assertThat(pendingReminders()).as("a new booking gets none either").isZero();
        assertThat(get(operator, page)).bodyText().contains("is-off", "2 of 2")
                .as("nothing to connect").doesNotContain("name=\"phoneNumberId\"", "Test message");

        assertThat(post(operator, page + "/whatsapp-mode").param("enabled", "true")).flash().containsEntry("notice", "notice.whatsapp-on");
        assertThat(pendingReminders()).as("both upcoming appointments, two reminders each").isEqualTo(4);
        assertThat(post(operator, page + "/whatsapp-mode").param("enabled", "true")).as("already on: nothing to say")
                .flash().doesNotContainKey("notice");
        assertThat(pendingReminders()).isEqualTo(4);

        assertThat(post(secretary, page + "/whatsapp-mode").param("enabled", "false")).as("the operator's only").hasStatus(403);
    }

    @Test
    void theDashboardOfAClinicWithoutWhatsAppHasNothingAboutIt() throws Exception {
        Clinic clinic = data.clinic("Dr. Nassar");
        clinics.findById(clinic.getId()).ifPresent(c -> {
            c.changeWhatsAppEnabled(false);
            clinics.save(c);
        });
        Doctor doctor = data.doctor(clinic, "Dr. Nassar", 30);
        StaffPrincipal owner = staffOf(clinic, StaffRole.OWNER, Language.EN);
        assertThat(book(owner, doctor, "2026-10-14", "15:00")).hasStatus3xxRedirection();

        assertThat(get(owner, "/settings")).bodyText()
                .contains("name=\"defaultLanguage\"", "name=\"address\"", "Printed on your receipts")
                .doesNotContain("name=\"h24Enabled\"", "name=\"h2Enabled\"", "Info on WhatsApp", "Preview on WhatsApp", "name=\"hoursText\"");
        assertThat(get(owner, "/report")).bodyText().doesNotContain("Booked on WhatsApp", "WhatsApp messages", "Confirmed by the patient");
        assertThat(get(owner, "/week")).bodyText().doesNotContain("class=\"bell", "href=\"/attention\"", "lg-CONFIRMED");
        assertThat(get(owner, "/appointments/new?doctorId=" + doctor.getId() + "&date=2026-10-15")).bodyText()
                .doesNotContain("Messages in").contains("type=\"hidden\" name=\"language\"");
        assertThat(get(owner, "/api/session")).bodyJson().extractingPath("$.whatsApp").isEqualTo(false);

        // Saving settings keeps the reminder switches for when WhatsApp comes back.
        assertThat(post(owner, "/settings/clinic").param("defaultLanguage", "en").param("address", "Main street"))
                .hasStatus3xxRedirection();
        Clinic saved = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(saved.isReminderH24Enabled()).isTrue();
        assertThat(saved.isReminderH2Enabled()).isTrue();
        assertThat(saved.getAddress()).isEqualTo("Main street");
    }

    @Test
    void aClinicWithWhatsAppStillHasItAll() {
        Clinic clinic = data.clinic("Clinique Zahle");
        StaffPrincipal owner = staffOf(clinic, StaffRole.OWNER, Language.EN);

        assertThat(get(owner, "/settings")).bodyText().contains("name=\"h24Enabled\"", "Info on WhatsApp", "name=\"hoursText\"");
        assertThat(get(owner, "/week")).bodyText().contains("href=\"/attention\"");
        assertThat(get(owner, "/api/session")).bodyJson().extractingPath("$.whatsApp").isEqualTo(true);
    }

    @Test
    void thePlanFeeWithoutWhatsAppIsSetWithTheRest() {
        assertThat(get(operator, "/operator/billing")).bodyText().contains("name=\"monthlyFeeNoWhatsApp\"", "value=\"20.00\"");

        assertThat(post(operator, "/operator/billing/plan").param("monthlyFee", "45").param("monthlyFeeNoWhatsApp", "25")
                .param("discount6", "5").param("discount12", "10").param("graceDays", "2").param("messageCosts", "INCLUDED"))
                .hasRedirectedUrl("/operator/billing");

        assertThat(billing.plan(true).monthlyFeeUsd()).isEqualByComparingTo("45");
        assertThat(billing.plan(false).monthlyFeeUsd()).isEqualByComparingTo("25");
        assertThat(post(operator, "/operator/billing/plan").param("monthlyFee", "45").param("monthlyFeeNoWhatsApp", "twenty")
                .param("discount6", "5").param("discount12", "10").param("graceDays", "2")).flash().containsEntry("error", "error.billing");
    }

    private int pendingReminders() {
        return jdbc.queryForObject("select count(*) from reminder where status = 'PENDING'", Integer.class);
    }

    private MvcTestResult book(StaffPrincipal staff, Doctor doctor, String date, String time) {
        return post(staff, "/appointments").param("doctorId", String.valueOf(doctor.getId()))
                .param("date", date).param("otherTime", time).param("phone", "70 123 456").param("name", "Rana Haddad")
                .param("language", "ar").exchange();
    }
}
