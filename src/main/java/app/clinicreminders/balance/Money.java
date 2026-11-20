package app.clinicreminders.balance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Dollars to the cent, and lira to dollars at a rate. */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** Lebanese pounds to the dollar: one fixed rate for every clinic, not a setting. */
    public static final BigDecimal LBP_PER_USD = new BigDecimal("89500");

    private Money() {
    }

    public static BigDecimal usd(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code lbp} lira at {@code lbpPerUsd} lira to the dollar, to the cent. */
    public static BigDecimal fromLbp(BigDecimal lbp, BigDecimal lbpPerUsd) {
        return lbp.divide(lbpPerUsd, 2, RoundingMode.HALF_UP);
    }
}
