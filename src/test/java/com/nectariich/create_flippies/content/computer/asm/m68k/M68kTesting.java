package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmDiagnostic;
import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Helpers for the 68k assembler tests: assemble at 0x1000 and read big-endian words back. */
final class M68kTesting {

    static final int BASE = 0x1000;

    private M68kTesting() {
    }

    static AsmResult asm(M68kTarget target, String src) {
        return M68kAssembler.assemble(src, AsmOptions.at(BASE), target);
    }

    static AsmImage ok(M68kTarget target, String src) {
        AsmResult r = asm(target, src);
        if (!r.ok())
            fail("expected success:\n" + src + "\n" + r);
        assertNoInternal(r);
        return r.image();
    }

    /** The text section's bytes. */
    static byte[] text(M68kTarget target, String src) {
        AsmImage img = ok(target, src);
        int end = (int) img.symbol("__text_end").getAsLong();
        if (img.segments().isEmpty())
            return new byte[0];
        return Arrays.copyOf(img.segments().get(0).bytes(), end - BASE);
    }

    static int[] words(M68kTarget target, String src) {
        byte[] b = text(target, src);
        assertEquals(0, b.length % 2, "odd text length");
        int[] w = new int[b.length / 2];
        for (int i = 0; i < w.length; i++)
            w[i] = (b[2 * i] & 0xFF) << 8 | (b[2 * i + 1] & 0xFF);
        return w;
    }

    /** Asserts the 68040 encoding of {@code src}. */
    static void check(String src, int... expected) {
        check(M68kTarget.M68040, src, expected);
    }

    /** Asserts the 68000 encoding (and the same on the 68040, which runs every 68000 instruction). */
    static void check000(String src, int... expected) {
        check(M68kTarget.M68000, src, expected);
        check(M68kTarget.M68040, src, expected);
    }

    static void check(M68kTarget target, String src, int... expected) {
        int[] actual = words(target, src);
        if (!Arrays.equals(expected, actual))
            fail("for " + target + ":\n" + src + "\nexpected " + hex(expected) + "\nbut got  " + hex(actual));
    }

    static String hex(int... w) {
        List<String> s = new ArrayList<>();
        for (int x : w)
            s.add(String.format("%04X", x));
        return s.toString();
    }

    static List<AsmDiagnostic> errors(M68kTarget target, String src) {
        AsmResult r = asm(target, src);
        assertFalse(r.ok(), () -> "expected errors for:\n" + src);
        assertNoInternal(r);
        return r.errors();
    }

    /** Asserts exactly one error, at {@code line}, whose message contains {@code text}. */
    static AsmDiagnostic oneError(M68kTarget target, String src, int line, String text) {
        List<AsmDiagnostic> e = errors(target, src);
        assertEquals(1, e.size(), () -> "expected one error, got " + e);
        AsmDiagnostic d = e.get(0);
        assertEquals(line, d.line(), () -> "wrong line: " + d);
        assertTrue(d.message().contains(text), () -> "message '" + d.message() + "' lacks '" + text + "'");
        return d;
    }

    static void assertNoInternal(AsmResult r) {
        for (AsmDiagnostic d : r.diagnostics())
            assertFalse(d.message().contains("internal"), () -> "internal error: " + d);
    }
}
