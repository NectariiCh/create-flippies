package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fx80 arithmetic against hand-checked bit patterns (each expected value is worked out in the
 * comment next to it), plus randomised cross-checks: against Java's IEEE double and float
 * arithmetic for double and single rounding precision, and against an exact BigInteger reference
 * for extended precision in all four rounding modes.
 */
class Fx80Test {

    static final long INT = Fx80.INT_BIT;
    // 1.0, 2.0, 10.0, 0.5
    static final int ONE = 0x3FFF;
    static final int TWO = 0x4000;
    static final int TEN_SE = 0x4002;
    static final long TEN_M = 0xA000_0000_0000_0000L;

    static Fx80.Ctx ctx(int mode, int prec) {
        return new Fx80.Ctx(mode, prec);
    }

    static void assertX(Fx80.Ctx c, int se, long mant) {
        assertEquals(se, c.se, () -> String.format("se %04X, expected %04X (mant %016X)", c.se, se, c.mant));
        assertEquals(mant, c.mant, () -> String.format("mant %016X, expected %016X", c.mant, mant));
    }

    static void assertFlags(Fx80.Ctx c, int flags) {
        assertEquals(flags, c.flags, () -> String.format("flags %04X, expected %04X", c.flags, flags));
    }

    // ---- add and sub ----

