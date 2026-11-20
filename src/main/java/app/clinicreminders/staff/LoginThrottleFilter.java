package app.clinicreminders.staff;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Before the login form is checked: a username locked by {@link LoginAttempts} goes back to the login
 * page with a message, without its password being tried at all. Added to the security chain by
 * {@link SecurityConfig} (not a bean, so it isn't registered a second time as a servlet filter).
 */
class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginAttempts attempts;

    LoginThrottleFilter(LoginAttempts attempts) {
        this.attempts = attempts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && (request.getContextPath() + "/login").equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = request.getParameter("username");
        if (username != null && attempts.isLocked(username)) {
            response.sendRedirect(request.getContextPath() + "/login?locked");
            return;
        }
        chain.doFilter(request, response);
    }
}
