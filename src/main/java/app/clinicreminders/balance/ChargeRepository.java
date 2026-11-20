package app.clinicreminders.balance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ChargeRepository extends Repository<Charge, Long> {

    Optional<Charge> findByClinicIdAndAppointmentId(Long clinicId, Long appointmentId);

    List<Charge> findByClinicIdAndAppointmentIdIn(Long clinicId, Collection<Long> appointmentIds);

    List<Charge> findByClinicIdAndPatientIdInOrderByCreatedAt(Long clinicId, Collection<Long> patientIds);

    List<Charge> findByClinicIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long clinicId, Instant from, Instant to);

    /** Each patient's charges in all: [patientId, total]. */
    @Query("select c.patientId, sum(c.amountUsd) from Charge c where c.clinicId = :clinicId group by c.patientId")
    List<Object[]> chargedByPatient(Long clinicId);

    Charge save(Charge charge);
}
