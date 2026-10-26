package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kCpu;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Emulated MIPS of the 68040 machine with paging on against paging off (spec 12.2.2: at least
 * 60% of the MMU-off rate is the target). The loop of {@link M68kThroughputBenchmarkTest} runs in
 * supervisor mode; with the MMU on, the supervisor tables map the first MiB to itself (no TT
 * register), so every fetch and data access goes through the TLB. Prints both figures and the
 * ratio; asserts only the lenient 10 M instructions per second floor of the MMU-off benchmark.
 */
@Tag("benchmark")
class M68kMmuThroughputBenchmarkTest {

    private static final CpuModel MODEL = CpuModel.CPU_68040;
    private static final int ROOT = 0x8_0000;

    private static double mips(boolean mmu) {
        var program = assemble(MODEL, """
                _start: move.l  #$80000,d0
                        movec   d0,srp
                        pflusha
                        move.l  #@TC@,d0
                        movec   d0,tc
                        moveq   #0,d0
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
                """.replace("@TC@", mmu ? "$8000" : "0"));
        Machine m = new Machine(new MachineConfig(MODEL, M68kMap.CLOCK_68040_MAX_HZ, RAM_BYTES, null,
                BootRoms.image(MODEL), () -> FIXED_EPOCH, null), StandardCpus.INSTANCE);
        m.powerOn();
        prepare(m, program);
        // supervisor tables: root entry 0 -> one pointer table, entries 0..3 -> four page tables
        // of 64 pages each, the first MiB mapped to itself
        poke32(m, ROOT, (ROOT + 0x200) | 2);
        for (int k = 0; k < 4; k++)
            poke32(m, ROOT + 0x200 + 4 * k, (ROOT + 0x400 + 256 * k) | 2);
        for (int page = 0; page < 256; page++)
            poke32(m, ROOT + 0x400 + 4 * page, (page << 12) | 1);

        for (int i = 0; i < 40; i++)   // warm-up: let HotSpot compile the interpreter loop
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        assertEquals(mmu ? 0x8000 : 0, ((M68kCpu) m.cpu()).controlRegister(0x003), "TC");
        if (mmu)
            assertEquals(0x9, ram32(m, ROOT + 0x400 + 4) & 0x9, "the tables are in use (U set on page 1)");

        long instr0 = m.cpu().instructionsRetired();
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 1_000_000_000L)
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        long nanos = System.nanoTime() - t0;
        return (m.cpu().instructionsRetired() - instr0) * 1_000.0 / nanos;
    }

    @Test
    void pagingOnAgainstPagingOff() {
        double off = mips(false);
        double on = mips(true);
        double off2 = mips(false);
        double offBest = Math.max(off, off2);
        System.out.printf("68040 machine throughput: MMU off %.1f M instructions per second (again %.1f), MMU on %.1f (%.0f%% of off)%n",
                off, off2, on, 100 * on / offBest);
        assertTrue(on > 10, "at least 10 M instructions per second with paging on, got " + on);
    }
}
