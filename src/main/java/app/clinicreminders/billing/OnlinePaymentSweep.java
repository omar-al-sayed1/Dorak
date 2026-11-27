package app.clinicreminders.billing;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: {@link OnlinePayments#sweep}. Off with the other schedulers (tests, one-off commands). */
@Component
@ConditionalOnProperty(name = "reminders.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class OnlinePaymentSweep {

    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentSweep.class);

    private final OnlinePayments payments;
    private final Clock clock;

    OnlinePaymentSweep(OnlinePayments payments, Clock clock) {
        this.payments = payments;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void sweep() {
        try {
            payments.sweep(clock.instant());
        } catch (RuntimeException e) {
            log.error("Online payment sweep failed", e);
        }
    }
}
