package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

/**
 * The FMOVECR constant ROM (spec 10.2.4). The 68040 leaves FMOVECR to its floating-point support
 * package, so this follows the package's routine ({@code smovecr} with the tables of
 * {@code get_op}) bit for bit, which is what software on a real 68040 saw:
 * <ul>
 * <li>one extended table per rounding mode (RN; RZ and RM share one; RP);</li>
 * <li>INEX2 for pi, log10(2), e, log2(e), ln(2), ln(10) and 10^32 up to 10^4096; no INEX2 for
 * log10(e) (a quirk of the package), 0.0 and 10^0 to 10^16;</li>
 * <li>with single or double rounding precision the table value is rounded again in the FPCR
 * mode (raising INEX2 if that loses bits);</li>
 * <li>offsets 0x01..0x0A, 0x0F..0x2F and 0x40..0x7F give +0.0 (the real hardware ROM of the
 * 68881 holds undocumented values at some of them; documented deviation of spec 10.2.4).</li>
 * </ul>
 * The table values are the exact constants rounded to 64 bits, except for a few last-bit
 * differences the package's tables carry (checked against the exact values computed with 400
 * digit arithmetic): e and log10(2) in RN are rounded down (exact rounding gives ...4A9B and
 * ...F799), log10(e) in RP is not rounded up (...7196), and 10^2048 in RZ and RM is rounded up
 * (...5DE4 truncated).
 */
public final class FpConstantRom {

    private FpConstantRom() {
    }

    /** Documented offsets, parallel to the tables. */
    private static final int[] OFFSET = {
            0x00, 0x0B, 0x0C, 0x0D, 0x0E, 0x30, 0x31,
            0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F,
    };

    private static final int[] SE = {
            0x4000, // 0x00 pi = 3.14159265358979323846...
            0x3FFD, // 0x0B log10(2) = 0.30102999566398119521...
            0x4000, // 0x0C e = 2.71828182845904523536...
            0x3FFF, // 0x0D log2(e) = 1.44269504088896340735...
            0x3FFD, // 0x0E log10(e) = 0.43429448190325182765...
            0x3FFE, // 0x30 ln(2) = 0.69314718055994530941...
            0x4000, // 0x31 ln(10) = 2.30258509299404568401...
            0x3FFF, // 0x32 10^0
            0x4002, // 0x33 10^1
            0x4005, // 0x34 10^2
            0x400C, // 0x35 10^4
            0x4019, // 0x36 10^8
            0x4034, // 0x37 10^16
            0x4069, // 0x38 10^32
            0x40D3, // 0x39 10^64
            0x41A8, // 0x3A 10^128
            0x4351, // 0x3B 10^256
            0x46A3, // 0x3C 10^512
            0x4D48, // 0x3D 10^1024
            0x5A92, // 0x3E 10^2048
            0x7525, // 0x3F 10^4096
    };

    private static final long[] RN = {
            0xC90F_DAA2_2168_C235L,
            0x9A20_9A84_FBCF_F798L,
            0xADF8_5458_A2BB_4A9AL,
            0xB8AA_3B29_5C17_F0BCL,
            0xDE5B_D8A9_3728_7195L,
            0xB172_17F7_D1CF_79ACL,
            0x935D_8DDD_AAA8_AC17L,
            0x8000_0000_0000_0000L,
            0xA000_0000_0000_0000L,
            0xC800_0000_0000_0000L,
            0x9C40_0000_0000_0000L,
            0xBEBC_2000_0000_0000L,
            0x8E1B_C9BF_0400_0000L,
            0x9DC5_ADA8_2B70_B59EL,
            0xC278_1F49_FFCF_A6D5L,
            0x93BA_47C9_80E9_8CE0L,
            0xAA7E_EBFB_9DF9_DE8EL,
            0xE319_A0AE_A60E_91C7L,
            0xC976_7586_8175_0C17L,
            0x9E8B_3B5D_C53D_5DE5L,
            0xC460_5202_8A20_979BL,
    };

    /** RZ and RM. */
    private static final long[] RZ_RM = {
            0xC90F_DAA2_2168_C234L,
            0x9A20_9A84_FBCF_F798L,
            0xADF8_5458_A2BB_4A9AL,
            0xB8AA_3B29_5C17_F0BBL,
            0xDE5B_D8A9_3728_7195L,
            0xB172_17F7_D1CF_79ABL,
            0x935D_8DDD_AAA8_AC16L,
            0x8000_0000_0000_0000L,
            0xA000_0000_0000_0000L,
            0xC800_0000_0000_0000L,
            0x9C40_0000_0000_0000L,
            0xBEBC_2000_0000_0000L,
            0x8E1B_C9BF_0400_0000L,
            0x9DC5_ADA8_2B70_B59DL,
            0xC278_1F49_FFCF_A6D5L,
            0x93BA_47C9_80E9_8CDFL,
            0xAA7E_EBFB_9DF9_DE8DL,
            0xE319_A0AE_A60E_91C6L,
            0xC976_7586_8175_0C17L,
            0x9E8B_3B5D_C53D_5DE5L,
            0xC460_5202_8A20_979AL,
    };

    private static final long[] RP = {
            0xC90F_DAA2_2168_C235L,
            0x9A20_9A84_FBCF_F799L,
            0xADF8_5458_A2BB_4A9BL,
            0xB8AA_3B29_5C17_F0BCL,
            0xDE5B_D8A9_3728_7195L,
            0xB172_17F7_D1CF_79ACL,
            0x935D_8DDD_AAA8_AC17L,
            0x8000_0000_0000_0000L,
            0xA000_0000_0000_0000L,
            0xC800_0000_0000_0000L,
            0x9C40_0000_0000_0000L,
            0xBEBC_2000_0000_0000L,
            0x8E1B_C9BF_0400_0000L,
            0x9DC5_ADA8_2B70_B59EL,
            0xC278_1F49_FFCF_A6D6L,
            0x93BA_47C9_80E9_8CE0L,
            0xAA7E_EBFB_9DF9_DE8EL,
            0xE319_A0AE_A60E_91C7L,
            0xC976_7586_8175_0C18L,
            0x9E8B_3B5D_C53D_5DE6L,
            0xC460_5202_8A20_979BL,
    };

    /** Whether the package raises INEX2 for the constant. */
    private static final boolean[] INEXACT = {
            true, true, true, true, false, true, true,
            false, false, false, false, false, false, true, true, true, true, true, true, true, true,
    };

    /** Loads constant {@code offset} (0..0x7F) into {@code c} in its mode and precision, ORing INEX2. */
    public static void load(Fx80.Ctx c, int offset) {
        for (int i = 0; i < OFFSET.length; i++) {
            if (OFFSET[i] != offset)
                continue;
            long m = switch (c.mode) {
                case Fx80.RN -> RN[i];
                case Fx80.RP -> RP[i];
                default -> RZ_RM[i];
            };
            if (INEXACT[i])
                c.flags |= Fx80.INEX2;
            if (c.precision == Fx80.PREC_EXTENDED)
                c.set(SE[i], m);
            else
                Fx80.round(c, SE[i], m);
            return;
        }
        c.set(0, 0);
    }
}
