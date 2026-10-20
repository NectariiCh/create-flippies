package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Backend;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classic 68k syntax flags of {@link LexerSyntax} and the framework hooks the 68k backend
 * needs ({@code equ}, data statements in .bss, 2-byte {@code .word}), with a toy backend; and
 * that RV32I's syntax is unchanged by them.
 */
class ClassicSyntaxTest {

    private static final LexerSyntax CLASSIC = new LexerSyntax(List.of(";"), false, (char) 0, (char) 0, true,
            true, true, true, true, true, true);

    private final List<String> errors = new ArrayList<>();

    private List<AsmToken> lex(LexerSyntax syntax, String src) {
        List<AsmToken> t = new AsmLexer(syntax, src,
                (line, column, message) -> errors.add(line + ":" + column + " " + message)).tokenize();
        return t.subList(0, t.size() - 1);   // drop the final NEWLINE
    }

    private static String shape(List<AsmToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (AsmToken t : tokens) {
            if (!sb.isEmpty())
                sb.append(' ');
            sb.append(t.kind()).append(':').append(t.kind() == Kind.INT ? Long.toString(t.value()) : t.text());
        }
        return sb.toString();
    }

    private String classic(String src) {
        return shape(lex(CLASSIC, src));
    }

    @Test
    void dollarHexAndPercentBinary() {
        assertEquals("IDENT:dc.l INT:31 PUNCT:, INT:4095", classic("dc.l $1F,$fff"));
        assertEquals("IDENT:move.b PUNCT:# INT:10 PUNCT:, IDENT:d0", classic("move.b #%1010,d0"));
        assertEquals("IDENT:dc.w INT:5", classic("dc.w %101"));
        assertEquals("IDENT:dc.w IDENT:x PUNCT:% INT:10", classic("dc.w x%10"));
        assertEquals("IDENT:dc.w PUNCT:( INT:7 PUNCT:) PUNCT:% INT:2", classic("dc.w (7)%2"));
        assertEquals("IDENT:dc.w INT:4096 IDENT:.w", classic("dc.w $1000.w"));
        assertEquals("IDENT:foo$ IDENT:$", classic("foo$ $"));
        assertTrue(errors.isEmpty(), errors::toString);
        classic("dc.l $1G");
        assertEquals(1, errors.size(), errors::toString);
    }

    @Test
    void floatLiteralsAndSizeSuffixes() {
        assertEquals("IDENT:fmove.s PUNCT:# FLOAT:1.5", classic("fmove.s #1.5"));
        assertEquals("FLOAT:1.0e-3 FLOAT:2E10 PUNCT:- FLOAT:2.5E+10", classic("1.0e-3 2E10 -2.5E+10"));
        assertEquals("INT:1000 IDENT:.w", classic("1000.w"));
        assertEquals("INT:16 IDENT:.l", classic("0x10.l"));
        assertEquals("LOCAL_REF:1b LOCAL_REF:2f", classic("1b 2f"));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void starIsACommentInColumnOneAndTheLocationCounterInOperandPosition() {
        assertEquals("", classic("* a comment line"));
        assertEquals("IDENT:bra IDENT:. PUNCT:+ INT:4", classic(" bra *+4"));
        assertEquals("IDENT:dc.l IDENT:x PUNCT:* INT:2", classic(" dc.l x*2"));
        assertEquals("IDENT:dc.l PUNCT:( IDENT:. PUNCT:) PUNCT:* INT:2", classic(" dc.l (*)*2"));
        assertEquals("IDENT:x IDENT:equ IDENT:.", classic("x equ *"));
        assertEquals("IDENT:loop PUNCT:: IDENT:bra IDENT:.", classic("loop: bra *"));
        assertEquals("IDENT:nop", classic(" nop ; comment"));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void rv32iSyntaxIsUnchanged() {
        LexerSyntax rv = Rv32Backend.INSTANCE.syntax();
        assertFalse(rv.dollarHex() || rv.percentBinary() || rv.floatLiterals() || rv.equKeyword()
                || rv.starColumnOneComment() || rv.starLocationCounter());
        assertEquals("IDENT:$1F", shape(lex(rv, "$1F")));
        assertEquals("PUNCT:% IDENT:hi PUNCT:( IDENT:x PUNCT:)", shape(lex(rv, "%hi(x)")));
        assertEquals("PUNCT:* INT:2", shape(lex(rv, "*2")));
        assertTrue(errors.isEmpty(), errors::toString);
        lex(rv, "1.5");
        assertEquals(1, errors.size(), errors::toString);
    }

    /** Big-endian toy: {@code nop} is 2 bytes, {@code ds.b n} reserves n zero bytes. */
    private static final class ToyBackend implements IsaBackend {
        @Override public LexerSyntax syntax() { return CLASSIC; }
        @Override public ByteOrder byteOrder() { return ByteOrder.BIG_ENDIAN; }
        @Override public int instructionAlignment() { return 2; }
        @Override public byte[] nopPadding() { return new byte[] {0x4E, 0x71}; }
        @Override public boolean alignIsPowerOfTwo() { return false; }
        @Override public int wordBytes() { return 2; }
        @Override public boolean isDataStatement(Statement st) { return st.mnemonic().equals("ds.b"); }

        @Override
        public int size(Statement st, AsmContext ctx) {
            return switch (st.mnemonic()) {
                case "nop" -> 2;
                case "ds.b" -> (int) ctx.evalNow(st.operand(0).expr(), "size");
                default -> throw new AsmError(st.column(), "unknown instruction '" + st.mnemonic() + "'");
            };
        }

        @Override
        public void encode(Statement st, AsmContext ctx, Emitter out) {
            if (st.mnemonic().equals("nop"))
                out.u16(0x4E71);
            else
                out.fill(st.size(), 0);
        }

        @Override
        public OptionalLong relocationOperator(String name, Expr arg, AsmContext ctx) {
            return OptionalLong.empty();
        }
    }

    private static AsmResult toy(String src) {
        return new Assembler(new ToyBackend(), AsmOptions.at(0x1000)).assemble(src);
    }

    @Test
    void equAndSetKeywords() {
        AsmResult r = toy("""
                five equ 5
                start: nop
                here EQU *
                n set 1
                n SET n+%10
                """);
        assertTrue(r.ok(), r::toString);
        assertEquals(5L, r.image().symbol("five").getAsLong());
        assertEquals(0x1002L, r.image().symbol("here").getAsLong());
        assertEquals(3L, r.image().symbol("n").getAsLong());
        AsmResult twice = toy("x equ 1\nx equ 2\n");
        assertFalse(twice.ok());
        assertTrue(twice.errors().get(0).message().contains("already defined"), twice::toString);
    }

    @Test
    void wordIsTheBackendsWidthAndDataStatementsAreAllowedInBss() {
        AsmResult r = toy("""
                .word $1234, -1
                .long 1
                .bss
                buf: ds.b 6
                end:
                """);
        assertTrue(r.ok(), r::toString);
        assertArrayEquals(new byte[] {0x12, 0x34, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 1},
                r.image().flatten(0x1000, 8));
        assertEquals(6L, r.image().symbol("end").getAsLong() - r.image().symbol("buf").getAsLong());
        AsmResult code = toy(".bss\nnop\n");
        assertFalse(code.ok());
        assertTrue(code.errors().get(0).message().contains("not allowed in .bss"), code::toString);
    }
}
