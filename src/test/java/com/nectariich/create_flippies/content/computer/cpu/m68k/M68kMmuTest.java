package com.nectariich.create_flippies.content.computer.cpu.m68k;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kCpu.*;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.DATA;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.PROG;
import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kRig.handler;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.cpu.m68k.fpu.M68kFpu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * The 68040 MMU (spec 12.2.5): table searches with 4 KiB and 8 KiB pages, U and M written back,
 * protection, TT registers, precise restart after an access error, the format 7 frame, PTEST,
 * the PFLUSH forms and MOVES. Descriptors are built by hand in the rig's RAM; the supervisor
 * normally runs through ITT0 and DTT0 (supervisor only, every address, identity) so only the
 * user accesses under test go through the tables.
 *
 * Encodings used (checked by hand against the PRM opcode maps): move.l (a0),d0 = 0x2010,
 * move.l d1,(a0) = 0x2081, move.l (a0)+,d0 = 0x2018, move.l d0,-(a0) = 0x2100, addx.l
 * -(a0),-(a1) = 0xD388, movem.l d0-d7,(a0) = 0x48D0 0x00FF, movem.l (a0)+,d0/d1/a0 = 0x4CD8
 * 0x0103, move16 (a0)+,(a1)+ = 0xF620 0x9000, cas.l d0,d1,(a0) = 0x0ED0 0x0040,
 * cas2.l d0:d1,d2:d3,(a0):(a1) = 0x0EFC 0x8080 0x90C1, jmp (a0) = 0x4ED0, ptestr (a0) = 0xF568,
 * ptestw (a0) = 0xF548, pflushn (a0) = 0xF500, pflush (a0) = 0xF508, pflushan = 0xF510,
 * pflusha = 0xF518, moves.l (a0),d0 = 0x0E90 0x0000, moves.l d1,(a0) = 0x0E90 0x1800, move.l
 * #n,d7 = 0x2E3C, movec d7,Rc = 0x4E7B 0x7xxx, rte = 0x4E73, moveq #n,d0 = 0x70nn. FPU (the
 * real one, see {@link WithFpu}): fmove.l #n,fp0 = 0xF23C 0x4000 n, fmove.b #n,fp0 = 0xF23C
 * 0x5800 n, fmove.d #n,fp1 = 0xF23C 0x5480 n, fmove.l fp0,dn = 0xF200+n 0x6000, fmove.l fp1,d2 =
 * 0xF202 0x6080.
 */
class M68kMmuTest {

    /** ITT0 and DTT0 for the supervisor: base 0, mask 0xFF, E, S field 01 (supervisor only). */
    private static final int SUPER_TT = 0x00FF_A000;
    private static final int TABLES = 0xA_0000;
    private static final int SCRATCH = 0x3000;
    /** A logical page well away from RAM, so only the tables can map it. */
    private static final int LA = 0x4000_0000;
    private static final int F1 = 0x2_1000, F2 = 0x2_2000, F3 = 0x2_3000, F4 = 0x2_6000;

    private static final int ATC = 0x400, RW = 0x100, LK = 0x200, MA = 0x800;
    private static final int V = 0x01, W = 0x04, U = 0x08, M = 0x10, S = 0x80, G = 0x400;

    /** A 68040 rig with page tables in RAM at {@link #TABLES} and paging on. */
    private static final class Paged {
        final M68kRig rig = M68kRig.m68040();
        final boolean big;
        final int pageSize;
        int next = TABLES;
        final int userRoot;
        final int superRoot;

        /** {@code superTt}: the supervisor's data also goes through DTT0 (else through the SRP tables). */
        Paged(boolean big, boolean superTt) {
            this.big = big;
            this.pageSize = big ? 8192 : 4096;
            userRoot = alloc(512);
            superRoot = alloc(512);
            control(CR_URP, userRoot);
            control(CR_SRP, superRoot);
            control(CR_ITT0, SUPER_TT);
            if (superTt)
                control(CR_DTT0, SUPER_TT);
            map(false, PROG, PROG, 0);
            rig.at(handler(2), 0x4E73);
            control(CR_TC, 0x8000 | (big ? 0x4000 : 0));
        }

        Paged() {
            this(false, true);
        }

        int alloc(int size) {
            next = (next + size - 1) & -size;
            int a = next;
            next += size;
            for (int i = 0; i < size; i += 4)
                rig.poke32(a + i, 0);
            return a;
        }

        /** MOVEC to a control register, run in supervisor mode from SCRATCH (clobbers D7). */
        void control(int code, int value) {
            int sr = rig.cpu.sr();
            rig.cpu.setSrHost(0x2700);
            rig.at(SCRATCH, 0x2E3C, value >>> 16, value & 0xFFFF, 0x4E7B, 0x7000 | code);
            rig.cpu.setPc(SCRATCH);
            rig.steps(2);
            rig.cpu.setSrHost(sr);
        }

        /** Runs supervisor code from SCRATCH to its end. */
        void supervisor(int... words) {
            rig.cpu.setSrHost(0x2700);
            rig.at(SCRATCH, words);
            rig.cpu.setPc(SCRATCH);
            for (int k = 0; k < 8 && rig.cpu.pc() != SCRATCH + 2 * words.length; k++)
                rig.step();
            assertEquals(SCRATCH + 2 * words.length, rig.cpu.pc(), "supervisor code ran to its end");
        }

        int rootAddr(boolean superv, int la) {
            return (superv ? superRoot : userRoot) + ((la >>> 25) << 2);
        }

        int pointerAddr(boolean superv, int la) {
            return (rig.peek32(rootAddr(superv, la)) & ~0x1FF) + (((la >>> 18) & 0x7F) << 2);
        }

        /** Address of {@code la}'s page descriptor, building the root and pointer entries it needs. */
        int pdAddr(boolean superv, int la) {
            int ra = rootAddr(superv, la);
            if ((rig.peek32(ra) & 2) == 0)
                rig.poke32(ra, alloc(512) | 2);
            int pa = pointerAddr(superv, la);
            int tableSize = big ? 128 : 256;
            if ((rig.peek32(pa) & 2) == 0)
                rig.poke32(pa, alloc(tableSize) | 2);
            int index = big ? (la >>> 13) & 31 : (la >>> 12) & 63;
            return (rig.peek32(pa) & -tableSize) + (index << 2);
        }

