package app.clinicreminders.clinic;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import app.clinicreminders.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deleting a clinic with everything that is its own: logins, doctors and their hours, patients,
 * appointments, reminders, messages, money and billing. The operator's, behind a typed confirmation.
 *
 * <p>For {@link #UNDO_WINDOW} after it the deletion can be undone: the clinic's rows are kept whole
 * in {@code deleted_clinic} and put back as they were, ids included. After that they are purged
 * ({@link DeletedClinicPurge}) and nothing can bring the clinic back.
 */
@Service
public class ClinicRemoval {

    /** How long a deleted clinic can be brought back. Short: its patients' data is still held meanwhile. */
    public static final Duration UNDO_WINDOW = Duration.ofMinutes(10);

    /**
     * Every table with a {@code clinic_id}, children before the rows they point to. A new tenant
     * table must be added here ({@code ClinicRemovalIT} fails until it is).
     */
    static final List<String> TABLES = List.of("reminder", "charge", "payment", "day_close", "message_log", "conversation",
            "extra_slot", "schedule_rule", "schedule_exception", "appointment", "patient", "staff_user", "doctor",
            "subscription_payment", "subscription");

    /** What goes with the clinic, to say so before it is deleted. */
    public record Contents(long logins, long doctors, long patients, long appointments) {
    }

    /** A deleted clinic that can still be brought back. */
    public record Deleted(UUID token, String name) {
    }

    private final JdbcClient jdbc;

    ClinicRemoval(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Contents contents(long clinicId) {
        return new Contents(count("staff_user", clinicId), count("doctor", clinicId), count("patient", clinicId),
                count("appointment", clinicId));
    }

    /**
     * Deletes the clinic, keeping its rows aside for {@link #UNDO_WINDOW}.
     *
     * @return the key to undo it with
     * @throws NotFoundException if there is no such clinic
     */
    @Transactional
    public UUID remove(long clinicId, Instant now) {
        String name = jdbc.sql("select name from clinic where id = :clinicId for update").param("clinicId", clinicId)
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        UUID token = UUID.randomUUID();
        // One JSON object: {"clinic": [row], "reminder": [rows], ...}
        String parts = "'clinic', (select jsonb_agg(to_jsonb(t)) from clinic t where t.id = :clinicId)" + TABLES.stream()
                .map(table -> ", '%1$s', (select coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb) from %1$s t where t.clinic_id = :clinicId)"
                        .formatted(table))
                .collect(Collectors.joining());
        jdbc.sql("insert into deleted_clinic (token, name, deleted_at, data) values (:token, :name, :now, jsonb_build_object("
                        + parts + "))")
                .param("token", token).param("name", name).param("now", utc(now)).param("clinicId", clinicId).update();
        for (String table : TABLES) {
            jdbc.sql("delete from " + table + " where clinic_id = :clinicId").param("clinicId", clinicId).update();
        }
        jdbc.sql("delete from clinic where id = :clinicId").param("clinicId", clinicId).update();
        return token;
    }

    /** The clinics deleted within the last {@link #UNDO_WINDOW}, latest first. */
    @Transactional(readOnly = true)
    public List<Deleted> restorable(Instant now) {
        return jdbc.sql("select token, name from deleted_clinic where deleted_at > :cutoff order by deleted_at desc")
                .param("cutoff", utc(now.minus(UNDO_WINDOW)))
                .query((row, n) -> new Deleted(row.getObject("token", UUID.class), row.getString("name"))).list();
    }

    /**
     * Undoes a deletion: every row back as it was.
     *
     * @return the clinic's name
     * @throws NotFoundException     if the key is unknown or the time to undo is over
     * @throws IllegalStateException if something made since is in the way (a login with the same
     *                               username, a clinic on the same WhatsApp number); nothing is restored
     */
    @Transactional
    public String restore(UUID token, Instant now) {
        String name = jdbc.sql("select name from deleted_clinic where token = :token and deleted_at > :cutoff for update")
                .param("token", token).param("cutoff", utc(now.minus(UNDO_WINDOW)))
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("Deleted clinic", token));
        List<String> parentsFirst = new ArrayList<>(TABLES.reversed());
        parentsFirst.addFirst("clinic");
        try {
            for (String table : parentsFirst) {
                // The saved rows keep their ids, so identity columns are written as they were.
                jdbc.sql("""
                                insert into %1$s overriding system value
                                select * from jsonb_populate_recordset(null::%1$s,
                                        (select data -> '%1$s' from deleted_clinic where token = :token))""".formatted(table))
                        .param("token", token).update();
            }
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Could not restore " + name, e);
        }
        jdbc.sql("delete from deleted_clinic where token = :token").param("token", token).update();
        return name;
    }

    /** Forgets, for good, the deleted clinics whose time to undo is over. */
    @Transactional
    public int purge(Instant now) {
        return jdbc.sql("delete from deleted_clinic where deleted_at <= :cutoff").param("cutoff", utc(now.minus(UNDO_WINDOW)))
                .update();
    }

    private long count(String table, long clinicId) {
        return jdbc.sql("select count(*) from " + table + " where clinic_id = :clinicId").param("clinicId", clinicId)
                .query(Long.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
