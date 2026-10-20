package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Helpers shared by the assembler tests: assemble at 0x1000 and read words or errors back. */
public final class AsmTesting {

    public static final int BASE = 0x1000;

    private AsmTesting() {
    }

    public static AsmResult asm(String src) {
        return Rv32Assembler.assemble(src, AsmOptions.at(BASE));
    }

    /** Assembles and asserts success (no errors), returning the image. */
    public static AsmImage ok(String src) {
        AsmResult r = asm(src);
        if (!r.ok())
            fail("expected success:\n" + r);
        assertNoInternal(r);
        return r.image();
    }

    /** The little-endian words of the text section starting at {@link #BASE}. */
    public static int[] words(String src) {
        AsmImage img = ok(src);
        int textEnd = (int) (long) img.symbol("__text_end").getAsLong();
        byte[] b = img.flatten(BASE, textEnd - BASE);
        return toWords(b);
    }

    public static int[] toWords(byte[] b) {
        int[] w = new int[b.length / 4];
        for (int i = 0; i < w.length; i++)
            w[i] = (b[4 * i] & 0xFF) | (b[4 * i + 1] & 0xFF) << 8 | (b[4 * i + 2] & 0xFF) << 16 | (b[4 * i + 3] & 0xFF) << 24;
        return w;
    }

    /** The single word one instruction assembles to. */
    public static int word(String insn) {
        int[] w = words(insn);
        assertEquals(1, w.length, () -> "expected one word for '" + insn + "'");
        return w[0];
    }

    public static void assertWords(String src, int... expected) {
        int[] actual = words(src);
        if (!java.util.Arrays.equals(expected, actual))
            fail("for:\n" + src + "\nexpected " + hex(expected) + "\nbut got  " + hex(actual));
    }

    public static String hex(int... w) {
        List<String> s = new ArrayList<>();
        for (int x : w)
            s.add(String.format("0x%08X", x));
        return s.toString();
    }

    /** Assembles, asserts failure, returns the errors. */
    public static List<AsmDiagnostic> errors(String src) {
        AsmResult r = asm(src);
        assertFalse(r.ok(), () -> "expected errors for:\n" + src);
        assertTrue(r.image() == null);
        assertNoInternal(r);
        return r.errors();
    }

    /** Asserts exactly one error, at {@code line}, whose message contains {@code text}. */
    public static AsmDiagnostic oneError(String src, int line, String text) {
        List<AsmDiagnostic> e = errors(src);
        assertEquals(1, e.size(), () -> "expected one error, got " + e);
        AsmDiagnostic d = e.get(0);
        assertEquals(line, d.line(), () -> "wrong line: " + d);
        assertTrue(d.message().contains(text), () -> "message '" + d.message() + "' lacks '" + text + "'");
        return d;
    }

    public static void assertNoInternal(AsmResult r) {
        for (AsmDiagnostic d : r.diagnostics())
            assertFalse(d.message().contains("internal"), () -> "internal error: " + d);
    }

    public static long sym(AsmImage img, String name) {
        return img.symbol(name).orElseThrow(() -> new AssertionError("no symbol " + name));
    }

    public static int u32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }
}