        void map(boolean superv, int la, int frame, int bits) {
            rig.poke32(pdAddr(superv, la), (frame & -pageSize) | bits | V);
        }

        int pd(boolean superv, int la) {
            return rig.peek32(pdAddr(superv, la));
        }

        /** Makes a user page invalid and flushes everything. */
        void unmap(int la) {
            rig.poke32(pdAddr(false, la), 0);
            supervisor(0xF518);
        }

        /** One user-mode instruction at PROG with A0 = {@code a0}; returns the PC after it. */
        int user(int a0, int... words) {
            rig.program(words);
            rig.cpu.setA(0, a0);
            rig.userMode();
            rig.step();
            return rig.cpu.pc();
        }

        /** A user read of a long at {@code la} (must not fault). */
        int read(int la) {
            assertEquals(PROG + 2, user(la, 0x2010), String.format("user read of 0x%08x", la));
            return rig.d(0);
        }

        /** The faulted instruction's frame, after the CPU entered the access error handler. */
        int frame() {
            assertEquals(handler(2), rig.cpu.pc(), "access error taken");
            int sp = rig.a(7);
            assertEquals(0x7008, rig.peek16(sp + 6), "format 7, vector 2");
            return sp;
        }

        int ssw() {
            return rig.peek16(frame() + 0x0C);
        }

        int fa() {
            return rig.peek32(frame() + 0x14);
        }

        /** RTE from the handler back to PROG, then the instruction again. */
        void rteAndRerun() {
            rig.step();
            assertEquals(PROG, rig.cpu.pc(), "RTE returns to the faulted instruction");
            assertEquals(0, rig.cpu.sr() & 0x2000, "in user mode");
            rig.step();
        }
    }

    // ---- MOVEC ----

    @Test
    void movecTcStoresEnableAndPageSizeAndTtRegistersTheirBits() {
        Paged p = new Paged();
        assertEquals(0x8000, p.rig.cpu.controlRegister(CR_TC));
        p.control(CR_TC, 0xFFFF);
        assertEquals(0xC000, p.rig.cpu.controlRegister(CR_TC), "E and P only");
        p.control(CR_ITT1, 0x1234_5678);
        assertEquals(0x1234_4260, p.rig.cpu.controlRegister(CR_ITT1), "bits 12..10, 7, 4, 3, 1, 0 read 0");
        p.control(CR_URP, 0x1234_56FF);
        assertEquals(0x1234_56FF, p.rig.cpu.controlRegister(CR_URP), "URP keeps its bits; the search ignores 8..0");
    }

    // ---- table searches ----

