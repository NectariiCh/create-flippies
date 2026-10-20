package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.DEV;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.HANDLER;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Cycle costs (spec 4.6), how run meets its horizon and deadline (spec 1.3, 1.4, 7.2), and that
 * slicing a run into pieces never changes the result.
 */
class Rv32TimingTest {

    /** One instruction of every cost class, then an ECALL whose handler skips it, then a spin. */
    private static final int[] MIXED = {
            addi(A0, ZERO, 5),      // 0x00  1
            lw(A3, A1, 0),          // 0x04  2  load
            lw(A3, A1, 1),          // 0x08  3  misaligned load
            sw(A3, A1, 3),          // 0x0C  2  misaligned store
            beq(ZERO, ZERO, 8),     // 0x10  3  taken branch
            NOP,                    // 0x14     skipped
            bne(ZERO, ZERO, 8),     // 0x18  1  branch not taken
            jal(RA, 8),             // 0x1C  3
            NOP,                    // 0x20     skipped
            auipc(T0, 0),           // 0x24  1
            jalr(RA, T0, 12),       // 0x28  3  to 0x30
            NOP,                    // 0x2C     skipped
            mul(A4, A0, A0),        // 0x30  3
            div(A5, A4, A0),        // 0x34  33
            lw(A6, A2, 0),          // 0x38  4  device load
            sw(A0, A2, 4),          // 0x3C  3  device store
            ECALL,                  // 0x40  3  trap
            lb(A7, A1, 0),          // 0x44  2
            jal(ZERO, 0),           // 0x48     spin
    };

    /** The handler: skip the trapping instruction and return (1 + 1 + 1 + 3 cycles). */
    private static final int[] SKIP_HANDLER = {
            csrr(T1, Rv32Csr.MEPC), addi(T1, T1, 4), csrw(Rv32Csr.MEPC, T1), MRET,
    };

    private static Rv32Rig mixedRig() {
        Rv32Rig rig = new Rv32Rig();
        rig.mtvec(HANDLER);
        rig.program(MIXED);
        rig.at(HANDLER, SKIP_HANDLER);
        rig.cpu.setReg(A1, RAM + 0x400);
        rig.cpu.setReg(A2, DEV);
        rig.poke32(RAM + 0x400, 0x0403_0201);
        return rig;
    }

    @Test
    void exactCycleTotalsForEveryCostClass() {
        Rv32Rig rig = mixedRig();
        long c0 = rig.cpu.cycles();
        long r0 = rig.cpu.instructionsRetired();
        rig.steps(19);
        assertEquals(RAM + 0x48, rig.cpu.pc());
        assertEquals(1 + 2 + 3 + 2 + 3 + 1 + 3 + 1 + 3 + 3 + 33 + 4 + 3 + 3 + (1 + 1 + 1 + 3) + 2,
                rig.cpu.cycles() - c0);
        assertEquals(18, rig.cpu.instructionsRetired() - r0, "the ECALL does not retire");
        assertEquals(rig.cpu.cycles(), rig.clock.now());
        assertEquals(25, rig.x(A4));
        assertEquals(5, rig.x(A5));
    }