    @Test
    void onePlusOneIsTwo() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, ONE, INT);
        assertX(c, TWO, INT);
        assertFlags(c, 0);
    }

    @Test
    void exactCancellationSignDependsOnMode() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.sub(c, ONE, INT, ONE, INT);
        assertX(c, 0x0000, 0);
        c = ctx(Fx80.RM, Fx80.PREC_EXTENDED);
        Fx80.sub(c, ONE, INT, ONE, INT);
        assertX(c, 0x8000, 0);
        // +0 + -0 = +0, except -0 in RM
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.add(c, 0, 0, 0x8000, 0);
        assertX(c, 0, 0);
        c = ctx(Fx80.RM, Fx80.PREC_EXTENDED);
        Fx80.add(c, 0, 0, 0x8000, 0);
        assertX(c, 0x8000, 0);
    }

    @Test
    void halfwayCasesRoundToEven() {
        // 2^-64 = (0x3FFF - 64 = 0x3FBF, INT): half an ulp of 1.0, a tie between 1 and 1 + 2^-63
        int halfUlp = 0x3FBF;
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, halfUlp, INT);
        assertX(c, ONE, INT);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RP, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, halfUlp, INT);
        assertX(c, ONE, INT | 1);
        c = ctx(Fx80.RZ, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, halfUlp, INT);
        assertX(c, ONE, INT);
        c = ctx(Fx80.RM, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, halfUlp, INT);
        assertX(c, ONE, INT);
        // 1 + 3 * 2^-64 = 1 + 2^-63 + 2^-64: tie between ...01 and ...10, even is ...10
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.add(c, ONE, INT, 0x3FC0, 0xC000_0000_0000_0000L);
        assertX(c, ONE, INT | 2);
        // negative: -1 - 2^-64 in RM goes away from zero, in RP towards
        c = ctx(Fx80.RM, Fx80.PREC_EXTENDED);
        Fx80.sub(c, 0x8000 | ONE, INT, halfUlp, INT);
        assertX(c, 0x8000 | ONE, INT | 1);
        c = ctx(Fx80.RP, Fx80.PREC_EXTENDED);
        Fx80.sub(c, 0x8000 | ONE, INT, halfUlp, INT);
        assertX(c, 0x8000 | ONE, INT);
    }

    @Test
    void halfwayInSinglePrecision() {
        // 1 + 2^-24 is half a single ulp: RN keeps 1.0, RP gives 1 + 2^-23 (mantissa bit 40)
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.add(c, ONE, INT, 0x3FFF - 24, INT);
        assertX(c, ONE, INT);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RP, Fx80.PREC_SINGLE);
        Fx80.add(c, ONE, INT, 0x3FFF - 24, INT);
        assertX(c, ONE, INT | (1L << 40));
        // 1 + 3 * 2^-24: tie, even is 1 + 2^-22 (bit 41)
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.add(c, ONE, INT, 0x3FFF - 23, 0xC000_0000_0000_0000L);
        assertX(c, ONE, INT | (1L << 41));
    }

    @Test
    void farApartOperandsKeepSticky() {
        // 1 - 2^-200 rounds to 1 in RN and RP, to 1 - 2^-64 (0x3FFE, all ones) in RZ and RM
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.sub(c, ONE, INT, 0x3FFF - 200, INT);
        assertX(c, ONE, INT);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RZ, Fx80.PREC_EXTENDED);
        Fx80.sub(c, ONE, INT, 0x3FFF - 200, INT);
        assertX(c, 0x3FFE, -1L);
    }

    // ---- 0.1 in each precision ----

    @Test
    void oneTenthInEachPrecision() {
        // 1/10 = 0x3FFB CCCC_CCCC_CCCC_CCCC.CCC...: RN rounds up to ...CCCD, RZ truncates
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.div(c, ONE, INT, TEN_SE, TEN_M);
        assertX(c, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RZ, Fx80.PREC_EXTENDED);
        Fx80.div(c, ONE, INT, TEN_SE, TEN_M);
        assertX(c, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCCL);
        // single 0x3DCCCCCD: 24-bit mantissa 0xCCCCCD
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.div(c, ONE, INT, TEN_SE, TEN_M);
        assertX(c, 0x3FFB, 0xCCCC_CD00_0000_0000L);
        assertEquals(0x3DCC_CCCD, Fx80.toSingle(ctx(Fx80.RN, 0), c.se, c.mant));
        // double 0x3FB999999999999A: 53-bit mantissa 0x1999999999999A, shifted left 11
        c = ctx(Fx80.RN, Fx80.PREC_DOUBLE);
        Fx80.div(c, ONE, INT, TEN_SE, TEN_M);
        assertX(c, 0x3FFB, 0xCCCC_CCCC_CCCC_D000L);
        assertEquals(0x3FB9_9999_9999_999AL, Fx80.toDouble(ctx(Fx80.RN, 0), c.se, c.mant));
        // the extended value converted to double and single on output
        Fx80.Ctx o = ctx(Fx80.RN, 0);
        assertEquals(0x3FB9_9999_9999_999AL, Fx80.toDouble(o, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL));
        assertEquals(Fx80.INEX2, o.flags);
        assertEquals(0x3DCC_CCCD, Fx80.toSingle(o, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL));
    }

    // ---- sqrt ----

    @Test
    void squareRootOfTwo() {
        // sqrt(2) * 2^63 = 0xB504_F333_F9DE_6484.597D...: round bit 0
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.sqrt(c, TWO, INT);
        assertX(c, ONE, 0xB504_F333_F9DE_6484L);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RP, Fx80.PREC_EXTENDED);
        Fx80.sqrt(c, TWO, INT);
        assertX(c, ONE, 0xB504_F333_F9DE_6485L);
        // single 0x3FB504F3, double 0x3FF6A09E667F3BCD
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.sqrt(c, TWO, INT);
        assertX(c, ONE, 0xB504_F300_0000_0000L);
        c = ctx(Fx80.RN, Fx80.PREC_DOUBLE);
        Fx80.sqrt(c, TWO, INT);
        assertX(c, ONE, 0xB504_F333_F9DE_6800L);
    }

    @Test
    void squareRootSpecials() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.sqrt(c, 0x4001, INT); // sqrt(4) = 2, exact
        assertX(c, TWO, INT);
        assertFlags(c, 0);
        Fx80.sqrt(c, 0x8000, 0); // sqrt(-0) = -0
        assertX(c, 0x8000, 0);
        Fx80.sqrt(c, 0x8000 | ONE, INT); // sqrt(-1): OPERR, default NaN
        assertX(c, Fx80.DEFAULT_NAN_SE, Fx80.DEFAULT_NAN_MANT);
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        Fx80.sqrt(c, 0x7FFF, 0);
        assertX(c, 0x7FFF, 0);
        // sqrt(2^-16446), the smallest denormal: 2^-8223 = (0x3FFF - 8223 = 0x1FE0, INT)
        Fx80.sqrt(c, 0, 1);
        assertX(c, 0x1FE0, INT);
        assertFlags(c, 0);
    }

    // ---- mul, div, overflow ----

    @Test
    void multiplyAndDivide() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.mul(c, 0x4000, 0xC000_0000_0000_0000L, 0x3FFE, INT); // 3 * 0.5 = 1.5
        assertX(c, ONE, 0xC000_0000_0000_0000L);
        Fx80.div(c, 0x4000, 0xC000_0000_0000_0000L, 0x3FFE, INT); // 3 / 0.5 = 6
        assertX(c, 0x4001, 0xC000_0000_0000_0000L);
        assertFlags(c, 0);
        // 1 / 3 = 0x3FFD AAAA_AAAA_AAAA_AAAA.AAA...: next bit 1, rest nonzero, so RN rounds up
        Fx80.div(c, ONE, INT, 0x4000, 0xC000_0000_0000_0000L);
        assertX(c, 0x3FFD, 0xAAAA_AAAA_AAAA_AAABL);
        // max * max: (2 - 2^-63)^2 * 2^32766 overflows
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.mul(c, 0x7FFE, -1L, 0x7FFE, -1L);
        assertX(c, 0x7FFF, 0);
        assertFlags(c, Fx80.OVFL | Fx80.INEX2);
    }

    @Test
    void overflowResultDependsOnModeAndSign() {
        // max extended 0x7FFE FFFF_FFFF_FFFF_FFFF times 2
        int[] modes = {Fx80.RN, Fx80.RZ, Fx80.RM, Fx80.RP};
        int[] posSe = {0x7FFF, 0x7FFE, 0x7FFE, 0x7FFF};
        long[] posM = {0, -1L, -1L, 0};
        int[] negSe = {0xFFFF, 0xFFFE, 0xFFFF, 0xFFFE};
        long[] negM = {0, -1L, 0, -1L};
        for (int i = 0; i < 4; i++) {
            Fx80.Ctx c = ctx(modes[i], Fx80.PREC_EXTENDED);
            Fx80.mul(c, 0x7FFE, -1L, TWO, INT);
            assertX(c, posSe[i], posM[i]);
            assertFlags(c, Fx80.OVFL | Fx80.INEX2);
            c = ctx(modes[i], Fx80.PREC_EXTENDED);
            Fx80.mul(c, 0xFFFE, -1L, TWO, INT);
            assertX(c, negSe[i], negM[i]);
        }
    }

    @Test
    void singleAndDoublePrecisionReduceTheExponentRange() {
        // 2^127 * 2 = 2^128 overflows single: RN infinity, RZ the largest single 0x407E FFFFFF00...
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.mul(c, 0x407E, INT, TWO, INT);
        assertX(c, 0x7FFF, 0);
        assertFlags(c, Fx80.OVFL | Fx80.INEX2);
        c = ctx(Fx80.RZ, Fx80.PREC_SINGLE);
        Fx80.mul(c, 0x407E, INT, TWO, INT);
        assertX(c, 0x407E, 0xFFFF_FF00_0000_0000L);
        // 2^1023 * 2 in double, RZ: largest double 0x43FE FFFF_FFFF_FFFF_F800
        c = ctx(Fx80.RZ, Fx80.PREC_DOUBLE);
        Fx80.mul(c, 0x43FE, INT, TWO, INT);
        assertX(c, 0x43FE, 0xFFFF_FFFF_FFFF_F800L);
        // the same product in extended precision is exact
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.mul(c, 0x43FE, INT, TWO, INT);
        assertX(c, 0x43FF, INT);
        assertFlags(c, 0);
    }

    // ---- underflow and denormals ----

    @Test
    void underflowToDenormal() {
        // smallest normal 2^-16383 = (0x0000, INT); halved: denormal (0x0000, 0x4000...), exact
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.div(c, 0, INT, TWO, INT);
        assertX(c, 0, 0x4000_0000_0000_0000L);
        assertFlags(c, 0);
        // with the UNFL trap enabled an exact tiny result still raises UNFL
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        c.enables = Fx80.UNFL;
        Fx80.div(c, 0, INT, TWO, INT);
        assertFlags(c, Fx80.UNFL);
        // smallest denormal 2^-16446 = (0, 1), halved: a tie between 0 and it, even is 0
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.div(c, 0, 1, TWO, INT);
        assertX(c, 0, 0);
        assertFlags(c, Fx80.UNFL | Fx80.INEX2);
        c = ctx(Fx80.RP, Fx80.PREC_EXTENDED);
        Fx80.div(c, 0, 1, TWO, INT);
        assertX(c, 0, 1);
        // 0.75 * 2^-16383 is a denormal (0, 0x6000...)
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.mul(c, 0, INT, 0x3FFE, 0xC000_0000_0000_0000L);
        assertX(c, 0, 0x6000_0000_0000_0000L);
        // a denormal operand times 1 stays the same denormal
        Fx80.mul(c, 0, 0x0000_0000_0001_2345L, ONE, INT);
        assertX(c, 0, 0x0000_0000_0001_2345L);
        // denormal + denormal carrying into the smallest normal
        Fx80.add(c, 0, 0x4000_0000_0000_0000L, 0, 0x4000_0000_0000_0000L);
        assertX(c, 0, INT);
    }

    @Test
    void singleDenormalRounding() {
        // 2^-126 * 0.5 = 2^-127, a single denormal held exactly: (0x3F80, INT)
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.mul(c, 0x3F81, INT, 0x3FFE, INT);
        assertX(c, 0x3F80, INT);
        assertFlags(c, 0);
        assertEquals(0x0040_0000, Fx80.toSingle(ctx(Fx80.RN, 0), c.se, c.mant));
        // (1 + 2^-23) * 2^-127 = 2^-127 + 2^-150: in single the denormal ulp is 2^-149, a tie, even: 2^-127
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.mul(c, ONE, 0x8000_0100_0000_0000L, 0x3F80, INT);
        assertX(c, 0x3F80, INT);
        assertFlags(c, Fx80.UNFL | Fx80.INEX2);
        // 2^-149 * 0.5: tie to 0 in RN, 2^-149 (0x3F6A, INT) in RP
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.mul(c, 0x3F6A, INT, 0x3FFE, INT);
        assertX(c, 0, 0);
        c = ctx(Fx80.RP, Fx80.PREC_SINGLE);
        Fx80.mul(c, 0x3F6A, INT, 0x3FFE, INT);
        assertX(c, 0x3F6A, INT);
    }

    // ---- NaN rules ----

    @Test
    void nanPropagationPrefersTheDestination() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.add(c, 0x7FFF, 0xC000_0000_0000_0001L, 0xFFFF, 0xC000_0000_0000_0002L);
        assertX(c, 0x7FFF, 0xC000_0000_0000_0001L);
        assertFlags(c, 0);
        // a source NaN with an ordinary destination
        Fx80.mul(c, ONE, INT, 0xFFFF, 0xC000_0000_0000_0002L);
        assertX(c, 0xFFFF, 0xC000_0000_0000_0002L);
        // a signalling NaN is made quiet and raises SNAN
        Fx80.add(c, 0x7FFF, 0x8000_0000_0000_0001L, ONE, INT);
        assertX(c, 0x7FFF, 0xC000_0000_0000_0001L);
        assertFlags(c, Fx80.SNAN);
        // quiet destination, signalling source: destination wins, SNAN still raised
        c.flags = 0;
        Fx80.sub(c, 0x7FFF, 0xC000_0000_0000_0005L, 0x7FFF, 0x8000_0000_0000_0001L);
        assertX(c, 0x7FFF, 0xC000_0000_0000_0005L);
        assertFlags(c, Fx80.SNAN);
    }

    @Test
    void invalidOperationsGiveTheDefaultNan() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.sub(c, 0x7FFF, 0, 0x7FFF, 0); // inf - inf
        assertX(c, 0x7FFF, -1L);
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        Fx80.mul(c, 0, 0, 0xFFFF, 0); // 0 * -inf
        assertX(c, 0x7FFF, -1L);
        c.flags = 0;
        Fx80.div(c, 0, 0, 0x8000, 0); // 0 / -0
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        Fx80.div(c, 0x8000 | ONE, INT, 0, 0); // -1 / +0: DZ, -inf
        assertX(c, 0xFFFF, 0);
        assertFlags(c, Fx80.DZ);
        c.flags = 0;
        Fx80.div(c, ONE, INT, 0x7FFF, 0); // 1 / inf = +0
        assertX(c, 0, 0);
        assertFlags(c, 0);
    }

    // ---- compare, classification ----

    @Test
    void compareAndConditionCodes() {
        Fx80.Ctx c = ctx(Fx80.RN, 0);
        assertEquals(-1, Fx80.compare(c, ONE, INT, TWO, INT));
        assertEquals(1, Fx80.compare(c, ONE, INT, 0x8000 | TWO, INT));
        assertEquals(0, Fx80.compare(c, 0, 0, 0x8000, 0));
        assertEquals(1, Fx80.compare(c, 0x7FFF, 0, 0x7FFE, -1L));
        assertEquals(-1, Fx80.compare(c, 0xFFFE, -1L, 0xFFFE, -2L));
        assertEquals(0, Fx80.compare(c, 0, INT, 1, 0x4000_0000_0000_0000L)); // unnormal equals normal
        assertEquals(Fx80.UNORDERED, Fx80.compare(c, 0x7FFF, 0xC000_0000_0000_0000L, ONE, INT));
        assertEquals(0, c.flags);
        assertEquals(4, Fx80.conditionCodes(0, 0)); // Z
        assertEquals(12, Fx80.conditionCodes(0x8000, 0)); // N Z
        assertEquals(2, Fx80.conditionCodes(0x7FFF, 0)); // I
        assertEquals(10, Fx80.conditionCodes(0xFFFF, 0)); // N I
        assertEquals(1, Fx80.conditionCodes(0x7FFF, 1)); // NAN (integer bit ignored)
        assertEquals(8, Fx80.conditionCodes(0xBFFF, INT)); // N
        assertTrue(Fx80.isDenormal(0, 1));
        assertTrue(Fx80.isUnnormal(0x3FFF, 0x4000_0000_0000_0000L));
        assertTrue(Fx80.isZero(0x1234, 0)); // unnormal zero
        assertTrue(Fx80.isSignaling(0x7FFF, 0x8000_0000_0000_0001L));
    }

    // ---- integer conversions ----

    @Test
    void toIntegerRoundsAndSaturates() {
        Fx80.Ctx c = ctx(Fx80.RN, 0);
        assertEquals(2, Fx80.toInteger(c, 0x4000, 0xA000_0000_0000_0000L, 32)); // 2.5 -> 2 (even)
        assertFlags(c, Fx80.INEX2);
        assertEquals(4, Fx80.toInteger(c, 0x4000, 0xE000_0000_0000_0000L, 32)); // 3.5 -> 4
        assertEquals(-2, Fx80.toInteger(c, 0xC000, 0xA000_0000_0000_0000L, 32)); // -2.5 -> -2
        c = ctx(Fx80.RM, 0);
        assertEquals(-3, Fx80.toInteger(c, 0xC000, 0xA000_0000_0000_0000L, 32)); // -2.5 RM -> -3
        c = ctx(Fx80.RN, 0);
        // 2^31 does not fit a long: OPERR, the largest positive long, no INEX2
        assertEquals(0x7FFF_FFFF, Fx80.toInteger(c, 0x401E, INT, 32));
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        assertEquals(0x8000_0000, Fx80.toInteger(c, 0xC01E, INT, 32)); // -2^31 fits
        assertFlags(c, 0);
        // 127.5 -> 128 does not fit a byte: OPERR, 0x7F
        assertEquals(0x7F, Fx80.toInteger(c, 0x4005, 0xFF00_0000_0000_0000L, 8));
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        // -128.25 -> -128 fits, inexact
        assertEquals(-128, Fx80.toInteger(c, 0xC006, 0x8040_0000_0000_0000L, 8));
        assertFlags(c, Fx80.INEX2);
        c.flags = 0;
        assertEquals(-32768, Fx80.toInteger(c, 0xFFFF, 0, 16)); // -inf
        assertFlags(c, Fx80.OPERR);
        c.flags = 0;
        assertEquals(0x3FFF_1234, Fx80.toInteger(c, 0x7FFF, 0x3FFF_1234_0000_0000L, 32)); // signalling NaN: upper mantissa bits
        assertFlags(c, Fx80.OPERR | Fx80.SNAN);
    }

    @Test
    void fromIntegerRoundsToPrecision() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.fromLong(c, 0x7FFF_FFFF); // 2^31 - 1 = (0x401D, 0xFFFF_FFFE_0000_0000)
        assertX(c, 0x401D, 0xFFFF_FFFE_0000_0000L);
        assertFlags(c, 0);
        c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.fromLong(c, 0x7FFF_FFFF); // rounds up to 2^31 in single
        assertX(c, 0x401E, INT);
        assertFlags(c, Fx80.INEX2);
        c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.fromLong(c, -1);
        assertX(c, 0xBFFF, INT);
        Fx80.fromLong(c, Long.MIN_VALUE);
        assertX(c, 0xC03E, INT);
    }

    @Test
    void singleAndDoubleConversions() {
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.fromSingle(c, 0x3DCC_CCCD); // 0.1f
        assertX(c, 0x3FFB, 0xCCCC_CD00_0000_0000L);
        Fx80.fromSingle(c, 0x0000_0001); // 2^-149
        assertX(c, 0x3F6A, INT);
        Fx80.fromSingle(c, 0x8000_0000);
        assertX(c, 0x8000, 0);
        Fx80.fromSingle(c, 0xFF80_0000);
        assertX(c, 0xFFFF, 0);
        Fx80.fromSingle(c, 0x7F80_0001); // signalling NaN stays signalling
        assertX(c, 0x7FFF, 0x8000_0100_0000_0000L);
        Fx80.fromDouble(c, 0x0000_0000_0000_0001L); // 2^-1074 = (0x3FFF - 1074 = 0x3BCD, INT)
        assertX(c, 0x3BCD, INT);
        Fx80.fromDouble(c, 0x3FF0_0000_0000_0000L);
        assertX(c, ONE, INT);
        Fx80.Ctx o = ctx(Fx80.RN, 0);
        assertEquals(1, Fx80.toSingle(o, 0x3F6A, INT));
        assertEquals(0, Fx80.toSingle(o, 0x3F69, INT)); // 2^-150: tie to 0
        assertFlags(o, Fx80.UNFL | Fx80.INEX2);
        o.flags = 0;
        assertEquals(0x7F80_0000, Fx80.toSingle(o, 0x4100, INT)); // 2^257 overflows single
        assertFlags(o, Fx80.OVFL | Fx80.INEX2);
        o.flags = 0;
        assertEquals(0x7FC0_0001, Fx80.toSingle(o, 0x7FFF, 0x8000_0100_0000_0000L));
        assertFlags(o, Fx80.SNAN);
        o.flags = 0;
        assertEquals(0x0000_0000_0000_0001L, Fx80.toDouble(o, 0x3BCD, INT));
        assertEquals(0x7FEF_FFFF_FFFF_FFFFL, Fx80.toDouble(ctx(Fx80.RZ, 0), 0x7FFE, -1L));
    }

    @Test
    void roundToIntegerInEachMode() {
        long twoAndHalf = 0xA000_0000_0000_0000L;
        int[] modes = {Fx80.RN, Fx80.RZ, Fx80.RM, Fx80.RP};
        long[] pos = {2, 2, 2, 3};
        long[] neg = {-2, -2, -3, -2};
        for (int i = 0; i < 4; i++) {
            Fx80.Ctx c = ctx(modes[i], Fx80.PREC_EXTENDED);
            Fx80.roundToInteger(c, 0x4000, twoAndHalf, modes[i]);
            assertEquals(pos[i], Fx80.toInteger(ctx(Fx80.RN, 0), c.se, c.mant, 32));
            assertEquals(Fx80.INEX2, c.flags);
            Fx80.roundToInteger(c, 0xC000, twoAndHalf, modes[i]);
            assertEquals(neg[i], Fx80.toInteger(ctx(Fx80.RN, 0), c.se, c.mant, 32));
        }
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_EXTENDED);
        Fx80.roundToInteger(c, 0xBFFD, 0x9999_9999_9999_999AL, Fx80.RZ); // -0.3 -> -0
        assertX(c, 0x8000, 0);
        Fx80.roundToInteger(c, 0x3FFE, INT, Fx80.RN); // 0.5 -> 0
        assertX(c, 0, 0);
        Fx80.roundToInteger(c, ONE, 0xC000_0000_0000_0000L, Fx80.RN); // 1.5 -> 2
        assertX(c, TWO, INT);
        c.flags = 0;
        Fx80.roundToInteger(c, 0x403F, 0xC000_0000_0000_0001L, Fx80.RN); // already an integer
        assertX(c, 0x403F, 0xC000_0000_0000_0001L);
        assertFlags(c, 0);
    }

    // ---- randomised cross-checks ----

    static double randomDouble(Random r) {
        double m = r.nextDouble() * 2 - 1;
        int e = r.nextInt(80) - 40;
        return switch (r.nextInt(8)) {
            case 0 -> Double.longBitsToDouble(r.nextLong() & 0x000F_FFFF_FFFF_FFFFL); // subnormal
            case 1 -> Double.longBitsToDouble(r.nextLong()); // anything, NaN filtered by the caller
            default -> Math.scalb(m, e);
        };
    }

    @Test
    void doublePrecisionMatchesIeeeDouble() {
        Random r = new Random(42);
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_DOUBLE);
        Fx80.Ctx a = new Fx80.Ctx();
        Fx80.Ctx b = new Fx80.Ctx();
        for (int i = 0; i < 20000; i++) {
            double x = randomDouble(r);
            double y = randomDouble(r);
            if (Double.isNaN(x) || Double.isNaN(y))
                continue;
            Fx80.fromDouble(a, Double.doubleToRawLongBits(x));
            Fx80.fromDouble(b, Double.doubleToRawLongBits(y));
            int op = i % 5;
            double expected;
            switch (op) {
                case 0 -> {
                    Fx80.add(c, a.se, a.mant, b.se, b.mant);
                    expected = x + y;
                }
                case 1 -> {
                    Fx80.sub(c, a.se, a.mant, b.se, b.mant);
                    expected = x - y;
                }
                case 2 -> {
                    Fx80.mul(c, a.se, a.mant, b.se, b.mant);
                    expected = x * y;
                }
                case 3 -> {
                    Fx80.div(c, a.se, a.mant, b.se, b.mant);
                    expected = x / y;
                }
                default -> {
                    Fx80.sqrt(c, a.se & 0x7FFF, a.mant);
                    expected = Math.sqrt(Math.abs(x));
                }
            }
            long got = Fx80.toDouble(ctx(Fx80.RN, 0), c.se, c.mant);
            long want = Double.doubleToRawLongBits(expected);
            if (Double.isNaN(expected))
                assertTrue(Double.isNaN(Double.longBitsToDouble(got)), "op " + op + " " + x + " " + y);
            else
                assertEquals(want, got, () -> "op " + op + " " + x + " " + y);
        }
    }

    @Test
    void singlePrecisionMatchesIeeeFloat() {
        Random r = new Random(7);
        Fx80.Ctx c = ctx(Fx80.RN, Fx80.PREC_SINGLE);
        Fx80.Ctx a = new Fx80.Ctx();
        Fx80.Ctx b = new Fx80.Ctx();
        for (int i = 0; i < 20000; i++) {
            float x = Float.intBitsToFloat(r.nextInt());
            float y = r.nextInt(4) == 0 ? Float.intBitsToFloat(r.nextInt(0x0080_0000)) : (float) Math.scalb(r.nextDouble(), r.nextInt(60) - 30);
            if (Float.isNaN(x) || Float.isNaN(y))
                continue;
            Fx80.fromSingle(a, Float.floatToRawIntBits(x));
            Fx80.fromSingle(b, Float.floatToRawIntBits(y));
            int op = i % 4;
            float expected;
            switch (op) {
                case 0 -> {
                    Fx80.add(c, a.se, a.mant, b.se, b.mant);
                    expected = x + y;
                }
                case 1 -> {
                    Fx80.mul(c, a.se, a.mant, b.se, b.mant);
                    expected = x * y;
                }
                case 2 -> {
                    Fx80.div(c, a.se, a.mant, b.se, b.mant);
                    expected = x / y;
                }
                default -> {
                    Fx80.sub(c, a.se, a.mant, b.se, b.mant);
                    expected = x - y;
                }
            }
            int got = Fx80.toSingle(ctx(Fx80.RN, 0), c.se, c.mant);
            if (Float.isNaN(expected))
                assertTrue(Float.isNaN(Float.intBitsToFloat(got)));
            else
                assertEquals(Float.floatToRawIntBits(expected), got, () -> "op " + op + " " + x + " " + y);
        }
    }

    /**
     * Exact reference for extended precision, normal range: rounds num/den (positive) to 64
     * mantissa bits in {@code mode}. Returns {se, mant hi, mant lo} as longs.
     */
    static long[] reference(boolean neg, BigInteger num, BigInteger den, int mode) {
        // exponent: 2^e <= num/den < 2^(e+1)
        int e = num.bitLength() - den.bitLength();
        boolean below = e >= 0 ? num.compareTo(den.shiftLeft(e)) < 0 : num.shiftLeft(-e).compareTo(den) < 0;
        if (below)
            e--;
        // scaled = num / den * 2^(63 - e)
        int s = 63 - e;
        BigInteger n = s >= 0 ? num.shiftLeft(s) : num;
        BigInteger d = s >= 0 ? den : den.shiftLeft(-s);
        BigInteger[] qr = n.divideAndRemainder(d);
        BigInteger q = qr[0];
        int cmpHalf = qr[1].shiftLeft(1).compareTo(d);
        boolean inexact = qr[1].signum() != 0;
        boolean inc = switch (mode) {
            case Fx80.RN -> cmpHalf > 0 || (cmpHalf == 0 && q.testBit(0));
            case Fx80.RZ -> false;
            case Fx80.RM -> inexact && neg;
            default -> inexact && !neg;
        };
        if (inc)
            q = q.add(BigInteger.ONE);
        if (q.bitLength() > 64) {
            q = q.shiftRight(1);
            e++;
        }
        return new long[] {(neg ? 0x8000 : 0) | (e + 16383), q.longValue()};
    }

    static BigInteger mantissa(long m) {
        return new BigInteger(Long.toUnsignedString(m));
    }

    @Test
    void extendedMatchesExactReferenceInAllModes() {
        Random r = new Random(1234);
        for (int i = 0; i < 4000; i++) {
            long ma = r.nextLong() | Fx80.INT_BIT;
            long mb = r.nextLong() | Fx80.INT_BIT;
            if (r.nextInt(4) == 0)
                mb = Fx80.INT_BIT | (r.nextLong() & 0xFF);
            int ea = 0x3FFF + r.nextInt(40) - 20;
            int eb = 0x3FFF + r.nextInt(100) - 50;
            boolean na = r.nextBoolean();
            boolean nb = r.nextBoolean();
            int mode = r.nextInt(4);
            int op = r.nextInt(4);
            // value = m * 2^(e - 16446); common scale 2^(min - 16446)
            int lowE = Math.min(ea, eb);
            BigInteger a = mantissa(ma).shiftLeft(ea - lowE);
            BigInteger b = mantissa(mb).shiftLeft(eb - lowE);
            BigInteger sa = na ? a.negate() : a;
            BigInteger sb = nb ? b.negate() : b;
            Fx80.Ctx c = ctx(mode, Fx80.PREC_EXTENDED);
            long[] want;
            BigInteger num;
            BigInteger den;
            int shift; // result = num / den * 2^shift
            switch (op) {
                case 0 -> {
                    Fx80.add(c, (na ? 0x8000 : 0) | ea, ma, (nb ? 0x8000 : 0) | eb, mb);
                    num = sa.add(sb);
                    den = BigInteger.ONE;
                    shift = lowE - 16446;
                }
                case 1 -> {
                    Fx80.sub(c, (na ? 0x8000 : 0) | ea, ma, (nb ? 0x8000 : 0) | eb, mb);
                    num = sa.subtract(sb);
                    den = BigInteger.ONE;
                    shift = lowE - 16446;
                }
                case 2 -> {
                    Fx80.mul(c, (na ? 0x8000 : 0) | ea, ma, (nb ? 0x8000 : 0) | eb, mb);
                    num = mantissa(ma).multiply(mantissa(mb));
                    if (na != nb)
                        num = num.negate();
                    den = BigInteger.ONE;
                    shift = ea - 16446 + eb - 16446;
                }
                default -> {
                    Fx80.div(c, (na ? 0x8000 : 0) | ea, ma, (nb ? 0x8000 : 0) | eb, mb);
                    num = mantissa(ma);
                    if (na != nb)
                        num = num.negate();
                    den = mantissa(mb);
                    shift = ea - eb;
                }
            }
            if (num.signum() == 0)
                continue;
            boolean neg = num.signum() < 0;
            num = num.abs();
            if (shift >= 0)
                num = num.shiftLeft(shift);
            else
                den = den.shiftLeft(-shift);
            want = reference(neg, num, den, mode);
            final int fop = op;
            final int fmode = mode;
            assertEquals(want[0], c.se, () -> "op " + fop + " mode " + fmode);
            assertEquals(want[1], c.mant, () -> String.format("op %d mode %d: %016X vs %016X", fop, fmode, c.mant, want[1]));
        }
    }

    @Test
    void extendedSquareRootMatchesExactReference() {
        Random r = new Random(99);
        for (int i = 0; i < 2000; i++) {
            long m = r.nextLong() | Fx80.INT_BIT;
            int e = 0x3FFF + r.nextInt(200) - 100;
            int mode = r.nextInt(4);
            Fx80.Ctx c = ctx(mode, Fx80.PREC_EXTENDED);
            Fx80.sqrt(c, e, m);
            // sqrt(m * 2^(e - 16446)) * 2^(63 - k), k the result's true exponent: compare via squares
            int k = (c.se & 0x7FFF) - 16383;
            // x = m * 2^(e-16446); result r = R * 2^(k-63). Check R^2 <= x * 2^(126-2k) < (R+1)^2 (RZ/RM)
            BigInteger x = mantissa(m);
            int sh = e - 16446 + 126 - 2 * k;
            BigInteger lhs = sh >= 0 ? x.shiftLeft(sh) : x;
            BigInteger rr = mantissa(c.mant);
            BigInteger lo = rr.multiply(rr);
            BigInteger hi = rr.add(BigInteger.ONE).multiply(rr.add(BigInteger.ONE));
            BigInteger lowR = rr.subtract(BigInteger.ONE).multiply(rr.subtract(BigInteger.ONE));
            if (sh < 0) {
                lo = lo.shiftLeft(-sh);
                hi = hi.shiftLeft(-sh);
                lowR = lowR.shiftLeft(-sh);
            }
            if (mode == Fx80.RZ || mode == Fx80.RM) {
                assertTrue(lo.compareTo(lhs) <= 0 && lhs.compareTo(hi) < 0, "truncated root " + i);
            } else if (mode == Fx80.RP) {
                assertTrue(lowR.compareTo(lhs) < 0 && lhs.compareTo(lo) <= 0, "rounded up root " + i);
            } else {
                // nearest: (R - 1/2)^2 < x <= (R + 1/2)^2, times 4: (2R-1)^2 < 4x < (2R+1)^2
                BigInteger two = BigInteger.TWO;
                BigInteger a = rr.multiply(two).subtract(BigInteger.ONE).pow(2);
                BigInteger b = rr.multiply(two).add(BigInteger.ONE).pow(2);
                BigInteger l4 = lhs.shiftLeft(2);
                if (sh < 0) {
                    a = a.shiftLeft(-sh);
                    b = b.shiftLeft(-sh);
                }
                assertTrue(a.compareTo(l4) < 0 && l4.compareTo(b) < 0, "nearest root " + i);
            }
        }
    }
}
