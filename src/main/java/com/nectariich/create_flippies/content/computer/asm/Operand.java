package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;

import java.util.List;

/**
 * One comma-separated operand of a statement, as raw tokens. Backends read registers and memory
 * forms from the tokens and parse expressions with {@link #expr()}, which caches the tree so the
 * second pass does not parse again.
 */
public final class Operand {

    private final List<AsmToken> tokens;
    private final int column;
    private Expr expr;

    public Operand(List<AsmToken> tokens, int column) {
        this.tokens = List.copyOf(tokens);
        this.column = column;
    }

    public List<AsmToken> tokens() {
        return tokens;
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /** Column of the first token (or of where the operand should have been). */
    public int column() {
        return column;
    }

    /** The single identifier this operand consists of, or null. */
    public String identifier() {
        return tokens.size() == 1 && tokens.get(0).kind() == Kind.IDENT ? tokens.get(0).text() : null;
    }

    /** The single string literal this operand consists of, or null. */
    public String string() {
        return tokens.size() == 1 && tokens.get(0).kind() == Kind.STRING ? tokens.get(0).text() : null;
    }

    /** The whole operand as one expression. */
    public Expr expr() {
        if (expr == null) {
            if (tokens.isEmpty())
                throw new AsmError(column, "missing operand");
            expr = ExprParser.parse(tokens, column);
        }
        return expr;
    }

    /** Tokens [from, to) as an expression (not cached). */
    public Expr expr(int from, int to) {
        return ExprParser.parse(tokens, from, to, from < tokens.size() ? tokens.get(from).column() : column);
    }

    /** The source text, re-spaced, for messages. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (AsmToken t : tokens) {
            if (!sb.isEmpty() && t.kind() != Kind.PUNCT && sb.charAt(sb.length() - 1) != '(')
                sb.append(' ');
            sb.append(t.display());
        }
        return sb.toString();
    }
}
