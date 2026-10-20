package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import com.nectariich.create_flippies.content.computer.cpu.m68k.FpuOpcodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Support-package instructions: the exact ones (FMOD, FREM, FSCALE, FGETEXP, FGETMAN, FSGLMUL,
 * FSGLDIV) against hand-checked bit patterns, the transcendentals against a BigDecimal reference
 * within 2 ulp in double precision, and the exact special cases.
 */
class FpSupportTest {

    static final long INT = Fx80.INT_BIT;
    static final MathContext MC = new MathContext(80);

    static Fx80.Ctx ctx() {
        return new Fx80.Ctx(Fx80.RN, Fx80.PREC_EXTENDED);
    }

    static Fx80.Ctx of(double d) {
        Fx80.Ctx c = ctx();
        Fx80.fromDouble(c, Double.doubleToRawLongBits(d));
        return c;
    }

    static double run(int opmode, double x) {
        Fx80.Ctx a = of(x);
        Fx80.Ctx c = ctx();
        FpSupport.transcendental(c, opmode, a.se, a.mant);
        return Fx80.toJavaDouble(c.se, c.mant);
    }

    // ---- exact instructions ----

    @Test
    void fmodAndFremWithALargeExponentGap() {
        // 2^100 mod 3 = 1; floor(2^100 / 3) = 0x5555...5, low 7 bits 0x55
        Fx80.Ctx c = ctx();
        int q = FpSupport.modRem(c, 0x3FFF + 100, INT, 0x4000, 0xC000_0000_0000_0000L, false);
        assertEquals(0x3FFF, c.se);
        assertEquals(INT, c.mant);
        assertEquals(0x55, q);
        q = FpSupport.modRem(c, 0x3FFF + 100, INT, 0x4000, 0xC000_0000_0000_0000L, true);
        assertEquals(0x3FFF, c.se);
        assertEquals(0x55, q);
        assertEquals(0, c.flags);
    }

    @Test
    void fremHalfwayAndSmallCases() {
        Fx80.Ctx c = ctx();
        // frem(1.5, 2): 0.75 rounds to 1, 1.5 - 2 = -0.5
        int q = FpSupport.modRem(c, 0x3FFF, 0xC000_0000_0000_0000L, 0x4000, INT, true);
        assertEquals(0xBFFE, c.se);
        assertEquals(INT, c.mant);
        assertEquals(1, q);
        // frem(1, 2): 0.5 ties to 0 (even), result 1
        q = FpSupport.modRem(c, 0x3FFF, INT, 0x4000, INT, true);
        assertEquals(0x3FFF, c.se);
        assertEquals(0, q);
        // frem(3, 2): 1.5 ties to 2, 3 - 4 = -1
        q = FpSupport.modRem(c, 0x4000, 0xC000_0000_0000_0000L, 0x4000, INT, true);
        assertEquals(0xBFFF, c.se);
        assertEquals(2, q);
        // fmod(5, -2) = 1, quotient -2
        q = FpSupport.modRem(c, 0x4001, 0xA000_0000_0000_0000L, 0xC000, INT, false);
        assertEquals(0x3FFF, c.se);
        assertEquals(0x82, q);
        // fmod(4, 2) = +0 with the dividend's sign; fmod(-4, 2) = -0
        FpSupport.modRem(c, 0x4001, INT, 0x4000, INT, false);
        assertEquals(0, c.se);
        assertEquals(0, c.mant);
        FpSupport.modRem(c, 0xC001, INT, 0x4000, INT, false);
        assertEquals(0x8000, c.se);
        // by zero, or of infinity: OPERR
        FpSupport.modRem(c, 0x3FFF, INT, 0, 0, false);
        assertEquals(Fx80.OPERR, c.flags);
    }

