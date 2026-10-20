package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Confirmed defects found by an adversarial review of the 68000/68040 core and FPU. Every test
 * here fails against the current code; each names the reference it is checked against.
 */
class ReviewM68kCoreTest {

    private static final int N = 8;

    private static M68kRig run1(M68kRig rig, int d0, int d1, int ccr, int... words) {
        rig.program(words);
        rig.cpu.setD(0, d0);
        rig.cpu.setD(1, d1);
        rig.ccr(ccr);
        rig.step();
        return rig;
    }

    // ---- BCD with invalid digits ----
    // Reference: the hardware-verified model in flamewing's 68k-bcd-verifier (bcd-emul.cc), the
    // same source as spec 10.3.4's N and V rule. SBCD corrects a nibble only on a binary borrow;
    // ABCD corrects on a binary carry or a decimal carry of the uncorrected binary sum.

    @Test
    void sbcdInvalidLowDigitWithoutBorrowIsNotCorrected() {
        M68kRig rig = M68kRig.m68000();
        run1(rig, 0x0A, 0x00, 0, 0x8101); // sbcd d1,d0: 0x0A - 0x00
        assertEquals(0x0A, rig.d(0) & 0xFF);
        assertEquals(0, rig.ccr());
    }

    @Test
    void abcdInvalidHighDigitCarryRule() {
        M68kRig rig = M68kRig.m68000();
        run1(rig, 0x8F, 0x05, 0, 0xC101); // abcd d1,d0: 0x8F + 0x05
        assertEquals(0x9A, rig.d(0) & 0xFF);
        assertEquals(N, rig.ccr());
    }

    // ---- 68000 address error function code ----
    // Reference: PRM 2.2 (PC relative modes: "the reference is classified as a program
    // reference") and the 68000 UM function codes (110 = supervisor program).

    @Test
    void pcRelativeOddReadIsProgramSpace() {
        M68kRig rig = M68kRig.m68000();
        rig.program(0x303A, 0x0001); // move.w 1(pc),d0: 0x1003
        rig.step();
        assertEquals(handler(3), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(PROG + 3, rig.peek32(sp + 2));
        assertEquals(6, rig.peek16(sp) & 7);
    }

    // ---- FPU-raised pre-instruction exceptions and trace ----
    // Spec 10.3.10: illegal, privilege, line A and line F do not retire and are not traced;
    // BSUN is a pre-instruction exception (10.2.4), so the FBcc never executes and has no trace.

    /** A 68040 with the real FPU on 1 MiB of RAM; vector N points at handler(N), SSP = 0x8_0000. */
    private static final class Real {
        final byte[] ram = new byte[M68kRig.RAM_SIZE];
        final com.nectariich.create_flippies.content.computer.bus.CycleClock clock =
                new com.nectariich.create_flippies.content.computer.bus.CycleClock();
        final M68kCpu cpu;

        Real() {
            com.nectariich.create_flippies.content.computer.bus.SystemBus bus =
                    new com.nectariich.create_flippies.content.computer.bus.SystemBus(java.nio.ByteOrder.BIG_ENDIAN);
            bus.setRam(ram, 0);
            com.nectariich.create_flippies.content.computer.cpu.CpuHost host =
                    new com.nectariich.create_flippies.content.computer.cpu.CpuHost(
                            com.nectariich.create_flippies.content.computer.cpu.CpuModel.CPU_68040, 25_000_000, bus,
                            null, clock, null, () -> { }, 0);
            cpu = new M68kCpu(host, M68kOptions.m68040(false),
                    (h, strict) -> new com.nectariich.create_flippies.content.computer.cpu.m68k.fpu.M68kFpu(h, strict));
            put32(0, M68kRig.SSP);
            put32(4, PROG);
            for (int n = 2; n < 256; n++)
                put32(4 * n, handler(n));
            step();
        }

        void step() {
            cpu.run(clock.now() + 1, Long.MAX_VALUE);
        }

        void put16(int a, int v) {
            ram[a] = (byte) (v >>> 8);
            ram[a + 1] = (byte) v;
        }

        void put32(int a, int v) {
            put16(a, v >>> 16);
            put16(a + 2, v);
        }

        void program(int... words) {
            for (int k = 0; k < words.length; k++)
                put16(PROG + 2 * k, words[k]);
            cpu.setPc(PROG);
        }
    }

    @Test
    void fpuLineFIsNotTraced() {
        Real m = new Real();
        m.program(0xF200, 0x2000); // general FPU opword, command class 001: undefined, F-line
        m.cpu.setSrHost(0xA700); // T1 set, supervisor
        m.step();
        assertEquals(handler(11), m.cpu.pc());
        assertEquals(M68kRig.SSP - 8, m.cpu.a(7));
    }

    @Test
    void fpuBsunIsNotTraced() {
        Real m = new Real();
        // fmove.l #NAN,fpsr; fmove.l #BSUN enable,fpcr; fbseq *+4
        m.program(0xF23C, 0x8800, 0x0100, 0x0000, 0xF23C, 0x9000, 0x0000, 0x8000, 0xF291, 0x0002);
        m.step();
        m.step();
        assertEquals(PROG + 16, m.cpu.pc());
        m.cpu.setSrHost(0xA700); // T1 set, supervisor
        m.step();
        assertEquals(handler(48), m.cpu.pc());
        assertEquals(M68kRig.SSP - 8, m.cpu.a(7));
    }

    @Test
    void fpuFrestoreFormatErrorIsNotTraced() {
        Real m = new Real();
        m.put32(0x4000, 0x1234_0000); // not a 68040 FPU frame header
        m.program(0x207C, 0x0000, 0x4000, 0xF350); // movea.l #$4000,a0; frestore (a0)
        m.step();
        m.cpu.setSrHost(0xA700); // T1 set, supervisor
        m.step();
        assertEquals(handler(14), m.cpu.pc());
        assertEquals(M68kRig.SSP - 8, m.cpu.a(7));
    }
}
