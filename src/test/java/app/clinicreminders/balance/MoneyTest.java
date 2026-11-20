package app.clinicreminders.balance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void aVisitsStatusFromWhatWasChargedAndPaid() {
        assertThat(MoneyStatus.of(null, Money.ZERO)).isEqualTo(MoneyStatus.NONE);
        assertThat(MoneyStatus.of(null, usd("10"))).as("paid before a fee was set").isEqualTo(MoneyStatus.PAID);
        assertThat(MoneyStatus.of(usd("0"), Money.ZERO)).isEqualTo(MoneyStatus.FREE);
        assertThat(MoneyStatus.of(usd("30"), Money.ZERO)).isEqualTo(MoneyStatus.UNPAID);
        assertThat(MoneyStatus.of(usd("30"), usd("10"))).isEqualTo(MoneyStatus.PARTLY_PAID);
        assertThat(MoneyStatus.of(usd("30"), usd("30"))).isEqualTo(MoneyStatus.PAID);
        assertThat(MoneyStatus.of(usd("30"), usd("35"))).as("overpaid").isEqualTo(MoneyStatus.PAID);
    }

    @Test
    void liraBecomeDollarsToTheCent() {
        assertThat(Money.fromLbp(new BigDecimal("2685000"), new BigDecimal("89500"))).isEqualByComparingTo("30.00");
        assertThat(Money.fromLbp(new BigDecimal("1000000"), new BigDecimal("89500"))).isEqualTo(usd("11.17"));
        assertThat(Money.fromLbp(new BigDecimal("500"), new BigDecimal("89500"))).as("too little for a cent")
                .isEqualTo(usd("0.01"));
        assertThat(Money.fromLbp(new BigDecimal("400"), new BigDecimal("89500"))).isEqualTo(usd("0.00"));
        assertThat(Money.usd(new BigDecimal("12.345"))).isEqualTo(new BigDecimal("12.35"));
    }

    @Test
    void paymentsCoverTheOldestFeesFirst() {
        Charge march = charge("30", "2026-03-01T08:00:00Z");
        Charge april = charge("30", "2026-04-01T08:00:00Z");
        Charge may = charge("20", "2026-05-01T08:00:00Z");
        List<Charge> charges = List.of(march, april, may);

        assertThat(Balances.oldestUnpaid(charges, Money.ZERO)).isEqualTo(march.getCreatedAt());
        assertThat(Balances.oldestUnpaid(charges, usd("29.99"))).isEqualTo(march.getCreatedAt());
        assertThat(Balances.oldestUnpaid(charges, usd("30"))).isEqualTo(april.getCreatedAt());
        assertThat(Balances.oldestUnpaid(charges, usd("45"))).isEqualTo(april.getCreatedAt());
        assertThat(Balances.oldestUnpaid(charges, usd("60"))).isEqualTo(may.getCreatedAt());
    }

    private static BigDecimal usd(String amount) {
        return Money.usd(new BigDecimal(amount));
    }

    private static Charge charge(String amount, String createdAt) {
        return new Charge(1, 1, 1L, new BigDecimal(amount), Instant.parse(createdAt));
    }
}
