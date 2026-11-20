package app.clinicreminders.balance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.jspecify.annotations.Nullable;

/** What a patient paid: in dollars, and in lira with the rate used when paid in lira. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "patient_id")
    private Long patientId;

    /** The visit it was for; null if that appointment is gone. */
    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "amount_usd")
    private BigDecimal amountUsd;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(name = "amount_lbp")
    private BigDecimal amountLbp;

    @Column(name = "lbp_per_usd")
    private BigDecimal lbpPerUsd;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Key of the public receipt page: random, set by the database. */
    @Generated
    @Column(name = "receipt_token", insertable = false, updatable = false)
    private UUID receiptToken;

    protected Payment() {
    }

    Payment(long clinicId, long patientId, @Nullable Long appointmentId, BigDecimal amountUsd, PaymentMethod method,
            @Nullable BigDecimal amountLbp, @Nullable BigDecimal lbpPerUsd, Instant paidAt) {
        this.clinicId = clinicId;
        this.patientId = patientId;
        this.appointmentId = appointmentId;
        this.amountUsd = Money.usd(amountUsd);
        this.method = method;
        this.amountLbp = amountLbp;
        this.lbpPerUsd = lbpPerUsd;
        this.paidAt = paidAt;
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

    public @Nullable Long getAppointmentId() {
        return appointmentId;
    }

    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public @Nullable BigDecimal getAmountLbp() {
        return amountLbp;
    }

    public @Nullable BigDecimal getLbpPerUsd() {
        return lbpPerUsd;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public UUID getReceiptToken() {
        return receiptToken;
    }
}
