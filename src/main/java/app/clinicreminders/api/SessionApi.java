package app.clinicreminders.api;

import java.time.Clock;

import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.AttentionController;
import app.clinicreminders.dashboard.Avatars;
import app.clinicreminders.dashboard.StaffLocaleResolver;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.MessageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is logged in, for the React app: their role, clinic (null for the operator), doctor (a
 * doctor's own login), language, the Needs attention count, and the CSRF token every change must
 * send back (the session's, as for the server-rendered forms).
 */
@RestController
class SessionApi {

    /** @param attentionCount null where there is no Needs attention: operators and doctors */
    record Session(String username, String role, @Nullable Long clinicId, @Nullable String clinicName,
                   @Nullable Long doctorId, String language, String initials, int hue, @Nullable Long attentionCount,
                   Csrf csrf) {
    }

    record Csrf(String headerName, String token) {
    }

    record LanguageChange(String language) {
    }

    private final ClinicRepository clinics;
    private final ConversationRepository conversations;
    private final MessageLogRepository logs;
    private final StaffAccounts accounts;
    private final Avatars avatars;
    private final Clock clock;

    SessionApi(ClinicRepository clinics, ConversationRepository conversations, MessageLogRepository logs,
               StaffAccounts accounts, Avatars avatars, Clock clock) {
        this.clinics = clinics;
        this.conversations = conversations;
        this.logs = logs;
        this.accounts = accounts;
        this.avatars = avatars;
        this.clock = clock;
    }

    @GetMapping("/api/session")
    Session session(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, CsrfToken csrf) {
        Long clinicId = staff.clinicId();
        String clinicName = clinicId == null ? null : clinics.findById(clinicId).map(c -> c.getName()).orElse(null);
        Long attention = clinicId == null || staff.doctorId() != null ? null
                : conversations.countByClinicIdAndNeedsHumanTrue(clinicId)
                        + logs.countFailedSince(clinicId, clock.instant().minus(AttentionController.FAILED_SENDS_WINDOW));
        return new Session(staff.username(), staff.role().name(), clinicId, clinicName, staff.doctorId(),
                StaffLocaleResolver.language(request).code(), avatars.initials(staff.username()),
                avatars.hue(staff.username()), attention, new Csrf(csrf.getHeaderName(), csrf.getToken()));
    }

    /** Remembered for this session and as the user's language, as the language switch does on the pages. */
    @PutMapping("/api/language")
    void language(@RequestBody LanguageChange change, @AuthenticationPrincipal StaffPrincipal staff,
                  HttpServletRequest request) {
        Language language = "en".equals(change.language()) ? Language.EN : Language.AR;
        StaffLocaleResolver.remember(request, language);
        accounts.changeLanguage(staff, language);
    }
}
