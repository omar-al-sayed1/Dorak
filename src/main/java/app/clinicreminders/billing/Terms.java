package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import app.clinicreminders.billing.Subscription.MessageCosts;
import org.jspecify.annotations.Nullable;

/**
 * What a clinic is billed by: the general plan, with whatever the clinic's own subscription changes.
 *
 * @param discount6Percent  off the fee when 6 months or more are paid at once
 * @param discount12Percent off the fee when a year or more is paid at once
 * @param graceDays         days after the due day before new bookings pause
 */
public record Terms(BigDecimal monthlyFeeUsd, int discount6Percent, int discount12Percent, int graceDays, MessageCosts messageCosts) {

    public static final int DEFAULT_GRACE_DAYS = 2;
    public static final int MAX_GRACE_DAYS = 90;

    /** The discount for paying this many months at once. */
    public int discountPercent(int months) {
        return months >= 12 ? discount12Percent : months >= 6 ? discount6Percent : 0;
    }

    /** What a month comes to for a clinic that pays this many months at a time. */
    public BigDecimal netMonthlyUsd(int months) {
        return monthlyFeeUsd.multiply(BigDecimal.valueOf(100L - discountPercent(months)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Null is "not set" and passes: a subscription leaves to the plan what it does not change.
     *
     * @throws IllegalArgumentException for a negative fee, a discount outside 0 to 100 or a grace outside 0 to 90 days
     */
    static void check(@Nullable BigDecimal monthlyFeeUsd, @Nullable Integer discount6Percent, @Nullable Integer discount12Percent,
                      @Nullable Integer graceDays) {
        if (monthlyFeeUsd != null && (monthlyFeeUsd.signum() < 0 || monthlyFeeUsd.precision() - monthlyFeeUsd.scale() > 8)) {
            throw new IllegalArgumentException("fee");
        }
        if (discount6Percent != null && (discount6Percent < 0 || discount6Percent > 100)
                || discount12Percent != null && (discount12Percent < 0 || discount12Percent > 100)) {
            throw new IllegalArgumentException("discount");
        }
        if (graceDays != null && (graceDays < 0 || graceDays > MAX_GRACE_DAYS)) {
            throw new IllegalArgumentException("grace");
        }
    }
}
