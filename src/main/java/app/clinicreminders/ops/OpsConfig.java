package app.clinicreminders.ops;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertProperties.class)
class OpsConfig {

    private static final Logger log = LoggerFactory.getLogger(OpsConfig.class);

    /** Telegram when TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are set; otherwise alerts only go to the log. */
    @Bean
    OperatorAlerts operatorAlerts(AlertProperties properties) {
        AlertProperties.Telegram telegram = properties.telegram();
        if (!telegram.configured()) {
            log.warn("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are not set: operator alerts are only logged");
            return text -> log.warn("Operator alert: {}", text);
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient rest = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(telegram.baseUrl().toString())
                .build();
        return new TelegramAlerts(rest, telegram.botToken(), telegram.chatId());
    }
}
