package app.clinicreminders.billing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Where a clinic stands with its subscription on a given day, worked out from the day its next
 * payment is due.
 *
 * @param dueOn       the day the next payment is due
 * @param daysLeft    days until then; 0 on the day, negative once it has passed
 * @param graceLeft   days of grace left before new bookings pause; 0 once paused, the whole grace before the due day
 * @param pausesOn    the first day new bookings are paused, if nothing is paid
 */
public record Standing(Status status, LocalDate dueOn, long daysLeft, long graceLeft, LocalDate pausesOn) {

    /** A payment is announced this many days ahead. */
    public static final int NOTICE_DAYS = 7;

    public enum Status {
        /** Nothing paid yet, and nothing due for more than a week. */
        TRIAL,
        ACTIVE,
        /** Due within a week. */
        DUE_SOON,
        /** Due, within the days of grace: everything still works. */
        OVERDUE,
        /** The grace is over: no new bookings; reminders for what is booked still go out. */
        PAUSED
    }

    /**
     * @param paidUntil  the day the next payment is due
     * @param graceDays  days after it before the pause
     * @param anyPayment false until the clinic's first payment (a trial)
     */
    public static Standing of(LocalDate paidUntil, int graceDays, boolean anyPayment, LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, paidUntil);
        LocalDate pausesOn = paidUntil.plusDays(graceDays + 1L);
        Status status;
        if (daysLeft > NOTICE_DAYS) {
            status = anyPayment ? Status.ACTIVE : Status.TRIAL;
        } else if (daysLeft > 0) {
            status = Status.DUE_SOON;
        } else {
            status = today.isBefore(pausesOn) ? Status.OVERDUE : Status.PAUSED;
        }
        long graceLeft = Math.clamp(ChronoUnit.DAYS.between(today, pausesOn), 0, graceDays + 1L);
        return new Standing(status, paidUntil, daysLeft, graceLeft, pausesOn);
    }

    public boolean paused() {
        return status == Status.PAUSED;
    }

    /** Worth telling the clinic about: due soon, overdue or paused. */
    public boolean needsNotice() {
        return status == Status.DUE_SOON || status == Status.OVERDUE || status == Status.PAUSED;
    }

    /** Days since the payment was due; 0 before then. */
    public long daysOverdue() {
        return Math.max(-daysLeft, 0);
    }
}
