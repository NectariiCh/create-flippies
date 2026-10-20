package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kFpuUnit;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kVectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FPU instruction layer through a fake host. Encodings are hand-assembled from the PRM
 * command word layout (class in bits 15..13, source 12..10, destination 9..7, opmode 6..0) and
 * written in the comment of each call.
 */
class M68kFpuTest {

    static final int AT = 0x1000;
    static final long INT = Fx80.INT_BIT;

    FakeFpuHost h;
    M68kFpu f;

    @BeforeEach
    void setUp() {
        h = new FakeFpuHost();
        f = new M68kFpu(h, false);
    }

    void strict() {
        f = new M68kFpu(h, true);
    }

    /** Runs a general instruction at AT: opword plus command and extension words. */
    int run(int... words) {
        h.place(AT, words);
        return f.general(words[0]);
    }

    void assertFp(int n, int se, long mant) {
        assertEquals(se, f.registerSe(n), () -> String.format("FP%d se %04X, expected %04X", n, f.registerSe(n), se));
        assertEquals(mant, f.registerMant(n), () -> String.format("FP%d mant %016X, expected %016X", n, f.registerMant(n), mant));
    }

    int cc() {
        return (f.fpsr() >>> 24) & 0xF;
    }

    int status() {
        return f.fpsr() & 0xFF00;
    }

    int accrued() {
        return f.fpsr() & 0xF8;
    }

    // ---- reset and moves in ----

    @Test
    void resetLeavesNansAndNullState() {
        for (int i = 0; i < 8; i++)
            assertFp(i, 0x7FFF, -1L);
        assertEquals(0, f.fpcr());
        assertEquals(0, f.fpsr());
        assertTrue(f.isNullState());
    }

    @Test
    void moveLongImmediateIntoRegister() {
        // fmove.l #3,fp2: opword F23C, command 0x4000 | 2 << 7 = 0x4100, then the long
        int cycles = run(0xF23C, 0x4100, 0x0000, 0x0003);
        assertFp(2, 0x4000, 0xC000_0000_0000_0000L);
        assertEquals(0, cc());
        assertEquals(AT, f.fpiar());
        assertEquals(AT + 8, h.pc);
        assertTrue(cycles > 0);
        assertFalse(f.isNullState());
    }

    @Test
    void moveEachIntegerAndRealFormatIn() {
        // fmove.b #-2,fp0: 0x5800 (format 110); the byte is the low byte of its word
        run(0xF23C, 0x5800, 0x00FE);
        assertFp(0, 0xC000, INT);
        // fmove.w #-300,fp0: 0x5000 (format 100)
        run(0xF23C, 0x5000, 0xFED4);
        assertFp(0, 0xC007, 0x9600_0000_0000_0000L);
        // fmove.s #0.1,fp0: 0x4400 (format 001), 0x3DCCCCCD
        run(0xF23C, 0x4400, 0x3DCC, 0xCCCD);
        assertFp(0, 0x3FFB, 0xCCCC_CD00_0000_0000L);
        // fmove.d #0.1,fp0: 0x5400 (format 101), 0x3FB999999999999A
        run(0xF23C, 0x5400, 0x3FB9, 0x9999, 0x9999, 0x999A);
        assertFp(0, 0x3FFB, 0xCCCC_CCCC_CCCC_D000L);
        // fmove.x #..,fp0: 0x4800 (format 010), 12 bytes: se, zero word, mantissa
        run(0xF23C, 0x4800, 0x3FFB, 0x0000, 0xCCCC, 0xCCCC, 0xCCCC, 0xCCCD);
        assertFp(0, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL);
        assertEquals(0, status());
        // fmove.l d3,fp1: opword F203, command 0x4080
        h.regs[3] = -7;
        run(0xF203, 0x4080);
        assertFp(1, 0xC001, 0xE000_0000_0000_0000L);
        // fmove.s d3,fp1: 0x4480 with d3 = 1.0f
        h.regs[3] = 0x3F80_0000;
        run(0xF203, 0x4480);
        assertFp(1, 0x3FFF, INT);
    }

    @Test
    void registerModesTheInstructionDoesNotTakeAreLineF() {
        // fmove.x d0,fp0 (format 010 from a data register) and anything from an address register
        run(0xF200, 0x4800);
        assertEquals(new FakeFpuHost.Taken(M68kVectors.LINE_F, M68kVectors.FORMAT_0, AT, 0), h.lastException());
        run(0xF208, 0x4000);
        assertEquals(2, h.exceptions.size());
        // class 001 and an undefined opmode (0x05)
        run(0xF200, 0x2000);
        run(0xF200, 0x0005);
        assertEquals(4, h.exceptions.size());
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
    }

