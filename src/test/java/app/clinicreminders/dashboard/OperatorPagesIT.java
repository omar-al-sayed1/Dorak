package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.TokenCipher;
import app.clinicreminders.whatsapp.WhatsAppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Onboarding a clinic through the operator pages (SPEC section 5.5). */
class OperatorPagesIT extends DashboardTest {

    private static final String TOKEN = "EAAG-secret-token-123";

    @Autowired
    ClinicRepository clinics;

    @Autowired
    StaffUserRepository users;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    TokenCipher tokenCipher;

    @Autowired
    Billing billing;

    private StaffPrincipal operator;
    private Clinic clinic;

    @BeforeEach
    void anOperatorAndAClinic() {
        operator = StaffPrincipal.of(users.save(StaffUser.operator("omar", "{noop}operator-pass", Language.EN)));
        clinic = data.clinic("Clinique Zahle");
    }

    // --- Clinics ---

    @Test
    void listsEveryClinicWithItsConnectionAndLogins() {
        data.connectedClinic("Dr. Haddad", "pn-h");
        staffOf(clinic, StaffRole.OWNER, Language.AR);

        assertThat(get(operator, "/operator")).hasStatusOk().bodyText()
                .contains("Clinique Zahle", "Dr. Haddad", "Asia/Beirut", "Not connected", "Connected");
    }

    @Test
    void theClinicsAreSearchedByNameAndFiltered() {
        Clinic haddad = data.connectedClinic("Dr. Haddad", "pn-h");
        data.clinic("Dr. Saab");
        billing.save(haddad.getId(), 1, LocalDate.of(2020, 1, 1), null, null, null, null, null, null, clock.instant());
        billing.save(clinic.getId(), 1, LocalDate.of(2099, 1, 1), null, null, null, null, null, null, clock.instant());

        assertThat(get(operator, "/operator")).hasStatusOk().bodyText()
                .contains("Clinics: 3 of 3", "data-dialog-open=\"add-clinic\"", "name=\"q\"", "Paused", "Trial");
        assertThat(get(operator, "/operator?q=hadd")).bodyText().contains("Clinics: 1 of 3", ">Dr. Haddad<")
                .doesNotContain(">Clinique Zahle<", ">Dr. Saab<");
        assertThat(get(operator, "/operator?whatsapp=not-connected")).bodyText().contains(">Clinique Zahle<", ">Dr. Saab<")
                .doesNotContain(">Dr. Haddad<");
        assertThat(get(operator, "/operator?whatsapp=connected")).bodyText().contains(">Dr. Haddad<").doesNotContain(">Dr. Saab<");
        assertThat(get(operator, "/operator?billed=late")).bodyText().contains(">Dr. Haddad<").doesNotContain(">Clinique Zahle<", ">Dr. Saab<");
        assertThat(get(operator, "/operator?billed=paying")).as("Dr. Saab was given a subscription: every clinic is billed")
                .bodyText().contains(">Clinique Zahle<", ">Dr. Saab<").doesNotContain(">Dr. Haddad<", "Not billed yet");

        // Quick filters: whose subscription ends soon, who never paid, who has gone quiet.
        billing.save(clinic.getId(), 1, LocalDate.of(2026, 10, 18), null, null, null, null, null, null, clock.instant());
        Doctor doctor = data.doctor(haddad, "Dr. Haddad", 30);
        data.appointment(doctor, data.patient(haddad), clock.instant().plusSeconds(86_400), java.time.Duration.ofMinutes(30));
        billing.record(haddad.getId(), 1, null, app.clinicreminders.billing.SubscriptionPayment.Method.CASH, null,
                LocalDate.of(2026, 10, 14), "omar", clock.instant());
        assertThat(get(operator, "/operator")).bodyText().contains("Subscription ends soon", "Never paid", "Gone quiet");
        assertThat(get(operator, "/operator?soon=true")).as("due in four days").bodyText()
                .contains("Clinics: 1 of 3", ">Clinique Zahle<").doesNotContain(">Dr. Haddad<", ">Dr. Saab<");
        assertThat(get(operator, "/operator?unpaid=true")).bodyText().contains("Clinics: 2 of 3").doesNotContain(">Dr. Haddad<");
        assertThat(get(operator, "/operator?quiet=true")).as("only Dr. Haddad took a booking lately").bodyText()
                .contains("Clinics: 2 of 3", ">Clinique Zahle<", ">Dr. Saab<").doesNotContain(">Dr. Haddad<");
        assertThat(get(operator, "/operator?quiet=true&soon=true")).bodyText().contains("Clinics: 1 of 3", ">Clinique Zahle<");
        assertThat(get(operator, "/operator?setup=done")).bodyText().contains("Clinics: 0 of 3", "No clinic matches.");
        assertThat(get(operator, "/operator?setup=unfinished&q=SAAB")).bodyText().contains("Clinics: 1 of 3", ">Dr. Saab<");
    }

