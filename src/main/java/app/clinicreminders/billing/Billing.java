package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What clinics pay the operator. One general plan (the fee, discounts for paying 6 months or a year
 * at once, the grace, message costs) holds for every clinic, and a clinic's subscription can change
 * any of it for that clinic. The operator sets a clinic's subscription and records its payments
 * by hand (cash, Whish, OMT, bank); each payment moves the day the next one is due and is also the
 * clinic's invoice. From that day the clinic's {@link Standing} follows: due soon, overdue and,
 * after the days of grace, paused, which stops new bookings only. Every clinic has a subscription
 * ({@link #enrolAll}); one that somehow has none is not billed.
 */
@Service
public class Billing {

    /**
     * What a payment for {@code months} months comes to.
     *
     * @param from         the first day it covers (the day the payment is due)
     * @param to           the day the next payment would be due
     * @param messageCosts Meta's estimated message costs not yet invoiced; zero when they are part of the fee
     */
    public record Quote(int months, LocalDate from, LocalDate to, BigDecimal fee, BigDecimal discount, BigDecimal messageCosts) {

        public BigDecimal total() {
            return fee.subtract(discount).add(messageCosts);
        }
    }

    /** One month of income: what the clinics paid in it. */
    public record MonthIncome(YearMonth month, BigDecimal collected) {
    }

    /** A new clinic's first payment is due this long after it starts. */
    public static final int TRIAL_MONTHS = 1;

    private final BillingPlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionPaymentRepository payments;
    private final JdbcClient jdbc;

    Billing(BillingPlanRepository plans, SubscriptionRepository subscriptions, SubscriptionPaymentRepository payments,
            JdbcClient jdbc) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    /** The general plan: what a clinic pays unless its own subscription says otherwise. */
    @Transactional(readOnly = true)
    public Terms plan() {
        return thePlan().terms();
    }

    /** @throws IllegalArgumentException see {@link Terms#check} */
    @Transactional
    public void savePlan(BigDecimal monthlyFeeUsd, int discount6Percent, int discount12Percent, int graceDays,
                         MessageCosts messageCosts) {
        BillingPlan plan = thePlan();
        plan.change(monthlyFeeUsd, discount6Percent, discount12Percent, graceDays, messageCosts);
        plans.save(plan);
    }

    /** What this clinic is billed by: the general plan with the clinic's own terms over it. */
    @Transactional(readOnly = true)
    public Terms terms(Subscription subscription) {
        return subscription.terms(plan());
    }

    private BillingPlan thePlan() {
        return plans.findById(BillingPlan.ID).orElseThrow(() -> new IllegalStateException("billing_plan has no row"));
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> subscription(long clinicId) {
        return subscriptions.findByClinicId(clinicId);
    }

    /**
     * Every clinic is billed: gives a subscription on the general plan to each clinic that has none
     * yet (a new one, the demo). Its first payment is due a month from today; it pays a month at a time.
     */
    @Transactional
    public void enrolAll(LocalDate today, Instant now) {
        jdbc.sql("""
                        insert into subscription (clinic_id, period_months, paid_until, costs_from)
                        select c.id, 1, :due, :now from clinic c
                        where not exists (select 1 from subscription s where s.clinic_id = c.id)
                        on conflict (clinic_id) do nothing""")
                .param("due", today.plusMonths(TRIAL_MONTHS))
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** Every clinic's subscription: the operator's. */
    @Transactional(readOnly = true)
    public List<Subscription> all() {
        return subscriptions.findAll();
    }

    /** Where the clinic stands today; empty for a clinic that is not billed. */
    @Transactional(readOnly = true)
    public Optional<Standing> standing(long clinicId, LocalDate today) {
        return subscriptions.findByClinicId(clinicId).map(subscription -> standing(subscription, today));
    }

    public Standing standing(Subscription subscription, LocalDate today) {
        return Standing.of(subscription.getPaidUntil(), terms(subscription).graceDays(),
                payments.existsByClinicId(subscription.getClinicId()), today);
    }

    /** True once the clinic has paid at least once. */
    @Transactional(readOnly = true)
    public boolean hasPaid(long clinicId) {
        return payments.existsByClinicId(clinicId);
    }

    /** True once the grace after an unpaid due day is over: no new bookings for this clinic. */
    @Transactional(readOnly = true)
    public boolean paused(long clinicId, LocalDate today) {
        return standing(clinicId, today).map(Standing::paused).orElse(false);
    }

    /**
     * Sets or changes the clinic's subscription. {@code paidUntil} is the day the next payment is
     * due: the end of the trial for a new clinic; changing it later moves the due day by hand. The
     * fee, discounts, grace and message costs are the clinic's own; null leaves that one to the
     * general plan.
     *
     * @throws IllegalArgumentException see {@link Subscription#change} and {@link Terms#check}
     */
    @Transactional
    public Subscription save(long clinicId, int periodMonths, LocalDate paidUntil, @Nullable String contactPhone,
                             @Nullable BigDecimal monthlyFeeUsd, @Nullable Integer discount6Percent,
                             @Nullable Integer discount12Percent, @Nullable Integer graceDays, @Nullable MessageCosts messageCosts,
                             Instant now) {
        Subscription subscription = subscriptions.findByClinicId(clinicId).orElseGet(() -> new Subscription(clinicId, now));
        subscription.change(periodMonths, paidUntil, contactPhone);
        subscription.changeOwnTerms(monthlyFeeUsd, discount6Percent, discount12Percent, graceDays, messageCosts);
        return subscriptions.save(subscription);
    }

    /** What a month brings from this clinic, paying its usual period at a time. */
    @Transactional(readOnly = true)
    public BigDecimal netMonthlyUsd(Subscription subscription) {
        return terms(subscription).netMonthlyUsd(subscription.getPeriodMonths());
    }

    /**
     * What the clinic's next payment comes to for this many months: the fee, less the discount for
     * paying 6 months or a year at once, plus the message costs not yet invoiced.
     */
    @Transactional(readOnly = true)
    public Quote quote(Subscription subscription, int months, Instant now) {
        if (months < 1 || months > 36) {
            throw new IllegalArgumentException("months");
        }
        Terms terms = terms(subscription);
        BigDecimal fee = terms.monthlyFeeUsd().multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = fee.multiply(BigDecimal.valueOf(terms.discountPercent(months)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal costs = terms.messageCosts() == MessageCosts.ON_TOP
                ? messageCosts(subscription.getClinicId(), subscription.getCostsFrom(), now)
                : BigDecimal.ZERO.setScale(2);
        LocalDate from = subscription.getPaidUntil();
        return new Quote(months, from, from.plusMonths(months), fee, discount, costs);
    }

    /**
     * Records a payment: the next one is due {@code months} months after this one was, and the
     * message costs up to now count as invoiced.
     *
     * @param amountUsd what was received; null for exactly what the quote asks
     * @throws NotFoundException        if the clinic has no subscription
     * @throws IllegalArgumentException for a negative amount or months outside 1 to 36
     */
    @Transactional
    public SubscriptionPayment record(long clinicId, int months, @Nullable BigDecimal amountUsd, Method method,
                                      @Nullable String reference, LocalDate paidOn, String by, Instant now) {
        Subscription subscription = subscriptions.findByClinicId(clinicId)
                .orElseThrow(() -> new NotFoundException("Subscription", clinicId));
        Quote quote = quote(subscription, months, now);
        BigDecimal amount = amountUsd == null ? quote.total() : amountUsd.setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() < 0 || amount.precision() - amount.scale() > 8) {
            throw new IllegalArgumentException("amount");
        }
        String cleanReference = reference == null || reference.isBlank() ? null
                : reference.strip().substring(0, Math.min(reference.strip().length(), 80));
        SubscriptionPayment payment = payments.save(new SubscriptionPayment(clinicId, quote, amount, method, cleanReference,
                paidOn, subscription.getCostsFrom(), by, now));
        subscription.paidUntil(quote.to(), now);
        subscriptions.save(subscription);
        return payment;
    }

    /**
     * Takes back the clinic's latest payment (recorded by mistake): the due day and the uninvoiced
     * message costs go back to where they were before it.
     *
     * @throws NotFoundException     if the payment is not this clinic's
     * @throws IllegalStateException if it is not the clinic's latest
     */
    @Transactional
    public void undo(long clinicId, long paymentId) {
        SubscriptionPayment latest = payments.findFirstByClinicIdOrderByPeriodEndDescIdDesc(clinicId)
                .orElseThrow(() -> new NotFoundException("Subscription payment", paymentId));
        if (payments.findByClinicIdOrderByPeriodEndDescIdDesc(clinicId).stream().noneMatch(p -> p.getId() == paymentId)) {
            throw new NotFoundException("Subscription payment", paymentId);
        }
        if (latest.getId() != paymentId) {
            throw new IllegalStateException("Only the latest payment can be undone");
        }
        Subscription subscription = subscriptions.findByClinicId(clinicId)
                .orElseThrow(() -> new NotFoundException("Subscription", clinicId));
        subscription.paidUntil(latest.getPeriodStart(), latest.getCostsFrom());
        subscriptions.save(subscription);
        payments.delete(latest);
    }

    /** The clinic's payments, latest first. */
    @Transactional(readOnly = true)
    public List<SubscriptionPayment> payments(long clinicId) {
        return payments.findByClinicIdOrderByPeriodEndDescIdDesc(clinicId);
    }

    /** The payment behind a public invoice link. */
    @Transactional(readOnly = true)
    public Optional<SubscriptionPayment> invoice(UUID token) {
        return payments.findByInvoiceToken(token);
    }

    /** What every clinic paid in each of the {@code count} months up to and including {@code last}, oldest first. */
    @Transactional(readOnly = true)
    public List<MonthIncome> income(YearMonth last, int count) {
        YearMonth first = last.minusMonths(count - 1L);
        List<SubscriptionPayment> paid = payments.findByPaidOnGreaterThanEqualAndPaidOnLessThan(first.atDay(1),
                last.plusMonths(1).atDay(1));
        List<MonthIncome> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            YearMonth current = month;
            months.add(new MonthIncome(month, paid.stream()
                    .filter(payment -> YearMonth.from(payment.getPaidOn()).equals(current))
                    .map(SubscriptionPayment::getAmountUsd)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)));
        }
        return months;
    }

    /** How many clinics paid something in the month. */
    @Transactional(readOnly = true)
    public long payingClinics(YearMonth month) {
        return payments.findByPaidOnGreaterThanEqualAndPaidOnLessThan(month.atDay(1), month.plusMonths(1).atDay(1)).stream()
                .map(SubscriptionPayment::getClinicId).distinct().count();
    }

    /**
     * Meta's estimated cost of every clinic's messages, month by month (months as {@code zone} has
     * them); failed sends cost nothing. A month without messages is left out. The operator's.
     */
    @Transactional(readOnly = true)
    public Map<YearMonth, BigDecimal> messageCostsByMonth(YearMonth first, YearMonth last, ZoneId zone) {
        Map<YearMonth, BigDecimal> costs = new HashMap<>();
        jdbc.sql("""
                        select to_char(created_at at time zone :zone, 'YYYY-MM') as month, coalesce(sum(est_cost_usd), 0) as cost
                        from message_log
                        where direction = 'OUT' and status is distinct from 'FAILED' and created_at >= :from and created_at < :to
                        group by 1""")
                .param("zone", zone.getId())
                .param("from", first.atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .param("to", last.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime())
                .query((row, n) -> costs.put(YearMonth.parse(row.getString("month")), row.getBigDecimal("cost")))
                .list();
        return costs;
    }

    /** Meta's estimated cost of the messages the clinic sent in a stretch of time; failed sends cost nothing. */
    private BigDecimal messageCosts(long clinicId, Instant from, Instant to) {
        BigDecimal sum = jdbc.sql("""
                        select coalesce(sum(est_cost_usd), 0) from message_log
                        where clinic_id = :clinicId and direction = 'OUT' and status is distinct from 'FAILED'
                          and created_at >= :from and created_at < :to""")
                .param("clinicId", clinicId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(BigDecimal.class)
                .single();
        return sum.setScale(2, RoundingMode.HALF_UP);
    }
}
