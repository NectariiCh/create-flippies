package com.nectariich.create_flippies.content.computer.cpu.rv32;

import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Enc.*;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.DEV;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.HANDLER;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM;
import static com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Rig.RAM_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import org.junit.jupiter.api.Test;

/** Exceptions, interrupts, MRET, WFI and the double-fault halt (spec 4.5). */
class Rv32TrapTest {

    private static final int MIE_BIT = 8;
    private static final int MTIE = 1 << Rv32Map.IRQ_PIN_MTIP;
    private static final int MSIE = 1 << Rv32Map.IRQ_PIN_MSIP;
    private static final int MEIE = 1 << Rv32Map.IRQ_PIN_MEIP;

    private static Rv32Rig rig(int... program) {
        Rv32Rig rig = new Rv32Rig();
        rig.mtvec(HANDLER);
        rig.program(program);
        rig.at(HANDLER, jal(ZERO, 0));
        return rig;
    }

    /** One instruction that must trap with {@code cause}, mepc = RAM_BASE and {@code tval}. */
    private static Rv32Rig assertTrap(int inst, int cause, int tval) {
        Rv32Rig rig = rig(inst);
        long retired = rig.cpu.instructionsRetired();
        rig.step();
        assertEquals(cause, rig.csr(Rv32Csr.MCAUSE), "cause of " + Integer.toHexString(inst));
        assertEquals(RAM, rig.csr(Rv32Csr.MEPC));
        assertEquals(tval, rig.csr(Rv32Csr.MTVAL));
        assertEquals(HANDLER, rig.cpu.pc());
        assertEquals(retired, rig.cpu.instructionsRetired(), "a trapping instruction does not retire");
        return rig;
    }

    /** Sets mie and mstatus.MIE through real CSR writes. */
    private static void enable(Rv32Rig rig, int mie, boolean globally) {
        rig.setup(concat(li(T6, mie), csrw(Rv32Csr.MIE, T6), csrrsi(ZERO, Rv32Csr.MSTATUS, globally ? MIE_BIT : 0)));
    }

    private static int[] concat(int[] a, int... b) {
        return Rv32Rig.concat(a, b);
    }

    // ---- exceptions ----

    @Test
    void ecallAndEbreak() {
        assertTrap(ECALL, 11, 0);
        assertTrap(EBREAK, 3, RAM);
    }

    @Test
    void illegalWords() {
        int[] words = { 0x0000_0000, 0xFFFF_FFFF, UNIMP, 0x0000_0001, 0x0000_4502,
                0x1020_0073, // sret
                0x0020_0073, // uret
                0x7B20_0073, // dret
                0x1200_0073, // sfence.vma
                i(0, 0, 2, 0, 0x0F), // MISC-MEM funct3 010
                i(0, 0, 7, 0, 0x0F),
                0x0005_2007, // flw: LOAD-FP
                0x0805_252F, // amoswap.w: AMO
                0x0000_0053, // fadd.s: OP-FP
                i(0, A1, 3, A0, 0x03), // ld
                i(0, A1, 6, A0, 0x03), // lwu
                s(0, A2, A1, 3), // sd
                b(8, A2, A1, 2), b(8, A2, A1, 3), // branch funct3 010 and 011
                i(0, A1, 1, A0, 0x67), // jalr funct3 001
                i(0x020, A1, 1, A0, 0x13), // slli with bit 25 set
                i(0x020, A1, 5, A0, 0x13), // srli with bit 25 set
                i(0x420, A1, 5, A0, 0x13), // srai with bit 25 set
                i(0x200, A1, 5, A0, 0x13), // srli with funct7 0010000
                i(0x400, A1, 1, A0, 0x13), // slli with funct7 0100000
                r(0x20, A2, A1, 1, A0, 0x33), // funct7 0100000 with sll
                r(0x02, A2, A1, 0, A0, 0x33),
                r(0x01, A2, A1, 0, A0, 0x3B), // mulw: OP-32
                0x0000_001B, // addiw: OP-IMM-32
        };
        for (int w : words)
            assertTrap(w, 2, w);
    }

    @Test
    void fencesAreOneCycleNoOps() {
        int[] words = { fence(0xF, 0xF), 0x9000_000F, i(0x0FF, 0, 0, 5, 0x0F), i(0x033, 7, 0, 0, 0x0F),
                0x8330_000F, // fence.tso
                0x0100_000F, // pause
                fenceI(), i(0x123, 7, 1, 9, 0x0F) };
        for (int w : words) {
            Rv32Rig rig = rig(w);
            long c = rig.cpu.cycles();
            rig.cpu.setReg(5, 1234);
            rig.cpu.setReg(9, 1234);
            rig.step();
            assertEquals(RAM + 4, rig.cpu.pc(), Integer.toHexString(w));
            assertEquals(c + 1, rig.cpu.cycles());
            assertEquals(1234, rig.x(5), "rd is not written");
            assertEquals(1234, rig.x(9));
        }
    }

