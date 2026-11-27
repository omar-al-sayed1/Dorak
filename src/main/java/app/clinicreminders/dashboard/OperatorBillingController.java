package app.clinicreminders.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Billing.MonthIncome;
import app.clinicreminders.billing.Standing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.billing.Terms;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.dashboard.BillingViews.StandingView;
import app.clinicreminders.dashboard.ClinicSignals.Quick;
import app.clinicreminders.dashboard.ClinicSignals.Signals;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The operator's billing: every clinic's standing and the income at a glance, and per clinic its
 * subscription, what is due, recording a payment (which is also the clinic's invoice) and the
 * reminder to send from the operator's own WhatsApp. Operators only ({@code SecurityConfig}).
 */
@Controller
@RequestMapping("/operator")
class OperatorBillingController {

    /** Months of income drawn on the overview. */
    static final int CHART_MONTHS = 6;

    /**
     * A clinic on the overview; {@code standing} is null for one that is not billed yet.
     *
     * @param urgency sorts the list: paused first, then overdue, due soon, the rest by due day
     */
    record ClinicBill(long id, String name, String initials, int hue, @Nullable StandingView standing, @Nullable String monthly,
                      @Nullable Integer periodMonths, @Nullable String remindUrl, long urgency, boolean ownTerms,
                      Signals signals) {
    }

    /** A recent payment on the overview, any clinic's. */
    record LatestPayment(long clinicId, String clinic, String initials, int hue, BillingViews.PaymentView payment) {
    }

    /** One bar of the income chart. */
    record ChartBar(String month, String amount, int percent, boolean current) {
    }

    private final Billing billing;
    private final BillingViews views;
    private final ClinicSettings clinics;
    private final Avatars avatars;
    private final ClinicSignals signals;
    private final Clock clock;

    OperatorBillingController(Billing billing, BillingViews views, ClinicSettings clinics, Avatars avatars, Clock clock,
                              ClinicSignals signals) {
        this.signals = signals;
        this.billing = billing;
        this.views = views;
        this.clinics = clinics;
        this.avatars = avatars;
        this.clock = clock;
    }

    /**
     * @param q      narrows the clinics by name
     * @param status trial, active, due-soon, overdue or paused
     * @param terms  "plan" for clinics on the general plan, "own" for those with terms of their own
     */
    @GetMapping("/billing")
    String overview(HttpServletRequest request, Model model, @RequestParam(defaultValue = "") String q,
                    @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String terms,
                    // Quick filters ({@link ClinicSignals}); a clinic must match every one that is on.
                    @RequestParam(defaultValue = "false") boolean soon, @RequestParam(defaultValue = "false") boolean unpaid,
                    @RequestParam(defaultValue = "false") boolean quiet) {
        Language language = StaffLocaleResolver.language(request);
        billing.enrolAll(LocalDate.ofInstant(clock.instant(), Clinic.DEFAULT_ZONE), clock.instant());
        Map<Long, Subscription> subscriptions = billing.all().stream()
                .collect(Collectors.toMap(Subscription::getClinicId, Function.identity()));
        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal late = BigDecimal.ZERO;
        int lateClinics = 0;
        int dueSoon = 0;
        List<ClinicBill> bills = new java.util.ArrayList<>();
        List<Clinic> everyClinic = clinics.all();
        Map<Long, Signals> signals = this.signals.of(everyClinic, subscriptions);
        Quick quick = new Quick(soon, unpaid, quiet);
        for (Clinic clinic : everyClinic) {
            Subscription subscription = subscriptions.get(clinic.getId());
            if (subscription == null) {
                continue;
            }
            Standing standing = billing.standing(subscription, views.today(clinic));
            if (!standing.paused()) {
                expected = expected.add(billing.netMonthlyUsd(subscription));
            }
            if (standing.daysLeft() <= 0) {
                late = late.add(billing.quote(subscription, subscription.getPeriodMonths(), clock.instant()).total());
                lateClinics++;
            } else if (standing.status() == Standing.Status.DUE_SOON) {
                dueSoon++;
            }
            bills.add(new ClinicBill(clinic.getId(), clinic.getName(), avatars.initials(clinic.getName()),
                    avatars.hue(clinic.getName()), views.standing(clinic, subscription, language),
                    ReportView.dollars(billing.netMonthlyUsd(subscription)), subscription.getPeriodMonths(),
                    views.remindUrl(clinic, subscription), standing.daysLeft(), subscription.hasOwnTerms(),
                    signals.get(clinic.getId())));
        }
        bills.sort(Comparator.comparingLong(ClinicBill::urgency).thenComparing(ClinicBill::name));

        YearMonth thisMonth = YearMonth.from(LocalDate.ofInstant(clock.instant(), Clinic.DEFAULT_ZONE));
        List<MonthIncome> income = billing.income(thisMonth, CHART_MONTHS);
        BigDecimal most = income.stream().map(MonthIncome::collected).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        model.addAttribute("chart", income.stream().map(month -> new ChartBar(
                month.month().getMonth().getDisplayName(TextStyle.SHORT, language.locale()), ReportView.dollars(month.collected()),
                ReportView.percent(month.collected(), most), month.month().equals(thisMonth))).toList());
        model.addAttribute("collected", ReportView.dollars(income.getLast().collected()));
        model.addAttribute("expected", ReportView.dollars(expected));
        model.addAttribute("collectedPercent", ReportView.percent(income.getLast().collected(), expected));
        model.addAttribute("late", ReportView.dollars(late));
        model.addAttribute("lateClinics", lateClinics);
        model.addAttribute("dueSoon", dueSoon);
        String wanted = q.strip().toLowerCase(Locale.ROOT);
        model.addAttribute("bills", bills.stream()
                .filter(bill -> bill.name().toLowerCase(Locale.ROOT).contains(wanted))
                .filter(bill -> quick.matches(bill.signals()))
                .filter(bill -> status.isEmpty() || bill.standing().status().equals(status))
                .filter(bill -> switch (terms) {
                    case "own" -> bill.ownTerms();
                    case "plan" -> !bill.ownTerms();
                    default -> true;
                })
                .toList());
        model.addAttribute("total", bills.size());
        model.addAttribute("q", q.strip());
        model.addAttribute("status", status);
        model.addAttribute("terms", terms);
        model.addAttribute("filtered", !wanted.isEmpty() || !(status + terms).isEmpty() || quick.any());
        model.addAttribute("quick", quick);
        model.addAttribute("quickCounts", ClinicSignals.counts(signals));
        model.addAttribute("statuses", List.of("trial", "active", "due-soon", "overdue", "paused"));
        model.addAttribute("plan", billing.plan());
        model.addAttribute("planNoWhatsApp", billing.plan(false));
        Map<Long, Clinic> byId = everyClinic.stream().collect(Collectors.toMap(Clinic::getId, Function.identity()));
        model.addAttribute("latest", billing.latestPayments().stream()
                .filter(payment -> byId.containsKey(payment.getClinicId()))
                .map(payment -> {
                    Clinic clinic = byId.get(payment.getClinicId());
                    return new LatestPayment(clinic.getId(), clinic.getName(), avatars.initials(clinic.getName()),
                            avatars.hue(clinic.getName()), views.payment(payment, language));
                })
                .toList());
        model.addAttribute("maxGrace", Terms.MAX_GRACE_DAYS);
        return "dashboard/operator-billing";
    }

    /** The general plan: what every clinic pays unless its own subscription says otherwise. */
    @PostMapping("/billing/plan")
    String savePlan(RedirectAttributes redirect, @RequestParam(defaultValue = "") String monthlyFee,
                    @RequestParam(defaultValue = "") String monthlyFeeNoWhatsApp,
                    @RequestParam(defaultValue = "0") String discount6, @RequestParam(defaultValue = "0") String discount12,
                    @RequestParam(defaultValue = "" + Terms.DEFAULT_GRACE_DAYS) String graceDays,
                    @RequestParam(defaultValue = "INCLUDED") MessageCosts messageCosts) {
        try {
            BigDecimal fee = amount(monthlyFee);
            // Left out (an older form, a script): the fee without WhatsApp stays as it is.
            BigDecimal feeNoWhatsApp = monthlyFeeNoWhatsApp.isBlank() ? billing.plan(false).monthlyFeeUsd() : amount(monthlyFeeNoWhatsApp);
            Integer off6 = whole(discount6);
            Integer off12 = whole(discount12);
            Integer grace = whole(graceDays);
            if (fee == null || feeNoWhatsApp == null || off6 == null || off12 == null || grace == null) {
                throw new IllegalArgumentException("plan");
            }
            billing.savePlan(fee, feeNoWhatsApp, off6, off12, grace, messageCosts);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.billing");
        }
        return "redirect:/operator/billing";
    }

    @GetMapping("/clinics/{id}/billing")
    String clinic(@PathVariable long id, HttpServletRequest request, Model model) {
        Language language = StaffLocaleResolver.language(request);
        Clinic clinic = clinics.get(id);
        LocalDate today = views.today(clinic);
        billing.enrolAll(today, clock.instant());
        Subscription subscription = billing.subscription(id).orElse(null);
        model.addAttribute("clinic", clinic);
        model.addAttribute("initials", avatars.initials(clinic.getName()));
        model.addAttribute("hue", avatars.hue(clinic.getName()));
        model.addAttribute("subscription", subscription);
        model.addAttribute("today", today);
        // A new clinic: a month's trial, paying a month at a time.
        model.addAttribute("defaultDue", today.plusMonths(1));
        model.addAttribute("periods", Subscription.PERIODS.stream().sorted().toList());
        model.addAttribute("methods", Method.values());
        model.addAttribute("maxGrace", Terms.MAX_GRACE_DAYS);
        // The clinic's own empty fields show the plan it is on: with or without WhatsApp.
        Terms plan = billing.plan(clinic.isWhatsAppEnabled());
        model.addAttribute("plan", plan);
        model.addAttribute("planFee", ReportView.dollars(plan.monthlyFeeUsd()));
        model.addAttribute("payTo", views.payTo());
        if (subscription != null) {
            model.addAttribute("standing", views.standing(clinic, subscription, language));
            model.addAttribute("quotes", views.quotes(subscription, language));
            model.addAttribute("payments", views.payments(id, language));
            model.addAttribute("remindUrl", views.remindUrl(clinic, subscription));
        }
        return "dashboard/operator-clinic-billing";
    }

    @PostMapping("/clinics/{id}/billing")
    String save(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "1") int periodMonths,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidUntil,
                @RequestParam(defaultValue = "") String contactPhone,
                // The clinic's own terms: an empty field leaves that one to the general plan.
                @RequestParam(defaultValue = "") String monthlyFee, @RequestParam(defaultValue = "") String discount6,
                @RequestParam(defaultValue = "") String discount12, @RequestParam(defaultValue = "") String graceDays,
                @RequestParam(defaultValue = "") String messageCosts) {
        clinics.get(id);
        String phone = contactPhone.isBlank() ? null : PhoneNumbers.parseLebanese(contactPhone);
        if (!contactPhone.isBlank() && phone == null) {
            redirect.addFlashAttribute("error", "error.phone");
            return redirectTo(id);
        }
        try {
            billing.save(id, periodMonths, paidUntil, phone, optional(monthlyFee, amount(monthlyFee)),
                    optional(discount6, whole(discount6)), optional(discount12, whole(discount12)),
                    optional(graceDays, whole(graceDays)), messageCosts.isBlank() ? null : MessageCosts.valueOf(messageCosts),
                    clock.instant());
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.billing");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/billing/payments")
    String record(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal operator, RedirectAttributes redirect,
                  @RequestParam int months, @RequestParam(defaultValue = "") String amount, @RequestParam Method method,
                  @RequestParam(defaultValue = "") String reference,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn) {
        Clinic clinic = clinics.get(id);
        BigDecimal received = amount(amount);
        if (!amount.isBlank() && received == null) {
            redirect.addFlashAttribute("error", "error.billing");
            return redirectTo(id);
        }
        LocalDate today = views.today(clinic);
        try {
            billing.record(id, months, received, method, reference, paidOn == null || paidOn.isAfter(today) ? today : paidOn,
                    operator.username(), clock.instant());
            redirect.addFlashAttribute("notice", "notice.billing.recorded");
        } catch (IllegalArgumentException | NotFoundException e) {
            redirect.addFlashAttribute("error", "error.billing");
        }
        return redirectTo(id);
    }

    /** Takes back the latest payment, recorded by mistake. An older one is refused ({@code DashboardModel}). */
    @PostMapping("/clinics/{id}/billing/payments/{paymentId}/delete")
    String undo(@PathVariable long id, @PathVariable long paymentId, RedirectAttributes redirect) {
        clinics.get(id);
        try {
            billing.undo(id, paymentId);
            redirect.addFlashAttribute("notice", "notice.billing.undone");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", "error.not-allowed");
        }
        return redirectTo(id);
    }

    /** "50", "49.5", "1,200": null for anything else. */
    static @Nullable BigDecimal amount(String typed) {
        String digits = typed.strip().replace(",", "").replace("$", "");
        if (!digits.matches("\\d{1,7}(\\.\\d{1,2})?")) {
            return null;
        }
        return new BigDecimal(digits);
    }

    /** "10": null for anything but a whole number of up to three digits. */
    static @Nullable Integer whole(String typed) {
        return typed.strip().matches("\\d{1,3}") ? Integer.valueOf(typed.strip()) : null;
    }

    /** Nothing typed is null ("as the general plan"); something typed that could not be read is refused. */
    private static <T> @Nullable T optional(String typed, @Nullable T read) {
        if (!typed.isBlank() && read == null) {
            throw new IllegalArgumentException(typed);
        }
        return typed.isBlank() ? null : read;
    }

    private static String redirectTo(long clinicId) {
        return "redirect:/operator/clinics/" + clinicId + "/billing";
    }
}
