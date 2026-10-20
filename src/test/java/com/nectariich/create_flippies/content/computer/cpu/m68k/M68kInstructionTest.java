package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Integer instructions common to both models (spec 10.12.1): hand-assembled words with the
 * results and flags worked out by hand in the comments. CCR bits: X 0x10, N 8, Z 4, V 2, C 1.
 */
class M68kInstructionTest {

    private static final int X = 0x10, N = 8, Z = 4, V = 2, C = 1;

    private static List<M68kRig> both() {
        return List.of(M68kRig.m68000(), M68kRig.m68040());
    }

    /** Runs {@code op} once with D0 = d0, D1 = d1, CCR = ccr; returns the rig. */
    private static M68kRig run1(M68kRig rig, int d0, int d1, int ccr, int... words) {
        rig.program(words);
        rig.cpu.setD(0, d0);
        rig.cpu.setD(1, d1);
        rig.ccr(ccr);
        rig.step();
        return rig;
    }

    @Test
    void moveqSignExtendsAndSetsNKeepsX() {
        for (M68kRig rig : both()) {
            run1(rig, 0, 0, X | C | V, 0x70FF); // moveq #-1,d0
            assertEquals(-1, rig.d(0));
            assertEquals(X | N, rig.ccr());
            run1(rig, 5, 0, 0, 0x7000); // moveq #0,d0
            assertEquals(0, rig.d(0));
            assertEquals(Z, rig.ccr());
        }
    }

    @Test
    void addCarryAndOverflowAtEachSize() {
        for (M68kRig rig : both()) {
            run1(rig, 0x7F, 1, 0, 0xD001); // add.b d1,d0: 0x7F + 1 = 0x80, V, N
            assertEquals(0x80, rig.d(0));
            assertEquals(N | V, rig.ccr());
            run1(rig, 0x12FF, 1, 0, 0xD001); // 0xFF + 1 = 0x00, X C Z; upper bytes kept
            assertEquals(0x1200, rig.d(0));
            assertEquals(X | C | Z, rig.ccr());
            run1(rig, 0x7FFF, 1, 0, 0xD041); // add.w: 0x8000 N V
            assertEquals(0x8000, rig.d(0));
            assertEquals(N | V, rig.ccr());
            run1(rig, 0xFFFF_FFFF, 1, 0, 0xD081); // add.l: 0, X C Z
            assertEquals(0, rig.d(0));
            assertEquals(X | C | Z, rig.ccr());
            run1(rig, 0x8000_0000, 0x8000_0000, 0, 0xD081); // add.l: 0, X C Z V
            assertEquals(X | C | Z | V, rig.ccr());
        }
    }

    @Test
    void subAndCmpBorrowAndOverflow() {
        for (M68kRig rig : both()) {
            run1(rig, 0x80, 1, 0, 0x9001); // sub.b d1,d0: 0x80 - 1 = 0x7F, V
            assertEquals(0x7F, rig.d(0));
            assertEquals(V, rig.ccr());
            run1(rig, 0, 1, 0, 0x9041); // sub.w: 0 - 1 = 0xFFFF, X N C
            assertEquals(0xFFFF, rig.d(0));
            assertEquals(X | N | C, rig.ccr());
            run1(rig, 0, 1, X, 0xB081); // cmp.l d1,d0: 0 - 1, N C, X untouched (still set)
            assertEquals(0, rig.d(0));
            assertEquals(X | N | C, rig.ccr());
            run1(rig, 0, 1, 0, 0xB081); // X stays clear through CMP
            assertEquals(N | C, rig.ccr());
            run1(rig, 5, 5, X, 0xB001); // cmp.b equal: Z, X kept
            assertEquals(X | Z, rig.ccr());
        }
    }