    @Test
    void accessFaults() {
        Rv32Rig load = rig(lw(A0, A1, 0));
        load.cpu.setReg(A1, 0x4000_0000);
        load.step();
        assertEquals(5, load.csr(Rv32Csr.MCAUSE));
        assertEquals(0x4000_0000, load.csr(Rv32Csr.MTVAL));

        Rv32Rig nul = rig(lbu(A0, ZERO, 0x10));
        nul.step();
        assertEquals(5, nul.csr(Rv32Csr.MCAUSE), "page zero is unmapped");

        Rv32Rig store = rig(sw(A0, A1, 4));
        store.cpu.setReg(A1, 0x4000_0000);
        store.step();
        assertEquals(7, store.csr(Rv32Csr.MCAUSE));
        assertEquals(0x4000_0004, store.csr(Rv32Csr.MTVAL));

        Rv32Rig rom = rig(sb(A0, A1, 0));
        rom.cpu.setReg(A1, Rv32Map.ROM_BASE + 0x10);
        rom.step();
        assertEquals(7, rom.csr(Rv32Csr.MCAUSE), "ROM is read-only");
        assertEquals(Rv32Map.ROM_BASE + 0x10, rom.csr(Rv32Csr.MTVAL));

        Rv32Rig crossing = rig(lw(A0, A1, 0));
        crossing.cpu.setReg(A1, RAM + RAM_SIZE - 2);
        crossing.step();
        assertEquals(5, crossing.csr(Rv32Csr.MCAUSE), "a word crossing the RAM end");

        Rv32Rig device = rig(lw(A0, A1, 2));
        device.cpu.setReg(A1, DEV);
        device.cpu.setReg(A0, 99);
        device.step();
        assertEquals(5, device.csr(Rv32Csr.MCAUSE), "misaligned device access");
        assertEquals(99, device.x(A0));
        assertEquals(0, device.device.reads);
    }

    @Test
    void fetchFromUnmappedOrDeviceSpaceFaults() {
        for (int target : new int[] { 0x4000_0000, DEV, RAM + RAM_SIZE }) {
            Rv32Rig rig = rig(jalr(ZERO, A1, 0));
            rig.cpu.setReg(A1, target);
            rig.steps(2);
            assertEquals(1, rig.csr(Rv32Csr.MCAUSE), Integer.toHexString(target));
            assertEquals(target, rig.csr(Rv32Csr.MEPC));
            assertEquals(target, rig.csr(Rv32Csr.MTVAL));
            assertEquals(HANDLER, rig.cpu.pc());
        }
    }

    @Test
    void misalignedJumpsTrapOnTheJumpWithoutWritingRd() {
        Rv32Rig jalr = rig(jalr(RA, RA, 2));
        jalr.cpu.setReg(RA, RAM + 0x40);
        long retired = jalr.cpu.instructionsRetired();
        jalr.step();
        assertEquals(0, jalr.csr(Rv32Csr.MCAUSE));
        assertEquals(RAM, jalr.csr(Rv32Csr.MEPC));
        assertEquals(RAM + 0x42, jalr.csr(Rv32Csr.MTVAL));
        assertEquals(RAM + 0x40, jalr.x(RA), "rd == rs1 not overwritten");
        assertEquals(retired, jalr.cpu.instructionsRetired());
        assertEquals(retired, jalr.csr(Rv32Csr.MINSTRET) & 0xFFFF_FFFFL, "minstret does not count it");

        Rv32Rig jal = assertTrap(jal(RA, 6), 0, RAM + 6);
        assertEquals(0, jal.x(RA));

        assertTrap(beq(ZERO, ZERO, 2), 0, RAM + 2);
        assertTrap(bgeu(ZERO, ZERO, -6), 0, RAM - 6);
    }

    @Test
    void trapEntryStacksMieAndMretRestoresIt() {
        Rv32Rig rig = rig(ECALL);
        rig.setup(csrrsi(ZERO, Rv32Csr.MSTATUS, MIE_BIT));
        rig.at(HANDLER, csrr(A0, Rv32Csr.MSTATUS), csrr(T0, Rv32Csr.MEPC), addi(T0, T0, 4), csrw(Rv32Csr.MEPC, T0), MRET);
        rig.step();
        assertEquals(0x1880, rig.csr(Rv32Csr.MSTATUS), "MPIE = old MIE, MIE = 0");
        rig.steps(5);
        assertEquals(RAM + 4, rig.cpu.pc());
        assertEquals(0x1888, rig.csr(Rv32Csr.MSTATUS), "MIE = MPIE, MPIE = 1");
    }

