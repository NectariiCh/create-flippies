package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.BASE;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.errors;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.oneError;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.sym;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.u32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sections, layout, predefined symbols, the data base, several files, limits (spec 5.1). */
class AssemblerLayoutTest {

    @Test
    void sectionLayoutAndPredefinedSymbols() {
        AsmImage img = ok("""
                .text
                _start: nop
                        nop
                .section .rodata
                ro:     .byte 1, 2, 3
                .section .init_array,"aw"
                        .word _start
                .section .fini_array,"aw"
                        .word _start
                .data
                d:      .byte 9, 8, 7, 6, 5
                .bss
                b:      .space 8
                """);
        assertEquals(0x1000, sym(img, "__text_start"));
        assertEquals(0x1008, sym(img, "__text_end"));
        assertEquals(0x1010, sym(img, "__rodata_start"));
        assertEquals(0x1010, sym(img, "ro"));
        assertEquals(0x1020, sym(img, "__init_array_start"));
        assertEquals(0x1024, sym(img, "__init_array_end"));
        assertEquals(0x1030, sym(img, "__fini_array_start"));
        assertEquals(0x1034, sym(img, "__fini_array_end"));
        assertEquals(0x1040, sym(img, "__data_load_start"));
        assertEquals(0x1040, sym(img, "__data_start"));
        assertEquals(0x1040, sym(img, "d"));
        assertEquals(0x1045, sym(img, "__data_end"));
        assertEquals(0x1050, sym(img, "__bss_start"));
        assertEquals(0x1050, sym(img, "b"));
        assertEquals(0x1058, sym(img, "__bss_end"));
        assertEquals(0x1058, sym(img, "_end"));
        assertEquals(0x1050, img.bssStart());
        assertEquals(0x1058, img.bssEnd());
        assertEquals(0x1000, img.entry());
        // One segment, ending with data; bss is in no segment.
        List<AsmSegment> segs = img.segments();
        assertEquals(1, segs.size());
        assertEquals(0x1000, segs.get(0).address());
        assertEquals(0x45, segs.get(0).bytes().length);
        byte[] b = segs.get(0).bytes();
        assertEquals(0x1000, u32(b, 0x20));      // init_array entry
        assertEquals(9, b[0x40]);
    }

    @Test
    void sectionAlignmentFollowsLargestAlign() {
        AsmImage img = ok("nop\n.data\n.align 7\nd: .byte 1\n.bss\nz: .byte 0");
        assertEquals(0x1080, sym(img, "d"));
        assertEquals(0x1090, sym(img, "z"));
    }

    @Test
    void entryIsStartOrTextBase() {
        assertEquals(0x1004, ok("nop\n_start: nop").entry());
        assertEquals(0x1000, ok("nop\nmain: nop").entry());
    }

    @Test
    void sectionNameMapping() {
        AsmImage img = ok("""
                nop
                .section .rodata.str1.4,"aMS",@progbits,1
                s: .string "x"
                .section .srodata.cst4,"aM",@progbits,4
                c: .word 1
                .section .sdata,"aw"
                sd: .word 2
                .section .text.startup,"ax",@progbits
                t2: nop
                .section .sbss,"aw",@nobits
                sb: .word 0
                .section .bss.big,"aw",@nobits
                bb: .space 4
                """);
        assertEquals(0x1004, sym(img, "t2"));
        assertEquals(0x1010, sym(img, "s"));
        assertEquals(0x1012, sym(img, "c"));
        assertTrue(sym(img, "sd") >= 0x1020 && sym(img, "sd") < img.bssStart());
        assertTrue(sym(img, "sb") >= img.bssStart());
        assertEquals(sym(img, "sb") + 4, sym(img, "bb"));
    }

    @Test
    void droppedSectionsEmitNothing() {
        AsmImage img = ok("""
                nop
                .section .note.GNU-stack,"",@progbits
                .section .comment
                noted: .string "GCC"
                .section .debug_info,"",@progbits
                .word 1, 2, 3
                .section .custom,""
                .word 5
                .text
                nop
                """);
        assertEquals(8, img.segments().get(0).bytes().length);
        assertFalse(img.symbols().containsKey("noted"));
        oneError(".section .custom,\"aw\"", 1, "unsupported section '.custom'");
        // Dropped statements still count for syntax errors.
        oneError(".section .debug_line\n.word (1", 2, "missing ')'");
        // A label in a dropped section is not a symbol.
        oneError(".section .comment\nx: .byte 1\n.text\nj x", 4, "undefined symbol 'x'");
    }

    @Test
    void bssHoldsZerosOnly() {
        AsmImage img = ok(".bss\n.word 0\n.byte 0\n.space 7\n.zero 4\n.align 3\n.fill 2, 4, 0");
        assertTrue(img.segments().isEmpty());
        assertEquals(0x1000, img.bssStart());
        assertEquals(0x1018, img.bssEnd());
        oneError(".bss\n.word 1", 2, "nonzero data in .bss");
        oneError(".bss\n.space 4, 1", 2, "nonzero data in .bss");
    }

