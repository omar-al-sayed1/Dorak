package app.clinicreminders.balance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A closed day: the cash the drawer should hold, what was counted, by whom. Closing again updates it. */
@Entity
@Table(name = "day_close")
public class DayClose {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    private LocalDate day;

    @Column(name = "expected_cash_usd")
    private BigDecimal expectedCashUsd;

    @Column(name = "counted_cash_usd")
    private BigDecimal countedCashUsd;

    @Column(name = "closed_by")
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected DayClose() {
    }

    DayClose(long clinicId, LocalDate day) {
        this.clinicId = clinicId;
        this.day = day;
    }

    void close(BigDecimal expected, BigDecimal counted, String by, Instant now) {
        this.expectedCashUsd = Money.usd(expected);
        this.countedCashUsd = Money.usd(counted);
        this.closedBy = by;
        this.closedAt = now;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public LocalDate getDay() {
        return day;
    }

    public BigDecimal getExpectedCashUsd() {
        return expectedCashUsd;
    }

    public BigDecimal getCountedCashUsd() {
        return countedCashUsd;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
