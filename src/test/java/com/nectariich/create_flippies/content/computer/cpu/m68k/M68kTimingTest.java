package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;

import org.junit.jupiter.api.Test;

/**
 * Cycle counts (spec 10.2.2, 10.3.9), worked out by hand from the 68000 timing tables (base time
 * plus EA time), the 68040 cost model, and run-loop behaviour: slicing invariance, horizon and
 * host deadline.
 */
class M68kTimingTest {

    /** Cycles of {@code words} as one instruction, with D0 = d0, D1 = d1, A0 = A1 = DATA. */
    private static long cost(M68kRig rig, int d0, int d1, int... words) {
        rig.program(words);
        rig.cpu.setD(0, d0);
        rig.cpu.setD(1, d1);
        rig.cpu.setA(0, DATA);
        rig.cpu.setA(1, DATA + 0x100);
        rig.ccr(0);
        return rig.cost();
    }

    @Test
    void m68000TableSamples() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(4, cost(rig, 0, 0, 0x4E71), "nop");
        assertEquals(4, cost(rig, 0, 0, 0x700A), "moveq");
        assertEquals(4, cost(rig, 0, 0, 0x3200), "move.w d0,d1");
        assertEquals(4, cost(rig, 0, 0, 0x2200), "move.l d0,d1");
        assertEquals(12, cost(rig, 0, 0, 0x3290), "move.w (a0),(a1)");
        assertEquals(20, cost(rig, 0, 0, 0x2290), "move.l (a0),(a1)");
        assertEquals(8, cost(rig, 0, 0, 0x3300), "move.w d0,-(a1)");
        assertEquals(12, cost(rig, 0, 0, 0x2300), "move.l d0,-(a1)");
        assertEquals(16, cost(rig, 0, 0, 0x2028, 0x0004), "move.l 4(a0),d0");
        assertEquals(8, cost(rig, 0, 0, 0x303C, 0x1234), "move.w #imm,d0");
        assertEquals(20, cost(rig, 0, 0, 0x20BC, 0x1234, 0x5678), "move.l #imm,(a0)");
        assertEquals(14, cost(rig, 0, 0, 0xD090), "add.l (a0),d0: 6 + 8");
        assertEquals(8, cost(rig, 0, 0, 0xD081), "add.l d1,d0");
        assertEquals(12, cost(rig, 0, 0, 0xD150), "add.w d0,(a0): 8 + 4");
        assertEquals(8, cost(rig, 0, 0, 0x5288), "addq.l #1,a0");
        assertEquals(8, cost(rig, 0, 0, 0x43E8, 0x0008), "lea 8(a0),a1");
        assertEquals(4, cost(rig, 0, 0, 0x41D0), "lea (a0),a0");
        assertEquals(6, cost(rig, 0, 0, 0x4280), "clr.l d0");
        assertEquals(12, cost(rig, 0, 0, 0x4250), "clr.w (a0)");
        assertEquals(10, cost(rig, 0, 0, 0x0800, 0x0003), "btst #3,d0");
        assertEquals(8, cost(rig, 0, 0, 0x03C0), "bset d1,d0");
        assertEquals(6, cost(rig, 0, 0, 0xC101), "abcd d1,d0");
        assertEquals(6, cost(rig, 0, 0, 0xC141), "exg d0,d1");
        assertEquals(14, cost(rig, 0, 0, 0x0280, 0, 1), "andi.l #,d0");
        assertEquals(16, cost(rig, 0, 0, 0x0080, 0, 1), "ori.l #,d0");
        assertEquals(32, cost(rig, 0, 0, 0x48E7, 0xC080), "movem.l 3 regs,-(a7): 8 + 3 * 8");
        assertEquals(20, cost(rig, 0, 0, 0x4C98, 0x0201), "movem.w (a0)+,2 regs: 12 + 2 * 4");
        assertEquals(16, cost(rig, 0, 0, 0x4E56, 0xFFF8), "link");
        assertEquals(4, cost(rig, 0, 0, 0x4E76), "trapv not taken");
    }

    @Test
    void m68000BranchesAndCalls() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(10, cost(rig, 0, 0, 0x6002), "bra.s");
        assertEquals(8, cost(rig, 0, 0, 0x6702), "beq.s not taken");
        assertEquals(12, cost(rig, 0, 0, 0x6700, 0x0004), "beq.w not taken");
        assertEquals(10, cost(rig, 0, 0, 0x6600, 0x0004), "bne.w taken");
        assertEquals(18, cost(rig, 0, 0, 0x6100, 0x0004), "bsr.w");
        rig.at(DATA, 0x4E75);
        assertEquals(16, cost(rig, 0, 0, 0x4E90), "jsr (a0)");
        assertEquals(16, rig.cost(), "rts");
        assertEquals(10, cost(rig, 5, 0, 0x51C8, 0xFFFE), "dbf looping");
        assertEquals(14, cost(rig, 0, 0, 0x51C8, 0xFFFE), "dbf expired");
        assertEquals(12, cost(rig, 0, 0, 0x50C8, 0xFFFE), "dbt condition true");
        assertEquals(8, cost(rig, 0, 0, 0x4ED0), "jmp (a0)");
    }

    @Test
    void m68000ShiftMultiplyDivide() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(6 + 2 * 3, cost(rig, 0, 0, 0xE748), "lsl.w #3,d0");
        assertEquals(8 + 2 * 10, cost(rig, 0, 10, 0xE3A0), "asl.l d1,d0 by 10");
        assertEquals(8 + 2 * 63, cost(rig, 0, 63, 0xE3A8), "lsl.l by 63");
        assertEquals(12, cost(rig, 0, 0, 0xE1D0), "asl.w (a0): 8 + 4");
        assertEquals(38, cost(rig, 7, 0, 0xC0C1), "mulu of 0");
        assertEquals(70, cost(rig, 7, 0xFFFF, 0xC0C1), "mulu of 0xFFFF: 38 + 2 * 16");
        assertEquals(70, cost(rig, 7, 0x5555, 0xC1C1), "muls of 0x5555: 16 alternations");
        assertEquals(38, cost(rig, 7, 0, 0xC1C1), "muls of 0");
        assertEquals(136, cost(rig, 0, 1, 0x80C1), "divu 0 / 1, the worst case");
        assertEquals(10, cost(rig, 0x0001_0000, 1, 0x80C1), "divu overflow");
        assertEquals(156, cost(rig, -1, 2, 0x81C1), "divs -1 / 2, the worst case");
        assertEquals(16, cost(rig, 0x7FFF_FFFF, 1, 0x81C1), "divs absolute overflow: (6 + 2) * 2");
    }

    @Test
    void m68000ExceptionCosts() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(34, cost(rig, 0, 0, 0x4E40), "trap");
        assertEquals(34, cost(rig, 0, 0, 0x4AFC), "illegal");
        assertEquals(34, cost(rig, 0, 0, 0xA000), "line A");
        assertEquals(40, cost(rig, -1, 5, 0x4181), "chk taken");
        assertEquals(10, cost(rig, 3, 5, 0x4181), "chk not taken");
        assertEquals(38, cost(rig, 3, 0, 0x80C1), "zero divide");
    }

    @Test
    void m68000GroupZeroAndInterruptCosts() {
        M68kRig rig = M68kRig.m68000();
        rig.program(0x3010); // move.w (a0),d0 at an odd address: (An) EA 4 + group 0 50
        rig.cpu.setA(0, DATA + 1);
        assertEquals(54, rig.cost());
        rig.program(0x4E71);
        rig.cpu.setSrHost(0x2000);
        rig.cpu.setIrq(1, true);
        assertEquals(44, rig.cost(), "interrupt entry");
        assertEquals(handler(25), rig.cpu.pc());
    }

    @Test
    void deviceAccessSurcharge() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(4 + 16 + 2, cost(rig, 0, 0, 0x23C0, 0xFFF0, 0x2000), "move.l d0,abs.l to a device");
    }

    @Test
    void m68040CostModel() {
        M68kRig rig = M68kRig.m68040();
        assertEquals(1, cost(rig, 0, 0, 0x4E71), "nop");
        assertEquals(2, cost(rig, 0, 0, 0x2010), "move.l (a0),d0: base + 1 read");
        assertEquals(3, cost(rig, 0, 0, 0x2290), "move.l (a0),(a1): base + read + write");
        assertEquals(2, cost(rig, 0, 0, 0x6002), "bra taken");
        assertEquals(1, cost(rig, 0, 0, 0x6702), "beq not taken");
        assertEquals(16, cost(rig, 3, 3, 0xC0C1), "mulu.w");
        assertEquals(27, cost(rig, 30, 3, 0x80C1), "divu.w");
        assertEquals(20, cost(rig, 3, 3, 0x4C01, 0x0000), "mulu.l");
        assertEquals(44, cost(rig, 30, 3, 0x4C41, 0x0000), "divu.l");
        assertEquals(16 + 4, cost(rig, 0, 0, 0x4E40), "trap: entry 16 + 3 pushes + vector read");
        assertEquals(3 + 1, cost(rig, 0, 0, 0x4E90), "jsr (a0): 3 + push");
    }

    private static final int[] MIXED = {
            0x700A,         // PROG + 0   moveq #10,d0
            0x5281,         // PROG + 2   loop: addq.l #1,d1
            0x20C1,         // PROG + 4   move.l d1,(a0)+
            0xD491,         // PROG + 6   add.l (a1),d2
            0x4E41,         // PROG + 8   trap #1
            0x23C1, 0xFFF0, 0x2004, // PROG + 10 move.l d1,$FFF02004 (device)
            0x51C8, 0xFFF0, // PROG + 16  dbf d0,loop (base PROG + 18, -16 = PROG + 2)
            0x60FE,         // PROG + 20  bra.s *
    };

    private static M68kRig mixed(boolean is040) {
        M68kRig rig = new M68kRig(is040).program(MIXED);
        rig.at(handler(33), 0x4E73);
        rig.cpu.setA(0, DATA);
        rig.cpu.setA(1, DATA + 0x8000);
        rig.poke32(DATA + 0x8000, 0x0101_0101);
        return rig;
    }

    private static int[] snapshot(M68kRig rig) {
        int[] s = new int[24];
        for (int i = 0; i < 8; i++) {
            s[i] = rig.d(i);
            s[8 + i] = rig.a(i);
        }
        s[16] = rig.cpu.pc();
        s[17] = rig.cpu.sr();
        s[18] = (int) rig.cpu.cycles();
        s[19] = (int) rig.cpu.instructionsRetired();
        s[20] = rig.peek32(DATA + 4 * 10);
        s[21] = rig.dev.regs[1];
        s[22] = (int) rig.clock.now();
        return s;
    }

    @Test
    void slicingDoesNotChangeTheResult() {
        for (boolean is040 : new boolean[] {false, true}) {
            M68kRig whole = mixed(is040);
            long target = whole.clock.now() + 3000;
            whole.cpu.run(target, Long.MAX_VALUE);
            M68kRig ones = mixed(is040);
            while (ones.clock.now() < target)
                ones.cpu.run(ones.clock.now() + 1, Long.MAX_VALUE);
            M68kRig sevens = mixed(is040);
            while (sevens.clock.now() < target)
                sevens.cpu.run(Math.min(sevens.clock.now() + 7, target), Long.MAX_VALUE);
            assertArrayEquals(snapshot(whole), snapshot(ones));
            assertArrayEquals(snapshot(whole), snapshot(sevens));
            assertEquals(PROG + 20, whole.cpu.pc());
            assertEquals(11, whole.d(1));
        }
    }

    @Test
    void deviceStopRequestEndsTheRunAfterThatInstruction() {
        M68kRig rig = mixed(false);
        rig.dev.onWrite = () -> rig.clock.requestStopAt(rig.clock.now());
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(rig.clock.now() + 1_000_000, Long.MAX_VALUE));
        assertEquals(PROG + 16, rig.cpu.pc(), "stopped right after the device write");
        assertEquals(rig.cpu.cycles(), rig.clock.now());
    }

    @Test
    void hostDeadlineEndsARunningLoop() {
        M68kRig rig = M68kRig.m68000().program(0x60FE);
        long t0 = rig.clock.now();
        assertEquals(Cpu.Exit.DEADLINE, rig.cpu.run(t0 + 1_000_000_000L, System.nanoTime() - 1));
        assertTrue(rig.clock.now() > t0);
        assertTrue(rig.clock.now() <= t0 + 4096L * 10, "checked at least every 4096 instructions");
    }

    @Test
    void runAtTheHorizonReturnsAtOnce() {
        M68kRig rig = M68kRig.m68000().program(0x4E71);
        long now = rig.clock.now();
        assertEquals(Cpu.Exit.REACHED, rig.cpu.run(now, Long.MAX_VALUE));
        assertEquals(PROG, rig.cpu.pc());
    }
}