    @Test
    void scaleGetExpGetMan() {
        Fx80.Ctx c = ctx();
        FpSupport.scale(c, 0x3FFF, INT, 0x400C, 0xFA00_0000_0000_0000L); // 1 * 2^16000 (16000 = 0x3E80)
        assertEquals(0x3FFF + 16000, c.se);
        assertEquals(INT, c.mant);
        FpSupport.scale(c, 0x4000, 0xC000_0000_0000_0000L, 0xC000, INT); // 3 * 2^-2 = 0.75
        assertEquals(0x3FFE, c.se);
        assertEquals(0xC000_0000_0000_0000L, c.mant);
        FpSupport.scale(c, 0x3FFF, INT, 0x4000, 0xB99A_0000_0000_0000L); // 2.9 truncates to 2: 4
        assertEquals(0x4001, c.se);
        FpSupport.getExp(c, 0x4002, 0xA000_0000_0000_0000L); // exponent of 10 = 3
        assertEquals(0x4000, c.se);
        assertEquals(0xC000_0000_0000_0000L, c.mant);
        FpSupport.getExp(c, 0, 1); // smallest denormal: -16446 = (0xC00D, 0x807C...)
        assertEquals(0xC00D, c.se);
        assertEquals(0x807C_0000_0000_0000L, c.mant);
        FpSupport.getMan(c, 0xC002, 0xA000_0000_0000_0000L); // -10 -> -1.25
        assertEquals(0xBFFF, c.se);
        assertEquals(0xA000_0000_0000_0000L, c.mant);
        FpSupport.getMan(c, 0x8000, 0);
        assertEquals(0x8000, c.se);
        assertEquals(0, c.flags);
        FpSupport.getMan(c, 0x7FFF, 0);
        assertEquals(Fx80.OPERR, c.flags);
    }

    @Test
    void singlePrecisionMultiplyAndDivide() {
        // (1 + 2^-30)^2: the operands are truncated to 24 bits, so exactly 1
        Fx80.Ctx c = ctx();
        long m = INT | (1L << 33);
        FpSupport.sglMul(c, 0x3FFF, m, 0x3FFF, m);
        assertEquals(0x3FFF, c.se);
        assertEquals(INT, c.mant);
        assertEquals(0, c.flags);
        // 1 / 3 with a 24-bit mantissa and the extended exponent: 0xAAAAAB
        FpSupport.sglDiv(c, 0x3FFF, INT, 0x4000, 0xC000_0000_0000_0000L);
        assertEquals(0x3FFD, c.se);
        assertEquals(0xAAAA_AB00_0000_0000L, c.mant);
        assertEquals(Fx80.INEX2, c.flags);
        // the exponent range stays extended: 2^-200 * 2^-200 does not underflow
        c = ctx();
        FpSupport.sglMul(c, 0x3FFF - 200, INT, 0x3FFF - 200, INT);
        assertEquals(0x3FFF - 400, c.se);
        assertEquals(0, c.flags);
    }

    // ---- transcendentals against BigDecimal ----

    static BigDecimal sinRef(BigDecimal x, boolean cos) {
        BigDecimal x2 = x.multiply(x, MC);
        BigDecimal term = cos ? BigDecimal.ONE : x;
        BigDecimal sum = term;
        for (int j = 0; j < 200; j++) {
            long d = cos ? (2L * j + 1) * (2L * j + 2) : (2L * j + 2) * (2L * j + 3);
            term = term.multiply(x2, MC).divide(BigDecimal.valueOf(d), MC).negate();
            sum = sum.add(term, MC);
            if (term.abs().compareTo(new BigDecimal("1e-60")) < 0)
                break;
        }
        return sum;
    }

    static BigDecimal expRef(BigDecimal x) {
        // e^x = (e^(x / 1024))^1024
        BigDecimal y = x.divide(BigDecimal.valueOf(1024), MC);
        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        for (int n = 1; n < 100; n++) {
            term = term.multiply(y, MC).divide(BigDecimal.valueOf(n), MC);
            sum = sum.add(term, MC);
        }
        for (int i = 0; i < 10; i++)
            sum = sum.multiply(sum, MC);
        return sum;
    }

    static BigDecimal atanh(BigDecimal y) {
        BigDecimal y2 = y.multiply(y, MC);
        BigDecimal p = y;
        BigDecimal sum = BigDecimal.ZERO;
        for (int k = 0; k < 2000; k++) {
            sum = sum.add(p.divide(BigDecimal.valueOf(2L * k + 1), MC), MC);
            p = p.multiply(y2, MC);
            if (p.abs().compareTo(new BigDecimal("1e-60")) < 0)
                break;
        }
        return sum;
    }