    @Test
    void mretCostsThreeCyclesAndSetsMpie() {
        Rv32Rig rig = rig(MRET);
        rig.setup(concat(li(T6, RAM + 0x100), csrw(Rv32Csr.MEPC, T6)));
        long c = rig.cpu.cycles();
        rig.step();
        assertEquals(RAM + 0x100, rig.cpu.pc());
        assertEquals(c + 3, rig.cpu.cycles());
        assertEquals(0x1880, rig.csr(Rv32Csr.MSTATUS), "MIE = old MPIE (0), MPIE = 1");
    }

    @Test
    void exceptionsGoToBaseEvenWhenVectored() {
        Rv32Rig rig = new Rv32Rig().program(ECALL);
        rig.mtvec(HANDLER | 1);
        rig.step();
        assertEquals(HANDLER, rig.cpu.pc());
    }

    @Test
    void doubleFaultHalts() {
        Rv32Rig rig = new Rv32Rig().program(ECALL);
        assertEquals(Cpu.Exit.HALTED, rig.runFor(100));
        assertTrue(rig.cpu.halted());
        assertEquals("trap handler at 0x00000000 is not executable (mcause 11, mepc 0x80000000)", rig.cpu.haltReason());
        assertEquals(Cpu.Exit.HALTED, rig.runFor(100), "stays halted");
        rig.cpu.reset();
        assertFalse(rig.cpu.halted());
        assertEquals(RAM, rig.cpu.pc());
    }

