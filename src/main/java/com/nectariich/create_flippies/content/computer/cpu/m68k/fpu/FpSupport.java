package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import com.nectariich.create_flippies.content.computer.cpu.m68k.FpuOpcodes;

/**
 * The instructions a real 68040 leaves to its floating-point support package (spec 10.2.4,
 * 10.4), emulated here when the FPU is not strict.
 *
 * Exact: FMOD and FREM (integer remainder on the mantissas, also giving the quotient byte),
 * FSCALE, FGETEXP, FGETMAN, FSGLMUL and FSGLDIV (operand mantissas truncated to 24 bits, result
 * rounded to a 24-bit mantissa with the extended exponent range, as the support package does).
 * FINT and FINTRZ are in {@link Fx80#roundToInteger}.
 *
 * Approximate (documented deviation): the transcendentals go through {@code StrictMath} in double
 * precision, so they are correct to about 53 bits, not 64. The exponential family splits the
 * argument into an integer power of two and a fraction so results keep the extended exponent
 * range; logarithms work on the mantissa and exponent separately for the same reason; arguments
 * too small for a double use the first term of the series; trigonometric arguments too large for
 * a double are first reduced exactly by the extended value of 2 pi. INEX2 is set unless the result
 * is exact by construction (special operands, and the exact cases tested in {@link #isExact}).
 */
public final class FpSupport {

    private FpSupport() {
    }

    /** Below this true exponent a double cannot hold the argument with full precision. */
    private static final int TINY_EXP = -1000;

    // ---- FMOD and FREM ----

    /**
     * FMOD ({@code rem} false) or FREM (true): destination modulo source into {@code c}. Returns
     * the FPSR quotient byte (sign in bit 7, low 7 bits of the quotient's magnitude).
     */
    public static int modRem(Fx80.Ctx c, int dSe, long dM, int sSe, long sM, boolean rem) {
        if (Fx80.isNan(dSe, dM) || Fx80.isNan(sSe, sM)) {
            Fx80.nan2(c, dSe, dM, sSe, sM);
            return 0;
        }
        boolean dn = Fx80.isNegative(dSe);
        int qSign = (dn ^ Fx80.isNegative(sSe)) ? 0x80 : 0;
        if (Fx80.isInf(dSe, dM) || Fx80.isZero(sSe, sM)) {
            Fx80.operandError(c);
            return 0;
        }
        if (Fx80.isZero(dSe, dM) || Fx80.isInf(sSe, sM)) {
            Fx80.round(c, dSe, dM);
            return qSign;
        }
        int de = Fx80.exponentOf(dSe, dM);
        int sex = Fx80.exponentOf(sSe, sM);
        long mx = dM << Long.numberOfLeadingZeros(dM);
        long my = sM << Long.numberOfLeadingZeros(sM);
        long q = 0;
        long r;
        int d = de - sex;
        // r is the remainder at the source's scale: |result| = r * 2^(sex - 63)
        if (d < 0) {
            r = mx;
        } else {
            r = mx;
            boolean bit = Long.compareUnsigned(r, my) >= 0;
            if (bit)
                r -= my;
            q = bit ? 1 : 0;
            for (int i = 0; i < d; i++) {
                boolean carry = r < 0;
                r <<= 1;
                bit = carry || Long.compareUnsigned(r, my) >= 0;
                if (bit)
                    r -= my;
                q = (q << 1) | (bit ? 1 : 0);
            }
        }
        boolean neg = dn;
        int rexp = d < 0 ? de : sex;
        if (rem) {
            // round the quotient to nearest even: compare 2r with y
            int cmp;
            if (d < -1) {
                cmp = -1;
            } else if (d == -1) {
                cmp = Long.compareUnsigned(mx, my);
            } else {
                cmp = r < 0 ? 1 : Long.compareUnsigned(r << 1, my);
            }
            if (cmp > 0 || (cmp == 0 && (q & 1) != 0)) {
                if (d < 0) {
                    // |y|/2 < |x| < |y|: the result is -(|y| - |x|) = (2 my - mx) * 2^(sex - 64)
                    long v = (my << 1) - mx;
                    Fx80.roundPack(c, !neg, sex + Fx80.BIAS - 1, v, 0, c.precision);
                    return qSign | 1;
                }
                r = my - r;
                neg = !neg;
                q++;
            }
        }
        if (r == 0) {
            Fx80.setZero(c, dn);
        } else {
            Fx80.roundPack(c, neg, rexp + Fx80.BIAS, r, 0, c.precision);
        }
        return qSign | (int) (q & 0x7F);
    }

    // ---- FSCALE, FGETEXP, FGETMAN ----