    @Test
    void runStopsWithinOneInstructionOfItsLimit() {
        Rv32Rig rig = new Rv32Rig().program(div(A0, A1, A2), div(A0, A1, A2), div(A0, A1, A2), jal(ZERO, -12));
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(40, Long.MAX_VALUE));
        assertEquals(66, rig.cpu.cycles(), "the second divide crossed 40");
        assertEquals(66, rig.clock.now(), "final count published");
        assertEquals(40, rig.clock.horizon());
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(66, Long.MAX_VALUE), "horizon not after the clock");
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(10, Long.MAX_VALUE));
        assertEquals(66, rig.cpu.cycles());
        assertEquals(RAM + 8, rig.cpu.pc());
    }

    @Test
    void runLoadsItsCycleCountFromTheClock() {
        Rv32Rig rig = new Rv32Rig().program(NOP, csrr(A0, Rv32Csr.MCYCLE));
        rig.step();
        rig.clock.advanceTo(10_000);
        rig.step();
        assertEquals(10_000, rig.x(A0));
        assertEquals(10_001, rig.cpu.cycles());
    }

    @Test
    void aDeviceLoweringTheHorizonEndsTheRunThere() {
        Rv32Rig rig = new Rv32Rig().program(NOP, sw(ZERO, A2, 0), addi(A0, A0, 1), jal(ZERO, -4));
        rig.cpu.setReg(A2, DEV);
        rig.device.onWrite = () -> rig.clock.requestStopAt(rig.clock.now() + 50);
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(1_000_000, Long.MAX_VALUE));
        long stopAt = 1 + 50;
        assertTrue(rig.cpu.cycles() >= stopAt && rig.cpu.cycles() < stopAt + 3, "stopped at " + rig.cpu.cycles());
    }

    @Test
    void aStopRequestedAtNowEndsTheRunAfterTheWritingInstruction() {
        Rv32Rig rig = new Rv32Rig().program(NOP, sw(ZERO, A2, 0), addi(A0, A0, 1), jal(ZERO, -4));
        rig.cpu.setReg(A2, DEV);
        rig.device.onWrite = () -> rig.clock.requestStopAt(rig.clock.now());
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(1_000_000, Long.MAX_VALUE));
        assertEquals(1 + 3, rig.cpu.cycles());
        assertEquals(RAM + 8, rig.cpu.pc());
        assertEquals(0, rig.x(A0));
    }

    @Test
    void aPassedDeadlineReturnsWithin4096Instructions() {
        Rv32Rig rig = new Rv32Rig().program(addi(A0, A0, 1), jal(ZERO, -4));
        assertEquals(Cpu.Exit.DEADLINE, rig.cpu.run(1_000_000_000L, Long.MIN_VALUE));
        long n = rig.cpu.instructionsRetired();
        assertTrue(n > 0 && n <= 4096, "retired " + n);
        assertEquals(rig.cpu.cycles(), rig.clock.now());
        assertEquals((n + 1) / 2, rig.x(A0));
        assertEquals(n % 2 == 0 ? RAM : RAM + 4, rig.cpu.pc());
        assertEquals(n / 2 * 4 + n % 2, rig.cpu.cycles());
    }

    // ---- slicing ----

    /** Everything a slice boundary could disturb. */
    private record Snapshot(int[] regs, int pc, long cycles, long retired, byte[] ram, int[] csrs,
                            List<TestDevice.Write> writes) {
        void assertSame(Snapshot o, String name) {
            assertArrayEquals(regs, o.regs, name + ": registers");
            assertEquals(pc, o.pc, name + ": pc");
            assertEquals(cycles, o.cycles, name + ": cycles");
            assertEquals(retired, o.retired, name + ": retired");
            assertArrayEquals(ram, o.ram, name + ": RAM");
            assertArrayEquals(csrs, o.csrs, name + ": CSRs");
            assertEquals(writes, o.writes, name + ": device writes");
        }
    }

    private static final int[] SNAPSHOT_CSRS = { Rv32Csr.MSTATUS, Rv32Csr.MEPC, Rv32Csr.MCAUSE, Rv32Csr.MTVAL,
            Rv32Csr.MSCRATCH, Rv32Csr.MCYCLE, Rv32Csr.MINSTRET };

    private static Snapshot runSliced(java.util.function.Supplier<Rv32Rig> setup, long total, long slice) {
        Rv32Rig rig = setup.get();
        long end = rig.clock.now() + total;
        while (rig.clock.now() < end) {
            Cpu.Exit exit = rig.cpu.run(Math.min(rig.clock.now() + slice, end), Long.MAX_VALUE);
            if (exit == Cpu.Exit.WAITING || exit == Cpu.Exit.HALTED)
                break;
        }
        int[] regs = new int[32];
        for (int r = 0; r < 32; r++)
            regs[r] = rig.x(r);
        int[] csrs = Arrays.stream(SNAPSHOT_CSRS).map(rig::csr).toArray();
        return new Snapshot(regs, rig.cpu.pc(), rig.cpu.cycles(), rig.cpu.instructionsRetired(),
                rig.bus.ramArray().clone(), csrs, new ArrayList<>(rig.device.writes));
    }

    /** A loop that hashes RAM bytes with multiplies, divides, shifts and stores, writing progress to the device. */
    private static Rv32Rig checksumRig() {
        Rv32Rig rig = new Rv32Rig();
        rig.mtvec(HANDLER);
        rig.at(HANDLER, SKIP_HANDLER);
        rig.program(
                lbu(T0, A1, 0),          // loop:
                add(A0, A0, T0),
                mul(A0, A0, A3),
                srli(T1, A0, 7),
                xor(A0, A0, T1),
                remu(T2, A0, A4),
                sw(T2, A1, 0x100),
                sh(A0, A1, 3),
                addi(A1, A1, 1),
                andi(T3, A1, 63),
                bne(T3, ZERO, 12),
                sw(A0, A2, 0),           // every 64 bytes: report to the device
                ECALL,                   // and trap once per loop
                blt(A1, A5, -13 * 4),
                jal(ZERO, 0));
        rig.cpu.setReg(A1, RAM + 0x1000);
        rig.cpu.setReg(A2, DEV);
        rig.cpu.setReg(A3, 0x0100_0193);
        rig.cpu.setReg(A4, 251);
        rig.cpu.setReg(A5, RAM + 0x1800);
        Random random = new Random(7);
        for (int k = 0x1000; k < 0x1900; k++)
            rig.bus.ramArray()[k] = (byte) random.nextInt();
        return rig;
    }

    /** Random but valid-leaning words, as the fuzz test makes them, with a handler that skips traps. */
    private static Rv32Rig randomRig(long seed) {
        Rv32Rig rig = new Rv32Rig();
        rig.mtvec(HANDLER);
        Random random = new Random(seed);
        int[] words = new int[256];
        for (int k = 0; k < words.length; k++) {
            int w;
            do {
                w = Rv32FuzzTest.plausibleWord(random);
            } while (w == WFI);
            words[k] = w;
        }
        words[words.length - 1] = jal(ZERO, -4 * (words.length - 1));
        rig.program(words);
        rig.at(HANDLER, SKIP_HANDLER);
        for (int r = 1; r < 32; r++)
            rig.cpu.setReg(r, Rv32FuzzTest.plausibleValue(random));
        return rig;
    }

    private static void assertSlicingInvariant(java.util.function.Supplier<Rv32Rig> setup, long total, String name) {
        Snapshot whole = runSliced(setup, total, total);
        runSliced(setup, total, 1).assertSame(whole, name + " in 1-cycle slices");
        runSliced(setup, total, 7).assertSame(whole, name + " in 7-cycle slices");
        runSliced(setup, total, 1000).assertSame(whole, name + " in 1000-cycle slices");
    }

    @Test
    void slicingDoesNotChangeTheResult() {
        assertSlicingInvariant(Rv32TimingTest::mixedRig, 500, "mixed");
        assertSlicingInvariant(Rv32TimingTest::checksumRig, 150_000, "checksum");
        Rv32Rig check = checksumRig();
        check.runFor(150_000);
        assertEquals(RAM + 14 * 4, check.cpu.pc(), "the checksum loop finished");
        assertEquals(32, check.device.writes.size());
        for (long seed = 1; seed <= 6; seed++) {
            long s = seed;
            assertSlicingInvariant(() -> randomRig(s), 20_000, "random program " + seed);
        }
    }
}
