package app.clinicreminders.billing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

public interface SubscriptionPaymentRepository extends Repository<SubscriptionPayment, Long> {

    /** Latest period first. */
    List<SubscriptionPayment> findByClinicIdOrderByPeriodEndDescIdDesc(Long clinicId);

    Optional<SubscriptionPayment> findFirstByClinicIdOrderByPeriodEndDescIdDesc(Long clinicId);

    boolean existsByClinicId(Long clinicId);

    @CrossTenant("Public invoice page: the unguessable token is the key, and the page shows one payment")
    Optional<SubscriptionPayment> findByInvoiceToken(UUID invoiceToken);

    @CrossTenant("Operator area: what every clinic paid, for the income figures")
    List<SubscriptionPayment> findByPaidOnGreaterThanEqualAndPaidOnLessThan(LocalDate from, LocalDate to);

    @CrossTenant("Operator area: the latest payments of every clinic, on the billing overview")
    List<SubscriptionPayment> findTop8ByOrderByRecordedAtDescIdDesc();

    SubscriptionPayment save(SubscriptionPayment payment);

    void delete(SubscriptionPayment payment);
}