    // ---- arithmetic, precision, condition codes ----

    @Test
    void addAndRoundingPrecisionFromFpcr() {
        f.setRegister(0, 0x3FFF, INT); // 1.0
        f.setRegister(1, 0x4002, 0xA000_0000_0000_0000L); // 10.0
        // fdiv.x fp1,fp0: 1 << 10 | 0 << 7 | 0x20 = 0x0420
        run(0xF200, 0x0420);
        assertFp(0, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL);
        assertEquals(Fx80.INEX2, status());
        assertEquals(M68kFpu.ACCRUED_INEX, accrued());
        // FPCR precision single (bits 7..6 = 01): fmove.l #0x40,fpcr is F23C 9000 0000 0040
        run(0xF23C, 0x9000, 0x0000, 0x0040);
        f.setRegister(0, 0x3FFF, INT);
        run(0xF200, 0x0420);
        assertFp(0, 0x3FFB, 0xCCCC_CD00_0000_0000L);
        // fddiv.x fp1,fp0 (opmode 0x64) rounds to double whatever FPCR says
        f.setRegister(0, 0x3FFF, INT);
        run(0xF200, 0x0464);
        assertFp(0, 0x3FFB, 0xCCCC_CCCC_CCCC_D000L);
        // fsadd.x fp1,fp0 (0x62) with FPCR back to extended: 0.1 + 10 rounded to single
        run(0xF23C, 0x9000, 0x0000, 0x0000);
        f.setRegister(0, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL);
        run(0xF200, 0x0462);
        // 10.1 = 1.2625 * 8; single mantissa 0xA1999A (10.1f = 0x4121999A)
        assertFp(0, 0x4002, 0xA199_9A00_0000_0000L);
    }

    @Test
    void exactOperationClearsStatusButKeepsAccrued() {
        f.setRegister(0, 0x3FFF, INT);
        f.setRegister(1, 0x4000, 0xC000_0000_0000_0000L); // 3.0
        run(0xF200, 0x0420); // fdiv.x fp1,fp0: 1/3, inexact
        assertEquals(Fx80.INEX2, status());
        run(0xF200, 0x0422); // fadd.x fp1,fp0: exact enough? 1/3 + 3 is inexact too; use fmove
        run(0xF200, 0x0400); // fmove.x fp1,fp0: exact
        assertEquals(0, status());
        assertEquals(M68kFpu.ACCRUED_INEX, accrued());
    }

    @Test
    void conditionCodesOfEachClass() {
        // ftst.x fp0: 0 << 10 | 0x3A
        f.setRegister(0, 0, 0);
        run(0xF200, 0x003A);
        assertEquals(4, cc()); // Z
        f.setRegister(0, 0x8000, 0);
        run(0xF200, 0x003A);
        assertEquals(12, cc()); // N Z
        f.setRegister(0, 0x7FFF, 0);
        run(0xF200, 0x003A);
        assertEquals(2, cc()); // I
        f.setRegister(0, 0xFFFF, 0xC000_0000_0000_0000L);
        run(0xF200, 0x003A);
        assertEquals(9, cc()); // N NAN
        f.setRegister(0, 0xBFFF, INT);
        run(0xF200, 0x003A);
        assertEquals(8, cc()); // N
        // ftst of a signalling NaN raises SNAN
        f.setRegister(0, 0x7FFF, 0x8000_0000_0000_0001L);
        run(0xF200, 0x003A);
        assertEquals(Fx80.SNAN, status());
        assertEquals(M68kFpu.ACCRUED_IOP, accrued() & M68kFpu.ACCRUED_IOP);
    }

    @Test
    void compareSetsCodesWithoutStoring() {
        f.setRegister(0, 0x3FFF, INT); // 1
        f.setRegister(1, 0x4000, INT); // 2
        // fcmp.x fp1,fp0 (0x0438): 1 - 2 < 0
        run(0xF200, 0x0438);
        assertEquals(8, cc());
        assertFp(0, 0x3FFF, INT);
        // fcmp.x fp0,fp1 (0x00B8): 2 - 1 > 0
        run(0xF200, 0x00B8);
        assertEquals(0, cc());
        // equal: Z; negative equal: N Z
        f.setRegister(1, 0x3FFF, INT);
        run(0xF200, 0x0438);
        assertEquals(4, cc());
        f.setRegister(0, 0x8000, 0);
        f.setRegister(1, 0, 0);
        run(0xF200, 0x0438); // -0 compared with +0
        assertEquals(12, cc());
        f.setRegister(1, 0x7FFF, 0xC000_0000_0000_0000L);
        run(0xF200, 0x0438);
        assertEquals(1, cc()); // NAN
    }