    @Test
    void negSetsCarryUnlessZeroAndOverflowOnMostNegative() {
        for (M68kRig rig : both()) {
            run1(rig, 0x80, 0, 0, 0x4400); // neg.b: 0x80 -> 0x80, X N V C
            assertEquals(0x80, rig.d(0));
            assertEquals(X | N | V | C, rig.ccr());
            run1(rig, 0, 0, X, 0x4480); // neg.l 0: 0, Z, X C cleared
            assertEquals(Z, rig.ccr());
            run1(rig, 1, 0, 0, 0x4440); // neg.w 1: 0xFFFF X N C
            assertEquals(0xFFFF, rig.d(0));
            assertEquals(X | N | C, rig.ccr());
        }
    }

    @Test
    void addxSubxNegxKeepZAcrossAZeroChain() {
        for (M68kRig rig : both()) {
            run1(rig, 0, 0, Z, 0xD181); // addx.l d1,d0: 0 + 0 + 0 = 0, Z stays set
            assertEquals(Z, rig.ccr());
            run1(rig, 0, 0, 0, 0xD181); // Z clear before stays clear on a zero result
            assertEquals(0, rig.ccr());
            run1(rig, 0xFFFF_FFFF, 0, X | Z, 0xD181); // -1 + 0 + 1 = 0: X C, Z kept
            assertEquals(0, rig.d(0));
            assertEquals(X | C | Z, rig.ccr());
            run1(rig, 1, 0, Z, 0xD181); // nonzero result clears Z
            assertEquals(1, rig.d(0));
            assertEquals(0, rig.ccr());
            run1(rig, 0, 0, X | Z, 0x9181); // subx.l: 0 - 0 - 1 = -1, X N C, Z cleared
            assertEquals(-1, rig.d(0));
            assertEquals(X | N | C, rig.ccr());
            run1(rig, 0, 0, Z, 0x4080); // negx.l 0 with X = 0: 0, Z kept
            assertEquals(Z, rig.ccr());
            run1(rig, 0, 0, X | Z, 0x4080); // negx.l 0 with X: -1, X N C
            assertEquals(-1, rig.d(0));
            assertEquals(X | N | C, rig.ccr());
        }
    }

    @Test
    void addxMemoryPredecrementChain() {
        for (M68kRig rig : both()) {
            // addx.b -(a1),-(a0) = 1101 000 1 00 00 1 001
            rig.program(0xD109);
            rig.poke8(DATA + 0x10, 0x01);
            rig.poke8(DATA + 0x20, 0xFF);
            rig.cpu.setA(0, DATA + 0x21);
            rig.cpu.setA(1, DATA + 0x11);
            rig.ccr(Z);
            rig.step();
            assertEquals(0x00, rig.peek8(DATA + 0x20));
            assertEquals(X | C | Z, rig.ccr());
            assertEquals(DATA + 0x20, rig.a(0));
            assertEquals(DATA + 0x10, rig.a(1));
        }
    }

    @Test
    void logicOpsClearVAndC() {
        for (M68kRig rig : both()) {
            run1(rig, 0xF0, 0x0F, X | V | C, 0x8001); // or.b d1,d0 = 0xFF: X N
            assertEquals(0xFF, rig.d(0));
            assertEquals(X | N, rig.ccr());
            run1(rig, 0xF0, 0x0F, 0, 0xC001); // and.b d1,d0 = 0: Z
            assertEquals(0, rig.d(0));
            assertEquals(Z, rig.ccr());
            run1(rig, 0xFFFF, 0x8001, 0, 0xB340); // eor.w d1,d0 = 0x7FFE
            assertEquals(0x7FFE, rig.d(0));
            assertEquals(0, rig.ccr());
            run1(rig, 0x00FF_0000, 0, 0, 0x4680); // not.l d0 = 0xFF00FFFF, N
            assertEquals(0xFF00_FFFF, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, 0x1234_5678, 0, N | C, 0x4240); // clr.w d0: Z
            assertEquals(0x1234_0000, rig.d(0));
            assertEquals(Z, rig.ccr());
            run1(rig, 0, 0, 0, 0x0040, 0x8000); // ori.w #$8000,d0
            assertEquals(0x8000, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, 0x10, 0, 0, 0x0C00, 0x0011); // cmpi.b #$11,d0: 0x10 - 0x11 N C
            assertEquals(N | C, rig.ccr());
            run1(rig, 0x10, 0, 0, 0x0680, 0x7FFF, 0xFFF0); // addi.l #$7FFFFFF0,d0 = 0x80000000 N V
            assertEquals(0x8000_0000, rig.d(0));
            assertEquals(N | V, rig.ccr());
        }
    }

