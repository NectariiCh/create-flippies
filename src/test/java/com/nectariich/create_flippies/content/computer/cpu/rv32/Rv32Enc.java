package com.nectariich.create_flippies.content.computer.cpu.rv32;

/**
 * Hand-written RV32IM + Zicsr encoder for the CPU tests, straight from the unprivileged spec's
 * instruction formats (chapter 2.2 and 2.3, "RV32/64G Instruction Set Listings"). It exists so
 * the CPU tests do not depend on the assembler; {@link Rv32EncodingTest} pins it to words checked
 * by hand.
 */
final class Rv32Enc {

    private Rv32Enc() {
    }

    // ---- ABI register numbers ----

    static final int ZERO = 0, RA = 1, SP = 2, GP = 3, TP = 4, T0 = 5, T1 = 6, T2 = 7, S0 = 8, S1 = 9;
    static final int A0 = 10, A1 = 11, A2 = 12, A3 = 13, A4 = 14, A5 = 15, A6 = 16, A7 = 17;
    static final int S2 = 18, T3 = 28, T4 = 29, T5 = 30, T6 = 31;

    // ---- formats ----

    static int r(int f7, int rs2, int rs1, int f3, int rd, int opcode) {
        return f7 << 25 | rs2 << 20 | rs1 << 15 | f3 << 12 | rd << 7 | opcode;
    }

    static int i(int imm, int rs1, int f3, int rd, int opcode) {
        return imm << 20 | rs1 << 15 | f3 << 12 | rd << 7 | opcode;
    }

    static int s(int imm, int rs2, int rs1, int f3) {
        return ((imm >> 5) & 0x7F) << 25 | rs2 << 20 | rs1 << 15 | f3 << 12 | (imm & 31) << 7 | 0x23;
    }

    static int b(int imm, int rs2, int rs1, int f3) {
        return ((imm >> 12) & 1) << 31 | ((imm >> 5) & 0x3F) << 25 | rs2 << 20 | rs1 << 15 | f3 << 12
                | ((imm >> 1) & 0xF) << 8 | ((imm >> 11) & 1) << 7 | 0x63;
    }

    static int u(int imm20, int rd, int opcode) {
        return imm20 << 12 | rd << 7 | opcode;
    }

    static int j(int imm, int rd) {
        return ((imm >> 20) & 1) << 31 | ((imm >> 1) & 0x3FF) << 21 | ((imm >> 11) & 1) << 20
                | ((imm >> 12) & 0xFF) << 12 | rd << 7 | 0x6F;
    }

    // ---- RV32I ----

