package app.clinicreminders.ops;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The request shape is Telegram's Bot API "sendMessage" (core.telegram.org/bots/api#sendmessage). */
class TelegramAlertsTest {

    private MockRestServiceServer server;
    private TelegramAlerts alerts;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://telegram.example");
        server = MockRestServiceServer.bindTo(builder).build();
        alerts = new TelegramAlerts(builder.build(), "123:ABC", "987654");
    }

    @Test
    void sendsTheTextToTheOperatorsChat() {
        server.expect(requestTo("https://telegram.example/bot123:ABC/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"chat_id":"987654","text":"Reminders are late","disable_web_page_preview":true}""",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alerts.send("Reminders are late");

        server.verify();
    }

    @Test
    void anAlertThatCannotBeDeliveredNeverBreaksTheCaller() {
        server.expect(requestTo("https://telegram.example/bot123:ABC/sendMessage")).andRespond(withServerError());
        server.expect(requestTo("https://telegram.example/bot123:ABC/sendMessage")).andRespond(withException(new IOException("down")));

        assertThatCode(() -> alerts.send("one")).doesNotThrowAnyException();
        assertThatCode(() -> alerts.send("two")).doesNotThrowAnyException();
        server.verify();
    }
}
