package com.nectariich.create_flippies.content.computer.asm.m68k;

import java.util.Locale;
import java.util.Map;

/**
 * Register names of the 68000 and 68040 (spec 10.7.2), case-insensitive. General registers are
 * numbered 0..7 for D0..D7 and 8..15 for A0..A7 ({@code sp} is A7), the numbering of MOVEM masks
 * and of the D/A plus register fields of extension words.
 */
final class M68kRegisters {

    private M68kRegisters() {
    }

    /** Special register kinds returned by {@link #special}. */
    static final int SR = 1;
    static final int CCR = 2;
    static final int USP = 3;
    static final int PC = 4;
    static final int ZPC = 5;

    /** MOVEC control register numbers (spec 10.2.3), 68040 only. */
    private static final Map<String, Integer> CONTROL = Map.ofEntries(
            Map.entry("sfc", 0x000), Map.entry("dfc", 0x001), Map.entry("cacr", 0x002),
            Map.entry("tc", 0x003), Map.entry("itt0", 0x004), Map.entry("itt1", 0x005),
            Map.entry("dtt0", 0x006), Map.entry("dtt1", 0x007), Map.entry("usp", 0x800),
            Map.entry("vbr", 0x801), Map.entry("msp", 0x803), Map.entry("isp", 0x804),
            Map.entry("mmusr", 0x805), Map.entry("urp", 0x806), Map.entry("srp", 0x807));

    /** FPU control register select bits (FMOVE and FMOVEM control form, bits 12..10). */
    static final int FPCR = 4;
    static final int FPSR = 2;
    static final int FPIAR = 1;

    /** D0..D7 as 0..7, A0..A7 and SP as 8..15, or -1. */
    static int general(String name) {
        if (name == null)
            return -1;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("sp"))
            return 15;
        if (n.length() == 2 && (n.charAt(0) == 'd' || n.charAt(0) == 'a')
                && n.charAt(1) >= '0' && n.charAt(1) <= '7')
            return (n.charAt(0) == 'a' ? 8 : 0) + n.charAt(1) - '0';
        return -1;
    }

    /** {@code sr}, {@code ccr}, {@code usp}, {@code pc} or {@code zpc} as the constants above, or 0. */
    static int special(String name) {
        if (name == null)
            return 0;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "sr" -> SR;
            case "ccr" -> CCR;
            case "usp" -> USP;
            case "pc" -> PC;
            case "zpc" -> ZPC;
            default -> 0;
        };
    }

    /** The MOVEC number of a control register name, or -1. */
    static int control(String name) {
        if (name == null)
            return -1;
        Integer v = CONTROL.get(name.toLowerCase(Locale.ROOT));
        return v == null ? -1 : v;
    }

    /** FP0..FP7 as 0..7, or -1. */
    static int fp(String name) {
        if (name == null)
            return -1;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.length() == 3 && n.startsWith("fp") && n.charAt(2) >= '0' && n.charAt(2) <= '7')
            return n.charAt(2) - '0';
        return -1;
    }

    /** {@link #FPCR}, {@link #FPSR} or {@link #FPIAR} for a control register name, or 0. */
    static int fpControl(String name) {
        if (name == null)
            return 0;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "fpcr" -> FPCR;
            case "fpsr" -> FPSR;
            case "fpiar" -> FPIAR;
            default -> 0;
        };
    }

    /**
     * An index register as written in an indexed address: {@code d0}, {@code a1.l},
     * {@code zd2.w}. {@code reg} is 0..15; {@code suppressed} for the {@code z} forms.
     */
    record Index(int reg, boolean isLong, boolean suppressed) { }

    /** Parses an index register name with an optional {@code .w}/{@code .l} suffix, or null. */
    static Index index(String name) {
        if (name == null)
            return null;
        String n = name.toLowerCase(Locale.ROOT);
        boolean isLong = false;
        if (n.endsWith(".l") || n.endsWith(".w")) {
            isLong = n.endsWith(".l");
            n = n.substring(0, n.length() - 2);
        }
        boolean suppressed = false;
        if (n.length() == 3 && n.charAt(0) == 'z' && (n.charAt(1) == 'd' || n.charAt(1) == 'a')) {
            suppressed = true;
            n = n.substring(1);
        }
        int r = general(n);
        return r < 0 ? null : new Index(r, isLong, suppressed);
    }

    /** ZA0..ZA7 as 0..7 (a suppressed base register), or -1. */
    static int suppressedBase(String name) {
        if (name == null)
            return -1;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.length() == 3 && n.startsWith("za") && n.charAt(2) >= '0' && n.charAt(2) <= '7')
            return n.charAt(2) - '0';
        return -1;
    }

    /** Whether a name is any register this assembler knows (so it is never a symbol). */
    static boolean isRegister(String name) {
        return general(name) >= 0 || special(name) != 0 || control(name) >= 0 || fp(name) >= 0
                || fpControl(name) != 0 || index(name) != null || suppressedBase(name) >= 0;
    }

    /** The name of general register 0..15, for messages. */
    static String name(int reg) {
        return (reg < 8 ? "d" : "a") + (reg & 7);
    }
}
