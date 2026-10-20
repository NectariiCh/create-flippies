package com.nectariich.create_flippies.content.computer.asm;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.BASE;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.assertWords;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.oneError;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shared directives (spec 5.1). */
class AssemblerDirectivesTest {

    /** The image's bytes from the text base. */
    private static byte[] bytes(String src) {
        return ok(src).segments().get(0).bytes();
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            out[i] = (byte) v[i];
        return out;
    }

    @Test
    void dataLittleEndian() {
        assertArrayEquals(b(1, 0xFF, 0xFF), bytes(".byte 1, -1, 255"));
        assertArrayEquals(b(0x34, 0x12, 0xFF, 0xFF), bytes(".half 0x1234, -1"));
        assertArrayEquals(b(0x34, 0x12, 0x78, 0x56), bytes(".short 0x1234\n.2byte 0x5678"));
        assertArrayEquals(b(0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0x80), bytes(".word 0x12345678\n.long 0x80000000"));
        assertArrayEquals(b(0xEF, 0xBE, 0xAD, 0xDE), bytes(".4byte 0xDEADBEEF"));
        assertArrayEquals(b(8, 7, 6, 5, 4, 3, 2, 1), bytes(".dword 0x0102030405060708"));
        assertArrayEquals(b(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), bytes(".quad -1"));
        assertArrayEquals(b(1, 0, 0, 0, 0, 0, 0, 0), bytes(".8byte 1"));
        // Range: signed or unsigned fits.
        oneError(".byte -129", 1, "does not fit in 1 byte");
        oneError(".half 65536", 1, "does not fit in 2 bytes");
        oneError(".word -0x80000001", 1, "does not fit in 4 bytes");
    }

    @Test
    void strings() {
        assertArrayEquals(b('a', 'b', '\n', '"'), bytes(".ascii \"ab\\n\\\"\""));
        assertArrayEquals(b('h', 'i', 0, 'x', 0), bytes(".asciz \"hi\", \"x\""));
        assertArrayEquals(b('o', 'k', 0), bytes(".string \"ok\""));
        assertArrayEquals(b(0101, 7, 0x41, 0), bytes(".ascii \"\\101\\a\\x41\\0\""));
        oneError(".ascii 5", 1, "expected a string");
    }

    @Test
    void spaceAndFill() {
        assertArrayEquals(b(0xAA, 0xAA, 0xAA, 0, 0, 1), bytes(".space 3, 0xAA\n.zero 2\n.byte 1"));
        assertArrayEquals(b(0, 0, 0, 0xFF), bytes(".skip 3\n.byte 0xFF"));
        assertArrayEquals(b(0x44, 0x33, 0x22, 0x11, 0x44, 0x33, 0x22, 0x11), bytes(".fill 2, 4, 0x11223344"));
        assertArrayEquals(b(0, 0, 0, 7), bytes(".fill 3\n.byte 7"));
        assertArrayEquals(b(5, 0, 5, 0), bytes(".fill 2, 2, 5"));
        oneError(".space -1", 1, "negative size");
        oneError(".space n\n.equ n, 4", 1, "must be a constant known at this point");
        oneError(".fill 1, 9, 0", 1, "'.fill' size 9 out of range");
        oneError(".space 1, 256", 1, "fill value 256 does not fit in a byte");
    }

    @Test
    void alignment() {
        // In text: NOP padding when the gap is whole instructions.
        assertWords("addi a0, a0, 1\n.align 4\naddi a0, a0, 2",
                0x00150513, 0x00000013, 0x00000013, 0x00000013, 0x00250513);
        assertWords("addi a0, a0, 1\n.p2align 3\naddi a0, a0, 2", 0x00150513, 0x00000013, 0x00250513);
        assertWords("addi a0, a0, 1\n.balign 8\naddi a0, a0, 2", 0x00150513, 0x00000013, 0x00250513);
        // In text, a gap that is not whole instructions is zeros.
        assertArrayEquals(b(1, 0, 0, 0, 0x13, 0, 0, 0), bytes(".byte 1\n.align 2\nnop"));
        // In data: zeros, or the fill byte.
        assertArrayEquals(b(1, 0, 0, 0, 2), ok(".data\n.byte 1\n.align 2\n.byte 2").segments().get(0).bytes());
        assertArrayEquals(b(1, 0xFF, 0xFF, 0xFF, 2), bytes(".byte 1\n.p2align 2, 0xFF\n.byte 2"));
        assertArrayEquals(b(1, 0xEE, 2), bytes(".byte 1\n.balign 2, 0xEE\n.byte 2"));
        // Maximum skip: no padding when it would exceed the limit.
        assertArrayEquals(b(1, 2), bytes(".byte 1\n.p2align 4, 0, 2\n.byte 2"));
        assertArrayEquals(b(1, 0, 0, 0, 2), bytes(".byte 1\n.p2align 2, , 3\n.byte 2"));
        oneError(".balign 3", 1, "not a power of two");
        oneError(".align 17", 1, "out of range");
    }

