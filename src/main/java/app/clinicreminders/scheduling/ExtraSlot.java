package app.clinicreminders.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One slot added on one date, outside the doctor's hours that day (one more patient after the last
 * slot, say). It lasts the doctor's appointment length; the weekly hours don't change.
 */
@Entity
@Table(name = "extra_slot")
public class ExtraSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "slot_date")
    private LocalDate date;

    @Column(name = "start_time")
    private LocalTime startTime;

    protected ExtraSlot() {
    }

    ExtraSlot(long clinicId, long doctorId, LocalDate date, LocalTime startTime) {
        this.clinicId = clinicId;
        this.doctorId = doctorId;
        this.date = date;
        this.startTime = startTime;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getStartTime() {
        return startTime;
    }
}
