package app.clinicreminders.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Duration;

import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.DashboardTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/** Guessing passwords through the real sign-in form: after five wrong ones, even the right one waits. */
class LoginThrottleIT extends DashboardTest {

    @Autowired
    StaffUserRepository users;

    private String username;

    @BeforeEach
    void aSecretary() {
        username = "throttled." + System.nanoTime();
        long clinicId = data.clinic("A").getId();
        users.save(StaffUser.clinicStaff(clinicId, username, "{noop}right-password", StaffRole.SECRETARY, Language.EN));
    }

    @Test
    void fiveWrongPasswordsLockTheUsernameEvenForTheRightOne() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("wrong")).hasRedirectedUrl("/login?error");
        }

        assertThat(login("right-password")).as("locked, not even tried").hasRedirectedUrl("/login?locked");
        assertThat(mvc.get().uri("/login?locked")).bodyText().as("the reason, on the login page").contains("#i-lock");

        clock.advance(Duration.ofMinutes(16));
        assertThat(login("right-password")).hasRedirectedUrl("/app/");
    }

    @Test
    void aGoodLoginClearsTheCount() {
        for (int i = 0; i < 4; i++) {
            login("wrong").exchange();
        }
        assertThat(login("right-password")).hasRedirectedUrl("/app/");

        for (int i = 0; i < 4; i++) {
            login("wrong").exchange();
        }
        assertThat(login("right-password")).as("counting started again").hasRedirectedUrl("/app/");
    }

    private MockMvcRequestBuilder login(String password) {
        return mvc.post().uri("/login").param("username", username).param("password", password).with(csrf());
    }
}
