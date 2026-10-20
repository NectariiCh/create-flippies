package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

/**
 * Soft-float 80-bit extended precision in the 68k layout (spec 10.4): a value is a pair of a
 * 16-bit sign and exponent word {@code se} (sign bit 15, biased exponent 14..0, bias 16383) and
 * a 64-bit mantissa {@code mant} with an explicit integer bit (bit 63).
 *
 * Value of a finite operand: {@code mant * 2^(exponent - 16383 - 63)} for every exponent from 0
 * to 0x7FFE, whatever the integer bit says. That is the 68k rule: unlike the x87 format, an
 * exponent of 0 has the same scale as 1 would have minus one, so the smallest normal number is
 * 2^-16383 (exponent 0, integer bit 1) and the smallest denormal 2^-16446. Exponent 0x7FFF is an
 * infinity when the fraction (bits 62..0) is zero, else a NaN, signalling when bit 62 is clear;
 * the integer bit is ignored there. An exponent above 0 with the integer bit clear is an
 * unnormal; operations normalise it first. An unnormal zero (mantissa 0) is a zero.
 *
 * All operations are static and allocation free: they take operands as (se, mant) pairs and
 * leave the result in a reusable {@link Ctx}, which also carries the rounding mode, the rounding
 * precision and the accumulated exception flags. Flags use the bit positions of the FPSR
 * exception status byte, so the FPU ORs them straight in.
 *
 * Rounding (68040 rule, checked against the floating-point support package's overflow and
 * underflow result tables): single and double rounding precision reduce both the mantissa (24 or
 * 53 bits) and the exponent range (overflow above 2^128 or 2^1024, denormalised results below
 * 2^-126 or 2^-1022). Results always come back as extended values, normalised when they fit.
 * Tininess is detected before rounding; UNFL is raised for a tiny result that is inexact, or for
 * any tiny result when the UNFL trap is enabled (so the accrued UNFL = UNFL and INEX2 of the PRM
 * holds). NaN rules (68k, not the IEEE default NaN): an operation with a NaN operand returns the
 * destination NaN if the destination is one, else the source NaN, made quiet; a signalling NaN
 * raises SNAN; an invalid operation returns the default NaN (positive, all mantissa bits set) and
 * raises OPERR.
 */
public final class Fx80 {

    private Fx80() {
    }

    public static final int SIGN = 0x8000;
    public static final int EXP_MASK = 0x7FFF;
    public static final int BIAS = 16383;
    /** Exponent field of infinities and NaNs. */
    public static final int EXP_SPECIAL = 0x7FFF;
    public static final long INT_BIT = 0x8000_0000_0000_0000L;
    /** Bit 62: set in a quiet NaN, clear in a signalling one. */
    public static final long QUIET_BIT = 0x4000_0000_0000_0000L;
    /** The default NaN an invalid operation produces: 0x7FFF_FFFF_FFFF_FFFF_FFFF. */
    public static final int DEFAULT_NAN_SE = 0x7FFF;
    public static final long DEFAULT_NAN_MANT = -1L;

    /**
     * Exponent offset of the unpacked integer form: a finite value is {@code mant * 2^(e -
     * UNPACKED)}, {@code e} the (possibly negative after normalising) exponent.
     */
    static final int UNPACKED = BIAS + 63;

    // ---- exception flags, FPSR exception status byte positions (spec 10.2.4) ----

    public static final int BSUN = 0x8000;
    public static final int SNAN = 0x4000;
    public static final int OPERR = 0x2000;
    public static final int OVFL = 0x1000;
    public static final int UNFL = 0x0800;
    public static final int DZ = 0x0400;
    public static final int INEX2 = 0x0200;
    public static final int INEX1 = 0x0100;

    // ---- rounding modes, FPCR bits 5..4 ----

    public static final int RN = 0;
    public static final int RZ = 1;
    public static final int RM = 2;
    public static final int RP = 3;

    // ---- rounding precisions (index into the tables below) ----

