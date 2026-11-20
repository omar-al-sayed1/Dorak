package app.clinicreminders.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.clinicreminders.balance.BalanceProblem;
import app.clinicreminders.balance.Balances;
import app.clinicreminders.balance.Balances.Day;
import app.clinicreminders.balance.Balances.Ledger;
import app.clinicreminders.balance.Balances.PaymentEntry;
import app.clinicreminders.balance.Balances.Visit;
import app.clinicreminders.balance.Currency;
import app.clinicreminders.balance.DayClose;
import app.clinicreminders.balance.MoneyStatus;
import app.clinicreminders.balance.Payment;
import app.clinicreminders.balance.PaymentMethod;
import app.clinicreminders.common.AppProperties;
import app.clinicreminders.dashboard.Avatars;
import app.clinicreminders.dashboard.ClinicContext;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Balance (SPEC section 5.6) for the React dashboard: a visit's fee and payments, a patient's
 * account, who owes, and the day's takings and drawer. The front
 * desk's only (SecurityConfig): money is the clinic's. Amounts are US dollars; dates and times
 * come formatted in the clinic's zone and the user's language.
 */
@RestController
class BalanceApi {

    record PaymentJson(long id, BigDecimal amountUsd, PaymentMethod method, @Nullable BigDecimal amountLbp, String paidAt,
                       String receiptUrl) {
    }

    record VisitJson(long appointmentId, long patientId, String patientName, String doctor, String when,
                     MoneyStatus status, @Nullable BigDecimal charged, BigDecimal paid, BigDecimal due,
                     BigDecimal patientBalance, BigDecimal familyBalance, int familySize, List<PaymentJson> payments) {
    }

    record PaymentRequest(BigDecimal amount, Currency currency, PaymentMethod method) {
    }

    record VisitRequest(BigDecimal fee, @Nullable PaymentRequest payment) {
    }

    record Recorded(VisitJson visit, @Nullable String receiptUrl) {
    }

    record EntryJson(String type, String date, BigDecimal amountUsd, @Nullable String visit, @Nullable String doctor,
                     @Nullable PaymentMethod method, @Nullable BigDecimal amountLbp, @Nullable Long paymentId,
                     @Nullable String receiptUrl) {
    }

    record MemberJson(long patientId, String name, String initials, int hue, BigDecimal balance) {
    }

    record LedgerJson(long patientId, String name, String phone, String initials, int hue, BigDecimal balance,
                      List<EntryJson> entries, List<MemberJson> family, BigDecimal familyBalance) {
    }

    enum AccountKind { OWES, PAID }

    /** Add Payment: an amount the patient owes, or one they paid, on their account rather than for a visit. */
    record AccountRequest(AccountKind kind, BigDecimal amount, @Nullable Currency currency, @Nullable PaymentMethod method) {
    }

    record AccountRecorded(BigDecimal balance, @Nullable String receiptUrl) {
    }

    record OwingJson(long patientId, String name, String phone, String initials, int hue, BigDecimal balance, String since,
                     long days) {
    }

    record OwesJson(BigDecimal total, List<OwingJson> patients) {
    }

    record DayPaymentJson(long id, String time, String patient, BigDecimal amountUsd, PaymentMethod method,
                          @Nullable BigDecimal amountLbp, String receiptUrl) {
    }

    record CloseJson(BigDecimal expected, BigDecimal counted, BigDecimal difference, String closedBy, String closedAt) {
    }

    record DayJson(String date, String dateLabel, boolean today, String previousDate, String nextDate,
                   List<DayPaymentJson> payments, Map<PaymentMethod, BigDecimal> byMethod, BigDecimal total,
                   BigDecimal expectedCash, @Nullable CloseJson close) {
    }

    record CloseRequest(LocalDate date, BigDecimal countedCash) {
    }

    private final ClinicContext context;
    private final Balances balances;
    private final DoctorRepository doctors;
    private final Avatars avatars;
    private final AppProperties app;

    BalanceApi(ClinicContext context, Balances balances, DoctorRepository doctors, Avatars avatars, AppProperties app) {
        this.context = context;
        this.balances = balances;
        this.doctors = doctors;
        this.avatars = avatars;
        this.app = app;
    }

    // --- A visit ---

    @GetMapping("/api/money/appointments/{id}")
    VisitJson visit(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        return visitJson(ctx, balances.visit(ctx.clinicId(), id));
    }

    /** The visit's fee and, if given, a payment for it ("Later": a fee with no payment). */
    @PostMapping("/api/money/appointments/{id}")
    Recorded record(@PathVariable long id, @RequestBody VisitRequest body, @AuthenticationPrincipal StaffPrincipal staff,
                    HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        PaymentRequest payment = body.payment();
        String receipt = balances.record(ctx.clinicId(), id, body.fee(),
                        payment == null ? null : new PaymentEntry(payment.amount(), payment.currency(), payment.method()),
                        ctx.now())
                .map(this::receiptUrl).orElse(null);
        return new Recorded(visitJson(ctx, balances.visit(ctx.clinicId(), id)), receipt);
    }

    @DeleteMapping("/api/money/payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePayment(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        balances.deletePayment(context.of(staff, request).clinicId(), id);
    }

    // --- A patient, and who owes ---

