package app.clinicreminders.ops;

/**
 * Tells the operator that something in production needs them: sends failing, the scheduler stalled,
 * no recent backup. Sending never throws; an alert that can't be delivered is logged instead.
 */
public interface OperatorAlerts {

    void send(String text);
}
