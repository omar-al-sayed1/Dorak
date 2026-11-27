package app.clinicreminders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import app.clinicreminders.api.PatientTable.Filter;
import app.clinicreminders.api.PatientTable.Row;
import app.clinicreminders.api.PatientTable.Sort;
import app.clinicreminders.balance.Balances;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.dashboard.Avatars;
import app.clinicreminders.dashboard.ClinicContext;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patients table of the React dashboard: search, filters with their counts, sorting, a page at a
 * time; and deleting a patient on request (the front desk's, SecurityConfig).
 */
@RestController
class PatientsApi {

    static final int PAGE_SIZE = 30;

    /**
     * @param days how many days from today (0 today, 1 tomorrow; negative in the past), for "In 3 days"
     */
    record When(long appointmentId, String label, long days, @Nullable String doctor) {
    }

    /**
     * @param familyHue  the same colour for everyone on one number, to tie a family's rows together
     * @param family     the others on their number, by name; empty for a doctor's login
     * @param balance    null for a doctor's login
     */
    record PatientJson(long id, String name, String phone, String language, String initials, int hue,
                       boolean optedOut, @Nullable When next, @Nullable String lastVisit, @Nullable Long lastVisitDays,
                       long visits, @Nullable BigDecimal balance, int familyHue, List<String> family) {
    }

    record Counts(long all, long booked, long unbooked, long owes, long family, long optedOut) {
    }

    record PatientsJson(long total, Counts counts, List<PatientJson> patients, @Nullable Integer nextPage) {
    }

    /** A new patient from the front desk (Add Payment): a name and a Lebanese number. */
    record NewPatient(@Nullable String name, @Nullable String phone) {
    }

    /**
     * The patient to put money on, as the Add Payment dialog shows them.
     *
     * @param existing the number already had a patient by that name: theirs is used, not a copy
     */
    record AddedPatient(long id, String name, String phone, String initials, int hue, BigDecimal balance, boolean existing) {
    }

    /** Longest patient name accepted. */
    static final int NAME_MAX = 100;

    private final ClinicContext context;
    private final PatientTable table;
    private final PatientService patients;
    private final DoctorRepository doctors;
    private final Avatars avatars;
    private final Balances balances;

    PatientsApi(ClinicContext context, PatientTable table, PatientService patients, DoctorRepository doctors,
                Avatars avatars, Balances balances) {
        this.balances = balances;
        this.context = context;
        this.table = table;
        this.patients = patients;
        this.doctors = doctors;
        this.avatars = avatars;
    }

    /**
     * @param sort  name, next, last, visits or balance (the front desk's)
     * @param page  from 0
     * @param size  30 for the table; the Add Payment picker asks for fewer
     */
    @GetMapping("/api/patients")
    PatientsJson list(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String filter,
                      @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String dir,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        Ctx ctx = context.of(staff, request);
        int pageSize = Math.clamp(size, 1, PAGE_SIZE);
        String name = q.strip();
        Filter chosenFilter = parse(Filter.class, filter.replace('-', '_'), Filter.ALL);
        PatientTable.Page found = table.find(ctx.clinicId(), ctx.doctorId(), name, PatientService.searchDigits(name),
                chosenFilter, parse(Sort.class, sort, Sort.NAME), "desc".equalsIgnoreCase(dir), page, pageSize, ctx.now());

        Map<Long, String> doctorNames = new HashMap<>();
        doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).forEach(d -> doctorNames.put(d.getId(), d.getDisplayName()));
        Map<String, List<Patient>> households = ctx.isDoctor() ? Map.of() : patients.households(ctx.clinicId(),
                found.rows().stream().filter(row -> row.familySize() > 1).map(Row::phone).distinct().toList());

        List<PatientJson> rows = found.rows().stream().map(row -> json(ctx, row, doctorNames, households)).toList();
        PatientTable.Counts counts = found.counts();
        long total = counts.of(ctx.isDoctor() && (chosenFilter == Filter.OWES || chosenFilter == Filter.FAMILY)
                ? Filter.ALL : chosenFilter);
        return new PatientsJson(total, new Counts(counts.all(), counts.booked(), counts.unbooked(), counts.owes(),
                counts.family(), counts.optedOut()), rows,
                (long) (Math.max(page, 0) + 1) * pageSize < total ? Math.max(page, 0) + 1 : null);
    }

    /** The patient's card, as the hover card shows it: the table's line for them. */
    /**
     * Adds a patient (front desk only, SecurityConfig), for money that is not about a visit. The same
     * name on the same number is the same patient: theirs comes back, with their balance. A new one
     * gets the clinic's language; another name on a number that is known joins that family.
     *
     * @return 400 with error.name-required or error.phone when one is missing or does not look right
     */
    @PostMapping("/api/patients")
    ResponseEntity<?> add(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, @RequestBody NewPatient body) {
        Ctx ctx = context.of(staff, request);
        String name = body.name() == null ? "" : Patient.normalizeName(body.name());
        if (name.isEmpty() || name.length() > NAME_MAX) {
            return ResponseEntity.badRequest().body(Map.of("error", "error.name-required"));
        }
        String phone = body.phone() == null ? null : PhoneNumbers.parseLebanese(body.phone());
        if (phone == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "error.phone"));
        }
        boolean existing = patients.household(ctx.clinicId(), phone).stream().anyMatch(patient -> patient.hasName(name));
        Patient patient = patients.findOrCreate(ctx.clinicId(), phone, name, ctx.clinic().getDefaultLanguage());
        return ResponseEntity.ok(new AddedPatient(patient.getId(), patient.getName(), patient.getPhoneE164(),
                avatars.initials(patient.getName()), avatars.hue(patient.getName()),
                balances.balanceOf(ctx.clinicId(), patient.getId()), existing));
    }

    @GetMapping("/api/patients/{id}/card")
    PatientJson card(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Ctx ctx = context.of(staff, request);
        Row row = table.one(ctx.clinicId(), ctx.doctorId(), id, ctx.now())
                .orElseThrow(() -> new NotFoundException("Patient", id));
        Map<Long, String> doctorNames = new HashMap<>();
        doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).forEach(d -> doctorNames.put(d.getId(), d.getDisplayName()));
        Map<String, List<Patient>> households = ctx.isDoctor() || row.familySize() < 2 ? Map.of()
                : patients.households(ctx.clinicId(), List.of(row.phone()));
        return json(ctx, row, doctorNames, households);
    }

    private PatientJson json(Ctx ctx, Row row, Map<Long, String> doctorNames, Map<String, List<Patient>> households) {
        Instant next = row.nextAt();
        Long nextId = row.nextId();
        Long nextDoctor = row.nextDoctorId();
        Instant last = row.lastAt();
        return new PatientJson(row.id(), row.name(), row.phone(), row.language(), avatars.initials(row.name()),
                avatars.hue(row.name()), row.optedOut(),
                next == null || nextId == null ? null : new When(nextId,
                        ctx.formats().dayShort(ctx.dateOf(next)) + ", " + ctx.formats().time(next), days(ctx, next),
                        nextDoctor == null ? null : doctorNames.get(nextDoctor)),
                last == null ? null : ctx.formats().dayShort(ctx.dateOf(last)),
                last == null ? null : -days(ctx, last),
                row.visits(), row.balance(), avatars.hue(row.phone()),
                households.getOrDefault(row.phone(), List.of()).stream()
                        .filter(other -> other.getId() != row.id())
                        .map(Patient::getName)
                        .collect(Collectors.toList()));
    }

    /** On the patient's request (SPEC section 5.3): see {@link PatientService#delete}. Their payments go too. */
    @DeleteMapping("/api/patients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        patients.delete(context.of(staff, request).clinicId(), id);
    }

    private static long days(Ctx ctx, Instant instant) {
        return ChronoUnit.DAYS.between(ctx.today(), ctx.dateOf(instant));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E otherwise) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return otherwise;
        }
    }
}
