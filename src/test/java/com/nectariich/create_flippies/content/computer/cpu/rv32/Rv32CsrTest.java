package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.HANDLER;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.HZ;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;

import org.junit.jupiter.api.Test;

/** Zicsr and Zicntr: the six CSR ops, which CSRs exist, read-only rules and the counters (spec 4.4). */
class Rv32CsrTest {

    /** A rig with a trap handler, running {@code program} from RAM_BASE. */
    private static Rv32Rig rig(int... program) {
        Rv32Rig rig = new Rv32Rig();
        rig.mtvec(HANDLER);
        rig.program(program);
        return rig;
    }

    private static void assertIllegal(int inst) {
        Rv32Rig rig = rig(inst);
        rig.cpu.setReg(A0, 0x55);
        rig.step();
        assertEquals(2, rig.csr(Rv32Csr.MCAUSE), "illegal: " + Integer.toHexString(inst));
        assertEquals(inst, rig.csr(Rv32Csr.MTVAL));
        assertEquals(RAM, rig.csr(Rv32Csr.MEPC));
        assertEquals(HANDLER, rig.cpu.pc());
        assertEquals(0x55, rig.x(A0), "rd untouched");
    }

    private static void assertLegal(int inst) {
        Rv32Rig rig = rig(inst);
        rig.step();
        assertEquals(RAM + 4, rig.cpu.pc(), "legal: " + Integer.toHexString(inst));
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE));
    }

    // ---- the six ops ----

    @Test
    void readModifyWriteSemanticsOfAllSixOps() {
        Rv32Rig rig = rig(csrrw(A0, Rv32Csr.MSCRATCH, A1), csrrs(A2, Rv32Csr.MSCRATCH, A3),
                csrrc(A4, Rv32Csr.MSCRATCH, A5), csrrwi(A6, Rv32Csr.MSCRATCH, 0x1F),
                csrrsi(A7, Rv32Csr.MSCRATCH, 0x0A), csrrci(T0, Rv32Csr.MSCRATCH, 0x03),
                csrr(T1, Rv32Csr.MSCRATCH));
        rig.cpu.setReg(A1, 0xF0F0_0000);
        rig.cpu.setReg(A3, 0x0000_0F0F);
        rig.cpu.setReg(A5, 0x00F0_000F);
        rig.steps(7);
        assertEquals(0, rig.x(A0), "csrrw returns the old value");
        assertEquals(0xF0F0_0000, rig.x(A2));
        assertEquals(0xF0F0_0F0F, rig.x(A4), "csrrs set the bits");
        assertEquals(0xF000_0F00, rig.x(A6), "csrrc cleared the bits");
        assertEquals(0x1F, rig.x(A7), "csrrwi wrote the zero-extended immediate");
        assertEquals(0x1F, rig.x(T0), "csrrsi of 0x0A changed nothing visible");
        assertEquals(0x1C, rig.x(T1), "csrrci cleared bits 0 and 1");
        assertEquals(0x1C, rig.csr(Rv32Csr.MSCRATCH));
    }

    @Test
    void csrrwWithRdX0StillWrites() {
        Rv32Rig rig = rig(csrw(Rv32Csr.MSCRATCH, A1));
        rig.cpu.setReg(A1, 123);
        rig.step();
        assertEquals(123, rig.csr(Rv32Csr.MSCRATCH));
    }

    // ---- read-only and unknown CSRs ----

    @Test
    void readingReadOnlyCsrsWithoutWritingIsLegal() {
        assertLegal(csrr(A0, Rv32Csr.MHARTID));
        assertLegal(csrrsi(A0, Rv32Csr.MHARTID, 0));
        assertLegal(csrrc(A0, Rv32Csr.CYCLE, ZERO));
        assertLegal(csrrci(A0, Rv32Csr.TIME, 0));
    }

    @Test
    void writingReadOnlyCsrsIsIllegal() {
        assertIllegal(UNIMP);
        assertIllegal(csrrw(A0, Rv32Csr.MHARTID, ZERO));
        assertIllegal(csrrs(A0, Rv32Csr.MVENDORID, A1));
        assertIllegal(csrrsi(A0, Rv32Csr.INSTRET, 1));
        assertIllegal(csrrci(A0, Rv32Csr.TIMEH, 4));
        assertIllegal(csrrw(ZERO, Rv32Csr.HPMCOUNTER3, A1));
        assertIllegal(csrrwi(ZERO, Rv32Csr.MCONFIGPTR, 0));
    }

    @Test
    void unknownCsrsAreIllegal() {
        assertIllegal(csrr(A0, 0x3A0));
        assertIllegal(csrr(A0, 0x7C0));
        assertIllegal(csrr(A0, 0x180));
        assertIllegal(csrrw(A0, 0x800, A1));
        assertIllegal(csrr(A0, 0xB01));
        assertIllegal(csrr(A0, 0xF16));
        assertIllegal(i(Rv32Csr.MSCRATCH, A1, 4, A0, 0x73));
        assertThrows(IllegalArgumentException.class, () -> new Rv32Rig().cpu.csr(0x3A0));
    }

    // ---- values ----

    @Test
    void identificationValues() {
        Rv32Rig rig = new Rv32Rig();
        assertEquals(0x4000_1100, rig.csr(Rv32Csr.MISA));
        assertEquals(0, rig.csr(Rv32Csr.MVENDORID));
        assertEquals(0, rig.csr(Rv32Csr.MARCHID));
        assertEquals(1, rig.csr(Rv32Csr.MIMPID));
        assertEquals(0, rig.csr(Rv32Csr.MHARTID));
        assertEquals(0, rig.csr(Rv32Csr.MCONFIGPTR));
        assertEquals(0x1800, rig.csr(Rv32Csr.MSTATUS), "MPP reads 0b11");
    }

    @Test
    void hardwiredRegistersIgnoreWrites() {
        Rv32Rig rig = rig(csrw(Rv32Csr.MISA, A1), csrw(Rv32Csr.MIP, A1), csrw(Rv32Csr.MSTATUSH, A1),
                csrw(Rv32Csr.MCOUNTINHIBIT, A1), csrw(Rv32Csr.MHPMCOUNTER3, A1), csrw(Rv32Csr.MHPMCOUNTER31H, A1),
                csrw(Rv32Csr.MHPMEVENT3, A1), csrw(0x33F, A1));
        rig.cpu.setReg(A1, -1);
        rig.steps(8);
        assertEquals(RAM + 32, rig.cpu.pc(), "all legal");
        assertEquals(0x4000_1100, rig.csr(Rv32Csr.MISA));
        assertEquals(0, rig.csr(Rv32Csr.MIP), "mip is driven by pins only");
        for (int csr : new int[] { Rv32Csr.MSTATUSH, Rv32Csr.MCOUNTINHIBIT, Rv32Csr.MHPMCOUNTER3,
                Rv32Csr.MHPMCOUNTER31H, Rv32Csr.MHPMEVENT3, Rv32Csr.MHPMEVENT31, Rv32Csr.HPMCOUNTER3,
                Rv32Csr.HPMCOUNTER31H, 0xB1F, 0xC1F })
            assertEquals(0, rig.csr(csr), Integer.toHexString(csr));
    }

    @Test
    void writableFieldsAreMasked() {
        Rv32Rig rig = rig(csrw(Rv32Csr.MIE, A1), csrw(Rv32Csr.MEPC, A1), csrw(Rv32Csr.MCAUSE, A1),
                csrw(Rv32Csr.MTVAL, A1), csrw(Rv32Csr.MTVEC, A2), csrr(A3, Rv32Csr.MTVEC), csrw(Rv32Csr.MTVEC, A4));
        rig.cpu.setReg(A1, -1);
        rig.cpu.setReg(A2, 0x8000_1003);
        rig.cpu.setReg(A4, 0x8000_1001);
        rig.steps(7);
        assertEquals(0x888, rig.csr(Rv32Csr.MIE));
        assertEquals(0xFFFF_FFFC, rig.csr(Rv32Csr.MEPC), "mepc bits 1..0 read 0");
        assertEquals(-1, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(-1, rig.csr(Rv32Csr.MTVAL));
        assertEquals(0x8000_1000, rig.x(A3), "mode 3 stored as 0");
        assertEquals(0x8000_1001, rig.csr(Rv32Csr.MTVEC), "vectored kept");
    }

    @Test
    void mstatusKeepsOnlyMieAndMpie() {
        Rv32Rig rig = rig(csrw(Rv32Csr.MSTATUS, A1), csrr(A0, Rv32Csr.MSTATUS), csrrci(ZERO, Rv32Csr.MSTATUS, 8),
                csrr(A2, Rv32Csr.MSTATUS));
        rig.cpu.setReg(A1, -1);
        rig.steps(4);
        assertEquals(0x1888, rig.x(A0));
        assertEquals(0x1880, rig.x(A2));
    }

    // ---- counters ----

    @Test
    void mcycleAndMinstretCount() {
        Rv32Rig rig = new Rv32Rig().program(NOP, NOP, div(A1, A1, A1), csrr(A0, Rv32Csr.MCYCLE),
                csrr(A2, Rv32Csr.MINSTRET), csrr(A3, Rv32Csr.CYCLE), csrr(A4, Rv32Csr.INSTRET),
                csrr(A5, Rv32Csr.MCYCLEH), csrr(A6, Rv32Csr.INSTRETH));
        rig.steps(9);
        assertEquals(1 + 1 + 33, rig.x(A0), "cycles before the reading instruction");
        assertEquals(4, rig.x(A2), "instructions retired before the reading instruction");
        assertEquals(37, rig.x(A3));
        assertEquals(6, rig.x(A4));
        assertEquals(0, rig.x(A5));
        assertEquals(0, rig.x(A6));
        assertEquals(9, rig.cpu.instructionsRetired());
        assertEquals(41, rig.cpu.cycles());
    }

    @Test
    void aCounterWriteIsWhatTheNextInstructionReads() {
        Rv32Rig rig = new Rv32Rig().program(csrw(Rv32Csr.MCYCLE, A1), csrr(A0, Rv32Csr.MCYCLE),
                csrw(Rv32Csr.MINSTRET, A2), csrr(A3, Rv32Csr.MINSTRET), csrr(A4, Rv32Csr.INSTRET));
        rig.cpu.setReg(A1, 1000);
        rig.cpu.setReg(A2, 500);
        rig.steps(5);
        assertEquals(1000, rig.x(A0));
        assertEquals(500, rig.x(A3));
        assertEquals(501, rig.x(A4));
        assertEquals(1000 + 4, rig.csr(Rv32Csr.MCYCLE));
        assertEquals(5, rig.cpu.cycles(), "cycles() has no write offset");
        assertEquals(5, rig.cpu.instructionsRetired());
    }

    @Test
    void writingOneHalfKeepsTheOther() {
        Rv32Rig rig = new Rv32Rig().program(NOP, NOP, NOP, NOP, NOP, csrw(Rv32Csr.MCYCLEH, A1),
                csrr(A0, Rv32Csr.MCYCLE), csrr(A2, Rv32Csr.MCYCLEH), csrw(Rv32Csr.MINSTRETH, A1),
                csrr(A3, Rv32Csr.MINSTRET), csrr(A4, Rv32Csr.MINSTRETH), csrw(Rv32Csr.MCYCLE, A5),
                csrr(A6, Rv32Csr.MCYCLEH));
        rig.cpu.setReg(A1, 7);
        rig.cpu.setReg(A5, -1);
        rig.steps(13);
        assertEquals(5, rig.x(A0), "low half as the writing instruction read it");
        assertEquals(7, rig.x(A2));
        assertEquals(8, rig.x(A3), "low half as the writing instruction read it");
        assertEquals(7, rig.x(A4));
        assertEquals(7, rig.x(A6), "writing the low half kept the high half");
        assertEquals(0x0000_0008_0000_0000L, (long) rig.csr(Rv32Csr.MCYCLEH) << 32 | (rig.csr(Rv32Csr.MCYCLE) & 0xFFFF_FFFFL),
                "after one more cycle the low half carried into the high half");
    }

    @Test
    void countersStartAtZeroAfterReset() {
        Rv32Rig rig = new Rv32Rig().program(NOP, NOP, NOP);
        rig.steps(3);
        rig.cpu.reset();
        rig.clock.advanceTo(500);
        rig.step();
        assertEquals(1, rig.csr(Rv32Csr.MCYCLE));
        assertEquals(1, rig.csr(Rv32Csr.MINSTRET));
        assertEquals(501, rig.cpu.cycles());
    }

    @Test
    void mcycleFollowsTheClockBetweenRuns() {
        Rv32Rig rig = new Rv32Rig().program(NOP, csrr(A0, Rv32Csr.MCYCLE), csrr(A1, Rv32Csr.TIME), csrr(A2, Rv32Csr.TIMEH));
        rig.step();
        rig.clock.advanceTo(50_000_000_000L);
        rig.steps(3);
        assertEquals(50_000_000_000L & 0xFFFF_FFFFL, rig.x(A0) & 0xFFFF_FFFFL);
        long micros = CycleClock.cyclesToMicros(50_000_000_001L, HZ);
        assertEquals(5_000_000_000L, micros);
        assertEquals((int) micros, rig.x(A1), "time agrees with cyclesToMicros");
        assertEquals((int) (CycleClock.cyclesToMicros(50_000_000_002L, HZ) >>> 32), rig.x(A2));
        assertEquals(1, rig.x(A2));
    }

    @Test
    void timeIsMicrosecondsOfTheCpuCycleCount() {
        Rv32Rig rig = new Rv32Rig().program(csrr(A0, Rv32Csr.TIME));
        rig.clock.advanceTo(123_456_789);
        rig.step();
        assertEquals(12_345_678, rig.x(A0));
    }

    // ---- names ----

    @Test
    void namesCoverEveryImplementedCsr() {
        Rv32Rig rig = new Rv32Rig();
        int implemented = 0;
        for (int csr = 0; csr < 0x1000; csr++) {
            boolean exists;
            try {
                rig.cpu.csr(csr);
                exists = true;
            } catch (IllegalArgumentException e) {
                exists = false;
            }
            if (exists) {
                implemented++;
                assertNotNull(Rv32Csr.name(csr), Integer.toHexString(csr));
            } else {
                assertNull(Rv32Csr.name(csr), Integer.toHexString(csr));
            }
        }
        assertEquals("mhpmcounter31h", Rv32Csr.name(0xB9F));
        assertEquals("hpmcounter3", Rv32Csr.name(0xC03));
        assertEquals(26 + 29 * 5, implemented);
    }
}