    static BigDecimal lnRef(double x) {
        int e = Math.getExponent(x);
        BigDecimal m = new BigDecimal(x).divide(new BigDecimal(Math.scalb(1.0, e)), MC);
        BigDecimal ln2 = atanh(BigDecimal.ONE.divide(BigDecimal.valueOf(3), MC)).multiply(BigDecimal.valueOf(2));
        BigDecimal lm = atanh(m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC)).multiply(BigDecimal.valueOf(2));
        return lm.add(ln2.multiply(BigDecimal.valueOf(e)), MC);
    }

    static void within2Ulp(BigDecimal ref, double got, String what) {
        double r = ref.doubleValue();
        double tol = 2 * Math.ulp(r);
        assertTrue(Math.abs(got - r) <= tol, () -> what + ": got " + got + ", reference " + r);
    }

    @Test
    void sinCosExpLogAgainstBigDecimal() {
        Random rnd = new Random(2024);
        for (int i = 0; i < 300; i++) {
            double x = (rnd.nextDouble() * 2 - 1) * 10;
            within2Ulp(sinRef(new BigDecimal(x), false), run(FpuOpcodes.FSIN, x), "sin " + x);
            within2Ulp(sinRef(new BigDecimal(x), true), run(FpuOpcodes.FCOS, x), "cos " + x);
            double y = (rnd.nextDouble() * 2 - 1) * 30;
            within2Ulp(expRef(new BigDecimal(y)), run(FpuOpcodes.FETOX, y), "etox " + y);
            double z = Math.scalb(1 + rnd.nextDouble(), rnd.nextInt(40) - 20);
            within2Ulp(lnRef(z), run(FpuOpcodes.FLOGN, z), "logn " + z);
        }
    }

    @Test
    void otherFunctionsMatchStrictMath() {
        Random rnd = new Random(5);
        for (int i = 0; i < 200; i++) {
            double x = rnd.nextDouble() * 1.8 - 0.9;
            assertEquals(StrictMath.atan(x), run(FpuOpcodes.FATAN, x));
            assertEquals(StrictMath.asin(x), run(FpuOpcodes.FASIN, x));
            assertEquals(StrictMath.acos(x), run(FpuOpcodes.FACOS, x));
            assertEquals(StrictMath.tan(x), run(FpuOpcodes.FTAN, x));
            assertEquals(StrictMath.sinh(x), run(FpuOpcodes.FSINH, x));
            assertEquals(StrictMath.cosh(x), run(FpuOpcodes.FCOSH, x));
            assertEquals(StrictMath.tanh(x), run(FpuOpcodes.FTANH, x));
            assertEquals(StrictMath.expm1(x), run(FpuOpcodes.FETOXM1, x));
            within2Ulp(new BigDecimal(StrictMath.log1p(x)), run(FpuOpcodes.FLOGNP1, x), "lognp1 " + x);
            double p = Math.abs(x) + 0.01;
            assertEquals(StrictMath.log10(p), run(FpuOpcodes.FLOG10, p));
            within2Ulp(new BigDecimal(StrictMath.log(p) / StrictMath.log(2)), run(FpuOpcodes.FLOG2, p), "log2");
            within2Ulp(new BigDecimal(StrictMath.pow(2, x * 5)), run(FpuOpcodes.FTWOTOX, x * 5), "twotox");
            within2Ulp(new BigDecimal(StrictMath.pow(10, x * 5)), run(FpuOpcodes.FTENTOX, x * 5), "tentox");
            within2Ulp(atanh(new BigDecimal(x)), run(FpuOpcodes.FATANH, x), "atanh " + x);
        }
    }

    // ---- exact special cases ----

    static Fx80.Ctx apply(int opmode, int se, long mant) {
        Fx80.Ctx c = ctx();
        FpSupport.transcendental(c, opmode, se, mant);
        return c;
    }

    @Test
    void exactSpecialCases() {
        Fx80.Ctx c = apply(FpuOpcodes.FSIN, 0x8000, 0); // sin(-0) = -0
        assertEquals(0x8000, c.se);
        assertEquals(0, c.mant);
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FLOGN, 0x3FFF, INT); // ln 1 = +0, exact
        assertEquals(0, c.se);
        assertEquals(0, c.mant);
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FETOX, 0xFFFF, 0); // e^-inf = +0
        assertEquals(0, c.se);
        assertEquals(0, c.mant);
        c = apply(FpuOpcodes.FLOGN, 0xBFFF, INT); // ln -1: OPERR
        assertEquals(Fx80.OPERR, c.flags);
        c = apply(FpuOpcodes.FASIN, 0x4000, INT); // asin 2: OPERR
        assertEquals(Fx80.OPERR, c.flags);
        c = apply(FpuOpcodes.FASIN, 0x3FFF, INT | 1); // asin(1 + 2^-63): OPERR though it rounds to 1.0 in double
        assertEquals(Fx80.OPERR, c.flags);
        c = apply(FpuOpcodes.FLOG10, 0x8000, 0); // log10 -0: DZ, -inf
        assertEquals(0xFFFF, c.se);
        assertEquals(Fx80.DZ, c.flags);
        c = apply(FpuOpcodes.FATANH, 0xBFFF, INT); // atanh -1: DZ, -inf
        assertEquals(0xFFFF, c.se);
        assertEquals(Fx80.DZ, c.flags);
        c = apply(FpuOpcodes.FCOS, 0x7FFF, 0); // cos inf: OPERR
        assertEquals(Fx80.OPERR, c.flags);
        c = apply(FpuOpcodes.FTWOTOX, 0x400C, 0xFA00_0000_0000_0000L); // 2^16000, exact
        assertEquals(0x3FFF + 16000, c.se);
        assertEquals(INT, c.mant);
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FTENTOX, 0x4000, 0xC000_0000_0000_0000L); // 10^3 = 1000, exact
        assertEquals(0x4008, c.se);
        assertEquals(0xFA00_0000_0000_0000L, c.mant);
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FLOG2, 0x3FFF + 300, INT); // log2 2^300 = 300, exact
        assertEquals(300.0, Fx80.toJavaDouble(c.se, c.mant));
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FLOG10, 0x4008, 0xFA00_0000_0000_0000L); // log10 1000 = 3, exact
        assertEquals(3.0, Fx80.toJavaDouble(c.se, c.mant));
        assertEquals(0, c.flags);
        c = apply(FpuOpcodes.FSIN, 0x3FFF, INT); // sin 1: inexact
        assertEquals(Fx80.INEX2, c.flags);
        c = apply(FpuOpcodes.FSIN, 0x7FFF, 0x8000_0000_0000_0001L); // a signalling NaN: quiet, SNAN
        assertEquals(0xC000_0000_0000_0001L, c.mant);
        assertEquals(Fx80.SNAN, c.flags);
    }

    @Test
    void extendedRangeBeyondDouble() {
        // e^11000 (about 2^15869.7) overflows a double but not the extended format
        Fx80.Ctx a = of(11000);
        Fx80.Ctx c = ctx();
        FpSupport.transcendental(c, FpuOpcodes.FETOX, a.se, a.mant);
        assertEquals(0x3FFF + 15869, c.se);
        Fx80.Ctx l = ctx();
        FpSupport.transcendental(l, FpuOpcodes.FLOGN, c.se, c.mant);
        assertEquals(11000.0, Fx80.toJavaDouble(l.se, l.mant), 1e-8);
        // sin of a tiny argument below the double range is the argument
        c = apply(FpuOpcodes.FSIN, 0x0100, 0xC000_0000_0000_0000L);
        assertEquals(0x0100, c.se);
        assertEquals(0xC000_0000_0000_0000L, c.mant);
        // log of a value beyond the double range: ln(2^16000) = 16000 ln 2
        c = apply(FpuOpcodes.FLOGN, 0x3FFF + 16000, INT);
        assertEquals(16000 * Math.log(2), Fx80.toJavaDouble(c.se, c.mant), 1e-9);
        // e^-20000 underflows to zero with UNFL and INEX2
        a = of(-20000);
        c = ctx();
        FpSupport.transcendental(c, FpuOpcodes.FETOX, a.se, a.mant);
        assertEquals(0, c.mant);
        assertEquals(Fx80.UNFL | Fx80.INEX2, c.flags);
        // cosh 12000 overflows: OVFL, infinity
        a = of(12000);
        c = ctx();
        FpSupport.transcendental(c, FpuOpcodes.FCOSH, a.se, a.mant);
        assertEquals(0x7FFF, c.se);
        assertTrue((c.flags & Fx80.OVFL) != 0);
        // sin of 2^2000 reduced by the extended 2 pi: a finite value in [-1, 1]
        c = apply(FpuOpcodes.FSIN, 0x3FFF + 2000, INT);
        double s = Fx80.toJavaDouble(c.se, c.mant);
        assertTrue(s >= -1 && s <= 1);
    }
}
