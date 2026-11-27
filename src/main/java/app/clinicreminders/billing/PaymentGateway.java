package app.clinicreminders.billing;

import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * An online payment provider (Whish): it hosts the page where the clinic owner pays, and says
 * whether a payment went through. Dorak never handles card or wallet details.
 *
 * <p>Only a fake exists so far ({@link FakeWhish}, dev and tests). The real Whish client is added
 * once Whish provides its current API documentation and sandbox credentials; until then online
 * payment is off in production ({@code WHISH_CLIENT} unset).
 */
public interface PaymentGateway {

    /**
     * @param paymentId   our id for the attempt; the provider echoes it back
     * @param description shown to the payer on the provider's page
     * @param returnUrl   where the provider sends the payer back, paid or not
     */
    record Checkout(UUID paymentId, BigDecimal amountUsd, String description, URI returnUrl) {
    }

    enum Status { PENDING, PAID, FAILED }

    /** @param reference the provider's own reference for a paid payment */
    record Result(Status status, @Nullable String reference) {
    }

    /**
     * Starts a payment.
     *
     * @return the provider's page to send the payer to
     * @throws RuntimeException when the provider can't be reached or refuses
     */
    URI checkout(Checkout checkout);

    /**
     * Asks the provider, server to server, how the payment stands. The only thing a payment is
     * recorded on: what comes back in the payer's browser could be forged.
     */
    Result status(UUID paymentId);
}