    @Test
    void extSwapExg() {
        for (M68kRig rig : both()) {
            run1(rig, 0x1234_0080, 0, 0, 0x4880); // ext.w d0
            assertEquals(0x1234_FF80, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, 0x1234_8000, 0, 0, 0x48C0); // ext.l d0
            assertEquals(0xFFFF_8000, rig.d(0));
            run1(rig, 0x1234_5678, 0, 0, 0x4840); // swap d0
            assertEquals(0x5678_1234, rig.d(0));
            run1(rig, 1, 2, 0, 0xC141); // exg d0,d1
            assertEquals(2, rig.d(0));
            assertEquals(1, rig.d(1));
            rig.cpu.setA(1, 7);
            run1(rig, 3, 0, 0, 0xC189); // exg d0,a1 = 1100 000 1 10001 001
            assertEquals(7, rig.d(0));
            assertEquals(3, rig.a(1));
        }
    }

    @Test
    void bcdKnownCases() {
        for (M68kRig rig : both()) {
            run1(rig, 0x99, 0x01, 0, 0xC101); // abcd d1,d0: 99 + 01 = 00 carry
            assertEquals(0x00, rig.d(0) & 0xFF);
            assertEquals(X | C, rig.ccr() & (X | C | Z));
            run1(rig, 0x45, 0x46, 0, 0x8101); // sbcd d1,d0: 45 - 46 = 99 borrow
            assertEquals(0x99, rig.d(0) & 0xFF);
            assertEquals(X | C | N, rig.ccr() & (X | C | N | Z));
            run1(rig, 0, 0, Z, 0x4800); // nbcd d0 = 0 with X = 0: Z kept, no carry
            assertEquals(0, rig.d(0) & 0xFF);
            assertEquals(Z, rig.ccr());
            run1(rig, 0x25, 0, 0, 0x4800); // nbcd: 0 - 25 = 75 borrow; binary 0xDB has bit 7 set, 0x75 clear: V (fixed rule)
            assertEquals(0x75, rig.d(0) & 0xFF);
            assertEquals(X | C | V, rig.ccr());
            run1(rig, 0x38, 0x45, X, 0xC101); // abcd: 38 + 45 + 1 = 84; binary 0x7E has bit 7 clear, 0x84 set: V (fixed rule), N
            assertEquals(0x84, rig.d(0) & 0xFF);
            assertEquals(N | V, rig.ccr());
        }
    }

