package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Interpreter speed (spec 7.3). Prints millions of instructions per second; asserts only a
 * lenient floor of 20 M/s so it does not flake on a busy machine. Falling under the floor
 * usually means the hot loop grew past HotSpot's huge-method limit and runs interpreted.
 */
@Tag("benchmark")
class Rv32BenchmarkTest {

    /** A loop of ALU ops, a RAM load and store, and a taken branch: typical compiled-code mix. */
    private static Rv32Rig rig() {
        Rv32Rig rig = new Rv32Rig().program(
                addi(A0, A0, 1),
                xor(A1, A1, A0),
                add(A2, A2, A1),
                slli(A3, A2, 3),
                sub(A4, A3, A0),
                lw(A5, SP, 0),
                and(A5, A5, A4),
                sw(A5, SP, 4),
                or(A6, A5, A2),
                sltu(A7, A6, A1),
                jal(ZERO, -40));
        rig.cpu.setReg(SP, RAM + 0x1000);
        return rig;
    }

    @Test
    void simpleInstructionsPerSecond() {
        Rv32Rig rig = rig();
        for (int k = 0; k < 20; k++)
            rig.runFor(5_000_000);

        long retired0 = rig.cpu.instructionsRetired();
        long t0 = System.nanoTime();
        rig.runFor(200_000_000);
        long nanos = System.nanoTime() - t0;
        double mips = (rig.cpu.instructionsRetired() - retired0) * 1000.0 / nanos;
        System.out.printf("RV32I interpreter: %.1f M instructions per second%n", mips);
        assertTrue(mips >= 20, "too slow: " + mips + " M/s");
    }
}
