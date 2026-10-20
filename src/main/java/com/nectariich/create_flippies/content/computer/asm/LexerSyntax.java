package com.nectariich.create_flippies.content.computer.asm;

import java.util.List;

/**
 * The per-backend parts of the assembly syntax. The flags after {@code hashImmediate} are the
 * classic 68k syntax (spec 10.7.1); every one of them is off for RV32I, whose syntax they leave
 * untouched.
 *
 * "Operand position" below means: at the start of a statement, after punctuation other than
 * {@code )} and {@code ]}, after the statement's head (its first identifier after any labels),
 * or after an {@code equ} or {@code set} keyword when {@code equKeyword} is on. Anywhere else a
 * value has just ended, so the character is an operator.
 *
 * @param lineComments         strings that start a comment running to the end of the line
 * @param blockComments        whether C-style comments are recognised
 * @param statementSeparator   a character that ends a statement like a line break, or 0
 * @param registerSigil        a character that prefixes register names (MIPS {@code $}), or 0;
 *                             the lexer folds it into the following identifier
 * @param hashImmediate        whether {@code #} marks an immediate (68000, ARM2); then it is
 *                             punctuation and must not be a comment starter
 * @param dollarHex            {@code $1F} is the integer 0x1F: a {@code $} followed by a hex
 *                             digit starts a number; otherwise {@code $} stays an identifier
 *                             character
 * @param percentBinary        {@code %1010} in operand position is the integer 10; after a value
 *                             {@code %} stays the modulo operator
 * @param floatLiterals        {@code 1.5}, {@code 1.0e-3} and {@code 2E10} become
 *                             {@link AsmToken.Kind#FLOAT} tokens holding the exact text; a
 *                             number then ends at a {@code .} not followed by a digit, so
 *                             {@code 1000.w} is the integer 1000 then the identifier {@code .w}
 * @param equKeyword           {@code name equ expr} parses as {@code .equ name, expr} and
 *                             {@code name set expr} as {@code .set name, expr} (keywords in any
 *                             case)
 * @param starColumnOneComment a {@code *} in column 1 starts a comment line
 * @param starLocationCounter  {@code *} in operand position is the location counter, the same as
 *                             {@code .} ({@code bra *}); after a value it stays multiplication
 */
public record LexerSyntax(List<String> lineComments, boolean blockComments, char statementSeparator,
                          char registerSigil, boolean hashImmediate, boolean dollarHex,
                          boolean percentBinary, boolean floatLiterals, boolean equKeyword,
                          boolean starColumnOneComment, boolean starLocationCounter) {

    public LexerSyntax {
        lineComments = List.copyOf(lineComments);
    }

    /** A syntax without any of the classic 68k flags (RV32I and the GNU-style backends). */
    public LexerSyntax(List<String> lineComments, boolean blockComments, char statementSeparator,
                       char registerSigil, boolean hashImmediate) {
        this(lineComments, blockComments, statementSeparator, registerSigil, hashImmediate,
                false, false, false, false, false, false);
    }
}