    /** 64-bit mantissa, extended exponent range. */
    public static final int PREC_EXTENDED = 0;
    /** 24-bit mantissa, single exponent range. */
    public static final int PREC_SINGLE = 1;
    /** 53-bit mantissa, double exponent range. */
    public static final int PREC_DOUBLE = 2;
    /** 24-bit mantissa, extended exponent range (FSGLMUL, FSGLDIV). */
    public static final int PREC_SINGLE_MANTISSA = 3;

    private static final int[] PREC_BITS = {64, 24, 53, 24};
    private static final int[] PREC_EMIN = {-16383, -126, -1022, -16383};
    private static final int[] PREC_EMAX = {16383, 127, 1023, 16383};

    /** Rounding mode, precision, enabled traps and accumulated flags, plus the last result. */
    public static final class Ctx {
        /** {@link #RN}, {@link #RZ}, {@link #RM} or {@link #RP}. */
        public int mode;
        /** {@link #PREC_EXTENDED} and so on. */
        public int precision;
        /** FPCR exception enable byte (bits 15..8); only UNFL changes rounding results. */
        public int enables;
        /** Exception flags raised so far, ORed by every operation. */
        public int flags;
        /** Result sign and exponent. */
        public int se;
        /** Result mantissa. */
        public long mant;
        /** Scratch of {@link #roundMagnitude}: whether the rounding to an integer was inexact. */
        boolean magInexact;

        public Ctx() {
        }

        public Ctx(int mode, int precision) {
            this.mode = mode;
            this.precision = precision;
        }

        public void set(int se, long mant) {
            this.se = se & 0xFFFF;
            this.mant = mant;
        }
    }

    // ---- classification ----

    public static boolean isNan(int se, long mant) {
        return (se & EXP_MASK) == EXP_SPECIAL && (mant << 1) != 0;
    }

    public static boolean isSignaling(int se, long mant) {
        return isNan(se, mant) && (mant & QUIET_BIT) == 0;
    }

    public static boolean isInf(int se, long mant) {
        return (se & EXP_MASK) == EXP_SPECIAL && (mant << 1) == 0;
    }

    /** Zero, including an unnormal zero (exponent above 0, mantissa 0). */
    public static boolean isZero(int se, long mant) {
        return (se & EXP_MASK) != EXP_SPECIAL && mant == 0;
    }

    /** Exponent 0, integer bit clear, mantissa not zero. */
    public static boolean isDenormal(int se, long mant) {
        return (se & EXP_MASK) == 0 && mant > 0;
    }

    /** Exponent 1..0x7FFE, integer bit clear (an unnormal zero included). */
    public static boolean isUnnormal(int se, long mant) {
        int e = se & EXP_MASK;
        return e != 0 && e != EXP_SPECIAL && mant >= 0;
    }

    public static boolean isNegative(int se) {
        return (se & SIGN) != 0;
    }

    /** True exponent of a finite nonzero value written as 1.f * 2^e (denormals and unnormals normalised). */
    public static int exponentOf(int se, long mant) {
        return (se & EXP_MASK) - Long.numberOfLeadingZeros(mant) - BIAS;
    }

    /** The FPSR condition code nibble (N 8, Z 4, I 2, NAN 1) of a value. */
    public static int conditionCodes(int se, long mant) {
        int cc = isNegative(se) ? 8 : 0;
        if (isNan(se, mant))
            return cc | 1;
        if (isInf(se, mant))
            return cc | 2;
        if (mant == 0)
            return cc | 4;
        return cc;
    }

    // ---- NaN helpers ----

    static void nan1(Ctx c, int se, long mant) {
        if ((mant & QUIET_BIT) == 0)
            c.flags |= SNAN;
        c.set(se, mant | QUIET_BIT);
    }

    /** Two operands, at least one a NaN: the destination's NaN wins (68k rule). */
    static void nan2(Ctx c, int dSe, long dM, int sSe, long sM) {
        if (isSignaling(dSe, dM) || isSignaling(sSe, sM))
            c.flags |= SNAN;
        if (isNan(dSe, dM))
            c.set(dSe, dM | QUIET_BIT);
        else
            c.set(sSe, sM | QUIET_BIT);
    }

