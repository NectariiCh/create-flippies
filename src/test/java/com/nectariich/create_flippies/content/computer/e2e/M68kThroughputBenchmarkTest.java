package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Emulated MIPS of a whole 68k machine on this host (spec 10.3.9, 10.12.4): an assembled loop of
 * register and memory instructions run through {@link Machine#runTick} at the model's fastest
 * clock with a large budget. Prints the figure; asserts only a lenient 10 M instructions per
 * second floor, so it does not flake on a busy CI host.
 */
@Tag("benchmark")
class M68kThroughputBenchmarkTest {

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void mixedLoopThroughput(CpuModel model) {
        var program = assemble(model, """
                _start: moveq   #0,d0
                        moveq   #0,d1
                        lea     buf,a0
                        move.l  #1000000,d2
                1:      add.l   d1,d0
                        move.l  d0,(a0)
                        add.l   (a0),d3
                        addq.l  #1,d1
                        cmp.l   d2,d1
                        bne.s   1b
                        moveq   #0,d1
                        bra.s   1b
                        .bss
                buf:    ds.l    1
                """);
        int hz = model == CpuModel.CPU_68040 ? M68kMap.CLOCK_68040_MAX_HZ : M68kMap.CLOCK_68000_MAX_HZ;
        Machine m = new Machine(new MachineConfig(model, hz, RAM_BYTES, null, BootRoms.image(model),
                () -> FIXED_EPOCH, null), StandardCpus.INSTANCE);
        m.powerOn();
        prepare(m, program);

        for (int i = 0; i < 40; i++)   // warm-up: let HotSpot compile the interpreter loop
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
        System.out.printf("%s machine throughput at %.2f MHz: %.1f M instructions per second (%d ticks, %.3f ms per tick, %.2f ms emulated per host ms)%n",
                model.displayName(), hz / 1e6, mips, ticks, nanos / 1e6 / ticks, ticks * 50.0 / (nanos / 1e6));
        assertTrue(mips > 10, "at least 10 M instructions per second, got " + mips);
    }
}
