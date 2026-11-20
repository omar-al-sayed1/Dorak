package app.clinicreminders.staff;

import java.io.IOException;

import app.clinicreminders.dashboard.StaffLocaleResolver;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * Form login for the dashboard. Clinic staff get the dashboard: owners and secretaries all of it,
 * doctors their own day (Today, Week, Patients, their hours; the controllers limit what they see to
 * their own doctor). Operators get {@code /operator}; everyone can change their own password. The WhatsApp webhook and the dev simulator stay open: the webhook is
 * authenticated by its signature, and the simulator only exists with the fake client.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final String[] FRONT_DESK = {StaffRole.OWNER.name(), StaffRole.SECRETARY.name()};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttempts loginAttempts) throws Exception {
        http
                // Too many wrong passwords for a username: it is locked for a while (LoginAttempts).
                .addFilterBefore(new LoginThrottleFilter(loginAttempts), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/terms", "/css/**", "/js/**", "/fonts/**", "/img/**", "/webjars/**", "/favicon.ico", "/favicon.png", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/webhooks/whatsapp").permitAll()
                        // The dashboard's texts, in both languages: nothing private.
                        .requestMatchers(HttpMethod.GET, "/api/messages").permitAll()
                        // A patient's "Add to my calendar" page: the random token in the link is the key.
                        .requestMatchers(HttpMethod.GET, "/c/*", "/c/*/calendar.ics").permitAll()
                        // A payment's receipt, sent to the patient: its random token is the key.
                        .requestMatchers(HttpMethod.GET, "/r/*").permitAll()
                        // A clinic's invoice for the service: its random token is the key.
                        .requestMatchers(HttpMethod.GET, "/i/*").permitAll()
                        .requestMatchers("/dev/whatsapp", "/dev/whatsapp/**").permitAll()
                        .requestMatchers("/operator", "/operator/**").hasRole(StaffRole.OPERATOR.name())
                        .requestMatchers("/", "/language", "/account/**", "/app", "/app/**", "/api/session",
                                "/api/language").authenticated()
                        // The front desk's: clinic settings, the inbox, adding and editing doctors and their
                        // logins, deleting patients (PatientsApi). A doctor may change only their own hours and days off.
                        .requestMatchers("/settings", "/settings/**", "/attention", "/attention/**").hasAnyRole(FRONT_DESK)
                        // Balance: money is the clinic's, not a doctor's login's.
                        .requestMatchers("/api/money/**").hasAnyRole(FRONT_DESK)
                        // What the clinic pays for the service: the owner's.
                        .requestMatchers("/billing").hasRole(StaffRole.OWNER.name())
                        .requestMatchers(HttpMethod.POST, "/doctors", "/doctors/*", "/doctors/*/delete", "/doctors/*/login",
                                "/doctors/*/login/**").hasAnyRole(FRONT_DESK)
                        .requestMatchers(HttpMethod.DELETE, "/api/patients/*").hasAnyRole(FRONT_DESK)
                        .anyRequest().hasAnyRole(StaffRole.OWNER.name(), StaffRole.SECRETARY.name(), StaffRole.DOCTOR.name()))
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(new LanguageRememberingSuccessHandler(loginAttempts))
                        .failureHandler(new CountingFailureHandler(loginAttempts))
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .requestCache(cache -> cache.requestCache(pagesOnlyRequestCache()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/whatsapp", "/dev/whatsapp/**"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HtmxAwareEntryPoint()));
        return http.build();
    }

    /**
     * Only whole pages are remembered as the place to return to after logging in. Otherwise the
     * 30-second refresh of an open Today page, after the session expired, would send the user to a
     * bare htmx fragment once they log in again, and what the browser fetches on its own (icons,
     * Chrome DevTools' {@code /.well-known/appspecific/com.chrome.devtools.json}) to a 404.
     */
    private static RequestCache pagesOnlyRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(SecurityConfig::isPageRequest);
        return cache;
    }

    /** A page the user opened: the browser asks for HTML, and it isn't htmx or a {@code /.well-known/} probe. */
    static boolean isPageRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return "GET".equals(request.getMethod())
                && !"true".equals(request.getHeader("HX-Request"))
                && accept != null && accept.contains("text/html")
                && !request.getRequestURI().startsWith(request.getContextPath() + "/.well-known/");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Starts the session in the user's saved language, then continues to the page they asked for; the
     * username's wrong-password count starts again.
     */
    private static final class LanguageRememberingSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

        private final LoginAttempts attempts;

        LanguageRememberingSuccessHandler(LoginAttempts attempts) {
            this.attempts = attempts;
            // Today, in the React dashboard (an operator is sent on to /operator from there).
            setDefaultTargetUrl("/app/");
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException, ServletException {
            attempts.succeeded(authentication.getName());
            if (authentication.getPrincipal() instanceof StaffPrincipal staff) {
                StaffLocaleResolver.remember(request, staff.language());
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /** A failed login (wrong password, unknown username) counts towards the username's lock; back to /login?error. */
    private static final class CountingFailureHandler extends SimpleUrlAuthenticationFailureHandler {

        private final LoginAttempts attempts;

        CountingFailureHandler(LoginAttempts attempts) {
            super("/login?error");
            this.attempts = attempts;
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException, ServletException {
            String username = request.getParameter("username");
            if (username != null) {
                attempts.failed(username);
            }
            super.onAuthenticationFailure(request, response, exception);
        }
    }

    /**
     * htmx requests (the 30-second refresh) can't follow a redirect to the login page in place, so
     * they get 401 with {@code HX-Redirect}, which makes htmx load the login page as a whole page.
     * The JSON API ({@code /api/}) gets a bare 401: the React app reloads its page, which logs in.
     */
    private static final class HtmxAwareEntryPoint implements AuthenticationEntryPoint {

        private final LoginUrlAuthenticationEntryPoint login = new LoginUrlAuthenticationEntryPoint("/login");

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException exception) throws IOException, ServletException {
            if ("true".equals(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", request.getContextPath() + "/login");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            login.commence(request, response, exception);
        }
    }
}
