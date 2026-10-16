package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.Set;

/**
 * A preprocessing token. Tokens keep the position they were written at, even when
 * they come out of a macro expansion (they then carry the position of the macro's
 * use), so every diagnostic can point into the sketch the player wrote.
 */
final class Token {

    enum Kind { IDENT, NUMBER, CHAR, STRING, PUNCT, EOF }

    /** Which source a token came from. Only {@link #SKETCH} positions are shown to the player. */
    static final int INTERNAL = 0;
    static final int PRELUDE = 1;
    static final int SKETCH = 2;

    final Kind kind;
    final String text;
    final int file;
    final int line;
    final int col;
    /** First token on its source line: where a {@code #} starts a directive. */
    boolean bol;
    /** Whitespace before it, for stringizing. */
    boolean space;
    /** Macros that may not expand again inside this token's expansion (Prosser's hide set). */
    Set<String> hide;
    /** Produced by a macro expansion, so never a directive. */
    boolean fromMacro;

    Token(Kind kind, String text, int file, int line, int col) {
        this.kind = kind;
        this.text = text;
        this.file = file;
        this.line = line;
        this.col = col;
    }

    Token copy() {
        Token t = new Token(kind, text, file, line, col);
        t.space = space;
        t.hide = hide;
        t.fromMacro = fromMacro;
        return t;
    }

    /** A copy positioned at {@code at}, for tokens a macro expansion produces. */
    Token at(Token at) {
        Token t = new Token(kind, text, at.file, at.line, at.col);
        t.space = space;
        t.hide = hide;
        t.fromMacro = true;
        return t;
    }

    boolean is(String s) {
        return (kind == Kind.PUNCT || kind == Kind.IDENT) && text.equals(s);
    }

    @Override
    public String toString() {
        return text;
    }
}
