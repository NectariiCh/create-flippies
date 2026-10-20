package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.BASE;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.assertNoInternal;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.sym;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.u32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiler output, determinism, speed, and hostile input (spec 5.1, 7.4, 8.2). */
class AssemblerRobustnessTest {

    /** In the shape of {@code riscv32-unknown-elf-gcc -O2 -S} output. */
    static final String GCC_OUTPUT = """
            	.file	"main.c"
            	.option nopic
            	.attribute arch, "rv32i2p1_m2p0"
            	.attribute unaligned_access, 0
            	.attribute stack_align, 16
            	.text
            .Ltext0:
            	.cfi_sections	.debug_frame
            	.align	2
            	.globl	main
            	.type	main, @function
            main:
            .LFB0:
            	.file 1 "main.c"
            	.loc 1 4 1
            	.cfi_startproc
            	addi	sp,sp,-16
            	.cfi_def_cfa_offset 16
            	sw	ra,12(sp)
            	.cfi_offset 1, -4
            	lui	a5,%hi(.LC0)
            	addi	a0,a5,%lo(.LC0)
            	call	puts
            	lui	a5,%hi(counter)
            	lw	a4,%lo(counter)(a5)
            	addi	a4,a4,1
            	sw	a4,%lo(counter)(a5)
            	li	a0,0
            	lw	ra,12(sp)
            	.cfi_restore 1
            	addi	sp,sp,16
            	.cfi_def_cfa_offset 0
            	jr	ra
            	.cfi_endproc
            .LFE0:
            	.size	main, .-main
            	.section	.rodata.str1.4,"aMS",@progbits,1
            	.align	2
            .LC0:
            	.string	"hello"
            	.section	.text.startup,"ax",@progbits
            	.align	2
            	.globl	puts
            	.type	puts, @function
            puts:
            	ret
            	.size	puts, .-puts
            	.globl	counter
            	.section	.sdata,"aw"
            	.align	2
            	.type	counter, @object
            	.size	counter, 4
            counter:
            	.word	42
            	.local	buf
            	.comm	buf,64,4
            	.section	.init_array,"aw"
            	.align	2
            	.word	main
            	.text
            .Letext0:
            	.section	.debug_info,"",@progbits
            .Ldebug_info0:
            	.4byte	0x5e
            	.2byte	0x5
            	.byte	0x1
            	.section	.debug_abbrev,"",@progbits
            	.uleb128 0x1
            	.ident	"GCC: (g2ee5e430018) 13.2.0"
            	.section	.note.GNU-stack,"",@progbits
            """;

    @Test
    void gccOutputAssemblesCleanly() {
        AsmResult r = Rv32Assembler.assemble(GCC_OUTPUT, AsmOptions.at(BASE));
        assertTrue(r.ok(), r::toString);
        assertTrue(r.diagnostics().isEmpty(), r::toString);
        AsmImage img = r.image();
        assertEquals(BASE, sym(img, "main"));
        assertEquals(42, u32(img.segments().get(0).bytes(), (int) (sym(img, "counter") - BASE)));
        assertEquals(BASE, u32(img.segments().get(0).bytes(), (int) (sym(img, "__init_array_start") - BASE)));
        assertTrue(sym(img, "buf") >= img.bssStart());
        assertEquals(0, sym(img, "buf") % 4);
    }

    @Test
    void deterministic() {
        AsmImage a = ok(GCC_OUTPUT), b = ok(GCC_OUTPUT);
        assertArrayEquals(a.segments().get(0).bytes(), b.segments().get(0).bytes());
        assertEquals(a.symbols(), b.symbols());
    }

    @Test
    void tenThousandLinesAreFast() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("l").append(i).append(": addi a0, a0, ").append(i % 2000).append('\n');
            sb.append("    lw a1, 8(sp)\n");
            sb.append("    beq a0, a1, l").append(i).append('\n');
            sb.append("    li t0, 0x12345678\n");
            sb.append("    .word l").append(i).append(" + 4\n");
        }
        String src = sb.toString();
        ok(src);   // warm up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            AsmResult r = Rv32Assembler.assemble(src, AsmOptions.at(BASE));
            best = Math.min(best, System.nanoTime() - t0);
            assertTrue(r.ok());
        }
        assertTrue(best < 200_000_000L, "10 000 lines took " + best / 1_000_000 + " ms");
    }

    private static final String[] VOCAB = {
            "add", "addi", "li", "la", "call", "j", "beq", "lw", "sw", "csrr", "fence", "jalr", "ret",
            ".word", ".byte", ".ascii", ".space", ".fill", ".align", ".org", ".equ", ".set", ".section",
            ".text", ".data", ".bss", ".globl", ".comm", ".uleb128", ".sleb128", ".option", ".weak",
            "a0", "x31", "sp", "zero", "mstatus", "x99", "loop", "1b", "1f", "1", ".", ".L1",
            "0", "-1", "2047", "0x7FFFFFFF", "0xFFFFFFFFFFFFFFFF", "'a'", "\"str\"", "%hi", "%lo", "%pcrel_lo",
            "(", ")", ",", ":", "+", "-", "*", "/", "%", "<<", ">>", "&&", "||", "!", "~", "=", "==",
            "\n", "\n", "\n", ";", "#", "/*", "*/", "@progbits", "\"aw\"", "rvc", "\\", "`", "\"", "'"
    };

    @Test
    void randomTokenSoupNeverThrows() {
        Random rnd = new Random(12345);
        for (int n = 0; n < 1500; n++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(120);
            for (int i = 0; i < len; i++)
                sb.append(VOCAB[rnd.nextInt(VOCAB.length)]).append(rnd.nextInt(4) == 0 ? "" : " ");
            check(sb.toString());
        }
    }

    @Test
    void mutatedSourcesNeverThrow() {
        Random rnd = new Random(777);
        String seed = GCC_OUTPUT + """
                1: li a0, X
                   la a1, 1b
                   .equ X, 0x12345
                   bnez a0, 1b
                   .sleb128 -5
                   .org . + 4
                   .p2align 3
                """;
        for (int n = 0; n < 1500; n++) {
            StringBuilder sb = new StringBuilder(seed);
            int edits = 1 + rnd.nextInt(8);
            for (int e = 0; e < edits; e++) {
                int at = rnd.nextInt(sb.length() + 1);
                switch (rnd.nextInt(3)) {
                    case 0 -> {
                        if (at < sb.length())
                            sb.deleteCharAt(at);
                    }
                    case 1 -> sb.insert(at, (char) (rnd.nextInt(96) + 32));
                    default -> sb.insert(at, VOCAB[rnd.nextInt(VOCAB.length)]);
                }
            }
            check(sb.toString());
        }
    }

    @Test
    void randomBytesNeverThrow() {
        Random rnd = new Random(99);
        for (int n = 0; n < 300; n++) {
            char[] c = new char[rnd.nextInt(400)];
            for (int i = 0; i < c.length; i++)
                c[i] = (char) rnd.nextInt(rnd.nextBoolean() ? 128 : 0x3000);
            check(new String(c));
        }
    }

    private static void check(String src) {
        AsmResult r = Rv32Assembler.assemble(List.of(new AsmSource("f.s", src), new AsmSource("g.s", src)),
                AsmOptions.at(BASE));
        assertNoInternal(r);
        assertTrue(r.ok() == (r.image() != null));
    }
}