    @Test
    void commonSymbols() {
        AsmImage img = ok("""
                nop
                .bss
                z: .byte 0
                .comm buf, 64
                .lcomm small, 3
                .comm x, 10, 8
                .comm odd, 5
                """);
        long bss = img.bssStart();
        assertEquals(bss, sym(img, "z"));
        assertEquals(bss + 16, sym(img, "buf"));     // aligned 16
        assertEquals(bss + 80, sym(img, "small"));   // aligned 2
        assertEquals(bss + 88, sym(img, "x"));       // aligned 8
        assertEquals(bss + 100, sym(img, "odd"));    // aligned 4
        assertEquals(bss + 105, img.bssEnd());
        oneError("x: nop\n.comm x, 4", 2, "already defined");
    }

    @Test
    void dataBaseSplitsLoadAndRunAddresses() {
        AsmResult r = Rv32Assembler.assemble("""
                .text
                _start: la a0, d
                .rodata
                .byte 1
                .data
                d: .word 0x11223344
                .bss
                z: .space 16
                """, new AsmOptions(0x1000, 0x8000_0000, AsmOptions.DEFAULT_MAX_IMAGE_BYTES));
        assertTrue(r.ok(), r::toString);
        AsmImage img = r.image();
        List<AsmSegment> segs = img.segments();
        assertEquals(2, segs.size());
        assertEquals(0x1000, segs.get(0).address());
        assertEquals(0x24, segs.get(0).bytes().length);
        assertEquals(0x8000_0000, segs.get(1).address());
        assertEquals(4, segs.get(1).bytes().length);
        assertEquals(0x8000_0000L, sym(img, "d"));
        assertEquals(0x8000_0000L, sym(img, "__data_start"));
        assertEquals(0x1020, sym(img, "__data_load_start"));
        assertEquals(0x8000_0010L, sym(img, "z"));
        assertEquals(0x8000_0010, img.bssStart());
        assertEquals(0x8000_0020, img.bssEnd());
        byte[] rom = img.flatten(0x1000, 0xF000);
        assertEquals(0x11223344, u32(rom, 0x20));
        // la reaches the run address: auipc a0, 0x7FFFF; addi a0, a0, 0.
        assertEquals(0x7FFFF517, u32(rom, 0));
        assertEquals(0x00050513, u32(rom, 4));
    }

    @Test
    void flattenRejectsACutSegment() {
        AsmImage img = ok("nop\nnop");
        assertArrayEquals(new byte[] {0x13, 0, 0, 0, 0x13, 0, 0, 0, 0, 0}, img.flatten(0x1000, 10));
        assertEquals(0x13, img.flatten(0xFFC, 12)[4]);
        assertThrows(IllegalArgumentException.class, () -> img.flatten(0x1000, 4));
        assertEquals(4, img.flatten(0x2000, 4).length);
    }

    @Test
    void segmentsAreCopies() {
        AsmImage img = ok("nop");
        img.segments().get(0).bytes()[0] = 99;
        assertEquals(0x13, img.segments().get(0).bytes()[0]);
    }

    @Test
    void symbolsNearTheSignBoundary() {
        AsmResult r = Rv32Assembler.assemble("""
                x:  .word x
                    .word x - .
                y:  .word y - x
                    beq a0, a1, x
                """, AsmOptions.at(0x8000_0000));
        assertTrue(r.ok(), r::toString);
        byte[] b = r.image().flatten(0x8000_0000, 16);
        assertEquals(0x8000_0000, u32(b, 0));
        assertEquals(-4, u32(b, 4));
        assertEquals(8, u32(b, 8));
        assertEquals(0xFEB50AE3, u32(b, 12));                // beq a0, a1, -12
        assertEquals(2147483648L, r.image().symbols().get("x"));
        assertEquals(0x8000_0000, r.image().entry());
    }

    @Test
    void lineMap() {
        AsmImage img = ok("nop\nli a0, 0x12345678\n.word 5\nret");
        assertEquals(new AsmLine("<source>", 1), img.lineAt(0x1000));
        assertEquals(new AsmLine("<source>", 2), img.lineAt(0x1004));
        assertEquals(new AsmLine("<source>", 2), img.lineAt(0x1008));
        assertNull(img.lineAt(0x100C));
        assertEquals(new AsmLine("<source>", 4), img.lineAt(0x1010));
        assertNull(img.lineAt(0x1014));
        assertNull(img.lineAt(0xFFC));
    }