    // ---- predicates and BSUN ----

    @Test
    void predicateTruthTable() {
        // columns: greater (no codes), less (N), equal (Z), unordered (NAN)
        int[] codes = {0, 8, 4, 1};
        String[] expected = {
                "0000", "0010", "1000", "1010", "0100", "0110", "1100", "1110",
                "0001", "0011", "1001", "1011", "0101", "0111", "1101", "1111",
        };
        for (int p = 0; p < 32; p++) {
            for (int col = 0; col < 4; col++) {
                if (codes[col] == 1 && p >= 0x10)
                    continue; // signalling predicates on NaN: checked below
                f.setFpsr(codes[col] << 24);
                int want = expected[p & 15].charAt(col) - '0';
                assertEquals(want, f.condition(p), "predicate " + p + " column " + col);
            }
        }
        // negative zero (N Z) is equal, not less
        f.setFpsr(12 << 24);
        assertEquals(1, f.condition(0x01));
        assertEquals(0, f.condition(0x04));
        assertEquals(1, f.condition(0x05));
        assertTrue(h.exceptions.isEmpty());
    }

    @Test
    void signallingPredicateOnNanSetsBsun() {
        f.setFpsr(M68kFpu.FPSR_NAN);
        // fbogt: not signalling, no BSUN
        assertEquals(0, f.condition(0x02));
        assertEquals(0, status());
        // fbun: true
        assertEquals(1, f.condition(0x08));
        // fbgt (0x12): false, BSUN and accrued IOP set, no trap while disabled
        assertEquals(0, f.condition(0x12));
        assertEquals(Fx80.BSUN, status());
        assertEquals(M68kFpu.ACCRUED_IOP, accrued());
        assertTrue(h.exceptions.isEmpty());
        // enabled: pre-instruction exception, vector 48, format 0, PC = the instruction
        f.setFpcr(Fx80.BSUN);
        h.instructionAddress = 0x2222;
        assertEquals(M68kFpuUnit.CONDITION_TRAPPED, f.condition(0x12));
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_BSUN, M68kVectors.FORMAT_0, 0x2222, 0), h.lastException());
        // reserved predicates are F-line
        assertEquals(M68kFpuUnit.CONDITION_TRAPPED, f.condition(0x20));
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
    }

    // ---- exceptions ----

    @Test
    void enabledDivideByZeroTrapsAfterTheInstruction() {
        f.setFpcr(Fx80.DZ);
        f.setRegister(0, 0x3FFF, INT);
        f.setRegister(1, 0, 0);
        run(0xF200, 0x0420); // fdiv.x fp1,fp0
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_DIVIDE_BY_ZERO, M68kVectors.FORMAT_3, AT + 4, 0), h.lastException());
        assertFp(0, 0x3FFF, INT); // not written
        assertEquals(Fx80.DZ, status());
        assertEquals(M68kFpu.ACCRUED_DZ, accrued());
        assertEquals(AT, f.fpiar());
        // disabled: infinity stored, I code, no trap
        f.setFpcr(0);
        run(0xF200, 0x0420);
        assertFp(0, 0x7FFF, 0);
        assertEquals(2, cc());
        assertEquals(1, h.exceptions.size());
    }

    @Test
    void trapPriorityAndDefaultResults() {
        // inf - inf with OPERR and INEX enabled: OPERR (vector 52) wins, destination kept
        f.setFpcr(Fx80.OPERR | Fx80.INEX2 | Fx80.INEX1);
        f.setRegister(0, 0x7FFF, 0);
        f.setRegister(1, 0x7FFF, 0);
        run(0xF200, 0x0428); // fsub.x fp1,fp0
        assertEquals(M68kVectors.FP_OPERAND_ERROR, h.lastException().vector());
        assertFp(0, 0x7FFF, 0);
        // a signalling NaN source with SNAN and OPERR enabled: SNAN (54)
        f.setFpcr(Fx80.SNAN | Fx80.OPERR);
        f.setRegister(1, 0x7FFF, 0x8000_0000_0000_0001L);
        run(0xF200, 0x0422); // fadd.x fp1,fp0
        assertEquals(M68kVectors.FP_SIGNALING_NAN, h.lastException().vector());
        // overflow enabled: vector 53, the default infinity is stored
        f.setFpcr(Fx80.OVFL | Fx80.INEX2);
        f.setRegister(0, 0x7FFE, -1L);
        f.setRegister(1, 0x7FFE, -1L);
        run(0xF200, 0x0422);
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_OVERFLOW, M68kVectors.FORMAT_3, AT + 4, 0), h.lastException());
        assertFp(0, 0x7FFF, 0);
        assertEquals(M68kFpu.ACCRUED_OVFL | M68kFpu.ACCRUED_INEX, accrued() & (M68kFpu.ACCRUED_OVFL | M68kFpu.ACCRUED_INEX));
        // inexact only: vector 49; underflow: vector 51
        f.setFpcr(Fx80.INEX2);
        f.setRegister(0, 0x3FFF, INT);
        f.setRegister(1, 0x4000, 0xC000_0000_0000_0000L);
        run(0xF200, 0x0420);
        assertEquals(M68kVectors.FP_INEXACT, h.lastException().vector());
        f.setFpcr(Fx80.UNFL | Fx80.INEX2);
        f.setRegister(0, 0, 1);
        f.setRegister(1, 0x4000, 0xC000_0000_0000_0000L);
        run(0xF200, 0x0420); // tiny / 3
        assertEquals(M68kVectors.FP_UNDERFLOW, h.lastException().vector());
    }

    @Test
    void underflowAccruesOnlyWhenInexact() {
        f.setRegister(0, 0, INT); // smallest normal
        f.setRegister(1, 0x4000, INT); // 2
        run(0xF200, 0x0420); // exact denormal result
        assertEquals(0, accrued() & M68kFpu.ACCRUED_UNFL);
        f.setRegister(0, 0, 1);
        run(0xF200, 0x0420); // smallest denormal / 2: inexact
        assertEquals(Fx80.UNFL | Fx80.INEX2, status());
        assertEquals(M68kFpu.ACCRUED_UNFL | M68kFpu.ACCRUED_INEX, accrued());
    }

    // ---- moves out ----

    @Test
    void moveOutLongSaturatesWithOperr() {
        f.setRegister(0, 0x401E, INT); // 2^31
        int ccBefore = f.fpsr() & 0x0F00_0000;
        // fmove.l fp0,d0: F200 6000
        run(0xF200, 0x6000);
        assertEquals(0x7FFF_FFFF, h.regs[0]);
        assertEquals(Fx80.OPERR, status());
        assertEquals(M68kFpu.ACCRUED_IOP, accrued());
        assertEquals(ccBefore, f.fpsr() & 0x0F00_0000);
        // fmove.b fp1,d1 keeps the upper 24 bits: -3.5 rounds to -4 (even)
        f.setRegister(1, 0xC000, 0xE000_0000_0000_0000L);
        h.regs[1] = 0x1234_5678;
        run(0xF201, 0x7880); // format 110 = B: 0x6000 | 6 << 10 | 1 << 7
        assertEquals(0x1234_56FC, h.regs[1]);
        assertEquals(Fx80.INEX2, status());
        // fmove.w fp1,(a0): format 100
        h.regs[8] = 0x3000;
        run(0xF210, 0x7080);
        assertEquals(0xFFFC, h.read16(0x3000));
        // operr enabled: move_out still stores, then traps with format 3 and the address
        f.setFpcr(Fx80.OPERR);
        run(0xF210, 0x6000); // fmove.l fp0,(a0)
        assertEquals(0x7FFF_FFFF, h.read32(0x3000));
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_OPERAND_ERROR, M68kVectors.FORMAT_3, AT + 4, 0x3000), h.lastException());
    }

    @Test
    void moveOutSingleAndDouble() {
        f.setRegister(3, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL); // 0.1
        h.regs[8] = 0x3000;
        run(0xF210, 0x6580); // fmove.s fp3,(a0): 0x6000 | 1 << 10 | 3 << 7
        assertEquals(0x3DCC_CCCD, h.read32(0x3000));
        run(0xF210, 0x7580); // fmove.d fp3,(a0): format 101
        assertEquals(0x3FB9_9999, h.read32(0x3000));
        assertEquals(0x9999_999A, h.read32(0x3004));
        assertEquals(Fx80.INEX2, status());
        // RZ truncates the double: ...9999
        f.setFpcr(Fx80.RZ << 4);
        run(0xF210, 0x7580);
        assertEquals(0x9999_9999, h.read32(0x3004));
        // to a data register as single
        run(0xF202, 0x6580);
        assertEquals(0x3DCC_CCCC, h.regs[2]);
    }

    @Test
    void extendedRoundTripOfEveryClass() {
        long[][] values = {
                {0x0000, 0}, {0x8000, 0}, {0x7FFF, 0}, {0xFFFF, 0},
                {0x7FFF, 0xC000_0000_0000_1234L}, {0x0000, 0x0000_0000_0000_0001L}, {0x8000, 0x7FFF_FFFF_FFFF_FFFFL},
                {0x3FFF, INT}, {0x7FFE, -1L}, {0xC123, 0x8765_4321_0FED_CBA9L},
        };
        h.regs[8] = 0x3000;
        for (long[] v : values) {
            f.setRegister(1, (int) v[0], v[1]);
            run(0xF210, 0x6880); // fmove.x fp1,(a0): format 010
            assertEquals((int) v[0], h.read16(0x3000));
            assertEquals(0, h.read16(0x3002));
            assertEquals(v[1], ((long) h.read32(0x3004) << 32) | (h.read32(0x3008) & 0xFFFF_FFFFL));
            run(0xF210, 0x4A00); // fmove.x (a0),fp4: 0x4000 | 2 << 10 | 4 << 7
            assertFp(4, (int) v[0], v[1]);
        }
        // a signalling NaN goes out quiet, raising SNAN
        f.setRegister(1, 0x7FFF, 0x8000_0000_0000_0001L);
        run(0xF210, 0x6880);
        assertEquals(0xC000_0000, h.read32(0x3004));
        assertEquals(0x0000_0001, h.read32(0x3008));
        assertEquals(Fx80.SNAN, status());
        // an unnormal loaded through FMOVE is normalised
        h.write32(0x3000, 0x3FFF_0000);
        h.write32(0x3004, 0x4000_0000);
        h.write32(0x3008, 0);
        run(0xF210, 0x4A00);
        assertFp(4, 0x3FFE, INT);
    }

    // ---- control registers ----

    @Test
    void controlRegisterMovesAndMasks() {
        h.regs[0] = -1;
        run(0xF200, 0x9000); // fmove.l d0,fpcr
        assertEquals(0xFFF0, f.fpcr());
        run(0xF200, 0x8800); // fmove.l d0,fpsr
        assertEquals(0x0FFF_FFF8, f.fpsr());
        h.regs[9] = 0x1234_5678;
        run(0xF209, 0x8400); // fmove.l a1,fpiar
        assertEquals(0x1234_5678, f.fpiar());
        run(0xF201, 0xB000); // fmove.l fpcr,d1
        assertEquals(0xFFF0, h.regs[1]);
        // fmovem.l fpcr/fpsr/fpiar,-(a0): F220 BC00; memory FPCR, FPSR, FPIAR upwards
        h.regs[8] = 0x3000;
        run(0xF220, 0xBC00);
        assertEquals(0x2FF4, h.regs[8]);
        assertEquals(0xFFF0, h.read32(0x2FF4));
        assertEquals(0x0FFF_FFF8, h.read32(0x2FF8));
        assertEquals(0x1234_5678, h.read32(0x2FFC));
        // fmovem.l #..,fpcr/fpiar: immediate, two longs
        run(0xF23C, 0x9400, 0x0000, 0x0030, 0xAAAA, 0xBBBB);
        assertEquals(0x30, f.fpcr());
        assertEquals(0xAAAA_BBBB, f.fpiar());
        // two registers to a data register, an empty list, and FPCR to an address register are F-line
        run(0xF200, 0xB800);
        run(0xF200, 0xA000);
        run(0xF208, 0xB000);
        assertEquals(3, h.exceptions.size());
    }

    @Test
    void fmovemPredecrementAndPostincrementOrder() {
        f.setRegister(0, 0x3FFF, INT); // 1
        f.setRegister(7, 0x4000, INT); // 2
        h.regs[8] = 0x3000;
        // fmovem.x fp0/fp7,-(a0): predecrement list, bit 0 = FP0, bit 7 = FP7
        run(0xF220, 0xE081);
        assertEquals(0x3000 - 24, h.regs[8]);
        assertEquals(0x3FFF, h.read16(0x2FE8)); // FP0 at the lower address
        assertEquals(0x4000, h.read16(0x2FF4));
        // fmovem.x (a0)+,fp2/fp5: postincrement list, bit 7 = FP0, so FP2 = 0x20, FP5 = 0x04
        run(0xF218, 0xD024);
        assertEquals(0x3000, h.regs[8]);
        assertFp(2, 0x3FFF, INT);
        assertFp(5, 0x4000, INT);
        // dynamic list from d3 to a control address: fmovem.x d3,(a1) is F211 F830
        h.regs[3] = 0x80; // FP0
        h.regs[9] = 0x3100;
        run(0xF211, 0xF830);
        assertEquals(0x3FFF, h.read16(0x3100));
        assertEquals(0x8000_0000, h.read32(0x3104));
        // predecrement list with a postincrement address is F-line
        run(0xF218, 0xE081);
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
        // fmovem raw bits: an unnormal goes in unchanged
        h.write32(0x3200, 0x3FFF_0000);
        h.write32(0x3204, 0x4000_0000);
        h.write32(0x3208, 0);
        h.regs[10] = 0x3200;
        run(0xF212, 0xD080); // fmovem.x (a2),fp0
        assertFp(0, 0x3FFF, 0x4000_0000_0000_0000L);
    }

    // ---- FMOVECR ----

    @Test
    void constantRom() {
        // fmovecr #0,fp0: F200 5C00
        run(0xF200, 0x5C00);
        assertFp(0, 0x4000, 0xC90F_DAA2_2168_C235L);
        assertEquals(Fx80.INEX2, status());
        f.setFpcr(Fx80.RZ << 4);
        run(0xF200, 0x5C00);
        assertFp(0, 0x4000, 0xC90F_DAA2_2168_C234L);
        f.setFpcr(0);
        run(0xF200, 0x5CBF); // fmovecr #$3f,fp1: 10^4096
        assertFp(1, 0x7525, 0xC460_5202_8A20_979BL);
        run(0xF200, 0x5CB2); // 10^0, exact
        assertFp(1, 0x3FFF, INT);
        assertEquals(0, status());
        run(0xF200, 0x5CB0); // ln 2
        assertFp(1, 0x3FFE, 0xB172_17F7_D1CF_79ACL);
        run(0xF200, 0x5C8F); // 0.0: Z
        assertFp(1, 0, 0);
        assertEquals(4, cc());
        run(0xF200, 0x5C90); // undocumented: +0.0
        assertFp(1, 0, 0);
        // the support package's table quirks: e in RN is ...4A9A (exact rounding gives ...4A9B),
        // log10(e) raises no INEX2, 10^2048 in RZ is ...5DE5
        run(0xF200, 0x5C8C);
        assertFp(1, 0x4000, 0xADF8_5458_A2BB_4A9AL);
        assertEquals(Fx80.INEX2, status());
        run(0xF200, 0x5C8E);
        assertFp(1, 0x3FFD, 0xDE5B_D8A9_3728_7195L);
        assertEquals(0, status());
        f.setFpcr(Fx80.RZ << 4);
        run(0xF200, 0x5CBE);
        assertFp(1, 0x5A92, 0x9E8B_3B5D_C53D_5DE5L);
        // single precision pi: 0x40490FDB
        f.setFpcr(0x40);
        run(0xF200, 0x5C00);
        assertFp(0, 0x4000, 0xC90F_DB00_0000_0000L);
    }

    // ---- FSAVE and FRESTORE ----

    @Test
    void fsaveNullThenIdle() {
        h.regs[15] = 0x4000;
        h.place(AT, 0xF327); // fsave -(a7)
        f.save(0xF327);
        assertEquals(0x3FFC, h.regs[15]);
        assertEquals(0, h.read32(0x3FFC));
        run(0xF200, 0x003A); // any FP instruction
        h.write32(0x3FF8, -1);
        h.place(AT, 0xF327);
        f.save(0xF327);
        assertEquals(0x3FF8, h.regs[15]);
        assertEquals(M68kFpu.FRAME_IDLE, h.read32(0x3FF8));
    }

    @Test
    void frestoreFrames() {
        f.setRegister(3, 0x3FFF, INT);
        f.setFpcr(0x30);
        h.regs[15] = 0x4000;
        // idle frame through (a7)+: consumed, state kept
        h.write32(0x4000, M68kFpu.FRAME_IDLE);
        h.place(AT, 0xF35F);
        f.restore(0xF35F);
        assertEquals(0x4004, h.regs[15]);
        assertFp(3, 0x3FFF, INT);
        assertFalse(f.isNullState());
        // unimplemented frame 0x4130: 52 bytes; version 0x40 unimplemented 0x4028: 44 bytes
        h.write32(0x4004, 0x4130_0000);
        f.restore(0xF35F);
        assertEquals(0x4004 + 52, h.regs[15]);
        int a = h.regs[15];
        h.write32(a, 0x4028_0000);
        f.restore(0xF35F);
        assertEquals(a + 44, h.regs[15]);
        // a bad frame: format error, vector 14, format 0, A7 unchanged
        a = h.regs[15];
        h.write32(a, 0x4155_0000);
        f.restore(0xF35F);
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FORMAT_ERROR, M68kVectors.FORMAT_0, AT, 0), h.lastException());
        assertEquals(a, h.regs[15]);
        // the null frame resets the FPU
        h.write32(a, 0);
        f.restore(0xF35F);
        assertEquals(a + 4, h.regs[15]);
        assertFp(3, 0x7FFF, -1L);
        assertEquals(0, f.fpcr());
        assertTrue(f.isNullState());
        // fsave (a7)+ is not allowed: F-line
        f.save(0xF31F);
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
    }

    // ---- support-package instructions through the instruction layer ----

    @Test
    void fmodAndFremQuotientByte() {
        f.setRegister(0, 0x4001, 0xE000_0000_0000_0000L); // 7
        f.setRegister(1, 0x4000, INT); // 2
        run(0xF200, 0x0421); // fmod.x fp1,fp0: 7 mod 2 = 1, quotient 3
        assertFp(0, 0x3FFF, INT);
        assertEquals(0x03, (f.fpsr() >>> 16) & 0xFF);
        f.setRegister(0, 0x4001, 0xE000_0000_0000_0000L);
        run(0xF200, 0x0425); // frem.x fp1,fp0: 7 / 2 = 3.5 rounds to 4 (even), 7 - 8 = -1
        assertFp(0, 0xBFFF, INT);
        assertEquals(0x04, (f.fpsr() >>> 16) & 0xFF);
        f.setRegister(0, 0xC001, 0xE000_0000_0000_0000L); // -7
        run(0xF200, 0x0421); // fmod: -1, quotient sign set, 3
        assertFp(0, 0xBFFF, INT);
        assertEquals(0x83, (f.fpsr() >>> 16) & 0xFF);
        // another instruction leaves the quotient byte alone
        run(0xF200, 0x0422);
        assertEquals(0x83, (f.fpsr() >>> 16) & 0xFF);
    }

    @Test
    void fintFollowsTheRoundingMode() {
        f.setRegister(1, 0x4000, 0xA000_0000_0000_0000L); // 2.5
        run(0xF200, 0x0401); // fint.x fp1,fp0
        assertFp(0, 0x4000, INT); // 2
        f.setFpcr(Fx80.RP << 4);
        run(0xF200, 0x0401);
        assertFp(0, 0x4000, 0xC000_0000_0000_0000L); // 3
        run(0xF200, 0x0403); // fintrz: 2
        assertFp(0, 0x4000, INT);
    }

    @Test
    void fsincosWritesTwoRegisters() {
        f.setRegister(1, 0, 0);
        // fsincos.x fp1,fp3:fp2 (cos to fp3, sin to fp2): 1 << 10 | 2 << 7 | 0x30 | 3
        run(0xF200, 0x0533);
        assertFp(2, 0, 0); // sin 0 = +0
        assertFp(3, 0x3FFF, INT); // cos 0 = 1
        f.setRegister(1, 0x3FFE, INT); // 0.5
        run(0xF200, 0x0533);
        assertEquals(StrictMath.sin(0.5), Fx80.toJavaDouble(f.registerSe(2), f.registerMant(2)));
        assertEquals(StrictMath.cos(0.5), Fx80.toJavaDouble(f.registerSe(3), f.registerMant(3)));
    }

    @Test
    void packedThroughTheInstructionLayer() {
        // fmove.p #1.5E+2,fp0: format 011, 0x4C00; 12 bytes 0002_0001 5000_0000 0000_0000
        run(0xF23C, 0x4C00, 0x0002, 0x0001, 0x5000, 0x0000, 0x0000, 0x0000);
        assertFp(0, 0x4006, 0x9600_0000_0000_0000L);
        // fmove.p fp0,(a0){#2}: 0x6C00 | 2 -> 1.5E+2
        h.regs[8] = 0x3000;
        run(0xF210, 0x6C02);
        assertEquals(0x0002_0001, h.read32(0x3000));
        assertEquals(0x5000_0000, h.read32(0x3004));
        // dynamic k in d5 = 1: fmove.p fp0,(a0){d5} is 0x7C00 | 5 << 4 -> 2E+2 (tie to even)
        h.regs[5] = 1;
        run(0xF210, 0x7C50);
        assertEquals(0x0002_0002, h.read32(0x3000));
        assertEquals(Fx80.INEX2, status());
    }

    // ---- strict mode ----

    @Test
    void strictModeTrapsUnimplementedInstructionsAndTypes() {
        strict();
        f.setRegister(1, 0x3FFE, INT);
        run(0xF200, 0x040E); // fsin.x fp1,fp0
        assertEquals(new FakeFpuHost.Taken(M68kVectors.LINE_F, M68kVectors.FORMAT_2, AT + 4, 0), h.lastException());
        assertFp(0, 0x7FFF, -1L);
        assertEquals(AT, f.fpiar());
        // the next FSAVE writes the unimplemented-instruction frame, 52 bytes
        h.regs[15] = 0x4000;
        h.place(AT, 0xF327);
        f.save(0xF327);
        assertEquals(0x4000 - 52, h.regs[15]);
        assertEquals(M68kFpu.FRAME_UNIMP, h.read32(0x4000 - 52));
        // a denormal extended operand from memory: vector 55, pre-instruction, format 0 with PC =
        // the instruction (UM040 9.6.2)
        h.regs[8] = 0x3000;
        h.write32(0x3000, 0);
        h.write32(0x3004, 0);
        h.write32(0x3008, 1);
        run(0xF210, 0x4800); // fmove.x (a0),fp0
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, M68kVectors.FORMAT_0, AT, 0), h.lastException());
        // packed source, FMOVECR, FINT
        run(0xF210, 0x4C00);
        assertEquals(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, h.lastException().vector());
        run(0xF200, 0x5C00);
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
        run(0xF200, 0x0401);
        assertEquals(M68kVectors.LINE_F, h.lastException().vector());
        int taken = h.exceptions.size();
        // implemented instructions with normal operands still run
        f.setRegister(0, 0x3FFF, INT);
        run(0xF200, 0x0422); // fadd.x fp1,fp0: 1 + 0.5
        assertFp(0, 0x3FFF, 0xC000_0000_0000_0000L);
        assertEquals(taken, h.exceptions.size());
        // a denormal register operand of a dyadic instruction: 55
        f.setRegister(0, 0, 1);
        run(0xF200, 0x0422);
        assertEquals(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, h.lastException().vector());
        // FMOVE of a denormal to memory (opclass 011): vector 55, post-instruction, format 3 with
        // PC = the next instruction and the effective address (UM040 9.6.2)
        run(0xF210, 0x6800); // fmove.x fp0,(a0)
        assertEquals(new FakeFpuHost.Taken(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, M68kVectors.FORMAT_3, AT + 4, 0x3000), h.lastException());
    }

    @Test
    void defaultModeEmulatesWhatStrictModeTraps() {
        f.setRegister(1, 0x3FFE, INT);
        run(0xF200, 0x040E); // fsin.x fp1,fp0
        assertEquals(StrictMath.sin(0.5), Fx80.toJavaDouble(f.registerSe(0), f.registerMant(0)));
        h.regs[8] = 0x3000;
        h.write32(0x3000, 0);
        h.write32(0x3004, 0);
        h.write32(0x3008, 1);
        run(0xF210, 0x4800); // the smallest denormal loads
        assertFp(0, 0, 1);
        assertTrue(h.exceptions.isEmpty());
    }

    // ---- saved state ----

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        f.setRegister(0, 0x3FFB, 0xCCCC_CCCC_CCCC_CCCDL);
        f.setRegister(5, 0x8000, 1);
        f.setRegister(7, 0x7FFF, 0x8000_0000_0000_0001L);
        f.setFpcr(0xFFF0);
        f.setFpsr(0x0F12_3458);
        run(0xF200, 0x003A);
        byte[] saved = save(f);
        M68kFpu g = new M68kFpu(new FakeFpuHost(), false);
        g.loadState(new DataInputStream(new ByteArrayInputStream(saved)));
        for (int i = 0; i < 8; i++) {
            assertEquals(f.registerSe(i), g.registerSe(i));
            assertEquals(f.registerMant(i), g.registerMant(i));
        }
        assertEquals(f.fpcr(), g.fpcr());
        assertEquals(f.fpsr(), g.fpsr());
        assertEquals(f.fpiar(), g.fpiar());
        assertEquals(f.isNullState(), g.isNullState());
        assertEquals(0, java.util.Arrays.compare(saved, save(g)));
        // a strict FPU's state does not load into a non-strict one; an unknown version fails
        M68kFpu s = new M68kFpu(new FakeFpuHost(), true);
        byte[] strictState = save(s);
        assertThrows(StateFormatException.class, () -> g.loadState(new DataInputStream(new ByteArrayInputStream(strictState))));
        saved[3] = 99;
        assertThrows(StateFormatException.class, () -> g.loadState(new DataInputStream(new ByteArrayInputStream(saved))));
    }

    @Test
    void nullStateSurvivesSaveAndLoad() throws IOException {
        byte[] saved = save(f);
        M68kFpu g = new M68kFpu(new FakeFpuHost(), false);
        run(0xF200, 0x003A);
        g.loadState(new DataInputStream(new ByteArrayInputStream(saved)));
        assertTrue(g.isNullState());
        assertNull(h.lastException());
    }

    static byte[] save(M68kFpu fpu) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fpu.saveState(new DataOutputStream(b));
        return b.toByteArray();
    }
}
