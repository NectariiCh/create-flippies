package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.BootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Emulated MIPS of a whole machine on this host (spec 1.4, 7.3): an assembled tight loop run
 * through {@link Machine#runTick} at 100 MHz with a large budget. Prints the figure; asserts only
 * the lenient 20 M instructions per second floor, so it does not flake on a busy CI host.
 */
@Tag("benchmark")
class MachineThroughputBenchmarkTest {

    @Test
    void tightLoopThroughput() {
        // sum of 0..n over and over: add, addi, bne (fast path, no memory)
        var program = assemble("""
                _start:
                    li   t0, 0
                    li   t1, 0
                    li   t2, 1000000
                1:  add  t0, t0, t1
                    addi t1, t1, 1
                    bne  t1, t2, 1b
                    li   t1, 0
                    j    1b
                """);
        Machine m = new Machine(new MachineConfig(CpuModel.RV32I, 100_000_000, RAM_BYTES, null,
                BootRom.image(), () -> FIXED_EPOCH, Rv32Map.RAM_BASE), StandardCpus.INSTANCE);
        m.powerOn();
        load(m, program);

        for (int i = 0; i < 20; i++)   // warm-up: let HotSpot compile the interpreter loop
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);

        long instr0 = m.cpu().instructionsRetired();
        long t0 = System.nanoTime();
        int ticks = 0;
        while (System.nanoTime() - t0 < 1_000_000_000L) {
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            ticks++;
        }
        long nanos = System.nanoTime() - t0;
        long instructions = m.cpu().instructionsRetired() - instr0;
        double mips = instructions * 1_000.0 / nanos;
        System.out.printf("RV32I machine throughput: %.1f M instructions per second (%d ticks of 5 M cycles, %.2f ms per tick)%n",
                mips, ticks, nanos / 1e6 / ticks);
        assertTrue(mips > 20, "at least 20 M instructions per second, got " + mips);
    }
}
