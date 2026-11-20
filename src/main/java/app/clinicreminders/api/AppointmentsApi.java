package app.clinicreminders.api;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Today's actions for the React dashboard, as the classic Today has them: arrived, no-show, cancel,
 * and undoing arrived or no-show. A doctor's login acts on their own appointments only; an action
 * that no longer applies (cancelled meanwhile, not started yet) is 409 (ApiErrors).
 */
@RestController
class AppointmentsApi {

    private final ClinicContext context;
    private final AppointmentService appointmentService;
    private final AppointmentRepository appointments;

    AppointmentsApi(ClinicContext context, AppointmentService appointmentService, AppointmentRepository appointments) {
        this.context = context;
        this.appointmentService = appointmentService;
        this.appointments = appointments;
    }

    @PostMapping("/api/appointments/{id}/arrived")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void arrived(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        appointmentService.markArrived(seen(staff, request, id).clinicId(), id);
    }

    @PostMapping("/api/appointments/{id}/no-show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void noShow(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        appointmentService.markNoShow(seen(staff, request, id).clinicId(), id);
    }

    /** As on the classic Today: the patient is not messaged (that would need its own approved template). */
    @PostMapping("/api/appointments/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        appointmentService.cancel(seen(staff, request, id).clinicId(), id, CancelledBy.STAFF);
    }

    /** The toast's Undo after arrived or no-show. */
    @PostMapping("/api/appointments/{id}/unmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unmark(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        appointmentService.unmark(seen(staff, request, id).clinicId(), id);
    }

    /** The clinic's appointment, and a doctor's own; any other is not found. */
    private Ctx seen(StaffPrincipal staff, HttpServletRequest request, long id) {
        Ctx ctx = context.of(staff, request);
        Appointment appointment = appointments.findByIdAndClinicId(id, ctx.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", id));
        ctx.requireSees(appointment.getDoctorId(), "Appointment", id);
        return ctx;
    }
}
