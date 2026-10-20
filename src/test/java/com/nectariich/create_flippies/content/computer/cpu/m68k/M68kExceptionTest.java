package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.SSP;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.USP;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

import java.nio.ByteOrder;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Exceptions, frames per model, interrupts, STOP, trace, privilege, RESET and halts (spec 10.12.1). */
class M68kExceptionTest {

    private static List<M68kRig> both() {
        return List.of(M68kRig.m68000(), M68kRig.m68040());
    }

    @Test
    void m68000AddressErrorOnOddWordRead() {
        M68kRig rig = M68kRig.m68000().program(0x3010); // move.w (a0),d0
        rig.cpu.setA(0, DATA + 1);
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(SSP - 14, sp);
        assertEquals(0x10 | 0x08 | 5, rig.peek16(sp), "read, not an instruction fetch, supervisor data");
        assertEquals(DATA + 1, rig.peek32(sp + 2));
        assertEquals(0x3010, rig.peek16(sp + 6), "instruction register");
        assertEquals(0x2700, rig.peek16(sp + 8));
        assertEquals(PROG + 2, rig.peek32(sp + 10), "instruction + 2 (documented deviation)");
    }

    @Test
    void m68000AddressErrorOnOddJumpAndUserFunctionCode() {
        M68kRig rig = M68kRig.m68000().program(0x4ED0); // jmp (a0)
        rig.userMode();
        rig.cpu.setA(0, PROG + 0x41);
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(SSP - 14, sp);
        assertEquals(0x10 | 2, rig.peek16(sp), "read, instruction fetch, user program");
        assertEquals(PROG + 0x41, rig.peek32(sp + 2));
        assertEquals(0x4ED0, rig.peek16(sp + 6));
        assertEquals(0x0000, rig.peek16(sp + 8));
        assertEquals(USP, rig.cpu.usp());
    }

    /** 68040 user's manual 8.4: format 2, PC = the jumping instruction, address = odd target with bit 0 cleared. */
    @Test
    void m68040OddJumpTakesFormat2WithTheTargetBit0Cleared() {
        M68kRig rig = M68kRig.m68040().program(0x4ED0); // jmp (a0)
        rig.cpu.setA(0, PROG + 0x41);
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(SSP - 12, sp);
        assertEquals(0x2700, rig.peek16(sp));
        assertEquals(PROG, rig.peek32(sp + 2), "the jmp itself");
        assertEquals(0x2000 | 3 * 4, rig.peek16(sp + 6));
        assertEquals(PROG + 0x40, rig.peek32(sp + 8), "odd target with bit 0 cleared");
    }

    @Test
    void m68000OddBranchDisplacementFFIsAnAddressError() {
        M68kRig rig = M68kRig.m68000().program(0x60FF); // bra.s -1 on the 68000
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        assertEquals(PROG + 1, rig.peek32(rig.a(7) + 2));
    }

