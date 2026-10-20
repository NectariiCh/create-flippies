package com.nectariich.create_flippies.content.computer.asm.m68k;

/**
 * The assembler's own copy of the 68040 FPU encodings (spec 10.2.4, 10.10): opmodes, operand
 * format codes and the 32 condition predicates. The assembler imports nothing from the CPU
 * packages; the integration step cross-checks this copy against the core's table. Every code was
 * checked against the programmer's reference manual (and its errata: FMOD is 0x21).
 */
public final class M68kFpOpcodes {

    private M68kFpOpcodes() {
    }

    // ---- opmodes; "(package)" marks the ones the real 68040 leaves to its support package ----

    public static final int FMOVE = 0x00;
    /** (package) */
    public static final int FINT = 0x01;
    /** (package) */
    public static final int FSINH = 0x02;
    /** (package) */
    public static final int FINTRZ = 0x03;
    public static final int FSQRT = 0x04;
    /** (package) */
    public static final int FLOGNP1 = 0x06;
    /** (package) */
    public static final int FETOXM1 = 0x08;
    /** (package) */
    public static final int FTANH = 0x09;
    /** (package) */
    public static final int FATAN = 0x0A;
    /** (package) */
    public static final int FASIN = 0x0C;
    /** (package) */
    public static final int FATANH = 0x0D;
    /** (package) */
    public static final int FSIN = 0x0E;
    /** (package) */
    public static final int FTAN = 0x0F;
    /** (package) */
    public static final int FETOX = 0x10;
    /** (package) */
    public static final int FTWOTOX = 0x11;
    /** (package) */
    public static final int FTENTOX = 0x12;
    /** (package) */
    public static final int FLOGN = 0x14;
    /** (package) */
    public static final int FLOG10 = 0x15;
    /** (package) */
    public static final int FLOG2 = 0x16;
    public static final int FABS = 0x18;
    /** (package) */
    public static final int FCOSH = 0x19;
    public static final int FNEG = 0x1A;
    /** (package) */
    public static final int FACOS = 0x1C;
    /** (package) */
    public static final int FCOS = 0x1D;
    /** (package) */
    public static final int FGETEXP = 0x1E;
    /** (package) */
    public static final int FGETMAN = 0x1F;
    public static final int FDIV = 0x20;
    /** (package) */
    public static final int FMOD = 0x21;
    public static final int FADD = 0x22;
    public static final int FMUL = 0x23;
    /** (package) */
    public static final int FSGLDIV = 0x24;
    /** (package) */
    public static final int FREM = 0x25;
    /** (package) */
    public static final int FSCALE = 0x26;
    /** (package) */
    public static final int FSGLMUL = 0x27;
    public static final int FSUB = 0x28;
    /** (package) FSINCOS is 0x30..0x37; the low 3 bits name the cosine's destination register. */
    public static final int FSINCOS = 0x30;
    public static final int FCMP = 0x38;
    public static final int FTST = 0x3A;
    public static final int FSMOVE = 0x40;
    public static final int FSSQRT = 0x41;
    public static final int FDMOVE = 0x44;
    public static final int FDSQRT = 0x45;
    public static final int FSABS = 0x58;
    public static final int FSNEG = 0x5A;
    public static final int FDABS = 0x5C;
    public static final int FDNEG = 0x5E;
    public static final int FSDIV = 0x60;
    public static final int FSADD = 0x62;
    public static final int FSMUL = 0x63;
    public static final int FDDIV = 0x64;
    public static final int FDADD = 0x66;
    public static final int FDMUL = 0x67;
    public static final int FSSUB = 0x68;
    public static final int FDSUB = 0x6C;

    // ---- operand formats (source specifier, command word bits 12..10) ----

    /** Long integer, 4 bytes. */
    public static final int FORMAT_L = 0;
    /** Single, 4 bytes. */
    public static final int FORMAT_S = 1;
    /** Extended, 12 bytes. */
    public static final int FORMAT_X = 2;
    /** Packed decimal, 12 bytes, static k factor on output. */
    public static final int FORMAT_P = 3;
    /** Word integer, 2 bytes. */
    public static final int FORMAT_W = 4;
    /** Double, 8 bytes. */
    public static final int FORMAT_D = 5;
    /** Byte integer, 1 byte. */
    public static final int FORMAT_B = 6;
    /** Packed decimal, 12 bytes, dynamic k factor (in a data register) on output. */
    public static final int FORMAT_P_DYNAMIC = 7;

    /** Bytes of an operand in {@code format} 0..7. */
    public static int formatBytes(int format) {
        return switch (format) {
            case FORMAT_L, FORMAT_S -> 4;
            case FORMAT_X, FORMAT_P, FORMAT_P_DYNAMIC -> 12;
            case FORMAT_W -> 2;
            case FORMAT_D -> 8;
            case FORMAT_B -> 1;
            default -> throw new IllegalArgumentException("format " + format);
        };
    }

    // ---- condition predicates (6-bit field; 0x20..0x3F reserved) ----

    /** Predicate names in code order: {@code PREDICATES[code]}; 0x10..0x1F signal BSUN on unordered. */
    public static final String[] PREDICATES = {
            "f", "eq", "ogt", "oge", "olt", "ole", "ogl", "or",
            "un", "ueq", "ugt", "uge", "ult", "ule", "ne", "t",
            "sf", "seq", "gt", "ge", "lt", "le", "gl", "gle",
            "ngle", "ngl", "nle", "nlt", "nge", "ngt", "sne", "st"
    };

    /** Bit of a predicate code that makes it signal BSUN when NAN is set. */
    public static final int PREDICATE_SIGNALING = 0x10;
}