    @Test
    void shiftsByZeroOneSizeAnd63() {
        for (M68kRig rig : both()) {
            run1(rig, 0x8000_0001, 0, X | C | V, 0xE3A8); // lsl.l d1,d0 with d1 = 0: C = 0, X kept
            assertEquals(0x8000_0001, rig.d(0));
            assertEquals(X | N, rig.ccr());
            run1(rig, 0x8000_0001, 1, 0, 0xE3A8); // lsl.l #1 by reg: 2, X C
            assertEquals(2, rig.d(0));
            assertEquals(X | C, rig.ccr());
            run1(rig, 0x8000_0001, 32, 0, 0xE2A8); // lsr.l by 32: 0, last bit out = bit 31: X C Z
            assertEquals(0, rig.d(0));
            assertEquals(X | C | Z, rig.ccr());
            run1(rig, 0xFFFF_FFFF, 63, 0, 0xE3A8); // lsl.l by 63: 0, C = 0
            assertEquals(0, rig.d(0));
            assertEquals(Z, rig.ccr());
            run1(rig, 0x8000_0000, 64 + 1, 0, 0xE2A0); // asr.l by 65 mod 64 = 1: 0xC0000000
            assertEquals(0xC000_0000, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, 0x40, 0, 0, 0xE300); // asl.b #1: 0x80, sign changed: V, N
            assertEquals(0x80, rig.d(0));
            assertEquals(N | V, rig.ccr());
            run1(rig, 0x30, 0, 0, 0xE500); // asl.b #2: 0x60 -> 0xC0: bits 7,6,5 = 0,0,1 not uniform: V
            assertEquals(0xC0, rig.d(0));
            assertEquals(N | V, rig.ccr());
            run1(rig, 0x1, 0, X, 0xE3B0); // roxl.l d1 = 0: C = X, X kept
            assertEquals(1, rig.d(0));
            assertEquals(X | C, rig.ccr());
            run1(rig, 0x8000_0000, 1, 0, 0xE3B0); // roxl.l by 1, X = 0: 0, X C Z
            assertEquals(0, rig.d(0));
            assertEquals(X | C | Z, rig.ccr());
            run1(rig, 0x8000_0001, 1, 0, 0xE3B8); // rol.l by 1: 3, C, X unchanged (0)
            assertEquals(3, rig.d(0));
            assertEquals(C, rig.ccr());
            run1(rig, 0x81, 8, X, 0xE338); // rol.b d1(=8),d0 = 1110 001 1 00 1 11 000: 0x81, C = bit 0 = 1, X kept
            assertEquals(0x81, rig.d(0) & 0xFF);
            assertEquals(X | N | C, rig.ccr());
        }
    }

    @Test
    void memoryShiftByOne() {
        for (M68kRig rig : both()) {
            rig.program(0xE0D0); // asr.w (a0) = 1110 000 0 11 010 000
            rig.cpu.setA(0, DATA);
            rig.poke16(DATA, 0x8001);
            rig.step();
            assertEquals(0xC000, rig.peek16(DATA));
            assertEquals(X | N | C, rig.ccr());
        }
    }

    @Test
    void multiplyAndDivide() {
        for (M68kRig rig : both()) {
            run1(rig, 0xFFFF, 0xFFFF, V | C, 0xC0C1); // mulu.w: 0xFFFE0001
            assertEquals(0xFFFE_0001, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, 0xFFFF, 2, 0, 0xC1C1); // muls.w -1 * 2 = -2
            assertEquals(-2, rig.d(0));
            run1(rig, 0x8000, 0x8000, 0, 0xC1C1); // muls: -32768 * -32768 = 0x40000000
            assertEquals(0x4000_0000, rig.d(0));
            run1(rig, 100, 7, 0, 0x80C1); // divu.w: 14 r 2
            assertEquals((2 << 16) | 14, rig.d(0));
            run1(rig, 0x0001_0000, 1, N | Z, 0x80C1); // divu overflow: unchanged, V, N Z kept
            assertEquals(0x0001_0000, rig.d(0));
            assertEquals(N | Z | V, rig.ccr());
            run1(rig, -7, 2, 0, 0x81C1); // divs.w: -3 r -1
            assertEquals((0xFFFF << 16) | 0xFFFD, rig.d(0));
            assertEquals(N, rig.ccr());
            run1(rig, -32768, 0xFFFF, 0, 0x81C1); // divs -32768 / -1 = 32768: overflow
            assertEquals(-32768, rig.d(0));
            assertEquals(V, rig.ccr());
            run1(rig, -32768, 1, 0, 0x81C1); // -32768 / 1 fits
            assertEquals(0x0000_8000, rig.d(0));
            assertEquals(N, rig.ccr());
        }
    }

    @Test
    void divideByZeroTrapsWithPcOfNextInstruction() {
        for (M68kRig rig : both()) {
            run1(rig, 5, 0, N | C, 0x80C1); // divu.w d1,d0 with d1 = 0
            assertEquals(M68kRig.handler(5), rig.cpu.pc());
            assertEquals(5, rig.d(0));
            assertEquals(N, rig.ccr(), "C cleared, N kept");
            int sp = rig.a(7);
            assertEquals(PROG + 2, rig.peek32(sp + 2));
            if (rig.is040) {
                assertEquals(0x2000 | (5 * 4), rig.peek16(sp + 6));
                assertEquals(PROG, rig.peek32(sp + 8));
            }
        }
    }