    @Test
    void aClinicIsDeletedOnlyOnceTheWordIsTypedAndCanBeBroughtBackForAFewMinutes() {
        Clinic other = data.clinic("Dr. Saab");
        StaffPrincipal owner = staffOf(clinic, StaffRole.OWNER, Language.EN);
        Doctor doctor = data.doctor(clinic, "Dr. Khoury", 30);
        data.appointment(doctor, data.patient(clinic), clock.instant().plusSeconds(86_400), java.time.Duration.ofMinutes(30));
        String page = "/operator/clinics/" + clinic.getId();

        assertThat(get(operator, page)).hasStatusOk().bodyText()
                .contains("Delete this clinic", "data-type-to-confirm=\"delete\"", "data-confirm=\"Delete Clinique Zahle and everything in it?\"", "Logins: 1", "Doctors: 1", "Patients: 1", "Appointments: 1");
        assertThat(post(owner, page + "/delete").param("confirm", "delete")).as("the operator's only").hasStatus(403);
        assertThat(post(operator, page + "/delete")).hasStatus3xxRedirection().hasRedirectedUrl(page);
        assertThat(post(operator, page + "/delete").param("confirm", "yes")).hasStatus3xxRedirection().hasRedirectedUrl(page);
        assertThat(clinics.findById(clinic.getId())).as("nothing typed, or the wrong word: still there").isPresent();

        assertThat(post(operator, page + "/delete").param("confirm", " Delete ")).hasStatus3xxRedirection().hasRedirectedUrl("/operator");
        assertThat(clinics.findById(clinic.getId())).isEmpty();
        assertThat(clinics.findById(other.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from appointment", Integer.class)).isZero();
        assertThat(users.findByUsernameIgnoreCase("omar")).as("the operator's own login stays").isPresent();
        assertThat(get(operator, page)).hasStatus(404);
        assertThat(post(operator, page + "/delete").param("confirm", "delete")).hasStatus(404);
        assertThat(get(operator, "/operator/patients")).as("its patients went with it").hasStatusOk().bodyText()
                .doesNotContain("Clinique Zahle");
        assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isZero();

        // The clinics page offers to undo it, and Undo brings everything back.
        java.util.UUID token = jdbc.queryForObject("select token from deleted_clinic", java.util.UUID.class);
        assertThat(get(operator, "/operator")).bodyText()
                .contains("Clinique Zahle was deleted.", "/operator/clinics/restore/" + token, ">Undo<");
        assertThat(post(owner, "/operator/clinics/restore/" + token)).hasStatus(403);
        assertThat(post(operator, "/operator/clinics/restore/" + token)).hasStatus3xxRedirection().hasRedirectedUrl("/operator");
        assertThat(clinics.findById(clinic.getId()).orElseThrow().getName()).isEqualTo("Clinique Zahle");
        assertThat(jdbc.queryForObject("select count(*) from appointment where doctor_id = ?", Integer.class, doctor.getId())).isEqualTo(1);
        assertThat(get(operator, page)).hasStatusOk().bodyText().contains("Logins: 1", "Patients: 1");
        assertThat(get(operator, "/operator")).bodyText().contains(">Clinique Zahle<").doesNotContain("was deleted");

        // Deleted again and left: once the minutes are over there is nothing to undo.
        assertThat(post(operator, page + "/delete").param("confirm", "delete")).hasStatus3xxRedirection();
        java.util.UUID second = jdbc.queryForObject("select token from deleted_clinic", java.util.UUID.class);
        clock.setInstant(clock.instant().plus(java.time.Duration.ofMinutes(11)));
        assertThat(get(operator, "/operator")).bodyText().doesNotContain("was deleted", ">Undo<");
        assertThat(post(operator, "/operator/clinics/restore/" + second)).hasStatus3xxRedirection();
        assertThat(clinics.findById(clinic.getId())).isEmpty();
    }

    @Test
    void aClinicCanHaveAnEmailThatIsCheckedAndOptional() {
        assertThat(post(operator, "/operator/clinics").param("name", "Dr. Nassar").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar").param("email", "  contact@nassar-clinic.com ")).hasStatus3xxRedirection();
        Clinic nassar = clinics.findAllByOrderByNameAscIdAsc().stream().filter(c -> c.getName().equals("Dr. Nassar")).findFirst().orElseThrow();
        assertThat(nassar.getContactEmail()).isEqualTo("contact@nassar-clinic.com");
        assertThat(get(operator, "/operator/clinics/{id}", nassar.getId())).bodyText()
                .contains("href=\"mailto:contact@nassar-clinic.com\"", "value=\"contact@nassar-clinic.com\"");
        assertThat(get(operator, "/operator")).bodyText().contains("name=\"email\"", "Email (optional)");

        assertThat(post(operator, "/operator/clinics").param("name", "Dr. Typo").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar").param("email", "not-an-email")).hasRedirectedUrl("/operator")
                .flash().containsEntry("error", "error.email");
        assertThat(clinics.findAllByOrderByNameAscIdAsc()).noneMatch(c -> c.getName().equals("Dr. Typo"));

        String page = "/operator/clinics/" + nassar.getId();
        assertThat(post(operator, page).param("name", "Dr. Nassar").param("timezone", "Asia/Beirut").param("defaultLanguage", "ar")
                .param("email", "nassar@clinic")).flash().containsEntry("error", "error.email");
        assertThat(post(operator, page).param("name", "Dr. Nassar").param("timezone", "Asia/Beirut").param("defaultLanguage", "ar")
                .param("email", "")).hasStatus3xxRedirection();
        assertThat(clinics.findById(nassar.getId()).orElseThrow().getContactEmail()).as("emptied: no email").isNull();
    }

    @Test
    void createsAClinicAndOpensIt() {
        MvcTestResult result = post(operator, "/operator/clinics").param("name", " Dr. Saab ")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "en").exchange();

        Clinic created = clinics.findAllByOrderByNameAscIdAsc().stream()
                .filter(c -> c.getName().equals("Dr. Saab")).findFirst().orElseThrow();
        assertThat(result).hasStatus3xxRedirection().headers()
                .hasValue("Location", "/operator/clinics/" + created.getId());
        assertThat(created.getDefaultLanguage()).isEqualTo(Language.EN);
        assertThat(created.getWaPhoneNumberId()).isNull();
        assertThat(billing.subscription(created.getId())).as("billed from the start, on the general plan")
                .hasValueSatisfying(subscription -> assertThat(subscription.hasOwnTerms()).isFalse());
        assertThat(get(operator, "/operator/clinics/{id}", created.getId())).hasStatusOk().bodyText()
                .contains("Dr. Saab", "No token saved.", "No logins yet.");
    }

    @Test
    void refusesAnUnknownTimezoneOrABlankName() {
        assertThat(post(operator, "/operator/clinics").param("name", "X").param("timezone", "Beirut")
                .param("defaultLanguage", "ar")).flash().containsEntry("error", "error.timezone");
        assertThat(post(operator, "/operator/clinics").param("name", "  ").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar")).flash().containsEntry("error", "error.clinic-name");
        assertThat(clinics.findAllByOrderByNameAscIdAsc()).hasSize(1);
    }

    @Test
    void editsTheDetails() {
        post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Chtaura")
                .param("timezone", "Europe/Paris").param("defaultLanguage", "en").exchange();

        Clinic edited = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(edited.getName()).isEqualTo("Clinique Chtaura");
        assertThat(edited.getTimezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(edited.getDefaultLanguage()).isEqualTo(Language.EN);
    }

    @Test
    void theAppointmentLengthIsTheOperatorsAndAppliesToEveryDoctor() {
        assertThat(clinic.getSlotMinutes()).as("default").isEqualTo(20);
        Doctor khoury = data.doctor(clinic, "Dr. Khoury", 20);
        Doctor saab = data.doctor(clinic, "Dr. Saab", 20);

        post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Zahle")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "ar").param("slotMinutes", "30").exchange();

        assertThat(clinics.findById(clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
        assertThat(doctors.findByIdAndClinicId(khoury.getId(), clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
        assertThat(doctors.findByIdAndClinicId(saab.getId(), clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);

        assertThat(post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Zahle")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "ar").param("slotMinutes", "2"))
                .flash().containsEntry("error", "error.slot-length");
        assertThat(clinics.findById(clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
    }

    @Test
    void aNewClinicCanStartWithItsOwnLength() {
        post(operator, "/operator/clinics").param("name", "Dr. Saab").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar").param("slotMinutes", "15").exchange();

        assertThat(clinics.findAllByOrderByNameAscIdAsc()).filteredOn(c -> c.getName().equals("Dr. Saab")).singleElement()
                .extracting(Clinic::getSlotMinutes).isEqualTo(15);
    }

    @Test
    void anUnknownClinicIsNotFound() {
        assertThat(get(operator, "/operator/clinics/999")).hasStatus(404);
    }

    // --- WhatsApp connection ---

    @Test
    void theTokenIsStoredEncryptedAndNeverShownAgain() {
        connect("1065403", "1022901", TOKEN);

        Clinic saved = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(saved.getWaPhoneNumberId()).isEqualTo("1065403");
        assertThat(saved.getWaBusinessAccountId()).isEqualTo("1022901");
        assertThat(new String(saved.getWaAccessTokenEncrypted())).doesNotContain(TOKEN);
        assertThat(tokenCipher.decrypt(clinic.getId(), saved.getWaAccessTokenEncrypted())).isEqualTo(TOKEN);
        assertThat(get(operator, "/operator/clinics/{id}", clinic.getId())).bodyText()
                .contains("1065403", "A token is saved.").doesNotContain(TOKEN);
    }

    @Test
    void aBlankTokenKeepsTheSavedOneAndRemovingItClearsIt() {
        connect("1065403", "1022901", TOKEN);
        connect("1065404", "1022901", "");

        Clinic kept = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(kept.getWaPhoneNumberId()).isEqualTo("1065404");
        assertThat(tokenCipher.decrypt(clinic.getId(), kept.getWaAccessTokenEncrypted())).isEqualTo(TOKEN);

        post(operator, "/operator/clinics/{id}/whatsapp", clinic.getId()).param("phoneNumberId", "1065404")
                .param("businessAccountId", "1022901").param("removeToken", "true").exchange();
        assertThat(clinics.findById(clinic.getId()).orElseThrow().getWaAccessTokenEncrypted()).isNull();
    }

    @Test
    void anotherClinicsPhoneNumberIdIsRefused() {
        data.connectedClinic("Dr. Haddad", "1065403");

        assertThat(connect("1065403", "1022901", TOKEN)).flash().containsEntry("error", "error.phone-number-id-taken");
        Clinic unchanged = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(unchanged.getWaPhoneNumberId()).isNull();
        assertThat(unchanged.getWaAccessTokenEncrypted()).isNull();
    }

    @Test
    void subscribesTheBusinessAccountToOurWebhooks() {
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("error", "error.not-connected");

        connect("1065403", "1022901", TOKEN);
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("notice", "notice.subscribed");
        assertThat(whatsApp.subscribedAccounts()).containsExactly("1022901");

        whatsApp.failNext(WhatsAppException.fromMetaError(200, "Permissions error"));
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("metaError", "(#200) Permissions error");
    }

    // --- Test message ---

    @Test
    void sendsHelloWorldOrASampleReminderAndShowsTheResult() {
        connect("1065403", "1022901", TOKEN);
        data.doctor(clinic, "د. خوري", 30);

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "03 123 456")
                .param("kind", "hello")).flash().extractingByKey("testOutcome")
                .hasFieldOrPropertyWithValue("result", "sent");
        assertThat(whatsApp.recent().getFirst().message())
                .isEqualTo(new TemplateMessage("+9613123456", "hello_world", "en_US", List.of()));

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "+96170123456")
                .param("kind", "reminder")).hasStatus3xxRedirection();
        assertThat(whatsApp.recent().getFirst().message()).isInstanceOfSatisfying(TemplateMessage.class, reminder -> {
            assertThat(reminder.name()).isEqualTo("appointment_reminder");
            assertThat(reminder.languageCode()).isEqualTo("ar");
            assertThat(reminder.bodyParameters()).hasSize(4).startsWith("رنا", "د. خوري");
        });
        assertThat(jdbc.queryForObject("select count(*) from message_log where clinic_id = ?", Integer.class,
                clinic.getId())).isEqualTo(2);
    }

    @Test
    void aRefusedTestShowsMetasError() {
        connect("1065403", "1022901", TOKEN);
        whatsApp.failNext(WhatsAppException.fromMetaError(132001, "Template name does not exist in the translation"));

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "+96170123456")
                .param("kind", "hello")).flash().extractingByKey("testOutcome")
                .hasFieldOrPropertyWithValue("result", "failed")
                .hasFieldOrPropertyWithValue("detail", "(#132001) Template name does not exist in the translation");
        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "hello")
                .param("kind", "hello")).flash().containsEntry("error", "error.phone");
    }

    // --- Logins ---

    @Test
    void aNewLoginGetsAPasswordShownOnceThatWorks() {
        MvcTestResult result = post(operator, "/operator/clinics/{id}/logins", clinic.getId())
                .param("username", "rana.sec").param("role", "SECRETARY").param("language", "ar").exchange();

        NewPassword password = (NewPassword) result.getMvcResult().getFlashMap().get("newPassword");
        assertThat(password.username()).isEqualTo("rana.sec");
        assertThat(password.password()).matches("[a-zA-Z2-9]{4}(-[a-zA-Z2-9]{4}){3}");
        assertThat(logIn("rana.sec", password.password())).isEqualTo("/app/");
        StaffUser saved = users.findByUsernameIgnoreCase("rana.sec").orElseThrow();
        assertThat(saved.getClinicId()).isEqualTo(clinic.getId());
        assertThat(saved.getRole()).isEqualTo(StaffRole.SECRETARY);
        assertThat(saved.getPasswordHash()).doesNotContain(password.password());
    }

    @Test
    void usernamesAreUniqueAcrossClinicsAndOperatorsCantBeAddedHere() {
        StaffPrincipal other = staffOf(data.clinic("B"), StaffRole.OWNER, Language.AR);

        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", other.username().toUpperCase())
                .param("role", "OWNER").param("language", "ar")).flash().containsEntry("error", "error.username-taken");
        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", "a b")
                .param("role", "OWNER").param("language", "ar")).flash().containsEntry("error", "error.username");
        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", "boss")
                .param("role", "OPERATOR").param("language", "ar")).flash().containsEntry("error", "error.username");
        assertThat(users.findByUsernameIgnoreCase("boss")).isEmpty();
    }

    @Test
    void resettingGivesANewPasswordAndDisablingStopsLogins() {
        NewPassword first = (NewPassword) post(operator, "/operator/clinics/{id}/logins", clinic.getId())
                .param("username", "rana.sec").param("role", "SECRETARY").param("language", "ar")
                .exchange().getMvcResult().getFlashMap().get("newPassword");
        long userId = users.findByUsernameIgnoreCase("rana.sec").orElseThrow().getId();

        NewPassword second = (NewPassword) post(operator, "/operator/clinics/{id}/logins/{u}/reset", clinic.getId(), userId)
                .exchange().getMvcResult().getFlashMap().get("newPassword");
        assertThat(logIn("rana.sec", first.password())).isEqualTo("/login?error");
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/app/");

        post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), userId).param("enabled", "false").exchange();
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/login?error");
        post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), userId).param("enabled", "true").exchange();
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/app/");
    }

    @Test
    void aNewPasswordAndDisablingOrEnablingAskFirst() {
        post(operator, "/operator/clinics/{id}/logins", clinic.getId())
                .param("username", "rana.sec").param("role", "SECRETARY").param("language", "ar").exchange();
        long userId = users.findByUsernameIgnoreCase("rana.sec").orElseThrow().getId();

        assertThat(get(operator, "/operator/clinics/{id}", clinic.getId())).bodyText()
                .contains("data-confirm=\"Give rana.sec a new password? Their current password stops working right away",
                        "data-confirm=\"Disable rana.sec? They will not be able to log in")
                .containsOnlyOnce("data-confirm-calm");

        post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), userId).param("enabled", "false").exchange();
        assertThat(get(operator, "/operator/clinics/{id}", clinic.getId())).bodyText()
                .contains("data-confirm=\"Enable rana.sec? They can log in again")
                .as("New password and Enable are calm, only Disable is red")
                .containsPattern("(?s)data-confirm-calm.*data-confirm-calm");
    }

    @Test
    void anotherClinicsLoginCantBeResetThroughThisClinic() {
        StaffPrincipal other = staffOf(data.clinic("B"), StaffRole.OWNER, Language.AR);

        assertThat(post(operator, "/operator/clinics/{id}/logins/{u}/reset", clinic.getId(), other.id())).hasStatus(404);
        assertThat(post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), other.id())
                .param("enabled", "false")).hasStatus(404);
    }

    // --- Own password ---

    @Test
    void everyoneCanChangeTheirOwnPassword() {
        StaffPrincipal secretary = secretaryOf(clinic);

        assertThat(post(secretary, "/account/password").param("currentPassword", "wrong").param("newPassword", "long-enough-1"))
                .flash().containsEntry("error", "error.password-wrong");
        assertThat(post(secretary, "/account/password").param("currentPassword", "secret").param("newPassword", "short"))
                .flash().containsEntry("error", "error.password-short");
        assertThat(post(secretary, "/account/password").param("currentPassword", "secret").param("newPassword", "long-enough-1"))
                .flash().containsEntry("notice", "notice.password-changed");
        assertThat(logIn(secretary.username(), "long-enough-1")).isEqualTo("/app/");

        assertThat(get(operator, "/account/password")).hasStatusOk();
        post(operator, "/account/password").param("currentPassword", "operator-pass").param("newPassword", "operator-pass-2")
                .exchange();
        assertThat(logIn("omar", "operator-pass-2")).isEqualTo("/app/");
    }

    @Test
    void operatorsCanSwitchLanguageToo() {
        assertThat(post(operator, "/language").param("lang", "ar").header("Referer", "http://localhost/operator"))
                .hasStatus3xxRedirection().headers().hasValue("Location", "/operator");
        assertThat(users.findByUsernameIgnoreCase("omar").orElseThrow().getLanguage()).isEqualTo(Language.AR);
    }

    private MvcTestResult connect(String phoneNumberId, String businessAccountId, String token) {
        return post(operator, "/operator/clinics/{id}/whatsapp", clinic.getId()).param("phoneNumberId", phoneNumberId)
                .param("businessAccountId", businessAccountId).param("accessToken", token).exchange();
    }

    /** @return where the login sent the browser: "/app/" on success, "/login?error" otherwise */
    private String logIn(String username, String password) {
        return mvc.post().uri("/login").param("username", username).param("password", password).with(csrf())
                .exchange().getResponse().getHeader("Location");
    }
}
