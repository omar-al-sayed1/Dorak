package app.clinicreminders.dashboard;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.clinicreminders.billing.Billing;
import app.clinicreminders.billing.Subscription;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRemoval;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.dashboard.ClinicSignals.Quick;
import app.clinicreminders.dashboard.ClinicSignals.Signals;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.reminder.ReminderKind;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.UsernameTakenException;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppConnections;
import app.clinicreminders.whatsapp.WhatsAppConnections.ConnectionException;
import app.clinicreminders.whatsapp.WhatsAppException;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.WhatsAppMessenger.SendOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Operator onboarding (SPEC section 5.5): clinics, their WhatsApp connection, a test message and
 * their logins. Only operators reach {@code /operator/**} (see {@code SecurityConfig}); here the
 * clinic comes from the path, because the operator runs every clinic.
 */
@Controller
@RequestMapping("/operator")
class OperatorController {

    /** Meta's sample template on the test accounts it creates ("Get started" guide). */
    static final String HELLO_WORLD = "hello_world";
    static final String HELLO_WORLD_LANGUAGE = "en_US";

    /**
     * @param ready    how many of the clinic's {@code steps} onboarding steps are done
     * @param whatsApp off: a dashboard-only clinic, with no WhatsApp to connect
     */
    record ClinicRow(long id, String name, String timezone, String language, int slotMinutes, boolean connected, long logins,
                     int ready, int steps, boolean whatsApp, @Nullable String billing, Signals signals) {

        /** Not paid in time: overdue, or paused after the grace. */
        boolean late() {
            return "overdue".equals(billing) || "paused".equals(billing);
        }

        public int readyPercent() {
            return 100 * ready / steps;
        }
    }

    /** What the operator types to delete a clinic, whatever the page's language. */
    static final String DELETE_WORD = "delete";

    /** Details, phone number id, business account id, access token, a login. */
    static final int SETUP_STEPS = 5;

    /** Without WhatsApp: details and a login. */
    static final int SETUP_STEPS_NO_WHATSAPP = 2;

    /** The result of a test message, shown once on the clinic page. */
    record TestOutcome(String result, String detail) {
    }

    private final ClinicSettings clinics;
    private final WhatsAppConnections connections;
    private final StaffAccounts accounts;
    private final ScheduleSettings scheduleSettings;
    private final WhatsAppMessenger messenger;
    private final ReminderMessages reminderMessages;
    private final DoctorRepository doctors;
    private final MessageSource messages;
    private final Clock clock;
    private final Billing billing;
    private final ClinicRemoval removal;
    private final ClinicSignals signals;

    OperatorController(ClinicSettings clinics, WhatsAppConnections connections, StaffAccounts accounts,
                       ScheduleSettings scheduleSettings,
                       WhatsAppMessenger messenger, ReminderMessages reminderMessages, DoctorRepository doctors,
                       MessageSource messages, Clock clock, Billing billing, ClinicRemoval removal, ClinicSignals signals) {
        this.signals = signals;
        this.billing = billing;
        this.removal = removal;
        this.clinics = clinics;
        this.connections = connections;
        this.accounts = accounts;
        this.scheduleSettings = scheduleSettings;
        this.messenger = messenger;
        this.reminderMessages = reminderMessages;
        this.doctors = doctors;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Every clinic, narrowed by a search on the name and by three filters.
     *
     * @param whatsapp "connected" or "not-connected"
     * @param setup    "done" or "unfinished"
     * @param billed   "paying" (trial to due soon) or "late" (overdue or paused)"
     */
    @GetMapping
    String clinics(Model model, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String whatsapp,
                   @RequestParam(defaultValue = "") String setup, @RequestParam(defaultValue = "") String billed,
                   // Quick filters ({@link ClinicSignals}); a clinic must match every one that is on.
                   @RequestParam(defaultValue = "false") boolean soon, @RequestParam(defaultValue = "false") boolean unpaid,
                   @RequestParam(defaultValue = "false") boolean quiet) {
        billing.enrolAll(LocalDate.ofInstant(clock.instant(), Clinic.DEFAULT_ZONE), clock.instant());
        Map<Long, Subscription> subscriptions = billing.all().stream()
                .collect(Collectors.toMap(Subscription::getClinicId, Function.identity()));
        List<Clinic> everyClinic = clinics.all();
        Map<Long, Signals> signals = this.signals.of(everyClinic, subscriptions);
        Quick quick = new Quick(soon, unpaid, quiet);
        List<ClinicRow> all = everyClinic.stream()
                .map(c -> {
                    long logins = accounts.countLogins(c.getId());
                    Subscription subscription = subscriptions.get(c.getId());
                    return new ClinicRow(c.getId(), c.getName(), c.getTimezone().getId(), c.getDefaultLanguage().code(),
                            c.getSlotMinutes(), c.getWaPhoneNumberId() != null && c.getWaAccessTokenEncrypted() != null, logins,
                            readySteps(c, logins), setupSteps(c), c.isWhatsAppEnabled(), subscription == null ? null : BillingViews.key(
                                    billing.standing(subscription, LocalDate.ofInstant(clock.instant(), c.getTimezone()))),
                            signals.get(c.getId()));
                })
                .toList();
        String wanted = q.strip().toLowerCase(Locale.ROOT);
        List<ClinicRow> rows = all.stream()
                .filter(c -> c.name().toLowerCase(Locale.ROOT).contains(wanted))
                .filter(c -> quick.matches(c.signals()))
                .filter(c -> switch (whatsapp) {
                    case "connected" -> c.whatsApp() && c.connected();
                    case "not-connected" -> c.whatsApp() && !c.connected();
                    case "off" -> !c.whatsApp();
                    default -> true;
                })
                .filter(c -> switch (setup) {
                    case "done" -> c.ready() == c.steps();
                    case "unfinished" -> c.ready() < c.steps();
                    default -> true;
                })
                .filter(c -> switch (billed) {
                    case "paying" -> c.billing() != null && !c.late();
                    case "late" -> c.late();
                    default -> true;
                })
                .toList();
        model.addAttribute("clinics", rows);
        model.addAttribute("total", all.size());
        model.addAttribute("q", q.strip());
        model.addAttribute("whatsapp", whatsapp);
        model.addAttribute("setup", setup);
        model.addAttribute("billed", billed);
        model.addAttribute("filtered", !wanted.isEmpty() || !(whatsapp + setup + billed).isEmpty() || quick.any());
        model.addAttribute("quick", quick);
        model.addAttribute("quickCounts", ClinicSignals.counts(signals));
        model.addAttribute("deleted", removal.restorable(clock.instant()));
        model.addAttribute("undoMinutes", ClinicRemoval.UNDO_WINDOW.toMinutes());
        model.addAttribute("defaultZone", Clinic.DEFAULT_ZONE.getId());
        model.addAttribute("feeOn", ReportView.dollars(billing.plan(true).monthlyFeeUsd()));
        model.addAttribute("feeOff", ReportView.dollars(billing.plan(false).monthlyFeeUsd()));
        return "dashboard/operator";
    }

    @PostMapping("/clinics")
    String create(RedirectAttributes redirect, @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String timezone, @RequestParam String defaultLanguage,
                  @RequestParam(defaultValue = "" + Clinic.DEFAULT_SLOT_MINUTES) int slotMinutes,
                  @RequestParam(defaultValue = "") String email,
                  // The form's switch: without the form (scripts, tests), a clinic has WhatsApp.
                  @RequestParam(defaultValue = "false") boolean whatsAppShown,
                  @RequestParam(defaultValue = "false") boolean whatsApp) {
        if (!validEmail(email)) {
            redirect.addFlashAttribute("error", "error.email");
            return "redirect:/operator";
        }
        ZoneId zone = zone(timezone);
        if (zone == null) {
            redirect.addFlashAttribute("error", "error.timezone");
            return "redirect:/operator";
        }
        if (!validSlotMinutes(slotMinutes)) {
            redirect.addFlashAttribute("error", "error.slot-length");
            return "redirect:/operator";
        }
        try {
            Clinic clinic = clinics.create(name, zone, Language.fromCode(defaultLanguage), email, !whatsAppShown || whatsApp);
            scheduleSettings.changeSlotLength(clinic.getId(), slotMinutes);
            // Billed from the start, on the general plan: the first payment is due after the trial.
            billing.enrolAll(LocalDate.ofInstant(clock.instant(), zone), clock.instant());
            redirect.addFlashAttribute("notice", "notice.saved");
            return "redirect:/operator/clinics/" + clinic.getId();
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.clinic-name");
            return "redirect:/operator";
        }
    }

    @GetMapping("/clinics/{id}")
    String clinic(@PathVariable long id, Model model) {
        Clinic clinic = clinics.get(id);
        model.addAttribute("clinic", clinic);
        model.addAttribute("connection", connections.connection(id));
        List<StaffUser> logins = accounts.logins(id);
        model.addAttribute("logins", logins);
        model.addAttribute("ready", readySteps(clinic, logins.size()));
        model.addAttribute("steps", setupSteps(clinic));
        model.addAttribute("feeOn", ReportView.dollars(billing.plan(true).monthlyFeeUsd()));
        model.addAttribute("feeOff", ReportView.dollars(billing.plan(false).monthlyFeeUsd()));
        model.addAttribute("roles", List.of(StaffRole.OWNER, StaffRole.SECRETARY));
        model.addAttribute("webhookUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/webhooks/whatsapp").toUriString());
        model.addAttribute("contents", removal.contents(id));
        return "dashboard/operator-clinic";
    }

    /**
     * Deletes the clinic and everything of it. Only when the operator typed the word the page asks
     * for ("delete", or its Arabic), so it cannot happen by a slip. The clinics page then offers to
     * undo it for a few minutes ({@link ClinicRemoval#UNDO_WINDOW}); after that it is for good.
     */
    @PostMapping("/clinics/{id}/delete")
    String delete(@PathVariable long id, HttpServletRequest request, RedirectAttributes redirect,
                  @RequestParam(defaultValue = "") String confirm) {
        clinics.get(id);
        String word = messages.getMessage("operator.clinic.delete.word", null, StaffLocaleResolver.language(request).locale());
        if (!confirm.strip().equalsIgnoreCase(word) && !confirm.strip().equalsIgnoreCase(DELETE_WORD)) {
            redirect.addFlashAttribute("error", "error.delete-word");
            return redirectTo(id);
        }
        removal.remove(id, clock.instant());
        return "redirect:/operator";
    }

    /** Undoes a deletion made in the last few minutes: the clinic comes back as it was. */
    @PostMapping("/clinics/restore/{token}")
    String restore(@PathVariable UUID token, RedirectAttributes redirect) {
        try {
            removal.restore(token, clock.instant());
            redirect.addFlashAttribute("notice", "notice.clinic-restored");
        } catch (NotFoundException e) {
            redirect.addFlashAttribute("error", "error.restore-late");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", "error.restore-conflict");
        }
        return "redirect:/operator";
    }

    @PostMapping("/clinics/{id}")
    String updateDetails(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String timezone, @RequestParam String defaultLanguage,
                         @RequestParam(defaultValue = "" + Clinic.DEFAULT_SLOT_MINUTES) int slotMinutes,
                         @RequestParam(defaultValue = "") String email) {
        if (!validEmail(email)) {
            redirect.addFlashAttribute("error", "error.email");
            return redirectTo(id);
        }
        ZoneId zone = zone(timezone);
        if (zone == null) {
            redirect.addFlashAttribute("error", "error.timezone");
            return redirectTo(id);
        }
        if (!validSlotMinutes(slotMinutes)) {
            redirect.addFlashAttribute("error", "error.slot-length");
            return redirectTo(id);
        }
        try {
            clinics.updateDetails(id, name, zone, Language.fromCode(defaultLanguage), email);
            // Every doctor of the clinic follows it; booked appointments keep their times.
            scheduleSettings.changeSlotLength(id, slotMinutes);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.clinic-name");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/whatsapp")
    String connect(@PathVariable long id, RedirectAttributes redirect,
                   @RequestParam(defaultValue = "") String phoneNumberId,
                   @RequestParam(defaultValue = "") String businessAccountId,
                   @RequestParam(defaultValue = "") String accessToken,
                   @RequestParam(defaultValue = "false") boolean removeToken) {
        try {
            connections.connect(id, phoneNumberId, businessAccountId, accessToken, removeToken);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (ConnectionException e) {
            redirect.addFlashAttribute("error", errorKey(e));
        }
        return redirectTo(id);
    }

    /**
     * Turns the clinic's WhatsApp (reminders and booking) on or off. The page asks first; off, the
     * clinic's dashboard loses everything WhatsApp and the plan's lower fee applies.
     */
    @PostMapping("/clinics/{id}/whatsapp-mode")
    String whatsAppMode(@PathVariable long id, @RequestParam boolean enabled, RedirectAttributes redirect) {
        clinics.get(id);
        if (clinics.changeWhatsApp(id, enabled)) {
            redirect.addFlashAttribute("notice", enabled ? "notice.whatsapp-on" : "notice.whatsapp-off");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/subscribe")
    String subscribe(@PathVariable long id, RedirectAttributes redirect) {
        clinics.get(id);
        try {
            connections.subscribeToWebhooks(id);
            redirect.addFlashAttribute("notice", "notice.subscribed");
        } catch (ConnectionException e) {
            redirect.addFlashAttribute("error", errorKey(e));
        } catch (WhatsAppException e) {
            redirect.addFlashAttribute("metaError", e.getMessage());
        }
        return redirectTo(id);
    }

    /**
     * Sends through the messenger, so it is logged and costed like any message. Retries can make
     * the request wait a few seconds; acceptable for a button the operator presses once.
     */
    @PostMapping("/clinics/{id}/test")
    String test(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String to,
                @RequestParam String kind) {
        Clinic clinic = clinics.get(id);
        String phone = PhoneNumbers.parseLebanese(to);
        if (phone == null) {
            redirect.addFlashAttribute("error", "error.phone");
            return redirectTo(id);
        }
        TemplateMessage message = kind.equals("reminder") ? sampleReminder(clinic, phone)
                : new TemplateMessage(phone, HELLO_WORLD, HELLO_WORLD_LANGUAGE, List.of());
        SendOutcome outcome = messenger.send(id, message, null);
        redirect.addFlashAttribute("testOutcome", switch (outcome.result()) {
            case SENT -> new TestOutcome("sent", outcome.waMessageId());
            case FAILED -> new TestOutcome("failed", outcome.errorCode() == null ? outcome.errorTitle()
                    : "(#" + outcome.errorCode() + ") " + outcome.errorTitle());
            case OPTED_OUT -> new TestOutcome("opted-out", "");
            case DISABLED -> new TestOutcome("disabled", "");
        });
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins")
    String addLogin(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String username,
                    @RequestParam StaffRole role, @RequestParam String language) {
        clinics.get(id);
        // Owners and secretaries only: a doctor's login belongs to a doctor, so the clinic makes it on the doctor's page.
        if (role != StaffRole.OWNER && role != StaffRole.SECRETARY) {
            redirect.addFlashAttribute("error", "error.username");
            return redirectTo(id);
        }
        try {
            redirect.addFlashAttribute("newPassword", accounts.createClinicLogin(id, username, role, Language.fromCode(language)));
        } catch (UsernameTakenException e) {
            redirect.addFlashAttribute("error", "error.username-taken");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.username");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins/{userId}/reset")
    String resetPassword(@PathVariable long id, @PathVariable long userId, RedirectAttributes redirect) {
        NewPassword password = accounts.resetPassword(id, userId);
        redirect.addFlashAttribute("newPassword", password);
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins/{userId}/enabled")
    String setEnabled(@PathVariable long id, @PathVariable long userId, RedirectAttributes redirect,
                      @RequestParam boolean enabled) {
        accounts.setEnabled(id, userId, enabled);
        redirect.addFlashAttribute("notice", "notice.saved");
        return redirectTo(id);
    }

    /**
     * The clinic's own 24-hour reminder template with sample values, in the clinic's language:
     * proves the template that matters is approved for this account.
     */
    private TemplateMessage sampleReminder(Clinic clinic, String phone) {
        Language language = clinic.getDefaultLanguage();
        String doctorName = doctors.findByClinicIdOrderByDisplayName(clinic.getId()).stream()
                .filter(Doctor::isActive).map(Doctor::getDisplayName).findFirst()
                .orElseGet(() -> messages.getMessage("operator.test.sample-doctor", null, language.locale()));
        Patient patient = new Patient(clinic.getId(), messages.getMessage("operator.test.sample-patient", null,
                language.locale()), phone, language);
        Doctor doctor = new Doctor(clinic.getId(), doctorName, 30);
        LocalDate tomorrow = LocalDate.ofInstant(clock.instant(), clinic.getTimezone()).plusDays(1);
        return reminderMessages.reminder(ReminderKind.H24, patient, doctor,
                tomorrow.atTime(LocalTime.of(10, 0)).atZone(clinic.getTimezone()).toInstant(), clinic.getTimezone());
    }

    private static boolean validEmail(String email) {
        try {
            Clinic.email(email);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean validSlotMinutes(int minutes) {
        return minutes >= 5 && minutes <= 240;
    }

    static int setupSteps(Clinic clinic) {
        return clinic.isWhatsAppEnabled() ? SETUP_STEPS : SETUP_STEPS_NO_WHATSAPP;
    }

    /** The clinic itself counts as the first step; without WhatsApp, only a login is left. */
    static int readySteps(Clinic clinic, long logins) {
        if (!clinic.isWhatsAppEnabled()) {
            return 1 + (logins > 0 ? 1 : 0);
        }
        return 1 + (clinic.getWaPhoneNumberId() != null ? 1 : 0) + (clinic.getWaBusinessAccountId() != null ? 1 : 0)
                + (clinic.getWaAccessTokenEncrypted() != null ? 1 : 0) + (logins > 0 ? 1 : 0);
    }

    private static String errorKey(ConnectionException e) {
        return switch (e.problem()) {
            case PHONE_NUMBER_ID_TAKEN -> "error.phone-number-id-taken";
            case TOO_LONG -> "error.whatsapp-too-long";
            case NO_ENCRYPTION_KEY -> "error.no-encryption-key";
            case NOT_CONNECTED -> "error.not-connected";
        };
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone.strip());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String redirectTo(long clinicId) {
        return "redirect:/operator/clinics/" + clinicId;
    }
}