    @Test
    void chkBothSides() {
        for (M68kRig rig : both()) {
            run1(rig, 0xFFFF, 10, Z | V | C, 0x4181); // chk.w d1,d0: d0 = -1 < 0: trap, N = 1, Z V C kept
            assertEquals(M68kRig.handler(6), rig.cpu.pc());
            assertEquals(N | Z | V | C, rig.ccr());
            run1(rig, 11, 10, N, 0x4181); // 11 > 10: trap, N = 0
            assertEquals(M68kRig.handler(6), rig.cpu.pc());
            assertEquals(0, rig.ccr() & N);
            run1(rig, 10, 10, 0, 0x4181); // in bounds
            assertEquals(PROG + 2, rig.cpu.pc());
        }
    }

    @Test
    void tasSetsBit7AfterFlags() {
        for (M68kRig rig : both()) {
            rig.program(0x4AD0); // tas (a0)
            rig.cpu.setA(0, DATA);
            rig.poke8(DATA, 0x00);
            rig.step();
            assertEquals(0x80, rig.peek8(DATA));
            assertEquals(Z, rig.ccr());
        }
    }

    @Test
    void bitOperations() {
        for (M68kRig rig : both()) {
            run1(rig, 0x8, 0, Z, 0x0800, 0x0003); // btst #3,d0: bit set, Z = 0
            assertEquals(0, rig.ccr());
            run1(rig, 0, 33, 0, 0x03C0); // bset d1,d0: bit 33 mod 32 = 1
            assertEquals(2, rig.d(0));
            assertEquals(Z, rig.ccr());
            rig.program(0x0390); // bclr d1,(a0): bit 9 mod 8 = 1
            rig.cpu.setA(0, DATA);
            rig.cpu.setD(1, 9);
            rig.poke8(DATA, 0xFF);
            rig.step();
            assertEquals(0xFD, rig.peek8(DATA));
            assertEquals(0, rig.ccr() & Z);
        }
    }

    @Test
    void everyAddressingModeOnRead() {
        for (M68kRig rig : both()) {
            rig.poke16(DATA, 0x1111);
            rig.poke16(DATA + 2, 0x2222);
            rig.poke16(DATA + 0x10, 0x3333);
            rig.poke16(DATA + 0x08, 0x4444);
            rig.cpu.setA(0, DATA);
            run1(rig, 0, -8, 0, 0x3010); // move.w (a0),d0
            assertEquals(0x1111, rig.d(0));
            rig.cpu.setA(0, DATA);
            run1(rig, 0, 0, 0, 0x3018); // move.w (a0)+,d0
            assertEquals(DATA + 2, rig.a(0));
            run1(rig, 0, 0, 0, 0x3020); // move.w -(a0),d0
            assertEquals(0x1111, rig.d(0));
            assertEquals(DATA, rig.a(0));
            run1(rig, 0, 0, 0, 0x3028, 0x0010); // move.w 16(a0),d0
            assertEquals(0x3333, rig.d(0));
            run1(rig, 0, 0x0001_FFF8, 0, 0x3030, 0x1010); // move.w 16(a0,d1.w),d0: d1.w = -8 sign-extended
            assertEquals(0x4444, rig.d(0));
            run1(rig, 0, 0x0001_FFF8, 0, 0x3030, 0x1810); // move.w 16(a0,d1.l),d0: d1.l = 0x1FFF8 -> DATA + 0x20008
            assertEquals(rig.peek16(DATA + 0x2_0008), rig.d(0));
            run1(rig, 0, 0, 0, 0x3038, 0x0006); // move.w $6.w,d0: low word of vector 1 (PROG)
            assertEquals(PROG, rig.d(0));
            run1(rig, 0, 0, 0, 0x3039, DATA >>> 16, 2); // move.w abs.l,d0
            assertEquals(0x2222, rig.d(0));
            rig.at(PROG + 0x20, 0xABCD);
            run1(rig, 0, 0, 0, 0x303A, 0x001E); // move.w d16(pc),d0: base PROG + 2, + 0x1E = PROG + 0x20
            assertEquals(0xABCD, rig.d(0));
            run1(rig, 0, 4, 0, 0x303B, 0x101A); // move.w d8(pc,d1.w),d0: PROG + 2 + 4 + 0x1A
            assertEquals(0xABCD, rig.d(0));
            run1(rig, 0, 0, 0, 0x303C, 0x8001); // move.w #$8001,d0
            assertEquals(0x8001, rig.d(0));
            assertEquals(N, rig.ccr());
            rig.cpu.setA(2, 0x1234_5678);
            run1(rig, 0, 0, 0, 0x300A); // move.w a2,d0
            assertEquals(0x5678, rig.d(0));
        }
    }

