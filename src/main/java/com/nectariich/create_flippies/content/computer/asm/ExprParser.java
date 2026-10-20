package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;

import java.util.List;

/**
 * Parses an expression from a token range with C precedence: unary {@code - ~ !} (and a no-op
 * {@code +}), then {@code * / %}, {@code + -}, {@code << >>}, {@code < <= > >=}, {@code == !=},
 * {@code &}, {@code ^}, {@code |}, {@code &&}, {@code ||}. This differs from GNU as, which binds
 * shifts like multiplication ({@code 1+2<<3} is 24 here, 17 there). {@code %name(expr)} in
 * operand position is a backend relocation operator.
 *
 * Nesting of parentheses, unary operators and relocation operators is capped at
 * {@link #MAX_NESTING}, and the whole tree's depth at {@link #MAX_DEPTH}, so neither parsing nor
 * evaluation can overflow the stack on hostile input.
 */
public final class ExprParser {

    public static final int MAX_NESTING = 256;
    public static final int MAX_DEPTH = 2048;

    private static final String[][] LEVELS = {
            {"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", "<=", ">", ">="},
            {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
    };

    private final List<AsmToken> tokens;
    private final int end;
    private final int fallbackColumn;
    private int pos;
    private int nesting;
    private int depth;

    private ExprParser(List<AsmToken> tokens, int from, int to, int fallbackColumn) {
        this.tokens = tokens;
        this.pos = from;
        this.end = to;
        this.fallbackColumn = fallbackColumn;
    }

    /**
     * Parses tokens [from, to) as one whole expression.
     *
     * @param fallbackColumn column reported when the range is empty
     * @throws AsmError on a syntax error
     */
    public static Expr parse(List<AsmToken> tokens, int from, int to, int fallbackColumn) {
        ExprParser p = new ExprParser(tokens, from, to, fallbackColumn);
        if (from >= to)
            throw new AsmError(fallbackColumn, "expected an expression");
        Expr e = p.binary(0);
        if (p.pos < p.end)
            throw new AsmError(p.tokens.get(p.pos).column(),
                    "unexpected '" + p.tokens.get(p.pos).display() + "' in expression");
        return e;
    }

    public static Expr parse(List<AsmToken> tokens, int fallbackColumn) {
        return parse(tokens, 0, tokens.size(), fallbackColumn);
    }

    private AsmToken peek() {
        return pos < end ? tokens.get(pos) : null;
    }

    private int column() {
        AsmToken t = peek();
        if (t != null)
            return t.column();
        return pos > 0 && pos - 1 < tokens.size() ? tokens.get(pos - 1).column() : fallbackColumn;
    }

    private Expr binary(int level) {
        if (level == LEVELS.length)
            return unary();
        Expr left = binary(level + 1);
        int leftDepth = depth;
        while (true) {
            AsmToken t = peek();
            String op = matchOp(t, LEVELS[level]);
            if (op == null) {
                depth = leftDepth;
                return left;
            }
            pos++;
            Expr right = binary(level + 1);
            left = new Expr.Binary(op, left, right, left.column());
            setDepth(Math.max(leftDepth, depth) + 1, t.column());
            leftDepth = depth;
        }
    }

    private static String matchOp(AsmToken t, String[] ops) {
        if (t == null || t.kind() != Kind.PUNCT)
            return null;
        for (String op : ops)
            if (t.text().equals(op))
                return op;
        return null;
    }

    private Expr unary() {
        AsmToken t = peek();
        if (t == null)
            throw new AsmError(column(), "expected an expression");
        if (t.kind() == Kind.PUNCT && (t.text().equals("-") || t.text().equals("~")
                || t.text().equals("!") || t.text().equals("+"))) {
            pos++;
            enter(t.column());
            Expr operand = unary();
            nesting--;
            if (t.text().equals("+"))
                return operand;
            setDepth(depth + 1, t.column());
            return new Expr.Unary(t.text(), operand, t.column());
        }
        return primary();
    }

    private void enter(int column) {
        if (++nesting > MAX_NESTING)
            throw new AsmError(column, "expression nested more than " + MAX_NESTING + " deep");
    }

    private Expr primary() {
        AsmToken t = peek();
        if (t == null)
            throw new AsmError(column(), "expected an expression");
        switch (t.kind()) {
            case INT -> {
                pos++;
                depth = 1;
                return new Expr.Num(t.value(), t.column());
            }
            case LOCAL_REF -> {
                pos++;
                depth = 1;
                return new Expr.LocalRef((int) t.value(), t.forward(), t.column());
            }
            case IDENT -> {
                pos++;
                depth = 1;
                if (t.text().equals("."))
                    return new Expr.Dot(t.column());
                return new Expr.Sym(t.text(), t.column());
            }
            case PUNCT -> {
                if (t.text().equals("(")) {
                    pos++;
                    enter(t.column());
                    Expr inner = binary(0);
                    expectClose(t);
                    nesting--;
                    return inner;
                }
                if (t.text().equals("%") && pos + 2 < end && tokens.get(pos + 1).kind() == Kind.IDENT
                        && tokens.get(pos + 2).isPunct("(")) {
                    String name = tokens.get(pos + 1).text();
                    AsmToken open = tokens.get(pos + 2);
                    pos += 3;
                    enter(t.column());
                    Expr arg = binary(0);
                    expectClose(open);
                    nesting--;
                    setDepth(depth + 1, t.column());
                    return new Expr.Reloc(name, arg, t.column());
                }
            }
            default -> { }
        }
        throw new AsmError(t.column(), "unexpected '" + t.display() + "' in expression");
    }

    private void expectClose(AsmToken open) {
        AsmToken t = peek();
        if (t == null || !t.isPunct(")"))
            throw new AsmError(t == null ? column() : t.column(),
                    "missing ')' for '(' at column " + open.column());
        pos++;
    }

    /** Depth of the tree the last parse method returned. */
    private void setDepth(int d, int column) {
        depth = d;
        if (d > MAX_DEPTH)
            throw new AsmError(column, "expression too long (more than " + MAX_DEPTH + " levels)");
    }
}
