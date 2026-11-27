package app.clinicreminders.dashboard;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.OnlinePayments;
import app.clinicreminders.common.AppProperties;
import app.clinicreminders.billing.Billing.Quote;
import app.clinicreminders.billing.Standing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.billing.SubscriptionPayment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Billing as the pages show it: a clinic's standing with its "runway" (how much of the paid time is
 * used, and of the grace after it), what the next payment comes to, its payments, the notice across
 * the clinic's dashboard, and the reminder the operator sends from their own WhatsApp.
 */
@Component
public class BillingViews {

    /**
     * The bar across the clinic's dashboard when a payment is near or late.
     *
     * @param status due-soon, overdue or paused
     * @param days   days until the payment is due (due soon), or until new bookings pause (overdue)
     * @param owner  the owner gets a link to the Billing page
     */
    public record Notice(String status, String dueOn, long days, boolean owner) {
    }

    /**
     * @param elapsedPercent how much of the paid time is used (100 from the due day on)
     * @param gracePercent   how much of the grace after it is used
     * @param nextTotal      what a payment for the usual period comes to
     */
    record StandingView(String status, String dueOn, long daysLeft, long daysOverdue, long graceLeft, String pausesOn,
                        int elapsedPercent, int gracePercent, String nextTotal, String periodStart) {
    }

    /** One way to pay next: this many months, to this day, for this much. */
    record QuoteView(int months, String until, String fee, @Nullable String discount, @Nullable String messageCosts, String total,
                     String totalPlain, boolean usual) {
    }

    /** @param online paid by the owner with Whish, not entered by the operator */
    record PaymentView(long id, String number, String period, int months, String amount, String method, @Nullable String reference,
                       String paidOn, String invoiceUrl, boolean online) {
    }

    private final Billing billing;
    private final ClinicRepository clinics;
    private final MessageSource messages;
    private final Clock clock;
    private final String payTo;

    private final OnlinePayments onlinePayments;
    private final AppProperties app;

    BillingViews(Billing billing, ClinicRepository clinics, MessageSource messages, Clock clock,
                 @Value("${billing.pay-to:}") String payTo, OnlinePayments onlinePayments, AppProperties app) {
        this.onlinePayments = onlinePayments;
        this.app = app;
        this.billing = billing;
        this.clinics = clinics;
        this.messages = messages;
        this.clock = clock;
        this.payTo = payTo.strip();
    }

    /** How to pay, as the operator configured it (BILLING_PAY_TO); empty if not set. */
    String payTo() {
        return payTo;
    }

    /** The notice for this login's dashboard, if any: the front desk's, when a payment is near or late. */
    public @Nullable Notice notice(@Nullable StaffPrincipal staff, Language language) {
        if (staff == null || staff.clinicId() == null || staff.doctorId() != null) {
            return null;
        }
        long clinicId = staff.clinicId();
        Subscription subscription = billing.subscription(clinicId).orElse(null);
        Clinic clinic = subscription == null ? null : clinics.findById(clinicId).orElse(null);
        if (subscription == null || clinic == null) {
            return null;
        }
        Standing standing = billing.standing(subscription, today(clinic));
        if (!standing.needsNotice()) {
            return null;
        }
        boolean overdue = standing.status() == Standing.Status.OVERDUE;
        return new Notice(key(standing), date(standing.dueOn(), language), overdue ? standing.graceLeft() : standing.daysLeft(),
                staff.role().name().equals("OWNER"));
    }

    LocalDate today(Clinic clinic) {
        return LocalDate.ofInstant(clock.instant(), clinic.getTimezone());
    }

    StandingView standing(Clinic clinic, Subscription subscription, Language language) {
        LocalDate today = today(clinic);
        Standing standing = billing.standing(subscription, today);
        LocalDate start = periodStart(subscription);
        long length = Math.max(ChronoUnit.DAYS.between(start, standing.dueOn()), 1);
        int elapsed = (int) Math.clamp(100 * ChronoUnit.DAYS.between(start, today) / length, 0, 100);
        int graceDays = billing.terms(subscription).graceDays();
        int grace = graceDays == 0 ? (standing.daysLeft() <= 0 ? 100 : 0)
                : (int) Math.clamp(100 * standing.daysOverdue() / graceDays, 0, 100);
        Quote next = billing.quote(subscription, subscription.getPeriodMonths(), clock.instant());
        return new StandingView(key(standing), date(standing.dueOn(), language), standing.daysLeft(), standing.daysOverdue(),
                standing.graceLeft(), date(standing.pausesOn(), language), elapsed, grace, ReportView.dollars(next.total()),
                date(start, language));
    }

