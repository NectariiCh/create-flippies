package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Run budgets on the 68k machines (spec 1.4): a run stops within one instruction of its cycle
 * target, ticks keep exact time, and a passed host deadline returns within 4096 instructions.
 */
class M68kBudgetE2eTest {

    /** A loop whose longest instruction, ADDQ.L to an absolute long address, costs 28 cycles on the 68000. */
    private static final String COUNTER = """
            _start: addq.l  #1,(counter).l
                    bra.s   _start
                    .bss
            counter: ds.l   1
            """;

    /** The longest instruction in {@link #COUNTER} minus one: how far a run may overshoot its target. */
    private static long maxOvershoot(CpuModel model) {
        return model == CpuModel.CPU_68000 ? 27 : 4;
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void runStopsAtTheCycleTargetWithinOneInstruction(CpuModel model) {
        Machine m = boot(model, COUNTER);
        Cpu cpu = m.cpu();
        cpu.run(100, Long.MAX_VALUE);   // the reset sequence reads the vectors first
        long target = 0;
        for (int step : new int[] {1, 2, 3, 5, 7, 100, 1000, 12_345, 999_999}) {
            target = Math.max(target, m.clock().now()) + step;
            assertEquals(Cpu.Exit.REACHED, cpu.run(target, Long.MAX_VALUE));
            long over = m.clock().now() - target;
            assertTrue(over >= 0 && over <= maxOvershoot(model), "stopped " + over + " cycles past " + target);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void longRunsKeepExactTime(CpuModel model) {
        Machine m = boot(model, COUNTER);
        for (int i = 1; i <= 40; i++) {
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            long over = m.clock().now() - i * tickCycles(model);
            assertTrue(over >= 0 && over <= maxOvershoot(model), "tick " + i + " ended " + over + " cycles off");
        }
        // each tick may end up to one instruction late, which the next tick's window absorbs
        assertEquals(1.0, m.speed(), 20.0 * maxOvershoot(model) / (20 * tickCycles(model)) + 1e-9);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void tinyHostBudgetDilatesTimeButKeepsMakingProgress(CpuModel model) {
        AsmImage program = assemble(model, COUNTER);
        Machine m = boot(model, program, null);
        int last = 0;
        for (int i = 0; i < 10; i++) {
            m.runTick(Machine.TICK_NANOS, 0);
            int now = ram32(m, program, "counter");
            assertTrue(now > last, "progress in tick " + i);
            // a passed deadline returns within 4096 instructions: 2048 loop iterations
            assertTrue(now - last <= 2048 + 1, "tick " + i + " ran " + (now - last) + " iterations");
            last = now;
        }
        assertEquals(MachineState.RUNNING, m.state());
        assertTrue(m.speed() < 0.25, "speed " + m.speed());
        m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertTrue(ram32(m, program, "counter") - last > 5_000);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void pastDeadlineReturnsWithConsistentState(CpuModel model) {
        Machine m = boot(model, COUNTER);
        long before = m.clock().now();
        assertEquals(Cpu.Exit.DEADLINE, m.cpu().run(Long.MAX_VALUE / 2, System.nanoTime() - 1));
        long instructions = m.cpu().instructionsRetired();
        assertTrue(instructions > 0 && instructions <= 4096, instructions + " instructions");
        assertTrue(m.clock().now() > before, "the clock moved with the CPU");
        assertEquals(Cpu.Exit.REACHED, m.cpu().run(m.clock().now() + 1000, Long.MAX_VALUE));
    }
}
