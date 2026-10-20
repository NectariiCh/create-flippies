package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Backend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsmLexerTest {

    private final List<String> errors = new ArrayList<>();

    private List<AsmToken> lex(String src) {
        return new AsmLexer(Rv32Backend.INSTANCE.syntax(), src,
                (line, column, message) -> errors.add(line + ":" + column + " " + message)).tokenize();
    }

    private long intValue(String src) {
        List<AsmToken> t = lex(src);
        assertEquals(Kind.INT, t.get(0).kind(), src);
        return t.get(0).value();
    }

    @Test
    void integers() {
        assertEquals(10, intValue("10"));
        assertEquals(0x1F, intValue("0x1F"));
        assertEquals(0x1F, intValue("0X1f"));
        assertEquals(5, intValue("0b101"));
        assertEquals(15, intValue("0o17"));
        assertEquals(8, intValue("010"));      // GNU leading-zero octal
        assertEquals(0, intValue("0"));
        assertEquals(-1L, intValue("0xFFFFFFFFFFFFFFFF"));
        assertTrue(errors.isEmpty(), errors::toString);
        lex("08");
        lex("0x");
        lex("0x1FFFFFFFFFFFFFFFF");
        lex("12ab");
        assertEquals(4, errors.size(), errors::toString);
    }

    @Test
    void characterLiterals() {
        assertEquals('a', intValue("'a'"));
        assertEquals('\n', intValue("'\\n'"));
        assertEquals('\t', intValue("'\\t'"));
        assertEquals('\\', intValue("'\\\\'"));
        assertEquals('\'', intValue("'\\''"));
        assertEquals(0, intValue("'\\0'"));
        assertEquals(0x41, intValue("'\\x41'"));
        assertTrue(errors.isEmpty(), errors::toString);
    }

    @Test
    void strings() {
        List<AsmToken> t = lex("\"a\\\"b\\101\\x42\\n\\t\\\\\"");
        assertEquals(Kind.STRING, t.get(0).kind());
        assertEquals("a\"bAB\n\t\\", t.get(0).text());
        assertEquals("Ã©", lex("\"é\"").get(0).text());   // UTF-8 bytes
        assertTrue(errors.isEmpty(), errors::toString);
        lex("\"open");
        assertEquals(1, errors.size());
    }

    @Test
    void commentsSeparatorsAndPositions() {
        List<AsmToken> t = lex("a # x\nb // y\n/* multi\nline */ c ; d\n  e");
        List<String> texts = new ArrayList<>();
        for (AsmToken k : t)
            texts.add(k.kind() == Kind.NEWLINE ? "NL" : k.text());
        assertEquals(List.of("a", "NL", "b", "NL", "c", "NL", "d", "NL", "e", "NL"), texts);
        AsmToken c = t.get(4), e = t.get(8);
        assertEquals(4, c.line());
        assertEquals(9, c.column());
        assertEquals(5, e.line());
        assertEquals(3, e.column());
        assertTrue(errors.isEmpty());
    }

    @Test
    void localReferencesAndOperators() {
        List<AsmToken> t = lex("1b 22f 0b1 << >= == != && || %hi");
        assertEquals(Kind.LOCAL_REF, t.get(0).kind());
        assertEquals(1, t.get(0).value());
        assertTrue(!t.get(0).forward());
        assertEquals(Kind.LOCAL_REF, t.get(1).kind());
        assertEquals(22, t.get(1).value());
        assertTrue(t.get(1).forward());
        assertEquals(Kind.INT, t.get(2).kind());
        assertEquals(1, t.get(2).value());
        for (int i = 3; i <= 8; i++)
            assertEquals(Kind.PUNCT, t.get(i).kind());
        assertEquals("<<", t.get(3).text());
        assertEquals("%", t.get(9).text());
        assertEquals("hi", t.get(10).text());
    }

    @Test
    void badCharactersAreReportedAndSkipped() {
        List<AsmToken> t = lex("a ` b \\ c");
        assertEquals(2, errors.size());
        assertEquals(4, t.size());   // a b c NEWLINE
    }
}
