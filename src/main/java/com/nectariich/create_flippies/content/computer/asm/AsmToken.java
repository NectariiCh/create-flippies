package com.nectariich.create_flippies.content.computer.asm;

/**
 * One token of assembly source. {@code text} is the source spelling, except for strings, whose
 * text is the decoded bytes (one char per byte, 0..255). {@code value} holds an integer's or
 * character literal's value, and a numeric local reference's label number.
 */
public record AsmToken(Kind kind, String text, long value, int line, int column) {

    public enum Kind {
        /** Symbol, mnemonic, directive or register name. */
        IDENT,
        /** Integer or character literal. */
        INT,
        /** A numeric local label reference such as {@code 1b} or {@code 2f} (value = number). */
        LOCAL_REF,
        /**
         * A decimal floating-point literal such as {@code 1.5} or {@code 1.0e-3}, only with
         * {@link LexerSyntax#floatLiterals()}; {@code text} is the exact spelling and {@code value}
         * is 0. Not part of expressions: the backend converts the text itself.
         */
        FLOAT,
        STRING,
        /** Operators and punctuation: one or two characters. */
        PUNCT,
        /** End of a statement: a line break or the statement separator. */
        NEWLINE
    }

    public boolean is(Kind k, String s) {
        return kind == k && text.equals(s);
    }

    public boolean isPunct(String s) {
        return kind == Kind.PUNCT && text.equals(s);
    }

    /** For a LOCAL_REF: whether it points forward ({@code f}). */
    public boolean forward() {
        return text.endsWith("f");
    }

    /** How the token reads in a message. */
    public String display() {
        return switch (kind) {
            case STRING -> '"' + text + '"';
            case NEWLINE -> "end of line";
            default -> text;
        };
    }
}
