package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 68040 integer additions (spec 10.2.3, 10.5, 10.12.1) and their absence on the 68000. */
class M68k040Test {

    private static final int X = 0x10, N = 8, Z = 4, V = 2, C = 1;

    private static M68kRig run1(M68kRig rig, int d0, int d1, int... words) {
        rig.program(words);
        rig.cpu.setD(0, d0);
        rig.cpu.setD(1, d1);
        rig.ccr(0);
        rig.step();
        return rig;
    }

    @Test
    void longMultiply() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0x1_0000, 0x1_0000, 0x4C01, 0x0000); // mulu.l d1,d0: 2^32 overflows 32 bits
        assertEquals(0, rig.d(0));
        assertEquals(Z | V, rig.ccr());
        run1(rig, -3, 5, 0x4C01, 0x0800); // muls.l d1,d0 = -15
        assertEquals(-15, rig.d(0));
        assertEquals(N, rig.ccr());
        run1(rig, -2, 3, 0x4C01, 0x0C02); // muls.l d1,d2:d0 = -6 as 64 bits
        assertEquals(-6, rig.d(0));
        assertEquals(-1, rig.d(2));
        assertEquals(N, rig.ccr());
        run1(rig, -1, -1, 0x4C01, 0x0402); // mulu.l d1,d2:d0 = 0xFFFFFFFE_00000001
        assertEquals(1, rig.d(0));
        assertEquals(0xFFFF_FFFE, rig.d(2));
        assertEquals(N, rig.ccr());
    }

    @Test
    void longDivide() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 100, 7, 0x4C41, 0x0000); // divu.l d1,d0
        assertEquals(14, rig.d(0));
        rig.cpu.setD(2, 0x55);
        run1(rig, 100, 7, 0x4C41, 0x0002); // divul.l d1,d2:d0
        assertEquals(14, rig.d(0));
        assertEquals(2, rig.d(2));
        rig.cpu.setD(2, -1);
        run1(rig, -100, 7, 0x4C41, 0x0C02); // divs.l d1,d2:d0 with d2:d0 = -100
        assertEquals(-14, rig.d(0));
        assertEquals(-2, rig.d(2));
        assertEquals(N, rig.ccr());
        rig.cpu.setD(2, 1);
        run1(rig, 0, 1, 0x4C41, 0x0402); // divu.l 2^32 / 1 overflows: unchanged, V
        assertEquals(0, rig.d(0));
        assertEquals(1, rig.d(2));
        assertEquals(V, rig.ccr());
        run1(rig, 5, 0, 0x4C41, 0x0000); // divide by zero
        assertEquals(handler(5), rig.cpu.pc());
        assertEquals(PROG + 4, rig.peek32(rig.a(7) + 2));
        assertEquals(PROG, rig.peek32(rig.a(7) + 8));
    }

    @Test
    void extbLinkLongRtd() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0x1280, 0, 0x49C0); // extb.l d0
        assertEquals(0xFFFF_FF80, rig.d(0));
        assertEquals(N, rig.ccr());
        rig.cpu.setA(6, 0xABCD);
        int sp = rig.a(7);
        run1(rig, 0, 0, 0x480E, 0xFFFF, 0xFFF8); // link.l a6,#-8
        assertEquals(sp - 4, rig.a(6));
        assertEquals(sp - 12, rig.a(7));
        assertEquals(0xABCD, rig.peek32(sp - 4));
        sp = rig.a(7);
        rig.poke32(sp, PROG + 0x30);
        run1(rig, 0, 0, 0x4E74, 0x0008); // rtd #8
        assertEquals(PROG + 0x30, rig.cpu.pc());
        assertEquals(sp + 12, rig.a(7));
    }

    @Test
    void trapcc() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0, 0, 0x51FA, 0x1234); // trapf.w: no trap, skips its operand
        assertEquals(PROG + 4, rig.cpu.pc());
        run1(rig, 0, 0, 0x50FC); // trapt
        assertEquals(handler(7), rig.cpu.pc());
        int sp = rig.a(7);
        assertEquals(PROG + 2, rig.peek32(sp + 2));
        assertEquals(0x2000 | 7 * 4, rig.peek16(sp + 6));
        assertEquals(PROG, rig.peek32(sp + 8));
    }

    @Test
    void movecEveryRegisterAndAnInvalidNumber() {
        M68kRig rig = M68kRig.m68040();
        int[][] cases = {
                {0x000, -1, 7}, {0x001, -1, 7}, {0x002, -1, 0x8000_8000}, {0x003, 0x7FFF, 0x4000},
                {0x004, -1, 0xFFFF_E364}, {0x005, 0x1234, 0x0224}, {0x006, 5, 4}, {0x007, 6, 4},
                {0x800, DATA, DATA}, {0x801, DATA + 0x400, DATA + 0x400}, {0x803, DATA + 0x800, DATA + 0x800},
                {0x805, 0x77, 0x77}, {0x806, 0x88, 0x88}, {0x807, 0x99, 0x99},
                {0x804, M68kRig.SSP - 0x100, M68kRig.SSP - 0x100},
        };
        for (int[] c : cases) {
            run1(rig, c[1], 0, 0x4E7B, c[0], 0x4E7A, 0x1000 | c[0]);
            rig.step();
            assertEquals(c[2], rig.d(1), String.format("control register 0x%03x", c[0]));
            assertEquals(c[2], rig.cpu.controlRegister(c[0]));
        }
        assertEquals(M68kRig.SSP - 0x100, rig.a(7), "ISP is the active stack");
        run1(rig, 0, 0, 0x4E7B, 0x0801); // VBR back to 0
        run1(rig, 0, 0, 0x4E7A, 0x0802); // CAAR does not exist on the 68040
        assertEquals(handler(4), rig.cpu.pc());
        rig.program(0x4E7A, 0x0801);
        rig.userMode();
        rig.step();
        assertEquals(handler(8), rig.cpu.pc());
    }

    @Test
    void movesAndMove16() {
        M68kRig rig = M68kRig.m68040();
        rig.cpu.setA(0, DATA);
        rig.poke32(DATA, 0x1234_5678);
        run1(rig, 0, 0, 0x0E90, 0x0000); // moves.l (a0),d0
        assertEquals(0x1234_5678, rig.d(0));
        run1(rig, 0, 0xCAFE, 0x0E90, 0x1800); // moves.l d1,(a0)
        assertEquals(0xCAFE, rig.peek32(DATA));
        for (int i = 0; i < 16; i++)
            rig.poke8(DATA + 0x200 + i, i + 1);
        rig.cpu.setA(0, DATA + 0x203);
        rig.cpu.setA(1, DATA + 0x305);
        run1(rig, 0, 0, 0xF620, 0x9000); // move16 (a0)+,(a1)+: both aligned down to 16
        for (int i = 0; i < 16; i++)
            assertEquals(i + 1, rig.peek8(DATA + 0x300 + i));
        assertEquals(DATA + 0x213, rig.a(0));
        assertEquals(DATA + 0x315, rig.a(1));
        rig.cpu.setA(2, DATA + 0x200);
        run1(rig, 0, 0, 0xF612, DATA >>> 16, 0x0400); // move16 (a2),$20400: no increment
        assertEquals(16, rig.peek8(DATA + 0x40F));
        assertEquals(DATA + 0x200, rig.a(2));
        rig.program(0x0E90, 0x0000);
        rig.userMode();
        rig.step();
        assertEquals(handler(8), rig.cpu.pc(), "MOVES is privileged");
    }

    @Test
    void bitfieldsInRegisters() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0x1234_5678, 0, 0xE9C0, 0x1108); // bfextu d0{4:8},d1
        assertEquals(0x23, rig.d(1));
        assertEquals(0, rig.ccr());
        run1(rig, 0x1234_5678, 0, 0xEBC0, 0x1708); // bfexts d0{28:8},d1: wraps, 0x81
        assertEquals(0xFFFF_FF81, rig.d(1));
        assertEquals(N, rig.ccr());
        run1(rig, 0, 0, 0xEDC0, 0x1108); // bfffo d0{4:8},d1 with no set bit: offset + width
        assertEquals(12, rig.d(1));
        assertEquals(Z, rig.ccr());
        run1(rig, 0x0010_0000, 0, 0xEDC0, 0x1108); // bfffo: bit 20 is field bit 7 from offset 4
        assertEquals(11, rig.d(1));
        run1(rig, 0x0000_000F, 0, 0xEAC0, 0x0708); // bfchg d0{28:8}: field 0xF0 (bits 3..0 then 31..28)
        assertEquals(0xF000_0000, rig.d(0));
        assertEquals(N, rig.ccr());
        run1(rig, 0, 0x80, 0xEFC0, 0x1108); // bfins d1,d0{4:8}: N of the inserted value
        assertEquals(0x0800_0000, rig.d(0));
        assertEquals(N, rig.ccr());
    }

    @Test
    void bitfieldInMemorySpanningFiveBytes() {
        M68kRig rig = M68kRig.m68040();
        for (int i = 0; i < 6; i++)
            rig.poke8(DATA + i, 0xFF);
        rig.cpu.setA(0, DATA);
        run1(rig, 0, 0, 0xEFD0, 0x1100); // bfins d1,(a0){4:32}
        assertEquals(0xF0, rig.peek8(DATA));
        assertEquals(0x00, rig.peek8(DATA + 1));
        assertEquals(0x00, rig.peek8(DATA + 3));
        assertEquals(0x0F, rig.peek8(DATA + 4));
        assertEquals(0xFF, rig.peek8(DATA + 5), "byte past the field untouched");
        assertEquals(Z, rig.ccr());
        rig.cpu.setD(2, -12);
        rig.poke8(DATA - 2, 0x0F);
        run1(rig, 0, 0, 0xE9D0, 0x1888); // bfextu (a0){d2:8},d1: offset -12 starts at DATA - 2, bit 4
        assertEquals(0xF0, rig.d(1));
    }

    @Test
    void casAndCas2() {
        M68kRig rig = M68kRig.m68040();
        rig.cpu.setA(0, DATA);
        rig.poke32(DATA, 5);
        run1(rig, 5, 9, 0x0ED0, 0x0040); // cas.l d0,d1,(a0): equal, writes d1
        assertEquals(9, rig.peek32(DATA));
        assertEquals(Z, rig.ccr());
        run1(rig, 5, 7, 0x0ED0, 0x0040); // not equal: loads the operand into d0
        assertEquals(9, rig.d(0));
        assertEquals(9, rig.peek32(DATA));
        assertEquals(0, rig.ccr() & Z);
        rig.cpu.setA(1, DATA + 8);
        rig.poke32(DATA + 8, 2);
        rig.cpu.setD(2, 0x22);
        rig.cpu.setD(3, 0x33);
        run1(rig, 9, 2, 0x0EFC, 0x8080, 0x90C1); // cas2.l d0:d1,d2:d3,(a0):(a1)
        assertEquals(0x22, rig.peek32(DATA));
        assertEquals(0x33, rig.peek32(DATA + 8));
        run1(rig, 0x22, 0x40, 0x0EFC, 0x8080, 0x90C1); // second compare differs: both loaded
        assertEquals(0x22, rig.d(0));
        assertEquals(0x33, rig.d(1));
        assertEquals(C, rig.ccr() & C, "flags of the second compare (0 - 0x33 borrows)");
    }

    @Test
    void chk2AndCmp2() {
        M68kRig rig = M68kRig.m68040();
        rig.cpu.setA(0, DATA);
        rig.poke32(DATA, 10);
        rig.poke32(DATA + 4, 20);
        run1(rig, 20, 0, 0x04D0, 0x0800); // chk2.l (a0),d0: equals the upper bound
        assertEquals(PROG + 4, rig.cpu.pc());
        assertEquals(Z, rig.ccr());
        run1(rig, 21, 0, 0x04D0, 0x0800); // out of bounds: trap
        assertEquals(handler(6), rig.cpu.pc());
        assertEquals(PROG, rig.peek32(rig.a(7) + 8));
        rig.poke16(DATA + 0x10, 0xFFF0);
        rig.poke16(DATA + 0x12, 0x0010);
        rig.cpu.setA(0, DATA + 0x10);
        run1(rig, 0xFFFF_0005, 0, 0x02D0, 0x0000); // cmp2.w (a0),d0: signed bounds -16..16, 5 in
        assertEquals(0, rig.ccr() & (Z | C));
        run1(rig, 0x0020, 0, 0x02D0, 0x0000); // 32 out
        assertEquals(C, rig.ccr() & C);
        rig.poke8(DATA + 0x20, 0xF0);
        rig.poke8(DATA + 0x21, 0x10);
        rig.cpu.setA(0, DATA + 0x20);
        rig.cpu.setA(1, -16);
        run1(rig, 0, 0, 0x00D0, 0x9000); // cmp2.b (a0),a1: bounds sign-extended, a1 = -16 equals lower
        assertEquals(Z, rig.ccr() & (Z | C));
        rig.cpu.setA(1, 0xF0);
        run1(rig, 0, 0, 0x00D0, 0x9000); // 240 is out of -16..16
        assertEquals(C, rig.ccr() & (Z | C));
        rig.poke8(DATA + 0x20, 0x10);
        rig.poke8(DATA + 0x21, 0xF0);
        run1(rig, 0x80, 0, 0x00D0, 0x0000); // cmp2.b (a0),d0: unsigned bounds 0x10..0xF0
        assertEquals(0, rig.ccr() & (Z | C));
    }

    @Test
    void packAndUnpack() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0x0304, 0, 0x8340, 0x0000); // pack d0,d1,#0
        assertEquals(0x34, rig.d(1) & 0xFF);
        run1(rig, 0x34, 0, 0x8380, 0x3030); // unpk d0,d1,#$3030
        assertEquals(0x3334, rig.d(1) & 0xFFFF);
        rig.poke8(DATA, 0x03);
        rig.poke8(DATA + 1, 0x04);
        rig.cpu.setA(0, DATA + 2);
        rig.cpu.setA(1, DATA + 0x11);
        run1(rig, 0, 0, 0x8348, 0x0000); // pack -(a0),-(a1),#0
        assertEquals(0x34, rig.peek8(DATA + 0x10));
        assertEquals(DATA, rig.a(0));
        rig.cpu.setA(0, DATA + 0x11);
        rig.cpu.setA(1, DATA + 0x22);
        run1(rig, 0, 0, 0x8388, 0x3030); // unpk -(a0),-(a1),#$3030: word 0x3334 big-endian at DATA + 0x20
        assertEquals(0x3334, rig.peek16(DATA + 0x20));
        assertEquals(DATA + 0x20, rig.a(1));
    }

    @Test
    void scaledIndexAndFullExtensionWords() {
        M68kRig rig = M68kRig.m68040();
        rig.cpu.setA(0, DATA);
        rig.poke32(DATA + 12, 0xAAAA_0001);
        run1(rig, 0, 3, 0x2030, 0x1C00); // move.l (0,a0,d1.l*4),d0
        assertEquals(0xAAAA_0001, rig.d(0));
        rig.poke32(DATA + 8, DATA + 0x100);
        rig.poke32(DATA + 0x104, 0xBBBB_0002);
        run1(rig, 0, 0, 0x2030, 0x0162, 0x0008, 0x0004); // move.l ([8,a0],4),d0: pre-indexed, index suppressed
        assertEquals(0xBBBB_0002, rig.d(0));
        rig.poke32(0x7000 + 6, 0xDDDD_0004);
        run1(rig, 0, 3, 0x2030, 0x1BA0, 0x7000); // move.l ($7000,d1.l*2),d0, base suppressed: $7000 + 3*2
        assertEquals(0xDDDD_0004, rig.d(0));
        rig.poke32(DATA, DATA + 0x200);
        rig.poke32(DATA + 0x200 + 5, 0xEEEE_0005);
        run1(rig, 0, 5, 0x2030, 0x1915); // move.l ([a0],d1.l),d0: post-indexed
        assertEquals(0xEEEE_0005, rig.d(0));
        run1(rig, 0, 0, 0x2030, 0x0168); // reserved: bit 3 set
        assertEquals(handler(4), rig.cpu.pc());
        run1(rig, 0, 0, 0x2030, 0x0114); // reserved: I/IS 100
        assertEquals(handler(4), rig.cpu.pc());
        run1(rig, 0, 0, 0x2030, 0x0100); // reserved: BD size 00
        assertEquals(handler(4), rig.cpu.pc());
    }

    @Test
    void m68000IgnoresScaleAndFullFormatBits() {
        M68kRig rig = M68kRig.m68000();
        rig.cpu.setA(0, DATA);
        rig.poke32(DATA + 3 + 0x10, 0x1111_2222);
        rig.poke16(DATA + 0x14, 0x1111);
        run1(rig, 0, 4, 0x3030, 0x1D10); // brief word: bits 10..8 ignored, index d1.l (4) unscaled + 16
        assertEquals(rig.peek16(DATA + 0x14), rig.d(0) & 0xFFFF);
    }

    @Test
    void tstAnCmpiPcRelativeAndLongBranch() {
        M68kRig rig = M68kRig.m68040();
        rig.cpu.setA(0, 0x8000);
        run1(rig, 0, 0, 0x4A48); // tst.w a0
        assertEquals(N, rig.ccr());
        rig.at(PROG + 0x14, 0x0005);
        run1(rig, 0, 0, 0x0C7A, 0x0005, 0x0010); // cmpi.w #5,16(pc): base = the displacement word
        assertEquals(Z, rig.ccr());
        run1(rig, 0, 0, 0x60FF, 0x0000, 0x0100); // bra.l
        assertEquals(PROG + 0x102, rig.cpu.pc());
    }

    @Test
    void fpuConditionalsRunInTheCore() {
        M68kRig rig = M68kRig.m68040();
        rig.fpu().conditionResult = 1;
        run1(rig, 0, 0, 0xF281, 0x0010); // fbeq.w
        assertEquals(PROG + 0x12, rig.cpu.pc());
        assertEquals(1, rig.fpu().lastPredicate);
        run1(rig, 0, 0, 0xF240, 0x0001); // fseq d0
        assertEquals(0xFF, rig.d(0) & 0xFF);
        rig.fpu().conditionResult = 0;
        run1(rig, 2, 0, 0xF248, 0x0000, 0xFFFC); // fdbf d0,*-?: displacement from the displacement word
        assertEquals(PROG, rig.cpu.pc());
        assertEquals(1, rig.d(0));
        rig.fpu().conditionResult = 1;
        run1(rig, 0, 0, 0xF27C, 0x000F); // ftrapt
        assertEquals(handler(7), rig.cpu.pc());
        rig.fpu().conditionResult = null;
        run1(rig, 0, 0, 0xF281, 0x0010); // the stub traps: line F, PC = the instruction
        assertEquals(handler(11), rig.cpu.pc());
        assertEquals(PROG, rig.peek32(rig.a(7) + 2));
        rig.program(0xF310); // fsave (a0) in user mode
        rig.userMode();
        rig.step();
        assertEquals(handler(8), rig.cpu.pc());
        assertEquals(0, rig.fpu().saves);
    }

    @Test
    void mmuAndCacheInstructions() {
        M68kRig rig = M68kRig.m68040();
        run1(rig, 0, 0, 0xF518); // pflusha
        assertEquals(PROG + 2, rig.cpu.pc());
        run1(rig, 0, 0, 0xF4D8); // cinva bc
        assertEquals(PROG + 2, rig.cpu.pc());
        rig.cpu.setA(0, 0x1234_5678);
        run1(rig, 0, 0, 0xF568); // ptestr (a0): paging off, no TT hit: the address itself, resident
        assertEquals(0x1234_5001, rig.cpu.controlRegister(0x805));
        rig.program(0xF4D8);
        rig.userMode();
        rig.step();
        assertEquals(handler(8), rig.cpu.pc());
    }

    @Test
    void m68040OnlyOpcodesAreIllegalOnThe68000() {
        int[] ops = {0x49C0, 0x4E74, 0x4E7A, 0x4C01, 0xE9C0, 0x50FC, 0x0ED0, 0x8340, 0x42C0, 0x480E, 0x4100, 0x04D0, 0x0E90};
        for (int op : ops) {
            M68kRig rig = M68kRig.m68000().program(op, 0, 0);
            rig.step();
            assertEquals(handler(4), rig.cpu.pc(), String.format("%04x", op));
        }
        M68kRig rig = M68kRig.m68000().program(0xF620, 0x9000);
        rig.step();
        assertEquals(handler(11), rig.cpu.pc());
        rig = M68kRig.m68000().program(0x4A48); // tst.w a0
        rig.step();
        assertEquals(handler(4), rig.cpu.pc());
    }
}
