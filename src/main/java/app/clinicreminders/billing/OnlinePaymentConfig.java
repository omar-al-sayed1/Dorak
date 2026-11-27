package app.clinicreminders.billing;

import app.clinicreminders.common.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the online payment provider with {@code whish.client} ({@code WHISH_CLIENT}): {@code fake}
 * for development, anything else (the default) for none, which hides the Pay button. A real Whish
 * client comes once its API is documented to us.
 */
@Configuration(proxyBeanMethods = false)
class OnlinePaymentConfig {

    @Bean
    @ConditionalOnProperty(name = "whish.client", havingValue = "fake")
    FakeWhish fakeWhish(AppProperties app) {
        return new FakeWhish(app);
    }
}