    /** FSCALE: destination times 2 to the integer part of the source. */
    public static void scale(Fx80.Ctx c, int dSe, long dM, int sSe, long sM) {
        if (Fx80.isNan(dSe, dM) || Fx80.isNan(sSe, sM)) {
            Fx80.nan2(c, dSe, dM, sSe, sM);
            return;
        }
        if (Fx80.isInf(sSe, sM)) {
            Fx80.operandError(c);
            return;
        }
        if (Fx80.isInf(dSe, dM)) {
            Fx80.setInf(c, Fx80.isNegative(dSe));
            return;
        }
        if (Fx80.isZero(dSe, dM)) {
            Fx80.setZero(c, Fx80.isNegative(dSe));
            return;
        }
        int n;
        if (Fx80.isZero(sSe, sM)) {
            n = 0;
        } else {
            int e = Fx80.exponentOf(sSe, sM);
            if (e >= 20) {
                n = 1 << 20;
            } else {
                long m = sM << Long.numberOfLeadingZeros(sM);
                n = e < 0 ? 0 : (int) (m >>> (63 - e));
            }
            if (Fx80.isNegative(sSe))
                n = -n;
        }
        int nz = Long.numberOfLeadingZeros(dM);
        Fx80.roundPack(c, Fx80.isNegative(dSe), (dSe & Fx80.EXP_MASK) - nz + n, dM << nz, 0, c.precision);
    }

    /** FGETEXP: the unbiased exponent of the (normalised) operand, as a value. */
    public static void getExp(Fx80.Ctx c, int se, long mant) {
        if (Fx80.isNan(se, mant)) {
            Fx80.nan1(c, se, mant);
            return;
        }
        if (Fx80.isInf(se, mant)) {
            Fx80.operandError(c);
            return;
        }
        if (mant == 0) {
            Fx80.setZero(c, Fx80.isNegative(se));
            return;
        }
        Fx80.fromLong(c, Fx80.exponentOf(se, mant));
    }

    /** FGETMAN: the mantissa of the (normalised) operand with exponent 0, sign kept. */
    public static void getMan(Fx80.Ctx c, int se, long mant) {
        if (Fx80.isNan(se, mant)) {
            Fx80.nan1(c, se, mant);
            return;
        }
        if (Fx80.isInf(se, mant)) {
            Fx80.operandError(c);
            return;
        }
        if (mant == 0) {
            Fx80.setZero(c, Fx80.isNegative(se));
            return;
        }
        Fx80.round(c, (se & Fx80.SIGN) | Fx80.BIAS, mant << Long.numberOfLeadingZeros(mant));
    }

    // ---- FSGLMUL, FSGLDIV ----

    private static final long SGL_MASK = 0xFFFF_FF00_0000_0000L;

    private static long truncateSingle(int se, long mant) {
        if ((se & Fx80.EXP_MASK) == Fx80.EXP_SPECIAL || mant == 0)
            return mant;
        int nz = Long.numberOfLeadingZeros(mant);
        return ((mant << nz) & SGL_MASK) >>> nz;
    }

    public static void sglMul(Fx80.Ctx c, int dSe, long dM, int sSe, long sM) {
        Fx80.mul(c, dSe, truncateSingle(dSe, dM), sSe, truncateSingle(sSe, sM), Fx80.PREC_SINGLE_MANTISSA);
    }

    public static void sglDiv(Fx80.Ctx c, int dSe, long dM, int sSe, long sM) {
        Fx80.div(c, dSe, truncateSingle(dSe, dM), sSe, truncateSingle(sSe, sM), Fx80.PREC_SINGLE_MANTISSA);
    }

    // ---- transcendentals ----

    /** Extended 2 pi (exact pattern of the 64-bit value), for reducing huge trigonometric arguments. */
    private static final int TWO_PI_SE = 0x4001;
    private static final long TWO_PI_MANT = 0xC90F_DAA2_2168_C235L;

    /** Whether {@code opmode} (bits 6..0 of the command word) is one of the transcendentals below. */
    public static boolean isTranscendental(int opmode) {
        return switch (opmode) {
            case FpuOpcodes.FSINH, FpuOpcodes.FLOGNP1, FpuOpcodes.FETOXM1, FpuOpcodes.FTANH, FpuOpcodes.FATAN,
                    FpuOpcodes.FASIN, FpuOpcodes.FATANH, FpuOpcodes.FSIN, FpuOpcodes.FTAN, FpuOpcodes.FETOX,
                    FpuOpcodes.FTWOTOX, FpuOpcodes.FTENTOX, FpuOpcodes.FLOGN, FpuOpcodes.FLOG10, FpuOpcodes.FLOG2,
                    FpuOpcodes.FCOSH, FpuOpcodes.FACOS, FpuOpcodes.FCOS -> true;
            default -> false;
        };
    }

