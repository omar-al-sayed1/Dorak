package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

import app.clinicreminders.balance.Balances.MonthMoney;
import app.clinicreminders.balance.PaymentMethod;
import org.junit.jupiter.api.Test;

class ReportViewTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final Instant CREATED = Instant.parse("2026-09-10T08:00:00Z");
    /** 31 October, 23:30 in Beirut: still October there, already November in UTC+3. */
    private static final Instant NOW = Instant.parse("2026-10-31T21:30:00Z");

    @Test
    void theMonthShownStaysBetweenTheClinicsFirstMonthAndNowInItsZone() {
        assertThat(ReportView.month(null, CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-09", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(ReportView.month("2026-11", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-01", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(ReportView.month("October", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-08", null, BEIRUT, NOW)).as("no creation date: this month only")
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void dollarsAndADashWhenNothingCouldBePriced() {
        assertThat(ReportView.usd(new BigDecimal("4.1234"))).isEqualTo("$4.12");
        assertThat(ReportView.usd(new BigDecimal("0.0040"))).isEqualTo("< $0.01");
        assertThat(ReportView.usd(BigDecimal.ZERO)).isEqualTo("$0.00");
        assertThat(ReportView.cost(BigDecimal.ZERO, 12, 12)).as("no rate was set").isEqualTo("—");
        assertThat(ReportView.cost(new BigDecimal("0.0314"), 12, 10)).isEqualTo("$0.03");
        assertThat(ReportView.cost(BigDecimal.ZERO, 0, 0)).isEqualTo("$0.00");
    }

    @Test
    void theBarsShowAPartOfAWholeFromNothingToAllOfIt() {
        assertThat(ReportView.percent(new BigDecimal("1100"), new BigDecimal("1250"))).isEqualTo(88);
        assertThat(ReportView.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo(33);
        assertThat(ReportView.percent(BigDecimal.ZERO, new BigDecimal("50"))).isZero();
        assertThat(ReportView.percent(new BigDecimal("20"), BigDecimal.ZERO)).as("nothing to compare with").isZero();
        assertThat(ReportView.percent(new BigDecimal("80"), new BigDecimal("50"))).as("paid ahead: the bar is full").isEqualTo(100);
        assertThat(ReportView.percent(new BigDecimal("-5"), new BigDecimal("50"))).isZero();
    }

    @Test
    void theMonthsMoneySaysHowMuchOfTheChargesCameInAndByWhichMethod() {
        ReportView.MoneyView view = ReportView.moneyView(new MonthMoney(new BigDecimal("200"), new BigDecimal("150"),
                Map.of(PaymentMethod.CASH, new BigDecimal("120"), PaymentMethod.CARD, new BigDecimal("30")), new BigDecimal("50")));
        assertThat(view.collectedPercent()).isEqualTo(75);
        assertThat(view.byMethod()).filteredOn(total -> total.method() == PaymentMethod.CASH)
                .singleElement().satisfies(cash -> {
                    assertThat(cash.amount()).isEqualTo("$120.00");
                    assertThat(cash.percent()).isEqualTo(80);
                });

        ReportView.MoneyView nothingCharged = ReportView.moneyView(
                new MonthMoney(BigDecimal.ZERO, new BigDecimal("40"), Map.of(PaymentMethod.CASH, new BigDecimal("40")), BigDecimal.ZERO));
        assertThat(nothingCharged.collectedPercent()).as("no bar without charges").isNull();
    }
}
