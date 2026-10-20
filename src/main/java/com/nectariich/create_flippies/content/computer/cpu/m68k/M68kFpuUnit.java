package com.nectariich.create_flippies.content.computer.cpu.m68k;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * The 68040's built-in FPU as the integer core sees it (spec 10.4, 10.9). The core decodes line F
 * opcodes with coprocessor id 1 and calls in here; FBcc, FScc, FDBcc and FTRAPcc are carried out
 * by the core (branching, EA writes, counters, traps) with {@link #condition} deciding them.
 * Frozen: the core and the FPU are written in parallel against it. The core checks privilege
 * (FSAVE, FRESTORE) before calling.
 *
 * Every entry point returns the cycles the instruction costs beyond what the core charges for
 * decoding (spec 10.3.9).
 */
public interface M68kFpuUnit {

    /** {@link #condition} result: a BSUN exception was taken; the core ends the instruction there. */
    int CONDITION_TRAPPED = -1;

    /** Builds the FPU for a core. {@code strict}: see {@link M68kOptions#strictFpu()}. */
    @FunctionalInterface
    interface Factory {
        M68kFpuUnit create(M68kFpuHost host, boolean strict);
    }

    /** Hardware reset: FP0..FP7 to non-signalling NaN, FPCR, FPSR, FPIAR to 0, FSAVE null state. */
    void reset();

    /**
     * A general FP instruction: {@code opword} 0xF200..0xF23F (coprocessor id 1, type 000, the
     * EA field in bits 5..0). Fetches its command word and any extension words through the host.
     */
    int general(int opword);

    /**
     * Evaluates FP condition {@code predicate} (the 6-bit field of FBcc, FScc, FDBcc, FTRAPcc)
     * against FPSR: 1 true, 0 false. For a signalling predicate (bit 4 set) with NAN set it sets
     * BSUN and the accrued IOP; if BSUN is enabled in FPCR it takes the pre-instruction exception
     * (vector 48, format 0, stacked PC = the instruction) and returns {@link #CONDITION_TRAPPED}.
     * Predicates 0x20..0x3F are reserved: F-line exception (vector 11, format 0, stacked PC = the
     * instruction) and {@link #CONDITION_TRAPPED}.
     */
    int condition(int predicate);

    /** FSAVE, {@code opword} 0xF300..0xF33F; writes the state frame at the EA in bits 5..0. */
    int save(int opword);

    /** FRESTORE, {@code opword} 0xF340..0xF37F; reads the state frame at the EA in bits 5..0. */
    int restore(int opword);

    /** The FPU's complete state (spec 10.6): its format version first. Pure. */
    void saveState(DataOutput out) throws IOException;

    /** Replaces the state with what {@link #saveState} wrote; StateFormatException for an unknown version. */
    void loadState(DataInput in) throws IOException;
}