    @Test
    void equatesAndSet() {
        assertArrayEquals(b(1, 2, 5, 9), bytes(".set x, 1\n.byte x\n.set x, 2\n.byte x\ny = 5\n.byte y\nx = 9\n.byte x"));
        assertArrayEquals(b(3), bytes(".equ a, b + 1\n.equ b, 2\n.byte a"));
        // A forward reference sees the value at the end of the first pass.
        assertArrayEquals(b(7), bytes(".byte later\n.equ later, 7"));
        oneError(".equ 5, 1", 1, "expected a symbol name");
    }

    @Test
    void org() {
        assertArrayEquals(b(1, 0, 0, 0, 2), bytes(".byte 1\n.org 4\n.byte 2"));
        assertArrayEquals(b(1, 0, 2), bytes("s: .byte 1\n.org s + 2\n.byte 2"));
        // A boot sector shape: signature at offset 510.
        byte[] boot = bytes("nop\n.org 510\n.byte 0x55, 0xAA");
        assertEquals(512, boot.length);
        assertEquals(0x55, boot[510] & 0xFF);
        assertEquals(0xAA, boot[511] & 0xFF);
        oneError(".byte 1, 2\n.org 1", 2, "'.org' moves the location counter backwards");
        oneError(".org later\nlater:", 1, "must be a constant known at this point");
        oneError("x: nop\n.data\n.org x", 3, "not in the current section");
    }

    @Test
    void leb128() {
        assertArrayEquals(b(0x00, 0x7F, 0x80, 0x01, 0xE5, 0x8E, 0x26),
                bytes(".uleb128 0, 127, 128, 624485"));
        assertArrayEquals(b(0x7F, 0x80, 0x7F, 0xFF, 0x00, 0xE5, 0x8E, 0x26, 0x00, 0xC0, 0x00, 0xBF, 0x7F),
                bytes(".sleb128 -1, -128, 127, 624485, 0, 64, -65"));
        oneError(".uleb128 later\nlater:", 1, "must be a constant known at this point");
    }

    @Test
    void ignoredAndOptionDirectives() {
        AsmImage img = ok("""
                .file "x.c"
                .file 1 "x.c"
                .loc 1 2 3
                .ident "GCC"
                .type f, @function
                .size f, .-f
                .hidden f
                .attribute arch, "rv32i2p1_m2p0"
                .attribute 5, "rv32i"
                .option nopic
                .option norvc
                .option push
                .option pop
                .option relax
                .option norelax
                .option pic
                .cfi_startproc
                .cfi_def_cfa_offset 16
                .cfi_endproc
                .addrsig
                .addrsig_sym f
                f: nop
                """);
        assertEquals(4, img.segments().get(0).bytes().length);
        oneError(".frobnicate 1", 1, "unknown directive '.frobnicate'");
    }

    @Test
    void localDirectiveKeepsSymbolFileLocal() {
        AsmImage img = ok(".globl x\n.local x\nx: nop");
        assertEquals(BASE, img.symbols().get("x"));
    }

    @Test
    void commentsAndSeparators() {
        assertWords("""
                # a hash comment
                nop // a slash comment
                /* a block
                   comment */ nop ; nop
                addi a0, a0, 1 /* inline */ ; addi a0, a0, 2 # end
                """, 0x13, 0x13, 0x13, 0x00150513, 0x00250513);
        assertWords("a: b: c: nop\nj b", 0x13, 0xFFDFF06F);
    }
}
