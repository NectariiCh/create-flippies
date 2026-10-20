package com.nectariich.create_flippies.content.computer.asm.rv32;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.assertWords;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.oneError;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.toWords;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.words;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pseudo-instruction expansions (spec 5.2), li sizing, and the relocation operators. */
class Rv32PseudoTest {

    @Test
    void registerPseudos() {
        assertWords("mv a0, a1", 0x00058513);          // addi a0, a1, 0
        assertWords("not a0, a1", 0xFFF5C513);         // xori a0, a1, -1
        assertWords("neg a0, a1", 0x40B00533);         // sub a0, x0, a1
        assertWords("seqz a0, a1", 0x0015B513);        // sltiu a0, a1, 1
        assertWords("snez a0, a1", 0x00B03533);        // sltu a0, x0, a1
        assertWords("sltz a0, a1", 0x0005A533);        // slt a0, a1, x0
        assertWords("sgtz a0, a1", 0x00B02533);        // slt a0, x0, a1
        assertWords("zext.b a0, a1", 0x0FF5F513);      // andi a0, a1, 255
        assertWords("sgt a0, a1, a2", 0x00B62533);     // slt a0, a2, a1
        assertWords("sgtu a0, a1, a2", 0x00B63533);    // sltu a0, a2, a1
        assertWords("nop", 0x00000013);
    }

    @Test
    void extensionPseudosAreTwoWords() {
        assertWords("sext.b a0, a1", 0x01859513, 0x41855513);   // slli 24; srai 24
        assertWords("sext.h a0, a1", 0x01059513, 0x41055513);   // slli 16; srai 16
        assertWords("zext.h a0, a1", 0x01059513, 0x01055513);   // slli 16; srli 16
    }

    @Test
    void branchPseudos() {
        assertWords("beqz a0, 8", 0x00050463);         // beq a0, x0
        assertWords("bnez a0, 8", 0x00051463);         // bne a0, x0
        assertWords("blez a0, 8", 0x00A05463);         // bge x0, a0
        assertWords("bgez a0, 8", 0x00055463);         // bge a0, x0
        assertWords("bltz a0, 8", 0x00054463);         // blt a0, x0
        assertWords("bgtz a0, 8", 0x00A04463);         // blt x0, a0
        assertWords("bgt a0, a1, 8", 0x00A5C463);      // blt a1, a0
        assertWords("ble a0, a1, 8", 0x00A5D463);      // bge a1, a0
        assertWords("bgtu a0, a1, 8", 0x00A5E463);     // bltu a1, a0
        assertWords("bleu a0, a1, 8", 0x00A5F463);     // bgeu a1, a0
    }

    @Test
    void jumpPseudos() {
        assertWords("j 8", 0x0080006F);                // jal x0, 8
        assertWords("jal 8", 0x008000EF);              // jal ra, 8
        assertWords("jr a0", 0x00050067);              // jalr x0, 0(a0)
        assertWords("jr 4(a0)", 0x00450067);
        assertWords("jalr a0", 0x000500E7);            // jalr ra, 0(a0)
        assertWords("jalr ra, a0", 0x000500E7);
        assertWords("ret", 0x00008067);                // jalr x0, 0(ra)
    }

    @Test
    void csrPseudos() {
        assertWords("csrr a0, mstatus", 0x30002573);   // csrrs a0, mstatus, x0
        assertWords("csrw mtvec, a0", 0x30551073);     // csrrw x0, mtvec, a0
        assertWords("csrs mie, a0", 0x30452073);       // csrrs x0, mie, a0
        assertWords("csrc mie, a0", 0x30453073);       // csrrc x0, mie, a0
        assertWords("csrwi mtvec, 5", 0x3052D073);     // csrrwi x0, mtvec, 5
        assertWords("csrsi mstatus, 8", 0x30046073);
        assertWords("csrci mstatus, 8", 0x30047073);
        // GNU as turns an immediate source into the immediate form.
        assertWords("csrw mtvec, 5", 0x3052D073);
        assertWords("csrs mstatus, 8", 0x30046073);
        assertWords("csrrs a0, mstatus, 8", 0x30046573);
        assertWords("rdcycle a0", 0xC0002573);
        assertWords("rdcycleh a0", 0xC8002573);
        assertWords("rdtime a0", 0xC0102573);
        assertWords("rdtimeh a0", 0xC8102573);
        assertWords("rdinstret a0", 0xC0202573);
        assertWords("rdinstreth a0", 0xC8202573);
        assertWords("unimp", 0xC0001073);              // csrrw x0, cycle, x0
    }

    /** li boundaries of spec 8.2 with their expected words. */
    @Test
    void liBoundaries() {
        assertWords("li a0, 0", 0x00000513);
        assertWords("li a0, 2047", 0x7FF00513);
        assertWords("li a0, -2048", 0x80000513);
        assertWords("li a0, -1", 0xFFF00513);
        assertWords("li a0, 2048", 0x00001537, 0x80050513);           // lui a0, 1; addi a0, a0, -2048
        assertWords("li a0, 0x800", 0x00001537, 0x80050513);
        assertWords("li a0, -2049", 0xFFFFF537, 0x7FF50513);          // lui a0, 0xFFFFF; addi a0, a0, 2047
        assertWords("li a0, 0x7FFFFFFF", 0x80000537, 0xFFF50513);     // lui a0, 0x80000; addi a0, a0, -1
        assertWords("li a0, 0x80000000", 0x80000537);                 // lui only
        assertWords("li a0, 0xFFFFF800", 0x80000513);                 // one addi after normalising
        assertWords("li a0, 0x12345000", 0x12345537);                 // lui only
        assertWords("li a0, 0x12345FFF", 0x12346537, 0xFFF50513);
        assertWords("li a0, -0x80000000", 0x80000537);
        oneError("li a0, 0x100000000", 1, "does not fit in 32 bits");
        oneError("li a0, -0x80000001", 1, "does not fit in 32 bits");
    }

