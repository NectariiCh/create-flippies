package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.AsmSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check000;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole programs, assembled by hand word for word. */
class M68kProgramTest {

    private static final String HELLO = """
            * print a string to a byte-wide port
            COM1    equ     $FFF00000
            _start: lea     msg(pc),a0          ; 41FA 0014
                    move.l  #COM1,a1            ; 227C FFF0 0000
            loop:   move.b  (a0)+,d0            ; 1018
                    beq.s   done                ; 6704
                    move.b  d0,(a1)             ; 1280
                    bra     loop                ; 60F8
            done:   moveq   #0,d0               ; 7000
                    rts                         ; 4E75
            msg:    dc.b    "Hi",0
                    even
            """;

    @Test
    void helloLoop() {
        check000(HELLO, 0x41FA, 0x0014, 0x227C, 0xFFF0, 0x0000, 0x1018, 0x6704, 0x1280, 0x60F8, 0x7000,
                0x4E75, 0x4869, 0x0000);
        AsmImage img = ok(M68kTarget.M68000, HELLO);
        assertEquals(0x1000, img.entry());
        assertEquals(0x1016L, img.symbol("msg").getAsLong());
        assertEquals(0x100AL, img.symbol("loop").getAsLong());
        assertEquals(0xFFF0_0000L, img.symbol("COM1").getAsLong());
    }

    private static final String SUBROUTINES = """
            start:  move.w  #9,d1               ; 323C 0009
                    lea     table,a0            ; 41F9 0000 1100
                    bsr     sum                 ; 6100 0108
                    move.l  d0,result           ; 23C0 0000 2000
                    illegal                     ; 4AFC
                    .org    $100
            table:  dc.w    1,2,3,4,5,6,7,8,9,10
            sum:    movem.l d1/a0,-(sp)         ; 48E7 4080
                    moveq   #0,d0               ; 7000
            1:      add.w   (a0)+,d0            ; D058
                    dbra    d1,1b               ; 51C9 FFFC
                    movem.l (sp)+,d1/a0         ; 4CDF 0102
                    rts                         ; 4E75
            result  equ     $2000
            """;

    @Test
    void subroutineWithLocalLabelsAndOrg() {
        byte[] t = text(M68kTarget.M68000, SUBROUTINES);
        // table is at $1100 (org $100); bsr is at $100A and sum at $1114, so the .w displacement is $108
        assertArrayEquals(new int[] {0x323C, 0x0009, 0x41F9, 0x0000, 0x1100, 0x6100, 0x0108, 0x23C0, 0x0000, 0x2000,
                0x4AFC}, words(t, 0, 11));
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, words(t, 0x80, 10));
        assertArrayEquals(new int[] {0x48E7, 0x4080, 0x7000, 0xD058, 0x51C9, 0xFFFC, 0x4CDF, 0x0102, 0x4E75},
                words(t, 0x8A, 9));
        AsmImage img = ok(M68kTarget.M68000, SUBROUTINES);
        assertEquals(0x1114L, img.symbol("sum").getAsLong());
    }

    /**
     * The classic org without a dot is a data statement, which may shrink between relaxation
     * passes: an org after a branch that grew lands the code where the dot form does.
     */
    @Test
    void orgAfterARelaxedBranchShrinks() {
        for (String org : new String[] {"org", ".org"}) {
            int[] w = M68kTesting.words(M68kTarget.M68000, " bra t\n " + org + " $100\nt: nop");
            assertEquals(0x6000, w[0]);
            assertEquals(0x00FE, w[1]);
            assertEquals(0x81, w.length);
            assertEquals(0x4E71, w[0x80]);
        }
        M68kTesting.check000(" nop\n org $4\n rts", 0x4E71, 0x0000, 0x4E75);
    }

    private static int[] words(byte[] b, int fromWord, int n) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++)
            w[i] = (b[2 * (fromWord + i)] & 0xFF) << 8 | (b[2 * (fromWord + i) + 1] & 0xFF);
        return w;
    }

    private static final String FPU = """
                    .cpu    68040
            area:   fmove.d radius(pc),fp0      ; F23A 5400 disp
                    fmul.x  fp0,fp0             ; F200 0023
                    fmul.d  #3.141592653589793,fp0
                    fmove.d fp0,result          ; F239 7400 addr.l
                    fbgt    .pos                ; F292 0006
                    fneg    fp0                 ; F200 001A
            .pos:   rts
                    even
            radius: dc.d    2.0
                    .data
            result: ds.d    1
            """;

    @Test
    void floatingPointRoutine() {
        AsmImage img = ok(M68kTarget.M68000, FPU);
        byte[] t = text(M68kTarget.M68000, FPU);
        int[] w = words(t, 0, t.length / 2);
        long radius = img.symbol("radius").getAsLong();
        long result = img.symbol("result").getAsLong();
        assertEquals(0xF23A, w[0]);
        assertEquals(0x5400, w[1]);
        assertEquals(radius - 0x1004, w[2]);
        assertArrayEquals(new int[] {0xF200, 0x0023}, new int[] {w[3], w[4]});
        assertArrayEquals(new int[] {0xF23C, 0x5423, 0x4009, 0x21FB, 0x5444, 0x2D18}, java.util.Arrays.copyOfRange(w, 5, 11));
        assertArrayEquals(new int[] {0xF239, 0x7400, (int) (result >>> 16), (int) (result & 0xFFFF)},
                java.util.Arrays.copyOfRange(w, 11, 15));
        assertArrayEquals(new int[] {0xF292, 0x0006, 0xF200, 0x001A, 0x4E75}, java.util.Arrays.copyOfRange(w, 15, 20));
        assertArrayEquals(new int[] {0x4000, 0, 0, 0}, java.util.Arrays.copyOfRange(w, 20, 24));
    }

    @Test
    void twoFilesWithAGlobal() {
        AsmResult r = M68kAssembler.assemble(List.of(
                new AsmSource("a.s", " .globl f\n bsr f\n rts\n"),
                new AsmSource("b.s", " .globl f\nf: moveq #1,d0\n rts\n")), AsmOptions.at(0x1000), M68kTarget.M68000);
        assertTrue(r.ok(), r::toString);
        byte[] b = r.image().flatten(0x1000, 8);
        assertArrayEquals(new int[] {0x6102, 0x4E75, 0x7001, 0x4E75}, words(b, 0, 4));
    }
}
