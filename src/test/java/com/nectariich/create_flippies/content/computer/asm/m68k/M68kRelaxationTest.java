package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check000;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.oneError;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.words;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Branch size selection (spec 10.7.4): the smallest form that reaches, grown by relaxation; a
 * short branch never has displacement 0; .l only on the 68040.
 */
class M68kRelaxationTest {

    private static int[] head(M68kTarget t, String src, int n) {
        return Arrays.copyOf(words(t, src), n);
    }

    @Test
    void forwardBranchSizes() {
        assertArrayEquals(new int[] {0x607E}, head(M68kTarget.M68000, " bra t\n ds.b 126\nt: nop", 1));
        assertEquals(1 + 63 + 1, words(M68kTarget.M68000, " bra t\n ds.b 126\nt: nop").length);
        assertArrayEquals(new int[] {0x6000, 0x0082}, head(M68kTarget.M68000, " bra t\n ds.b 128\nt: nop", 2));
        assertArrayEquals(new int[] {0x6000, 0x7FFE}, head(M68kTarget.M68000, " bra t\n ds.b 32764\nt: nop", 2));
        assertArrayEquals(new int[] {0x60FF, 0x0000, 0x8002}, head(M68kTarget.M68040, " bra t\n ds.b 32766\nt: nop", 3));
        oneError(M68kTarget.M68000, " bra t\n ds.b 32766\nt: nop", 1,
                "branch target out of range (32768 bytes, limit -32768..+32767 for .w; .l needs a 68040)");
    }

    @Test
    void backwardBranchSizes() {
        assertArrayEquals(new int[] {0x6080}, Arrays.copyOfRange(words(M68kTarget.M68000, "t: ds.b 126\n bra t"), 63, 64));
        assertArrayEquals(new int[] {0x6000, 0xFF7E},
                Arrays.copyOfRange(words(M68kTarget.M68000, "t: ds.b 128\n bra t"), 64, 66));
        assertArrayEquals(new int[] {0x6000, 0x8000},
                Arrays.copyOfRange(words(M68kTarget.M68000, "t: ds.b 32766\n bra t"), 16383, 16385));
        assertArrayEquals(new int[] {0x60FF, 0xFFFF, 0x7FFE},
                Arrays.copyOfRange(words(M68kTarget.M68040, "t: ds.b 32768\n bra t"), 16384, 16387));
        oneError(M68kTarget.M68000, "t: ds.b 32768\n bra t", 2, "branch target out of range (-32770 bytes");
    }

    @Test
    void branchToTheNextInstructionWidens() {
        check000(" bra n\nn: nop", 0x6000, 0x0002, 0x4E71);
        check000(" beq n\nn:", 0x6700, 0x0002);
        oneError(M68kTarget.M68000, " bra.s n\nn: nop", 1, "'bra.s' cannot branch to the next instruction");
        check000(" bra.w n\nn: nop", 0x6000, 0x0002, 0x4E71);
    }

    @Test
    void explicitSizesAreFixed() {
        check000(" bra.w t\nt: nop", 0x6000, 0x0002, 0x4E71);
        check(" bra.l t\n nop\nt: nop", 0x60FF, 0x0000, 0x0006, 0x4E71, 0x4E71);
        oneError(M68kTarget.M68000, " bra.s t\n ds.b 300\nt: nop", 1,
                "branch target out of range (300 bytes, limit -128..+127 for .s)");
        oneError(M68kTarget.M68000, " bra.l t\nt: nop", 1, "'bra.l' needs a 68040 (assembling for the 68000)");
    }

    @Test
    void chainOfForwardBranchesGrowingEachOtherConverges() {
        String src = " bra t1\n bra t2\n ds.b 124\nt1: ds.b 200\nt2: nop";
        int[] w = words(M68kTarget.M68000, src);
        assertArrayEquals(new int[] {0x6000, 0x0082, 0x6000, 0x0146}, Arrays.copyOf(w, 4));
        AsmImage img = ok(M68kTarget.M68000, src);
        assertEquals(0x1000 + 132L, img.symbol("t1").getAsLong());
        assertEquals(0x1000 + 332L, img.symbol("t2").getAsLong());
    }

    @Test
    void growingOneBranchMovesTheTargetForTheOthers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++)
            sb.append(" beq end\n");
        sb.append(" ds.b 50\nend: rts\n");
        int[] w = words(M68kTarget.M68000, sb.toString());
        // only the first is out of .s range (128 bytes); once it is .w the second is at 126
        assertEquals(0x6700, w[0]);
        assertEquals(130, w[1]);
        assertEquals(0x677E, w[2]);
        assertEquals(0x6732, w[40]);
        assertEquals(2 + 39 + 25 + 1, w.length);
    }

    @Test
    void absoluteAddressRelaxesWithForwardEquates() {
        // a forward equate is unknown in the first pass: abs.L, kept even though it fits .w
        check000(" move.w x,d0\nx equ $10", 0x3039, 0x0000, 0x0010);
        check000(" move.w x.w,d0\nx equ $10", 0x3038, 0x0010);
    }

    @Test
    void dbccAndFbccSizes() {
        check000("l: dbra d0,l", 0x51C8, 0xFFFE);
        check(" fbeq t\n ds.b 40000\nt: nop", concat(new int[] {0xF2C1, 0x0000, 0x9C44}, 20000));
    }

    private static int[] concat(int[] head, int zeros) {
        int[] r = Arrays.copyOf(head, head.length + zeros + 1);
        r[r.length - 1] = 0x4E71;
        return r;
    }
}
