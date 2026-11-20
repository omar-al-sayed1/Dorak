package app.clinicreminders.ops;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link HealthChecks} every minute and alerts the operator ({@link AlertState} keeps it from
 * repeating itself). Off with the other schedulers (tests, one-off commands). It needs a scheduler
 * thread of its own, so that a stuck reminder run cannot also stop the check that reports it
 * ({@code spring.task.scheduling.pool.size}).
 */
@Component
@ConditionalOnProperty(name = "reminders.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class HealthWatch {

    private static final Logger log = LoggerFactory.getLogger(HealthWatch.class);

    private final HealthChecks checks;
    private final OperatorAlerts alerts;
    private final AlertState state;
    private final Clock clock;

    HealthWatch(HealthChecks checks, OperatorAlerts alerts, AlertProperties properties, Clock clock) {
        this.checks = checks;
        this.alerts = alerts;
        this.state = new AlertState(properties.repeatAfter());
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${alerts.check-interval:PT1M}", initialDelayString = "${alerts.check-interval:PT1M}")
    void check() {
        try {
            HealthChecks.Result result = checks.check(clock.instant());
            state.update(result.problems(), result.complete(), clock.instant()).forEach(alerts::send);
        } catch (RuntimeException e) {
            log.error("Health check failed", e);
        }
    }
}
