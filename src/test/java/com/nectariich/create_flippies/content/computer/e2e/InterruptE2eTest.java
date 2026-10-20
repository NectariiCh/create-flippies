package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import org.junit.jupiter.api.Test;

/** Traps and interrupts end to end (spec 4.2 to 4.5, 8.4), timed against the machine's clock. */
class InterruptE2eTest {

    @Test
    void machineTimerFiresTenTimesOneMillisecondApart() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, handler
                    csrw mtvec, t0
                    li   s0, MTIMECMP
                    li   t0, MTIME
                    lw   s1, 0(t0)
                    sw   s1, start_us, t0
                    addi s1, s1, 1000
                    sw   zero, 4(s0)            # MTIMECMP_HI first, so no early match
                    sw   s1, 0(s0)              # MTIMECMP_LO = start + 1000 us
                    li   t0, 0x80
                    csrs mie, t0                # MTIE
                    csrsi mstatus, 8
                main:
                    wfi
                    lw   t0, ticks
                    li   t1, 10
                    blt  t0, t1, main
                    rdcycle t0
                    sw   t0, end_cycle, t1
                    j    exit

                .align 2
                handler:
                    addi sp, sp, -8
                    sw   t0, 0(sp)
                    sw   t1, 4(sp)
                    lw   t0, ticks
                    addi t0, t0, 1
                    sw   t0, ticks, t1
                    li   t1, MTIMECMP
                    lw   t0, 0(t1)
                    addi t0, t0, 1000           # re-arm on the 1 ms grid
                    sw   t0, 0(t1)
                    lw   t1, 4(sp)
                    lw   t0, 0(sp)
                    addi sp, sp, 8
                    mret

