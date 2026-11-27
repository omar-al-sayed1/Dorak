package app.clinicreminders.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import app.clinicreminders.common.AppProperties;
import app.clinicreminders.ops.AlertState.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What is wrong in production right now, as problems ready to alert (SPEC section 8, operations):
 * <ul>
 *   <li>reminders still pending well after their due time: the scheduler stalled;</li>
 *   <li>reminders claimed for sending but never finished: the app stopped in the middle;</li>
 *   <li>webhook deliveries left unprocessed: incoming messages and button replies are stuck;</li>
 *   <li>most WhatsApp sends failing in the last minutes;</li>
 *   <li>no successful backup for a day (the backup container records each one in {@code ops_heartbeat});</li>
 *   <li>the database itself unreachable.</li>
 * </ul>
 * Reads across every clinic on purpose: these are the operator's checks, not a clinic's.
 */
@Component
class HealthChecks {

    /** The checks found these problems; {@code complete} is false when the database could not be read. */
    record Result(List<Problem> problems, boolean complete) {
    }

    private record Backlog(long count, @Nullable OffsetDateTime oldest) {
    }

    private record ClinicSends(String clinic, long failed, long sent) {
    }

    private final JdbcClient jdbc;
    private final AlertProperties properties;
    private final MessageSource messages;
    private final String server;
    private final Instant startedAt;

    HealthChecks(JdbcClient jdbc, AlertProperties properties, MessageSource messages, AppProperties app, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.messages = messages;
        this.server = app.baseUrl().getHost() == null ? app.baseUrl().toString() : app.baseUrl().getHost();
        this.startedAt = clock.instant();
    }

    Result check(Instant now) {
        try {
            List<Problem> problems = new ArrayList<>();
            lateReminders(now).ifPresent(problems::add);
            stuckReminders(now).ifPresent(problems::add);
            stuckWebhooks(now).ifPresent(problems::add);
            failingSends(now).ifPresent(problems::add);
            if (properties.backupsWatched()) {
                oldBackup(now).ifPresent(problems::add);
            }
            return new Result(problems, true);
        } catch (DataAccessException e) {
            return new Result(List.of(problem("database", "alert.database",
                    e.getMostSpecificCause().getClass().getSimpleName())), false);
        }
    }

    private Optional<Problem> lateReminders(Instant now) {
        Backlog late = backlog("select count(*), min(due_at) from reminder where status = 'PENDING' and due_at < :cutoff",
                now.minus(properties.remindersLateAfter()));
        return late.count() == 0 ? Optional.empty()
                : Optional.of(problem("reminders", "alert.reminders", late.count(), minutesSince(late.oldest(), now)));
    }

    /**
     * Claimed for sending but never finished: the app stopped between sending and recording it. Not
     * sent again (it may have gone out: at most once), so the operator is told to look.
     */
    private Optional<Problem> stuckReminders(Instant now) {
        Backlog stuck = backlog("select count(*), min(due_at) from reminder where status = 'SENDING' and due_at < :cutoff",
                now.minus(properties.remindersLateAfter()));
        return stuck.count() == 0 ? Optional.empty()
                : Optional.of(problem("sending", "alert.reminders-stuck", stuck.count(), minutesSince(stuck.oldest(), now)));
    }

    private Optional<Problem> stuckWebhooks(Instant now) {
        Backlog stuck = backlog("select count(*), min(received_at) from webhook_inbox where not failed and received_at < :cutoff",
                now.minus(properties.inboxLateAfter()));
        return stuck.count() == 0 ? Optional.empty()
                : Optional.of(problem("inbox", "alert.inbox", stuck.count(), minutesSince(stuck.oldest(), now)));
    }

    /** At least the threshold of failed sends, and at least half of all sends: one patient's dead number is not a problem. */
    private Optional<Problem> failingSends(Instant now) {
        List<ClinicSends> byClinic = jdbc.sql("""
                        select c.name, count(*) filter (where m.status = 'FAILED') as failed, count(*) as sent
                        from message_log m join clinic c on c.id = m.clinic_id
                        where m.direction = 'OUT' and m.created_at >= :since
                        group by c.name
                        order by failed desc, c.name""")
                .param("since", utc(now.minus(properties.failureWindow())))
                .query((row, n) -> new ClinicSends(row.getString("name"), row.getLong("failed"), row.getLong("sent")))
                .list();
        long failed = byClinic.stream().mapToLong(ClinicSends::failed).sum();
        long sent = byClinic.stream().mapToLong(ClinicSends::sent).sum();
        if (failed < properties.failureThreshold() || failed * 2 < sent) {
            return Optional.empty();
        }
        String worst = byClinic.stream().filter(clinic -> clinic.failed() > 0).limit(3)
                .map(clinic -> clinic.clinic() + " (" + clinic.failed() + ")")
                .collect(Collectors.joining(", "));
        return Optional.of(problem("sends", "alert.sends", failed, sent, properties.failureWindow().toMinutes(), worst));
    }

    private Optional<Problem> oldBackup(Instant now) {
        Optional<OffsetDateTime> last = jdbc.sql("select at from ops_heartbeat where name = 'backup'")
                .query(OffsetDateTime.class).optional();
        Instant since = last.map(OffsetDateTime::toInstant).orElse(startedAt);
        Duration age = Duration.between(since, now);
        if (age.compareTo(properties.backupMaxAge()) <= 0) {
            return Optional.empty();
        }
        return Optional.of(problem("backup", last.isPresent() ? "alert.backup.old" : "alert.backup.none", age.toHours()));
    }

    private Backlog backlog(String sql, Instant cutoff) {
        return jdbc.sql(sql).param("cutoff", utc(cutoff))
                .query((row, n) -> new Backlog(row.getLong(1), row.getObject(2, OffsetDateTime.class)))
                .single();
    }

    private Problem problem(String key, String messageKey, Object... arguments) {
        // Plain strings, so numbers stay in Western digits whatever the language.
        Object[] texts = java.util.Arrays.stream(arguments).map(String::valueOf).toArray();
        return new Problem(key,
                text("alert.prefix", server) + "\n" + text(messageKey, texts),
                text("alert.resolved-prefix", server) + "\n" + text(messageKey + ".resolved"));
    }

    private String text(String key, Object... arguments) {
        return messages.getMessage(key, arguments, properties.language());
    }

    private static long minutesSince(@Nullable OffsetDateTime oldest, Instant now) {
        return oldest == null ? 0 : Duration.between(oldest.toInstant(), now).toMinutes();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