    @Test
    void everyWritableModeOnWrite() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA);
            rig.cpu.setA(1, DATA + 0x40);
            run1(rig, 0xAABB, 0, 0, 0x3080); // move.w d0,(a0)
            assertEquals(0xAABB, rig.peek16(DATA));
            run1(rig, 0xCCDD, 0, 0, 0x30C0); // move.w d0,(a0)+
            assertEquals(0xCCDD, rig.peek16(DATA));
            assertEquals(DATA + 2, rig.a(0));
            run1(rig, 0x1122, 0, 0, 0x3300); // move.w d0,-(a1)
            assertEquals(DATA + 0x3E, rig.a(1));
            assertEquals(0x1122, rig.peek16(DATA + 0x3E));
            run1(rig, 0x3344, 0, 0, 0x3340, 0x0004); // move.w d0,4(a1)
            assertEquals(0x3344, rig.peek16(DATA + 0x42));
            run1(rig, 0x5566, 2, 0, 0x3380, 0x1006); // move.w d0,6(a1,d1.w)
            assertEquals(0x5566, rig.peek16(DATA + 0x46));
            run1(rig, 0x7788, 0, 0, 0x33C0, DATA >>> 16, 0x0050); // move.w d0,abs.l
            assertEquals(0x7788, rig.peek16(DATA + 0x50));
            run1(rig, 0x99AA, 0, 0, 0x31C0, 0x0100); // move.w d0,$100.w
            assertEquals(0x99AA, rig.peek16(0x100));
        }
    }

    @Test
    void postincrementSameRegisterBothSidesAndByteStackStep() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA);
            rig.poke32(DATA, 0xDEAD_BEEF);
            run1(rig, 0, 0, 0, 0x20D8); // move.l (a0)+,(a0)+
            assertEquals(0xDEAD_BEEF, rig.peek32(DATA + 4));
            assertEquals(DATA + 8, rig.a(0));
            int sp = rig.a(7);
            run1(rig, 0x42, 0, 0, 0x1F00); // move.b d0,-(a7): A7 moves by 2
            assertEquals(sp - 2, rig.a(7));
            assertEquals(0x42, rig.peek8(sp - 2));
            run1(rig, 0, 0, 0, 0x101F); // move.b (a7)+,d0
            assertEquals(sp, rig.a(7));
            assertEquals(0x42, rig.d(0));
        }
    }

    @Test
    void leaPeaLinkUnlk() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA);
            run1(rig, 0, 0, 0, 0x43E8, 0x0008); // lea 8(a0),a1
            assertEquals(DATA + 8, rig.a(1));
            int sp = rig.a(7);
            run1(rig, 0, 0, 0, 0x4850); // pea (a0)
            assertEquals(sp - 4, rig.a(7));
            assertEquals(DATA, rig.peek32(sp - 4));
            rig.cpu.setA(6, 0x1234);
            sp = rig.a(7);
            run1(rig, 0, 0, 0, 0x4E56, 0xFFF8); // link a6,#-8
            assertEquals(sp - 4, rig.a(6));
            assertEquals(sp - 12, rig.a(7));
            assertEquals(0x1234, rig.peek32(sp - 4));
            run1(rig, 0, 0, 0, 0x4E5E); // unlk a6
            assertEquals(sp, rig.a(7));
            assertEquals(0x1234, rig.a(6));
        }
    }

    @Test
    void movemPredecrementOrderAndPostincrementSignExtension() {
        for (M68kRig rig : both()) {
            int sp = rig.a(7);
            rig.cpu.setA(0, 0xA0A0_A0A0);
            run1(rig, 0xD0D0_D0D0, 0xD1D1_D1D1, 0, 0x48E7, 0xC080); // movem.l d0-d1/a0,-(a7)
            assertEquals(sp - 12, rig.a(7));
            assertEquals(0xD0D0_D0D0, rig.peek32(sp - 12));
            assertEquals(0xD1D1_D1D1, rig.peek32(sp - 8));
            assertEquals(0xA0A0_A0A0, rig.peek32(sp - 4));
            rig.poke16(DATA, 0x8001);
            rig.poke16(DATA + 2, 0x7FFF);
            rig.cpu.setA(0, DATA);
            run1(rig, 0, 0, 0, 0x4C98, 0x0201); // movem.w (a0)+,d0/a1
            assertEquals(0xFFFF_8001, rig.d(0));
            assertEquals(0x7FFF, rig.a(1));
            assertEquals(DATA + 4, rig.a(0));
        }
    }

    @Test
    void movemStoresBaseRegisterPerModel() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA + 0x100);
            rig.program(0x48E0, 0x0080); // movem.l a0,-(a0): predecrement mask bit 7 = A0
            rig.step();
            int expected = rig.is040 ? DATA + 0x100 - 4 : DATA + 0x100;
            assertEquals(expected, rig.peek32(DATA + 0xFC));
            assertEquals(DATA + 0xFC, rig.a(0));
        }
    }

    @Test
    void movepAlternateBytes() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA);
            run1(rig, 0x1122_3344, 0, 0, 0x01C8, 0x0000); // movep.l d0,0(a0)
            assertEquals(0x11, rig.peek8(DATA));
            assertEquals(0x22, rig.peek8(DATA + 2));
            assertEquals(0x44, rig.peek8(DATA + 6));
            run1(rig, 0, 0, 0, 0x0308, 0x0002); // movep.w 2(a0),d1
            assertEquals(0x2233, rig.d(1) & 0xFFFF);
        }
    }

    @Test
    void branchesAndDbcc() {
        for (M68kRig rig : both()) {
            run1(rig, 0, 0, 0, 0x6002); // bra.s *+4
            assertEquals(PROG + 4, rig.cpu.pc());
            run1(rig, 0, 0, Z, 0x6704); // beq.s taken
            assertEquals(PROG + 6, rig.cpu.pc());
            run1(rig, 0, 0, 0, 0x6704); // beq.s not taken
            assertEquals(PROG + 2, rig.cpu.pc());
            run1(rig, 0, 0, 0, 0x6600, 0x0010); // bne.w
            assertEquals(PROG + 0x12, rig.cpu.pc());
            int sp = rig.a(7);
            run1(rig, 0, 0, 0, 0x6100, 0x0010); // bsr.w
            assertEquals(PROG + 0x12, rig.cpu.pc());
            assertEquals(PROG + 4, rig.peek32(sp - 4));
            // dbf d0,* : loops until d0.w wraps to -1
            rig.program(0x51C8, 0xFFFE);
            rig.cpu.setD(0, 0x0001_0002);
            rig.steps(3);
            assertEquals(PROG + 4, rig.cpu.pc());
            assertEquals(0x0001_FFFF, rig.d(0));
            run1(rig, 0, 0, 0, 0x57C0); // seq d0 false
            assertEquals(0, rig.d(0) & 0xFF);
            run1(rig, 0, 0, Z, 0x57C0); // seq d0 true
            assertEquals(0xFF, rig.d(0) & 0xFF);
        }
    }

    @Test
    void jsrRtsRtr() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, PROG + 0x40);
            rig.at(PROG + 0x40, 0x4E75); // rts
            rig.program(0x4E90); // jsr (a0)
            int sp = rig.a(7);
            rig.step();
            assertEquals(PROG + 0x40, rig.cpu.pc());
            rig.step();
            assertEquals(PROG + 2, rig.cpu.pc());
            assertEquals(sp, rig.a(7));
            // rtr: CCR from the stack, then PC
            rig.cpu.setA(7, sp - 6);
            rig.poke16(sp - 6, 0xFF1F);
            rig.poke32(sp - 4, PROG + 0x80);
            run1(rig, 0, 0, 0, 0x4E77);
            assertEquals(0x1F, rig.ccr());
            assertEquals(0x2700, rig.cpu.sr() & 0xFF00, "RTR leaves the system byte");
            assertEquals(PROG + 0x80, rig.cpu.pc());
        }
    }

    @Test
    void ccrAndSrImmediates() {
        for (M68kRig rig : both()) {
            run1(rig, 0, 0, 0, 0x003C, 0x0011); // ori #$11,ccr
            assertEquals(X | C, rig.ccr());
            run1(rig, 0, 0, 0x1F, 0x023C, 0x00F4); // andi #$F4,ccr
            assertEquals(X | Z, rig.ccr());
            run1(rig, 0, 0, 0, 0x027C, 0xF8FF); // andi #$F8FF,sr: mask 0
            assertEquals(0x2000, rig.cpu.sr());
            run1(rig, 0, 0, 0, 0x44FC, 0x00FF); // move #$FF,ccr: only 5 bits
            assertEquals(0x1F, rig.ccr());
            run1(rig, 0, 0, 0x15, 0x40C0); // move sr,d0
            assertEquals(0x2015, rig.d(0) & 0xFFFF);
        }
    }

    @Test
    void illegalEncodingsMapToIllegal() {
        for (M68kRig rig : both()) {
            for (int op : new int[] {0x39C0 /* move.w d0,#imm */, 0x41C0 /* lea d0,a0 */, 0x1048 /* move.b a0,d0 */,
                    0x4AFC /* illegal */, 0x4848 /* bkpt */, 0x7100 /* moveq bit 8 */}) {
                rig.program(op);
                rig.step();
                assertEquals(M68kRig.handler(4), rig.cpu.pc(), String.format("%04x", op));
                assertEquals(PROG, rig.peek32(rig.a(7) + 2));
            }
        }
    }

    @Test
    void cmpmCmpaAddaMoveUspTrapv() {
        for (M68kRig rig : both()) {
            rig.cpu.setA(0, DATA);
            rig.cpu.setA(1, DATA + 0x10);
            rig.poke32(DATA, 2);
            rig.poke32(DATA + 0x10, 1);
            run1(rig, 0, 0, X, 0xB388); // cmpm.l (a0)+,(a1)+: 1 - 2 borrows, X kept
            assertEquals(X | N | C, rig.ccr());
            assertEquals(DATA + 4, rig.a(0));
            assertEquals(DATA + 0x14, rig.a(1));
            rig.cpu.setA(0, -1);
            run1(rig, 0, 0xFFFF, 0, 0xB0C1); // cmpa.w d1,a0: word sign-extended, equal
            assertEquals(Z, rig.ccr());
            rig.cpu.setA(0, 0x100);
            run1(rig, 0, 0xFFFE, 0x1F, 0xD0C1); // adda.w d1,a0: 0x100 - 2, flags untouched
            assertEquals(0xFE, rig.a(0));
            assertEquals(0x1F, rig.ccr());
            rig.cpu.setA(0, 0x1234);
            run1(rig, 0, 0, 0, 0x4E60, 0x4E69); // move a0,usp
            rig.step(); // move usp,a1
            assertEquals(0x1234, rig.cpu.usp());
            assertEquals(0x1234, rig.a(1));
            run1(rig, 0, 0, V, 0x4E76); // trapv taken
            assertEquals(handler(7), rig.cpu.pc());
            assertEquals(PROG + 2, rig.peek32(rig.a(7) + 2));
        }
    }
}