                .data
                .align 2
                ticks:     .word 0
                start_us:  .word 0
                end_cycle: .word 0
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(10, ramWord(m, program, "ticks"));
        long tenth = (ramWord(m, program, "start_us") + 10_000L) * (HZ / 1_000_000);
        long end = Integer.toUnsignedLong(ramWord(m, program, "end_cycle"));
        assertTrue(end >= tenth && end < tenth + 100, "10th interrupt seen at cycle " + end + ", due " + tenth);
        // about 10 ms of emulated time in total (plus draining an empty UART)
        long total = m.clock().now();
        assertTrue(total >= tenth && total < tenth + 1000, "clock " + total);
    }

    @Test
    void busyLoopIsInterruptedWithinOneInstructionOfTheDeadline() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, handler
                    csrw mtvec, t0
                    li   s0, MTIMECMP
                    li   t0, MTIME
                    lw   s1, 0(t0)
                    sw   s1, start_us, t0
                    addi s1, s1, 1000
                    sw   zero, 4(s0)
                    sw   s1, 0(s0)
                    li   t0, 0x80
                    csrs mie, t0
                    csrsi mstatus, 8
                    la   s2, counter
                spin:
                    lw   t0, 0(s2)
                    addi t0, t0, 1
                    sw   t0, 0(s2)
                spin_end:
                    j    spin

                .align 2
                handler:
                    csrr t0, mcycle
                    sw   t0, irq_cycle, t1
                    csrr t0, mepc
                    sw   t0, irq_mepc, t1
                    j    exit

                .data
                .align 2
                counter:   .word 0
                start_us:  .word 0
                irq_cycle: .word 0
                irq_mepc:  .word 0
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        long due = (ramWord(m, program, "start_us") + 1000L) * (HZ / 1_000_000);
        long seen = Integer.toUnsignedLong(ramWord(m, program, "irq_cycle"));
        // at most one loop instruction (3 cycles for the jump) past the due cycle, plus 2 for trap entry
        assertTrue(seen >= due && seen <= due + 5, "interrupt at cycle " + seen + ", due " + due);
        int mepc = ramWord(m, program, "irq_mepc");
        assertTrue(mepc >= sym(program, "spin") && mepc <= sym(program, "spin_end"),
                "mepc 0x" + Integer.toHexString(mepc) + " inside the spin loop");
        assertTrue(ramWord(m, program, "counter") > 100, "the loop ran before the interrupt");
    }

    @Test
    void intervalTimerThroughTheInterruptControllerWithClaim() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, handler
                    csrw mtvec, t0
                    li   t0, PIC
                    li   t1, 1 << 7
                    sw   t1, 0x04(t0)           # ENABLE line 7
                    li   t0, 0x800
                    csrs mie, t0                # MEIE
                    csrsi mstatus, 8
                    li   s0, TIMER
                    li   t0, 500
                    sw   t0, 0x08(s0)           # PERIOD 500 us
                    li   t0, MTIME
                    lw   t0, 0(t0)
                    sw   t0, start_us, t1
                    li   t0, 3
                    sw   t0, 0x0C(s0)           # CTRL: ENABLE | IRQ_ENABLE
                main:
                    wfi
                    lw   t0, count
                    li   t1, 4
                    blt  t0, t1, main
                    sw   zero, 0x0C(s0)         # stop the timer
                    j    exit

                .align 2
                handler:
                    addi sp, sp, -12
                    sw   t0, 0(sp)
                    sw   t1, 4(sp)
                    sw   t2, 8(sp)
                    li   t0, PIC
                    lw   t1, 0x08(t0)           # CLAIM
                    lw   t0, count
                    slli t2, t0, 2
                    la   t0, claims
                    add  t0, t0, t2
                    sw   t1, 0(t0)
                    li   t1, MTIME
                    lw   t1, 0(t1)
                    sw   t1, 16(t0)             # times[count]
                    li   t0, TIMER
                    li   t1, 1
                    sw   t1, 0x10(t0)           # STATUS: W1C EXPIRED drops line 7
                    lw   t0, count
                    addi t0, t0, 1
                    sw   t0, count, t1
                    lw   t2, 8(sp)
                    lw   t1, 4(sp)
                    lw   t0, 0(sp)
                    addi sp, sp, 12
                    mret

                .data
                .align 2
                count:    .word 0
                start_us: .word 0
                claims:   .word 0, 0, 0, 0
                times:    .word 0, 0, 0, 0
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(4, ramWord(m, program, "count"));
        int start = ramWord(m, program, "start_us");
        for (int k = 0; k < 4; k++) {
            assertEquals(7, ramWord(m, sym(program, "claims") + 4 * k), "CLAIM in interrupt " + k);
            int t = ramWord(m, sym(program, "times") + 4 * k);
            long late = t - (start + 500L * (k + 1));
            assertTrue(late >= 0 && late <= 2, "interrupt " + k + " at " + t + " us, " + late + " us late");
        }
    }

    @Test
    void illegalInstructionAndEcallTrapToMtvec() {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, handler
                    csrw mtvec, t0
                bad1:
                    .word 0xFFFFFFFF
                bad2:
                    unimp
                bad3:
                    ecall
                    j    exit

                .align 2
                handler:
                    la   a0, s_cause
                    call puts
                    csrr a0, mcause
                    call puthex
                    la   a0, s_val
                    call puts
                    csrr a0, mtval
                    call puthex
                    la   a0, s_epc
                    call puts
                    csrr a0, mepc
                    call puthex
                    li   a0, '\\n'
                    call putc
                    csrr t0, mepc
                    addi t0, t0, 4
                    csrw mepc, t0
                    mret
                s_cause: .asciz "mcause="
                s_val:   .asciz " mtval="
                s_epc:   .asciz " mepc="
                """);
        Machine m = boot(program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        String expected = String.format("mcause=00000002 mtval=FFFFFFFF mepc=%08X\n", sym(program, "bad1"))
                + String.format("mcause=00000002 mtval=C0001073 mepc=%08X\n", sym(program, "bad2"))
                + String.format("mcause=0000000B mtval=00000000 mepc=%08X\n", sym(program, "bad3"));
        assertEquals(expected, com1(m));
    }

    @Test
    void trapWithoutHandlerHaltsTheMachine() {
        Machine m = boot("""
                _start:
                    li   t0, 0x40000000         # unmapped
                    lw   t1, 0(t0)
                """);
        runTicks(m, 2);
        assertEquals(MachineState.HALTED, m.state());
        assertTrue(m.cpu().haltReason().contains("mcause 5"), m.cpu().haltReason());
    }
}
