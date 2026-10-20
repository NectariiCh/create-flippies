package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmDiagnostic;
import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.asm;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check000;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.oneError;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data statements, alignment, equates, the lexer flags in 68k use, and the .cpu directive. */
class M68kDirectivesTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            r[i] = (byte) v[i];
        return r;
    }

    @Test
    void dcValuesAreBigEndianAndRangeChecked() {
        check000("dc.w 1,-1,$FFFF", 0x0001, 0xFFFF, 0xFFFF);
        check000("dc.l $12345678,-2", 0x1234, 0x5678, 0xFFFF, 0xFFFE);
        check000("dc 7", 0x0007);
        assertArrayEquals(b('A', 'B', 'C', 0), text(M68kTarget.M68000, "dc.b 'A',\"BC\",0"));
        oneError(M68kTarget.M68000, "dc.b 256", 1, "does not fit in 1 byte");
        oneError(M68kTarget.M68000, "dc.w 65536", 1, "does not fit in 2 bytes");
        oneError(M68kTarget.M68000, "dc.w \"ab\"", 1, "strings need dc.b");
        check000("lab: dc.l lab", 0x0000, 0x1000);
    }

    @Test
    void wordDataAndInstructionsAlignThemselves() {
        assertArrayEquals(b('h', 'i', 0, 0, 0, 1), text(M68kTarget.M68000, "dc.b \"hi\",0\ndc.w 1"));
        assertArrayEquals(b(1, 0, 0, 0, 0, 2), text(M68kTarget.M68000, "dc.b 1\ndc.l 2"));
        AsmResult r = asm(M68kTarget.M68000, "dc.b 1\nmove.w d0,d1");
        assertTrue(r.ok(), r::toString);
        List<AsmDiagnostic> w = r.diagnostics();
        assertEquals(1, w.size());
        assertEquals(Severity.WARNING, w.get(0).severity());
        assertEquals(2, w.get(0).line());
        assertTrue(w.get(0).message().contains("odd address"));
        assertArrayEquals(b(1, 0, 0x32, 0x00), text(M68kTarget.M68000, "dc.b 1\nmove.w d0,d1"));
        assertArrayEquals(b(1, 0, 2), text(M68kTarget.M68000, "dc.b 1\neven\ndc.b 2"));
        assertArrayEquals(b(1, 2), text(M68kTarget.M68000, "dc.b 1\ndc.b 2"));
    }

    @Test
    void reservationsAndBlocks() {
        check000("dcb.w 3,$FFFF", 0xFFFF, 0xFFFF, 0xFFFF);
        assertArrayEquals(b(9, 9, 9), text(M68kTarget.M68000, "dcb.b 3,9"));
        check000("dcb.l 1,-1", 0xFFFF, 0xFFFF);
        check000("ds.w 2\n nop", 0, 0, 0x4E71);
        check000("ds.l 1", 0, 0);
        assertArrayEquals(b(1, 0, 0, 0, 0, 0), text(M68kTarget.M68000, "dc.b 1\nds.w 2"));
        AsmImage img = ok(M68kTarget.M68000, " nop\n .bss\nbuf: ds.l 4\nbuf2: ds.b 3\n even\nbuf3: ds.w 1\n");
        assertEquals(16 + 3 + 1 + 2, img.bssEnd() - img.bssStart());
        assertEquals(img.bssStart() + 20L, img.symbol("buf3").getAsLong());
        oneError(M68kTarget.M68000, " .bss\n dc.w 1", 2, "not allowed in .bss");
        oneError(M68kTarget.M68000, "ds.b -1", 1, "negative count");
    }

    @Test
    void sharedDotDirectivesUseTheClassicWidths() {
        check000(".word 1\n.long 2\n.short 3", 0x0001, 0x0000, 0x0002, 0x0003);
        // in text, an even gap is padded with NOPs (the framework's rule)
        assertArrayEquals(b(1, 2, 0x4E, 0x71), text(M68kTarget.M68000, ".byte 1,2\n.balign 4"));
        check000("nop\n.align 4\nrts", 0x4E71, 0x4E71, 0x4E75);
        check000(".ascii \"AB\"", 0x4142);
    }

    @Test
    void alignAndOrg() {
        assertArrayEquals(b(1, 0, 0, 0, 2), text(M68kTarget.M68000, "dc.b 1\nalign 4\ndc.b 2"));
        check000("nop\nalign 8\nrts", 0x4E71, 0x4E71, 0x4E71, 0x4E71, 0x4E75);
        int[] w = M68kTesting.words(M68kTarget.M68000, "nop\norg $10\nrts");
        assertEquals(9, w.length);
        assertEquals(0x4E75, w[8]);
        oneError(M68kTarget.M68000, "ds.b 32\norg $10", 2, "backwards");
        check000("nop\nend\n", 0x4E71);
        oneError(M68kTarget.M68000, "align 3", 1, "not a power of two");
    }

    @Test
    void classicAlignShrinksWhenABranchBeforeItGrows() {
        // bra starts as .s (align pads 14), grows to .w over the 200-byte block (align pads 12)
        int[] w = M68kTesting.words(M68kTarget.M68000, " bra t\n align 16\nmid: dcb.b 200,1\nt: nop");
        assertEquals(0x6000, w[0]);
        assertEquals(0x00D6, w[1]);
        for (int i = 2; i < 8; i++)
            assertEquals(0x4E71, w[i]);
        assertEquals(0x0101, w[8]);
        assertEquals(0x4E71, w[108]);
        AsmImage img = ok(M68kTarget.M68000, " bra t\n align 16\nmid: dcb.b 200,1\nt: nop");
        assertEquals(M68kTesting.BASE + 16L, img.symbol("mid").getAsLong());
    }

    @Test
    void aLabelBeforeAPaddedStatementNamesTheAlignedAddress() {
        AsmImage img = ok(M68kTarget.M68000, "dc.b 1\ncode: nop\n dc.b 2\nword: dc.w 5\n dc.b 3\nbyte: dc.b 4\n"
                + " dc.b 5\nfirst:\nsecond: dc.l 6\n bra code");
        assertEquals(M68kTesting.BASE + 2L, img.symbol("code").getAsLong());
        assertEquals(M68kTesting.BASE + 6L, img.symbol("word").getAsLong());
        assertEquals(M68kTesting.BASE + 9L, img.symbol("byte").getAsLong());
        assertEquals(M68kTesting.BASE + 12L, img.symbol("first").getAsLong());
        assertEquals(M68kTesting.BASE + 12L, img.symbol("second").getAsLong());
        // the branch lands on the nop, not on the pad byte before it
        assertArrayEquals(b(1, 0, 0x4E, 0x71, 2, 0, 0, 5, 3, 4, 5, 0, 0, 0, 0, 6, 0x60, 0xF0),
                text(M68kTarget.M68000, "dc.b 1\ncode: nop\n dc.b 2\nword: dc.w 5\n dc.b 3\nbyte: dc.b 4\n"
                        + " dc.b 5\nfirst:\nsecond: dc.l 6\n bra code"));
        // a label before even keeps its odd address, as classic assemblers do
        assertEquals(M68kTesting.BASE + 1L, ok(M68kTarget.M68000, "dc.b 1\nodd: even\n nop").symbol("odd").getAsLong());
    }

    @Test
    void equatesAndLexerFlags() {
        check000("x equ 5\n moveq #x,d0", 0x7005);
        check000("X EQU $FF\n move.b #X,d0", 0x103C, 0x00FF);
        check000("y set 1\ny set 2\n moveq #y,d0", 0x7002);
        oneError(M68kTarget.M68000, "z equ 1\nz equ 2", 2, "already defined");
        check000("* a comment line\n nop ; trailing comment\n", 0x4E71);
        check000(" moveq #%1010,d0", 0x700A);
        check000(" moveq #7%4,d0", 0x7003);
        check000(" moveq #$1F,d0", 0x701F);
        check000(" moveq #0x10,d0", 0x7010);
        check000(" moveq #0b11,d0", 0x7003);
        check000(" bra *", 0x60FE);
        check000("a = 3\n moveq #a*2,d0", 0x7006);
        check000(" dc.w *-$1000", 0x0000);
    }

    @Test
    void cpuDirectiveSwitchesTheTarget() {
        check(M68kTarget.M68000, " .cpu 68040\n extb.l d0\n .cpu 68000\n ext.l d0", 0x49C0, 0x48C0);
        oneError(M68kTarget.M68040, " .cpu 68000\n extb.l d0", 2, "'extb' needs a 68040 (assembling for the 68000)");
        oneError(M68kTarget.M68040, " .cpu 68020", 1, "'.cpu' expects 68000 or 68040");
        // the switch holds for every pass, including relaxed branches after it
        check(M68kTarget.M68000, " .cpu 68040\n bra t\n ds.b 40000\nt: nop",
                concat(new int[] {0x60FF, 0x0000, 0x9C44}, 20000));
    }

    private static int[] concat(int[] head, int zeros) {
        int[] r = java.util.Arrays.copyOf(head, head.length + zeros + 1);
        r[r.length - 1] = 0x4E71;
        return r;
    }
}