    @Test
    void walkWith4KiBPagesSetsUThenM() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        p.rig.poke32(F1 + 0x10, 0xCAFE_F00D);
        assertEquals(0xCAFE_F00D, p.read(LA + 0x10));
        assertEquals(U, p.rig.peek32(p.rootAddr(false, LA)) & U, "root descriptor used");
        assertEquals(U, p.rig.peek32(p.pointerAddr(false, LA)) & U, "pointer descriptor used");
        assertEquals(F1 | U | V, p.pd(false, LA), "page used, not modified");
        p.rig.cpu.setD(1, 0x1234_5678);
        assertEquals(PROG + 2, p.user(LA + 0x10, 0x2081)); // move.l d1,(a0)
        assertEquals(0x1234_5678, p.rig.peek32(F1 + 0x10));
        assertEquals(F1 | M | U | V, p.pd(false, LA), "the first write sets M");
    }

    @Test
    void walkWith8KiBPages() {
        Paged p = new Paged(true, true);
        int la = 0x00A0_6000;
        int frame = 0x2_4000;
        p.map(false, la, frame, 0);
        assertEquals(p.pdAddr(false, la) & 0x7F, 3 << 2, "page index = bits 17..13");
        p.rig.poke32(frame + 0x1234, 0x5555_AAAA);
        assertEquals(0x5555_AAAA, p.read(la + 0x1234), "bit 12 of the offset comes from the logical address");
        assertEquals(0x00A0_0000 >>> 18, (p.pointerAddr(false, la) & 0x1FF) >>> 2, "pointer index = bits 24..18");
    }

    @Test
    void indirectDescriptor() {
        Paged p = new Paged();
        int real = p.alloc(16);
        p.rig.poke32(real, F2 | V);
        p.rig.poke32(p.pdAddr(false, LA), real | 2);
        p.rig.poke32(F2, 0x0BAD_CAFE);
        assertEquals(0x0BAD_CAFE, p.read(LA));
        assertEquals(F2 | U | V, p.rig.peek32(real), "U set in the real descriptor");
        assertEquals(real | 2, p.pd(false, LA), "the indirect descriptor is not changed");
        // an indirect descriptor pointing at another indirect one is invalid
        int la2 = LA + 0x1000;
        p.rig.poke32(p.pdAddr(false, la2), p.pdAddr(false, LA) | 2);
        assertEquals(handler(2), p.user(la2, 0x2010));
        assertEquals(ATC | RW | 1, p.ssw());
    }

    @Test
    void invalidAtEachLevelIsAnAccessError() {
        for (int level = 0; level < 4; level++) {
            Paged p = new Paged();
            p.map(false, LA, F1, 0);
            if (level == 0)
                p.rig.poke32(p.rootAddr(false, LA), 0);
            else if (level == 1)
                p.rig.poke32(p.rootAddr(false, LA), p.rig.peek32(p.rootAddr(false, LA)) & ~3 | 1);
            else if (level == 2)
                p.rig.poke32(p.pointerAddr(false, LA), 0);
            else
                p.rig.poke32(p.pdAddr(false, LA), F1);
            assertEquals(handler(2), p.user(LA + 8, 0x2010), "level " + level);
            assertEquals(ATC | RW | 1, p.ssw(), "read, long, user data");
            assertEquals(LA + 8, p.fa());
            if (level == 3) {
                assertEquals(U, p.rig.peek32(p.rootAddr(false, LA)) & U, "resident levels above were used");
                assertEquals(U, p.rig.peek32(p.pointerAddr(false, LA)) & U);
            }
        }
    }

    @Test
    void writeProtectAtEachLevel() {
        for (int level = 0; level < 3; level++) {
            Paged p = new Paged();
            p.map(false, LA, F1, 0);
            int a = level == 0 ? p.rootAddr(false, LA) : level == 1 ? p.pointerAddr(false, LA) : p.pdAddr(false, LA);
            p.rig.poke32(a, p.rig.peek32(a) | W);
            p.rig.poke32(F1, 77);
            assertEquals(77, p.read(LA), "reads are allowed");
            p.rig.cpu.setD(1, 99);
            assertEquals(handler(2), p.user(LA, 0x2081), "level " + level);
            assertEquals(ATC | 1, p.ssw(), "write, long, user data");
            assertEquals(77, p.rig.peek32(F1));
            assertEquals(0, p.pd(false, LA) & M, "M is not set on a write-protect violation");
        }
    }

    @Test
    void supervisorOnlyPage() {
        Paged p = new Paged(false, false);
        p.map(false, LA, F1, S);
        p.map(true, LA, F1, S);
        p.map(true, 0, 0, 0);                    // vector table
        p.map(true, M68kRig.SSP - 0x1000, M68kRig.SSP - 0x1000, 0);
        p.rig.poke32(F1, 0x600D);
        assertEquals(handler(2), p.user(LA, 0x2010));
        assertEquals(ATC | RW | 1, p.ssw());
        p.rig.cpu.setSrHost(0x2700);
        p.rig.program(0x2010);
        p.rig.cpu.setA(0, LA);
        p.rig.step();
        assertEquals(0x600D, p.rig.d(0), "the supervisor reads it through SRP");
    }

    // ---- transparent translation ----

    @Test
    void transparentTranslationByFunctionCodeAndMask() {
        Paged p = new Paged();
        p.control(CR_TC, 0);
        // DTT1: user only (S field 00), base 0x00, mask 0, write protected
        p.control(CR_DTT1, 0x0000_8004);
        assertTrue(p.rig.cpu.controlRegister(CR_DTT1) != 0);
        p.rig.poke32(DATA, 0x1111);
        assertEquals(0x1111, p.read(DATA), "TT with TC.E clear maps to itself");
        assertEquals(handler(2), p.user(DATA, 0x2081));
        assertEquals(ATC | 1, p.ssw(), "W in a TT register protects the block");
        // the supervisor does not match a user-only TT register (DTT0 matches it, writable)
        p.control(CR_DTT0, 0);
        p.rig.cpu.setSrHost(0x2700);
        p.rig.program(0x2081);
        p.rig.cpu.setA(0, DATA);
        p.rig.cpu.setD(1, 0x2222);
        p.rig.step();
        assertEquals(0x2222, p.rig.peek32(DATA), "no TT match, paging off: identity, writable");
        // mask: base 0x12, mask 0x01 matches 0x12xxxxxx and 0x13xxxxxx only (PTEST with DFC = 1)
        p.control(CR_TC, 0x8000);
        p.control(CR_DTT1, 0x1201_8000);
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, 0x1300_0000);
        p.supervisor(0xF568);
        assertEquals(0x3, p.rig.cpu.controlRegister(CR_MMUSR), "T and R, nothing else");
        p.rig.cpu.setA(0, 0x1400_0000);
        p.supervisor(0xF568);
        assertEquals(0, p.rig.cpu.controlRegister(CR_MMUSR), "no match: the tables have nothing");
        // instruction TT registers for function code 2
        p.control(CR_DFC, 2);
        p.supervisor(0xF568);
        assertEquals(0, p.rig.cpu.controlRegister(CR_MMUSR));
        p.control(CR_ITT1, 0x1401_8000);
        p.rig.cpu.setA(0, 0x1400_0000);
        p.supervisor(0xF568);
        assertEquals(0x3, p.rig.cpu.controlRegister(CR_MMUSR));
    }

    @Test
    void tt0WinsOverTt1() {
        Paged p = new Paged();
        p.control(CR_DTT1, 0x00FF_8004);          // S field 00: user only; write protected
        p.rig.cpu.setD(1, 5);
        assertEquals(handler(2), p.user(DATA, 0x2081));
        p.control(CR_DTT0, 0x00FF_C000);          // S field 10: both, writable
        p.rig.cpu.setD(1, 6);
        assertEquals(PROG + 2, p.user(DATA, 0x2081));
        assertEquals(6, p.rig.peek32(DATA));
    }

    // ---- precise restart ----

    @Test
    void postincrementIsPutBackAndTheInstructionRestarts() {
        Paged p = new Paged();
        p.rig.poke32(F1, 0x4242);
        assertEquals(handler(2), p.user(LA, 0x2018)); // move.l (a0)+,d0
        assertEquals(LA, p.rig.a(0), "A0 as before the instruction");
        assertEquals(PROG, p.rig.peek32(p.frame() + 2), "PC = the instruction");
        p.map(false, LA, F1, 0);
        p.rteAndRerun();
        assertEquals(0x4242, p.rig.d(0));
        assertEquals(LA + 4, p.rig.a(0), "incremented once");
    }

    @Test
    void registersAndCcrPutBackAfterAFault() {
        Paged p = new Paged();
        // move.l (a0)+,(a1)+ with the destination missing: both registers back
        p.map(false, LA, F1, 0);
        p.rig.poke32(F1, 0x77);
        p.rig.program(0x22D8);
        p.rig.cpu.setA(0, LA);
        p.rig.cpu.setA(1, LA + 0x1000);
        p.rig.userMode();
        p.rig.step();
        p.frame();
        assertEquals(LA, p.rig.a(0));
        assertEquals(LA + 0x1000, p.rig.a(1));
        // addx.l -(a0),-(a1) with a read-only destination: registers and X back
        p.map(false, LA + 0x1000, F2, W);
        p.rig.poke32(F1, 1);
        p.rig.poke32(F2, 2);
        p.rig.program(0xD388);
        p.rig.cpu.setA(0, LA + 4);
        p.rig.cpu.setA(1, LA + 0x1004);
        p.rig.userMode();
        p.rig.ccr(0x10 | 0x04);
        p.rig.step();
        int sp = p.frame();
        assertEquals(0x0014, p.rig.peek16(sp), "stacked SR holds the CCR from before the instruction");
        assertEquals(ATC | 1, p.rig.peek16(sp + 0x0C));
        assertEquals(LA + 4, p.rig.a(0));
        assertEquals(LA + 0x1004, p.rig.a(1));
        p.map(false, LA + 0x1000, F2, 0);
        p.rteAndRerun();
        assertEquals(4, p.rig.peek32(F2), "1 + 2 + X");
        assertEquals(LA, p.rig.a(0));
        assertEquals(LA + 0x1000, p.rig.a(1));
    }

    @Test
    void everyAddressingModeRestarts() {
        int[][] programs = {
                {0x2010},                   // move.l (a0),d0
                {0x2018},                   // move.l (a0)+,d0
                {0x2020},                   // move.l -(a0),d0       (A0 = LA + 4)
                {0x2028, 0x0010},           // move.l 16(a0),d0      (A0 = LA - 16)
                {0x2030, 0x1810},           // move.l 16(a0,d1.l),d0 (A0 = LA - 16, D1 = 0)
                {0x2039, LA >>> 16, 0},     // move.l LA,d0
                {0x2030, 0x0161, 0x1000},   // move.l ([4096,a0]),d0 (pointer at LA - 4096 + 4096 = LA)
        };
        int[] a0 = {LA, LA, LA + 4, LA - 16, LA - 16, 0, LA - 0x1000};
        for (int k = 0; k < programs.length; k++) {
            Paged p = new Paged();
            p.map(false, LA - 0x1000, F2, 0);
            boolean indirect = k == programs.length - 1;
            p.rig.poke32(F1, indirect ? LA - 0x1000 + 0x40 : 0x0DD0_0000 + k);
            p.rig.poke32(F2 + 0x40, 0x0DD0_0000 + k);
            p.rig.cpu.setD(1, 0);
            p.rig.cpu.setD(0, -1);
            assertEquals(handler(2), p.user(a0[k], programs[k]), "program " + k);
            assertEquals(a0[k], p.rig.a(0), "A0 back, program " + k);
            assertEquals(-1, p.rig.d(0));
            p.map(false, LA, F1, 0);
            p.rteAndRerun();
            assertEquals(0x0DD0_0000 + k, p.rig.d(0), "program " + k);
            assertEquals(PROG + 2 * programs[k].length, p.rig.cpu.pc());
        }
    }

    @Test
    void pcRelativeOperandOnAMissingPage() {
        Paged p = new Paged();
        p.rig.program(0x203A, 0x2000);   // move.l *+2+$2000(pc),d0 = 0x3002 in another page
        p.rig.poke32(F3 + 0x002, 0x1357);
        p.rig.userMode();
        p.rig.step();
        assertEquals(ATC | RW | 1, p.ssw(), "a PC-relative operand is a data reference (UM040 8.4.6)");
        assertEquals(PROG + 2 + 0x2000, p.fa());
        p.map(false, 0x3000, F3, 0);
        p.rteAndRerun();
        assertEquals(0x1357, p.rig.d(0));
    }

    @Test
    void movemAcrossAPageBoundary() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        for (int i = 0; i < 8; i++)
            p.rig.cpu.setD(i, 0x100 + i);
        assertEquals(handler(2), p.user(LA + 0x1000 - 16, 0x48D0, 0x00FF)); // movem.l d0-d7,(a0)
        assertEquals(ATC | 1, p.ssw());
        assertEquals(LA + 0x1000, p.fa(), "the first long on the missing page");
        p.map(false, LA + 0x1000, F2, 0);
        p.rteAndRerun();
        for (int i = 0; i < 4; i++) {
            assertEquals(0x100 + i, p.rig.peek32(F1 + 0xFF0 + 4 * i));
            assertEquals(0x104 + i, p.rig.peek32(F2 + 4 * i));
        }
        // loads with the base register in the list: nothing changes until the last load
        p.unmap(LA + 0x1000);
        p.rig.cpu.setD(0, -1);
        p.rig.cpu.setD(1, -1);
        p.rig.poke32(F1 + 0xFF8, 11);
        p.rig.poke32(F1 + 0xFFC, 22);
        assertEquals(handler(2), p.user(LA + 0xFF8, 0x4CD8, 0x0103)); // movem.l (a0)+,d0/d1/a0
        assertEquals(-1, p.rig.d(0));
        assertEquals(-1, p.rig.d(1));
        assertEquals(LA + 0xFF8, p.rig.a(0));
    }

    @Test
    void move16FaultLeavesRegistersAndRestarts() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        for (int i = 0; i < 16; i++)
            p.rig.poke8(F1 + 0x20 + i, i + 1);
        p.rig.program(0xF620, 0x9000);
        p.rig.cpu.setA(0, LA + 0x20);
        p.rig.cpu.setA(1, LA + 0x2000);
        p.rig.userMode();
        p.rig.step();
        assertEquals(ATC | (3 << 5) | (1 << 3) | 1, p.ssw(), "write, line, TT 1 (MOVE16), user data");
        assertEquals(LA + 0x20, p.rig.a(0));
        assertEquals(LA + 0x2000, p.rig.a(1));
        p.map(false, LA + 0x2000, F2, 0);
        p.rteAndRerun();
        for (int i = 0; i < 16; i++)
            assertEquals(i + 1, p.rig.peek8(F2 + i));
        assertEquals(LA + 0x30, p.rig.a(0));
        assertEquals(LA + 0x2010, p.rig.a(1));
    }

    @Test
    void casOnAWriteProtectedPageFaultsAsALockedRead() {
        Paged p = new Paged();
        p.map(false, LA, F1, W);
        p.rig.poke32(F1, 5);
        p.rig.cpu.setD(0, 6);   // compare fails: the chip still treats the read as a write
        assertEquals(handler(2), p.user(LA, 0x0ED0, 0x0040));
        assertEquals(ATC | LK | RW | 1, p.ssw());
        assertEquals(6, p.rig.d(0));
    }

    @Test
    void cas2WritesNothingWhenItsSecondOperandIsMissing() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        p.rig.poke32(F1, 10);
        p.rig.poke32(F2, 20);
        p.rig.cpu.setD(0, 10);
        p.rig.cpu.setD(1, 20);
        p.rig.cpu.setD(2, 30);
        p.rig.cpu.setD(3, 40);
        p.rig.program(0x0EFC, 0x8080, 0x90C1);
        p.rig.cpu.setA(0, LA);
        p.rig.cpu.setA(1, LA + 0x1000);
        p.rig.userMode();
        p.rig.step();
        assertEquals(ATC | LK | RW | 1, p.ssw());
        assertEquals(LA + 0x1000, p.fa());
        assertEquals(10, p.rig.peek32(F1), "the first operand is not written");
        p.map(false, LA + 0x1000, F2, 0);
        p.rteAndRerun();
        assertEquals(30, p.rig.peek32(F1));
        assertEquals(40, p.rig.peek32(F2));
    }

    @Test
    void misalignedLongAcrossTwoPagesWhereOnlyTheSecondIsMissing() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        p.rig.poke32(F1 + 0xFFC, 0);
        p.rig.cpu.setD(1, 0x1122_3344);
        assertEquals(handler(2), p.user(LA + 0xFFE, 0x2081));
        assertEquals(MA | ATC | 1, p.ssw(), "MA: the second page faulted");
        assertEquals(LA + 0xFFE, p.fa(), "FA = the first byte of the access (UM040 8.4.6.4)");
        assertEquals(0, p.rig.peek16(F1 + 0xFFE), "no byte moved");
        p.map(false, LA + 0x1000, F4, 0);         // not adjacent to F1
        p.rteAndRerun();
        assertEquals(0x1122, p.rig.peek16(F1 + 0xFFE));
        assertEquals(0x3344, p.rig.peek16(F4));
        assertEquals(0x1122_3344, p.read(LA + 0xFFE), "and back again byte by byte");
    }

    // ---- the format 7 frame word by word ----

    @Test
    void frameOfADataRead() {
        Paged p = new Paged();
        fillStack(p);
        p.rig.ccr(0);
        assertEquals(handler(2), p.user(LA + 0x123, 0x1010)); // move.b (a0),d0
        assertFrame(p, 0x0000, PROG, ATC | RW | (1 << 5) | 1, LA + 0x123);
    }

    @Test
    void frameOfADataWrite() {
        Paged p = new Paged();
        fillStack(p);
        assertEquals(handler(2), p.user(LA + 0x12, 0x3081)); // move.w d1,(a0)
        assertFrame(p, 0x0000, PROG, ATC | (2 << 5) | 1, LA + 0x12);
    }

    @Test
    void frameOfAnInstructionFetch() {
        Paged p = new Paged();
        fillStack(p);
        assertEquals(LA + 0x40, p.user(LA + 0x40, 0x4ED0)); // jmp (a0)
        p.rig.step();
        assertFrame(p, 0x0000, LA + 0x40, ATC | RW | (2 << 5) | 2, LA + 0x40);
        p.map(false, LA, F1, 0);
        p.rig.poke16(F1 + 0x40, 0x7007); // moveq #7,d0
        p.rig.step();
        assertEquals(LA + 0x40, p.rig.cpu.pc());
        p.rig.step();
        assertEquals(7, p.rig.d(0));
    }

    private static void fillStack(Paged p) {
        for (int a = M68kRig.SSP - 0x100; a < M68kRig.SSP; a += 2)
            p.rig.poke16(a, 0xAAAA);
    }

    private static void assertFrame(Paged p, int sr, int pc, int ssw, int fa) {
        int sp = p.frame();
        assertEquals(M68kRig.SSP - 60, sp);
        assertEquals(sr, p.rig.peek16(sp), "SR");
        assertEquals(pc, p.rig.peek32(sp + 2), "PC");
        assertEquals(0x7008, p.rig.peek16(sp + 6), "format and vector offset");
        assertEquals(fa, p.rig.peek32(sp + 8), "EA");
        assertEquals(ssw, p.rig.peek16(sp + 0x0C), "SSW");
        assertEquals(0, p.rig.peek16(sp + 0x0E), "WB3S");
        assertEquals(0, p.rig.peek16(sp + 0x10), "WB2S");
        assertEquals(0, p.rig.peek16(sp + 0x12), "WB1S");
        assertEquals(fa, p.rig.peek32(sp + 0x14), "FA");
        for (int o = 0x18; o < 0x3C; o += 4)
            assertEquals(0, p.rig.peek32(sp + o), "write-back and push data at +" + o);
    }

    // ---- PTEST ----

    @Test
    void ptestResults() {
        Paged p = new Paged();
        p.map(false, LA, F1, G | 0x200 | 0x20);
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, LA + 0x345);
        p.supervisor(0xF568); // ptestr (a0)
        assertEquals(F1 | G | 0x200 | 0x20 | 1, p.rig.cpu.controlRegister(CR_MMUSR), "page, G, U1, CM, R");
        assertEquals(U, p.pd(false, LA) & (U | M), "PTESTR sets U");
        p.supervisor(0xF548); // ptestw (a0)
        assertEquals(F1 | G | 0x200 | 0x20 | M | 1, p.rig.cpu.controlRegister(CR_MMUSR), "PTESTW sets M");
        assertEquals(U | M, p.pd(false, LA) & (U | M));
        // write protected in the pointer level, supervisor only
        int la = LA + 0x80_0000;
        p.map(false, la, F2, S);
        p.rig.poke32(p.pointerAddr(false, la), p.rig.peek32(p.pointerAddr(false, la)) | W);
        p.rig.cpu.setA(0, la);
        p.supervisor(0xF548);
        assertEquals(F2 | S | W | 1, p.rig.cpu.controlRegister(CR_MMUSR), "W accumulated, no M, S reported");
        // invalid
        p.rig.cpu.setA(0, LA + 0x1000);
        p.supervisor(0xF568);
        assertEquals(0, p.rig.cpu.controlRegister(CR_MMUSR) & 1);
        // bus error during the search: a root descriptor pointing at nothing
        int lb = 0x6000_0000;
        p.rig.poke32(p.rootAddr(false, lb), 0x00F0_0000 | 2);
        p.rig.cpu.setA(0, lb);
        p.supervisor(0xF568);
        assertEquals(0x800, p.rig.cpu.controlRegister(CR_MMUSR), "B alone");
        assertEquals(handler(2), p.user(lb, 0x2010), "the same address faults");
        assertEquals(ATC | RW | 1, p.ssw());
        // the supervisor's TT registers for function code 5
        p.control(CR_DFC, 5);
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF568);
        assertEquals(3, p.rig.cpu.controlRegister(CR_MMUSR));
        // paging off, no TT hit: undefined on the chip; here the address itself, resident
        p.control(CR_TC, 0);
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, 0x1234_5678);
        p.supervisor(0xF568);
        assertEquals(0x1234_5001, p.rig.cpu.controlRegister(CR_MMUSR));
    }

    // ---- PFLUSH ----

    /** Two user pages read once, then both descriptors retargeted in memory without a flush. */
    private static Paged stale(int bitsA, int bitsB) {
        Paged p = new Paged();
        p.map(false, LA, F1, bitsA);
        p.map(false, LA + 0x1000, F2, bitsB);
        p.rig.poke32(F1, 1);
        p.rig.poke32(F2, 2);
        p.rig.poke32(F3, 3);
        p.rig.poke32(F4, 4);
        assertEquals(1, p.read(LA));
        assertEquals(2, p.read(LA + 0x1000));
        p.map(false, LA, F3, bitsA);
        p.map(false, LA + 0x1000, F4, bitsB);
        assertEquals(1, p.read(LA), "the TLB still holds the old page");
        assertEquals(2, p.read(LA + 0x1000));
        return p;
    }

    @Test
    void pflushByAddressInvalidatesThatPageInThatTable() {
        Paged p = stale(0, 0);
        p.control(CR_DFC, 5);
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF508); // pflush (a0) for the supervisor table
        assertEquals(1, p.read(LA), "the user entry stays");
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF508);
        assertEquals(3, p.read(LA));
        assertEquals(2, p.read(LA + 0x1000), "only that page");
        p.supervisor(0xF518); // pflusha
        assertEquals(4, p.read(LA + 0x1000));
    }

    @Test
    void nonGlobalFormsKeepGlobalEntries() {
        Paged p = stale(G, 0);
        p.supervisor(0xF510); // pflushan
        assertEquals(1, p.read(LA), "global: kept");
        assertEquals(4, p.read(LA + 0x1000));
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF500); // pflushn (a0)
        assertEquals(1, p.read(LA), "PFLUSHN keeps it too");
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF508); // pflush (a0)
        assertEquals(3, p.read(LA));
        p = stale(G, G);
        p.supervisor(0xF518); // pflusha
        assertEquals(3, p.read(LA));
        assertEquals(4, p.read(LA + 0x1000));
    }

    @Test
    void noStaleWriteEntryAfterAPageIsMadeReadOnlyAndFlushed() {
        Paged p = new Paged();
        p.map(false, LA, F1, 0);
        p.rig.cpu.setD(1, 1);
        assertEquals(PROG + 2, p.user(LA, 0x2081));
        p.rig.cpu.setD(1, 2);
        assertEquals(PROG + 2, p.user(LA, 0x2081));
        p.rig.poke32(p.pdAddr(false, LA), p.pd(false, LA) | W);
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, LA);
        p.supervisor(0xF508);
        p.rig.cpu.setD(1, 3);
        assertEquals(handler(2), p.user(LA, 0x2081));
        assertEquals(2, p.rig.peek32(F1));
    }

    @Test
    void movecToARootPointerFlushes() {
        Paged p = stale(G, 0);
        p.control(CR_URP, p.userRoot);
        assertEquals(3, p.read(LA));
        assertEquals(4, p.read(LA + 0x1000));
    }

    // ---- MOVES ----

    @Test
    void movesWithEachFunctionCode() {
        Paged p = new Paged(false, false);
        p.map(true, 0, 0, 0);                                     // vector table
        p.map(true, M68kRig.SSP - 0x1000, M68kRig.SSP - 0x1000, 0); // supervisor stack
        p.map(false, LA, F1, 0);
        p.map(true, LA, F2, 0);
        p.rig.poke32(F1, 0x0001_0001);
        p.rig.poke32(F2, 0x0005_0005);
        int[] expected = {0, 0x0001_0001, 0x0001_0001, 0, 0, 0x0005_0005, 0x0005_0005, 0};
        for (int fc = 0; fc < 8; fc++) {
            p.control(CR_SFC, fc);
            int space = fc & 3;
            int a = space == 1 || space == 2 ? LA : F2;   // alternate spaces are physical
            p.rig.cpu.setA(0, a);
            p.rig.cpu.setD(0, -1);
            p.supervisor(0x0E90, 0x0000);
            assertEquals(space == 1 || space == 2 ? expected[fc] : 0x0005_0005, p.rig.d(0), "SFC " + fc);
        }
        // DFC = 1 writes through the user table and sets M there
        p.control(CR_DFC, 1);
        p.rig.cpu.setA(0, LA);
        p.rig.cpu.setD(1, 0xABCD);
        p.supervisor(0x0E90, 0x1800);
        assertEquals(0xABCD, p.rig.peek32(F1));
        assertEquals(M, p.pd(false, LA) & M);
        assertEquals(0x0005_0005, p.rig.peek32(F2));
        // a user-protected page faults with TM = 1 even though the CPU is the supervisor
        p.map(false, LA + 0x1000, F3, W);
        p.rig.cpu.setA(0, LA + 0x1000);
        p.rig.cpu.setSrHost(0x2700);
        p.rig.at(SCRATCH, 0x0E90, 0x1800);
        p.rig.cpu.setPc(SCRATCH);
        p.rig.step();
        assertEquals(ATC | 1, p.ssw());
        // DFC = 6 is supervisor data (TM 5) for the fault
        p.control(CR_DFC, 6);
        p.rig.cpu.setA(0, LA + 0x2000);
        p.rig.at(SCRATCH, 0x0E90, 0x1800);
        p.rig.cpu.setPc(SCRATCH);
        p.rig.step();
        assertEquals(ATC | 5, p.ssw());
    }

    // ---- saved state ----

    private static byte[] save(M68kCpu cpu) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        cpu.saveState(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static void load(M68kCpu cpu, byte[] state) throws IOException {
        cpu.loadState(new DataInputStream(new ByteArrayInputStream(state)));
    }

    @Test
    void savedStateKeepsTheMmuOnAndRunsInLockstep() throws IOException {
        Paged a = new Paged();
        for (int i = 0; i < 4; i++)
            a.map(false, LA + i * 0x1000, F1 + i * 0x1000, 0);
        int[] loop = {
                0x5281,         // addq.l #1,d1
                0x20C1,         // move.l d1,(a0)+
                0xB1C2,         // cmpa.l d2,a0
                0x66F8,         // bne.s to the top
                0x2049,         // movea.l a1,a0
                0x60F4,         // bra.s to the top
        };
        a.rig.program(loop);
        a.rig.cpu.setA(0, LA);
        a.rig.cpu.setA(1, LA);
        a.rig.cpu.setD(2, LA + 0x4000);
        a.rig.userMode();
        a.rig.runFor(5000);
        byte[] state = save(a.rig.cpu);
        M68kRig b = new M68kRig(true);
        System.arraycopy(a.rig.ram, 0, b.ram, 0, a.rig.ram.length);
        b.clock.advanceTo(a.rig.clock.now());
        load(b.cpu, state);
        assertEquals(0x8000, b.cpu.controlRegister(CR_TC));
        for (int k = 0; k < 300; k++) {
            a.rig.runFor(200);
            b.runFor(200);
            assertEquals(a.rig.cpu.pc(), b.cpu.pc());
            assertEquals(a.rig.cpu.cycles(), b.cpu.cycles());
        }
        assertArrayEquals(save(a.rig.cpu), save(b.cpu));
        assertArrayEquals(a.rig.ram, b.ram);
        assertTrue(a.rig.peek32(F1 + 0x3FFC) != 0, "the loop wrote all four pages");
    }

    @Test
    void aVersion1StateLoadsWithPagingAndTtOff() throws IOException {
        Paged a = new Paged();
        byte[] state = save(a.rig.cpu);
        // version int, model "CPU_68040" (2 + 9 bytes), strict flag, 16 registers, PC, SR,
        // USP, ISP, MSP, VBR, CACR, SFC, DFC, then TC, ITT0, ITT1, DTT0 ...
        int tc = 4 + 11 + 1 + 64 + 4 + 2 + 4 + 4 + 4 + 16;
        assertEquals(2, ByteBuffer.wrap(state).getInt(0));
        assertEquals(0x8000, ByteBuffer.wrap(state).getInt(tc));
        assertEquals(SUPER_TT, ByteBuffer.wrap(state).getInt(tc + 4));
        // version 1 ends the MMU part after the eight registers: no TLB entries and no code page
        int tlb = tc + 32;
        int tlbBytes = 4 + 11 * ByteBuffer.wrap(state).getInt(tlb) + 8;
        byte[] v1 = new byte[state.length - tlbBytes];
        System.arraycopy(state, 0, v1, 0, tlb);
        System.arraycopy(state, tlb + tlbBytes, v1, tlb, state.length - tlb - tlbBytes);
        ByteBuffer.wrap(v1).putInt(0, 1);
        M68kRig b = new M68kRig(true);
        load(b.cpu, v1);
        assertEquals(0, b.cpu.controlRegister(CR_TC));
        assertEquals(SUPER_TT & ~0x8000, b.cpu.controlRegister(CR_ITT0), "a TT register had no effect in version 1");
        assertEquals(a.userRoot, b.cpu.controlRegister(CR_URP));
    }

    /** A user read of a long at {@code la} on a bare rig, as {@link Paged#read}. */
    private static int userRead(M68kRig rig, int la) {
        rig.program(0x2010);
        rig.cpu.setA(0, la);
        rig.userMode();
        rig.step();
        assertEquals(PROG + 2, rig.cpu.pc(), "user read");
        return rig.d(0);
    }

    @Test
    void savedStateKeepsTlbEntriesWhoseDescriptorsChangedWithoutAFlush() throws IOException {
        Paged a = new Paged();
        a.map(false, LA, F1, 0);
        a.map(false, LA + 0x2000, F2, 0);
        a.rig.poke32(F1, 1);
        a.rig.poke32(F3, 3);
        a.rig.poke16(F2, 0x7001);
        a.rig.poke16(F4, 0x7004);
        assertEquals(1, a.read(LA));
        a.rig.cpu.setPc(LA + 0x2000);
        a.rig.step();
        assertEquals(1, a.rig.d(0), "the code page ran from F2");
        // both pages now point elsewhere with U clear, and nothing is flushed
        int dataPd = a.pdAddr(false, LA);
        int codePd = a.pdAddr(false, LA + 0x2000);
        a.rig.poke32(dataPd, F3 | V);
        a.rig.poke32(codePd, F4 | V);
        a.rig.cpu.setPc(LA + 0x2000);
        byte[] state = save(a.rig.cpu);
        M68kRig b = new M68kRig(true);
        System.arraycopy(a.rig.ram, 0, b.ram, 0, a.rig.ram.length);
        b.clock.advanceTo(a.rig.clock.now());
        load(b.cpu, state);
        assertArrayEquals(state, save(b.cpu), "the TLB and the code page are state");
        for (M68kRig rig : new M68kRig[] {a.rig, b}) {
            rig.step();
            assertEquals(1, rig.d(0), "the cached code page still maps F2");
            assertEquals(1, userRead(rig, LA), "the cached data page still maps F1");
            assertEquals(F3 | V, rig.peek32(dataPd), "a TLB hit writes no U back");
            assertEquals(F4 | V, rig.peek32(codePd));
        }
        assertEquals(a.rig.cpu.cycles(), b.cpu.cycles());
        assertArrayEquals(save(a.rig.cpu), save(b.cpu));
        assertArrayEquals(a.rig.ram, b.ram);
    }

    // ---- FPU immediate data ----

    /**
     * A 68040 with the real FPU (the rig's is a stub) on 1 MiB of RAM: vector N points at
     * handler(N), the supervisor stack at {@link M68kRig#SSP}, 4 KiB page tables built at
     * {@link #TABLES}.
     */
    private static final class WithFpu {
        final byte[] ram = new byte[M68kRig.RAM_SIZE];
        final CycleClock clock = new CycleClock();
        final M68kCpu cpu;
        final int userRoot;
        final int superRoot;
        int next = TABLES;

        WithFpu() {
            SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
            bus.setRam(ram, 0);
            CpuHost host = new CpuHost(CpuModel.CPU_68040, 25_000_000, bus, null, clock, null, () -> { }, 0);
            cpu = new M68kCpu(host, M68kOptions.m68040(false), M68kFpu::new);
            put32(0, M68kRig.SSP);
            put32(4, PROG);
            for (int n = 2; n < 256; n++)
                put32(4 * n, handler(n));
            put16(handler(2), 0x4E73);
            step();
            userRoot = alloc(512);
            superRoot = alloc(512);
            control(CR_URP, userRoot);
            control(CR_SRP, superRoot);
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

        int get16(int a) {
            return ((ram[a] & 0xFF) << 8) | (ram[a + 1] & 0xFF);
        }

        int get32(int a) {
            return (get16(a) << 16) | get16(a + 2);
        }

        void at(int a, int... words) {
            for (int k = 0; k < words.length; k++)
                put16(a + 2 * k, words[k]);
        }

        int alloc(int size) {
            next = (next + size - 1) & -size;
            int a = next;
            next += size;
            return a;
        }

        /** MOVEC to a control register in supervisor mode from SCRATCH (clobbers D7). */
        void control(int code, int value) {
            int sr = cpu.sr();
            cpu.setSrHost(0x2700);
            at(SCRATCH, 0x2E3C, value >>> 16, value & 0xFFFF, 0x4E7B, 0x7000 | code);
            cpu.setPc(SCRATCH);
            step();
            step();
            cpu.setSrHost(sr);
        }

        /** Maps the 4 KiB logical page of {@code la} to {@code frame}, building the tables it needs. */
        void map(boolean superv, int la, int frame) {
            int ra = (superv ? superRoot : userRoot) + ((la >>> 25) << 2);
            if ((get32(ra) & 2) == 0)
                put32(ra, alloc(512) | 2);
            int pa = (get32(ra) & ~0x1FF) + (((la >>> 18) & 0x7F) << 2);
            if ((get32(pa) & 2) == 0)
                put32(pa, alloc(256) | 2);
            put32((get32(pa) & ~0xFF) + (((la >>> 12) & 63) << 2), (frame & ~0xFFF) | V);
        }
    }

    @Test
    void fpuImmediateDataIsFetchedWithTheInstruction() {
        WithFpu m = new WithFpu();
        // every supervisor fetch goes through ITT0; supervisor data only through the SRP tables,
        // which map the first MiB except the code page
        for (int page = 0; page < M68kRig.RAM_SIZE; page += 0x1000)
            if (page != PROG)
                m.map(true, page, page);
        m.control(CR_ITT0, SUPER_TT);
        m.control(CR_TC, 0x8000);
        m.at(PROG,
                0xF23C, 0x4000, 0x0000, 0x0005,                 // fmove.l #5,fp0
                0xF200, 0x6000,                                 // fmove.l fp0,d0
                0xF23C, 0x5800, 0x00FD,                         // fmove.b #-3,fp0
                0xF201, 0x6000,                                 // fmove.l fp0,d1
                0xF23C, 0x5480, 0x401C, 0x0000, 0x0000, 0x0000, // fmove.d #7.0,fp1
                0xF202, 0x6080);                                // fmove.l fp1,d2
        m.cpu.setSrHost(0x2700);
        m.cpu.setPc(PROG);
        for (int k = 0; k < 6; k++)
            m.step();
        assertEquals(PROG + 38, m.cpu.pc(), "no access error: the data tables never see the immediates");
        assertEquals(5, m.cpu.d(0));
        assertEquals(-3, m.cpu.d(1));
        assertEquals(7, m.cpu.d(2));
    }

    @Test
    void fpuImmediateOnAMissingPageFaultsAsAProgramAccess() {
        WithFpu m = new WithFpu();
        m.control(CR_ITT0, SUPER_TT);
        m.control(CR_DTT0, SUPER_TT);
        m.map(false, LA, F1);
        m.control(CR_TC, 0x8000);
        m.at(F1 + 0xFFC, 0xF23C, 0x4000);               // fmove.l #5,fp0, its data on the next page
        m.at(F2, 0x0000, 0x0005, 0xF200, 0x6000);       // ... and fmove.l fp0,d0
        m.cpu.setSrHost(0x0000);
        m.cpu.setPc(LA + 0xFFC);
        m.step();
        assertEquals(handler(2), m.cpu.pc(), "access error");
        int sp = m.cpu.a(7);
        assertEquals(M68kRig.SSP - 60, sp);
        assertEquals(LA + 0xFFC, m.get32(sp + 2), "PC");
        assertEquals(ATC | RW | (2 << 5) | 2, m.get16(sp + 0x0C), "SSW: a user program word, not a user data long");
        assertEquals(LA + 0x1000, m.get32(sp + 0x14), "FA");
        m.map(false, LA + 0x1000, F2);
        m.step();
        assertEquals(LA + 0xFFC, m.cpu.pc(), "RTE restarts the instruction");
        m.step();
        m.step();
        assertEquals(LA + 0x1008, m.cpu.pc());
        assertEquals(5, m.cpu.d(0));
    }
}
