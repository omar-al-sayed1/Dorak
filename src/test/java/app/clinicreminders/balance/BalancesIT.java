package app.clinicreminders.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.balance.Balances.PaymentEntry;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Balance (SPEC section 5.6): fees, payments in dollars and lira, who owes, closing the day, and
 * each clinic seeing only its own money. Now is Wednesday 14 October 2026, 12:00 in Beirut (UTC+3).
 */
class BalancesIT extends IntegrationTest {

    private static final Instant TEN_THIRTY = Instant.parse("2026-10-14T07:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Autowired
    Balances balances;

    @Autowired
    PatientService patientService;

    private Clinic clinic;
    private Doctor khoury;
    private Patient rana;
    private Appointment visit;

    @BeforeEach
    void aVisitToday() {
        clinic = data.clinic("A");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        visit = data.appointment(khoury, rana, TEN_THIRTY, Duration.ofMinutes(30));
    }

    @Test
    void aFeePaidHalfInLiraThenTheRestInDollars() {

        Payment lira = balances.record(clinic.getId(), visit.getId(), usd("30"),
                new PaymentEntry(new BigDecimal("1342500"), Currency.LBP, PaymentMethod.CASH), clock.instant()).orElseThrow();

        assertThat(lira.getAmountUsd()).isEqualTo(usd("15"));
        assertThat(lira.getAmountLbp()).isEqualByComparingTo("1342500");
        assertThat(lira.getLbpPerUsd()).isEqualByComparingTo("89500");
        assertThat(lira.getReceiptToken()).as("set by the database").isNotNull();
        assertThat(money(visit).status()).isEqualTo(MoneyStatus.PARTLY_PAID);
        assertThat(money(visit).due()).isEqualTo(usd("15"));

        balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("15", PaymentMethod.WHISH), clock.instant());

        assertThat(money(visit).status()).isEqualTo(MoneyStatus.PAID);
        assertThat(money(visit).due()).isEqualTo(usd("0"));
        assertThat(balances.visit(clinic.getId(), visit.getId()).payments()).hasSize(2);
        assertThat(balances.ledger(clinic.getId(), rana.getId()).balance()).isEqualTo(usd("0"));
    }

    @Test
    void laterIsAFeeWithoutAPaymentAndAFreeVisitIsZero() {
        assertThat(balances.forAppointments(clinic.getId(), List.of(visit.getId()))).as("nothing yet").isEmpty();

        assertThat(balances.record(clinic.getId(), visit.getId(), usd("40"), null, clock.instant())).isEmpty();
        assertThat(money(visit).status()).isEqualTo(MoneyStatus.UNPAID);

        balances.record(clinic.getId(), visit.getId(), usd("0"), null, clock.instant());
        assertThat(money(visit).status()).as("the fee changed, not a second charge").isEqualTo(MoneyStatus.FREE);
        assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isEqualTo(1);
    }

