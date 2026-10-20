package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * Exception vector numbers and 68040 stack frame formats (spec 10.2.1, 10.2.3). The vector's
 * address is {@code VBR + vector * 4} (VBR is always 0 on the 68000).
 */
public final class M68kVectors {

    private M68kVectors() {
    }

    /** Initial supervisor stack pointer, read at reset. */
    public static final int RESET_SSP = 0;
    /** Initial PC, read at reset. */
    public static final int RESET_PC = 1;
    /** Bus error (68000) or access error (68040). */
    public static final int BUS_ERROR = 2;
    public static final int ADDRESS_ERROR = 3;
    public static final int ILLEGAL_INSTRUCTION = 4;
    public static final int ZERO_DIVIDE = 5;
    /** CHK, CHK2. */
    public static final int CHK = 6;
    /** TRAPV, TRAPcc, FTRAPcc. */
    public static final int TRAPV = 7;
    public static final int PRIVILEGE_VIOLATION = 8;
    public static final int TRACE = 9;
    /** Line 1010 emulator (opcodes 0xAxxx). */
    public static final int LINE_A = 10;
    /** Line 1111 emulator (opcodes 0xFxxx not taken by the FPU or the 68040 core); also the strict FPU's unimplemented instruction trap. */
    public static final int LINE_F = 11;
    /** Coprocessor protocol violation; never raised by this emulation (no external coprocessor). */
    public static final int COPROCESSOR_PROTOCOL = 13;
    /** Format error (68040): RTE or FRESTORE of a bad frame. */
    public static final int FORMAT_ERROR = 14;
    public static final int UNINITIALIZED_INTERRUPT = 15;
    public static final int SPURIOUS_INTERRUPT = 24;
    /** Autovector for interrupt level 1; level L uses {@code AUTOVECTOR_BASE + L} (25..31). */
    public static final int AUTOVECTOR_BASE = 24;
    /** TRAP #0; TRAP #n uses {@code TRAP_BASE + n} (32..47). */
    public static final int TRAP_BASE = 32;

    /** FP branch or set on unordered (pre-instruction). */
    public static final int FP_BSUN = 48;
    public static final int FP_INEXACT = 49;
    public static final int FP_DIVIDE_BY_ZERO = 50;
    public static final int FP_UNDERFLOW = 51;
    public static final int FP_OPERAND_ERROR = 52;
    public static final int FP_OVERFLOW = 53;
    public static final int FP_SIGNALING_NAN = 54;
    /** FP unimplemented data type (68040, strict FPU only: packed, denormal and unnormal operands). */
    public static final int FP_UNIMPLEMENTED_DATA_TYPE = 55;

    /** First user vector. */
    public static final int USER_BASE = 64;
    public static final int COUNT = 256;

    /** 68040 frame format 0: SR, PC, format/vector word (8 bytes). */
    public static final int FORMAT_0 = 0;
    /** 68040 frame format 1: throwaway frame on the ISP after an interrupt taken with M = 1 (8 bytes). */
    public static final int FORMAT_1 = 1;
    /** 68040 frame format 2: format 0 plus an address long (12 bytes). */
    public static final int FORMAT_2 = 2;
    /** 68040 frame format 3: FP post-instruction exception, address = the effective address (12 bytes). */
    public static final int FORMAT_3 = 3;
    /** Frame format 4 (16 bytes): accepted by RTE, never built by this emulation (spec 10.2.3). */
    public static final int FORMAT_4 = 4;
    /** 68040 frame format 7: access error (60 bytes). */
    public static final int FORMAT_7 = 7;

    /** Bytes of a 68040 frame of {@code format}, or -1 for a format RTE rejects (format error). */
    public static int frameBytes(int format) {
        return switch (format) {
            case FORMAT_0, FORMAT_1 -> 8;
            case FORMAT_2, FORMAT_3 -> 12;
            case FORMAT_4 -> 16;
            case FORMAT_7 -> 60;
            default -> -1;
        };
    }
}
