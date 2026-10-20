package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import org.junit.jupiter.api.Test;

/** Cycle targets and the host-time budget (spec 1.4, 8.4) on a real CPU running a busy loop. */
class BudgetE2eTest {

    /** Counts in RAM forever: lw, addi, sw (fast path, 2 + 1 + 1 cycles) and j (3 cycles). */
    private static final String COUNTER = """
            _start:
                la   s0, counter
            1:  lw   t0, 0(s0)
                addi t0, t0, 1
                sw   t0, 0(s0)
                j    1b
            .data
            .align 2
            counter: .word 0
            """;

    @Test
    void runStopsAtTheCycleTargetWithinOneInstruction() {
        Machine m = boot(COUNTER);
        Cpu cpu = m.cpu();
        long target = 0;
        for (int step : new int[] { 1, 2, 3, 5, 7, 100, 1000, 12_345, 999_999 }) {
            target = Math.max(target, m.clock().now()) + step;
            assertEquals(Cpu.Exit.REACHED, cpu.run(target, Long.MAX_VALUE));
            long over = m.clock().now() - target;
            // the longest instruction in the loop is the 3-cycle jump
            assertTrue(over >= 0 && over <= 2, "stopped " + over + " cycles past " + target);
        }
    }

    @Test
    void longRunsKeepExactTime() {
        // the CPU may stop a cycle or two past a tick's end; the next tick is shortened to match,
        // so emulated time never drifts from clockHz * elapsed game time
        Machine m = boot(COUNTER);
        for (int i = 1; i <= 40; i++) {
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            long over = m.clock().now() - i * TICK_CYCLES;
            assertTrue(over >= 0 && over <= 2, "tick " + i + " ended " + over + " cycles off");
        }
        assertEquals(1.0, m.speed(), 1e-5);
    }

    @Test
    void tinyHostBudgetDilatesTimeButKeepsMakingProgress() {
        AsmImage program = assemble(COUNTER);
        Machine m = boot(program, null);
        int counterAddress = sym(program, "counter");
        int last = 0;
        for (int i = 0; i < 10; i++) {
            m.runTick(Machine.TICK_NANOS, 0);
            int now = ramWord(m, counterAddress);
            assertTrue(now > last, "progress in tick " + i);
            // a passed deadline returns within 4096 instructions: at most 1024 loop iterations
            assertTrue(now - last <= 1024 + 1, "tick " + i + " ran " + (now - last) + " iterations");
            last = now;
        }
        assertEquals(MachineState.RUNNING, m.state());
        assertTrue(m.speed() < 0.05, "speed " + m.speed());
        assertTrue(m.clock().now() < 10 * TICK_CYCLES / 10, "emulated time fell behind: " + m.clock().now());

        // a normal budget afterwards runs whole ticks again
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertTrue(ramWord(m, counterAddress) - last > 50_000);
    }

    @Test
    void pastDeadlineReturnsWithConsistentState() {
        Machine m = boot(COUNTER);
        long before = m.clock().now();
        Cpu.Exit exit = m.cpu().run(Long.MAX_VALUE / 2, System.nanoTime() - 1);
        assertEquals(Cpu.Exit.DEADLINE, exit);
        long instructions = m.cpu().instructionsRetired();
        assertTrue(instructions > 0 && instructions <= 4096, instructions + " instructions");
        assertTrue(m.clock().now() > before, "the clock moved with the CPU");
        // and it carries on from where it stopped
        assertEquals(Cpu.Exit.REACHED, m.cpu().run(m.clock().now() + 1000, Long.MAX_VALUE));
    }
}
