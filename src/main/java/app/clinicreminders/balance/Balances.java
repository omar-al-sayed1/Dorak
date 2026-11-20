package app.clinicreminders.balance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Balance (SPEC section 5.6): what visits cost and what patients paid. A ledger only; nothing is
 * charged to a card. Amounts are dollars to the cent; a payment in lira is converted at the fixed
 * rate ({@link Money#LBP_PER_USD}) and keeps both amounts and the rate. A patient's balance is everything charged
 * minus everything paid; a family on one number also gets its total, as one parent usually pays.
 */
@Service
@Transactional
public class Balances {

    /** A visit's money: what it costs (null: not charged yet), what was paid for it, what's left. */
    public record VisitMoney(long appointmentId, @Nullable BigDecimal charged, BigDecimal paid, BigDecimal due,
                             MoneyStatus status) {

        static VisitMoney of(long appointmentId, @Nullable BigDecimal charged, BigDecimal paid) {
            BigDecimal due = charged == null ? Money.ZERO : charged.subtract(paid).max(Money.ZERO);
            return new VisitMoney(appointmentId, charged, paid, Money.usd(due), MoneyStatus.of(charged, paid));
        }
    }

    /** A payment to record: in dollars, or in lira (whole lira) at the clinic's rate. */
    public record PaymentEntry(BigDecimal amount, Currency currency, PaymentMethod method) {
    }

    /** Everything the payment panel needs about one visit. */
    public record Visit(long appointmentId, long patientId, String patientName, long doctorId, Instant startsAt,
                        VisitMoney money, BigDecimal patientBalance, BigDecimal familyBalance, int familySize,
                        List<Payment> payments) {
    }

    /** One line of a patient's timeline. */
    public record Entry(EntryType type, Instant at, BigDecimal amountUsd, @Nullable Long appointmentId,
                        @Nullable Instant appointmentStart, @Nullable String doctor, @Nullable PaymentMethod method,
                        @Nullable BigDecimal amountLbp, @Nullable Long paymentId, @Nullable UUID receiptToken) {
    }

    public enum EntryType { CHARGE, PAYMENT }

    public record FamilyMember(long patientId, String name, BigDecimal balance) {
    }

    /** A patient's account: their timeline, newest first, and their family's on the same number. */
    public record Ledger(long patientId, String name, String phone, BigDecimal balance, List<Entry> entries,
                         List<FamilyMember> family, BigDecimal familyBalance) {
    }

    /** @param since when the oldest charge they haven't paid was made (payments cover the oldest first) */
    public record Owing(long patientId, String name, String phone, BigDecimal balance, Instant since) {
    }

    public record DayPayment(long id, Instant paidAt, String patient, BigDecimal amountUsd, PaymentMethod method,
                             @Nullable BigDecimal amountLbp, UUID receiptToken) {
    }

    /** A day's takings, by method; the cash the drawer should hold; and its close, if closed. */
    public record Day(LocalDate date, List<DayPayment> payments, Map<PaymentMethod, BigDecimal> byMethod,
                      BigDecimal total, BigDecimal expectedCash, @Nullable DayClose close) {
    }

    /** One payment, for its public receipt; the balance is the patient's now. */
    public record Receipt(Payment payment, Clinic clinic, Patient patient, BigDecimal balance) {
    }

    /** The money side of a month: charged and collected in it, collected by method, and owed now. */
    public record MonthMoney(BigDecimal charged, BigDecimal collected, Map<PaymentMethod, BigDecimal> byMethod,
                             BigDecimal owedNow) {
    }

    private final ChargeRepository charges;
    private final PaymentRepository payments;
    private final DayCloseRepository closes;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final ClinicRepository clinics;

    Balances(ChargeRepository charges, PaymentRepository payments, DayCloseRepository closes, AppointmentRepository appointments, PatientRepository patients,
             DoctorRepository doctors, ClinicRepository clinics) {
        this.charges = charges;
        this.payments = payments;
        this.closes = closes;
        this.appointments = appointments;
        this.patients = patients;
        this.doctors = doctors;
        this.clinics = clinics;
    }

    // --- Visits ---

    /** The money of these visits, by appointment id; visits without a charge or payment are left out. */
    @Transactional(readOnly = true)
    public Map<Long, VisitMoney> forAppointments(long clinicId, Collection<Long> appointmentIds) {
        if (appointmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> charged = new HashMap<>();
        charges.findByClinicIdAndAppointmentIdIn(clinicId, appointmentIds)
                .forEach(charge -> charged.put(charge.getAppointmentId(), charge.getAmountUsd()));
        Map<Long, BigDecimal> paid = new HashMap<>();
        payments.findByClinicIdAndAppointmentIdInOrderByPaidAt(clinicId, appointmentIds)
                .forEach(payment -> paid.merge(payment.getAppointmentId(), payment.getAmountUsd(), BigDecimal::add));
        Map<Long, VisitMoney> money = new HashMap<>();
        for (Long id : appointmentIds) {
            if (charged.containsKey(id) || paid.containsKey(id)) {
                money.put(id, VisitMoney.of(id, charged.get(id), paid.getOrDefault(id, Money.ZERO)));
            }
        }
        return money;
    }

    @Transactional(readOnly = true)
    public Visit visit(long clinicId, long appointmentId) {
        Appointment appointment = appointment(clinicId, appointmentId);
        Patient patient = patientOf(clinicId, appointment);
        List<Patient> family = patients.findByClinicIdAndPhoneE164OrderById(clinicId, patient.getPhoneE164());
        Map<Long, BigDecimal> balances = balances(clinicId, family.stream().map(Patient::getId).toList());
        VisitMoney money = forAppointments(clinicId, List.of(appointmentId))
                .getOrDefault(appointmentId, VisitMoney.of(appointmentId, null, Money.ZERO));
        return new Visit(appointmentId, patient.getId(), patient.getName(), appointment.getDoctorId(),
                appointment.getStartsAt(), money, balances.getOrDefault(patient.getId(), Money.ZERO),
                sum(balances.values()), family.size(),
                payments.findByClinicIdAndAppointmentIdInOrderByPaidAt(clinicId, List.of(appointmentId)));
    }

    /**
     * Sets what the visit costs and, if given, records a payment for it. "Later" is a fee with no
     * payment: the visit then shows unpaid.
     *
     * @return the payment recorded, for its receipt; empty without one
     * @throws BalanceProblem for a negative fee, a payment of nothing, or lira without a rate
     */
    public Optional<Payment> record(long clinicId, long appointmentId, BigDecimal fee, @Nullable PaymentEntry entry,
                                    Instant now) {
        if (fee.signum() < 0 || fee.stripTrailingZeros().scale() > 2) {
            throw new BalanceProblem("error.amount");
        }
        Appointment appointment = appointment(clinicId, appointmentId);
        Patient patient = patientOf(clinicId, appointment);
        charges.findByClinicIdAndAppointmentId(clinicId, appointmentId).ifPresentOrElse(
                charge -> charge.change(fee),
                () -> charges.save(new Charge(clinicId, patient.getId(), appointmentId, fee, now)));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(payments.save(payment(clinicId, patient.getId(), appointmentId, entry, now)));
    }

    // --- The account, without a visit ---

    /** An amount the patient owes that belongs to no visit: an old debt, a balance brought over. */
    public Charge addOwed(long clinicId, long patientId, BigDecimal amount, Instant now) {
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BalanceProblem("error.amount");
        }
        Patient patient = patients.findByIdAndClinicId(patientId, clinicId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));
        return charges.save(new Charge(clinicId, patient.getId(), null, amount, now));
    }

    /** A payment on the patient's account rather than for one visit; it lowers their balance all the same. */
    public Payment payOnAccount(long clinicId, long patientId, PaymentEntry entry, Instant now) {
        Patient patient = patients.findByIdAndClinicId(patientId, clinicId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));
        return payments.save(payment(clinicId, patient.getId(), null, entry, now));
    }

    /** What the patient owes now (negative: in credit). */
    @Transactional(readOnly = true)
    public BigDecimal balanceOf(long clinicId, long patientId) {
        return balances(clinicId, List.of(patientId)).getOrDefault(patientId, Money.ZERO);
    }

    /** A payment entered by mistake. Its receipt link stops working. */
    public void deletePayment(long clinicId, long paymentId) {
        payments.delete(payments.findByIdAndClinicId(paymentId, clinicId)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId)));
    }

    private Payment payment(long clinicId, long patientId, @Nullable Long appointmentId, PaymentEntry entry, Instant now) {
        BigDecimal amount = entry.amount();
        if (entry.currency() == Currency.LBP) {
            BigDecimal rate = Money.LBP_PER_USD;
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
                throw new BalanceProblem("error.amount");
            }
            BigDecimal usd = Money.fromLbp(amount, rate);
            if (usd.signum() <= 0) {
                throw new BalanceProblem("error.amount");
            }
            return new Payment(clinicId, patientId, appointmentId, usd, entry.method(), amount.setScale(0), rate, now);
        }
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new BalanceProblem("error.amount");
        }
        return new Payment(clinicId, patientId, appointmentId, amount, entry.method(), null, null, now);
    }

    // --- Patients ---

    @Transactional(readOnly = true)
    public Ledger ledger(long clinicId, long patientId) {
        Patient patient = patients.findByIdAndClinicId(patientId, clinicId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));
        List<Patient> family = patients.findByClinicIdAndPhoneE164OrderById(clinicId, patient.getPhoneE164());
        Map<Long, BigDecimal> balances = balances(clinicId, family.stream().map(Patient::getId).toList());

        List<Charge> theirCharges = charges.findByClinicIdAndPatientIdInOrderByCreatedAt(clinicId, List.of(patientId));
        Map<Long, Appointment> visits = new HashMap<>();
        Map<Long, String> doctorNames = new HashMap<>();
        for (Doctor doctor : doctors.findByClinicIdOrderByDisplayName(clinicId)) {
            doctorNames.put(doctor.getId(), doctor.getDisplayName());
        }
        List<Entry> entries = new ArrayList<>();
        for (Charge charge : theirCharges) {
            Long appointmentId = charge.getAppointmentId();
            Appointment visit = appointmentId == null ? null : visits.computeIfAbsent(appointmentId,
                    id -> appointments.findByIdAndClinicId(id, clinicId).orElse(null));
            entries.add(new Entry(EntryType.CHARGE, charge.getCreatedAt(), charge.getAmountUsd(), charge.getAppointmentId(),
                    visit == null ? null : visit.getStartsAt(), visit == null ? null : doctorNames.get(visit.getDoctorId()),
                    null, null, null, null));
        }
        for (Payment payment : payments.findByClinicIdAndPatientIdInOrderByPaidAt(clinicId, List.of(patientId))) {
            entries.add(new Entry(EntryType.PAYMENT, payment.getPaidAt(), payment.getAmountUsd(), payment.getAppointmentId(),
                    null, null, payment.getMethod(), payment.getAmountLbp(), payment.getId(), payment.getReceiptToken()));
        }
        entries.sort(Comparator.comparing(Entry::at).reversed());
        List<FamilyMember> members = family.stream()
                .map(member -> new FamilyMember(member.getId(), member.getName(), balances.getOrDefault(member.getId(), Money.ZERO)))
                .toList();
        return new Ledger(patientId, patient.getName(), patient.getPhoneE164(), balances.getOrDefault(patientId, Money.ZERO),
                entries, members, sum(balances.values()));
    }

    /** Everyone who owes something, biggest first, then the longest owing. */
    @Transactional(readOnly = true)
    public List<Owing> whoOwes(long clinicId) {
        Map<Long, BigDecimal> balances = allBalances(clinicId);
        List<Long> owing = balances.entrySet().stream().filter(e -> e.getValue().signum() > 0).map(Map.Entry::getKey).toList();
        if (owing.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Charge>> chargesByPatient = new HashMap<>();
        charges.findByClinicIdAndPatientIdInOrderByCreatedAt(clinicId, owing)
                .forEach(charge -> chargesByPatient.computeIfAbsent(charge.getPatientId(), id -> new ArrayList<>()).add(charge));
        Map<Long, BigDecimal> paid = new HashMap<>();
        payments.findByClinicIdAndPatientIdInOrderByPaidAt(clinicId, owing)
                .forEach(payment -> paid.merge(payment.getPatientId(), payment.getAmountUsd(), BigDecimal::add));
        List<Owing> list = new ArrayList<>();
        for (Long patientId : owing) {
            Patient patient = patients.findByIdAndClinicId(patientId, clinicId).orElse(null);
            if (patient == null) {
                continue;
            }
            Instant since = oldestUnpaid(chargesByPatient.getOrDefault(patientId, List.of()),
                    paid.getOrDefault(patientId, Money.ZERO));
            list.add(new Owing(patientId, patient.getName(), patient.getPhoneE164(), balances.get(patientId), since));
        }
        list.sort(Comparator.comparing(Owing::balance).reversed().thenComparing(Owing::since));
        return list;
    }

    /** Payments cover the oldest charges first; the first charge left uncovered is when the debt began. */
    static Instant oldestUnpaid(List<Charge> chargesOldestFirst, BigDecimal paid) {
        BigDecimal left = paid;
        for (Charge charge : chargesOldestFirst) {
            if (left.compareTo(charge.getAmountUsd()) < 0) {
                return charge.getCreatedAt();
            }
            left = left.subtract(charge.getAmountUsd());
        }
        return chargesOldestFirst.isEmpty() ? Instant.EPOCH : chargesOldestFirst.getLast().getCreatedAt();
    }

    // --- The day ---

    @Transactional(readOnly = true)
    public Day day(long clinicId, LocalDate date, ZoneId zone) {
        List<Payment> paid = payments.findByClinicIdAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAt(clinicId,
                date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
        Map<Long, String> names = new HashMap<>();
        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        List<DayPayment> lines = new ArrayList<>();
        for (Payment payment : paid) {
            String name = names.computeIfAbsent(payment.getPatientId(),
                    id -> patients.findByIdAndClinicId(id, clinicId).map(Patient::getName).orElse(""));
            lines.add(new DayPayment(payment.getId(), payment.getPaidAt(), name, payment.getAmountUsd(), payment.getMethod(),
                    payment.getAmountLbp(), payment.getReceiptToken()));
            byMethod.merge(payment.getMethod(), payment.getAmountUsd(), BigDecimal::add);
        }
        return new Day(date, lines, byMethod, sum(byMethod.values()), byMethod.getOrDefault(PaymentMethod.CASH, Money.ZERO),
                closes.findByClinicIdAndDay(clinicId, date).orElse(null));
    }

    /** Closes the day with the cash counted in the drawer; closing it again replaces the count. */
    public DayClose closeDay(long clinicId, LocalDate date, BigDecimal countedCash, String by, ZoneId zone, Instant now) {
        if (countedCash.signum() < 0 || countedCash.stripTrailingZeros().scale() > 2) {
            throw new BalanceProblem("error.amount");
        }
        BigDecimal expected = day(clinicId, date, zone).expectedCash();
        DayClose close = closes.findByClinicIdAndDay(clinicId, date).orElseGet(() -> new DayClose(clinicId, date));
        close.close(expected, countedCash, by, now);
        return closes.save(close);
    }

    // --- Receipts and the month ---

    @Transactional(readOnly = true)
    public Optional<Receipt> receipt(UUID token) {
        return payments.findByReceiptToken(token).map(payment -> {
            long clinicId = payment.getClinicId();
            Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
            Patient patient = patients.findByIdAndClinicId(payment.getPatientId(), clinicId)
                    .orElseThrow(() -> new NotFoundException("Patient", payment.getPatientId()));
            return new Receipt(payment, clinic, patient,
                    balances(clinicId, List.of(patient.getId())).getOrDefault(patient.getId(), Money.ZERO));
        });
    }

    @Transactional(readOnly = true)
    public MonthMoney month(long clinicId, Instant from, Instant to) {
        BigDecimal charged = sum(charges.findByClinicIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(clinicId, from, to)
                .stream().map(Charge::getAmountUsd).toList());
        Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
        payments.findByClinicIdAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAt(clinicId, from, to)
                .forEach(payment -> byMethod.merge(payment.getMethod(), payment.getAmountUsd(), BigDecimal::add));
        BigDecimal owed = sum(allBalances(clinicId).values().stream().filter(b -> b.signum() > 0).toList());
        return new MonthMoney(Money.usd(charged), sum(byMethod.values()), byMethod, owed);
    }

    // --- Helpers ---

    /** Charged minus paid, per patient (those with any entry). */
    private Map<Long, BigDecimal> balances(long clinicId, Collection<Long> patientIds) {
        Map<Long, BigDecimal> balances = new HashMap<>();
        if (patientIds.isEmpty()) {
            return balances;
        }
        charges.findByClinicIdAndPatientIdInOrderByCreatedAt(clinicId, patientIds)
                .forEach(charge -> balances.merge(charge.getPatientId(), charge.getAmountUsd(), BigDecimal::add));
        payments.findByClinicIdAndPatientIdInOrderByPaidAt(clinicId, patientIds)
                .forEach(payment -> balances.merge(payment.getPatientId(), payment.getAmountUsd().negate(), BigDecimal::add));
        balances.replaceAll((id, amount) -> Money.usd(amount));
        return balances;
    }

    private Map<Long, BigDecimal> allBalances(long clinicId) {
        Map<Long, BigDecimal> balances = new HashMap<>();
        for (Object[] row : charges.chargedByPatient(clinicId)) {
            balances.merge((Long) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : payments.paidByPatient(clinicId)) {
            balances.merge((Long) row[0], ((BigDecimal) row[1]).negate(), BigDecimal::add);
        }
        balances.replaceAll((id, amount) -> Money.usd(amount));
        return balances;
    }

    private Appointment appointment(long clinicId, long appointmentId) {
        return appointments.findByIdAndClinicId(appointmentId, clinicId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
    }

    /** @throws BalanceProblem for a visit whose patient was deleted: there is no one to charge */
    private Patient patientOf(long clinicId, Appointment appointment) {
        if (appointment.getPatientId() == null) {
            throw new BalanceProblem("error.no-patient");
        }
        return patients.findByIdAndClinicId(appointment.getPatientId(), clinicId)
                .orElseThrow(() -> new BalanceProblem("error.no-patient"));
    }

    private static BigDecimal sum(Collection<BigDecimal> amounts) {
        return Money.usd(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
