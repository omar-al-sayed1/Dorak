package app.clinicreminders.ops;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator alerts, bound from {@code alerts.*}: where they go (a Telegram chat) and what counts as a
 * problem. Without Telegram settings, alerts are only logged.
 *
 * @param repeatAfter        a problem that lasts is alerted again this often
 * @param remindersLateAfter a reminder still pending this long after its due time means the scheduler stalled
 * @param inboxLateAfter     a webhook delivery still unprocessed this long after it arrived means processing stalled
 * @param failureWindow      how far back send failures are counted
 * @param failureThreshold   this many failed sends in the window, and at least half of all sends, is a problem
 * @param backupMaxAge       no successful backup for this long is a problem; zero turns the check off
 * @param language           the language alerts are written in (en or ar)
 */
@ConfigurationProperties("alerts")
public record AlertProperties(
        Telegram telegram,
        Duration repeatAfter,
        Duration remindersLateAfter,
        Duration inboxLateAfter,
        Duration failureWindow,
        int failureThreshold,
        Duration backupMaxAge,
        Locale language) {

    /**
     * @param baseUrl  Telegram's Bot API address
     * @param botToken from @BotFather ({@code TELEGRAM_BOT_TOKEN})
     * @param chatId   the operator's chat with the bot ({@code TELEGRAM_CHAT_ID})
     */
    public record Telegram(URI baseUrl, @Nullable String botToken, @Nullable String chatId) {

        public Telegram {
            baseUrl = baseUrl == null ? URI.create("https://api.telegram.org") : baseUrl;
        }

        boolean configured() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }
    }

    public AlertProperties {
        telegram = telegram == null ? new Telegram(null, null, null) : telegram;
        repeatAfter = repeatAfter == null ? Duration.ofHours(6) : repeatAfter;
        remindersLateAfter = remindersLateAfter == null ? Duration.ofMinutes(10) : remindersLateAfter;
        inboxLateAfter = inboxLateAfter == null ? Duration.ofMinutes(10) : inboxLateAfter;
        failureWindow = failureWindow == null ? Duration.ofMinutes(15) : failureWindow;
        failureThreshold = failureThreshold <= 0 ? 3 : failureThreshold;
        backupMaxAge = backupMaxAge == null ? Duration.ofHours(26) : backupMaxAge;
        language = language == null ? Locale.ENGLISH : language;
    }

    boolean backupsWatched() {
        return !backupMaxAge.isZero() && !backupMaxAge.isNegative();
    }
}
