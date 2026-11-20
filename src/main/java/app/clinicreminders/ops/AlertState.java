package app.clinicreminders.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which problems the operator has been told about, so a lasting problem is not alerted every minute:
 * a new problem is alerted at once, one that lasts again after {@code repeatAfter}, and one that
 * clears gets a "back to normal" message. Kept in memory: after a restart, a problem still there is
 * alerted once more, which is what you want after a restart anyway.
 */
final class AlertState {

    /** What is wrong right now. {@code key} identifies the problem across checks; the texts are ready to send. */
    record Problem(String key, String alert, String resolved) {
    }

    private record Open(Problem problem, Instant alertedAt) {
    }

    private final Duration repeatAfter;
    private final Map<String, Open> open = new HashMap<>();

    AlertState(Duration repeatAfter) {
        this.repeatAfter = repeatAfter;
    }

    /**
     * @param complete false when the check could not look at everything (the database is down): then
     *                 problems it did not see are not taken as resolved
     * @return the messages to send now, given the problems found by this check
     */
    synchronized List<String> update(List<Problem> problems, boolean complete, Instant now) {
        List<String> messages = new ArrayList<>();
        Map<String, Problem> current = new HashMap<>();
        for (Problem problem : problems) {
            current.put(problem.key(), problem);
            Open known = open.get(problem.key());
            if (known == null || !now.isBefore(known.alertedAt().plus(repeatAfter))) {
                messages.add(problem.alert());
                open.put(problem.key(), new Open(problem, now));
            } else {
                // Still the same problem: keep the newest texts (counts change) for the next repeat.
                open.put(problem.key(), new Open(problem, known.alertedAt()));
            }
        }
        open.entrySet().removeIf(entry -> {
            if (!complete || current.containsKey(entry.getKey())) {
                return false;
            }
            messages.add(entry.getValue().problem().resolved());
            return true;
        });
        return messages;
    }
}