    static void operandError(Ctx c) {
        c.flags |= OPERR;
        c.set(DEFAULT_NAN_SE, DEFAULT_NAN_MANT);
    }

    static void setInf(Ctx c, boolean neg) {
        c.set((neg ? SIGN : 0) | EXP_SPECIAL, 0);
    }

    static void setZero(Ctx c, boolean neg) {
        c.set(neg ? SIGN : 0, 0);
    }

    // ---- rounding ----

    /**
     * Rounds and packs {@code (hi:lo) * 2^(exp - BIAS - 127)} (a 128-bit magnitude, not
     * necessarily normalised; the lowest bit may be a sticky bit) to precision {@code prec} in
     * the context's rounding mode, raising INEX2, OVFL and UNFL. Result in {@code c.se, c.mant}.
     */
    static void roundPack(Ctx c, boolean neg, int exp, long hi, long lo, int prec) {
        if (hi == 0) {
            if (lo == 0) {
                setZero(c, neg);
                return;
            }
            hi = lo;
            lo = 0;
            exp -= 64;
        }
        int n = Long.numberOfLeadingZeros(hi);
        if (n != 0) {
            hi = (hi << n) | (lo >>> (64 - n));
            lo <<= n;
            exp -= n;
        }
        int p = PREC_BITS[prec];
        int emin = PREC_EMIN[prec];
        int emax = PREC_EMAX[prec];
        int e = exp - BIAS;
        boolean tiny = false;
        if (e < emin) {
            tiny = true;
            int sh = emin - e;
            e = emin;
            if (sh >= 128) {
                lo = (hi | lo) != 0 ? 1 : 0;
                hi = 0;
            } else if (sh >= 64) {
                long sticky = (lo != 0 || (sh > 64 && (hi << (128 - sh)) != 0)) ? 1 : 0;
                lo = (sh == 64 ? hi : hi >>> (sh - 64)) | sticky;
                hi = 0;
            } else {
                long sticky = (lo << (64 - sh)) != 0 ? 1 : 0;
                lo = (lo >>> sh) | (hi << (64 - sh)) | sticky;
                hi >>>= sh;
            }
        }
        int drop = 64 - p;
        long keep;
        boolean roundBit;
        boolean sticky;
        if (drop == 0) {
            keep = hi;
            roundBit = lo < 0;
            sticky = (lo << 1) != 0;
        } else {
            keep = hi >>> drop;
            roundBit = ((hi >>> (drop - 1)) & 1) != 0;
            sticky = lo != 0 || (hi & ((1L << (drop - 1)) - 1)) != 0;
        }
        boolean inexact = roundBit || sticky;
        boolean inc = switch (c.mode) {
            case RN -> roundBit && (sticky || (keep & 1) != 0);
            case RZ -> false;
            case RM -> inexact && neg;
            default -> inexact && !neg;
        };
        if (inc) {
            keep++;
            if (drop == 0 ? keep == 0 : keep == (1L << p)) {
                keep = drop == 0 ? INT_BIT : 1L << (p - 1);
                e++;
            }
        }
        if (e > emax) {
            c.flags |= OVFL | INEX2;
            boolean toInf = switch (c.mode) {
                case RN -> true;
                case RZ -> false;
                case RM -> neg;
                default -> !neg;
            };
            if (toInf)
                setInf(c, neg);
            else
                c.set((neg ? SIGN : 0) | (emax + BIAS), -1L << drop);
            return;
        }
        if (inexact)
            c.flags |= INEX2;
        if (tiny && (inexact || (c.enables & UNFL) != 0))
            c.flags |= UNFL;
        long mant = keep << drop;
        if (mant == 0) {
            setZero(c, neg);
            return;
        }
        if (mant > 0) {
            int z = Math.min(Long.numberOfLeadingZeros(mant), e + BIAS);
            mant <<= z;
            e -= z;
        }
        c.set((neg ? SIGN : 0) | (e + BIAS), mant);
    }