    @Test
    void imageLimits() {
        // Never allocate past the cap: errors, not OutOfMemoryError.
        oneError(".space 0x7FFFFFFF", 1, "image limit");
        oneError(".fill 0x40000000, 4, 0", 1, "image limit");
        oneError(".org 0x7FFF0000", 1, "image limit");
        oneError(".bss\n.space 0x7FFFFFFF", 2, "image limit");
        oneError(".comm big, 0x7FFFFFFF", 1, "image limit");
        AsmOptions small = new AsmOptions(0x1000, null, 1024);
        assertTrue(Rv32Assembler.assemble(".space 1024", small).ok());
        assertFalse(Rv32Assembler.assemble(".space 1025", small).ok());
        assertFalse(Rv32Assembler.assemble(".space 1000\n.data\n.space 100", small).ok());
        // Past the 32-bit address space.
        assertFalse(Rv32Assembler.assemble(".space 0x2000", AsmOptions.at(0xFFFF_F000)).ok());
    }

    @Test
    void severalFiles() {
        AsmResult r = Rv32Assembler.assemble(List.of(
                new AsmSource("a.s", """
                        .globl _start
                        _start:
                        loop: call func
                        .L1:  j loop
                        1:    j 1b
                        """),
                new AsmSource("b.s", """
                        .globl func
                        func:
                        loop: nop
                        .L1:  j loop
                        1:    ret
                        """)), AsmOptions.at(BASE));
        assertTrue(r.ok(), r::toString);
        AsmImage img = r.image();
        assertEquals(0x1010, sym(img, "func"));
        assertEquals(0x1000, sym(img, "_start"));
        assertEquals(0x1000, sym(img, "a.s:loop"));
        assertEquals(0x1010, sym(img, "b.s:loop"));
        assertFalse(img.symbols().containsKey("loop"));
        assertFalse(img.symbols().keySet().stream().anyMatch(k -> k.contains(".L1")));
        byte[] b = img.flatten(0x1000, 0x1C);
        assertEquals(0x00000097, u32(b, 0));          // auipc ra, 0
        assertEquals(0x010080E7, u32(b, 4));          // jalr ra, 16(ra)
        assertEquals(0xFF9FF06F, u32(b, 8));          // j -8 (a.s loop)
        assertEquals(0x0000006F, u32(b, 0xC));          // 1: j 1b in a.s
        assertEquals(0xFFDFF06F, u32(b, 0x14));         // j -4 (b.s loop)
        assertEquals(0x00008067, u32(b, 0x18));
        assertEquals(new AsmLine("b.s", 3), img.lineAt(0x1010));
    }

    @Test
    void globalDefinedTwiceNamesBothFiles() {
        AsmResult r = Rv32Assembler.assemble(List.of(
                new AsmSource("a.s", ".globl f\nf: nop"),
                new AsmSource("b.s", "nop\n.globl f\nf: nop")), AsmOptions.at(BASE));
        assertFalse(r.ok());
        AsmDiagnostic d = r.errors().get(0);
        assertEquals("b.s", d.fileName());
        assertEquals(3, d.line());
        assertEquals("global symbol 'f' already defined in a.s line 2", d.message());
    }

    @Test
    void fileLocalSymbolsDoNotLeak() {
        AsmResult r = Rv32Assembler.assemble(List.of(
                new AsmSource("a.s", "j helper"),
                new AsmSource("b.s", "helper: ret")), AsmOptions.at(BASE));
        assertFalse(r.ok());
        assertEquals("a.s", r.errors().get(0).fileName());
        assertEquals("undefined symbol 'helper'", r.errors().get(0).message());
    }

    @Test
    void weakSymbols() {
        AsmImage img = ok(".weak hook\n.word hook\ncall hook");
        assertEquals(0, u32(img.segments().get(0).bytes(), 0));
        // A weak definition yields to a strong one in another file.
        AsmResult r = Rv32Assembler.assemble(List.of(
                new AsmSource("a.s", ".weak f\nf: nop\n.word f"),
                new AsmSource("b.s", ".globl f\nf: nop")), AsmOptions.at(BASE));
        assertTrue(r.ok(), r::toString);
        assertEquals(0x1008, sym(r.image(), "f"));
    }

    @Test
    void emptySourceIsAnEmptyImage() {
        AsmImage img = ok("");
        assertTrue(img.segments().isEmpty());
        assertEquals(0x1000, img.entry());
        assertTrue(Rv32Assembler.assemble(List.of(), AsmOptions.at(0)).ok());
    }

    @Test
    void sizeMismatchesAreNotPossible() {
        // li with a .set that changes later: the first pass value is the one used for sizing.
        AsmImage img = ok(".set v, 1\nli a0, v\n.set v, 0x12345\nli a1, v");
        assertEquals(12, img.segments().get(0).bytes().length);
        assertTrue(errors(".equ big, 0x100000000\nli a0, big").get(0).message().contains("32 bits"));
    }
}
