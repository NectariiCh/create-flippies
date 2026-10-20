package com.nectariich.create_flippies.content.computer.asm.rv32;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.assertWords;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.word;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact words for every real instruction, against encodings worked out by hand from the RISC-V
 * unprivileged spec's base formats (R, I, S, B, U, J). Independent of the CPU emulator.
 */
class Rv32EncodingTest {

    /** The checks listed in spec 5.2. */
    @Test
    void specEncodings() {
        assertEquals(0x0050_0093, word("addi x1, x0, 5"));
        assertEquals(0x0020_81B3, word("add x3, x1, x2"));
        assertEquals(0x4020_81B3, word("sub x3, x1, x2"));
        assertEquals(0x1234_52B7, word("lui x5, 0x12345"));
        assertEquals(0x0080_00EF, word("jal x1, 8"));
        assertEquals(0x0020_8463, word("beq x1, x2, 8"));
        assertEquals(0x0020_A223, word("sw x2, 4(x1)"));
        assertEquals(0xFFC0_A183, word("lw x3, -4(x1)"));
        assertEquals(0x3052_9073, word("csrrw x0, mtvec, x5"));
        assertEquals(0x0220_81B3, word("mul x3, x1, x2"));
        assertEquals(0xC000_1073, word("unimp"));
    }