    /** Rounds a finite or special extended value to the context's precision (FMOVE to a register). */
    public static void round(Ctx c, int se, long mant) {
        round(c, se, mant, c.precision);
    }

    public static void round(Ctx c, int se, long mant, int prec) {
        if ((se & EXP_MASK) == EXP_SPECIAL) {
            if ((mant << 1) != 0)
                nan1(c, se, mant);
            else
                c.set(se, 0);
            return;
        }
        if (mant == 0) {
            setZero(c, isNegative(se));
            return;
        }
        roundPack(c, isNegative(se), se & EXP_MASK, mant, 0, prec);
    }

    // ---- arithmetic ----

    public static void add(Ctx c, int dSe, long dM, int sSe, long sM) {
        addSub(c, dSe, dM, sSe, sM, false);
    }

    /** Destination minus source. */
    public static void sub(Ctx c, int dSe, long dM, int sSe, long sM) {
        addSub(c, dSe, dM, sSe, sM, true);
    }

    private static void addSub(Ctx c, int dSe, long dM, int sSe, long sM, boolean subtract) {
        if (isNan(dSe, dM) || isNan(sSe, sM)) {
            nan2(c, dSe, dM, sSe, sM);
            return;
        }
        boolean dn = isNegative(dSe);
        boolean sn = isNegative(sSe) ^ subtract;
        int de = dSe & EXP_MASK;
        int sex = sSe & EXP_MASK;
        boolean dInf = de == EXP_SPECIAL;
        boolean sInf = sex == EXP_SPECIAL;
        if (dInf || sInf) {
            if (dInf && sInf && dn != sn)
                operandError(c);
            else
                setInf(c, dInf ? dn : sn);
            return;
        }
        boolean dZero = dM == 0;
        boolean sZero = sM == 0;
        if (dZero && sZero) {
            setZero(c, dn == sn ? dn : c.mode == RM);
            return;
        }
        if (dZero) {
            roundPack(c, sn, sex, sM, 0, c.precision);
            return;
        }
        if (sZero) {
            roundPack(c, dn, de, dM, 0, c.precision);
            return;
        }
        int nz = Long.numberOfLeadingZeros(dM);
        dM <<= nz;
        de -= nz;
        nz = Long.numberOfLeadingZeros(sM);
        sM <<= nz;
        sex -= nz;
        // a: the operand of larger magnitude
        int ea;
        int eb;
        long ma;
        long mb;
        boolean na;
        boolean nb;
        if (de > sex || (de == sex && Long.compareUnsigned(dM, sM) >= 0)) {
            ea = de;
            ma = dM;
            na = dn;
            eb = sex;
            mb = sM;
            nb = sn;
        } else {
            ea = sex;
            ma = sM;
            na = sn;
            eb = de;
            mb = dM;
            nb = dn;
        }
        int d = ea - eb;
        long bHi;
        long bLo;
        if (d == 0) {
            bHi = mb;
            bLo = 0;
        } else if (d < 64) {
            bHi = mb >>> d;
            bLo = mb << (64 - d);
        } else if (d < 128) {
            bHi = 0;
            bLo = d == 64 ? mb : (mb >>> (d - 64)) | ((mb << (128 - d)) != 0 ? 1 : 0);
        } else {
            bHi = 0;
            bLo = 1;
        }
        if (na == nb) {
            long hi = ma + bHi;
            long lo = bLo;
            if (Long.compareUnsigned(hi, ma) < 0) {
                lo = (lo >>> 1) | (hi << 63) | (lo & 1);
                hi = (hi >>> 1) | INT_BIT;
                ea++;
            }
            roundPack(c, na, ea, hi, lo, c.precision);
        } else {
            long lo = -bLo;
            long hi = ma - bHi - (bLo != 0 ? 1 : 0);
            if (hi == 0 && lo == 0) {
                setZero(c, c.mode == RM);
                return;
            }
            roundPack(c, na, ea, hi, lo, c.precision);
        }
    }

