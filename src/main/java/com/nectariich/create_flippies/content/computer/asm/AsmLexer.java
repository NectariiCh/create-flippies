package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits one source file into tokens, configured per backend by a {@link LexerSyntax}. Every
 * statement ends with a NEWLINE token (the last one too). Bad input never throws: it is reported
 * through the error callback and skipped.
 *
 * Integers: decimal, {@code 0x}, {@code 0b}, {@code 0o}, and GNU's leading-zero octal
 * ({@code 010} is 8). Values up to 2^64 - 1 are accepted and kept as their 64-bit pattern.
 * Character literals and strings take the escapes {@code \n \t \r \0 \\ \' \" \xNN} and octal
 * {@code \NNN}; non-ASCII characters in strings are encoded as UTF-8. The classic 68k flags of
 * {@link LexerSyntax} add {@code $hex}, {@code %binary}, float literals, {@code *} comments in
 * column 1 and {@code *} as the location counter.
 */
public final class AsmLexer {

    /** Receives lexical errors. */
    @FunctionalInterface
    public interface ErrorSink {
        void error(int line, int column, String message);
    }

    private static final String[] TWO_CHAR_OPS = {"<<", ">>", "<=", ">=", "==", "!=", "&&", "||"};
    private static final String ONE_CHAR_OPS = "+-*/%&|^~!<>=(),:[]{}@#";

    private final LexerSyntax syntax;
    private final String src;
    private final ErrorSink errors;
    private final List<AsmToken> out = new ArrayList<>();
    private int pos;
    private int line = 1;
    private int lineStart;

    public AsmLexer(LexerSyntax syntax, String src, ErrorSink errors) {
        this.syntax = syntax;
        this.src = src;
        this.errors = errors;
    }

    public static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '.' || c == '$';
    }

    public static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    public List<AsmToken> tokenize() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                newline(col());
                pos++;
                line++;
                lineStart = pos;
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                pos++;
            } else if (syntax.statementSeparator() != 0 && c == syntax.statementSeparator()) {
                newline(col());
                pos++;
            } else if (syntax.starColumnOneComment() && c == '*' && pos == lineStart) {
                while (pos < src.length() && src.charAt(pos) != '\n')
                    pos++;
            } else if (lineComment()) {
                while (pos < src.length() && src.charAt(pos) != '\n')
                    pos++;
            } else if (syntax.blockComments() && src.startsWith("/*", pos)) {
                blockComment();
            } else if (syntax.dollarHex() && c == '$' && pos + 1 < src.length()
                    && Character.digit(src.charAt(pos + 1), 16) >= 0) {
                prefixedNumber(16);
            } else if (syntax.percentBinary() && c == '%' && pos + 1 < src.length()
                    && (src.charAt(pos + 1) == '0' || src.charAt(pos + 1) == '1') && operandPosition()) {
                prefixedNumber(2);
            } else if (syntax.starLocationCounter() && c == '*' && operandPosition()) {
                out.add(new AsmToken(Kind.IDENT, ".", 0, line, col()));
                pos++;
            } else if (isIdentStart(c) || (syntax.registerSigil() != 0 && c == syntax.registerSigil()
                    && pos + 1 < src.length() && isIdentPart(src.charAt(pos + 1)))) {
                identifier();
            } else if (c >= '0' && c <= '9') {
                number();
            } else if (c == '\'') {
                charLiteral();
            } else if (c == '"') {
                string();
            } else {
                punct(c);
            }
        }
        newline(col());
        return out;
    }

    private int col() {
        return pos - lineStart + 1;
    }

    private void newline(int column) {
        if (out.isEmpty() || out.get(out.size() - 1).kind() != Kind.NEWLINE)
            out.add(new AsmToken(Kind.NEWLINE, "\n", 0, line, column));
    }

    private boolean lineComment() {
        for (String s : syntax.lineComments())
            if (src.startsWith(s, pos))
                return true;
        return false;
    }

    private void blockComment() {
        int startLine = line, startCol = col();
        pos += 2;
        while (pos < src.length() && !src.startsWith("*/", pos)) {
            if (src.charAt(pos) == '\n') {
                line++;
                lineStart = pos + 1;
            }
            pos++;
        }
        if (pos >= src.length())
            errors.error(startLine, startCol, "unterminated comment");
        else
            pos += 2;
    }

    private void identifier() {
        int start = pos, column = col();
        pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos)))
            pos++;
        out.add(new AsmToken(Kind.IDENT, src.substring(start, pos), 0, line, column));
    }

    private void number() {
        int start = pos, column = col();
        if (syntax.floatLiterals()) {
            int end = floatEnd();
            if (end > 0) {
                pos = end;
                out.add(new AsmToken(Kind.FLOAT, src.substring(start, end), 0, line, column));
                return;
            }
        }
        while (pos < src.length() && isIdentPart(src.charAt(pos))
                && !(syntax.floatLiterals() && src.charAt(pos) == '.'))
            pos++;
        String text = src.substring(start, pos);
        // A numeric local label reference: decimal digits then b or f.
        char last = text.charAt(text.length() - 1);
        if ((last == 'b' || last == 'f') && text.length() > 1 && allDigits(text, 0, text.length() - 1)
                && !(text.length() >= 3 && text.startsWith("0b"))) {
            String digits = text.substring(0, text.length() - 1);
            if (digits.length() <= 4) {
                out.add(new AsmToken(Kind.LOCAL_REF, text, Integer.parseInt(digits), line, column));
                return;
            }
        }
        Long v = parseInteger(text);
        if (v == null) {
            errors.error(line, column, "bad number '" + text + "'");
            v = 0L;
        }
        out.add(new AsmToken(Kind.INT, text, v, line, column));
    }

    /**
     * With float literals: where a decimal float starting at {@code pos} ends (digits, then a
     * fraction {@code .digits}, an exponent {@code e[+-]digits} or both), or -1 if the text there
     * is not one.
     */
    private int floatEnd() {
        int p = digitsEnd(pos);
        boolean fraction = false, exponent = false;
        if (p < src.length() && src.charAt(p) == '.' && digitsEnd(p + 1) > p + 1) {
            p = digitsEnd(p + 1);
            fraction = true;
        }
        if (p < src.length() && (src.charAt(p) == 'e' || src.charAt(p) == 'E')) {
            int q = p + 1;
            if (q < src.length() && (src.charAt(q) == '+' || src.charAt(q) == '-'))
                q++;
            if (digitsEnd(q) > q) {
                p = digitsEnd(q);
                exponent = true;
            }
        }
        if (!fraction && !exponent)
            return -1;
        if (p < src.length() && isIdentPart(src.charAt(p)))
            return -1;
        return p;
    }

    private int digitsEnd(int p) {
        while (p < src.length() && src.charAt(p) >= '0' && src.charAt(p) <= '9')
            p++;
        return p;
    }

    /** {@code $hex} or {@code %binary}: the prefix at {@code pos}, then the digits. */
    private void prefixedNumber(int radix) {
        int start = pos, column = col();
        pos++;
        while (pos < src.length() && isIdentPart(src.charAt(pos)) && src.charAt(pos) != '.')
            pos++;
        String text = src.substring(start, pos);
        Long v = null;
        try {
            v = Long.parseUnsignedLong(text.substring(1), radix);
        } catch (NumberFormatException e) {
            // reported below
        }
        if (v == null) {
            errors.error(line, column, "bad number '" + text + "'");
            v = 0L;
        }
        out.add(new AsmToken(Kind.INT, text, v, line, column));
    }

    /**
     * Whether the next token starts an operand rather than following a value (see
     * {@link LexerSyntax}): decides whether {@code %} and {@code *} are operators.
     */
    private boolean operandPosition() {
        if (out.isEmpty())
            return true;
        int i = out.size() - 1;
        AsmToken last = out.get(i);
        return switch (last.kind()) {
            case NEWLINE -> true;
            case PUNCT -> !last.text().equals(")") && !last.text().equals("]");
            case IDENT -> (syntax.equKeyword() && (last.text().equalsIgnoreCase("equ")
                    || last.text().equalsIgnoreCase("set")))
                    || i == 0 || out.get(i - 1).kind() == Kind.NEWLINE || out.get(i - 1).isPunct(":");
            default -> false;
        };
    }

    private static boolean allDigits(String s, int from, int to) {
        for (int i = from; i < to; i++)
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return false;
        return to > from;
    }

    /** The value of an integer literal, or null if it is malformed or above 2^64 - 1. */
    static Long parseInteger(String text) {
        int radix = 10;
        String digits = text;
        if (text.length() > 2 && text.charAt(0) == '0') {
            char p = Character.toLowerCase(text.charAt(1));
            if (p == 'x') { radix = 16; digits = text.substring(2); }
            else if (p == 'b') { radix = 2; digits = text.substring(2); }
            else if (p == 'o') { radix = 8; digits = text.substring(2); }
            else { radix = 8; digits = text.substring(1); }
        } else if (text.length() == 2 && text.charAt(0) == '0') {
            radix = 8;
            digits = text.substring(1);
        }
        if (digits.isEmpty())
            return null;
        try {
            return Long.parseUnsignedLong(digits, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void charLiteral() {
        int column = col();
        pos++;
        if (pos >= src.length() || src.charAt(pos) == '\n') {
            errors.error(line, column, "unterminated character literal");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (src.charAt(pos) == '\\') {
            escape(sb, column);
        } else {
            int cp = src.codePointAt(pos);
            pos += Character.charCount(cp);
            sb.appendCodePoint(cp);
        }
        if (pos >= src.length() || src.charAt(pos) != '\'') {
            errors.error(line, column, "unterminated character literal");
            while (pos < src.length() && src.charAt(pos) != '\'' && src.charAt(pos) != '\n')
                pos++;
            if (pos < src.length() && src.charAt(pos) == '\'')
                pos++;
            out.add(new AsmToken(Kind.INT, "'?'", 0, line, column));
            return;
        }
        pos++;
        long value = sb.isEmpty() ? 0 : sb.codePointAt(0);
        out.add(new AsmToken(Kind.INT, src.substring(lineStart + column - 1, pos), value, line, column));
    }

    private void string() {
        int column = col();
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                errors.error(line, column, "unterminated string");
                break;
            }
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                break;
            }
            if (c == '\\') {
                escape(sb, column);
            } else if (c < 0x80) {
                sb.append(c);
                pos++;
            } else {
                int cp = src.codePointAt(pos);
                pos += Character.charCount(cp);
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8))
                    sb.append((char) (b & 0xFF));
            }
        }
        out.add(new AsmToken(Kind.STRING, sb.toString(), 0, line, column));
    }

    /** Decodes one escape at {@code pos} (which is at the backslash) as a byte value. */
    private void escape(StringBuilder sb, int column) {
        pos++;
        if (pos >= src.length() || src.charAt(pos) == '\n') {
            errors.error(line, column, "unterminated escape");
            return;
        }
        char e = src.charAt(pos++);
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'a' -> sb.append((char) 7);
            case 'b' -> sb.append((char) 8);
            case 'f' -> sb.append((char) 12);
            case 'v' -> sb.append((char) 11);
            case '\\', '\'', '"' -> sb.append(e);
            case 'x' -> {
                int v = 0, n = 0;
                while (n < 2 && pos < src.length() && Character.digit(src.charAt(pos), 16) >= 0) {
                    v = v * 16 + Character.digit(src.charAt(pos++), 16);
                    n++;
                }
                if (n == 0)
                    errors.error(line, column, "\\x escape without hex digits");
                sb.append((char) v);
            }
            default -> {
                if (e >= '0' && e <= '7') {
                    int v = e - '0', n = 1;
                    while (n < 3 && pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                        v = v * 8 + (src.charAt(pos++) - '0');
                        n++;
                    }
                    sb.append((char) (v & 0xFF));
                } else {
                    errors.error(line, column, "unknown escape '\\" + e + "'");
                    sb.append(e);
                }
            }
        }
    }

    private void punct(char c) {
        int column = col();
        for (String op : TWO_CHAR_OPS) {
            if (src.startsWith(op, pos)) {
                pos += 2;
                out.add(new AsmToken(Kind.PUNCT, op, 0, line, column));
                return;
            }
        }
        if (ONE_CHAR_OPS.indexOf(c) >= 0 && (c != '#' || syntax.hashImmediate())) {
            pos++;
            out.add(new AsmToken(Kind.PUNCT, String.valueOf(c), 0, line, column));
            return;
        }
        int cp = src.codePointAt(pos);
        pos += Character.charCount(cp);
        errors.error(line, column, "unexpected character '" + new String(Character.toChars(cp)) + "'");
    }
}
