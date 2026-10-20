package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * The per-model decode table (spec 10.3.2): every 16-bit opcode word maps to a dense op id the
 * core's switch runs. Legality lives here, so the executor never re-checks an effective address
 * mode or a size: an encoding with an EA mode the instruction does not allow (for example
 * {@code move.w d0,#1} or {@code lea d0,a0}) and every unassigned word map to {@link #ILLEGAL},
 * 0xAxxx to {@link #LINE_A}, and every line F word the model does not run itself to
 * {@link #LINE_F}.
 *
 * The table is built by classifying each of the 65536 words with {@link #classify}, a decision
 * tree over the opcode map of the programmer's reference manual. Because each word is classified
 * exactly once, no opcode can be claimed twice. Built once per model into a static field.
 *
 * Op ids carry the instruction class only; the executor reads size, registers and EA fields from
 * the word itself.
 */
final class M68kOpTable {

    private M68kOpTable() {
    }

    // ---- op ids, dense from 0 so the core's switch compiles to a jump table ----

    static final int ILLEGAL = 0;
    static final int LINE_A = 1;
    static final int LINE_F = 2;
    static final int ORI_CCR = 3;
    static final int ORI_SR = 4;
    static final int ANDI_CCR = 5;
    static final int ANDI_SR = 6;
    static final int EORI_CCR = 7;
    static final int EORI_SR = 8;
    static final int ORI = 9;
    static final int ANDI = 10;
    static final int SUBI = 11;
    static final int ADDI = 12;
    static final int EORI = 13;
    static final int CMPI = 14;
    static final int BTST_IMM = 15;
    static final int BCHG_IMM = 16;
    static final int BCLR_IMM = 17;
    static final int BSET_IMM = 18;
    static final int BTST_REG = 19;
    static final int BCHG_REG = 20;
    static final int BCLR_REG = 21;
    static final int BSET_REG = 22;
    static final int MOVEP = 23;
    static final int CMP2_CHK2 = 24;
    static final int CAS = 25;
    static final int CAS2 = 26;
    static final int MOVES = 27;
    static final int MOVE_B = 28;
    static final int MOVE_W = 29;
    static final int MOVE_L = 30;
    static final int MOVEA_W = 31;
    static final int MOVEA_L = 32;
    static final int MOVE_FROM_SR = 33;
    static final int MOVE_FROM_CCR = 34;
    static final int MOVE_TO_CCR = 35;
    static final int MOVE_TO_SR = 36;
    static final int NEGX = 37;
    static final int CLR = 38;
    static final int NEG = 39;
    static final int NOT = 40;
    static final int NBCD = 41;
    static final int LINK_L = 42;
    static final int SWAP = 43;
    static final int PEA = 44;
    static final int EXT_W = 45;
    static final int EXT_L = 46;
    static final int EXTB_L = 47;
    static final int MOVEM_TO_MEM = 48;
    static final int MOVEM_TO_REG = 49;
    static final int TST = 50;
    static final int TAS = 51;
    static final int MUL_L = 52;
    static final int DIV_L = 53;
    static final int TRAP = 54;
    static final int LINK_W = 55;
    static final int UNLK = 56;
    static final int MOVE_TO_USP = 57;
    static final int MOVE_FROM_USP = 58;
    static final int RESET = 59;
    static final int NOP = 60;
    static final int STOP = 61;
    static final int RTE = 62;
    static final int RTD = 63;
    static final int RTS = 64;
    static final int TRAPV = 65;
    static final int RTR = 66;
    static final int MOVEC = 67;
    static final int JSR = 68;
    static final int JMP = 69;
    static final int CHK_W = 70;
    static final int CHK_L = 71;
    static final int LEA = 72;
    static final int ADDQ = 73;
    static final int SUBQ = 74;
    static final int ADDQ_A = 75;
    static final int SUBQ_A = 76;
    static final int SCC = 77;
    static final int DBCC = 78;
    static final int TRAPCC = 79;
    static final int BRA = 80;
    static final int BSR = 81;
    static final int BCC = 82;
    static final int MOVEQ = 83;
    static final int OR_EA_DN = 84;
    static final int OR_DN_EA = 85;
    static final int DIVU_W = 86;
    static final int DIVS_W = 87;
    static final int SBCD_R = 88;
    static final int SBCD_M = 89;
    static final int PACK_R = 90;
    static final int PACK_M = 91;
    static final int UNPK_R = 92;
    static final int UNPK_M = 93;
    static final int SUB_EA_DN = 94;
    static final int SUB_DN_EA = 95;
    static final int SUBA = 96;
    static final int SUBX_R = 97;
    static final int SUBX_M = 98;
    static final int CMP = 99;
    static final int CMPA = 100;
    static final int EOR = 101;
    static final int CMPM = 102;
    static final int AND_EA_DN = 103;
    static final int AND_DN_EA = 104;
    static final int MULU_W = 105;
    static final int MULS_W = 106;
    static final int ABCD_R = 107;
    static final int ABCD_M = 108;
    static final int EXG = 109;
    static final int ADD_EA_DN = 110;
    static final int ADD_DN_EA = 111;
    static final int ADDA = 112;
    static final int ADDX_R = 113;
    static final int ADDX_M = 114;
    static final int SHIFT_REG = 115;
    static final int SHIFT_MEM = 116;
    static final int BITFIELD = 117;
    static final int FPU_GENERAL = 118;
    static final int FSCC = 119;
    static final int FDBCC = 120;
    static final int FTRAPCC = 121;
    static final int FBCC_W = 122;
    static final int FBCC_L = 123;
    static final int FSAVE = 124;
    static final int FRESTORE = 125;
    static final int CINV_CPUSH = 126;
    static final int PFLUSH = 127;
    static final int PTEST = 128;
    static final int MOVE16 = 129;
    static final int ILLEGAL_OP = 130;
    /** Number of op ids. */
    static final int COUNT = 131;

    // ---- effective address kinds: bit k of a class mask allows kind k ----

    /** Kind of mode/register: 0..6 for modes 0..6, 7 abs.W, 8 abs.L, 9 d16(PC), 10 d8(PC,Xn), 11 #imm; -1 reserved. */
    static int kind(int mode, int reg) {
        return mode < 7 ? mode : reg <= 4 ? 7 + reg : -1;
    }

    static final int K_DN = 1;
    static final int K_AN = 1 << 1;
    static final int K_IND = 1 << 2;
    static final int K_POST = 1 << 3;
    static final int K_PRE = 1 << 4;
    static final int K_D16 = 1 << 5;
    static final int K_IDX = 1 << 6;
    static final int K_ABSW = 1 << 7;
    static final int K_ABSL = 1 << 8;
    static final int K_PCD = 1 << 9;
    static final int K_PCI = 1 << 10;
    static final int K_IMM = 1 << 11;

    /** Every mode. */
    static final int ALL = 0xFFF;
    /** Data addressing: all but An. */
    static final int DATA = ALL & ~K_AN;
    /** Memory addressing: all but Dn and An. */
    static final int MEM = DATA & ~K_DN;
    /** Control addressing. */
    static final int CTRL = K_IND | K_D16 | K_IDX | K_ABSW | K_ABSL | K_PCD | K_PCI;
    /** Alterable addressing. */
    static final int ALT = K_DN | K_AN | K_IND | K_POST | K_PRE | K_D16 | K_IDX | K_ABSW | K_ABSL;
    static final int DALT = ALT & ~K_AN;
    static final int MALT = ALT & ~(K_DN | K_AN);
    static final int CALT = CTRL & ALT;

    private static final short[] TABLE_68000 = build(false);
    private static final short[] TABLE_68040 = build(true);

    /** The shared table of a model; callers must not modify it. */
    static short[] table(boolean is040) {
        return is040 ? TABLE_68040 : TABLE_68000;
    }

    static short[] build(boolean is040) {
        short[] t = new short[65536];
        for (int op = 0; op < 65536; op++)
            t[op] = (short) classify(op, is040);
        return t;
    }

    private static boolean ea(int op, int classMask) {
        int k = kind((op >> 3) & 7, op & 7);
        return k >= 0 && ((classMask >> k) & 1) != 0;
    }

    private static int when(boolean ok, int id) {
        return ok ? id : ILLEGAL;
    }

    /** The op id of {@code op} on the 68000 ({@code m40} false) or the 68040. */
    static int classify(int op, boolean m40) {
        final int mode = (op >> 3) & 7;
        final int size = (op >> 6) & 3;
        final int rx = (op >> 9) & 7;
        switch (op >>> 12) {
            case 0x0:
                return line0(op, m40, mode, size, rx);
            case 0x1:
            case 0x2:
            case 0x3:
                return move(op, rx);
            case 0x4:
                return line4(op, m40, mode, size, rx);
            case 0x5:
                if (size != 3) {
                    boolean sub = (op & 0x100) != 0;
                    if (mode == 1)
                        return when(size != 0, sub ? SUBQ_A : ADDQ_A);
                    return when(ea(op, DALT), sub ? SUBQ : ADDQ);
                }
                if (mode == 1)
                    return DBCC;
                if (mode == 7 && (op & 7) >= 2 && (op & 7) <= 4)
                    return when(m40, TRAPCC);
                return when(ea(op, DALT), SCC);
            case 0x6: {
                int cc = (op >> 8) & 15;
                return cc == 0 ? BRA : cc == 1 ? BSR : BCC;
            }
            case 0x7:
                return when((op & 0x100) == 0, MOVEQ);
            case 0x8: {
                int opmode = (op >> 6) & 7;
                if (opmode == 3)
                    return when(ea(op, DATA), DIVU_W);
                if (opmode == 7)
                    return when(ea(op, DATA), DIVS_W);
                if (opmode == 4 && mode <= 1)
                    return mode == 0 ? SBCD_R : SBCD_M;
                if (opmode == 5 && mode <= 1)
                    return when(m40, mode == 0 ? PACK_R : PACK_M);
                if (opmode == 6 && mode <= 1)
                    return when(m40, mode == 0 ? UNPK_R : UNPK_M);
                if (opmode < 3)
                    return when(ea(op, DATA), OR_EA_DN);
                return when(ea(op, MALT), OR_DN_EA);
            }
            case 0x9:
            case 0xD: {
                boolean add = (op >>> 12) == 0xD;
                int opmode = (op >> 6) & 7;
                if (opmode == 3 || opmode == 7)
                    return when(ea(op, ALL), add ? ADDA : SUBA);
                if (opmode >= 4 && mode <= 1)
                    return add ? (mode == 0 ? ADDX_R : ADDX_M) : (mode == 0 ? SUBX_R : SUBX_M);
                if (opmode < 3)
                    return when(ea(op, size == 0 ? ALL & ~K_AN : ALL), add ? ADD_EA_DN : SUB_EA_DN);
                return when(ea(op, MALT), add ? ADD_DN_EA : SUB_DN_EA);
            }
            case 0xA:
                return LINE_A;
            case 0xB: {
                int opmode = (op >> 6) & 7;
                if (opmode < 3)
                    return when(ea(op, size == 0 ? ALL & ~K_AN : ALL), CMP);
                if (opmode == 3 || opmode == 7)
                    return when(ea(op, ALL), CMPA);
                if (mode == 1)
                    return CMPM;
                return when(ea(op, DALT), EOR);
            }
            case 0xC: {
                int opmode = (op >> 6) & 7;
                if (opmode == 3)
                    return when(ea(op, DATA), MULU_W);
                if (opmode == 7)
                    return when(ea(op, DATA), MULS_W);
                if (opmode == 4 && mode <= 1)
                    return mode == 0 ? ABCD_R : ABCD_M;
                if (opmode == 5 && mode <= 1)
                    return EXG;
                if (opmode == 6 && mode == 1)
                    return EXG;
                if (opmode < 3)
                    return when(ea(op, DATA), AND_EA_DN);
                return when(ea(op, MALT), AND_DN_EA);
            }
            case 0xE:
                if (size != 3)
                    return SHIFT_REG;
                if ((op & 0x800) == 0)
                    return when(ea(op, MALT), SHIFT_MEM);
                if (!m40)
                    return ILLEGAL;
                switch ((op >> 8) & 7) {
                    case 0: case 1: case 3: case 5:
                        return when(mode == 0 || ea(op, CTRL), BITFIELD);
                    default:
                        return when(mode == 0 || ea(op, CALT), BITFIELD);
                }
            default:
                return m40 ? lineF040(op, mode) : LINE_F;
        }
    }

    private static int line0(int op, boolean m40, int mode, int size, int rx) {
        switch (op) {
            case 0x003C: return ORI_CCR;
            case 0x007C: return ORI_SR;
            case 0x023C: return ANDI_CCR;
            case 0x027C: return ANDI_SR;
            case 0x0A3C: return EORI_CCR;
            case 0x0A7C: return EORI_SR;
            default: break;
        }
        if ((op & 0x100) != 0) {
            if (mode == 1)
                return MOVEP;
            if (size == 0)
                return when(ea(op, DATA), BTST_REG);
            return when(ea(op, DALT), BCHG_REG + size - 1);
        }
        if (rx == 4) {
            if (size == 0)
                return when(ea(op, DATA & ~K_IMM), BTST_IMM);
            return when(ea(op, DALT), BCHG_IMM + size - 1);
        }
        if (size == 3) {
            if (!m40)
                return ILLEGAL;
            if (rx <= 2)
                return when(ea(op, CTRL), CMP2_CHK2);
            if (rx >= 5) {
                if (op == 0x0CFC || op == 0x0EFC)
                    return CAS2;
                return when(ea(op, MALT), CAS);
            }
            return ILLEGAL;
        }
        switch (rx) {
            case 0: return when(ea(op, DALT), ORI);
            case 1: return when(ea(op, DALT), ANDI);
            case 2: return when(ea(op, DALT), SUBI);
            case 3: return when(ea(op, DALT), ADDI);
            case 5: return when(ea(op, DALT), EORI);
            case 6: return when(ea(op, m40 ? DATA & ~K_IMM : DALT), CMPI);
            case 7: return when(m40 && ea(op, MALT), MOVES);
            default: return ILLEGAL;
        }
    }

    private static int move(int op, int rx) {
        int line = op >>> 12;
        int sz = line == 1 ? 0 : line == 3 ? 1 : 2;
        if (!ea(op, sz == 0 ? ALL & ~K_AN : ALL))
            return ILLEGAL;
        int dmode = (op >> 6) & 7;
        if (dmode == 1)
            return sz == 0 ? ILLEGAL : sz == 1 ? MOVEA_W : MOVEA_L;
        int k = kind(dmode, rx);
        if (k < 0 || ((DALT >> k) & 1) == 0)
            return ILLEGAL;
        return sz == 0 ? MOVE_B : sz == 1 ? MOVE_W : MOVE_L;
    }

    private static int line4(int op, boolean m40, int mode, int size, int rx) {
        if ((op & 0x100) != 0) {
            switch (size) {
                case 3:
                    if (mode == 0 && rx == 4)
                        return when(m40, EXTB_L);
                    return when(ea(op, CTRL), LEA);
                case 2:
                    return when(ea(op, DATA), CHK_W);
                case 0:
                    return when(m40 && ea(op, DATA), CHK_L);
                default:
                    return ILLEGAL;
            }
        }
        switch (rx) {
            case 0:
                return size == 3 ? when(ea(op, DALT), MOVE_FROM_SR) : when(ea(op, DALT), NEGX);
            case 1:
                return size == 3 ? when(m40 && ea(op, DALT), MOVE_FROM_CCR) : when(ea(op, DALT), CLR);
            case 2:
                return size == 3 ? when(ea(op, DATA), MOVE_TO_CCR) : when(ea(op, DALT), NEG);
            case 3:
                return size == 3 ? when(ea(op, DATA), MOVE_TO_SR) : when(ea(op, DALT), NOT);
            case 4:
                switch (size) {
                    case 0:
                        if (mode == 1)
                            return when(m40, LINK_L);
                        return when(ea(op, DALT), NBCD);
                    case 1:
                        if (mode == 0)
                            return SWAP;
                        if (mode == 1)
                            return ILLEGAL; // BKPT: the illegal instruction exception on both models
                        return when(ea(op, CTRL), PEA);
                    case 2:
                        if (mode == 0)
                            return EXT_W;
                        return when(ea(op, CALT | K_PRE), MOVEM_TO_MEM);
                    default:
                        if (mode == 0)
                            return EXT_L;
                        return when(ea(op, CALT | K_PRE), MOVEM_TO_MEM);
                }
            case 5:
                if (size == 3) {
                    if (op == 0x4AFC)
                        return ILLEGAL_OP;
                    return when(ea(op, DALT), TAS);
                }
                if (!m40)
                    return when(ea(op, DALT), TST);
                return when(ea(op, size == 0 ? ALL & ~K_AN : ALL), TST);
            case 6:
                switch (size) {
                    case 0: return when(m40 && ea(op, DATA), MUL_L);
                    case 1: return when(m40 && ea(op, DATA), DIV_L);
                    default: return when(ea(op, CTRL | K_POST), MOVEM_TO_REG);
                }
            case 7:
                return line4E(op, m40, mode, size);
            default:
                return ILLEGAL;
        }
    }

    private static int line4E(int op, boolean m40, int mode, int size) {
        if (size == 2)
            return when(ea(op, CTRL), JSR);
        if (size == 3)
            return when(ea(op, CTRL), JMP);
        if (size == 0)
            return ILLEGAL;
        switch (mode) {
            case 0: case 1: return TRAP;
            case 2: return LINK_W;
            case 3: return UNLK;
            case 4: return MOVE_TO_USP;
            case 5: return MOVE_FROM_USP;
            default: break;
        }
        switch (op) {
            case 0x4E70: return RESET;
            case 0x4E71: return NOP;
            case 0x4E72: return STOP;
            case 0x4E73: return RTE;
            case 0x4E74: return when(m40, RTD);
            case 0x4E75: return RTS;
            case 0x4E76: return TRAPV;
            case 0x4E77: return RTR;
            case 0x4E7A: case 0x4E7B: return when(m40, MOVEC);
            default: return ILLEGAL;
        }
    }

    /** Line F on the 68040 (spec 10.3.7): FPU (coprocessor id 1), cache, MMU and MOVE16 encodings; the rest take vector 11. */
    private static int lineF040(int op, int mode) {
        int reg = op & 7;
        switch ((op >> 6) & 0x3F) {
            case 0x08: // 0xF200
                return FPU_GENERAL;
            case 0x09: // 0xF240
                if (mode == 1)
                    return FDBCC;
                if (mode == 7 && reg >= 2 && reg <= 4)
                    return FTRAPCC;
                return ea(op, DALT) ? FSCC : LINE_F;
            case 0x0A: // 0xF280
                return FBCC_W;
            case 0x0B: // 0xF2C0
                return FBCC_L;
            case 0x0C: // 0xF300
                return ea(op, CALT | K_PRE) ? FSAVE : LINE_F;
            case 0x0D: // 0xF340
                return ea(op, CTRL | K_POST) ? FRESTORE : LINE_F;
            case 0x10: case 0x11: case 0x12: case 0x13: // 0xF400..0xF4FF
                return ((op >> 3) & 3) != 0 ? CINV_CPUSH : LINE_F;
            case 0x14: // 0xF500..0xF53F
                return (op & 0x20) == 0 ? PFLUSH : LINE_F;
            case 0x15: // 0xF540..0xF57F
                return (op & 0x18) == 0x08 ? PTEST : LINE_F;
            case 0x18: // 0xF600..0xF63F
                if ((op & 0x20) == 0)
                    return MOVE16;
                return (op & 0x38) == 0x20 ? MOVE16 : LINE_F;
            default:
                return LINE_F;
        }
    }
}
