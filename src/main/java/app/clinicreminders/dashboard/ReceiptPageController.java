package app.clinicreminders.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import app.clinicreminders.balance.Balances;
import app.clinicreminders.balance.Balances.Receipt;
import app.clinicreminders.balance.Payment;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * A payment's receipt, for the patient, without logging in: the link the desk sends on WhatsApp or
 * prints. The clinic, who paid, when, how much and how, and what is left to pay; nothing medical.
 * The random token in the link is the only key. In the reader's language when staff open it, else
 * the patient's, and it can be switched on the page; never indexed.
 */
@Controller
class ReceiptPageController {

    /** The receipt as the page shows it; amounts already formatted. */
    record ReceiptView(String clinic, @Nullable String address, String number, String patient, String paidAt,
                       String amount, @Nullable String amountLbp, @Nullable String rate, String method, String balance,
                       boolean settled, boolean inCredit) {
    }

    private final Balances balances;
    private final MessageSource messages;

    ReceiptPageController(Balances balances, MessageSource messages) {
        this.balances = balances;
        this.messages = messages;
    }

    @GetMapping("/r/{token}")
    String receipt(@PathVariable String token, HttpServletRequest request, HttpServletResponse response, Model model) {
        Receipt receipt = balances.receipt(parse(token)).orElseThrow(() -> new NotFoundException("Receipt", token));
        // The reader's language if they use the dashboard, else the patient's; ?lang= switches it.
        Language language = StaffLocaleResolver.forPage(request, receipt.patient().getLanguage());
        model.addAttribute("lang", language.code());
        model.addAttribute("dir", language == Language.AR ? "rtl" : "ltr");
        model.addAttribute("switchLang", language == Language.AR ? "en" : "ar");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        Locale locale = language.locale();
        Payment payment = receipt.payment();
        DateTimeFormatter when = DateTimeFormatter.ofPattern(
                messages.getMessage("patient.date-format", null, locale) + " '·' " + messages.getMessage("patient.time-format", null, locale),
                locale);
        BigDecimal balance = receipt.balance();
        model.addAttribute("receipt", new ReceiptView(receipt.clinic().getName(), receipt.clinic().getAddress(),
                String.valueOf(payment.getId()), receipt.patient().getName(),
                when.format(payment.getPaidAt().atZone(receipt.clinic().getTimezone())), usd(payment.getAmountUsd()),
                payment.getAmountLbp() == null ? null : lbp(payment.getAmountLbp()),
                payment.getLbpPerUsd() == null ? null : lbp(payment.getLbpPerUsd()),
                messages.getMessage("method." + payment.getMethod().name(), null, locale), usd(balance.abs()),
                balance.signum() == 0, balance.signum() < 0));
        return "dashboard/receipt";
    }

    /** "$1,250.00", in Western digits as everywhere in the dashboard. */
    static String usd(BigDecimal amount) {
        return String.format(Locale.ROOT, "$%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** "1,800,000 LBP". */
    static String lbp(BigDecimal amount) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(amount) + " LBP";
    }

    private static UUID parse(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Receipt", token);
        }
    }
}
