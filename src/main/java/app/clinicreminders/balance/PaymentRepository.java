package app.clinicreminders.balance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PaymentRepository extends Repository<Payment, Long> {

    Optional<Payment> findByIdAndClinicId(Long id, Long clinicId);

    List<Payment> findByClinicIdAndAppointmentIdInOrderByPaidAt(Long clinicId, Collection<Long> appointmentIds);

    List<Payment> findByClinicIdAndPatientIdInOrderByPaidAt(Long clinicId, Collection<Long> patientIds);

    List<Payment> findByClinicIdAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAt(Long clinicId, Instant from,
                                                                                        Instant to);

    @CrossTenant("Public receipt page: the unguessable token is the key, and the page shows one payment")
    Optional<Payment> findByReceiptToken(UUID receiptToken);

    /** Each patient's payments in all: [patientId, total]. */
    @Query("select p.patientId, sum(p.amountUsd) from Payment p where p.clinicId = :clinicId group by p.patientId")
    List<Object[]> paidByPatient(Long clinicId);

    Payment save(Payment payment);

    void delete(Payment payment);
}
