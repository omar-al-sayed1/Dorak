package app.clinicreminders.scheduling;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Doctors, weekly hours, closed days and extra slots, as edited on the settings and week pages,
 * and the clinic's appointment length, set by the operator. Always within one clinic.
 */
@Service
@Transactional
public class ScheduleSettings {

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;
    private final ExtraSlotRepository extraSlots;
    private final SlotService slots;

    ScheduleSettings(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
                     ScheduleExceptionRepository exceptions, ExtraSlotRepository extraSlots, SlotService slots) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
        this.extraSlots = extraSlots;
        this.slots = slots;
    }

    /** A new doctor takes the clinic's appointment length. */
    public Doctor addDoctor(long clinicId, String displayName) {
        return doctors.save(new Doctor(clinicId, requireName(displayName), clinic(clinicId).getSlotMinutes()));
    }

    /**
     * The operator's appointment length for the clinic, applied to all its doctors. Existing
     * appointments keep their times; new slots use the new length.
     *
     * @throws IllegalArgumentException outside 5 to 240 minutes; nothing is changed
     */
    public void changeSlotLength(long clinicId, int minutes) {
        clinic(clinicId).changeSlotMinutes(minutes);
        doctors.findByClinicIdOrderByDisplayName(clinicId).forEach(doctor -> doctor.changeSlotMinutes(minutes));
    }

    /**
     * Takes the doctor out of the clinic: their hours and days off go, and they are left out of
     * lists and booking. The doctor itself stays for the history of their past appointments; the
     * caller makes sure none are still to come.
     */
    public void deleteDoctor(long clinicId, long doctorId, Instant now) {
        Doctor doctor = doctor(clinicId, doctorId);
        rules.findByClinicIdAndDoctorId(clinicId, doctorId).forEach(rules::delete);
        exceptions.findByClinicIdAndDoctorId(clinicId, doctorId).forEach(exceptions::delete);
        extraSlots.findByClinicIdAndDoctorId(clinicId, doctorId).forEach(extraSlots::delete);
        doctor.delete(now);
    }

    public void updateDoctor(long clinicId, long doctorId, String displayName, boolean active) {
        Doctor doctor = doctor(clinicId, doctorId);
        doctor.rename(requireName(displayName));
        if (active) {
            doctor.activate();
        } else {
            doctor.deactivate();
        }
    }

    public void addWeeklyHours(long clinicId, long doctorId, DayOfWeek day, TimeWindow window) {
        doctor(clinicId, doctorId);
        rules.save(new ScheduleRule(clinicId, doctorId, day, window));
    }

    /** Only that doctor's hours: another doctor's (or clinic's) are not found. */
    public void removeWeeklyHours(long clinicId, long doctorId, long ruleId) {
        rules.delete(rule(clinicId, doctorId, ruleId));
    }

    /** Moves or stretches one window of the weekly hours (dragged on the hours chart). */
    public void changeWeeklyHours(long clinicId, long doctorId, long ruleId, TimeWindow window) {
        rule(clinicId, doctorId, ruleId).change(window);
    }

    /** {@code from}'s hours on each of {@code to}, replacing what those days had; {@code from} itself is left as it is. */
    public void copyDay(long clinicId, long doctorId, DayOfWeek from, Collection<DayOfWeek> to) {
        doctor(clinicId, doctorId);
        List<ScheduleRule> all = rules.findByClinicIdAndDoctorId(clinicId, doctorId);
        List<TimeWindow> windows = all.stream().filter(rule -> rule.getDayOfWeek() == from).map(ScheduleRule::window).toList();
        for (DayOfWeek day : to) {
            if (day == from) {
                continue;
            }
            all.stream().filter(rule -> rule.getDayOfWeek() == day).forEach(rules::delete);
            windows.forEach(window -> rules.save(new ScheduleRule(clinicId, doctorId, day, window)));
        }
    }

    /**
     * One more slot on {@code date} at {@code start}, one appointment long. It may not overlap the
     * hours that day (nor another extra slot): those already have their slots.
     *
     * @throws IllegalArgumentException if it overlaps, or would run past midnight
     */
    public ExtraSlot addSlot(long clinicId, long doctorId, LocalDate date, LocalTime start) {
        Duration length = doctor(clinicId, doctorId).slotLength();
        if (start.plus(length).isBefore(start) || start.plus(length).equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException("A slot can't run past midnight: " + start);
        }
        TimeWindow slot = new TimeWindow(start, start.plus(length));
        boolean overlaps = slots.windowsOn(clinicId, doctorId, date).stream()
                .anyMatch(open -> open.start().isBefore(slot.end()) && slot.start().isBefore(open.end()));
        if (overlaps) {
            throw new IllegalArgumentException("Already open then: " + date + " " + start);
        }
        return extraSlots.save(new ExtraSlot(clinicId, doctorId, date, start));
    }

    /** Only that doctor's slot: another doctor's (or clinic's) is not found. An appointment in it stays. */
    public void removeSlot(long clinicId, long doctorId, long slotId) {
        extraSlots.delete(extraSlots.findByIdAndClinicId(slotId, clinicId)
                .filter(slot -> slot.getDoctorId() == doctorId)
                .orElseThrow(() -> new NotFoundException("Slot", slotId)));
    }

    private ScheduleRule rule(long clinicId, long doctorId, long ruleId) {
        return rules.findByIdAndClinicId(ruleId, clinicId)
                .filter(rule -> rule.getDoctorId() == doctorId)
                .orElseThrow(() -> new NotFoundException("Hours", ruleId));
    }

    /**
     * Closes the doctor on {@code date}, or sets special hours that day (null {@code hours} means
     * closed). Replaces any earlier exception for that date.
     */
    public void setDay(long clinicId, long doctorId, LocalDate date, @Nullable TimeWindow hours) {
        doctor(clinicId, doctorId);
        exceptions.findByClinicIdAndDoctorIdAndExceptionDate(clinicId, doctorId, date).ifPresentOrElse(
                existing -> existing.change(hours),
                () -> exceptions.save(hours == null
                        ? ScheduleException.closed(clinicId, doctorId, date)
                        : ScheduleException.withHours(clinicId, doctorId, date, hours)));
    }

    /** Only that doctor's day: another doctor's (or clinic's) is not found. */
    public void removeDay(long clinicId, long doctorId, long exceptionId) {
        exceptions.delete(exceptions.findByIdAndClinicId(exceptionId, clinicId)
                .filter(day -> day.getDoctorId() == doctorId)
                .orElseThrow(() -> new NotFoundException("Day", exceptionId)));
    }

    /** The clinic's doctor; a deleted one is not found. */
    private Doctor doctor(long clinicId, long doctorId) {
        return doctors.findByIdAndClinicId(doctorId, clinicId)
                .filter(doctor -> !doctor.isDeleted())
                .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A doctor needs a name");
        }
        return name.strip();
    }

    private Clinic clinic(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }
}
