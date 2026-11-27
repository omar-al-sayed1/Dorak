package app.clinicreminders.clinic;

import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClinicSettings {

    public static final int NAME_MAX = 100;

    private final ClinicRepository clinics;
    private final ApplicationEventPublisher events;

    ClinicSettings(ClinicRepository clinics, ApplicationEventPublisher events) {
        this.clinics = clinics;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Clinic get(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }

    /** Every clinic, by name: the operator's list. */
    @Transactional(readOnly = true)
    public List<Clinic> all() {
        return clinics.findAllByOrderByNameAscIdAsc();
    }

    /**
     * Onboarding: a new clinic, not yet connected to WhatsApp.
     *
     * @throws IllegalArgumentException if the name is blank or longer than {@value #NAME_MAX} characters
     */
    public Clinic create(String name, ZoneId timezone, Language defaultLanguage) {
        return create(name, timezone, defaultLanguage, null, true);
    }

    /**
     * @param whatsApp whether the clinic uses WhatsApp (reminders and booking); off is dashboard only
     * @throws IllegalArgumentException also for an email that does not look like one ({@link Clinic#email})
     */
    public Clinic create(String name, ZoneId timezone, Language defaultLanguage, @Nullable String email, boolean whatsApp) {
        Clinic clinic = new Clinic(requireName(name), timezone, defaultLanguage);
        clinic.changeContactEmail(email);
        clinic.changeWhatsAppEnabled(whatsApp);
        return clinics.save(clinic);
    }

    /**
     * Turns the clinic's WhatsApp on or off. Off: no reminders and no messages from now on, pending
     * reminders included. On again: the reminders of its upcoming appointments are planned again.
     *
     * @return whether anything changed
     */
    public boolean changeWhatsApp(long clinicId, boolean enabled) {
        Clinic clinic = get(clinicId);
        if (clinic.isWhatsAppEnabled() == enabled) {
            return false;
        }
        clinic.changeWhatsAppEnabled(enabled);
        events.publishEvent(new WhatsAppModeChanged(clinicId, enabled));
        return true;
    }

    /** The operator's edit. @throws IllegalArgumentException for a blank or too long name */
    public void updateDetails(long clinicId, String name, ZoneId timezone, Language defaultLanguage, @Nullable String email) {
        Clinic clinic = get(clinicId);
        clinic.changeContactEmail(email);
        clinic.rename(requireName(name));
        clinic.changeTimezone(timezone);
        clinic.changeDefaultLanguage(defaultLanguage);
    }

    /** @throws IllegalArgumentException if the address or hours are too long; nothing is saved */
    public void update(long clinicId, Language defaultLanguage, boolean h24Enabled, boolean h2Enabled,
                       @Nullable String address, @Nullable String hoursText) {
        Clinic clinic = get(clinicId);
        clinic.changeContactDetails(address, hoursText);
        clinic.changeDefaultLanguage(defaultLanguage);
        clinic.configureReminders(h24Enabled, h2Enabled);
    }

    private static String requireName(String name) {
        String stripped = name.strip();
        if (stripped.isEmpty() || stripped.length() > NAME_MAX) {
            throw new IllegalArgumentException("A clinic name is 1-" + NAME_MAX + " characters");
        }
        return stripped;
    }
}
