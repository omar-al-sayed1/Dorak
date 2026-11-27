package app.clinicreminders.clinic;

/**
 * A clinic's WhatsApp was turned on or off (ClinicSettings#changeWhatsApp). Published inside the
 * change's transaction: the reminders of its upcoming appointments are planned again to match.
 */
public record WhatsAppModeChanged(long clinicId, boolean enabled) {
}
