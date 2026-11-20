package app.clinicreminders.dashboard;

import java.util.Locale;

import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The dashboard's language: the one chosen in this session, else the logged-in user's saved
 * language, else English if the browser prefers it, else Arabic. A page for a patient (their
 * calendar page) is in the patient's language instead, for that request only.
 */
public class StaffLocaleResolver implements LocaleResolver {

    private static final String SESSION_ATTRIBUTE = StaffLocaleResolver.class.getName() + ".LANGUAGE";
    private static final String REQUEST_ATTRIBUTE = StaffLocaleResolver.class.getName() + ".PAGE_LANGUAGE";

    public static Language language(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof Language page) {
            return page;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Language chosen) {
            return chosen;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StaffPrincipal staff) {
            return staff.language();
        }
        String accept = request.getHeader("Accept-Language");
        return accept != null && accept.strip().toLowerCase(Locale.ROOT).startsWith("en") ? Language.EN : Language.AR;
    }

    /**
     * The language of a page opened from a link (a receipt, an invoice): the one asked for in the
     * address ({@code ?lang=en}), else the reader's own if they use the dashboard (chosen in this
     * session, or their login's), else {@code fallback} (the patient's, the clinic's). For this
     * request only; nothing is remembered.
     */
    public static Language forPage(HttpServletRequest request, Language fallback) {
        String asked = request.getParameter("lang");
        Language language = fallback;
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if ("en".equals(asked) || "ar".equals(asked)) {
            language = Language.fromCode(asked);
        } else if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Language chosen) {
            language = chosen;
        } else if (authentication != null && authentication.getPrincipal() instanceof StaffPrincipal staff) {
            language = staff.language();
        }
        forRequest(request, language);
        return language;
    }

    /** This request's page is in {@code language}, whoever reads it; nothing is remembered. */
    public static void forRequest(HttpServletRequest request, Language language) {
        request.setAttribute(REQUEST_ATTRIBUTE, language);
    }

    public static void remember(HttpServletRequest request, Language language) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, language);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return language(request).locale();
    }

    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        remember(request, locale != null && locale.getLanguage().equals("en") ? Language.EN : Language.AR);
    }
}
