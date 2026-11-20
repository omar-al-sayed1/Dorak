package app.clinicreminders.balance;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/** A visit's money at a glance: the chip on Today. */
public enum MoneyStatus {
    /** Not charged yet, nothing paid. */
    NONE,
    /** Charged nothing (a free follow-up). */
    FREE,
    UNPAID,
    PARTLY_PAID,
    PAID;

    static MoneyStatus of(@Nullable BigDecimal charged, BigDecimal paid) {
        if (charged == null) {
            return paid.signum() > 0 ? PAID : NONE;
        }
        if (charged.signum() == 0) {
            return FREE;
        }
        if (paid.signum() == 0) {
            return UNPAID;
        }
        return paid.compareTo(charged) >= 0 ? PAID : PARTLY_PAID;
    }
}
