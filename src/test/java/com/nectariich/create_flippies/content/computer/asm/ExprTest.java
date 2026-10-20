package com.nectariich.create_flippies.content.computer.asm;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.BASE;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.errors;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.AsmTesting.oneError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expression evaluation: 64-bit, C precedence (spec 5.1). */
class ExprTest {

    /** The 64-bit value of an expression, read back from a {@code .dword}. */
    private static long eval(String expr) {
        byte[] b = ok(".dword " + expr).flatten(BASE, 8);
        long v = 0;
        for (int i = 7; i >= 0; i--)
            v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    @Test
    void precedenceIsC() {
        assertEquals(24, eval("1+2<<3"));         // GNU as would give 17
        assertEquals(10, eval("2*3+4"));
        assertEquals(14, eval("2+3*4"));
        assertEquals(20, eval("(2+3)*4"));
        assertEquals(1, eval("1 < 2 == 1"));
        assertEquals(0, eval("(1|2)^3"));
        assertEquals(3, eval("1 | 2 & 3"));       // & binds tighter than |
        assertEquals(1, eval("0 || 2 && 3"));
        assertEquals(6, eval("1 + 2 + 3"));
        assertEquals(-4, eval("2 - 3 - 3"));      // left associative
        assertEquals(2, eval("16 / 4 / 2"));
    }

    @Test
    void operators() {
        assertEquals(-1, eval("-1"));
        assertEquals(-1, eval("~0"));
        assertEquals(0, eval("!5"));
        assertEquals(1, eval("!0"));
        assertEquals(5, eval("+5"));
        assertEquals(3, eval("7/2"));
        assertEquals(-3, eval("-7/2"));
        assertEquals(-1, eval("-7%2"));
        assertEquals(Long.MIN_VALUE, eval("1<<63"));
        assertEquals(-4, eval("-16>>2"));          // arithmetic
        assertEquals(1, eval("3 >= 3"));
        assertEquals(0, eval("3 > 3"));
        assertEquals(1, eval("2 <= 3"));
        assertEquals(1, eval("2 != 3"));
        assertEquals(0x0F, eval("0xFF & 0x0F"));
        assertEquals(0xF0, eval("0xFF ^ 0x0F"));
        assertEquals(0x1FFFFFFFEL, eval("0xFFFFFFFF * 2"));   // 64-bit, no wrap at 32
        assertEquals('A' + 1, eval("'A' + 1"));
    }

    @Test
    void symbolsAndDot() {
        AsmImage img = ok("""
                .equ N, 10
                .set M, N * 2
                K = M + 1
                start: .word N, M, K
                here:  .word here - start, . - start
                """);
        byte[] b = img.flatten(BASE, 20);
        assertEquals(10, AsmTesting.u32(b, 0));
        assertEquals(20, AsmTesting.u32(b, 4));
        assertEquals(21, AsmTesting.u32(b, 8));
        assertEquals(12, AsmTesting.u32(b, 12));
        assertEquals(12, AsmTesting.u32(b, 16));
        assertEquals(21, img.symbols().get("K"));
    }

    @Test
    void errorsInExpressions() {
        oneError(".word 1/0", 1, "division by zero");
        oneError(".word 1%0", 1, "division by zero");
        oneError(".word 1<<64", 1, "shift count 64 out of range");
        oneError(".word (1", 1, "missing ')'");
        oneError(".word 1 +", 1, "expected an expression");
        oneError(".word 1 2", 1, "unexpected '2'");
        oneError(".word %foo(1)", 1, "unknown relocation operator '%foo'");
    }

    @Test
    void nestingIsCapped() {
        assertEquals(1, eval("(".repeat(200) + "1" + ")".repeat(200)));
        oneError(".word " + "(".repeat(300) + "1" + ")".repeat(300), 1, "nested more than 256");
        oneError(".word " + "-".repeat(300) + "1", 1, "nested more than 256");
        // 100 000 nested parentheses: an error, not a StackOverflowError.
        assertTrue(errors(".word " + "(".repeat(100_000) + "1" + ")".repeat(100_000)).get(0)
                .message().contains("nested more than"));
        // Very long flat chains are capped too (evaluation recursion follows the tree depth).
        assertEquals(1000, eval("1" + "+1".repeat(999)));
        oneError(".word 1" + "+1".repeat(100_000), 1, "expression too long");
    }
}
