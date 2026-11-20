package app.clinicreminders.dashboard;

import java.time.YearMonth;

import app.clinicreminders.balance.Balances;
import app.clinicreminders.balance.Balances.MonthMoney;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.report.MonthlyReport;
import app.clinicreminders.report.Reports;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The monthly report (SPEC section 5.4): what became of the month's appointments, the reminders and
 * the messages with their estimated cost, and a CSV of it. A doctor's login sees only their own
 * appointments and not the clinic's messages.
 */
@Controller
class ReportController {

    private final ClinicContext context;
    private final Reports reports;
    private final MessageSource messages;
    private final Balances balances;

    ReportController(ClinicContext context, Reports reports, MessageSource messages, Balances balances) {
        this.context = context;
        this.reports = reports;
        this.messages = messages;
        this.balances = balances;
    }

    /** @param month "2026-10"; the current month without it */
    @GetMapping("/report")
    String report(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                  @RequestParam(required = false) String month) {
        Ctx ctx = context.of(staff, request);
        MonthlyReport report = report(ctx, month);
        ReportView.addTo(model, report, "/report", ctx.clinic().getCreatedAt(), ctx.now(), ctx.language(), messages);
        model.addAttribute("busy", ReportView.busyView(reports.busyHours(report), ctx.language(), messages));
        MonthMoney money = money(ctx, report);
        if (money != null) {
            model.addAttribute("money", ReportView.moneyView(money));
        }
        model.addAttribute("active", "report");
        return "dashboard/report";
    }

    @GetMapping("/report.csv")
    ResponseEntity<String> csv(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                               @RequestParam(required = false) String month) {
        Ctx ctx = context.of(staff, request);
        MonthlyReport report = report(ctx, month);
        return ReportView.csv(report, money(ctx, report), ctx.language(), messages);
    }

    /** The month's fees and payments; money is the front desk's, so none for a doctor's login. */
    private MonthMoney money(Ctx ctx, MonthlyReport report) {
        return ctx.isDoctor() ? null : balances.month(ctx.clinicId(), report.month().from(), report.month().to());
    }

    private MonthlyReport report(Ctx ctx, String month) {
        YearMonth shown = ReportView.month(month, ctx.clinic().getCreatedAt(), ctx.zone(), ctx.now());
        return reports.monthly(ctx.clinicId(), shown, ctx.doctorId(), ctx.now());
    }
}
