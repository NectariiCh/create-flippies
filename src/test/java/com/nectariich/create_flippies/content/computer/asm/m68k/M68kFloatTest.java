package com.nectariich.create_flippies.content.computer.asm.m68k;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.errors;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decimal literals to single, double, extended and packed bits. Expected patterns are the
 * well-known IEEE encodings and, for extended, worked by hand: 0.1 = 1.6 * 2^-4, so the biased
 * exponent is 16383 - 4 = 0x3FFB and the mantissa round(0.1 * 2^67) = 14757395258967641292.8,
 * rounded up to 0xCCCC_CCCC_CCCC_CCCD.
 */
class M68kFloatTest {

    private static int s(String text) {
        return M68kFloat.single(text.startsWith("-"), M68kFloat.parse(text.startsWith("-") ? text.substring(1) : text));
    }

    private static long d(String text) {
        return M68kFloat.dbl(text.startsWith("-"), M68kFloat.parse(text.startsWith("-") ? text.substring(1) : text));
    }

    private static int[] x(String text) {
        return M68kFloat.extended(text.startsWith("-"), M68kFloat.parse(text.startsWith("-") ? text.substring(1) : text));
    }

    @Test
    void single() {
        assertEquals(0x3F80_0000, s("1.0"));
        assertEquals(0x3DCC_CCCD, s("0.1"));
        assertEquals(0xC020_0000, s("-2.5"));
        assertEquals(0x3F8C_CCCD, s("1.1"));
        assertEquals(0x0000_0000, s("0.0"));
        assertEquals(0x8000_0000, s("-0.0"));
        assertEquals(0x7F7F_FFFF, s("3.4028234663852886e38"));
        assertEquals(0x0000_0001, s("1.4e-45"));
        assertEquals(0x0080_0000, s("1.1754943508222875e-38"));
        assertEquals(0x3EAA_AAAB, s("0.333333333333333333333"));
        assertThrows(IllegalArgumentException.class, () -> s("1e39"));
        assertEquals(0, s("1e-50"));
    }

    @Test
    void dbl() {
        assertEquals(0x3FF0_0000_0000_0000L, d("1.0"));
        assertEquals(0x3FB9_9999_9999_999AL, d("0.1"));
        assertEquals(0xC004_0000_0000_0000L, d("-2.5"));
        assertEquals(0x7FEF_FFFF_FFFF_FFFFL, d("1.7976931348623157e308"));
        assertEquals(0x0000_0000_0000_0001L, d("4.9e-324"));
        assertThrows(IllegalArgumentException.class, () -> d("1e309"));
        // matches the JDK's correctly rounded conversion on assorted values
        for (String v : new String[] {"3.141592653589793", "2.718281828459045", "1e22", "1e23", "123456.789",
                "5e-324", "2.2250738585072014e-308", "0.30000000000000004"})
            assertEquals(Double.doubleToRawLongBits(Double.parseDouble(v)), d(v), v);
    }

    @Test
    void extended() {
        assertArrayEquals(new int[] {0x3FFF_0000, 0x8000_0000, 0}, x("1.0"));
        assertArrayEquals(new int[] {0x3FFB_0000, 0xCCCC_CCCC, 0xCCCC_CCCD}, x("0.1"));
        assertArrayEquals(new int[] {0xC000_0000, 0xA000_0000, 0}, x("-2.5"));
        assertArrayEquals(new int[] {0, 0, 0}, x("0"));
        assertArrayEquals(new int[] {0x4000_0000, 0xC90F_DAA2, 0x2168_C235}, x("3.14159265358979323846264338327950288"));
        assertThrows(IllegalArgumentException.class, () -> x("1e5000"));
    }

    @Test
    void packed() {
        assertArrayEquals(new int[] {0x0000_0001, 0, 0}, M68kFloat.packed(false, new BigDecimal("1.0")));
        assertArrayEquals(new int[] {0x8000_0002, 0x5000_0000, 0}, M68kFloat.packed(true, new BigDecimal("2.5")));
        assertArrayEquals(new int[] {0x4001_0001, 0, 0}, M68kFloat.packed(false, new BigDecimal("0.1")));
        assertArrayEquals(new int[] {0x0012_0001, 0x2345_6789, 0x0123_4568},
                M68kFloat.packed(false, new BigDecimal("1.234567890123456789e12")));
    }

    @Test
    void dataStatements() {
        check("dc.s 1.0,0.1,-2.5", 0x3F80, 0x0000, 0x3DCC, 0xCCCD, 0xC020, 0x0000);
        check("dc.d 1.0,0.1,-2.5", 0x3FF0, 0, 0, 0, 0x3FB9, 0x9999, 0x9999, 0x999A, 0xC004, 0, 0, 0);
        check("dc.x 1.0,0.1,-2.5", 0x3FFF, 0, 0x8000, 0, 0, 0, 0x3FFB, 0, 0xCCCC, 0xCCCC, 0xCCCC, 0xCCCD,
                0xC000, 0, 0xA000, 0, 0, 0);
        check("dc.p -2.5", 0x8000, 0x0002, 0x5000, 0, 0, 0);
        check("dc.s 1,-2", 0x3F80, 0x0000, 0xC000, 0x0000);
        check("dc.d +1.5", 0x3FF8, 0, 0, 0);
        check(M68kTarget.M68000, "dc.s 1.0", 0x3F80, 0x0000);
    }

    @Test
    void literalErrors() {
        errors(M68kTarget.M68040, "dc.s 1e40");
        errors(M68kTarget.M68040, "fmove.s #1e40,fp0");
        errors(M68kTarget.M68040, "move.l #1.5,d0");
        errors(M68kTarget.M68040, "dc.w 1.5");
    }
}