    /** What paying 1, 3, 6 or 12 months now would come to. */
    List<QuoteView> quotes(Subscription subscription, Language language) {
        Instant now = clock.instant();
        return Subscription.PERIODS.stream().sorted().map(months -> {
            Quote quote = billing.quote(subscription, months, now);
            return new QuoteView(months, date(quote.to(), language), ReportView.dollars(quote.fee()),
                    quote.discount().signum() > 0 ? ReportView.dollars(quote.discount()) : null,
                    quote.messageCosts().signum() > 0 ? ReportView.dollars(quote.messageCosts()) : null,
                    ReportView.dollars(quote.total()), quote.total().toPlainString(), months == subscription.getPeriodMonths());
        }).toList();
    }

    List<PaymentView> payments(long clinicId, Language language) {
        return billing.payments(clinicId).stream().map(payment -> payment(payment, language)).toList();
    }

    PaymentView payment(SubscriptionPayment payment, Language language) {
        return new PaymentView(payment.getId(), number(payment),
                date(payment.getPeriodStart(), language) + " – " + date(payment.getPeriodEnd().minusDays(1), language),
                payment.getMonths(), ReportView.dollars(payment.getAmountUsd()), payment.getMethod().name(), payment.getReference(),
                date(payment.getPaidOn(), language), "/i/" + payment.getInvoiceToken(),
                OnlinePayments.RECORDED_BY.equals(payment.getRecordedBy()));
    }

    /** "INV-2026-0042": the year it was paid in and the payment's number. */
    static String number(SubscriptionPayment payment) {
        return "INV-%d-%04d".formatted(payment.getPaidOn().getYear(), payment.getId());
    }

    /**
     * A link that opens the operator's own WhatsApp on a message to the clinic's billing contact, in
     * the clinic's language: what is due, when, and how to pay. Null without a contact number, or
     * when nothing is due yet. (A wa.me link: nothing is sent through the Cloud API.)
     */
    @Nullable String remindUrl(Clinic clinic, Subscription subscription) {
        String phone = subscription.getContactPhone();
        Standing standing = billing.standing(subscription, today(clinic));
        if (phone == null || !standing.needsNotice()) {
            return null;
        }
        Language language = clinic.getDefaultLanguage();
        Quote next = billing.quote(subscription, subscription.getPeriodMonths(), clock.instant());
        String text = messages.getMessage(standing.daysLeft() > 0 ? "billing.remind.due" : "billing.remind.overdue",
                new Object[] {clinic.getName(), date(standing.dueOn(), language), ReportView.dollars(next.total())}, language.locale());
        if (onlinePayments.available()) {
            text += " " + messages.getMessage("billing.remind.pay-online", new Object[] {app.link("/billing")}, language.locale());
        }
        if (!payTo.isEmpty()) {
            text += " " + messages.getMessage("billing.remind.pay-to", new Object[] {payTo}, language.locale());
        }
        return "https://wa.me/" + phone.replaceAll("\\D", "") + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    String date(LocalDate date, Language language) {
        return DateTimeFormatter.ofPattern(messages.getMessage("format.date-plain", null, language.locale()), language.locale()).format(date);
    }

    /** The first day of the time now paid for: the latest payment's, or one usual period before the due day. */
    private LocalDate periodStart(Subscription subscription) {
        return billing.payments(subscription.getClinicId()).stream().findFirst()
                .filter(payment -> payment.getPeriodEnd().equals(subscription.getPaidUntil()))
                .map(SubscriptionPayment::getPeriodStart)
                .orElseGet(() -> subscription.getPaidUntil().minusMonths(subscription.getPeriodMonths()));
    }

    static String key(Standing standing) {
        return standing.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