    /** Unsigned high 64 bits of a 64 x 64 product (JDK 17 has no unsignedMultiplyHigh). */
    static long multiplyHighUnsigned(long a, long b) {
        return Math.multiplyHigh(a, b) + ((a >> 63) & b) + ((b >> 63) & a);
    }

    public static void mul(Ctx c, int dSe, long dM, int sSe, long sM) {
        mul(c, dSe, dM, sSe, sM, c.precision);
    }

    static void mul(Ctx c, int dSe, long dM, int sSe, long sM, int prec) {
        if (isNan(dSe, dM) || isNan(sSe, sM)) {
            nan2(c, dSe, dM, sSe, sM);
            return;
        }
        boolean neg = isNegative(dSe) ^ isNegative(sSe);
        boolean dInf = (dSe & EXP_MASK) == EXP_SPECIAL;
        boolean sInf = (sSe & EXP_MASK) == EXP_SPECIAL;
        if (dInf || sInf) {
            if ((dInf ? sM : dM) == 0 && !(dInf && sInf))
                operandError(c);
            else
                setInf(c, neg);
            return;
        }
        if (dM == 0 || sM == 0) {
            setZero(c, neg);
            return;
        }
        int de = dSe & EXP_MASK;
        int sex = sSe & EXP_MASK;
        int nz = Long.numberOfLeadingZeros(dM);
        dM <<= nz;
        de -= nz;
        nz = Long.numberOfLeadingZeros(sM);
        sM <<= nz;
        sex -= nz;
        roundPack(c, neg, de + sex - (BIAS - 1), multiplyHighUnsigned(dM, sM), dM * sM, prec);
    }

    /** Destination divided by source. */
    public static void div(Ctx c, int dSe, long dM, int sSe, long sM) {
        div(c, dSe, dM, sSe, sM, c.precision);
    }

    static void div(Ctx c, int dSe, long dM, int sSe, long sM, int prec) {
        if (isNan(dSe, dM) || isNan(sSe, sM)) {
            nan2(c, dSe, dM, sSe, sM);
            return;
        }
        boolean neg = isNegative(dSe) ^ isNegative(sSe);
        boolean dInf = (dSe & EXP_MASK) == EXP_SPECIAL;
        boolean sInf = (sSe & EXP_MASK) == EXP_SPECIAL;
        if (dInf) {
            if (sInf)
                operandError(c);
            else
                setInf(c, neg);
            return;
        }
        if (sInf) {
            setZero(c, neg);
            return;
        }
        if (sM == 0) {
            if (dM == 0) {
                operandError(c);
            } else {
                c.flags |= DZ;
                setInf(c, neg);
            }
            return;
        }
        if (dM == 0) {
            setZero(c, neg);
            return;
        }
        int de = dSe & EXP_MASK;
        int sex = sSe & EXP_MASK;
        int nz = Long.numberOfLeadingZeros(dM);
        dM <<= nz;
        de -= nz;
        nz = Long.numberOfLeadingZeros(sM);
        sM <<= nz;
        sex -= nz;
        // 66 quotient bits by restoring division; the first has weight 2^0 of dM / sM
        long r = dM;
        long qHi = 0;
        long qLo = 0;
        for (int i = 0; i < 66; i++) {
            boolean bit;
            if (i == 0) {
                bit = Long.compareUnsigned(r, sM) >= 0;
            } else {
                boolean carry = r < 0;
                r <<= 1;
                bit = carry || Long.compareUnsigned(r, sM) >= 0;
            }
            if (bit)
                r -= sM;
            qHi = (qHi << 1) | (qLo >>> 63);
            qLo = (qLo << 1) | (bit ? 1 : 0);
        }
        long hi = (qHi << 62) | (qLo >>> 2);
        long lo = (qLo << 62) | (r != 0 ? 1 : 0);
        roundPack(c, neg, de - sex + BIAS, hi, lo, prec);
    }

