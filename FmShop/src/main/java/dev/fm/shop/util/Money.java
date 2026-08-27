package dev.fm.shop.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is stored and moved as a {@code long} count of cents; every balance,
 * price and fee in FmShop is an exact integer so repeated buy/sell round trips
 * can never leak or mint fractions the way accumulated {@code double} rounding
 * does. Display and config parsing are the only places decimals exist.
 */
public final class Money {

    /** 100 cents = 1 unit of currency. */
    public static final long SCALE = 100L;

    /** Hard ceiling for a single balance: ~9.2e16 cents, i.e. 92 trillion coins. */
    public static final long MAX = Long.MAX_VALUE / 4;

    private Money() {
    }

    /** Config/command decimal -> cents, HALF_UP. Returns -1 when unparsable. */
    public static long parse(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        String t = s.trim().replace(",", "").replace("_", "");
        try {
            BigDecimal bd = new BigDecimal(t);
            if (bd.signum() < 0) {
                return -1;
            }
            BigDecimal cents = bd.multiply(BigDecimal.valueOf(SCALE)).setScale(0, RoundingMode.HALF_UP);
            if (cents.compareTo(BigDecimal.valueOf(MAX)) > 0) {
                return -1;
            }
            return cents.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    /** Config double (already read as a number) -> cents, HALF_UP. */
    public static long ofDouble(double v) {
        if (!Double.isFinite(v) || v < 0) {
            return -1;
        }
        return BigDecimal.valueOf(v)
                .multiply(BigDecimal.valueOf(SCALE))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Cents -> {@code 1,234.50}. */
    public static String format(long cents) {
        long abs = Math.abs(cents);
        String whole = group(abs / SCALE);
        long frac = abs % SCALE;
        StringBuilder sb = new StringBuilder(24);
        if (cents < 0) {
            sb.append('-');
        }
        sb.append(whole).append('.');
        if (frac < 10) {
            sb.append('0');
        }
        sb.append(frac);
        return sb.toString();
    }

    /** Cents -> {@code 1,234.50 金币} using the configured currency name. */
    public static String format(long cents, String currency) {
        return format(cents) + " " + currency;
    }

    /** unit price x quantity, saturating at {@link #MAX} instead of overflowing. */
    public static long times(long cents, int qty) {
        if (qty <= 0 || cents <= 0) {
            return 0;
        }
        long r = cents * qty;
        if (cents != 0 && (r / cents != qty || r > MAX)) {
            return MAX;
        }
        return r;
    }

    /**
     * Basis-point cut (250 = 2.5%), HALF_UP, never exceeding the amount.
     *
     * <p>Split into quotient and remainder rather than {@code amount * bp}:
     * a whale balance times 9999 overflows a long, and a negative fee would
     * turn a sale fee into free money.
     */
    public static long basisPoints(long amount, int bp) {
        if (amount <= 0 || bp <= 0) {
            return 0;
        }
        if (bp >= 10_000) {
            return amount;
        }
        long q = amount / 10_000L;
        long r = amount % 10_000L;
        long fee = q * bp + (r * bp + 5_000L) / 10_000L;
        return Math.min(amount, fee);
    }

    /** Saturating add used when crediting a balance. */
    public static long add(long a, long b) {
        long r = a + b;
        if (r < 0 || r > MAX) {
            return MAX;
        }
        return r;
    }

    private static String group(long v) {
        String s = Long.toString(v);
        int n = s.length();
        if (n <= 3) {
            return s;
        }
        StringBuilder sb = new StringBuilder(n + n / 3);
        int lead = n % 3 == 0 ? 3 : n % 3;
        sb.append(s, 0, lead);
        for (int i = lead; i < n; i += 3) {
            sb.append(',').append(s, i, i + 3);
        }
        return sb.toString();
    }
}
