package app.clinicreminders.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Subscription.MessageCosts;
import app.clinicreminders.billing.SubscriptionPayment.Method;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Deleting a clinic takes everything of it and nothing of another clinic. */
class ClinicRemovalIT extends IntegrationTest {

    @Autowired
    ClinicRemoval removal;

    @Autowired
    Billing billing;

    /** Every table that carries a clinic_id, as the database has them. */
    private List<String> tenantTables() {
        return jdbc.queryForList("""
                select table_name from information_schema.columns
                where table_schema = current_schema() and column_name = 'clinic_id' order by table_name""", String.class);
    }

    @Test
    void everyTableWithAClinicIdIsCleared() {
        assertThat(ClinicRemoval.TABLES).as("a new tenant table must be added to ClinicRemoval.TABLES")
                .containsExactlyInAnyOrderElementsOf(tenantTables());
    }

    @Test
    void removesEverythingOfTheClinicAndNothingOfAnother() {
        Clinic clinic = fullClinic("A", "+96170111111");
        Clinic other = fullClinic("B", "+96170222222");
        assertThat(removal.contents(clinic.getId())).isEqualTo(new ClinicRemoval.Contents(1, 1, 1, 1));

        removal.remove(clinic.getId(), clock.instant());

        for (String table : tenantTables()) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where clinic_id = ?", Integer.class, clinic.getId()))
                    .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from clinic where id = ?", Integer.class, clinic.getId())).isZero();
        assertThat(removal.contents(other.getId())).as("the other clinic is untouched").isEqualTo(new ClinicRemoval.Contents(1, 1, 1, 1));
        for (String table : List.of("reminder", "message_log", "charge", "payment", "schedule_rule", "subscription", "subscription_payment")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where clinic_id = ?", Integer.class, other.getId()))
                    .as(table).isEqualTo(1);
        }
        assertThatThrownBy(() -> removal.remove(clinic.getId(), clock.instant())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void undoingADeletionPutsEveryRowBackAsItWas() {
        Clinic clinic = fullClinic("A", "+96170111111");
        fullClinic("B", "+96170222222");
        Map<String, List<Map<String, Object>>> before = rows(clinic.getId());

        UUID token = removal.remove(clinic.getId(), clock.instant());
        assertThat(removal.restorable(clock.instant())).containsExactly(new ClinicRemoval.Deleted(token, "A"));
        clock.setInstant(clock.instant().plus(ClinicRemoval.UNDO_WINDOW).minusSeconds(1));

        assertThat(removal.restore(token, clock.instant())).isEqualTo("A");

        assertThat(rows(clinic.getId())).isEqualTo(before);
        assertThat(removal.restorable(clock.instant())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from deleted_clinic", Integer.class)).as("nothing is kept once restored").isZero();
        assertThatThrownBy(() -> removal.restore(token, clock.instant())).as("only once").isInstanceOf(NotFoundException.class);
        // New rows still get fresh ids after the old ones came back with theirs.
        assertThat(data.patient(clinic, "+96170333333").getId()).isNotNull();
    }

    @Test
    void afterAFewMinutesTheDeletionIsForGood() {
        Clinic clinic = fullClinic("A", "+96170111111");
        UUID token = removal.remove(clinic.getId(), clock.instant());
        assertThat(removal.purge(clock.instant())).as("still within the time to undo").isZero();

        clock.setInstant(clock.instant().plus(ClinicRemoval.UNDO_WINDOW));

        assertThat(removal.restorable(clock.instant())).isEmpty();
        assertThatThrownBy(() -> removal.restore(token, clock.instant())).isInstanceOf(NotFoundException.class);
        assertThat(removal.purge(clock.instant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deleted_clinic", Integer.class)).isZero();
    }

    @Test
    void aDeletionIsNotUndoneOverSomethingMadeSince() {
        Clinic clinic = fullClinic("A", "+96170111111");
        UUID token = removal.remove(clinic.getId(), clock.instant());
        Clinic newer = data.clinic("C");
        jdbc.update("insert into staff_user (clinic_id, username, password_hash, role, language) values (?, 'DOCTOR-a', '{noop}x', 'OWNER', 'en')",
                newer.getId());

        assertThatThrownBy(() -> removal.restore(token, clock.instant())).as("its doctor's username is taken now")
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from clinic where id = ?", Integer.class, clinic.getId())).as("all or nothing").isZero();
        assertThat(removal.restorable(clock.instant())).hasSize(1);
    }

    /** Every row of the clinic, by table, each table in a fixed order. */
    private Map<String, List<Map<String, Object>>> rows(long clinicId) {
        Map<String, List<Map<String, Object>>> all = new TreeMap<>();
        all.put("clinic", jdbc.queryForList("select to_jsonb(t)::text as row from clinic t where id = ?", clinicId));
        for (String table : tenantTables()) {
            all.put(table, jdbc.queryForList("select to_jsonb(t)::text as row from " + table + " t where clinic_id = ? order by 1", clinicId));
        }
        return all;
    }

    /** A clinic with something in the tables that point at each other. */
    private Clinic fullClinic(String name, String phone) {
        Clinic clinic = data.clinic(name);
        long id = clinic.getId();
        Doctor doctor = data.doctor(clinic, "Dr. " + name, 30);
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");
        Patient patient = data.patient(clinic, phone);
        Appointment appointment = data.appointment(doctor, patient, clock.instant().plusSeconds(86_400), Duration.ofMinutes(30));
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("insert into staff_user (clinic_id, username, password_hash, role, language, doctor_id) "
                + "values (?, ?, '{noop}x', 'DOCTOR', 'en', ?)", id, "doctor-" + name.toLowerCase(), doctor.getId());
        jdbc.update("insert into reminder (clinic_id, appointment_id, kind, due_at, status) values (?, ?, 'H24', ?, 'PENDING')", id, appointment.getId(), now);
        jdbc.update("insert into message_log (clinic_id, appointment_id, direction, kind, status, created_at) "
                + "values (?, ?, 'OUT', 'TEMPLATE', 'SENT', ?)", id, appointment.getId(), now);
        jdbc.update("insert into charge (clinic_id, patient_id, appointment_id, amount_usd, created_at) values (?, ?, ?, 20, ?)",
                id, patient.getId(), appointment.getId(), now);
        jdbc.update("insert into payment (clinic_id, patient_id, appointment_id, amount_usd, method, paid_at) "
                + "values (?, ?, ?, 20, 'CASH', ?)", id, patient.getId(), appointment.getId(), now);
        billing.save(id, 1, LocalDate.of(2026, 11, 1), null, new BigDecimal("50"), null, null, null, MessageCosts.INCLUDED, clock.instant());
        billing.record(id, 1, null, Method.CASH, null, LocalDate.of(2026, 10, 14), "omar", clock.instant());
        return clinic;
    }
}
