package app.clinicreminders.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import app.clinicreminders.balance.Balances;
import app.clinicreminders.balance.Balances.VisitMoney;
import app.clinicreminders.dashboard.Avatars;
import app.clinicreminders.dashboard.ClinicContext;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.dashboard.TodayController;
import app.clinicreminders.dashboard.TodayList;
import app.clinicreminders.dashboard.TodayList.Stats;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Today page's data: the day's appointments per doctor, their figures and the day around it, as
 * the server-rendered page shows them (a doctor's login: their own column). Times and dates come
 * formatted in the clinic's zone and the user's language; avatars come with their initials and colour.
 */
@RestController
class TodayApi {

    /**
     * @param relativeDayKey   "today.relative.tomorrow"..., with {@code relativeDayCount} for "In 3 days"
     * @param nowTime          the "now" line's time, on today
     */
    record Today(String date, String dateLabel, boolean today, String relativeDayKey, String relativeDayCount,
                 String previousDate, String nextDate, String nowTime, Stats stats, List<DoctorDay> doctors) {
    }

    /** @param nowIndex where the "now" line goes: before the row at this index, or after them all; -1 off today */
    record DoctorDay(long id, String name, String initials, int hue, long active, int nowIndex, List<Row> rows) {
    }

    /**
     * @param patientName null once the patient was deleted
     * @param money       the visit's fee and payments (Balance); null for a doctor's login, or before any
     */
    record Row(long id, String time, @Nullable String patientName, @Nullable String patientPhone, String initials,
               int hue, String status, String source, boolean cancelled, boolean cancelledByPatient, boolean past,
               boolean canArrive, boolean canNoShow, boolean canCancel, boolean canReschedule, @Nullable VisitMoney money,
               @Nullable Long patientId) {
    }

    private final ClinicContext context;
    private final TodayList list;
    private final Avatars avatars;
    private final Balances balances;

    TodayApi(ClinicContext context, TodayList list, Avatars avatars, Balances balances) {
        this.context = context;
        this.list = list;
        this.avatars = avatars;
        this.balances = balances;
    }

    /** @param date the clinic's today without it */
    @GetMapping("/api/today")
    Today today(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Ctx ctx = context.of(staff, request);
        LocalDate day = date != null ? date : ctx.today();
        TodayList.Day data = list.day(ctx, day);
        long daysAway = ChronoUnit.DAYS.between(ctx.today(), day);
        // Money is the front desk's: a doctor's login sees none.
        Map<Long, VisitMoney> money = ctx.isDoctor() ? Map.of() : balances.forAppointments(ctx.clinicId(),
                data.doctors().stream().flatMap(d -> d.rows().stream()).map(TodayList.Row::id).toList());
        return new Today(day.toString(), ctx.formats().date(day), data.today(), TodayController.relativeDayKey(daysAway),
                String.valueOf(Math.abs(daysAway)), day.minusDays(1).toString(), day.plusDays(1).toString(),
                data.nowTime(), data.stats(), data.doctors().stream().map(d -> doctorDay(d, money)).toList());
    }

    private DoctorDay doctorDay(TodayList.DoctorDay day, Map<Long, VisitMoney> money) {
        return new DoctorDay(day.id(), day.name(), avatars.initials(day.name()), avatars.hue(day.name()), day.active(),
                day.nowIndex(), day.rows().stream().map(row -> row(row, money.get(row.id()))).toList());
    }

    private Row row(TodayList.Row row, @Nullable VisitMoney money) {
        return new Row(row.id(), row.time(), row.patientName(), row.patientPhone(), avatars.initials(row.patientName()),
                avatars.hue(row.patientName()), row.status(), row.source(), row.cancelled(), row.cancelledByPatient(),
                row.past(), row.canArrive(), row.canNoShow(), row.canCancel(), row.canReschedule(), money, row.patientId());
    }
}
