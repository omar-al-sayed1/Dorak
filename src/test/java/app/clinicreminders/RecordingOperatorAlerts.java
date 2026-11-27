package app.clinicreminders;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.clinicreminders.ops.OperatorAlerts;

/** Keeps the operator alerts sent during a test, instead of sending them. */
public class RecordingOperatorAlerts implements OperatorAlerts {

    private final List<String> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String text) {
        sent.add(text);
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
    }
}
