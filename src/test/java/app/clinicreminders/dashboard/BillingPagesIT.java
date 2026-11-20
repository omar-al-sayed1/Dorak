package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.billing.Terms;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Billing through the pages: the operator's overview and a clinic's billing, the owner's page, the
 * public invoice, the notice across the clinic's dashboard and the pause. Now is Wednesday 14 October 2026.
 */
class BillingPagesIT extends DashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Autowired
    Billing billing;

    @Autowired
    StaffUserRepository users;

    private StaffPrincipal operator;
    private Clinic clinic;
    private StaffPrincipal owner;
    private StaffPrincipal secretary;

    @BeforeEach
    void anOperatorAndAClinic() {
        operator = StaffPrincipal.of(users.save(StaffUser.operator("omar", "{noop}operator-pass", Language.EN)));
        clinic = data.clinic("Clinique Zahle");
        owner = staffOf(clinic, StaffRole.OWNER, Language.EN);
        secretary = staffOf(clinic, StaffRole.SECRETARY, Language.EN);
    }

    private Subscription dueOn(LocalDate due) {
        // Its own terms: $50 a month, 10% off for 6 months and 20% for a year; it usually pays 3 months.
        return billing.save(clinic.getId(), 3, due, "+96170123456", new BigDecimal("50"), 10, 20, 14, MessageCosts.INCLUDED,
                clock.instant());
    }

    // --- The operator ---

    @Test
    void theOperatorSetsASubscriptionRecordsAPaymentAndUndoesIt() {
        String page = "/operator/clinics/" + clinic.getId() + "/billing";
        assertThat(get(operator, page)).hasStatusOk().bodyText().as("billed from the start: a month's trial on the general plan")
                .contains("Clinique Zahle", "Trial", "14 November 2026", "name=\"monthlyFee\"", "Record a payment")
                .doesNotContain("Not billed yet");
        assertThat(billing.subscription(clinic.getId()).orElseThrow().hasOwnTerms()).isFalse();

        assertThat(post(operator, page).param("monthlyFee", "50").param("periodMonths", "3").param("discount6", "10").param("discount12", "20")
                .param("messageCosts", "INCLUDED").param("paidUntil", "2026-11-01").param("graceDays", "14")
                .param("contactPhone", "70 123 456")).hasStatus3xxRedirection().hasRedirectedUrl(page);
        Subscription saved = billing.subscription(clinic.getId()).orElseThrow();
        assertThat(saved.getPaidUntil()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(saved.getContactPhone()).isEqualTo("+96170123456");

        assertThat(get(operator, page)).hasStatusOk().bodyText()
                .contains("Trial", "1 November 2026", "Record a payment", "$150.00", "$50.00", "$270.00", "$480.00", "until 1 February 2027")
                .as("nothing is due within a week: nobody to remind").doesNotContain("wa.me");

        assertThat(post(operator, page + "/payments").param("months", "3").param("method", "WHISH").param("reference", "W-88213")
                .param("paidOn", "2026-10-13")).hasStatus3xxRedirection().hasRedirectedUrl(page);
        SubscriptionPayment payment = billing.payments(clinic.getId()).getFirst();
        assertThat(payment.getAmountUsd()).isEqualByComparingTo("150");
        assertThat(payment.getRecordedBy()).isEqualTo("omar");
        assertThat(payment.getPaidOn()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(get(operator, page)).bodyText()
                .contains("Active", "1 February 2027", "W-88213", "/i/" + payment.getInvoiceToken(), "INV-2026-");

        assertThat(post(operator, page + "/payments/" + payment.getId() + "/delete")).hasStatus3xxRedirection();
        assertThat(billing.payments(clinic.getId())).isEmpty();
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getPaidUntil()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void theGeneralPlanIsSetOnceAndAClinicWithoutItsOwnTermsFollowsIt() {
        assertThat(post(operator, "/operator/billing/plan").param("monthlyFee", "60").param("discount6", "10")
                .param("discount12", "25").param("graceDays", "7").param("messageCosts", "ON_TOP"))
                .hasStatus3xxRedirection().hasRedirectedUrl("/operator/billing");
        assertThat(billing.plan()).isEqualTo(new Terms(new BigDecimal("60.00"), 10, 25, 7, MessageCosts.ON_TOP));

        String page = "/operator/clinics/" + clinic.getId() + "/billing";
        assertThat(get(operator, page)).hasStatusOk().bodyText().as("the plan shows through the empty fields")
                .contains("own terms", "placeholder=\"60.00\"", "placeholder=\"25\"", "placeholder=\"7\"",
                        "As the general plan: Added to each invoice");
        // Only what is the clinic's: how it pays, when, and who to remind.
        assertThat(post(operator, page).param("periodMonths", "12").param("paidUntil", "2026-11-01")).hasStatus3xxRedirection();
        Subscription saved = billing.subscription(clinic.getId()).orElseThrow();
        assertThat(saved.hasOwnTerms()).isFalse();
        assertThat(get(operator, page)).bodyText().as("1, 6 and 12 months by the plan").contains("$60.00", "$324.00", "$540.00");

        // Then a fee of its own; the rest still follows the plan.
        assertThat(post(operator, page).param("periodMonths", "12").param("paidUntil", "2026-11-01").param("monthlyFee", "40"))
                .hasStatus3xxRedirection();
        assertThat(billing.subscription(clinic.getId()).orElseThrow().getOwnMonthlyFeeUsd()).isEqualByComparingTo("40");
        assertThat(get(operator, page)).bodyText().contains("$40.00", "$216.00", "$360.00", "value=\"40.00\"");

        assertThat(post(operator, "/operator/billing/plan").param("monthlyFee", "sixty")).hasStatus3xxRedirection();
        assertThat(post(operator, "/operator/billing/plan").param("monthlyFee", "60").param("discount6", "150")).hasStatus3xxRedirection();
        assertThat(billing.plan().monthlyFeeUsd()).isEqualByComparingTo("60");
        assertThat(post(owner, "/operator/billing/plan").param("monthlyFee", "1")).hasStatus(403);
    }

    @Test
    void aBadFeeOrPhoneIsRefusedAndNothingIsSaved() {
        String page = "/operator/clinics/" + clinic.getId() + "/billing";
        assertThat(post(operator, page).param("monthlyFee", "fifty").param("paidUntil", "2026-11-01")).hasStatus3xxRedirection();
        assertThat(post(operator, page).param("monthlyFee", "50").param("paidUntil", "2026-11-01").param("contactPhone", "12"))
                .hasStatus3xxRedirection();
        assertThat(billing.subscription(clinic.getId())).isEmpty();
        assertThat(OperatorBillingController.amount("1,200.50")).isEqualByComparingTo("1200.50");
        assertThat(OperatorBillingController.amount("-3")).isNull();
        assertThat(OperatorBillingController.amount("")).isNull();
    }

    @Test
    void theOverviewListsClinicsByUrgencyWithIncomeAndAReminderLink() throws Exception {
        dueOn(LocalDate.of(2026, 10, 10));
        Clinic paid = data.clinic("Dr. Haddad");
        billing.savePlan(new BigDecimal("80"), 0, 0, 14, MessageCosts.INCLUDED);
        billing.save(paid.getId(), 1, TODAY, null, null, null, null, null, null, clock.instant());
        billing.record(paid.getId(), 1, null, Method.CASH, null, TODAY, "omar", clock.instant());
        data.clinic("New clinic");

        String html = get(operator, "/operator/billing").exchange().getResponse().getContentAsString();
        assertThat(html).contains("<h1>Billing</h1>", "Clinique Zahle", "Dr. Haddad", "Overdue", "Active", "New clinic", "Trial")
                .as("every clinic is billed").doesNotContain("Not billed yet")
                .as("collected this month, of what the three subscriptions bring a month").contains("$80.00", "$210.00")
                .as("the overdue clinic owes its usual three months").contains("$150.00")
                .as("the general plan, to change here").contains("General plan", "action=\"/operator/billing/plan\"", "value=\"80.00\"")
                .as("quick filters, each with its count: one due within a week? none; two never paid; all three quiet")
                .contains("name=\"soon\"", "name=\"unpaid\"", "name=\"quiet\"", "Subscription ends soon", "Never paid", "Gone quiet")
                .as("only the first clinic has terms of its own").containsOnlyOnce("class=\"tag own-terms\"")
                .as("a message for the operator's own WhatsApp, to the clinic's contact").contains("https://wa.me/96170123456?text=")
                .as("in the clinic's language").contains("Clinique%20Zahle", "%D8%AF%D9%88%D8%B1%D9%83");
        assertThat(html.indexOf("Clinique Zahle")).as("the late one first").isLessThan(html.indexOf("Dr. Haddad"));
        assertThat(html).contains("class=\"active\"").contains("href=\"/operator/billing\"");

        // Search and filters narrow the list; the figures above stay those of every clinic.
        assertThat(get(operator, "/operator/billing?q=hadd")).bodyText().contains("Clinics: 1 of 3", ">Dr. Haddad<", "$210.00")
                .doesNotContain(">Clinique Zahle<", ">New clinic<");
        assertThat(get(operator, "/operator/billing?status=overdue")).bodyText().contains(">Clinique Zahle<")
                .doesNotContain(">Dr. Haddad<", ">New clinic<");
        assertThat(get(operator, "/operator/billing?status=trial")).bodyText().contains(">New clinic<").doesNotContain(">Dr. Haddad<");
        assertThat(get(operator, "/operator/billing?terms=own")).bodyText().contains("Clinics: 1 of 3", ">Clinique Zahle<");
        assertThat(get(operator, "/operator/billing?terms=plan&q=clinic")).bodyText().contains("Clinics: 1 of 3", ">New clinic<");
        assertThat(get(operator, "/operator/billing?unpaid=true")).as("Dr. Haddad has paid").bodyText()
                .contains("Clinics: 2 of 3", ">Clinique Zahle<", ">New clinic<").doesNotContain(">Dr. Haddad<");
        assertThat(get(operator, "/operator/billing?soon=true")).bodyText().contains("Clinics: 0 of 3");
        billing.save(paid.getId(), 1, TODAY.plusDays(3), null, null, null, null, null, null, clock.instant());
        assertThat(get(operator, "/operator/billing?soon=true")).as("due in three days").bodyText()
                .contains("Clinics: 1 of 3", ">Dr. Haddad<").doesNotContain(">Clinique Zahle<");
        assertThat(get(operator, "/operator/billing?soon=true&unpaid=true")).as("both at once").bodyText().contains("Clinics: 0 of 3");
        assertThat(get(operator, "/operator/billing?status=paused")).bodyText().contains("Clinics: 0 of 3", "No clinic matches.");
    }

    @Test
    void billingIsTheOperatorsAndTheOwnersPageTheOwners() {
        dueOn(LocalDate.of(2026, 11, 1));
        assertThat(get(owner, "/operator/billing")).hasStatus(403);
        assertThat(get(secretary, "/operator/clinics/" + clinic.getId() + "/billing")).hasStatus(403);
        assertThat(get(secretary, "/billing")).hasStatus(403);
        assertThat(get(doctorLoginOf(data.doctor(clinic, "Dr. Khoury", 30)), "/billing")).hasStatus(403);
        assertThat(get(operator, "/operator/clinics/999999/billing")).hasStatus(404);
    }

    // --- The clinic ---

    @Test
    void theOwnerSeesWhatIsDueWhatItComesToAndTheirInvoices() {
        assertThat(get(owner, "/billing")).hasStatusOk().bodyText().as("a new clinic: a month's trial")
                .contains("Trial", "14 November 2026").doesNotContain("Nothing to pay yet.");

        dueOn(LocalDate.of(2026, 11, 1));
        SubscriptionPayment payment = billing.record(clinic.getId(), 1, null, Method.OMT, "T-1", TODAY, "omar", clock.instant());
        Clinic other = data.clinic("Other");
        billing.save(other.getId(), 1, TODAY, null, new BigDecimal("999"), null, null, null, null, clock.instant());

        assertThat(get(owner, "/billing")).hasStatusOk().bodyText()
                .contains("<h1>Billing</h1>", "Active", "1 December 2026", "$150.00", "$270.00", "/i/" + payment.getInvoiceToken())
                .as("read-only, and only their own clinic").doesNotContain("name=\"monthlyFee\"", "$999.00", "Record a payment");
    }

    @Test
    void anInvoiceOpensFromItsLinkWithoutLoggingInInTheClinicsLanguage() {
        dueOn(LocalDate.of(2026, 11, 1));
        SubscriptionPayment payment = billing.record(clinic.getId(), 6, new BigDecimal("100"), Method.WHISH, "W-9", TODAY, "omar",
                clock.instant());

        assertThat(mvc.get().uri("/i/" + payment.getInvoiceToken())).hasStatusOk().bodyText()
                .contains("dir=\"rtl\"", "Clinique Zahle", "INV-2026-", "$300.00", "$30.00", "$270.00", "$100.00", "W-9")
                .as("less than the total was received").contains("مدفوعة جزئيًا");
        // Someone using the dashboard reads it in their own language; the page's link switches it.
        assertThat(get(operator, "/i/" + payment.getInvoiceToken())).hasStatusOk().bodyText()
                .contains("dir=\"ltr\"", "Partly paid", "Balance due", "$170.00", "Billed to", "href=\"?lang=ar\"");
        assertThat(get(operator, "/i/" + payment.getInvoiceToken() + "?lang=ar")).bodyText().contains("dir=\"rtl\"", "href=\"?lang=en\"");
        assertThat(mvc.get().uri("/i/" + payment.getInvoiceToken() + "?lang=en")).bodyText().contains("dir=\"ltr\"", "Invoice");
        assertThat(mvc.get().uri("/i/00000000-0000-0000-0000-000000000000")).hasStatus(404);
        assertThat(mvc.get().uri("/i/not-a-token")).hasStatus(404);
    }

    @Test
    void theFrontDeskIsToldWhenAPaymentIsNearOrLateAndTheOwnerGetsTheLink() {
        assertThat(get(owner, "/week")).bodyText().doesNotContain("bill-banner");

        dueOn(LocalDate.of(2026, 12, 1));
        assertThat(get(owner, "/week")).as("weeks away").bodyText().doesNotContain("bill-banner");

        dueOn(LocalDate.of(2026, 10, 18));
        assertThat(get(owner, "/week")).bodyText().contains("bill-banner is-due-soon", "due on 18 October 2026", "href=\"/billing\"");
        assertThat(get(secretary, "/week")).bodyText().contains("bill-banner is-due-soon").doesNotContain("href=\"/billing\"");

        dueOn(LocalDate.of(2026, 10, 10));
        assertThat(get(owner, "/settings")).bodyText().contains("bill-banner is-overdue", "pause in 11 days");
        assertThat(get(owner, "/api/session")).bodyJson().extractingPath("$.billing.status").isEqualTo("overdue");
        assertThat(get(owner, "/api/session")).bodyJson().extractingPath("$.billing.owner").isEqualTo(true);

        StaffPrincipal doctor = doctorLoginOf(data.doctor(clinic, "Dr. Khoury", 30));
        assertThat(get(doctor, "/week")).as("not a doctor's business").bodyText().doesNotContain("bill-banner");
        assertThat(get(doctor, "/api/session")).bodyJson().extractingPath("$.billing").isNull();
        assertThat(get(operator, "/api/session")).bodyJson().extractingPath("$.billing").isNull();
    }

    @Test
    void onceTheGraceIsOverTheDeskCannotAddAppointmentsUntilItIsPaid() {
        Doctor khoury = data.doctor(clinic, "Dr. Khoury", 30);
        dueOn(LocalDate.of(2026, 9, 1));

        assertThat(get(secretary, "/week")).bodyText().contains("bill-banner is-paused");
        assertThat(book(khoury)).hasStatusOk().bodyText().contains("New appointments are paused");
        assertThat(jdbc.queryForObject("select count(*) from appointment", Integer.class)).isZero();

        billing.record(clinic.getId(), 3, null, Method.CASH, null, TODAY, "omar", clock.instant());
        assertThat(book(khoury)).hasStatus3xxRedirection();
        assertThat(jdbc.queryForObject("select count(*) from appointment", Integer.class)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult book(Doctor doctor) {
        return post(secretary, "/appointments").param("doctorId", String.valueOf(doctor.getId()))
                .param("date", "2026-10-15").param("otherTime", "10:00").param("phone", "70 123 456").param("name", "Rana Haddad")
                .param("language", "ar").exchange();
    }
}
