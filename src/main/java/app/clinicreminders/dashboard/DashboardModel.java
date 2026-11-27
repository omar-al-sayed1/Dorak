package app.clinicreminders.dashboard;

import java.time.Clock;

import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.MessageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * What the layout needs on every dashboard page: language and direction, the user, their clinic and
 * how many things wait under Needs attention (left out of htmx fragment requests, which have no layout).
 */
@ControllerAdvice(basePackageClasses = DashboardModel.class)
class DashboardModel {

    private final ClinicRepository clinics;
    private final ConversationRepository conversations;
    private final MessageLogRepository logs;
    private final BillingViews billing;
    private final Clock clock;

    DashboardModel(ClinicRepository clinics, ConversationRepository conversations, MessageLogRepository logs,
                   BillingViews billing, Clock clock) {
        this.billing = billing;
        this.clinics = clinics;
        this.conversations = conversations;
        this.logs = logs;
        this.clock = clock;
    }

    @ModelAttribute
    void layout(Model model, HttpServletRequest request, @AuthenticationPrincipal StaffPrincipal staff) {
        Language language = StaffLocaleResolver.language(request);
        model.addAttribute("lang", language.code());
        model.addAttribute("dir", language == Language.AR ? "rtl" : "ltr");
        model.addAttribute("otherLang", language == Language.AR ? Language.EN.code() : Language.AR.code());
        model.addAttribute("staff", staff);
        // Without WhatsApp the clinic's pages leave out everything WhatsApp (also in htmx's partial pages).
        model.addAttribute("whatsappOn", staff == null || staff.clinicId() == null
                || clinics.findById(staff.clinicId()).map(Clinic::isWhatsAppEnabled).orElse(true));
        if (staff != null && staff.clinicId() != null && !"true".equals(request.getHeader("HX-Request"))) {
            long clinicId = staff.clinicId();
            clinics.findById(clinicId).ifPresent(clinic -> model.addAttribute("clinicName", clinic.getName()));
            if (staff.doctorId() != null) {
                return; // Needs attention is the front desk's
            }
            // A payment for the service near or late: a bar across the page.
            model.addAttribute("billingNotice", billing.notice(staff, language));
            // Counted in the database: this runs on every page.
            model.addAttribute("attentionCount", conversations.countByClinicIdAndNeedsHumanTrue(clinicId)
                    + logs.countFailedSince(clinicId, clock.instant().minus(AttentionController.FAILED_SENDS_WINDOW)));
        }
    }

    /**
     * An action that no longer applies (e.g. a stale page: the appointment was cancelled meanwhile).
     * htmx reloads the page; a plain form goes back to Today with a message.
     */
    @ExceptionHandler(IllegalStateException.class)
    String notAllowed(HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
        if ("true".equals(request.getHeader("HX-Request"))) {
            response.setHeader("HX-Refresh", "true");
            return "dashboard/today-list :: empty";
        }
        redirect.addFlashAttribute("error", "error.not-allowed");
        return "redirect:/app/";
    }
}