    @Test
    void aHostSetPcRightAfterATrapIsAPlainFetchFault() {
        Rv32Rig rig = rig(ECALL);
        rig.step();
        rig.cpu.setPc(0x100);
        rig.step();
        assertFalse(rig.cpu.halted(), "the host moved pc; this is not the handler failing");
        assertEquals(1, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(0x100, rig.csr(Rv32Csr.MEPC));
        assertEquals(HANDLER, rig.cpu.pc());
    }

    @Test
    void aFaultingHandlerThatRetiredSomethingIsNotADoubleFault() {
        Rv32Rig rig = rig(ECALL);
        rig.at(HANDLER, jalr(ZERO, ZERO, 0x100));
        rig.steps(3);
        assertFalse(rig.cpu.halted());
        assertEquals(1, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(HANDLER, rig.cpu.pc());
    }

    @Test
    void trapEntryCostsTwoCycles() {
        Rv32Rig rig = rig(ECALL);
        long c = rig.cpu.cycles();
        rig.step();
        assertEquals(c + 1 + 2, rig.cpu.cycles());
        Rv32Rig load = rig(lw(A0, A1, 0));
        load.cpu.setReg(A1, 0x4000_0000);
        c = load.cpu.cycles();
        load.step();
        assertEquals(c + 2 + 2, load.cpu.cycles(), "a faulting load costs the load plus trap entry");
    }

    // ---- interrupts ----

    @Test
    void timerInterruptIsTakenAtTheNextBoundary() {
        Rv32Rig rig = rig(NOP, NOP, NOP);
        enable(rig, MTIE, true);
        rig.step();
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        rig.step();
        assertEquals(0x8000_0007, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(RAM + 4, rig.csr(Rv32Csr.MEPC), "the next instruction to execute");
        assertEquals(0, rig.csr(Rv32Csr.MTVAL));
        assertEquals(HANDLER, rig.cpu.pc());
        assertEquals(1 << 7, rig.csr(Rv32Csr.MIP));
    }

    @Test
    void maskedInterruptsAreNotTaken() {
        Rv32Rig rig = rig(NOP, NOP, NOP);
        enable(rig, MTIE, false);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        rig.steps(3);
        assertEquals(RAM + 12, rig.cpu.pc());
        Rv32Rig notEnabled = rig(NOP, NOP);
        enable(notEnabled, MEIE, true);
        notEnabled.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        notEnabled.steps(2);
        assertEquals(RAM + 8, notEnabled.cpu.pc());
    }

    @Test
    void priorityIsExternalThenSoftwareThenTimer() {
        Rv32Rig rig = rig(NOP);
        enable(rig, MTIE | MSIE | MEIE, true);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MSIP, true);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MEIP, true);
        rig.step();
        assertEquals(0x8000_000B, rig.csr(Rv32Csr.MCAUSE));
        Rv32Rig two = rig(NOP);
        enable(two, MTIE | MSIE, true);
        two.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        two.cpu.setIrq(Rv32Map.IRQ_PIN_MSIP, true);
        two.step();
        assertEquals(0x8000_0003, two.csr(Rv32Csr.MCAUSE));
    }

    @Test
    void vectoredModeSendsInterruptsToBasePlusFourTimesCause() {
        Rv32Rig rig = new Rv32Rig().program(NOP);
        rig.mtvec(HANDLER | 1);
        enable(rig, MTIE | MEIE, true);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        rig.step();
        assertEquals(HANDLER + 4 * 7, rig.cpu.pc());
    }

    @Test
    void enablingByCsrWriteTakesThePendingInterruptAfterTheWrite() {
        Rv32Rig rig = rig(NOP, csrrsi(ZERO, Rv32Csr.MSTATUS, MIE_BIT), NOP);
        enable(rig, MTIE, false);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        rig.steps(2);
        assertEquals(HANDLER, rig.cpu.pc());
        assertEquals(RAM + 8, rig.csr(Rv32Csr.MEPC));
    }

    @Test
    void interruptCostsTwoCycles() {
        Rv32Rig rig = rig(NOP);
        enable(rig, MTIE, true);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        long c = rig.cpu.cycles();
        rig.step();
        assertEquals(c + 2, rig.cpu.cycles());
    }

    @Test
    void aPinRaisedInsideADeviceAccessIsTakenAfterThatInstruction() {
        Rv32Rig rig = rig(sw(ZERO, A1, 0), NOP, NOP);
        rig.cpu.setReg(A1, DEV);
        enable(rig, MEIE, true);
        rig.device.onWrite = () -> rig.cpu.setIrq(Rv32Map.IRQ_PIN_MEIP, true);
        rig.runFor(1000);
        assertEquals(0x8000_000B, rig.csr(Rv32Csr.MCAUSE));
        assertEquals(RAM + 4, rig.csr(Rv32Csr.MEPC));
    }

    @Test
    void unknownPinsAreRejected() {
        Rv32Cpu cpu = new Rv32Rig().cpu;
        assertThrows(IllegalArgumentException.class, () -> cpu.setIrq(0, true));
        assertThrows(IllegalArgumentException.class, () -> cpu.setIrq(5, true));
        assertThrows(IllegalArgumentException.class, () -> cpu.setIrq(9, true));
    }

    // ---- WFI ----

    @Test
    void wfiWaitsAndWakesWithoutMieContinuingAfterIt() {
        Rv32Rig rig = rig(WFI, addi(A0, ZERO, 42), jal(ZERO, 0));
        enable(rig, MTIE, false);
        long c = rig.cpu.cycles();
        assertEquals(Cpu.Exit.WAITING, rig.runFor(1000));
        assertEquals(c + 1, rig.cpu.cycles(), "WFI costs 1 cycle");
        assertEquals(c + 1, rig.clock.now(), "count published");
        assertEquals(RAM + 4, rig.cpu.pc());
        rig.clock.advanceTo(5000);
        assertEquals(Cpu.Exit.WAITING, rig.runFor(1000), "still asleep");
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MSIP, true);
        assertEquals(Cpu.Exit.WAITING, rig.runFor(1000), "a pin not enabled in mie does not wake it");
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        assertEquals(Cpu.Exit.REACHED, rig.runFor(1000));
        assertEquals(42, rig.x(A0));
        assertEquals(0, rig.csr(Rv32Csr.MCAUSE), "no interrupt taken with MIE = 0");
    }

    @Test
    void wfiWakesIntoTheHandlerWithMie() {
        Rv32Rig rig = rig(WFI, NOP);
        enable(rig, MEIE, true);
        assertEquals(Cpu.Exit.WAITING, rig.runFor(1000));
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MEIP, true);
        rig.step();
        assertEquals(HANDLER, rig.cpu.pc());
        assertEquals(RAM + 4, rig.csr(Rv32Csr.MEPC), "the instruction after WFI");
    }

    @Test
    void wfiWithAnEnabledPendingInterruptDoesNotSleep() {
        Rv32Rig rig = rig(WFI, addi(A0, ZERO, 1));
        enable(rig, MTIE, false);
        rig.cpu.setIrq(Rv32Map.IRQ_PIN_MTIP, true);
        long retired = rig.cpu.instructionsRetired();
        assertEquals(Cpu.Exit.REACHED, rig.runFor(2));
        assertEquals(1, rig.x(A0));
        assertEquals(retired + 2, rig.cpu.instructionsRetired(), "WFI retires");
    }
}
