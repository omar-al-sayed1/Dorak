package app.clinicreminders.balance;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** What one visit costs. Never a description: nothing medical is kept (CLAUDE.md, Privacy). */
@Entity
@Table(name = "charge")
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "amount_usd")
    private BigDecimal amountUsd;

    /** When the fee was first set: the visit's day as a rule, so the month it counts in. */
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected Charge() {
    }

    Charge(long clinicId, long patientId, @Nullable Long appointmentId, BigDecimal amountUsd, Instant createdAt) {
        this.clinicId = clinicId;
        this.patientId = patientId;
        this.appointmentId = appointmentId;
        this.amountUsd = Money.usd(amountUsd);
        this.createdAt = createdAt;
    }

    void change(BigDecimal amount) {
        this.amountUsd = Money.usd(amount);
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public Long getPatientId() {
        return patientId;
    }

    /** Null for an amount added to the account rather than charged for a visit. */
    public @Nullable Long getAppointmentId() {
        return appointmentId;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