    public static void sqrt(Ctx c, int se, long mant) {
        if (isNan(se, mant)) {
            nan1(c, se, mant);
            return;
        }
        boolean neg = isNegative(se);
        if (mant == 0 && (se & EXP_MASK) != EXP_SPECIAL) {
            setZero(c, neg);
            return;
        }
        if (neg) {
            operandError(c);
            return;
        }
        if ((se & EXP_MASK) == EXP_SPECIAL) {
            setInf(c, false);
            return;
        }
        int nz = Long.numberOfLeadingZeros(mant);
        mant <<= nz;
        int e = (se & EXP_MASK) - nz - BIAS;
        long rh;
        long rl;
        if ((e & 1) == 0) {
            rh = mant >>> 1;
            rl = mant << 63;
        } else {
            rh = mant;
            rl = 0;
        }
        long root = 0;
        long remHi = 0;
        long remLo = 0;
        for (int i = 63; i >= 0; i--) {
            int pos = 2 * i;
            long two = pos >= 64 ? (rh >>> (pos - 64)) & 3 : (rl >>> pos) & 3;
            remHi = (remHi << 2) | (remLo >>> 62);
            remLo = (remLo << 2) | two;
            long tHi = root >>> 62;
            long tLo = (root << 2) | 1;
            if (compareU128(remHi, remLo, tHi, tLo) >= 0) {
                long nlo = remLo - tLo;
                remHi = remHi - tHi - (Long.compareUnsigned(remLo, tLo) < 0 ? 1 : 0);
                remLo = nlo;
                root = (root << 1) | 1;
            } else {
                root <<= 1;
            }
        }
        // the exact root is never a half-way case: the round bit is rem > root
        boolean roundBit = compareU128(remHi, remLo, 0, root) > 0;
        boolean sticky = (remHi | remLo) != 0;
        roundPack(c, false, (e >> 1) + BIAS, root, (roundBit ? INT_BIT : 0) | (sticky ? 1 : 0), c.precision);
    }

    static int compareU128(long aHi, long aLo, long bHi, long bLo) {
        int h = Long.compareUnsigned(aHi, bHi);
        return h != 0 ? h : Long.compareUnsigned(aLo, bLo);
    }

    /** Result of {@link #compare} when either operand is a NaN. */
    public static final int UNORDERED = 2;

    /** Compares two values: -1, 0, 1 for destination less, equal, greater; {@link #UNORDERED} with a NaN. Flags SNAN. */
    public static int compare(Ctx c, int dSe, long dM, int sSe, long sM) {
        if (isNan(dSe, dM) || isNan(sSe, sM)) {
            if (isSignaling(dSe, dM) || isSignaling(sSe, sM))
                c.flags |= SNAN;
            return UNORDERED;
        }
        boolean dZero = isZero(dSe, dM);
        boolean sZero = isZero(sSe, sM);
        if (dZero && sZero)
            return 0;
        boolean dn = isNegative(dSe);
        boolean sn = isNegative(sSe);
        if (dn != sn)
            return dn ? -1 : 1;
        int mag;
        if (dZero) {
            mag = -1;
        } else if (sZero) {
            mag = 1;
        } else {
            boolean dInf = isInf(dSe, dM);
            boolean sInf = isInf(sSe, sM);
            if (dInf || sInf) {
                mag = dInf == sInf ? 0 : dInf ? 1 : -1;
            } else {
                int de = exponentOf(dSe, dM);
                int sex = exponentOf(sSe, sM);
                if (de != sex) {
                    mag = de > sex ? 1 : -1;
                } else {
                    long a = dM << Long.numberOfLeadingZeros(dM);
                    long b = sM << Long.numberOfLeadingZeros(sM);
                    mag = Long.signum(Long.compareUnsigned(a, b));
                }
            }
        }
        return dn ? -mag : mag;
    }

    // ---- integer rounding ----