    @Test
    void liWithForwardValueIsEightBytes() {
        // X is not known when the first pass reaches li, so li takes the long form even though 5 fits.
        assertWords("li a0, X\n.equ X, 5", 0x00000537, 0x00550513);
        // Known before use: one word.
        assertWords(".equ Y, 5\nli a0, Y", 0x00500513);
        // A label is never known in the first pass.
        int[] w = words("li a0, here\nhere: nop");
        assertEquals(3, w.length);
        assertEquals(0x00001537, w[0]);                                // lui a0, %hi(0x1008)
        assertEquals(0x00850513, w[1]);                                // addi a0, a0, 8
    }

    @Test
    void hiLo() {
        assertWords("lui a0, %hi(0x12345FFF)\naddi a0, a0, %lo(0x12345FFF)", 0x12346537, 0xFFF50513);
        assertWords("lui a0, %hi(0x12345678)\naddi a0, a0, %lo(0x12345678)", 0x12345537, 0x67850513);
        assertWords("sw a1, %lo(0x12345FFF)(a0)", 0xFEB52FA3);
        assertWords("lw a1, %lo(0x12345FFF)(a0)", 0xFFF52583);
        // %hi(x) << 12 + %lo(x) == x for values around the bit 11 boundary.
        for (long x : new long[] {0, 0x7FF, 0x800, 0xFFF, 0x1000, 0x7FFFF800, 0x80000000L, 0xFFFFF800L, 0xFFFFFFFFL}) {
            int[] w = words("lui a0, %hi(" + x + ")\naddi a0, a0, %lo(" + x + ")");
            int hi = w[0] >>> 12, lo = w[1] >> 20;
            assertEquals((int) x, (hi << 12) + lo, "x = " + x);
        }
    }

    @Test
    void pcrelHiLo() {
        AsmImage img = ok("""
                1:  auipc a0, %pcrel_hi(target)
                    addi a0, a0, %pcrel_lo(1b)
                .Lp: auipc a1, %pcrel_hi(target)
                    lw a2, %pcrel_lo(.Lp)(a1)
                    sw a2, %pcrel_lo(.Lp)(a1)
                    .space 0x7F0
                target:
                """);
        int[] w = toWords(img.segments().get(0).bytes());
        // target = 0x1000 + 20 + 0x7F0 = 0x1804. From 0x1000 the delta is 0x804 (bit 11 set),
        // from the second auipc at 0x1008 it is 0x7FC (bit 11 clear).
        assertEquals(0x00001517, w[0]);              // auipc a0, 1
        assertEquals(0x80450513, w[1]);              // addi a0, a0, -2044
        assertEquals(0x00000597, w[2]);              // auipc a1, 0
        assertEquals(0x7FC5A603, w[3]);              // lw a2, 0x7FC(a1)
        assertEquals(0x7EC5AE23, w[4]);              // sw a2, 0x7FC(a1)
        oneError("addi a0, a0, %pcrel_lo(x)\nx: nop", 1, "%pcrel_lo");
    }

    @Test
    void laAndCallReachOneMegabyte() {
        // far = 0x1000 + 8 + 0x1007F8 = 0x101800: delta 0x100800 has bit 11 set.
        String pad = "\n.space 0x1007F8\nfar: ret\n";
        assertArrayEquals(new int[] {0x00101097, 0x800080E7}, first2("call far" + pad));
        assertArrayEquals(new int[] {0x00101517, 0x80050513}, first2("la a0, far" + pad));
        assertArrayEquals(new int[] {0x00101517, 0x80050513}, first2("lla a0, far" + pad));
        assertArrayEquals(new int[] {0x00101317, 0x80030067}, first2("tail far" + pad));
        assertArrayEquals(new int[] {0x00101297, 0x800282E7}, first2("call t0, far" + pad));
        assertArrayEquals(new int[] {0x00101517, 0x80052503}, first2("lw a0, far" + pad));
        assertArrayEquals(new int[] {0x00101517, 0x80054503}, first2("lbu a0, far" + pad));
        assertArrayEquals(new int[] {0x00101297, 0x80A2A023}, first2("sw a0, far, t0" + pad));
        // Bit 11 clear: far = 0x1000 + 8 + 0x100000 -> delta 0x100008.
        assertArrayEquals(new int[] {0x00100097, 0x008080E7}, first2("call far\n.space 0x100000\nfar: ret"));
        // Backwards.
        assertWords("sym: la a0, sym", 0x00000517, 0x00050513);
        assertWords("sym: nop\nla a0, sym", 0x00000013, 0x00000517, 0xFFC50513);
    }

    private static int[] first2(String src) {
        int[] w = toWords(ok(src).segments().get(0).bytes());
        return new int[] {w[0], w[1]};
    }

    @Test
    void numericLocalLabels() {
        assertWords("""
                1:  addi a0, a0, -1
                    bnez a0, 1b
                    j 1f
                    nop
                1:  j 1b
                """,
                0xFFF50513,
                0xFE051EE3,   // bne a0, x0, -4
                0x0080006F,   // jal x0, +8
                0x00000013,
                0x0000006F);  // 1b here is the label on this same line: jal x0, 0
    }
}