    static Stream<Arguments> encodings() {
        return Stream.of(
                Arguments.of("sll x5, x6, x7", 0x007312B3),
                Arguments.of("slt x5, x6, x7", 0x007322B3),
                Arguments.of("sltu x5, x6, x7", 0x007332B3),
                Arguments.of("xor x5, x6, x7", 0x007342B3),
                Arguments.of("srl x5, x6, x7", 0x007352B3),
                Arguments.of("sra x5, x6, x7", 0x407352B3),
                Arguments.of("or x5, x6, x7", 0x007362B3),
                Arguments.of("and x5, x6, x7", 0x007372B3),
                Arguments.of("mulh x5, x6, x7", 0x027312B3),
                Arguments.of("mulhsu x5, x6, x7", 0x027322B3),
                Arguments.of("mulhu x5, x6, x7", 0x027332B3),
                Arguments.of("div x5, x6, x7", 0x027342B3),
                Arguments.of("divu x5, x6, x7", 0x027352B3),
                Arguments.of("rem x5, x6, x7", 0x027362B3),
                Arguments.of("remu x31, x30, x29", 0x03DF7FB3),
                Arguments.of("addi a0, a1, -1", 0xFFF58513),
                Arguments.of("slti a0, a1, 2047", 0x7FF5A513),
                Arguments.of("sltiu a0, a1, -2048", 0x8005B513),
                Arguments.of("xori t0, t1, 0x55", 0x05534293),
                Arguments.of("ori t0, t1, 1", 0x00136293),
                Arguments.of("andi t0, t1, 0xF0", 0x0F037293),
                Arguments.of("slli a0, a0, 31", 0x01F51513),
                Arguments.of("srli a0, a0, 1", 0x00155513),
                Arguments.of("srai a0, a0, 7", 0x40755513),
                Arguments.of("lb s1, 0(sp)", 0x00010483),
                Arguments.of("lh s1, 2(sp)", 0x00211483),
                Arguments.of("lbu s1, -1(sp)", 0xFFF14483),
                Arguments.of("lhu s1, 2047(sp)", 0x7FF15483),
                Arguments.of("lw a0, (a1)", 0x0005A503),
                Arguments.of("sb a0, -2048(a1)", 0x80A58023),
                Arguments.of("sh a0, 6(a1)", 0x00A59323),
                Arguments.of("sw ra, 12(sp)", 0x00112623),
                Arguments.of("bne x1, x2, -4", 0xFE209EE3),
                Arguments.of("blt a0, a1, 4094", 0x7EB54FE3),
                Arguments.of("bge a0, a1, -4096", 0x80B55063),
                Arguments.of("bltu a0, a1, 16", 0x00B56863),
                Arguments.of("bgeu a0, a1, 2048", 0x00B570E3),
                Arguments.of("auipc gp, 0xFFFFF", 0xFFFFF197),
                Arguments.of("jal x0, -8", 0xFF9FF06F),
                Arguments.of("jal ra, 1048574", 0x7FFFF0EF),
                Arguments.of("jal ra, -1048576", 0x800000EF),
                Arguments.of("jalr ra, 4(t0)", 0x004280E7),
                Arguments.of("jalr ra, t0, 4", 0x004280E7),
                Arguments.of("csrrs a0, mstatus, x0", 0x30002573),
                Arguments.of("csrrc a0, mie, a1", 0x3045B573),
                Arguments.of("csrrwi zero, mtvec, 31", 0x305FD073),
                Arguments.of("csrrsi a0, mstatus, 8", 0x30046573),
                Arguments.of("csrrci a0, mstatus, 8", 0x30047573),
                Arguments.of("csrrw t0, 0xFFF, t1", 0xFFF312F3),
                // SYSTEM and MISC-MEM fixed words (spec 5.2).
                Arguments.of("ecall", 0x0000_0073),
                Arguments.of("ebreak", 0x0010_0073),
                Arguments.of("mret", 0x3020_0073),
                Arguments.of("wfi", 0x1050_0073),
                Arguments.of("fence", 0x0FF0_000F),
                Arguments.of("fence iorw, iorw", 0x0FF0_000F),
                Arguments.of("fence rw, w", 0x0310_000F),
                Arguments.of("fence i, o", 0x0840_000F),
                Arguments.of("fence.tso", 0x8330_000F),
                Arguments.of("pause", 0x0100_000F),
                Arguments.of("fence.i", 0x0000_100F),
                // Mnemonics and registers are case-insensitive; fp is s0.
                Arguments.of("ADD X3, X1, X2", 0x0020_81B3),
                Arguments.of("addi fp, s0, 0", 0x00040413));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void encodes(String insn, int expected) {
        assertWords(insn, expected);
    }

    /** Field extraction per the base formats, for the round-trip checks. */
    private static int opcode(int w) { return w & 0x7F; }
    private static int rd(int w) { return (w >>> 7) & 31; }
    private static int f3(int w) { return (w >>> 12) & 7; }
    private static int rs1(int w) { return (w >>> 15) & 31; }
    private static int rs2(int w) { return (w >>> 20) & 31; }
    private static int f7(int w) { return w >>> 25; }
    private static int immI(int w) { return w >> 20; }
    private static int immS(int w) { return ((w >> 25) << 5) | ((w >>> 7) & 31); }
    private static int immB(int w) {
        return ((w >> 31) << 12) | (((w >>> 7) & 1) << 11) | (((w >>> 25) & 0x3F) << 5) | (((w >>> 8) & 0xF) << 1);
    }
    private static int immJ(int w) {
        return ((w >> 31) << 20) | (((w >>> 12) & 0xFF) << 12) | (((w >>> 20) & 1) << 11) | (((w >>> 21) & 0x3FF) << 1);
    }

    /** Every R-type base and M instruction with all register numbers distinct and nonzero. */
    @Test
    void rTypeRoundTrip() {
        String[] names = {"add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and",
                "mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"};
        int[] f3s = {0, 0, 1, 2, 3, 4, 5, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7};
        int[] f7s = {0, 0x20, 0, 0, 0, 0, 0, 0x20, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1};
        for (int i = 0; i < names.length; i++) {
            for (int[] r : new int[][] {{1, 2, 3}, {31, 30, 29}, {17, 0, 9}}) {
                int w = word(names[i] + " x" + r[0] + ", x" + r[1] + ", x" + r[2]);
                String what = names[i];
                assertEquals(0b0110011, opcode(w), what);
                assertEquals(r[0], rd(w), what);
                assertEquals(r[1], rs1(w), what);
                assertEquals(r[2], rs2(w), what);
                assertEquals(f3s[i], f3(w), what);
                assertEquals(f7s[i], f7(w), what);
            }
        }
    }

    @Test
    void iTypeRoundTrip() {
        String[] names = {"addi", "slti", "sltiu", "xori", "ori", "andi"};
        int[] f3s = {0, 2, 3, 4, 6, 7};
        for (int i = 0; i < names.length; i++) {
            for (int imm : new int[] {0, 1, -1, 2047, -2048, 0x555, -0x556}) {
                int w = word(names[i] + " x7, x12, " + imm);
                assertEquals(0b0010011, opcode(w));
                assertEquals(7, rd(w));
                assertEquals(12, rs1(w));
                assertEquals(f3s[i], f3(w));
                assertEquals(imm, immI(w), names[i] + " " + imm);
            }
        }
    }

    @Test
    void shiftRoundTrip() {
        for (int sh = 0; sh < 32; sh++) {
            int w = word("srai x1, x2, " + sh);
            assertEquals(5, f3(w));
            assertEquals(0x20, f7(w));
            assertEquals(sh, rs2(w));
            w = word("slli x1, x2, " + sh);
            assertEquals(1, f3(w));
            assertEquals(0, f7(w));
            assertEquals(sh, rs2(w));
            w = word("srli x1, x2, " + sh);
            assertEquals(5, f3(w));
            assertEquals(0, f7(w));
        }
    }

    @Test
    void loadStoreRoundTrip() {
        String[] loads = {"lb", "lh", "lw", "lbu", "lhu"};
        int[] lf3 = {0, 1, 2, 4, 5};
        String[] stores = {"sb", "sh", "sw"};
        for (int imm : new int[] {0, 4, -4, 2047, -2048, 0x7A5, -0x7A5}) {
            for (int i = 0; i < loads.length; i++) {
                int w = word(loads[i] + " x9, " + imm + "(x20)");
                assertEquals(0b0000011, opcode(w));
                assertEquals(9, rd(w));
                assertEquals(20, rs1(w));
                assertEquals(lf3[i], f3(w));
                assertEquals(imm, immI(w));
            }
            for (int i = 0; i < stores.length; i++) {
                int w = word(stores[i] + " x9, " + imm + "(x20)");
                assertEquals(0b0100011, opcode(w));
                assertEquals(9, rs2(w));
                assertEquals(20, rs1(w));
                assertEquals(i, f3(w));
                assertEquals(imm, immS(w), stores[i] + " " + imm);
            }
        }
    }

    @Test
    void branchRoundTrip() {
        String[] names = {"beq", "bne", "blt", "bge", "bltu", "bgeu"};
        int[] f3s = {0, 1, 4, 5, 6, 7};
        for (int off : new int[] {0, 4, -4, 8, 2048, -2048, 4092, 4094, -4096, 0x554, -0xAA8}) {
            for (int i = 0; i < names.length; i++) {
                int w = word(names[i] + " x3, x4, " + off);
                assertEquals(0b1100011, opcode(w));
                assertEquals(3, rs1(w));
                assertEquals(4, rs2(w));
                assertEquals(f3s[i], f3(w));
                assertEquals(off, immB(w), names[i] + " " + off);
            }
        }
    }

    @Test
    void jumpAndUpperRoundTrip() {
        for (int off : new int[] {0, 4, -4, 2048, 4096, -4096, 0x7FFFE, 1048574, -1048576, 0x55554}) {
            int w = word("jal x5, " + off);
            assertEquals(0b1101111, opcode(w));
            assertEquals(5, rd(w));
            assertEquals(off, immJ(w), "jal " + off);
        }
        for (int imm : new int[] {0, 1, 0x80000, 0xFFFFF, 0x12345}) {
            int w = word("lui x6, " + imm);
            assertEquals(0b0110111, opcode(w));
            assertEquals(6, rd(w));
            assertEquals(imm, w >>> 12);
            w = word("auipc x6, " + imm);
            assertEquals(0b0010111, opcode(w));
            assertEquals(imm, w >>> 12);
        }
        int w = word("jalr x5, -2048(x6)");
        assertEquals(0b1100111, opcode(w));
        assertEquals(0, f3(w));
        assertEquals(5, rd(w));
        assertEquals(6, rs1(w));
        assertEquals(-2048, immI(w));
    }

    @Test
    void csrRoundTrip() {
        String[] names = {"csrrw", "csrrs", "csrrc", "csrrwi", "csrrsi", "csrrci"};
        for (int i = 0; i < names.length; i++) {
            for (int csr : new int[] {0, 0x300, 0x7FF, 0xB00, 0xC00, 0xFFF}) {
                String src = i < 3 ? names[i] + " x8, " + csr + ", x9" : names[i] + " x8, " + csr + ", 9";
                int w = word(src);
                assertEquals(0b1110011, opcode(w));
                assertEquals(8, rd(w));
                assertEquals(9, rs1(w));
                assertEquals(i < 3 ? i + 1 : i + 2, f3(w), names[i]);
                assertEquals(csr, w >>> 20);
            }
        }
    }

    /** Every named CSR of spec 4.4 assembles to its number. */
    @Test
    void csrNames() {
        assertEquals(0xF11, word("csrr a0, mvendorid") >>> 20);
        assertEquals(0xF12, word("csrr a0, marchid") >>> 20);
        assertEquals(0xF13, word("csrr a0, mimpid") >>> 20);
        assertEquals(0xF14, word("csrr a0, mhartid") >>> 20);
        assertEquals(0xF15, word("csrr a0, mconfigptr") >>> 20);
        assertEquals(0x300, word("csrr a0, mstatus") >>> 20);
        assertEquals(0x301, word("csrr a0, misa") >>> 20);
        assertEquals(0x304, word("csrr a0, mie") >>> 20);
        assertEquals(0x305, word("csrr a0, mtvec") >>> 20);
        assertEquals(0x310, word("csrr a0, mstatush") >>> 20);
        assertEquals(0x320, word("csrr a0, mcountinhibit") >>> 20);
        assertEquals(0x323, word("csrr a0, mhpmevent3") >>> 20);
        assertEquals(0x33F, word("csrr a0, mhpmevent31") >>> 20);
        assertEquals(0x340, word("csrr a0, mscratch") >>> 20);
        assertEquals(0x341, word("csrr a0, mepc") >>> 20);
        assertEquals(0x342, word("csrr a0, mcause") >>> 20);
        assertEquals(0x343, word("csrr a0, mtval") >>> 20);
        assertEquals(0x344, word("csrr a0, mip") >>> 20);
        assertEquals(0xB00, word("csrr a0, mcycle") >>> 20);
        assertEquals(0xB02, word("csrr a0, minstret") >>> 20);
        assertEquals(0xB03, word("csrr a0, mhpmcounter3") >>> 20);
        assertEquals(0xB1F, word("csrr a0, mhpmcounter31") >>> 20);
        assertEquals(0xB80, word("csrr a0, mcycleh") >>> 20);
        assertEquals(0xB82, word("csrr a0, minstreth") >>> 20);
        assertEquals(0xB83, word("csrr a0, mhpmcounter3h") >>> 20);
        assertEquals(0xB9F, word("csrr a0, mhpmcounter31h") >>> 20);
        assertEquals(0xC00, word("csrr a0, cycle") >>> 20);
        assertEquals(0xC01, word("csrr a0, time") >>> 20);
        assertEquals(0xC02, word("csrr a0, instret") >>> 20);
        assertEquals(0xC03, word("csrr a0, hpmcounter3") >>> 20);
        assertEquals(0xC1F, word("csrr a0, hpmcounter31") >>> 20);
        assertEquals(0xC80, word("csrr a0, cycleh") >>> 20);
        assertEquals(0xC81, word("csrr a0, timeh") >>> 20);
        assertEquals(0xC82, word("csrr a0, instreth") >>> 20);
        assertEquals(0xC83, word("csrr a0, hpmcounter3h") >>> 20);
        assertEquals(0xC9F, word("csrr a0, hpmcounter31h") >>> 20);
    }

    /** Every register name maps to its number in rd. */
    @Test
    void registerNames() {
        String[] abi = {"zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2",
                "a3", "a4", "a5", "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11",
                "t3", "t4", "t5", "t6"};
        for (int i = 0; i < 32; i++) {
            assertEquals(i, rd(word("add " + abi[i] + ", x0, x0")), abi[i]);
            assertEquals(i, rd(word("add x" + i + ", x0, x0")));
        }
        assertEquals(8, rd(word("add fp, x0, x0")));
    }

    /** Branch and jump targets given as labels are pc-relative, forward and backward. */
    @Test
    void labelTargets() {
        assertWords("""
                top:
                    beq a0, a1, done
                    nop
                    j top
                done:
                    bne a0, zero, top
                """,
                0x00B50663,   // beq a0, a1, +12
                0x00000013,
                0xFF9FF06F,   // jal x0, -8
                0xFE051AE3);  // bne a0, x0, -12
    }
}
