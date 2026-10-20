package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import org.junit.jupiter.api.Test;

/** Ordinary programs end to end (spec 8.4): calls with a stack, loops over memory, the M extension. */
class ProgramE2eTest {

    @Test
    void recursiveFibonacciAndFactorialUseTheStack() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    li   a0, 20
                    call fib
                    sw   a0, fib20, t0
                    li   a0, 12
                    call fact
                    sw   a0, fact12, t0
                    sw   sp, sp_after, t0
                    j    exit

                fib:                            # naive recursion: fib(n) = fib(n-1) + fib(n-2)
                    li   t0, 2
                    blt  a0, t0, 1f
                    addi sp, sp, -12
                    sw   ra, 8(sp)
                    sw   s0, 4(sp)
                    sw   s1, 0(sp)
                    mv   s0, a0
                    addi a0, s0, -1
                    call fib
                    mv   s1, a0
                    addi a0, s0, -2
                    call fib
                    add  a0, a0, s1
                    lw   s1, 0(sp)
                    lw   s0, 4(sp)
                    lw   ra, 8(sp)
                    addi sp, sp, 12
                1:  ret

                fact:                           # fact(n) = n * fact(n-1)
                    li   t0, 1
                    bgt  a0, t0, 1f
                    li   a0, 1
                    ret
                1:  addi sp, sp, -8
                    sw   ra, 4(sp)
                    sw   a0, 0(sp)
                    addi a0, a0, -1
                    call fact
                    lw   t0, 0(sp)
                    mul  a0, a0, t0
                    lw   ra, 4(sp)
                    addi sp, sp, 8
                    ret

                .data
                .align 2
                fib20:    .word 0
                fact12:   .word 0
                sp_after: .word 0
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 20);
        assertCleanExit(m);
        assertEquals(6765, ramWord(m, program, "fib20"));
        assertEquals(479_001_600, ramWord(m, program, "fact12"));
        assertEquals(0x8010_0000, ramWord(m, program, "sp_after"), "the stack is balanced");
    }

    @Test
    void memcpyAndStrlen() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   a0, text
                    call strlen
                    mv   s0, a0
                    sw   s0, length, t0
                    la   a0, buffer
                    la   a1, text
                    addi a2, s0, 1              # with the terminator
                    call memcpy
                    la   a0, buffer2 + 3        # misaligned destination: the byte loop
                    la   a1, text
                    li   a2, 5
                    call memcpy
                    la   a0, buffer
                    call puts
                    li   a0, '|'
                    call putc
                    la   a0, buffer2 + 3
                    call puts
                    j    exit

                strlen:                         # a0 = string, returns its length
                    mv   t0, a0
                1:  lbu  t1, 0(t0)
                    beqz t1, 2f
                    addi t0, t0, 1
                    j    1b
                2:  sub  a0, t0, a0
                    ret

                memcpy:                         # a0 = dst, a1 = src, a2 = n; words when both aligned
                    mv   t0, a0
                    or   t1, a0, a1
                    andi t1, t1, 3
                    bnez t1, 2f
                1:  li   t1, 4
                    blt  a2, t1, 2f
                    lw   t2, 0(a1)
                    sw   t2, 0(t0)
                    addi a1, a1, 4
                    addi t0, t0, 4
                    addi a2, a2, -4
                    j    1b
                2:  beqz a2, 3f
                    lbu  t2, 0(a1)
                    sb   t2, 0(t0)
                    addi a1, a1, 1
                    addi t0, t0, 1
                    addi a2, a2, -1
                    j    2b
                3:  ret

                .section .rodata
                .align 2
                text: .asciz "The quick brown fox jumps over the lazy dog"
                .data
                .align 2
                length: .word 0
                .bss
                .align 4
                buffer:  .space 64
                buffer2: .space 16
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(43, ramWord(m, program, "length"));
        assertEquals("The quick brown fox jumps over the lazy dog|The q", com1(m));
    }

    @Test
    void mExtensionResults() {
        AsmImage program = assemble("""
                _start:
                    la   s0, results
                    li   a0, -7
                    li   a1, 3
                    li   a2, 0
                    li   a3, 0x80000000
                    li   a4, -1
                    mul    t0, a0, a1
                    sw     t0, 0(s0)
                    mulh   t0, a4, a4
                    sw     t0, 4(s0)
                    mulhu  t0, a4, a4
                    sw     t0, 8(s0)
                    mulhsu t0, a4, a4
                    sw     t0, 12(s0)
                    div    t0, a0, a1
                    sw     t0, 16(s0)
                    rem    t0, a0, a1
                    sw     t0, 20(s0)
                    divu   t0, a0, a1
                    sw     t0, 24(s0)
                    remu   t0, a0, a1
                    sw     t0, 28(s0)
                    div    t0, a0, a2           # by zero
                    sw     t0, 32(s0)
                    divu   t0, a0, a2
                    sw     t0, 36(s0)
                    rem    t0, a0, a2
                    sw     t0, 40(s0)
                    remu   t0, a0, a2
                    sw     t0, 44(s0)
                    div    t0, a3, a4           # overflow
                    sw     t0, 48(s0)
                    rem    t0, a3, a4
                    sw     t0, 52(s0)
                    li     t1, 0x12345678
                    li     t2, 0x9ABCDEF0
                    mulhu  t0, t1, t2
                    sw     t0, 56(s0)
                    mulh   t0, t1, t2
                    sw     t0, 60(s0)
                    csrr   t0, misa
                    sw     t0, 64(s0)
                    j      exit
                .data
                .align 2
                results: .space 68
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        int base = sym(program, "results");
        long a = 0x12345678L, b = 0x9ABCDEF0L;
        int[] expected = {
                -21,
                0,
                0xFFFF_FFFE,
                0xFFFF_FFFF,
                -2,
                -1,
                Integer.divideUnsigned(-7, 3),
                Integer.remainderUnsigned(-7, 3),
                -1,
                0xFFFF_FFFF,
                -7,
                -7,
                Integer.MIN_VALUE,
                0,
                (int) ((a * b) >>> 32),
                (int) ((a * (long) (int) b) >> 32),
                0x4000_1100,
        };
        for (int i = 0; i < expected.length; i++)
            assertEquals(expected[i], ramWord(m, base + 4 * i), "result " + i);
    }
}
