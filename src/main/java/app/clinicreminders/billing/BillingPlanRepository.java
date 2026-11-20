package app.clinicreminders.billing;

import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

public interface BillingPlanRepository extends Repository<BillingPlan, Short> {

    @CrossTenant("The general plan is the operator's: one row, no clinic")
    Optional<BillingPlan> findById(Short id);

    BillingPlan save(BillingPlan plan);
}
