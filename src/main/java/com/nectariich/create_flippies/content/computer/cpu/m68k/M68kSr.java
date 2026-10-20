package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * Status register bits of the 68000 and 68040 (spec 10.2.1). The low byte is the condition code
 * register (CCR); the high byte is the system byte, writable only in supervisor mode.
 */
public final class M68kSr {

    private M68kSr() {
    }

    /** Carry (CCR bit 0). */
    public static final int C = 0x0001;
    /** Overflow (CCR bit 1). */
    public static final int V = 0x0002;
    /** Zero (CCR bit 2). */
    public static final int Z = 0x0004;
    /** Negative (CCR bit 3). */
    public static final int N = 0x0008;
    /** Extend (CCR bit 4). */
    public static final int X = 0x0010;
    /** Every CCR bit that exists; bits 7..5 read 0. */
    public static final int CCR_MASK = 0x001F;

    /** Interrupt mask, bits 10..8. */
    public static final int IPL_MASK = 0x0700;
    /** Shift of the interrupt mask field. */
    public static final int IPL_SHIFT = 8;
    /** Master/interrupt state (68040 only): selects MSP over ISP while S = 1. */
    public static final int M = 0x1000;
    /** Supervisor state. */
    public static final int S = 0x2000;
    /** Trace on change of flow (68040 only). */
    public static final int T0 = 0x4000;
    /** Trace (68000 T; 68040 T1, trace every instruction). */
    public static final int T1 = 0x8000;

    /** Bits that exist on the 68000: T, S, mask, CCR; the others read 0. */
    public static final int MASK_68000 = 0xA71F;
    /** Bits that exist on the 68040: T1, T0, S, M, mask, CCR. */
    public static final int MASK_68040 = 0xF71F;

    /** SR after reset on both models: S = 1, mask 7, trace off, M = 0, CCR 0. */
    public static final int RESET = 0x2700;
}
