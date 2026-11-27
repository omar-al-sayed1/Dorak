package app.clinicreminders.billing;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.clinicreminders.common.AppProperties;

/**
 * Whish for development and tests ({@code whish.client=fake}): its "payment page" is Dorak's own
 * {@code /dev/whish/{id}}, where you press Pay or Cancel. Nothing leaves the machine. Kept in memory:
 * a restart forgets unfinished payments, which then expire like abandoned ones.
 */
public class FakeWhish implements PaymentGateway {

    private record Attempt(Checkout checkout, Status status, String reference) {
    }

    private final AppProperties app;
    private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();

    public FakeWhish(AppProperties app) {
        this.app = app;
    }

    @Override
    public URI checkout(Checkout checkout) {
        attempts.put(checkout.paymentId(), new Attempt(checkout, Status.PENDING, "FAKE-" + checkout.paymentId().toString().substring(0, 8)));
        return URI.create(app.link("/dev/whish/" + checkout.paymentId()));
    }

    @Override
    public Result status(UUID paymentId) {
        Attempt attempt = attempts.get(paymentId);
        if (attempt == null) {
            return new Result(Status.PENDING, null);
        }
        return new Result(attempt.status(), attempt.status() == Status.PAID ? attempt.reference() : null);
    }

    /** What the payer is asked to pay, for the dev page. */
    public Optional<Checkout> checkoutOf(UUID paymentId) {
        return Optional.ofNullable(attempts.get(paymentId)).map(Attempt::checkout);
    }

    /** The payer pays (dev page, tests). */
    public void pay(UUID paymentId) {
        attempts.computeIfPresent(paymentId, (id, attempt) -> new Attempt(attempt.checkout(), Status.PAID, attempt.reference()));
    }

    /** The payer gives up, or the payment is refused (dev page, tests). */
    public void decline(UUID paymentId) {
        attempts.computeIfPresent(paymentId, (id, attempt) -> new Attempt(attempt.checkout(), Status.FAILED, attempt.reference()));
    }
}
