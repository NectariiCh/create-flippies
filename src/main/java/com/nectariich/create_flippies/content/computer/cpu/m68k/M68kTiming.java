package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * Cycle costs of both models (spec 10.2.2, 10.3.9).
 *
 * 68000: the user manual's timing tables, 4 clocks per bus cycle and no wait states. An
 * instruction costs its base time from the per-instruction tables plus the effective address
 * calculation time of each general operand ({@link #EA_68000}). The core charges the base times
 * itself (they sit next to each instruction's code, named after the manual's table rows) and
 * reads the per-mode tables here. MOVE uses its own source by destination matrix, which the
 * manual's MOVE tables reduce to 4 + source EA time + {@link #MOVE_DEST_68000}.
 *
 * 68040: a deliberately simple model (documented as approximate; the real chip overlaps its
 * pipelines and depends on cache hits): a base cost per instruction (1 for most) plus 1 per data
 * memory access, the fixed costs below, plus the device access surcharge.
 *
 * Tables indexed by EA kind ({@link M68kOpTable#kind}) hold two entries per kind, byte or word
 * first, then long. Entries for kinds an instruction cannot use are 0.
 */
final class M68kTiming {

    private M68kTiming() {
    }

    /** EA calculation time per kind, (byte or word, long): Dn, An, (An), (An)+, -(An), d16(An), d8(An,Xn), abs.W, abs.L, d16(PC), d8(PC,Xn), #imm. */
    static final int[] EA_68000 = {
            0, 0, 0, 0, 4, 8, 4, 8, 6, 10, 8, 12, 10, 14, 8, 12, 12, 16, 8, 12, 10, 14, 4, 8,
    };

    /** The 68040 charges memory operands per access instead (spec 10.3.9). */
    static final int[] EA_68040 = new int[24];

    /**
     * MOVE destination time per kind (byte or word, long): the MOVE matrix of the manual is
     * 4 + source EA time + this. Same as the EA times except -(An), which costs no extra as a
     * destination (MOVE.W Dn,-(An) is 8, MOVE.L Dn,-(An) is 12).
     */
    static final int[] MOVE_DEST_68000 = {
            0, 0, 0, 0, 4, 8, 4, 8, 4, 8, 8, 12, 10, 14, 8, 12, 12, 16, 0, 0, 0, 0, 0, 0,
    };

    // ---- control-mode instructions, total time per kind (manual "JMP, JSR, LEA, PEA, and MOVEM") ----

    /** JMP: (An) 8, d16(An) 10, d8(An,Xn) 14, abs.W 10, abs.L 12, d16(PC) 10, d8(PC,Xn) 14. */
    static final int[] JMP = {0, 0, 8, 0, 0, 10, 14, 10, 12, 10, 14, 0};
    /** JSR: (An) 16, d16(An) 18, d8(An,Xn) 22, abs.W 18, abs.L 20, d16(PC) 18, d8(PC,Xn) 22. */
    static final int[] JSR = {0, 0, 16, 0, 0, 18, 22, 18, 20, 18, 22, 0};
    /** LEA: (An) 4, d16(An) 8, d8(An,Xn) 12, abs.W 8, abs.L 12, d16(PC) 8, d8(PC,Xn) 12. */
    static final int[] LEA = {0, 0, 4, 0, 0, 8, 12, 8, 12, 8, 12, 0};
    /** PEA: (An) 12, d16(An) 16, d8(An,Xn) 20, abs.W 16, abs.L 20, d16(PC) 16, d8(PC,Xn) 20. */
    static final int[] PEA = {0, 0, 12, 0, 0, 16, 20, 16, 20, 16, 20, 0};
    /** MOVEM memory to registers, base before 4 (word) or 8 (long) per register. */
    static final int[] MOVEM_TO_REG = {0, 0, 12, 12, 0, 16, 18, 16, 20, 16, 18, 0};
    /** MOVEM registers to memory, base before 4 (word) or 8 (long) per register. */
    static final int[] MOVEM_TO_MEM = {0, 0, 8, 0, 8, 12, 14, 12, 16, 0, 0, 0};

    // ---- 68000 exception processing, totals (manual "Exception processing execution times") ----

    /** Bus error and address error. */
    static final int GROUP0 = 50;
    /** TRAP #n, illegal instruction, privilege violation, line A, line F, trace, TRAPV taken. */
    static final int EXCEPTION = 34;
    /** CHK taken, plus the EA time of the bound. */
    static final int CHK_TRAP = 40;
    /** Zero divide, plus the EA time of the divisor. */
    static final int ZERO_DIVIDE = 38;
    /** Interrupt, autovectored. */
    static final int INTERRUPT = 44;
    /** RESET instruction: the reset line held 124 clocks, 132 in total. */
    static final int RESET_INSTRUCTION = 132;
    /** Reset sequence: reading the SSP and PC vectors and the first prefetch (verify). */
    static final int RESET_SEQUENCE = 40;

    // ---- 68040 cost model (spec 10.3.9) ----

    static final int C040_BASE = 1;
    static final int C040_BRANCH_TAKEN = 2;
    static final int C040_BRANCH_NOT_TAKEN = 1;
    static final int C040_CALL = 3;
    static final int C040_RETURN = 3;
    static final int C040_MUL_W = 16;
    static final int C040_MUL_L = 20;
    static final int C040_DIV_W = 27;
    static final int C040_DIV_L = 44;
    static final int C040_EXCEPTION = 16;
    static final int C040_RTE = 13;
    /** RESET holds the reset output 512 clocks (verify); about 520 in total. */
    static final int C040_RESET = 520;
    static final int C040_RESET_SEQUENCE = 40;

    /** MULU.W: 38 + 2n, n = the number of ones in the 16-bit source. */
    static int mulu(int src) {
        return 38 + 2 * Integer.bitCount(src & 0xFFFF);
    }

    /** MULS.W: 38 + 2n, n = the number of 01 or 10 pairs in the source with a 0 appended below bit 0. */
    static int muls(int src) {
        int x = (src & 0xFFFF) << 1;
        return 38 + 2 * Integer.bitCount((x ^ (x >>> 1)) & 0xFFFF);
    }

    /**
     * DIVU.W without the EA time, for a nonzero divisor: the published per-bit description of the
     * 68000's restoring division (one microcycle pair per quotient bit, two more when the
     * shifted-out bit is clear and one fewer when the subtraction then succeeds). Overflow aborts
     * after 10 clocks. Range 76 to 136 (the manual: "less than 140").
     */
    static int divu(int dividend, int divisor) {
        long hdivisor = (long) (divisor & 0xFFFF) << 16;
        long d = dividend & 0xFFFF_FFFFL;
        if ((d >>> 16) >= (divisor & 0xFFFF))
            return 10;
        int mcycles = 38;
        for (int i = 0; i < 15; i++) {
            long before = d;
            d = (d << 1) & 0xFFFF_FFFFL;
            if ((before & 0x8000_0000L) != 0) {
                d = (d - hdivisor) & 0xFFFF_FFFFL;
            } else {
                mcycles += 2;
                if (d >= hdivisor) {
                    d -= hdivisor;
                    mcycles--;
                }
            }
        }
        return mcycles * 2;
    }

    /**
     * DIVS.W without the EA time, for a nonzero divisor: the published description of the
     * 68000's signed division (absolute values, then one extra microcycle per clear bit among the
     * absolute quotient's 15 high bits). An absolute overflow aborts early. Range up to 156 (the
     * manual: "less than 158").
     */
    static int divs(int dividend, int divisor) {
        short dv = (short) divisor;
        int mcycles = 6;
        if (dividend < 0)
            mcycles++;
        long adividend = Math.abs((long) dividend);
        int adivisor = Math.abs((int) dv);
        if ((adividend >>> 16) >= adivisor)
            return (mcycles + 2) * 2;
        int aquot = (int) (adividend / adivisor);
        mcycles += 55;
        if (dv >= 0) {
            if (dividend >= 0)
                mcycles--;
            else
                mcycles++;
        }
        for (int i = 0; i < 15; i++) {
            if ((short) aquot >= 0)
                mcycles++;
            aquot <<= 1;
        }
        return mcycles * 2;
    }
}
