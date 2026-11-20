package app.clinicreminders.billing;

import java.util.List;
import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

public interface SubscriptionRepository extends Repository<Subscription, Long> {

    Optional<Subscription> findByClinicId(Long clinicId);

    @CrossTenant("Operator area: the operator bills every clinic")
    List<Subscription> findAll();

    Subscription save(Subscription subscription);
}