    @Test
    void wrongAmountsAreRefused() {
        assertThatThrownBy(() -> balances.record(clinic.getId(), visit.getId(), new BigDecimal("-1"), null, clock.instant()))
                .isInstanceOf(BalanceProblem.class).hasMessage("error.amount");
        assertThatThrownBy(() -> balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("0", PaymentMethod.CASH),
                clock.instant())).hasMessage("error.amount");
        assertThatThrownBy(() -> balances.record(clinic.getId(), visit.getId(), new BigDecimal("30.005"), null, clock.instant()))
                .hasMessage("error.amount");
        assertThatThrownBy(() -> balances.record(clinic.getId(), visit.getId(), usd("30"),
                new PaymentEntry(new BigDecimal("1000.5"), Currency.LBP, PaymentMethod.CASH), clock.instant()))
                .as("whole lira only").hasMessage("error.amount");
    }

    @Test
    void whoOwesBiggestFirstWithWhenItBegan() {
        Patient karim = data.patient(clinic, "Karim", "+96171222333", Language.EN);
        Appointment karimsVisit = data.appointment(khoury, karim, TEN_THIRTY.plus(Duration.ofHours(1)), Duration.ofMinutes(30));
        Instant lastWeek = clock.instant().minus(Duration.ofDays(7));
        balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("10", PaymentMethod.CASH), lastWeek);
        balances.record(clinic.getId(), karimsVisit.getId(), usd("50"), null, clock.instant());
        Patient paidUp = data.patient(clinic, "Paid Up", "+96176000000", Language.EN);
        Appointment paidVisit = data.appointment(khoury, paidUp, TEN_THIRTY.plus(Duration.ofHours(2)), Duration.ofMinutes(30));
        balances.record(clinic.getId(), paidVisit.getId(), usd("20"), dollars("20", PaymentMethod.CASH), clock.instant());

        assertThat(balances.whoOwes(clinic.getId()))
                .extracting(Balances.Owing::name, Balances.Owing::balance, Balances.Owing::since)
                .containsExactly(
                        tuple("Karim", usd("50"), clock.instant()),
                        tuple("Rana Haddad", usd("20"), lastWeek));
    }

    @Test
    void aFamilyOnOneNumberHasEachAccountAndATotal() {
        Patient son = data.patient(clinic, "Sami Haddad", rana.getPhoneE164(), Language.AR);
        Appointment sonsVisit = data.appointment(khoury, son, TEN_THIRTY.plus(Duration.ofHours(1)), Duration.ofMinutes(30));
        balances.record(clinic.getId(), visit.getId(), usd("30"), null, clock.instant());
        balances.record(clinic.getId(), sonsVisit.getId(), usd("25"), dollars("5", PaymentMethod.CASH), clock.instant());

        Balances.Ledger ledger = balances.ledger(clinic.getId(), son.getId());

        assertThat(ledger.balance()).isEqualTo(usd("20"));
        assertThat(ledger.familyBalance()).isEqualTo(usd("50"));
        assertThat(ledger.family()).extracting(Balances.FamilyMember::name).containsExactly("Rana Haddad", "Sami Haddad");
        assertThat(ledger.entries()).extracting(Balances.Entry::type)
                .containsExactlyInAnyOrder(Balances.EntryType.CHARGE, Balances.EntryType.PAYMENT);
        assertThat(balances.visit(clinic.getId(), sonsVisit.getId()).familySize()).isEqualTo(2);
    }

    @Test
    void theDayCountsItsOwnPaymentsAndTheDrawerIsClosedAgainstCash() {
        Patient karim = data.patient(clinic, "Karim", "+96171222333", Language.EN);
        Appointment yesterday = data.appointment(khoury, karim, TEN_THIRTY.minus(Duration.ofDays(1)), Duration.ofMinutes(30));
        // The day is the clinic's: 23:30 in Beirut on the 13th is yesterday; 00:30 on the 14th is today, though still the 13th in UTC.
        balances.record(clinic.getId(), yesterday.getId(), usd("40"), dollars("40", PaymentMethod.CASH),
                Instant.parse("2026-10-13T20:30:00Z"));
        balances.record(clinic.getId(), visit.getId(), usd("35"), dollars("5", PaymentMethod.OMT),
                Instant.parse("2026-10-13T21:30:00Z"));
        balances.record(clinic.getId(), visit.getId(), usd("35"), dollars("20", PaymentMethod.CASH), clock.instant());
        balances.record(clinic.getId(), visit.getId(), usd("35"), dollars("10", PaymentMethod.OMT), clock.instant());

        Balances.Day day = balances.day(clinic.getId(), TODAY, clinic.getTimezone());

        assertThat(day.payments()).extracting(Balances.DayPayment::amountUsd).containsExactly(usd("5"), usd("20"), usd("10"));
        assertThat(day.byMethod()).containsExactlyInAnyOrderEntriesOf(
                Map.of(PaymentMethod.CASH, usd("20"), PaymentMethod.OMT, usd("15")));
        assertThat(day.total()).isEqualTo(usd("35"));
        assertThat(day.expectedCash()).isEqualTo(usd("20"));
        assertThat(day.close()).isNull();

        DayClose close = balances.closeDay(clinic.getId(), TODAY, usd("18"), "secretary", clinic.getTimezone(), clock.instant());
        assertThat(close.getExpectedCashUsd()).isEqualTo(usd("20"));
        assertThat(close.getCountedCashUsd()).isEqualTo(usd("18"));

        balances.closeDay(clinic.getId(), TODAY, usd("20"), "owner", clinic.getTimezone(), clock.instant());
        DayClose again = balances.day(clinic.getId(), TODAY, clinic.getTimezone()).close();
        assertThat(again).isNotNull();
        assertThat(again.getCountedCashUsd()).as("counted again: replaced").isEqualTo(usd("20"));
        assertThat(again.getClosedBy()).isEqualTo("owner");
        assertThat(jdbc.queryForObject("select count(*) from day_close", Integer.class)).isEqualTo(1);
    }

    @Test
    void moneyOnTheAccountWithoutAVisit() {
        balances.addOwed(clinic.getId(), rana.getId(), usd("100"), clock.instant().minus(Duration.ofDays(40)));
        Payment payment = balances.payOnAccount(clinic.getId(), rana.getId(), dollars("30", PaymentMethod.CASH), clock.instant());

        assertThat(payment.getAppointmentId()).isNull();
        assertThat(balances.balanceOf(clinic.getId(), rana.getId())).isEqualTo(usd("70"));
        assertThat(balances.whoOwes(clinic.getId())).extracting(Balances.Owing::balance).containsExactly(usd("70"));
        assertThat(balances.ledger(clinic.getId(), rana.getId()).entries())
                .filteredOn(entry -> entry.type() == Balances.EntryType.CHARGE)
                .singleElement().satisfies(entry -> {
                    assertThat(entry.appointmentId()).isNull();
                    assertThat(entry.doctor()).isNull();
                });
        assertThat(balances.day(clinic.getId(), TODAY, clinic.getTimezone()).expectedCash()).isEqualTo(usd("30"));
        assertThat(balances.forAppointments(clinic.getId(), List.of(visit.getId()))).as("not this visit's").isEmpty();
        assertThatThrownBy(() -> balances.addOwed(clinic.getId(), rana.getId(), usd("0"), clock.instant()))
                .hasMessage("error.amount");
        long other = data.clinic("B").getId();
        assertThatThrownBy(() -> balances.addOwed(other, rana.getId(), usd("5"), clock.instant()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> balances.payOnAccount(other, rana.getId(), dollars("5", PaymentMethod.CASH), clock.instant()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aClinicSeesAndChangesOnlyItsOwnMoney() {
        Payment payment = balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("10", PaymentMethod.CASH),
                clock.instant()).orElseThrow();
        balances.closeDay(clinic.getId(), TODAY, usd("10"), "secretary", clinic.getTimezone(), clock.instant());
        long other = data.clinic("B").getId();

        assertThat(balances.forAppointments(other, List.of(visit.getId()))).isEmpty();
        assertThat(balances.whoOwes(other)).isEmpty();
        assertThat(balances.day(other, TODAY, clinic.getTimezone()).payments()).isEmpty();
        assertThat(balances.day(other, TODAY, clinic.getTimezone()).close()).isNull();
        assertThat(balances.month(other, TEN_THIRTY.minus(Duration.ofDays(1)), TEN_THIRTY.plus(Duration.ofDays(1))).collected())
                .isEqualTo(usd("0"));
        assertThatThrownBy(() -> balances.visit(other, visit.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> balances.record(other, visit.getId(), usd("1"), null, clock.instant()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> balances.ledger(other, rana.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> balances.deletePayment(other, payment.getId())).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isEqualTo(1);
    }

    @Test
    void aReceiptIsFoundByItsTokenWithThePatientsBalanceNow() {
        Payment payment = balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("10", PaymentMethod.CASH),
                clock.instant()).orElseThrow();

        Balances.Receipt receipt = balances.receipt(payment.getReceiptToken()).orElseThrow();

        assertThat(receipt.clinic().getId()).isEqualTo(clinic.getId());
        assertThat(receipt.patient().getName()).isEqualTo("Rana Haddad");
        assertThat(receipt.balance()).isEqualTo(usd("20"));

        balances.deletePayment(clinic.getId(), payment.getId());
        assertThat(balances.receipt(payment.getReceiptToken())).as("a deleted payment has no receipt").isEmpty();
    }

    @Test
    void theMonthsMoney() {
        Patient karim = data.patient(clinic, "Karim", "+96171222333", Language.EN);
        Appointment karimsVisit = data.appointment(khoury, karim, TEN_THIRTY.plus(Duration.ofHours(1)), Duration.ofMinutes(30));
        balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("30", PaymentMethod.CASH), clock.instant());
        balances.record(clinic.getId(), karimsVisit.getId(), usd("50"), dollars("20", PaymentMethod.CARD), clock.instant());
        Instant october = Instant.parse("2026-09-30T21:00:00Z");
        Instant november = Instant.parse("2026-10-31T22:00:00Z");

        Balances.MonthMoney month = balances.month(clinic.getId(), october, november);

        assertThat(month.charged()).isEqualTo(usd("80"));
        assertThat(month.collected()).isEqualTo(usd("50"));
        assertThat(month.byMethod()).containsEntry(PaymentMethod.CASH, usd("30")).containsEntry(PaymentMethod.CARD, usd("20"));
        assertThat(month.owedNow()).isEqualTo(usd("30"));
        assertThat(balances.month(clinic.getId(), november, november.plus(Duration.ofDays(30))).charged()).isEqualTo(usd("0"));
    }

    @Test
    void deletingAPatientDeletesTheirFeesAndPayments() {
        Payment payment = balances.record(clinic.getId(), visit.getId(), usd("30"), dollars("10", PaymentMethod.CASH),
                clock.instant()).orElseThrow();

        patientService.delete(clinic.getId(), rana.getId());

        assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment", Integer.class)).isZero();
        assertThat(balances.receipt(payment.getReceiptToken())).isEmpty();
    }

    private Balances.VisitMoney money(Appointment appointment) {
        return balances.forAppointments(clinic.getId(), List.of(appointment.getId())).get(appointment.getId());
    }

    private static BigDecimal usd(String amount) {
        return Money.usd(new BigDecimal(amount));
    }

    private static PaymentEntry dollars(String amount, PaymentMethod method) {
        return new PaymentEntry(new BigDecimal(amount), Currency.USD, method);
    }
}
