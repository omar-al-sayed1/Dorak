package app.clinicreminders.clinic;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every minute: forgets the deleted clinics that can no longer be brought back, so a deleted
 * clinic's data is really gone a few minutes after the deletion. Off with the other schedulers
 * (tests, one-off commands).
 */
@Component
@ConditionalOnProperty(name = "reminders.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class DeletedClinicPurge {

    private static final Logger log = LoggerFactory.getLogger(DeletedClinicPurge.class);

    private final ClinicRemoval removal;
    private final Clock clock;

    DeletedClinicPurge(ClinicRemoval removal, Clock clock) {
        this.removal = removal;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M")
    void purge() {
        try {
            int purged = removal.purge(clock.instant());
            if (purged > 0) {
                log.info("Purged {} deleted clinic(s) past the time to undo", purged);
            }
        } catch (RuntimeException e) {
            log.error("Purging deleted clinics failed", e);
        }
    }
}
