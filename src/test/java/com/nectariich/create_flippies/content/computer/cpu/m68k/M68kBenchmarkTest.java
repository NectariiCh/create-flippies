package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Interpreter speed (spec 10.12.1). Prints millions of instructions per second for a mixed loop
 * on each model; asserts only a lenient floor of 15 M/s so it does not flake on a busy machine.
 */
@Tag("benchmark")
class M68kBenchmarkTest {

    private static final int[] LOOP = {
            0x5280,         // addq.l #1,d0
            0xB380,         // eor.l d1,d0
            0xD480,         // add.l d0,d2
            0xE78B,         // lsl.l #3,d3
            0x2610,         // move.l (a0),d3
            0xC682,         // and.l d2,d3
            0x2143, 0x0004, // move.l d3,4(a0)
            0x8682,         // or.l d2,d3
            0xB681,         // cmp.l d1,d3
            0x66EC,         // bne.s to the top (-20)
            0x60EA,         // bra.s to the top
    };

    @Test
    void mixedLoopInstructionsPerSecond() {
        for (boolean is040 : new boolean[] {false, true}) {
            M68kRig rig = new M68kRig(is040).program(LOOP);
            rig.cpu.setA(0, DATA);
            rig.cpu.setD(1, 0x5A5A_5A5A);
            for (int k = 0; k < 20; k++)
                rig.runFor(5_000_000);
            long retired0 = rig.cpu.instructionsRetired();
            long t0 = System.nanoTime();
            rig.runFor(is040 ? 60_000_000 : 300_000_000);
            long nanos = System.nanoTime() - t0;
            double mips = (rig.cpu.instructionsRetired() - retired0) * 1000.0 / nanos;
            System.out.printf("%s interpreter: %.1f M instructions per second%n", is040 ? "68040" : "68000", mips);
            assertTrue(mips >= 15, "too slow: " + mips + " M/s");
        }
    }
}
