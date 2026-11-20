package app.clinicreminders.api;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The patients table of the React dashboard, in SQL: each patient with their next appointment, their
 * last visit and how many they've had, their balance and how many share their number; filtered,
 * sorted and one page at a time, with the count of every filter. Every query takes the clinic id
 * (the multi-tenancy rule; this is not a Spring Data repository, so the convention test can't check
 * it). A doctor's login sees only the patients they've had an appointment with, only their own
 * appointments, and neither money nor families.
 */
@Component
class PatientTable {

    enum Filter { ALL, BOOKED, UNBOOKED, OWES, FAMILY, OPTED_OUT }

    enum Sort { NAME, NEXT, LAST, VISITS, BALANCE }

    /**
     * @param nextId     their next booked or confirmed appointment, if any
     * @param lastAt     their latest appointment that has passed and wasn't cancelled or missed
     * @param visits     how many such appointments
     * @param balance    what they owe (negative: in credit); null for a doctor's login
     * @param familySize how many patients have their number, them included; 1 for a doctor's login
     */
    record Row(long id, String name, String phone, String language, boolean optedOut, @Nullable Long nextId,
               @Nullable Instant nextAt, @Nullable Long nextDoctorId, @Nullable Instant lastAt, long visits,
               @Nullable BigDecimal balance, long familySize) {
    }

    /** How many patients each filter finds, for the same search. */
    record Counts(long all, long booked, long unbooked, long owes, long family, long optedOut) {

        long of(Filter filter) {
            return switch (filter) {
                case ALL -> all;
                case BOOKED -> booked;
                case UNBOOKED -> unbooked;
                case OWES -> owes;
                case FAMILY -> family;
                case OPTED_OUT -> optedOut;
            };
        }
    }

    record Page(List<Row> rows, Counts counts) {
    }

    private final JdbcClient jdbc;

    PatientTable(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param doctorId    a doctor's login, or null for the front desk
     * @param name        part of the name, any case
     * @param digits      part of the phone number; empty to match by name only
     * @param descending  the sort's direction; empty values (no next appointment...) always come last
     */
    /** One patient's line, as the table has it (the hover card); empty if not theirs to see. */
    java.util.Optional<Row> one(long clinicId, @Nullable Long doctorId, long patientId, Instant now) {
        return jdbc.sql(base(doctorId != null) + " select * from t where id = :patientId")
                .param("clinicId", clinicId)
                .param("doctorId", doctorId)
                .param("name", "")
                .param("digits", "")
                .param("now", utc(now))
                .param("patientId", patientId)
                .query(PatientTable::row)
                .optional();
    }

    Page find(long clinicId, @Nullable Long doctorId, String name, String digits, Filter filter, Sort sort,
              boolean descending, int page, int size, Instant now) {
        boolean doctor = doctorId != null;
        Filter shown = doctor && (filter == Filter.OWES || filter == Filter.FAMILY) ? Filter.ALL : filter;
        Sort order = doctor && sort == Sort.BALANCE ? Sort.NAME : sort;
        String base = base(doctor);
        List<Row> rows = jdbc.sql(base + " select * from t where " + where(shown) + " order by " + orderBy(order, descending)
                        + " limit :limit offset :offset")
                .param("clinicId", clinicId)
                .param("doctorId", doctorId)
                .param("name", name)
                .param("digits", digits)
                .param("now", utc(now))
                .param("limit", size)
                .param("offset", Math.max(page, 0) * size)
                .query(PatientTable::row)
                .list();
        Counts counts = jdbc.sql(base + """
                         select count(*) as all_patients,
                                count(*) filter (where next_id is not null) as booked,
                                count(*) filter (where next_id is null) as unbooked,
                                count(*) filter (where balance > 0) as owes,
                                count(*) filter (where family_size > 1) as family,
                                count(*) filter (where opted_out) as opted_out
                         from t""")
                .param("clinicId", clinicId)
                .param("doctorId", doctorId)
                .param("name", name)
                .param("digits", digits)
                .param("now", utc(now))
                .query((rs, n) -> new Counts(rs.getLong("all_patients"), rs.getLong("booked"), rs.getLong("unbooked"),
                        rs.getLong("owes"), rs.getLong("family"), rs.getLong("opted_out")))
                .single();
        return new Page(rows, counts);
    }

    private static String base(boolean doctor) {
        String theirs = doctor ? "and a.doctor_id = :doctorId" : "";
        return """
                with t as (
                    select p.id, p.name, p.phone_e164, p.language, p.opted_out,
                           nx.id as next_id, nx.starts_at as next_at, nx.doctor_id as next_doctor_id,
                           lv.last_at, coalesce(lv.visits, 0) as visits,
                           %2$s as balance,
                           %3$s as family_size
                    from patient p
                    left join lateral (select a.id, a.starts_at, a.doctor_id from appointment a
                                       where a.clinic_id = p.clinic_id and a.patient_id = p.id
                                         and a.status in ('BOOKED', 'CONFIRMED') and a.starts_at > :now %1$s
                                       order by a.starts_at
                                       limit 1) nx on true
                    left join lateral (select max(a.starts_at) as last_at, count(*) as visits from appointment a
                                       where a.clinic_id = p.clinic_id and a.patient_id = p.id
                                         and a.starts_at <= :now and a.status not in ('CANCELLED', 'NO_SHOW') %1$s) lv on true
                    where p.clinic_id = :clinicId
                      and (lower(p.name) like '%%' || lower(:name) || '%%'
                           or (:digits <> '' and p.phone_e164 like '%%' || :digits || '%%'))
                      %4$s
                )
                """.formatted(theirs,
                doctor ? "null::numeric" : """
                        (select coalesce(sum(c.amount_usd), 0) from charge c where c.clinic_id = p.clinic_id and c.patient_id = p.id)
                        - (select coalesce(sum(m.amount_usd), 0) from payment m where m.clinic_id = p.clinic_id and m.patient_id = p.id)""",
                doctor ? "1" : "(select count(*) from patient f where f.clinic_id = p.clinic_id and f.phone_e164 = p.phone_e164)",
                doctor ? """
                        and exists (select 1 from appointment d
                                    where d.clinic_id = p.clinic_id and d.patient_id = p.id and d.doctor_id = :doctorId)""" : "");
    }

    private static String where(Filter filter) {
        return switch (filter) {
            case ALL -> "true";
            case BOOKED -> "next_id is not null";
            case UNBOOKED -> "next_id is null";
            case OWES -> "balance > 0";
            case FAMILY -> "family_size > 1";
            case OPTED_OUT -> "opted_out";
        };
    }

    private static String orderBy(Sort sort, boolean descending) {
        String direction = descending ? "desc" : "asc";
        String byName = "lower(name), id";
        return switch (sort) {
            case NAME -> "lower(name) " + direction + ", id";
            case NEXT -> "next_at " + direction + " nulls last, " + byName;
            case LAST -> "last_at " + direction + " nulls last, " + byName;
            case VISITS -> "visits " + direction + ", " + byName;
            case BALANCE -> "balance " + direction + " nulls last, " + byName;
        };
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("phone_e164"), rs.getString("language"),
                rs.getBoolean("opted_out"), rs.getObject("next_id", Long.class), instant(rs, "next_at"),
                rs.getObject("next_doctor_id", Long.class), instant(rs, "last_at"), rs.getLong("visits"),
                rs.getBigDecimal("balance"), rs.getLong("family_size"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