    @Test
    void m68000BusErrorOnUnmappedAddress() {
        M68kRig rig = M68kRig.m68000().program(0x2039, 0x0010, 0x0000); // move.l $100000,d0
        rig.step();
        assertEquals(handler(2), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(0x10 | 0x08 | 5, rig.peek16(sp));
        assertEquals(0x0010_0000, rig.peek32(sp + 2));
    }

    @Test
    void m68040AccessErrorFormat7() {
        M68kRig rig = M68kRig.m68040().program(0x2039, 0x0010, 0x0000);
        rig.step();
        assertEquals(handler(2), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(SSP - 60, sp);
        assertEquals(0x2700, rig.peek16(sp));
        assertEquals(PROG, rig.peek32(sp + 2), "the faulted instruction, re-run by RTE");
        assertEquals(0x7008, rig.peek16(sp + 6));
        assertEquals(0x0010_0000, rig.peek32(sp + 8));
        assertEquals(0x100 | 5, rig.peek16(sp + 0x0C), "SSW: read, long, supervisor data");
        assertEquals(0x0010_0000, rig.peek32(sp + 0x14), "fault address");
        // RTE of format 7 re-runs the instruction (which faults again)
        rig.at(handler(2), 0x4E73);
        rig.step();
        assertEquals(PROG, rig.cpu.pc());
        assertEquals(SSP, rig.a(7));
    }

    @Test
    void m68040MisalignedLongLoadWorks() {
        M68kRig rig = M68kRig.m68040().program(0x2010); // move.l (a0),d0
        rig.poke32(DATA, 0x1122_3344);
        rig.poke8(DATA + 4, 0x55);
        rig.cpu.setA(0, DATA + 1);
        rig.step();
        assertEquals(0x2233_4455, rig.d(0));
        assertEquals(PROG + 2, rig.cpu.pc());
    }

    @Test
    void privilegeViolationFromUserMode() {
        for (M68kRig rig : both()) {
            rig.program(0x46FC, 0x2700); // move #$2700,sr
            rig.userMode();
            rig.step();
            assertEquals(handler(8), rig.cpu.pc());
            assertEquals(0x2000, rig.cpu.sr() & 0xF000);
            int sp = rig.a(7);
            assertEquals(rig.is040 ? SSP - 8 : SSP - 6, sp);
            assertEquals(0x0000, rig.peek16(sp));
            assertEquals(PROG, rig.peek32(sp + 2), "PC = the instruction");
            assertEquals(USP, rig.cpu.usp());
        }
    }

    @Test
    void moveFromSrPrivilegeDiffersByModel() {
        for (M68kRig rig : both()) {
            rig.program(0x40C0); // move sr,d0
            rig.userMode();
            rig.step();
            assertEquals(rig.is040 ? handler(8) : PROG + 2, rig.cpu.pc());
        }
    }

    @Test
    void trapVectorAndReturnAddress() {
        for (M68kRig rig : both()) {
            rig.program(0x4E43); // trap #3
            rig.step();
            assertEquals(handler(35), rig.cpu.pc());
            int sp = rig.a(7);
            assertEquals(PROG + 2, rig.peek32(sp + 2));
            if (rig.is040)
                assertEquals(35 * 4, rig.peek16(sp + 6), "format 0");
        }
    }

    @Test
    void lineAAndLineF() {
        for (M68kRig rig : both()) {
            rig.program(0xA123);
            rig.step();
            assertEquals(handler(10), rig.cpu.pc());
            assertEquals(PROG, rig.peek32(rig.a(7) + 2));
            rig.program(0xF000);
            rig.step();
            assertEquals(handler(11), rig.cpu.pc());
            rig.program(0xF200, 0x0000); // an FPU general instruction: the stub takes vector 11
            rig.step();
            assertEquals(handler(11), rig.cpu.pc());
            assertEquals(PROG, rig.peek32(rig.a(7) + 2));
            if (rig.is040)
                assertEquals(1, rig.fpu().generals);
        }
    }

    @Test
    void rteRestoresSrAndSwitchesToUserStack() {
        for (M68kRig rig : both()) {
            rig.cpu.setSrHost(0x0000);
            rig.cpu.setA(7, USP);
            rig.cpu.setSrHost(0x2700);
            int sp = SSP - (rig.is040 ? 8 : 6);
            rig.cpu.setA(7, sp);
            rig.poke16(sp, 0x0015);
            rig.poke32(sp + 2, PROG + 0x100);
            rig.poke16(sp + 6, 0x0000);
            rig.program(0x4E73);
            rig.step();
            assertEquals(PROG + 0x100, rig.cpu.pc());
            assertEquals(0x0015, rig.cpu.sr());
            assertEquals(USP, rig.a(7));
            assertEquals(SSP, rig.cpu.isp());
        }
    }

    @Test
    void m68040RteFormat2AndFormatError() {
        M68kRig rig = M68kRig.m68040();
        int sp = SSP - 12;
        rig.cpu.setA(7, sp);
        rig.poke16(sp, 0x2704);
        rig.poke32(sp + 2, PROG + 0x40);
        rig.poke16(sp + 6, 0x2000 | 6 * 4);
        rig.program(0x4E73);
        rig.step();
        assertEquals(PROG + 0x40, rig.cpu.pc());
        assertEquals(SSP, rig.a(7));
        assertEquals(0x2704, rig.cpu.sr());

        sp = SSP - 8;
        rig.cpu.setA(7, sp);
        rig.poke16(sp, 0x2000);
        rig.poke32(sp + 2, PROG + 0x40);
        rig.poke16(sp + 6, 0xF000);
        rig.program(0x4E73);
        rig.step();
        assertEquals(handler(14), rig.cpu.pc());
        assertEquals(sp - 8, rig.a(7));
        assertEquals(PROG, rig.peek32(sp - 6), "format error stacks the RTE");
        assertEquals(14 * 4, rig.peek16(sp - 2));
    }

    @Test
    void m68040ChkFrameHasInstructionAddress() {
        M68kRig rig = M68kRig.m68040().program(0x4181); // chk.w d1,d0
        rig.cpu.setD(0, -1);
        rig.step();
        int sp = rig.a(7);
        assertEquals(SSP - 12, sp);
        assertEquals(PROG + 2, rig.peek32(sp + 2));
        assertEquals(0x2000 | 6 * 4, rig.peek16(sp + 6));
        assertEquals(PROG, rig.peek32(sp + 8));
    }

    @Test
    void m68040InterruptWithMasterBitPushesThrowawayFrame() {
        M68kRig rig = M68kRig.m68040().program(0x4E71);
        int msp = 0x6_0000;
        rig.cpu.setSrHost(0x3000); // S, M, mask 0: A7 is the MSP
        rig.cpu.setA(7, msp);
        rig.cpu.setIrq(2, true);
        rig.step();
        assertEquals(handler(26), rig.cpu.pc());
        assertEquals(0x2200, rig.cpu.sr(), "M cleared, mask 2");
        assertEquals(msp - 8, rig.cpu.msp());
        assertEquals(0x3000, rig.peek16(msp - 8));
        assertEquals(26 * 4, rig.peek16(msp - 2), "format 0 on the MSP");
        int isp = rig.a(7);
        assertEquals(SSP - 8, isp);
        assertEquals(0x1000 | 26 * 4, rig.peek16(isp + 6), "format 1 throwaway on the ISP");
        // RTE pops the throwaway frame, switches to the MSP, then returns through the format 0 frame
        rig.cpu.setIrq(2, false);
        rig.at(handler(26), 0x4E73);
        rig.step();
        assertEquals(PROG, rig.cpu.pc());
        assertEquals(0x3000, rig.cpu.sr());
        assertEquals(msp, rig.a(7));
        assertEquals(SSP, rig.cpu.isp());
    }

    @Test
    void vbrRelocatesTheVectorTable() {
        M68kRig rig = M68kRig.m68040().program(0x4E7B, 0x0801, 0x4E40); // movec d0,vbr; trap #0
        rig.cpu.setD(0, DATA);
        rig.poke32(DATA + 32 * 4, PROG + 0x200);
        rig.steps(2);
        assertEquals(PROG + 0x200, rig.cpu.pc());
        assertEquals(DATA, rig.cpu.vbr());
    }

    @Test
    void interruptLevelsAndMasking() {
        for (M68kRig rig : both()) {
            rig.program(0x4E71, 0x4E71, 0x4E71);
            rig.cpu.setSrHost(0x2200);
            rig.cpu.setIrq(2, true);
            rig.step();
            assertEquals(PROG + 2, rig.cpu.pc(), "level 2 is masked at mask 2");
            rig.cpu.setIrq(3, true);
            rig.step();
            assertEquals(handler(27), rig.cpu.pc());
            assertEquals(0x2300, rig.cpu.sr());
            assertEquals(PROG + 2, rig.peek32(rig.a(7) + 2));
        }
    }

    @Test
    void level7IsTakenOncePerEdge() {
        for (M68kRig rig : both()) {
            rig.program(0x4E71);
            rig.cpu.setIrq(7, true); // mask 7 after reset
            rig.step();
            assertEquals(handler(31), rig.cpu.pc());
            rig.at(handler(31), 0x4E71, 0x4E71, 0x4E71);
            rig.steps(2);
            assertEquals(handler(31) + 4, rig.cpu.pc(), "held level 7 does not retrigger");
            rig.cpu.setIrq(7, false);
            rig.cpu.setIrq(7, true);
            rig.step();
            assertEquals(handler(31), rig.cpu.pc(), "a new edge is taken again");
        }
    }

    @Test
    void interruptRaisedInsideADeviceWriteIsTakenAfterThatInstruction() {
        for (M68kRig rig : both()) {
            rig.program(0x23C0, 0xFFF0, 0x2000, 0x4E71); // move.l d0,$FFF02000
            rig.cpu.setSrHost(0x2000);
            rig.dev.onWrite = () -> rig.cpu.setIrq(5, true);
            rig.step();
            assertEquals(PROG + 6, rig.cpu.pc());
            assertEquals(1, rig.dev.writes.size());
            rig.step();
            assertEquals(handler(29), rig.cpu.pc());
            assertEquals(PROG + 6, rig.peek32(rig.a(7) + 2));
        }
    }

    @Test
    void stopWaitsAndWakesOnAnUnmaskedLevel() {
        for (M68kRig rig : both()) {
            rig.program(0x4E72, 0x2500, 0x4E71); // stop #$2500
            assertEquals(Cpu.Exit.WAITING, rig.runFor(100));
            assertTrue(rig.cpu.stopped());
            assertEquals(Cpu.Exit.WAITING, rig.runFor(100));
            rig.cpu.setIrq(3, true);
            assertEquals(Cpu.Exit.WAITING, rig.runFor(100), "level 3 is masked by 5");
            rig.cpu.setIrq(6, true);
            rig.runFor(1);
            assertFalse(rig.cpu.stopped());
            assertEquals(handler(30), rig.cpu.pc());
            assertEquals(PROG + 4, rig.peek32(rig.a(7) + 2));
        }
    }

    @Test
    void traceAfterEachInstruction() {
        for (M68kRig rig : both()) {
            rig.program(0x4E71, 0x4E71);
            rig.cpu.setSrHost(0xA700);
            rig.step();
            assertEquals(handler(9), rig.cpu.pc());
            assertEquals(0x2700, rig.cpu.sr(), "trace cleared on entry");
            int sp = rig.a(7);
            assertEquals(0xA700, rig.peek16(sp));
            assertEquals(PROG + 2, rig.peek32(sp + 2));
            if (rig.is040) {
                assertEquals(0x2000 | 9 * 4, rig.peek16(sp + 6));
                assertEquals(PROG, rig.peek32(sp + 8));
            }
        }
    }

    @Test
    void traceBeforeInterruptAndAfterGroup2() {
        for (M68kRig rig : both()) {
            rig.program(0x4E71);
            rig.cpu.setSrHost(0xA000);
            rig.cpu.setIrq(3, true);
            rig.cpu.setSrHost(0xA000);
            // the pending interrupt is taken first at the boundary before the NOP (pins were already up)
            rig.step();
            assertEquals(handler(27), rig.cpu.pc());
        }
        for (M68kRig rig : both()) {
            rig.program(0x4E71);
            rig.cpu.setSrHost(0xA000);
            rig.dev.onWrite = () -> { };
            rig.step(); // nop traced
            assertEquals(handler(9), rig.cpu.pc());
            rig.cpu.setIrq(3, true);
            rig.step(); // trace handler not run yet: the interrupt comes first at the boundary
            assertEquals(handler(27), rig.cpu.pc());
            assertEquals(handler(9), rig.peek32(rig.a(7) + 2));
        }
        for (M68kRig rig : both()) {
            rig.program(0x4E40); // trap #0 with trace: the trap frame, then the trace frame on top
            rig.cpu.setSrHost(0xA700);
            rig.step();
            assertEquals(handler(9), rig.cpu.pc());
            assertEquals(handler(32), rig.peek32(rig.a(7) + 2));
        }
    }

    @Test
    void illegalIsNotTraced() {
        for (M68kRig rig : both()) {
            rig.program(0x4AFC);
            rig.cpu.setSrHost(0xA700);
            rig.step();
            assertEquals(handler(4), rig.cpu.pc());
        }
    }

    @Test
    void resetInstructionRequestsADeviceResetAndCosts132() {
        M68kRig rig = M68kRig.m68000().program(0x4E70);
        assertEquals(132, rig.cost());
        assertEquals(1, rig.deviceResets);
        rig.program(0x4E70);
        rig.userMode();
        rig.step();
        assertEquals(1, rig.deviceResets, "privileged");
        assertEquals(handler(8), rig.cpu.pc());
    }

    @Test
    void resetVectorsAreReadOnTheFirstRun() {
        M68kRig rig = M68kRig.m68000();
        assertEquals(PROG, rig.cpu.pc());
        assertEquals(SSP, rig.a(7));
        rig.poke32(4, PROG + 0x10);
        rig.cpu.reset();
        assertEquals(0x2700, rig.cpu.sr());
        rig.step();
        assertEquals(PROG + 0x10, rig.cpu.pc());
    }

    @Test
    void unreadableResetVectorHalts() {
        SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
        bus.setRam(new byte[0x1_0000], 0x10_0000);
        CycleClock clock = new CycleClock();
        M68kCpu cpu = new M68kCpu(CpuHost.of(CpuModel.CPU_68000, 8_000_000, bus, clock, 0), M68kOptions.m68000(), null);
        assertEquals(Cpu.Exit.HALTED, cpu.run(100, Long.MAX_VALUE));
        assertTrue(cpu.halted());
        assertTrue(cpu.haltReason().startsWith("reset vector unreadable"), cpu.haltReason());
    }

    @Test
    void doubleFaultOnOddStackHalts() {
        M68kRig rig = M68kRig.m68000().program(0x4E40);
        rig.cpu.setA(7, SSP + 1);
        assertEquals(Cpu.Exit.HALTED, rig.step());
        assertTrue(rig.cpu.haltReason().contains("double fault"), rig.cpu.haltReason());
        assertEquals(Cpu.Exit.HALTED, rig.step());
    }

    @Test
    void m68000OddHandlerRaisesAddressError() {
        M68kRig rig = M68kRig.m68000().program(0x4E40);
        rig.poke32(32 * 4, PROG + 0x31);
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        assertEquals(PROG + 0x31, rig.peek32(rig.a(7) + 2));
    }
}