    /**
     * Computes transcendental {@code opmode} of {@code se, mant} into {@code c}, rounded to the
     * context's precision. FSINCOS is two calls (FSIN and FCOS).
     */
    public static void transcendental(Fx80.Ctx c, int opmode, int se, long mant) {
        if (Fx80.isNan(se, mant)) {
            Fx80.nan1(c, se, mant);
            return;
        }
        boolean neg = Fx80.isNegative(se);
        if (Fx80.isInf(se, mant)) {
            infinite(c, opmode, neg);
            return;
        }
        if (mant == 0) {
            zeroArg(c, opmode, neg);
            return;
        }
        int e = Fx80.exponentOf(se, mant);
        if (e < TINY_EXP) {
            tiny(c, opmode, se, mant);
            return;
        }
        // |x| = m * 2^e with m in [1, 2); unity: |x| == 1 exactly
        long m = mant << Long.numberOfLeadingZeros(mant);
        boolean unity = e == 0 && m == Fx80.INT_BIT;
        boolean big = e > 1000;
        double x = Fx80.toJavaDouble(se, mant);
        double r;
        switch (opmode) {
            case FpuOpcodes.FSIN, FpuOpcodes.FCOS, FpuOpcodes.FTAN -> {
                if (big)
                    x = reduceTwoPi(se, mant);
                r = opmode == FpuOpcodes.FSIN ? StrictMath.sin(x) : opmode == FpuOpcodes.FCOS ? StrictMath.cos(x) : StrictMath.tan(x);
            }
            case FpuOpcodes.FASIN, FpuOpcodes.FACOS -> {
                if (e > 0 || (e == 0 && !unity)) {
                    Fx80.operandError(c);
                    return;
                }
                r = opmode == FpuOpcodes.FASIN ? StrictMath.asin(x) : StrictMath.acos(x);
            }
            case FpuOpcodes.FATAN -> r = StrictMath.atan(x);
            case FpuOpcodes.FATANH -> {
                if (e >= 0) {
                    if (unity) {
                        c.flags |= Fx80.DZ;
                        Fx80.setInf(c, neg);
                    } else {
                        Fx80.operandError(c);
                    }
                    return;
                }
                r = 0.5 * (StrictMath.log1p(x) - StrictMath.log1p(-x));
            }
            case FpuOpcodes.FSINH, FpuOpcodes.FCOSH, FpuOpcodes.FTANH -> {
                if (e >= 9) {
                    // |x| >= 512: tanh is +-1 to every precision, sinh and cosh are e^|x| / 2
                    if (opmode == FpuOpcodes.FTANH)
                        Fx80.fromLong(c, neg ? -1 : 1);
                    else
                        expBase(c, magnitudeLog2E(se, mant) - 1, neg && opmode == FpuOpcodes.FSINH);
                    c.flags |= Fx80.INEX2;
                    return;
                }
                r = opmode == FpuOpcodes.FSINH ? StrictMath.sinh(x) : opmode == FpuOpcodes.FCOSH ? StrictMath.cosh(x) : StrictMath.tanh(x);
            }
            case FpuOpcodes.FTWOTOX, FpuOpcodes.FETOX, FpuOpcodes.FTENTOX -> {
                if (x == Math.rint(x) && !big) {
                    if (opmode == FpuOpcodes.FTWOTOX) {
                        Fx80.roundPack(c, false, (int) x + Fx80.BIAS, Fx80.INT_BIT, 0, c.precision);
                        return;
                    }
                    if (opmode == FpuOpcodes.FTENTOX && x >= 0 && x <= 27) {
                        long p = 1;
                        for (int i = 0; i < (int) x; i++)
                            p *= 10;
                        Fx80.fromLong(c, p);
                        return;
                    }
                }
                double t = opmode == FpuOpcodes.FTWOTOX ? x
                        : opmode == FpuOpcodes.FETOX ? x / StrictMath.log(2) : x * (StrictMath.log(10) / StrictMath.log(2));
                if (Math.abs(t) > 1000) {
                    // beyond the double range: split into 2^n * 2^f (a few ulps less accurate)
                    expBase(c, t, false);
                    c.flags |= Fx80.INEX2;
                    return;
                }
                r = opmode == FpuOpcodes.FTWOTOX ? StrictMath.pow(2, x)
                        : opmode == FpuOpcodes.FETOX ? StrictMath.exp(x) : StrictMath.pow(10, x);
            }
            case FpuOpcodes.FETOXM1 -> {
                if (e >= 9) {
                    if (neg)
                        Fx80.fromLong(c, -1);
                    else
                        expBase(c, x / StrictMath.log(2), false);
                    c.flags |= Fx80.INEX2;
                    return;
                }
                r = StrictMath.expm1(x);
            }
            case FpuOpcodes.FLOGN, FpuOpcodes.FLOG10, FpuOpcodes.FLOG2 -> {
                if (neg) {
                    Fx80.operandError(c);
                    return;
                }
                if (m == Fx80.INT_BIT && opmode == FpuOpcodes.FLOG2) {
                    Fx80.fromLong(c, e);
                    return;
                }
                r = log(opmode, se, mant);
            }
            case FpuOpcodes.FLOGNP1 -> {
                if (neg && e >= 0) {
                    if (unity) {
                        c.flags |= Fx80.DZ;
                        Fx80.setInf(c, true);
                    } else {
                        Fx80.operandError(c);
                    }
                    return;
                }
                if (big || x <= -0.5) {
                    // log of 1 + x formed in extended (exact for x <= -0.5; the 1 is negligible for big x)
                    Fx80.Ctx tmp = new Fx80.Ctx(Fx80.RN, Fx80.PREC_EXTENDED);
                    Fx80.add(tmp, Fx80.BIAS, Fx80.INT_BIT, se, mant);
                    r = log(FpuOpcodes.FLOGN, tmp.se, tmp.mant);
                } else {
                    r = StrictMath.log1p(x);
                }
            }
            default -> throw new IllegalArgumentException("opmode " + opmode);
        }
        Fx80.fromJavaDouble(c, r);
        if (!isExact(opmode, x, r))
            c.flags |= Fx80.INEX2;
    }

