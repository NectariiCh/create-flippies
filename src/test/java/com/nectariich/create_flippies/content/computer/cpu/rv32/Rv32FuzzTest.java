package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.DEV;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Guest fuzz (spec 8.1): whatever words a guest runs, {@code run} only ever returns normally.
 * Every word is executed or trapped, division by zero never throws, x0 stays zero, pc stays
 * aligned and time only moves forward.
 */
class Rv32FuzzTest {

    /** Opcodes (bits 6..0) the core implements, so plausible words reach every decoder path. */
    private static final int[] OPCODES = { 0x03, 0x0F, 0x13, 0x17, 0x23, 0x33, 0x37, 0x63, 0x67, 0x6F, 0x73 };

    /** A word with a real opcode and random fields, biased toward small funct7 values that decode. */
    static int plausibleWord(Random random) {
        int w = random.nextInt() & ~0x7F | OPCODES[random.nextInt(OPCODES.length)];
        switch (random.nextInt(4)) {
            case 0: w &= 0x01FF_FFFF; break;                    // funct7 0
            case 1: w = (w & 0x01FF_FFFF) | 0x0200_0000; break;  // funct7 1: M
            case 2: w = (w & 0x01FF_FFFF) | 0x4000_0000; break;  // funct7 0x20: SUB, SRA
            default: break;
        }
        if ((w & 0x7F) == 0x73 && random.nextInt(3) == 0) {
            int[] csrs = { Rv32Csr.MSTATUS, Rv32Csr.MIE, Rv32Csr.MTVEC, Rv32Csr.MSCRATCH, Rv32Csr.MEPC,
                    Rv32Csr.MCYCLE, Rv32Csr.MINSTRETH, Rv32Csr.CYCLE, Rv32Csr.TIME, Rv32Csr.MIP };
            w = (w & 0x000F_FFFF) | csrs[random.nextInt(csrs.length)] << 20;
        }
        return w;
    }

    /** Register values that hit edge cases and every memory region. */
    static int plausibleValue(Random random) {
        switch (random.nextInt(10)) {
            case 0: return 0;
            case 1: return -1;
            case 2: return Integer.MIN_VALUE;
            case 3: return RAM + random.nextInt(RAM_SIZE);
            case 4: return Rv32Map.VRAM_BASE + random.nextInt(Rv32Map.VRAM_SIZE);
            case 5: return Rv32Map.ROM_BASE + random.nextInt(Rv32Map.ROM_SIZE);
            case 6: return DEV + random.nextInt(0x1000);
            case 7: return random.nextInt(64) - 32;
            default: return random.nextInt();
        }
    }

    private static void fuzz(long seed, boolean plausible, long cycles) {
        Random random = new Random(seed);
        Rv32Rig rig = new Rv32Rig(random.nextBoolean());
        byte[] ram = rig.bus.ramArray();
        if (plausible) {
            for (int k = 0; k < RAM_SIZE; k += 4)
                rig.poke32(RAM + k, plausibleWord(random));
        } else {
            random.nextBytes(ram);
        }
        for (int r = 1; r < 32; r++)
            rig.cpu.setReg(r, plausibleValue(random));
        rig.mtvec((RAM + random.nextInt(RAM_SIZE)) & ~3 | random.nextInt(2));
        rig.cpu.setPc(RAM);

        long end = rig.clock.now() + cycles;
        long last = rig.clock.now();
        int[] pins = { Rv32Map.IRQ_PIN_MSIP, Rv32Map.IRQ_PIN_MTIP, Rv32Map.IRQ_PIN_MEIP };
        while (rig.clock.now() < end) {
            if (random.nextInt(8) == 0)
                rig.cpu.setIrq(pins[random.nextInt(3)], random.nextBoolean());
            Cpu.Exit exit = rig.cpu.run(rig.clock.now() + 1 + random.nextInt(5000), Long.MAX_VALUE);
            assertEquals(0, rig.x(0), "x0");
            assertTrue(rig.clock.now() >= last, "time went backwards");
            last = rig.clock.now();
            assertEquals(rig.cpu.cycles(), rig.clock.now(), "count published");
            switch (exit) {
                case WAITING:
                    rig.clock.advanceTo(rig.clock.now() + 100);
                    if (random.nextBoolean())
                        rig.cpu.setIrq(pins[random.nextInt(3)], true);
                    else
                        rig.cpu.setPc(RAM + (random.nextInt(RAM_SIZE) & ~3));
                    if (rig.cpu.run(rig.clock.now() + 10, Long.MAX_VALUE) == Cpu.Exit.WAITING)
                        restart(rig, random);
                    break;
                case HALTED:
                    assertTrue(rig.cpu.haltReason().startsWith("trap handler at"), rig.cpu.haltReason());
                    restart(rig, random);
                    break;
                default:
                    assertEquals(0, rig.cpu.pc() & 3, "pc aligned");
            }
        }
    }

    private static void restart(Rv32Rig rig, Random random) {
        rig.cpu.reset();
        rig.cpu.setPc(RAM + (random.nextInt(RAM_SIZE) & ~3));
        rig.mtvec((RAM + random.nextInt(RAM_SIZE)) & ~3);
    }

    @Test
    void randomBytesNeverThrow() {
        for (long seed = 1; seed <= 12; seed++)
            fuzz(seed, false, 400_000);
    }

    @Test
    void plausibleWordsNeverThrow() {
        for (long seed = 100; seed <= 124; seed++)
            fuzz(seed, true, 400_000);
    }

    @Test
    void divisionByZeroNeverThrows() {
        Rv32Rig rig = new Rv32Rig();
        int[] program = new int[16];
        for (int f3 = 0; f3 < 8; f3++)
            program[f3] = Rv32Enc.r(1, Rv32Enc.ZERO, Rv32Enc.A1, f3, Rv32Enc.A0, 0x33);
        program[8] = Rv32Enc.jal(Rv32Enc.ZERO, -32);
        rig.program(program);
        rig.cpu.setReg(Rv32Enc.A1, Integer.MIN_VALUE);
        assertEquals(Cpu.Exit.REACHED, rig.runFor(10_000));
    }
}
