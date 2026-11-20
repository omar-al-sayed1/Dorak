package app.clinicreminders.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import app.clinicreminders.balance.Balances.MonthMoney;
import app.clinicreminders.balance.PaymentMethod;
import app.clinicreminders.common.Language;
import app.clinicreminders.report.BusyHours;
import app.clinicreminders.report.BusyHours.Cell;
import app.clinicreminders.report.MonthlyReport;
import app.clinicreminders.report.ReportCsv;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;

/**
 * What the report page shows, for a clinic's own report and for the operator's look at one: the
 * month and its neighbours, the messages with readable categories and dollars, and the CSV download.
 */
final class ReportView {

    /** The busy-hours grid as the page draws it: hour headings, and a row of cells per weekday. */
    record BusyView(List<String> hours, List<BusyRow> days, @Nullable String busiest,
                    @Nullable String quietest) {
    }

    record BusyRow(String day, List<BusyCell> cells) {
    }

    /** @param title what the cell says on hover and to a screen reader */
    record BusyCell(String level, String percent, String title) {
    }

    /** Messages of one pricing category, labelled and priced for the page. */
    record MessageRow(String category, long sent, long failed, String cost, long unpriced) {
    }

    private ReportView() {
    }

    /**
     * The month asked for, kept between the clinic's first month and the current one; the current
     * month when none (or nonsense) is asked for.
     */
    static YearMonth month(@Nullable String asked, @Nullable Instant created, ZoneId zone, Instant now) {
        YearMonth current = YearMonth.from(now.atZone(zone));
        YearMonth first = YearMonth.from((created == null ? now : created).atZone(zone));
        YearMonth month;
        try {
            month = asked == null || asked.isBlank() ? current : YearMonth.parse(asked.strip());
        } catch (DateTimeParseException e) {
            month = current;
        }
        if (month.isAfter(current)) {
            return current;
        }
        return month.isBefore(first) ? first : month;
    }

    /**
     * @param base    the page's own address ("/report", "/operator/clinics/3/report"); the month and the CSV hang off it
     * @param created when the clinic was added: no month before it
     */
    static void addTo(Model model, MonthlyReport report, String base, @Nullable Instant created, Instant now,
                      Language language, MessageSource messages) {
        Locale locale = language.locale();
        ZoneId zone = report.month().zone();
        monthNav(model, report.month().month(), YearMonth.from((created == null ? now : created).atZone(zone)),
                YearMonth.from(now.atZone(zone)), language, messages);
        model.addAttribute("report", report);
        model.addAttribute("reportBase", base);
        model.addAttribute("messageRows", report.messages().stream()
                .map(m -> new MessageRow(ReportCsv.categoryLabel(messages, locale, m.category()), m.sent(), m.failed(),
                        cost(m.estimatedUsd(), m.sent(), m.unpriced()), m.unpriced()))
                .toList());
        model.addAttribute("totalCost", cost(report.estimatedUsd(), report.messagesSent(), report.messagesUnpriced()));
        model.addAttribute("multipleDoctors", report.doctors().size() > 1);
    }

    /** The month shown ("October 2026") and the months before and after it, if there are any to show. */
    static void monthNav(Model model, YearMonth month, YearMonth first, YearMonth current, Language language,
                         MessageSource messages) {
        Locale locale = language.locale();
        DateTimeFormatter label = DateTimeFormatter.ofPattern(messages.getMessage("format.month", null, locale), locale);
        model.addAttribute("monthLabel", label.format(month));
        model.addAttribute("monthValue", month.toString());
        model.addAttribute("current", month.equals(current));
        model.addAttribute("previousMonth", month.isAfter(first) ? month.minusMonths(1).toString() : null);
        model.addAttribute("nextMonth", month.isBefore(current) ? month.plusMonths(1).toString() : null);
    }

