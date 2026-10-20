package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmDiagnostic;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.asm;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.assertNoInternal;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.errors;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.oneError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error messages name the problem, with line and column; garbage never throws. */
class M68kDiagnosticsTest {

    private static final M68kTarget T0 = M68kTarget.M68000;
    private static final M68kTarget T4 = M68kTarget.M68040;

    @Test
    void wrongAddressingModes() {
        AsmDiagnostic d = oneError(T0, "\n\n lea d0,a0", 3, "'lea' does not take a data register");
        assertEquals(6, d.column());
        oneError(T0, " move.l d0,(pc)", 1, "'move' does not take a PC-relative address");
        oneError(T0, " move.w d0,#1", 1, "'move' does not take an immediate");
        oneError(T0, " jmp (a0)+", 1, "'jmp' does not take (An)+");
        oneError(T0, " clr.w a0", 1, "'clr' does not take an address register");
        oneError(T0, " move.b a0,d0", 1, "'move.b' does not take an address register");
        oneError(T0, " move.l d0,-(d1)", 1, "-(...) takes an address register, not d1");
        oneError(T0, " movem.l d0,d1", 1, "'movem' needs a register list and a memory operand");
        oneError(T0, " movem.l (a0)+,(a1)", 1, "'movem' needs a register list");
        oneError(T0, " movem.l -(a0),d0", 1, "'movem' does not take -(An)");
        oneError(T0, " move.w d0,sr,d1", 1, "'move' expects 2 operands, got 3");
        oneError(T0, " lea (a0),d0", 1, "'lea' expects an address register here");
    }

    @Test
    void sizes() {
        AsmDiagnostic d = oneError(T0, " lea.b (a0),a0", 1, "'lea.b': size .b is not allowed (lea takes .l)");
        assertEquals(2, d.column());
        oneError(T0, " nop.w", 1, "'nop.w': nop takes no size");
        oneError(T0, " moveq.w #1,d0", 1, "size .w is not allowed");
        oneError(T0, " move.q d0,d1", 1, "unknown size suffix '.q'");
        oneError(T0, " btst.b d0,d1", 1, "on a data register is .l");
        oneError(T0, " ext.b d0", 1, "'ext.b'");
        oneError(T4, " fmove.l fp0,fp1", 1, "FP register to register takes .x");
        oneError(T4, " fmove.d d0,fp1", 1, "needs .b, .w, .l or .s");
    }

    @Test
    void ranges() {
        oneError(T0, " addq #9,d0", 1, "quick immediate 9 out of range (1..8)");
        oneError(T0, " subq #0,d0", 1, "quick immediate 0 out of range (1..8)");
        oneError(T0, " moveq #200,d0", 1, "'moveq' immediate 200 out of range (-128..127)");
        oneError(T0, " trap #16", 1, "trap vector 16 out of range (0..15)");
        oneError(T0, " lsl.w #9,d0", 1, "shift count 9 out of range (1..8)");
        oneError(T0, " move.b #256,d0", 1, "immediate 256 out of range (-128..255)");
        oneError(T0, " move.w d0,$8000.w", 1, "does not fit .w");
        oneError(T0, " move.w 40000(a0),d0", 1, "displacement 40000 does not fit in 16 bits");
        oneError(T0, " btst #8,(a0)", 1, "bit number 8 out of range (0..7)");
        oneError(T4, " bfextu d0{32:1},d1", 1, "bit field offset 32 out of range (0..31)");
        oneError(T0, " link a6,#40000", 1, "'link' displacement 40000 out of range");
        oneError(T0, "l: dbra d0,l+1", 1, "odd address");
    }

    @Test
    void namesAndSymbols() {
        oneError(T0, " ad d0,d1", 1, "unknown instruction 'ad'");
        oneError(T0, " move.l foo,d0", 1, "undefined symbol 'foo'");
        oneError(T0, "x: nop\nx: nop", 2, "symbol 'x' already defined on line 1");
        oneError(T4, " movec d0,foo", 1, "'movec' needs a control register");
        oneError(T0, " move.l vbr,d0", 1, "'move' does not take register vbr");
        oneError(T4, " cinva xx", 1, "expects a cache name");
    }

    @Test
    void severalErrorsAreReportedInLineOrder() {
        List<AsmDiagnostic> e = errors(T0, " nop\n lea d0,a0\n nop\n addq #9,d0\n foo\n");
        assertEquals(3, e.size(), e::toString);
        assertEquals(2, e.get(0).line());
        assertEquals(4, e.get(1).line());
        assertEquals(5, e.get(2).line());
    }

    @Test
    void tokenSoupNeverThrows() {
        String[] vocab = {"move.l", "move", "lea", "bra", "dbra", "fmove.x", "movem.l", "bfextu", "cas2.l", "dc.b",
                "ds.w", "even", "align", "org", "equ", "d0", "a7", "sp", "pc", "zpc", "za0", "fp0", "fpcr", "sr", "ccr",
                "usp", "vbr", "(", ")", "[", "]", "{", "}", "-", "+", "*", "/", ",", ":", "#", "$FF", "%101", "1.5",
                "1e400", "\"s\"", "'c'", ".w", ".l", "d0.l*4", "lab", "lab:", "1:", "1b", "1f", ";", "\n", ".cpu",
                "68040", "68000", "x", "=", "<<", "fsincos", "fp1:fp2", "{d1:8}", "([", "],", "100000", "-1"};
        Random rnd = new Random(68040);
        for (int i = 0; i < 1500; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + rnd.nextInt(30);
            for (int k = 0; k < n; k++) {
                sb.append(vocab[rnd.nextInt(vocab.length)]);
                sb.append(rnd.nextInt(3) == 0 ? "" : " ");
                if (rnd.nextInt(6) == 0)
                    sb.append('\n');
            }
            String src = sb.toString();
            for (M68kTarget t : M68kTarget.values()) {
                AsmResult r = asm(t, src);
                assertNoInternal(r);
                assertTrue(r.ok() || !r.errors().isEmpty());
            }
        }
    }
}
