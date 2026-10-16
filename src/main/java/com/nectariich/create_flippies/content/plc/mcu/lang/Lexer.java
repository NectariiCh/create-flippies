package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits source text into preprocessing tokens (C++ [lex.pptoken]): identifiers,
 * pp-numbers, character and string literals, and punctuators by longest match.
 * Line splices and comments are removed here, so a token's line and column are the
 * ones the player sees in the editor even when a backslash-newline sits in between.
 */
final class Lexer {

    private static final String[] PUNCTS = {
            "<<=", ">>=", "...", "->*", "->", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "&=", "^=", "|=", "##", "::", ".*",
            "+", "-", "*", "/", "%", "&", "|", "^", "~", "!", "=", "<", ">", "?", ":", ";", ",", ".",
            "(", ")", "[", "]", "{", "}", "#" };

    private final char[] src;
    private final int[] lineOf;
    private final int[] colOf;
    private final int n;
    private final int file;
    private final Diagnostics diags;

    private Lexer(String text, int file, Diagnostics diags) {
        this.file = file;
        this.diags = diags;
        // Phase 2: drop every backslash-newline, remembering where each kept char was written.
        char[] out = new char[text.length() + 1];
        int[] lines = new int[text.length() + 1];
        int[] cols = new int[text.length() + 1];
        int line = 1, col = 1, k = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                int j = i + 1;
                while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t'))
                    j++;
                if (j < text.length() && (text.charAt(j) == '\n' || text.charAt(j) == '\r')) {
                    if (text.charAt(j) == '\r' && j + 1 < text.length() && text.charAt(j + 1) == '\n')
                        j++;
                    i = j;
                    line++;
                    col = 1;
                    continue;
                }
            }
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n')
                    continue;
                c = '\n';
            }
            out[k] = c;
            lines[k] = line;
            cols[k] = col;
            k++;
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col += c == '\t' ? 1 : 1;
            }
        }
        out[k] = '\n';
        lines[k] = line;
        cols[k] = col;
        this.src = out;
        this.lineOf = lines;
        this.colOf = cols;
        this.n = k;
    }

    static List<Token> lex(String text, int file, Diagnostics diags) {
        return new Lexer(text, file, diags).run();
    }

    private List<Token> run() {
        List<Token> toks = new ArrayList<>();
        int i = 0;
        boolean bol = true;
        boolean space = false;
        while (i < n) {
            char c = src[i];
            if (c == '\n') {
                bol = true;
                space = false;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f' || c == '\u000b') {
                space = true;
                i++;
                continue;
            }
            if (c == '/' && src[i + 1] == '/') {
                while (i < n && src[i] != '\n')
                    i++;
                space = true;
                continue;
            }
            if (c == '/' && src[i + 1] == '*') {
                int start = i;
                i += 2;
                while (i < n && !(src[i] == '*' && src[i + 1] == '/'))
                    i++;
                if (i >= n)
                    diags.error(file, lineOf[start], colOf[start], "unterminated comment");
                i += 2;
                space = true;
                continue;
            }
            int start = i;
            Token.Kind kind;
            int raw = rawPrefix(i);
            if (raw > 0) {
                int[] end = new int[1];
                String text = rawString(i + raw, end);
                Token t = new Token(Token.Kind.STRING, text, file, lineOf[start], colOf[start]);
                t.bol = bol;
                t.space = space;
                toks.add(t);
                bol = false;
                space = false;
                i = end[0];
                continue;
            }
            if (isIdentStart(c)) {
                // u8"", L'' and friends: treat the prefix as part of the literal.
                if ((c == 'L' || c == 'u' || c == 'U') && (src[i + 1] == '"' || src[i + 1] == '\'')) {
                    i++;
                    kind = src[i] == '"' ? Token.Kind.STRING : Token.Kind.CHAR;
                    i = quoted(i, src[i]);
                } else if (c == 'u' && src[i + 1] == '8' && (src[i + 2] == '"' || src[i + 2] == '\'')) {
                    // u8"..." and C++17's u8'a' (a plain char).
                    i += 2;
                    kind = src[i] == '"' ? Token.Kind.STRING : Token.Kind.CHAR;
                    i = quoted(i, src[i]);
                } else {
                    while (i < n && isIdentPart(src[i]))
                        i++;
                    kind = Token.Kind.IDENT;
                }
            } else if (isDigit(c) || (c == '.' && isDigit(src[i + 1]))) {
                i++;
                while (i < n) {
                    char d = src[i];
                    if ((d == 'e' || d == 'E' || d == 'p' || d == 'P') && (src[i + 1] == '+' || src[i + 1] == '-'))
                        i += 2;
                    else if (isIdentPart(d) || d == '.' || (d == '\'' && isIdentPart(src[i + 1])))
                        i++;
                    else
                        break;
                }
                kind = Token.Kind.NUMBER;
            } else if (c == '"') {
                i = quoted(i, '"');
                kind = Token.Kind.STRING;
            } else if (c == '\'') {
                i = quoted(i, '\'');
                kind = Token.Kind.CHAR;
            } else {
                String p = null;
                for (String cand : PUNCTS) {
                    if (matches(i, cand)) {
                        p = cand;
                        break;
                    }
                }
                if (p == null) {
                    diags.error(file, lineOf[i], colOf[i], "stray '" + (c < 32 || c > 126 ? "\\" + Integer.toOctalString(c) : String.valueOf(c)) + "' in program");
                    i++;
                    continue;
                }
                i += p.length();
                kind = Token.Kind.PUNCT;
            }
            Token t = new Token(kind, new String(src, start, i - start), file, lineOf[start], colOf[start]);
            t.bol = bol;
            t.space = space;
            toks.add(t);
            bol = false;
            space = false;
        }
        Token eof = new Token(Token.Kind.EOF, "", file, lineOf[n], colOf[n]);
        eof.bol = true;
        toks.add(eof);
        return toks;
    }

    /** The length of a raw string literal's prefix ({@code R"}, {@code u8R"}, ...) up to the quote, or 0. */
    private int rawPrefix(int i) {
        int k = i;
        if (src[k] == 'u' && src[k + 1] == '8')
            k += 2;
        else if (src[k] == 'u' || src[k] == 'U' || src[k] == 'L')
            k++;
        if (src[k] == 'R' && src[k + 1] == '"')
            return k + 1 - i;
        return 0;
    }

    /**
     * A C++11 raw string literal {@code R"delim( ... )delim"}, starting at its opening
     * quote. It is handed on as an ordinary literal with the same bytes (every
     * character that would need it escaped), so nothing after the lexer has to know
     * raw strings exist. Sets {@code end[0]} to the index after the literal.
     */
    private String rawString(int q, int[] end) {
        int start = q;
        int i = q + 1;
        StringBuilder delim = new StringBuilder();
        while (i < n && src[i] != '(' && delim.length() <= 16) {
            char c = src[i];
            if (c == ' ' || c == ')' || c == '\\' || c == '\t' || c == '\n' || c == '"') {
                diags.error(file, lineOf[i], colOf[i], "invalid character '" + (c == '\n' ? "\\n" : String.valueOf(c)) + "' in raw string delimiter");
                end[0] = i;
                return "\"\"";
            }
            delim.append(c);
            i++;
        }
        if (delim.length() > 16) {
            diags.error(file, lineOf[start], colOf[start], "raw string delimiter longer than 16 characters");
            end[0] = i;
            return "\"\"";
        }
        if (i >= n) {
            diags.error(file, lineOf[start], colOf[start], "unterminated raw string");
            end[0] = n;
            return "\"\"";
        }
        i++;
        String close = ")" + delim + "\"";
        StringBuilder sb = new StringBuilder("\"");
        while (true) {
            if (i >= n) {
                diags.error(file, lineOf[start], colOf[start], "unterminated raw string");
                end[0] = n;
                return "\"\"";
            }
            if (matches(i, close)) {
                end[0] = i + close.length();
                return sb.append('"').toString();
            }
            char c = src[i++];
            if (c == '"' || c == '\\')
                sb.append('\\').append(c);
            else if (c < 32 || c == 127)
                sb.append(String.format("\\%03o", (int) c));
            else
                sb.append(c); // non-ASCII stays as written: the literal's decoder makes it UTF-8, as for "..."
        }
    }

    private int quoted(int i, char q) {
        int start = i;
        i++;
        while (i < n && src[i] != q && src[i] != '\n') {
            if (src[i] == '\\')
                i++;
            i++;
        }
        if (i >= n || src[i] != q) {
            diags.error(file, lineOf[start], colOf[start], "missing terminating " + q + " character");
            return i;
        }
        return i + 1;
    }

    private boolean matches(int i, String p) {
        for (int k = 0; k < p.length(); k++)
            if (i + k >= n + 1 || src[i + k] != p.charAt(k))
                return false;
        return true;
    }

    static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
