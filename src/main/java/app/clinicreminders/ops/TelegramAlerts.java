package app.clinicreminders.ops;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Alerts as Telegram messages from the operator's own bot (Bot API {@code sendMessage}). Free, needs
 * no Meta template, and still works when WhatsApp is what is failing.
 *
 * <p>The bot token is part of the request path, and Spring's I/O errors quote the full URL, so a
 * failure is logged by its type only, never with the exception's message.
 */
class TelegramAlerts implements OperatorAlerts {

    private static final Logger log = LoggerFactory.getLogger(TelegramAlerts.class);

    private final RestClient rest;
    private final String botToken;
    private final String chatId;

    TelegramAlerts(RestClient rest, String botToken, String chatId) {
        this.rest = rest;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    @Override
    public void send(String text) {
        try {
            rest.post()
                    // In the template itself, not as a variable: variables get every reserved character
                    // encoded, and the ':' in a bot token would go out as %3A.
                    .uri("/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("Could not send an operator alert to Telegram ({}). The alert was: {}", e.getClass().getSimpleName(), text);
        }
    }
}
