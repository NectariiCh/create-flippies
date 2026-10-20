package com.nectariich.create_flippies.content.computer.asm.m68k;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Decimal text to the 68k floating-point memory formats, exactly: the decimal value is held as a
 * ratio of big integers and rounded once to the format's precision, round to nearest even (spec
 * 10.7.2). Single and double are IEEE 754 with a hidden bit; extended is the 68k 96-bit format
 * (sign and 15-bit exponent, a zero word, a 64-bit mantissa with explicit integer bit); packed is
 * the 96-bit BCD format with a 3-digit exponent and 17 significant digits.
 *
 * Extended denormals (biased exponent 0) are scaled as 2^-16383 times 0.mantissa, the 68k
 * convention (the PRM gives 1.8e-4951 as the smallest extended denormal, which is 2^-16446).
 *
 * Errors (a value too large for the format) throw {@link IllegalArgumentException} with a message
 * meant for the user; a value too small rounds to zero or a denormal like the hardware would.
 */
final class M68kFloat {

    private M68kFloat() {
    }

    /** Parses a literal's text ({@code 1.5}, {@code 1e-3}) without its sign. */
    static BigDecimal parse(String text) {
        BigDecimal v;
        try {
            v = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad floating-point number '" + text + "'");
        }
        if (v.scale() < -100_000 || v.scale() > 100_000)
            throw new IllegalArgumentException("floating-point number '" + text + "' out of range");
        return v.abs();
    }

    /** IEEE single bits of {@code mag} (not negative), with the sign {@code negative}. */
    static int single(boolean negative, BigDecimal mag) {
        long[] r = round(mag, 24, -126, 127, "single", -126);
        long bits = r[1] < (1L << 23) ? r[1] : ((r[0] + 127) << 23) | (r[1] & 0x7F_FFFFL);
        return (int) bits | (negative ? 0x8000_0000 : 0);
    }

    /** IEEE double bits. */
    static long dbl(boolean negative, BigDecimal mag) {
        long[] r = round(mag, 53, -1022, 1023, "double", -1022);
        long bits = r[1] < (1L << 52) ? r[1] : ((r[0] + 1023) << 52) | (r[1] & 0xF_FFFF_FFFF_FFFFL);
        return bits | (negative ? 0x8000_0000_0000_0000L : 0);
    }

    /** Extended: three longwords (sign and biased exponent in the high half of the first). */
    static int[] extended(boolean negative, BigDecimal mag) {
        long[] r = round(mag, 64, -16382, 16383, "extended", -16383);
        long mant = r[1];
        int se = mant == 0 || r[0] == -16383 ? 0 : (int) (r[0] + 16383);
        if (negative)
            se |= 0x8000;
        return new int[] {se << 16, (int) (mant >>> 32), (int) mant};
    }

    /** Packed decimal: three longwords, 17 significant digits, exponent -999..999. */
    static int[] packed(boolean negative, BigDecimal mag) {
        int sign = negative ? 0x8000_0000 : 0;
        if (mag.signum() == 0)
            return new int[] {sign, 0, 0};
        BigDecimal r = mag.round(new MathContext(17, RoundingMode.HALF_EVEN));
        String digits = r.unscaledValue().toString();
        int exp = digits.length() - 1 - r.scale();
        StringBuilder d = new StringBuilder(digits);
        while (d.length() < 17)
            d.append('0');
        if (Math.abs(exp) > 999)
            throw new IllegalArgumentException("exponent " + exp + " out of range for packed decimal (-999..999)");
        int ae = Math.abs(exp);
        int w0 = sign | (exp < 0 ? 0x4000_0000 : 0)
                | (ae / 100) << 24 | (ae / 10 % 10) << 20 | (ae % 10) << 16 | (d.charAt(0) - '0');
        int w1 = 0, w2 = 0;
        for (int i = 1; i <= 8; i++)
            w1 = (w1 << 4) | (d.charAt(i) - '0');
        for (int i = 9; i <= 16; i++)
            w2 = (w2 << 4) | (d.charAt(i) - '0');
        return new int[] {w0, w1, w2};
    }

    /**
     * Rounds {@code v} (not negative) to {@code p} significant bits. Returns {exponent, mantissa}:
     * the value is mantissa * 2^(exponent - p + 1). A normal result has its mantissa in
     * [2^(p-1), 2^p) and exponent in [emin, emax]; below emin the value is scaled by
     * {@code denormExp} instead and the mantissa may be smaller. Zero gives mantissa 0. For p = 64
     * the mantissa is the unsigned 64-bit pattern.
     */
    private static long[] round(BigDecimal v, int p, int emin, int emax, String what, int denormExp) {
        if (v.signum() == 0)
            return new long[] {denormExp, 0};
        BigInteger n = v.unscaledValue();
        BigInteger d = BigInteger.ONE;
        if (v.scale() > 0)
            d = BigInteger.TEN.pow(v.scale());
        else if (v.scale() < 0)
            n = n.multiply(BigInteger.TEN.pow(-v.scale()));
        // e = floor(log2(n / d))
        int e = n.bitLength() - d.bitLength();
        if (compareScaled(n, d, e) < 0)
            e--;
        if (e > emax)
            throw new IllegalArgumentException("value too large for " + what);
        int scaleExp = e < emin ? denormExp : e;
        BigInteger m = scaledRound(n, d, p - 1 - scaleExp);
        if (m.bitLength() > p) {          // rounded up to 2^p
            m = m.shiftRight(1);
            scaleExp++;
        }
        if (scaleExp > emax)
            throw new IllegalArgumentException("value too large for " + what);
        return new long[] {scaleExp, m.longValue()};
    }

    /** Compares n with d * 2^e. */
    private static int compareScaled(BigInteger n, BigInteger d, int e) {
        return e >= 0 ? n.compareTo(d.shiftLeft(e)) : n.shiftLeft(-e).compareTo(d);
    }

    /** round(n / d * 2^shift), ties to even. */
    private static BigInteger scaledRound(BigInteger n, BigInteger d, int shift) {
        BigInteger num = shift >= 0 ? n.shiftLeft(shift) : n;
        BigInteger den = shift >= 0 ? d : d.shiftLeft(-shift);
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        int c = qr[1].shiftLeft(1).compareTo(den);
        if (c > 0 || (c == 0 && q.testBit(0)))
            q = q.add(BigInteger.ONE);
        return q;
    }
}