    /** The grid in the reader's language: "Tuesday 10 AM: 7 of 8 booked (88%)". */
    static BusyView busyView(BusyHours busy, Language language, MessageSource messages) {
        Locale locale = language.locale();
        DateTimeFormatter hour = DateTimeFormatter.ofPattern(messages.getMessage("format.hour", null, locale), locale);
        List<String> hours = busy.hours().stream().map(h -> hour.format(LocalTime.of(h, 0))).toList();
        List<BusyRow> days = busy.days().stream()
                .map(day -> new BusyRow(dayName(day.day(), locale), day.cells().stream()
                        .map(cell -> new BusyCell(cell.level(), cell.percent() == null ? "" : cell.percent() + "%",
                                cellTitle(cell, hour, locale, messages)))
                        .toList()))
                .toList();
        return new BusyView(hours, days,
                busy.busiest().map(cell -> cellName(cell, hour, locale) + " (" + cell.percent() + "%)").orElse(null),
                busy.quietest().map(cell -> cellName(cell, hour, locale) + " (" + cell.percent() + "%)").orElse(null));
    }

    private static String cellTitle(Cell cell, DateTimeFormatter hour, Locale locale, MessageSource messages) {
        String name = cellName(cell, hour, locale);
        return switch (cell.level()) {
            case "closed" -> messages.getMessage("report.busy.cell-closed", new Object[] {name}, locale);
            case "outside" -> messages.getMessage("report.busy.cell-outside",
                    new Object[] {name, String.valueOf(cell.booked())}, locale);
            default -> messages.getMessage("report.busy.cell", new Object[] {name, String.valueOf(cell.booked()),
                    String.valueOf(cell.open()), String.valueOf(cell.percent())}, locale);
        };
    }

    private static String cellName(Cell cell, DateTimeFormatter hour, Locale locale) {
        return dayName(cell.day(), locale) + " " + hour.format(LocalTime.of(cell.hour(), 0));
    }

    private static String dayName(DayOfWeek day, Locale locale) {
        return day.getDisplayName(TextStyle.FULL, locale);
    }

    /** The cost of some messages: a dash when none of them could be priced (no rate set), not a misleading $0.00. */
    static String cost(BigDecimal estimated, long sent, long unpriced) {
        return sent > 0 && unpriced == sent ? "—" : usd(estimated);
    }

    /**
     * The month's fees and payments as the report shows them: "$1,250.00" amounts, by method.
     *
     * @param collectedPercent how much of what was charged came in, for the bar; null when nothing was charged
     */
    record MoneyView(String charged, String collected, String owedNow, List<MethodTotal> byMethod,
                     @Nullable Integer collectedPercent) {
    }

    /**
     * @param percent the method's share of what was collected, for its bar
     */
    record MethodTotal(PaymentMethod method, String amount, int percent) {
    }

    static MoneyView moneyView(MonthMoney money) {
        return new MoneyView(dollars(money.charged()), dollars(money.collected()), dollars(money.owedNow()),
                java.util.Arrays.stream(PaymentMethod.values())
                        .map(method -> {
                            BigDecimal amount = money.byMethod().getOrDefault(method, BigDecimal.ZERO);
                            return new MethodTotal(method, dollars(amount), percent(amount, money.collected()));
                        })
                        .toList(),
                money.charged().signum() > 0 ? percent(money.collected(), money.charged()) : null);
    }

    /** A part of a whole as 0 to 100, for a bar: nothing of nothing is 0, and more than the whole is 100. */
    static int percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() <= 0 || part.signum() <= 0) {
            return 0;
        }
        return Math.min(100, part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP).intValue());
    }

    /** "$1,250.00": clinic money, with thousands separators. */
    static String dollars(BigDecimal amount) {
        return "$" + new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.ROOT))
                .format(amount);
    }

    /** "$12.34"; a few messages may cost less than a cent. */
    static String usd(BigDecimal amount) {
        if (amount.signum() > 0 && amount.compareTo(new BigDecimal("0.005")) < 0) {
            return "< $0.01";
        }
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** The report as a CSV download, named after the clinic's month. */
    static ResponseEntity<String> csv(MonthlyReport report, @Nullable MonthMoney money, Language language,
                                      MessageSource messages) {
        String filename = "report-" + report.month().month() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(ReportCsv.of(report, money, messages, language.locale()));
    }
}