    static int lui(int rd, int imm20) { return u(imm20, rd, 0x37); }
    static int auipc(int rd, int imm20) { return u(imm20, rd, 0x17); }
    static int jal(int rd, int offset) { return j(offset, rd); }
    static int jalr(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 0, rd, 0x67); }

    static int beq(int rs1, int rs2, int off) { return b(off, rs2, rs1, 0); }
    static int bne(int rs1, int rs2, int off) { return b(off, rs2, rs1, 1); }
    static int blt(int rs1, int rs2, int off) { return b(off, rs2, rs1, 4); }
    static int bge(int rs1, int rs2, int off) { return b(off, rs2, rs1, 5); }
    static int bltu(int rs1, int rs2, int off) { return b(off, rs2, rs1, 6); }
    static int bgeu(int rs1, int rs2, int off) { return b(off, rs2, rs1, 7); }

    static int lb(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 0, rd, 0x03); }
    static int lh(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 1, rd, 0x03); }
    static int lw(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 2, rd, 0x03); }
    static int lbu(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 4, rd, 0x03); }
    static int lhu(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 5, rd, 0x03); }
    static int sb(int rs2, int rs1, int imm) { return s(imm, rs2, rs1, 0); }
    static int sh(int rs2, int rs1, int imm) { return s(imm, rs2, rs1, 1); }
    static int sw(int rs2, int rs1, int imm) { return s(imm, rs2, rs1, 2); }

    static int addi(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 0, rd, 0x13); }
    static int slti(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 2, rd, 0x13); }
    static int sltiu(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 3, rd, 0x13); }
    static int xori(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 4, rd, 0x13); }
    static int ori(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 6, rd, 0x13); }
    static int andi(int rd, int rs1, int imm) { return i(imm & 0xFFF, rs1, 7, rd, 0x13); }
    static int slli(int rd, int rs1, int sh) { return i(sh, rs1, 1, rd, 0x13); }
    static int srli(int rd, int rs1, int sh) { return i(sh, rs1, 5, rd, 0x13); }
    static int srai(int rd, int rs1, int sh) { return i(0x400 | sh, rs1, 5, rd, 0x13); }

    static int add(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 0, rd, 0x33); }
    static int sub(int rd, int rs1, int rs2) { return r(0x20, rs2, rs1, 0, rd, 0x33); }
    static int sll(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 1, rd, 0x33); }
    static int slt(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 2, rd, 0x33); }
    static int sltu(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 3, rd, 0x33); }
    static int xor(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 4, rd, 0x33); }
    static int srl(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 5, rd, 0x33); }
    static int sra(int rd, int rs1, int rs2) { return r(0x20, rs2, rs1, 5, rd, 0x33); }
    static int or(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 6, rd, 0x33); }
    static int and(int rd, int rs1, int rs2) { return r(0, rs2, rs1, 7, rd, 0x33); }

    static int fence(int pred, int succ) { return i(pred << 4 | succ, 0, 0, 0, 0x0F); }
    static int fenceI() { return i(0, 0, 1, 0, 0x0F); }

    static final int ECALL = 0x0000_0073;
    static final int EBREAK = 0x0010_0073;
    static final int MRET = 0x3020_0073;
    static final int WFI = 0x1050_0073;
    static final int NOP = 0x0000_0013;
    /** {@code unimp}: csrrw x0, cycle, x0. */
    static final int UNIMP = 0xC000_1073;

    // ---- M ----

    static int mul(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 0, rd, 0x33); }
    static int mulh(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 1, rd, 0x33); }
    static int mulhsu(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 2, rd, 0x33); }
    static int mulhu(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 3, rd, 0x33); }
    static int div(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 4, rd, 0x33); }
    static int divu(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 5, rd, 0x33); }
    static int rem(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 6, rd, 0x33); }
    static int remu(int rd, int rs1, int rs2) { return r(1, rs2, rs1, 7, rd, 0x33); }

    // ---- Zicsr ----

    static int csrrw(int rd, int csr, int rs1) { return i(csr, rs1, 1, rd, 0x73); }
    static int csrrs(int rd, int csr, int rs1) { return i(csr, rs1, 2, rd, 0x73); }
    static int csrrc(int rd, int csr, int rs1) { return i(csr, rs1, 3, rd, 0x73); }
    static int csrrwi(int rd, int csr, int uimm) { return i(csr, uimm, 5, rd, 0x73); }
    static int csrrsi(int rd, int csr, int uimm) { return i(csr, uimm, 6, rd, 0x73); }
    static int csrrci(int rd, int csr, int uimm) { return i(csr, uimm, 7, rd, 0x73); }
    /** csrr rd, csr. */
    static int csrr(int rd, int csr) { return csrrs(rd, csr, ZERO); }
    /** csrw csr, rs1. */
    static int csrw(int csr, int rs1) { return csrrw(ZERO, csr, rs1); }

    /** li rd, value as lui + addi (the addi's sign extension is compensated in the lui part). */
    static int[] li(int rd, int value) {
        int hi = (value + 0x800) & 0xFFFF_F000;
        int lo = value - hi;
        return new int[] { lui(rd, hi >>> 12), addi(rd, rd, lo) };
    }
}
