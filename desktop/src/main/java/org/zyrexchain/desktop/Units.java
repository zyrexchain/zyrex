package org.zyrexchain.desktop;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact conversions between display coins and consensus nano units. */
public final class Units {
    public static final long NANO_PER_COIN = 1_000_000_000L;
    public static final long DEFAULT_FEE = 1_000_000L;

    private Units() { }

    public static long parse(String amount) {
        if (amount == null || amount.length() > 32 || !amount.matches("[0-9]+(?:\\.[0-9]{1,9})?")) {
            throw new IllegalArgumentException("Enter a positive amount with at most 9 decimal places");
        }
        try {
            long units = new BigDecimal(amount).movePointRight(9).longValueExact();
            if (units <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
            return units;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount exceeds the supported integer range");
        }
    }

    public static String format(long amount) {
        return BigDecimal.valueOf(amount, 9).stripTrailingZeros().toPlainString();
    }

    public static String format(BigInteger amount) {
        return new BigDecimal(amount, 9).stripTrailingZeros().toPlainString();
    }
}