    /** log2(e) * |x|, for the e^|x| paths of sinh and cosh. */
    private static double magnitudeLog2E(int se, long mant) {
        if (Fx80.exponentOf(se, mant) > 1000)
            return Double.POSITIVE_INFINITY;
        return Math.abs(Fx80.toJavaDouble(se, mant)) / StrictMath.log(2);
    }

    /** ln, log10 or log2 of a positive finite value of any extended magnitude. */
    private static double log(int opmode, int se, long mant) {
        int e = Fx80.exponentOf(se, mant);
        if (e > -1000 && e < 1000) {
            double x = Fx80.toJavaDouble(se, mant);
            return switch (opmode) {
                case FpuOpcodes.FLOGN -> StrictMath.log(x);
                case FpuOpcodes.FLOG10 -> StrictMath.log10(x);
                default -> StrictMath.log(x) / StrictMath.log(2);
            };
        }
        // log(m * 2^e) = log(m) + e log(2), m in [1, 2)
        double mm = Fx80.toJavaDouble(Fx80.BIAS, mant << Long.numberOfLeadingZeros(mant));
        double l2 = StrictMath.log(mm) / StrictMath.log(2) + e;
        return switch (opmode) {
            case FpuOpcodes.FLOGN -> StrictMath.log(mm) + e * StrictMath.log(2);
            case FpuOpcodes.FLOG10 -> l2 * (StrictMath.log(2) / StrictMath.log(10));
            default -> l2;
        };
    }

    /** 2^t into {@code c}: integer part to the exponent, the fraction through StrictMath. */
    private static void expBase(Fx80.Ctx c, double t, boolean neg) {
        if (t > 20000) {
            Fx80.roundPack(c, neg, 0x7FFF + 100, Fx80.INT_BIT, 0, c.precision);
            return;
        }
        if (t < -20000) {
            Fx80.roundPack(c, neg, -100, Fx80.INT_BIT, 0, c.precision);
            return;
        }
        double n = Math.floor(t);
        double f = StrictMath.pow(2, t - n);
        Fx80.Ctx tmp = new Fx80.Ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.fromDouble(tmp, Double.doubleToRawLongBits(f));
        long m = tmp.mant;
        int ex = (tmp.se & Fx80.EXP_MASK) + (int) n;
        Fx80.roundPack(c, neg, ex, m, 0, c.precision);
    }

    /** x mod 2 pi exactly (with the extended 2 pi), as a double. */
    private static double reduceTwoPi(int se, long mant) {
        Fx80.Ctx tmp = new Fx80.Ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        modRem(tmp, se, mant, TWO_PI_SE, TWO_PI_MANT, true);
        return Fx80.toJavaDouble(tmp.se, tmp.mant);
    }

