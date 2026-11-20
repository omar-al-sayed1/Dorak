package app.clinicreminders.dashboard;

import java.time.Clock;
import java.util.UUID;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.billing.SubscriptionPayment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Billing as the clinic sees it. The owner's page ({@code /billing}, owners only, SecurityConfig):
 * where the subscription stands, what is due and how to pay, and past invoices. And an invoice's
 * public page ({@code /i/{token}}), printable, opened from a link: the random token is the only key.
 */
@Controller
class BillingPageController {

    /** An invoice as its page shows it; amounts already formatted. {@code due} is what is left when less than the total was paid. */
    record InvoiceView(String number, String clinic, @Nullable String address, String period, int months, String fee,
                       @Nullable String discount, @Nullable String messageCosts, String total, String paid, boolean paidInFull,
                       String method, @Nullable String reference, String paidOn, @Nullable String due) {
    }

    private final Billing billing;
    private final BillingViews views;
    private final ClinicSettings clinics;
    private final Clock clock;

    BillingPageController(Billing billing, BillingViews views, ClinicSettings clinics, Clock clock) {
        this.clock = clock;
        this.billing = billing;
        this.views = views;
        this.clinics = clinics;
    }

    @GetMapping("/billing")
    String billing(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Language language = StaffLocaleResolver.language(request);
        Clinic clinic = clinics.get(staff.requireClinicId());
        billing.enrolAll(views.today(clinic), clock.instant());
        Subscription subscription = billing.subscription(clinic.getId()).orElse(null);
        model.addAttribute("subscription", subscription);
        model.addAttribute("payTo", views.payTo());
        if (subscription != null) {
            model.addAttribute("standing", views.standing(clinic, subscription, language));
            model.addAttribute("quotes", views.quotes(subscription, language));
            model.addAttribute("payments", views.payments(clinic.getId(), language));
            model.addAttribute("monthly", ReportView.dollars(billing.terms(subscription).monthlyFeeUsd()));
        }
        return "dashboard/billing";
    }

    @GetMapping("/i/{token}")
    String invoice(@PathVariable String token, HttpServletRequest request, HttpServletResponse response, Model model) {
        SubscriptionPayment payment = billing.invoice(parse(token)).orElseThrow(() -> new NotFoundException("Invoice", token));
        Clinic clinic = clinics.get(payment.getClinicId());
        // The reader's language if they use the dashboard, else the clinic's; ?lang= switches it.
        Language language = StaffLocaleResolver.forPage(request, clinic.getDefaultLanguage());
        model.addAttribute("lang", language.code());
        model.addAttribute("dir", language == Language.AR ? "rtl" : "ltr");
        model.addAttribute("switchLang", language == Language.AR ? "en" : "ar");
        response.setHeader("X-Robots-Tag", "noindex");
        response.setHeader("Cache-Control", "private, no-store");
        model.addAttribute("invoice", new InvoiceView(BillingViews.number(payment), clinic.getName(), clinic.getAddress(),
                views.date(payment.getPeriodStart(), language) + " – " + views.date(payment.getPeriodEnd().minusDays(1), language),
                payment.getMonths(), ReportView.dollars(payment.getFeeUsd()),
                payment.getDiscountUsd().signum() > 0 ? ReportView.dollars(payment.getDiscountUsd()) : null,
                payment.getMessageCostsUsd().signum() > 0 ? ReportView.dollars(payment.getMessageCostsUsd()) : null,
                ReportView.dollars(payment.totalUsd()), ReportView.dollars(payment.getAmountUsd()),
                payment.getAmountUsd().compareTo(payment.totalUsd()) >= 0, payment.getMethod().name(), payment.getReference(),
                views.date(payment.getPaidOn(), language),
                payment.getAmountUsd().compareTo(payment.totalUsd()) >= 0 ? null
                        : ReportView.dollars(payment.totalUsd().subtract(payment.getAmountUsd()))));
        return "dashboard/invoice";
    }

    private static UUID parse(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invoice", token);
        }
    }
}