    /**
     * Rounds the magnitude of a finite nonzero value with true exponent {@code e} (below 63) and
     * normalised mantissa to an integer in {@code mode}. Returns the magnitude (unsigned, at most
     * 2^63) and leaves whether it was inexact in {@code c.magInexact}.
     */
    static long roundMagnitude(Ctx c, boolean neg, int e, long mant, int mode) {
        long keep;
        boolean roundBit;
        boolean sticky;
        if (e < 0) {
            keep = 0;
            roundBit = e == -1;
            sticky = e == -1 ? (mant << 1) != 0 : true;
        } else {
            int fb = 63 - e;
            keep = mant >>> fb;
            roundBit = ((mant >>> (fb - 1)) & 1) != 0;
            sticky = (mant & ((1L << (fb - 1)) - 1)) != 0;
        }
        boolean inexact = roundBit || sticky;
        c.magInexact = inexact;
        boolean inc = switch (mode) {
            case RN -> roundBit && (sticky || (keep & 1) != 0);
            case RZ -> false;
            case RM -> inexact && neg;
            default -> inexact && !neg;
        };
        return inc ? keep + 1 : keep;
    }

    /** FINT (in {@code mode}): the value rounded to an integer, then to the context's precision. */
    public static void roundToInteger(Ctx c, int se, long mant, int mode) {
        if ((se & EXP_MASK) == EXP_SPECIAL || mant == 0) {
            round(c, se, mant);
            return;
        }
        boolean neg = isNegative(se);
        int e = exponentOf(se, mant);
        if (e >= 63) {
            round(c, se, mant);
            return;
        }
        long m = mant << Long.numberOfLeadingZeros(mant);
        long mag = roundMagnitude(c, neg, e, m, mode);
        if (c.magInexact)
            c.flags |= INEX2;
        if (mag == 0)
            setZero(c, neg);
        else
            roundPack(c, neg, UNPACKED, mag, 0, c.precision);
    }

    // ---- integer conversions ----

    /** Converts a signed integer, then rounds to the context's precision. */
    public static void fromLong(Ctx c, long v) {
        if (v == 0) {
            setZero(c, false);
            return;
        }
        boolean neg = v < 0;
        roundPack(c, neg, UNPACKED, neg ? -v : v, 0, c.precision);
    }

    /**
     * Converts to a signed integer of {@code bits} (8, 16, 32) in the context's rounding mode.
     * Out of range or infinity: OPERR and the largest integer of the value's sign. NaN: OPERR
     * (plus SNAN when signalling) and the upper 32 bits of the mantissa, as the support package
     * stores them. Inexact: INEX2. The caller keeps the low {@code bits} of the result.
     */
    public static int toInteger(Ctx c, int se, long mant, int bits) {
        boolean neg = isNegative(se);
        long max = (1L << (bits - 1)) - 1;
        if (isNan(se, mant)) {
            if ((mant & QUIET_BIT) == 0)
                c.flags |= SNAN;
            c.flags |= OPERR;
            return (int) (mant >>> 32);
        }
        if (isInf(se, mant)) {
            c.flags |= OPERR;
            return (int) (neg ? -max - 1 : max);
        }
        if (mant == 0)
            return 0;
        int e = exponentOf(se, mant);
        if (e >= 63) {
            c.flags |= OPERR;
            return (int) (neg ? -max - 1 : max);
        }
        long m = mant << Long.numberOfLeadingZeros(mant);
        long mag = roundMagnitude(c, neg, e, m, c.mode);
        long limit = neg ? max + 1 : max;
        if (Long.compareUnsigned(mag, limit) > 0) {
            c.flags |= OPERR;
            return (int) (neg ? -max - 1 : max);
        }
        if (c.magInexact)
            c.flags |= INEX2;
        return (int) (neg ? -mag : mag);
    }

    // ---- single and double ----

    /** Exact conversion of single bits into {@code c} (no flags; a signalling NaN stays signalling). */
    public static void fromSingle(Ctx c, int bits) {
        int sign = bits < 0 ? SIGN : 0;
        int exp = (bits >>> 23) & 0xFF;
        long frac = bits & 0x7F_FFFFL;
        if (exp == 0xFF) {
            c.set(sign | EXP_SPECIAL, frac == 0 ? 0 : INT_BIT | (frac << 40));
        } else if (exp == 0) {
            if (frac == 0) {
                c.set(sign, 0);
            } else {
                int s = Long.numberOfLeadingZeros(frac);
                c.set(sign | (UNPACKED - s - 149), frac << s);
            }
        } else {
            c.set(sign | (exp - 127 + BIAS), INT_BIT | (frac << 40));
        }
    }

