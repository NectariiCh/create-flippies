package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Packed decimal real (format P, 12 bytes), with exact decimal arithmetic (spec 10.4). Layout
 * of the three longs, most significant first:
 * <ul>
 * <li>word 0: SM (31, mantissa sign), SE (30, exponent sign), YY (29..28), three BCD exponent
 * digits (27..16, hundreds first), a fourth exponent digit (15..12, written on output when the
 * exponent needs it), zeros (11..4), the integer digit (3..0);</li>
 * <li>words 1 and 2: sixteen BCD fraction digits.</li>
 * </ul>
 * Value: SM D.DDDDDDDDDDDDDDDD x 10^(SE EEE). Exponent field 0xFFF with SE, YY all set: an
 * infinity (fraction zero) or a NaN (the 64 fraction bits are the extended NaN's mantissa).
 *
 * Input is converted exactly and rounded once to extended precision in the FPCR mode (INEX1 if
 * inexact; the real support package is not always correctly rounded, so this can differ from a
 * real 68040 in the last bit). Output follows the k-factor rules of the support package
 * ({@code bindec}): k > 0 is the number of significant digits (above 17 is OPERR and 17); k <= 0
 * is the number of digits right of the decimal point, so LEN = ILOG + 1 - k clamped to 1..17.
 * The decimal rounding uses the FPCR mode; INEX2 if inexact. An exponent of four digits puts its
 * thousands digit in bits 15..12 and raises OPERR.
 */
public final class FpPacked {

    private FpPacked() {
    }

    /** Converts a packed operand into {@code c} (extended, the context's mode). Flags INEX1. */
    public static void decode(Fx80.Ctx c, int w0, int w1, int w2) {
        long digitsLo = ((long) w1 << 32) | (w2 & 0xFFFF_FFFFL);
        boolean neg = w0 < 0;
        if ((w0 & 0x7FFF_0000) == 0x7FFF_0000) {
            c.set((neg ? Fx80.SIGN : 0) | Fx80.EXP_SPECIAL, (digitsLo << 1) == 0 ? 0 : digitsLo);
            return;
        }
        BigInteger m = BigInteger.valueOf(w0 & 0xF);
        for (int i = 15; i >= 0; i--)
            m = m.multiply(BigInteger.TEN).add(BigInteger.valueOf((digitsLo >>> (i * 4)) & 0xF));
        if (m.signum() == 0) {
            c.set(neg ? Fx80.SIGN : 0, 0);
            return;
        }
        int exp = ((w0 >>> 24) & 0xF) * 100 + ((w0 >>> 20) & 0xF) * 10 + ((w0 >>> 16) & 0xF);
        if ((w0 & 0x4000_0000) != 0)
            exp = -exp;
        int n = exp - 16;
        int flags = c.flags;
        int prec = c.precision;
        c.flags = 0;
        if (n >= 0) {
            packBig(c, neg, m.multiply(BigInteger.TEN.pow(n)), 0, false);
        } else {
            BigInteger den = BigInteger.TEN.pow(-n);
            int k = Math.max(0, 140 + den.bitLength() - m.bitLength());
            BigInteger[] qr = m.shiftLeft(k).divideAndRemainder(den);
            packBig(c, neg, qr[0], k, qr[1].signum() != 0);
        }
        c.precision = prec;
        c.flags = flags | ((c.flags & Fx80.INEX2) != 0 ? Fx80.INEX1 : 0);
    }

    /** Rounds {@code v * 2^-k} (plus a sticky bit) to extended precision into {@code c}. */
    private static void packBig(Fx80.Ctx c, boolean neg, BigInteger v, int k, boolean sticky) {
        int len = v.bitLength();
        BigInteger top;
        if (len > 128) {
            top = v.shiftRight(len - 128);
            sticky |= v.getLowestSetBit() < len - 128;
        } else {
            top = v.shiftLeft(128 - len);
        }
        long hi = top.shiftRight(64).longValue();
        long lo = top.longValue() | (sticky ? 1 : 0);
        Fx80.roundPack(c, neg, Fx80.BIAS - 1 + len - k, hi, lo, Fx80.PREC_EXTENDED);
    }

    /**
     * Converts {@code se, mant} to packed decimal with k-factor {@code k} (-64..63) in the
     * context's rounding mode. Returns the three longs; flags INEX2 and OPERR, SNAN for a
     * signalling NaN (stored quiet).
     */
    public static int[] encode(Fx80.Ctx c, int se, long mant, int k) {
        boolean neg = Fx80.isNegative(se);
        int sm = neg ? 0x8000_0000 : 0;
        if ((se & Fx80.EXP_MASK) == Fx80.EXP_SPECIAL) {
            long m = mant;
            if (Fx80.isNan(se, mant)) {
                if ((mant & Fx80.QUIET_BIT) == 0)
                    c.flags |= Fx80.SNAN;
                m |= Fx80.QUIET_BIT;
            } else {
                m = 0;
            }
            return new int[] {sm | 0x7FFF_0000, (int) (m >>> 32), (int) m};
        }
        if (mant == 0)
            return new int[] {sm, 0, 0};
        int nz = Long.numberOfLeadingZeros(mant);
        long m = mant << nz;
        int e = (se & Fx80.EXP_MASK) - nz - Fx80.BIAS - 63;
        BigDecimal x = new BigDecimal(new BigInteger(1, toBytes(m)));
        x = e >= 0 ? x.multiply(new BigDecimal(BigInteger.TWO.pow(e))) : x.divide(new BigDecimal(BigInteger.TWO.pow(-e)));
        if (neg)
            x = x.negate();
        int ilog = x.precision() - x.scale() - 1;
        int len;
        if (k > 0) {
            len = k;
            if (k > 17) {
                c.flags |= Fx80.OPERR;
                len = 17;
            }
        } else {
            len = Math.max(1, Math.min(17, ilog + 1 - k));
        }
        RoundingMode rm = switch (c.mode) {
            case Fx80.RN -> RoundingMode.HALF_EVEN;
            case Fx80.RZ -> RoundingMode.DOWN;
            case Fx80.RM -> RoundingMode.FLOOR;
            default -> RoundingMode.CEILING;
        };
        BigDecimal r = x.round(new MathContext(len, rm));
        if (r.compareTo(x) != 0)
            c.flags |= Fx80.INEX2;
        BigInteger digits = r.unscaledValue().abs();
        int rexp = r.precision() - r.scale() - 1;
        // 17 digits, most significant first
        String s = digits.toString();
        while (s.endsWith("0") && s.length() > 1)
            s = s.substring(0, s.length() - 1);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 17)
            sb.append('0');
        long frac = 0;
        for (int i = 1; i < 17; i++)
            frac = (frac << 4) | (sb.charAt(i) - '0');
        int w0 = sm | (sb.charAt(0) - '0');
        int ae = Math.abs(rexp);
        if (rexp < 0)
            w0 |= 0x4000_0000;
        w0 |= (ae / 100 % 10) << 24 | (ae / 10 % 10) << 20 | (ae % 10) << 16;
        if (ae >= 1000) {
            w0 |= (ae / 1000 % 10) << 12;
            c.flags |= Fx80.OPERR;
        }
        return new int[] {w0, (int) (frac >>> 32), (int) frac};
    }

    private static byte[] toBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++)
            b[i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }
}
