package app.clinicreminders.balance;

/** A money entry that can't be made; the message is the text key the dashboard shows. */
public class BalanceProblem extends IllegalArgumentException {

    public BalanceProblem(String messageKey) {
        super(messageKey);
    }
}
