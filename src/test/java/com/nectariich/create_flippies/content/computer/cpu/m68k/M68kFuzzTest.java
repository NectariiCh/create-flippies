package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Random words in RAM (spec 10.12.1): whatever the bytes, the core only ever takes exceptions,
 * waits, halts or returns normally; no Java exception escapes, including division and bit field
 * edge cases. Every handler is random code too, so exceptions inside handlers are exercised.
 */
class M68kFuzzTest {

    @Test
    void randomCodeNeverThrows() {
        long steps = 0;
        for (boolean is040 : new boolean[] {false, true}) {
            for (int seed = 0; seed < 40; seed++) {
                Random random = new Random(seed * 31L + (is040 ? 7 : 0));
                M68kRig rig = new M68kRig(is040);
                for (int a = 0x400; a < M68kRig.RAM_SIZE; a += 2)
                    rig.poke16(a, random.nextInt(0x10000));
                for (int n = 2; n < 256; n++)
                    rig.poke32(4 * n, (0x400 + random.nextInt(M68kRig.RAM_SIZE - 0x400)) & ~1);
                rig.cpu.setPc(PROG);
                for (int k = 0; k < 2000; k++) {
                    Cpu.Exit exit = rig.runFor(1000);
                    if (exit == Cpu.Exit.HALTED) {
                        steps += rig.cpu.instructionsRetired();
                        rig.cpu.reset();
                        continue;
                    }
                    if (exit == Cpu.Exit.WAITING) {
                        rig.cpu.setIrq(1 + random.nextInt(7), random.nextBoolean());
                        rig.clock.advanceTo(rig.clock.now() + 1000);
                    }
                    if (random.nextInt(10) == 0)
                        rig.cpu.setIrq(1 + random.nextInt(7), random.nextBoolean());
                }
                steps += rig.cpu.instructionsRetired();
            }
        }
        System.out.printf("68k fuzz: %d instructions retired%n", steps);
        assertTrue(steps > 1_000_000);
    }
}
