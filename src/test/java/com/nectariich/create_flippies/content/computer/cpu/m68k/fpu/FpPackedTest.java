package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Packed decimal in and out. Layout of word 0: SM 31, SE 30, YY 29..28, exponent digits 27..16,
 * fourth exponent digit 15..12, integer digit 3..0; words 1 and 2 hold 16 fraction digits.
 */
class FpPackedTest {

    static final long INT = Fx80.INT_BIT;

    static Fx80.Ctx ctx(int mode) {
        return new Fx80.Ctx(mode, Fx80.PREC_EXTENDED);
    }

    @Test
    void decodeExactValues() {
        Fx80.Ctx c = ctx(Fx80.RN);
        // 1.5E+2 = 150 = 1.171875 * 2^7: (0x4006, 0x9600...)
        FpPacked.decode(c, 0x0002_0001, 0x5000_0000, 0);
        assertEquals(0x4006, c.se);
        assertEquals(0x9600_0000_0000_0000L, c.mant);
        assertEquals(0, c.flags);
        // -2.5E-1 = -0.25 = (0xBFFD, INT)
        FpPacked.decode(c, 0xC001_0002, 0x5000_0000, 0);
        assertEquals(0xBFFD, c.se);
        assertEquals(INT, c.mant);
        // zero with any exponent, keeping the mantissa sign
        FpPacked.decode(c, 0x8123_0000, 0, 0);
        assertEquals(0x8000, c.se);
        assertEquals(0, c.mant);
    }

    @Test
    void decodeInexactSetsInex1() {
        // 1E-1 = 0.1: rounded like the nearest extended, 0x3FFB CCCC_CCCC_CCCC_CCCD
        Fx80.Ctx c = ctx(Fx80.RN);
        FpPacked.decode(c, 0x4001_0001, 0, 0);
        assertEquals(0x3FFB, c.se);
        assertEquals(0xCCCC_CCCC_CCCC_CCCDL, c.mant);
        assertEquals(Fx80.INEX1, c.flags);
        c = ctx(Fx80.RZ);
        FpPacked.decode(c, 0x4001_0001, 0, 0);
        assertEquals(0xCCCC_CCCC_CCCC_CCCCL, c.mant);
        // 1E+999 is far inside the extended range
        c = ctx(Fx80.RN);
        FpPacked.decode(c, 0x0999_0001, 0, 0);
        assertEquals(0x3FFF + 3318, c.se); // log2(10^999) = 3318.6
    }

    @Test
    void decodeInfinityAndNan() {
        Fx80.Ctx c = ctx(Fx80.RN);
        FpPacked.decode(c, 0xFFFF_0000, 0, 0);
        assertEquals(0xFFFF, c.se);
        assertEquals(0, c.mant);
        FpPacked.decode(c, 0x7FFF_0000, 0x4000_0000, 5);
        assertEquals(0x7FFF, c.se);
        assertEquals(0x4000_0000_0000_0005L, c.mant);
    }

    @Test
    void encodeWithStaticKFactors() {
        Fx80.Ctx c = ctx(Fx80.RN);
        // 150 with k = 3 (three significant digits): 1.50E+2
        assertArrayEquals(new int[] {0x0002_0001, 0x5000_0000, 0}, FpPacked.encode(c, 0x4006, 0x9600_0000_0000_0000L, 3));
        assertEquals(0, c.flags);
        // k = 1: 1.5 rounds to even, 2E+2, inexact
        assertArrayEquals(new int[] {0x0002_0002, 0, 0}, FpPacked.encode(c, 0x4006, 0x9600_0000_0000_0000L, 1));
        assertEquals(Fx80.INEX2, c.flags);
        // pi (the extended value 3.14159265358979323851...) with 17 digits
        c = ctx(Fx80.RN);
        assertArrayEquals(new int[] {0x0000_0003, 0x1415_9265, 0x3589_7932}, FpPacked.encode(c, 0x4000, 0xC90F_DAA2_2168_C235L, 17));
        assertEquals(Fx80.INEX2, c.flags);
        // k <= 0 counts digits right of the point: k = -2 gives 3.14, k = 0 gives 3
        assertArrayEquals(new int[] {0x0000_0003, 0x1400_0000, 0}, FpPacked.encode(c, 0x4000, 0xC90F_DAA2_2168_C235L, -2));
        assertArrayEquals(new int[] {0x0000_0003, 0, 0}, FpPacked.encode(c, 0x4000, 0xC90F_DAA2_2168_C235L, 0));
        // 0.1 (extended) with k = 1: 1E-1, exponent sign set
        assertArrayEquals(new int[] {0x4001_0001, 0, 0}, FpPacked.encode(c, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL, 1));
        // k = 18 is an operand error and gives 17 digits
        c = ctx(Fx80.RN);
        assertArrayEquals(new int[] {0x0002_0001, 0x5000_0000, 0}, FpPacked.encode(c, 0x4006, 0x9600_0000_0000_0000L, 18));
        assertEquals(Fx80.OPERR, c.flags);
    }

    @Test
    void encodeRoundsInTheFpcrMode() {
        long m150 = 0x9600_0000_0000_0000L;
        assertArrayEquals(new int[] {0x0002_0002, 0, 0}, FpPacked.encode(ctx(Fx80.RP), 0x4006, m150, 1));
        assertArrayEquals(new int[] {0x0002_0001, 0, 0}, FpPacked.encode(ctx(Fx80.RM), 0x4006, m150, 1));
        assertArrayEquals(new int[] {0x0002_0001, 0, 0}, FpPacked.encode(ctx(Fx80.RZ), 0x4006, m150, 1));
        // -150 in RM goes away from zero: -2E+2
        assertArrayEquals(new int[] {0x8002_0002, 0, 0}, FpPacked.encode(ctx(Fx80.RM), 0xC006, m150, 1));
    }

    @Test
    void encodeSpecialsAndFourDigitExponent() {
        Fx80.Ctx c = ctx(Fx80.RN);
        assertArrayEquals(new int[] {0x8000_0000, 0, 0}, FpPacked.encode(c, 0x8000, 0, 5));
        assertArrayEquals(new int[] {0x7FFF_0000, 0, 0}, FpPacked.encode(c, 0x7FFF, 0, 5));
        assertArrayEquals(new int[] {0xFFFF_0000, 0xC000_0000, 7}, FpPacked.encode(c, 0xFFFF, 0x8000_0000_0000_0007L, 5));
        assertEquals(Fx80.SNAN, c.flags);
        // 10^1024 (the constant ROM value) with k = 1: 1E+1024, thousands digit in 15..12, OPERR
        c = ctx(Fx80.RN);
        FpConstantRom.load(c, 0x3D);
        int se = c.se;
        long m = c.mant;
        c.flags = 0;
        assertArrayEquals(new int[] {0x0024_1001, 0, 0}, FpPacked.encode(c, se, m, 1));
        assertEquals(Fx80.OPERR | Fx80.INEX2, c.flags);
    }
}