    @GetMapping("/api/money/patients/{id}")
    LedgerJson ledger(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        Ledger ledger = balances.ledger(ctx.clinicId(), id);
        return new LedgerJson(ledger.patientId(), ledger.name(), ledger.phone(), avatars.initials(ledger.name()),
                avatars.hue(ledger.name()), ledger.balance(),
                ledger.entries().stream().map(entry -> new EntryJson(entry.type().name(),
                        ctx.formats().dateTime(entry.at()), entry.amountUsd(),
                        entry.appointmentStart() == null ? null : ctx.formats().dateTime(entry.appointmentStart()),
                        entry.doctor(), entry.method(), entry.amountLbp(), entry.paymentId(),
                        entry.receiptToken() == null ? null : app.link("/r/" + entry.receiptToken()))).toList(),
                ledger.family().stream().map(member -> new MemberJson(member.patientId(), member.name(),
                        avatars.initials(member.name()), avatars.hue(member.name()), member.balance())).toList(),
                ledger.familyBalance());
    }

    @PostMapping("/api/money/patients/{id}")
    AccountRecorded addToAccount(@PathVariable long id, @RequestBody AccountRequest body,
                                 @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        String receipt = null;
        if (body.kind() == AccountKind.OWES) {
            balances.addOwed(ctx.clinicId(), id, body.amount(), ctx.now());
        } else {
            if (body.method() == null) {
                throw new BalanceProblem("error.amount");
            }
            Currency currency = body.currency() == null ? Currency.USD : body.currency();
            receipt = receiptUrl(balances.payOnAccount(ctx.clinicId(), id,
                    new PaymentEntry(body.amount(), currency, body.method()), ctx.now()));
        }
        return new AccountRecorded(balances.balanceOf(ctx.clinicId(), id), receipt);
    }

    /** Collected this month so far, and in all of last month: the Balance page's line. */
    record MonthJson(String month, BigDecimal collected, String lastMonth, BigDecimal lastCollected) {
    }

    @GetMapping("/api/money/month")
    MonthJson month(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        LocalDate first = ctx.today().withDayOfMonth(1);
        LocalDate previous = first.minusMonths(1);
        BigDecimal now = balances.month(ctx.clinicId(), ctx.startOf(first), ctx.startOf(first.plusMonths(1))).collected();
        BigDecimal before = balances.month(ctx.clinicId(), ctx.startOf(previous), ctx.startOf(first)).collected();
        java.time.format.DateTimeFormatter name = java.time.format.DateTimeFormatter.ofPattern("LLLL", ctx.language().locale());
        return new MonthJson(name.format(first), now, name.format(previous), before);
    }

    @GetMapping("/api/money/owes")
    OwesJson owes(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        List<OwingJson> owing = balances.whoOwes(ctx.clinicId()).stream()
                .map(o -> new OwingJson(o.patientId(), o.name(), o.phone(), avatars.initials(o.name()), avatars.hue(o.name()),
                        o.balance(), ctx.formats().dayShort(ctx.dateOf(o.since())),
                        Math.max(0, ChronoUnit.DAYS.between(ctx.dateOf(o.since()), ctx.today()))))
                .toList();
        return new OwesJson(owing.stream().map(OwingJson::balance).reduce(BigDecimal.ZERO, BigDecimal::add), owing);
    }

    // --- The day ---

    /** @param date the clinic's today without it */
    @GetMapping("/api/money/day")
    DayJson day(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Ctx ctx = context.of(staff, request);
        LocalDate day = date != null ? date : ctx.today();
        Day data = balances.day(ctx.clinicId(), day, ctx.zone());
        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            byMethod.put(method, data.byMethod().getOrDefault(method, BigDecimal.ZERO.setScale(2)));
        }
        DayClose close = data.close();
        return new DayJson(day.toString(), ctx.formats().date(day), day.equals(ctx.today()), day.minusDays(1).toString(),
                day.plusDays(1).toString(),
                data.payments().stream().map(p -> new DayPaymentJson(p.id(), ctx.formats().time(p.paidAt()), p.patient(),
                        p.amountUsd(), p.method(), p.amountLbp(), app.link("/r/" + p.receiptToken()))).toList(),
                byMethod, data.total(), data.expectedCash(),
                close == null ? null : new CloseJson(close.getExpectedCashUsd(), close.getCountedCashUsd(),
                        close.getCountedCashUsd().subtract(close.getExpectedCashUsd()), close.getClosedBy(),
                        ctx.formats().dateTime(close.getClosedAt())));
    }

    @PutMapping("/api/money/day")
    DayJson closeDay(@RequestBody CloseRequest body, @AuthenticationPrincipal StaffPrincipal staff,
                     HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        balances.closeDay(ctx.clinicId(), body.date(), body.countedCash(), staff.username(), ctx.zone(), ctx.now());
        return day(staff, request, body.date());
    }

    // --- Helpers ---

    private VisitJson visitJson(Ctx ctx, Visit visit) {
        Map<Long, String> names = new HashMap<>();
        doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).forEach(d -> names.put(d.getId(), d.getDisplayName()));
        return new VisitJson(visit.appointmentId(), visit.patientId(), visit.patientName(),
                names.getOrDefault(visit.doctorId(), ""), ctx.formats().dateTime(visit.startsAt()),
                visit.money().status(), visit.money().charged(), visit.money().paid(), visit.money().due(),
                visit.patientBalance(), visit.familyBalance(), visit.familySize(),
                visit.payments().stream().map(p -> new PaymentJson(p.getId(), p.getAmountUsd(), p.getMethod(),
                        p.getAmountLbp(), ctx.formats().dateTime(p.getPaidAt()), receiptUrl(p))).toList());
    }

    private String receiptUrl(Payment payment) {
        return app.link("/r/" + payment.getReceiptToken());
    }
}