    /** Exact conversion of double bits into {@code c}. */
    public static void fromDouble(Ctx c, long bits) {
        int sign = bits < 0 ? SIGN : 0;
        int exp = (int) (bits >>> 52) & 0x7FF;
        long frac = bits & 0xF_FFFF_FFFF_FFFFL;
        if (exp == 0x7FF) {
            c.set(sign | EXP_SPECIAL, frac == 0 ? 0 : INT_BIT | (frac << 11));
        } else if (exp == 0) {
            if (frac == 0) {
                c.set(sign, 0);
            } else {
                int s = Long.numberOfLeadingZeros(frac);
                c.set(sign | (UNPACKED - s - 1074), frac << s);
            }
        } else {
            c.set(sign | (exp - 1023 + BIAS), INT_BIT | (frac << 11));
        }
    }

    /** Rounds to single in the context's mode (single precision and range) and packs the bits. */
    public static int toSingle(Ctx c, int se, long mant) {
        int sign = isNegative(se) ? 0x8000_0000 : 0;
        if (isNan(se, mant)) {
            if ((mant & QUIET_BIT) == 0)
                c.flags |= SNAN;
            return sign | 0x7FC0_0000 | (int) ((mant >>> 40) & 0x7F_FFFF);
        }
        if (isInf(se, mant))
            return sign | 0x7F80_0000;
        if (mant == 0)
            return sign;
        roundPack(c, sign != 0, se & EXP_MASK, mant, 0, PREC_SINGLE);
        if (isInf(c.se, c.mant))
            return sign | 0x7F80_0000;
        if (c.mant == 0)
            return sign;
        int e = (c.se & EXP_MASK) - BIAS;
        if (e >= -126)
            return sign | ((e + 127) << 23) | (int) ((c.mant >>> 40) & 0x7F_FFFF);
        return sign | (int) (c.mant >>> (40 + (-126 - e)));
    }

    /** Rounds to double in the context's mode (double precision and range) and packs the bits. */
    public static long toDouble(Ctx c, int se, long mant) {
        long sign = isNegative(se) ? Long.MIN_VALUE : 0;
        if (isNan(se, mant)) {
            if ((mant & QUIET_BIT) == 0)
                c.flags |= SNAN;
            return sign | 0x7FF8_0000_0000_0000L | ((mant >>> 11) & 0xF_FFFF_FFFF_FFFFL);
        }
        if (isInf(se, mant))
            return sign | 0x7FF0_0000_0000_0000L;
        if (mant == 0)
            return sign;
        roundPack(c, sign != 0, se & EXP_MASK, mant, 0, PREC_DOUBLE);
        if (isInf(c.se, c.mant))
            return sign | 0x7FF0_0000_0000_0000L;
        if (c.mant == 0)
            return sign;
        int e = (c.se & EXP_MASK) - BIAS;
        if (e >= -1022)
            return sign | ((long) (e + 1023) << 52) | ((c.mant >>> 11) & 0xF_FFFF_FFFF_FFFFL);
        return sign | (c.mant >>> (11 + (-1022 - e)));
    }

    /** The value as a Java double, rounded to nearest (for the support-package functions). */
    public static double toJavaDouble(int se, long mant) {
        return Double.longBitsToDouble(toDouble(new Ctx(RN, PREC_EXTENDED), se, mant));
    }

    /** A Java double, rounded to the context's precision into {@code c}. */
    public static void fromJavaDouble(Ctx c, double d) {
        int f = c.flags;
        fromDouble(c, Double.doubleToRawLongBits(d));
        round(c, c.se, c.mant);
        c.flags = f | (c.flags & ~SNAN);
    }
}