    /** Results that are exact by construction (no INEX2). */
    static boolean isExact(int opmode, double x, double r) {
        return switch (opmode) {
            case FpuOpcodes.FLOGN -> x == 1;
            case FpuOpcodes.FLOG10 -> r == Math.rint(r) && StrictMath.pow(10, r) == x && Math.abs(r) <= 22;
            case FpuOpcodes.FLOG2 -> x == 1;
            default -> false;
        };
    }

    private static void infinite(Fx80.Ctx c, int opmode, boolean neg) {
        switch (opmode) {
            case FpuOpcodes.FSIN, FpuOpcodes.FCOS, FpuOpcodes.FTAN, FpuOpcodes.FASIN, FpuOpcodes.FACOS, FpuOpcodes.FATANH ->
                    Fx80.operandError(c);
            case FpuOpcodes.FATAN -> {
                Fx80.fromJavaDouble(c, neg ? -Math.PI / 2 : Math.PI / 2);
                c.flags |= Fx80.INEX2;
            }
            case FpuOpcodes.FSINH -> Fx80.setInf(c, neg);
            case FpuOpcodes.FCOSH -> Fx80.setInf(c, false);
            case FpuOpcodes.FTANH -> Fx80.fromLong(c, neg ? -1 : 1);
            case FpuOpcodes.FETOX, FpuOpcodes.FTWOTOX, FpuOpcodes.FTENTOX -> {
                if (neg)
                    Fx80.setZero(c, false);
                else
                    Fx80.setInf(c, false);
            }
            case FpuOpcodes.FETOXM1 -> {
                if (neg)
                    Fx80.fromLong(c, -1);
                else
                    Fx80.setInf(c, false);
            }
            case FpuOpcodes.FLOGN, FpuOpcodes.FLOG10, FpuOpcodes.FLOG2, FpuOpcodes.FLOGNP1 -> {
                if (neg)
                    Fx80.operandError(c);
                else
                    Fx80.setInf(c, false);
            }
            default -> throw new IllegalArgumentException("opmode " + opmode);
        }
    }

    private static void zeroArg(Fx80.Ctx c, int opmode, boolean neg) {
        switch (opmode) {
            case FpuOpcodes.FSIN, FpuOpcodes.FTAN, FpuOpcodes.FASIN, FpuOpcodes.FATAN, FpuOpcodes.FATANH,
                    FpuOpcodes.FSINH, FpuOpcodes.FTANH, FpuOpcodes.FETOXM1, FpuOpcodes.FLOGNP1 -> Fx80.setZero(c, neg);
            case FpuOpcodes.FCOS, FpuOpcodes.FCOSH, FpuOpcodes.FETOX, FpuOpcodes.FTWOTOX, FpuOpcodes.FTENTOX ->
                    Fx80.fromLong(c, 1);
            case FpuOpcodes.FACOS -> {
                Fx80.fromJavaDouble(c, Math.PI / 2);
                c.flags |= Fx80.INEX2;
            }
            case FpuOpcodes.FLOGN, FpuOpcodes.FLOG10, FpuOpcodes.FLOG2 -> {
                c.flags |= Fx80.DZ;
                Fx80.setInf(c, true);
            }
            default -> throw new IllegalArgumentException("opmode " + opmode);
        }
    }

    /** |x| below 2^-1000: the first term of each function's series. */
    private static void tiny(Fx80.Ctx c, int opmode, int se, long mant) {
        boolean neg = Fx80.isNegative(se);
        switch (opmode) {
            case FpuOpcodes.FSIN, FpuOpcodes.FTAN, FpuOpcodes.FASIN, FpuOpcodes.FATAN, FpuOpcodes.FATANH,
                    FpuOpcodes.FSINH, FpuOpcodes.FTANH, FpuOpcodes.FETOXM1, FpuOpcodes.FLOGNP1 -> Fx80.round(c, se, mant);
            case FpuOpcodes.FCOS, FpuOpcodes.FCOSH, FpuOpcodes.FETOX, FpuOpcodes.FTWOTOX, FpuOpcodes.FTENTOX ->
                    Fx80.fromLong(c, 1);
            case FpuOpcodes.FACOS -> Fx80.fromJavaDouble(c, Math.PI / 2);
            case FpuOpcodes.FLOGN, FpuOpcodes.FLOG10, FpuOpcodes.FLOG2 -> {
                if (neg) {
                    Fx80.operandError(c);
                    return;
                }
                double r = log(opmode, se, mant);
                Fx80.fromJavaDouble(c, r);
            }
            default -> throw new IllegalArgumentException("opmode " + opmode);
        }
        c.flags |= Fx80.INEX2;
    }
}
