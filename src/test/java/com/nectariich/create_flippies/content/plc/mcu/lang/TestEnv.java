package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;

/** A minimal chip around the CPU for tests: pins, a recorded output list, and run windows. */
final class TestEnv implements McuEnvironment {

    final List<String> out = new ArrayList<>();
    final boolean[] inputs = new boolean[40];
    final boolean[] outputs = new boolean[40];
    final long[] handlers = new long[40];
    long writes;
    final List<Long> writeLog = new ArrayList<>();
    boolean done;
    long windowEnd = Long.MAX_VALUE;
    final CycleLedger ledger = new CycleLedger();
    CpuCore cpu;

    @Override
    public long nextEventCycle() {
        return windowEnd;
    }

    static TestEnv boot(CompileResult r) {
        TestEnv e = new TestEnv();
        e.cpu = r.program().createCpu(e, e.ledger);
        e.cpu.reset();
        return e;
    }

    /** Runs in 50 ms windows until done(), a fault, or {@code maxCycles}. */
    CpuCore.Exit runUntilDone(long maxCycles) {
        CpuCore.Exit x = CpuCore.Exit.REACHED;
        while (!done && !cpu.halted() && ledger.now() < maxCycles) {
            windowEnd = Math.min(maxCycles, ledger.now() + 3_600_000);
            x = cpu.run(windowEnd, System.nanoTime() + 5_000_000_000L);
            if (x == CpuCore.Exit.HALTED)
                break;
        }
        return x;
    }
}
