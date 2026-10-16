package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translation phase 4, as cpp does it for an .ino: directives, conditional
 * inclusion and macro expansion (object- and function-like, {@code #}, {@code ##},
 * variadic with GNU comma elision), using Prosser's hide-set algorithm so that a
 * macro never expands inside its own expansion but expands normally next to it.
 *
 * arduino-builder turns a sketch into C++ by prepending {@code #include <Arduino.h>}
 * and inserting prototypes. Here the include is the core's prelude, preprocessed
 * first in the same macro table so its definitions are visible to the sketch; the
 * prototypes are not text at all: the semantic pass declares every function before
 * it checks any body (see {@link Sema}), which is what the generated prototypes
 * achieve.
 *
 * {@code #include} resolves nothing from disk. Headers the core and newlib provide
 * (and whose contents the prelude already declares) are accepted as no-ops; headers
 * of communication libraries are refused with a message saying why; anything else
 * is a missing file, as it would be on a machine without that library installed.
 */
final class Preprocessor {

    /** Headers whose declarations are already part of Arduino.h here. */
    private static final Set<String> HARMLESS = Set.of(
            "Arduino.h", "arduino.h", "WProgram.h", "wiring.h", "wiring_private.h", "WString.h", "binary.h",
            "stdint.h", "stdbool.h", "stddef.h", "math.h", "string.h", "stdlib.h", "ctype.h", "limits.h",
            "float.h", "inttypes.h", "stdio.h", "avr/pgmspace.h", "pgmspace.h", "cstdint", "cmath", "cstring",
            "cstdlib", "cctype", "climits", "cfloat", "cstddef", "cstdio", "cinttypes", "stdarg.h", "cstdarg",
            "itoa.h", "avr/dtostrf.h", "new", "new.h");

    /** Libraries that talk to the outside world, which this PLC has no hardware for. */
    private static final Set<String> COMMS = Set.of(
            "SPI.h", "Wire.h", "SoftwareSerial.h", "HardwareSerial.h", "EEPROM.h", "Ethernet.h", "Ethernet2.h",
            "EthernetUdp.h", "EthernetClient.h", "EthernetServer.h", "WiFi.h", "WiFiUdp.h", "SD.h", "Stream.h",
            "Print.h", "Printable.h", "USBAPI.h", "USBCore.h", "Keyboard.h", "Mouse.h", "CAN.h", "STM32_CAN.h",
            "ArduinoModbus.h", "ModbusMaster.h", "ArduinoRS485.h", "OneWire.h", "STM32RTC.h",
            "HardwareTimer.h", "Udp.h", "Client.h", "Server.h", "IPAddress.h", "FRAM.h", "SPIMemory.h");

    /**
     * STM32duino libraries for on-chip peripherals this emulation does not model.
     * They are not communication libraries, so they get their own message.
     */
    private static final Set<String> UNSUPPORTED = Set.of("IWatchdog.h", "STM32LowPower.h", "LowPower.h", "backup.h");

    static final class Macro {
        final String name;
        /** Null for an object-like macro. */
        final List<String> params;
        final boolean variadic;
        final List<Token> body;
        /** __LINE__, __FILE__, __COUNTER__ and friends, computed at each use. */
        final boolean dynamic;

        Macro(String name, List<String> params, boolean variadic, List<Token> body, boolean dynamic) {
            this.name = name;
            this.params = params;
            this.variadic = variadic;
            this.body = body;
            this.dynamic = dynamic;
        }

        boolean sameAs(Macro o) {
            if (o.dynamic || dynamic || o.variadic != variadic || !java.util.Objects.equals(params, o.params) || body.size() != o.body.size())
                return false;
            for (int i = 0; i < body.size(); i++) {
                if (!body.get(i).text.equals(o.body.get(i).text) || (i > 0 && body.get(i).space != o.body.get(i).space))
                    return false;
            }
            return true;
        }
    }

    /** Where tokens are read from: pushed-back expansions first, then the token list. */
    private static final class Input {
        final ArrayDeque<Token> pending = new ArrayDeque<>();
        final List<Token> base;
        int pos;
        /** Directives are only honoured when reading a file, never an argument. */
        final boolean file;

        Input(List<Token> base, boolean file) {
            this.base = base;
            this.file = file;
        }

        Token peek() {
            Token t = pending.peekFirst();
            return t != null ? t : base.get(pos);
        }

        Token next() {
            Token t = pending.pollFirst();
            if (t != null)
                return t;
            t = base.get(pos);
            if (t.kind != Token.Kind.EOF)
                pos++;
            return t;
        }

        void pushFront(List<Token> toks) {
            for (int i = toks.size() - 1; i >= 0; i--)
                pending.addFirst(toks.get(i));
        }
    }

    private static final class Cond {
        boolean active;
        boolean taken;
        final boolean parentActive;
        boolean sawElse;

        Cond(boolean parentActive) {
            this.parentActive = parentActive;
        }
    }

    /**
     * How many tokens macro expansion may produce in one compile. cpp has no such
     * limit, but cpp's memory is the developer's; here the compile runs inside the
     * game (and on the client for Verify), and a few lines of
     * {@code #define M1 (M0+M0)} doubling each other expand exponentially. A million
     * tokens is far beyond any real sketch's expansion (the core's prelude and a
     * 2000-line sketch produce a few tens of thousands) and still fits comfortably in
     * memory.
     */
    static final int MAX_EXPANSION = 1_000_000;

    /**
     * {@code #pragma pack(...)} changes struct layout, which is the parser's business,
     * so it is handed on as one identifier token: this prefix followed by the
     * parenthesised arguments as written, e.g. {@code __pragma_pack(push,1)}. No
     * identifier the lexer makes can contain a parenthesis, so it cannot clash.
     * Every other pragma ({@code once}, {@code GCC optimize}, ...) changes nothing a
     * sketch can observe here and is dropped.
     */
    static final String PRAGMA_PACK = "__pragma_pack(";

    private final Diagnostics diags;
    private final Map<String, Macro> macros = new HashMap<>();
    /** Tokens produced by macro expansion so far, against {@link #MAX_EXPANSION}. */
    private long expansion;
    private final List<Token> out = new ArrayList<>();
    private int counter;
    private int lineDelta;
    private int currentFile;

    private Preprocessor(Diagnostics diags) {
        this.diags = diags;
        defineBuiltin("__LINE__");
        defineBuiltin("__FILE__");
        defineBuiltin("__COUNTER__");
        defineBuiltin("__DATE__");
        defineBuiltin("__TIME__");
    }

    private void defineBuiltin(String name) {
        macros.put(name, new Macro(name, null, false, List.of(), true));
    }

    /**
     * Preprocesses each source in order, sharing one macro table, and returns the
     * resulting tokens followed by one EOF.
     */
    static List<Token> run(Diagnostics diags, String[] texts, int[] files) {
        Preprocessor pp = new Preprocessor(diags);
        for (int i = 0; i < texts.length; i++) {
            pp.currentFile = files[i];
            pp.lineDelta = 0;
            List<Token> toks = Lexer.lex(texts[i], files[i], diags);
            pp.file(toks);
        }
        Token last = pp.out.isEmpty() ? null : pp.out.get(pp.out.size() - 1);
        Token eof = new Token(Token.Kind.EOF, "", Token.SKETCH, last == null ? 1 : last.line, last == null ? 1 : last.col + last.text.length());
        pp.out.add(eof);
        return pp.out;
    }

    /** The open conditional groups of the file being read; a macro's arguments may open and close them too. */
    private ArrayDeque<Cond> conds = new ArrayDeque<>();

    private void file(List<Token> toks) {
        Input in = new Input(toks, true);
        ArrayDeque<Cond> conds = new ArrayDeque<>();
        this.conds = conds;
        while (true) {
            Token t = in.peek();
            if (t.kind == Token.Kind.EOF && in.pending.isEmpty())
                break;
            boolean active = conds.isEmpty() || conds.peek().active;
            if (in.pending.isEmpty() && t.bol && t.is("#") && !t.fromMacro) {
                in.next();
                List<Token> line = readLine(in);
                directive(t, line, conds);
                continue;
            }
            if (!active) {
                in.next();
                continue;
            }
            expandNext(in, out);
        }
        if (!conds.isEmpty())
            diags.error(currentFile, toks.get(toks.size() - 1).line, 1, "unterminated #if");
    }

    private static List<Token> readLine(Input in) {
        List<Token> line = new ArrayList<>();
        while (true) {
            Token t = in.base.get(in.pos);
            if (t.kind == Token.Kind.EOF || t.bol)
                break;
            line.add(t);
            in.pos++;
        }
        return line;
    }

    private Token adjust(Token t) {
        if (lineDelta == 0 || t.file != currentFile)
            return t;
        Token c = new Token(t.kind, t.text, t.file, t.line + lineDelta, t.col);
        c.space = t.space;
        c.hide = t.hide;
        c.fromMacro = t.fromMacro;
        return c;
    }

    // ---- directives ----

    private void directive(Token hash, List<Token> line, ArrayDeque<Cond> conds) {
        boolean active = conds.isEmpty() || conds.peek().active;
        if (line.isEmpty())
            return; // the null directive
        Token d = line.get(0);
        String name = d.text;
        List<Token> rest = line.subList(1, line.size());
        switch (name) {
            case "if", "ifdef", "ifndef" -> {
                Cond c = new Cond(active);
                if (active) {
                    boolean v;
                    if (name.equals("if")) {
                        v = evalCondition(d, rest);
                    } else {
                        if (rest.isEmpty() || rest.get(0).kind != Token.Kind.IDENT) {
                            diags.error(d, "no macro name given in #" + name + " directive");
                            v = false;
                        } else {
                            v = isDefined(rest.get(0).text) == name.equals("ifdef");
                        }
                    }
                    c.active = v;
                    c.taken = v;
                }
                conds.push(c);
            }
            case "elif" -> {
                Cond c = conds.peek();
                if (c == null) {
                    diags.error(d, "#elif without #if");
                    return;
                }
                if (c.sawElse)
                    diags.error(d, "#elif after #else");
                if (c.parentActive && !c.taken) {
                    boolean v = evalCondition(d, rest);
                    c.active = v;
                    c.taken = v;
                } else {
                    c.active = false;
                }
            }
            case "else" -> {
                Cond c = conds.peek();
                if (c == null) {
                    diags.error(d, "#else without #if");
                    return;
                }
                if (c.sawElse)
                    diags.error(d, "#else after #else");
                c.sawElse = true;
                c.active = c.parentActive && !c.taken;
                c.taken = true;
            }
            case "endif" -> {
                if (conds.isEmpty())
                    diags.error(d, "#endif without #if");
                else
                    conds.pop();
            }
            default -> {
                if (!active)
                    return;
                switch (name) {
                    case "define" -> define(d, rest);
                    case "undef" -> {
                        if (rest.isEmpty() || rest.get(0).kind != Token.Kind.IDENT)
                            diags.error(d, "no macro name given in #undef directive");
                        else
                            macros.remove(rest.get(0).text);
                    }
                    case "include", "include_next" -> include(d, rest);
                    case "error" -> diags.error(d.file, d.line + (d.file == currentFile ? lineDelta : 0), hash.col, "#error " + joinRaw(rest));
                    case "warning" -> diags.warning(d.file, d.line + (d.file == currentFile ? lineDelta : 0), hash.col, "#warning " + joinRaw(rest));
                    case "pragma" -> pragma(d, rest);
                    case "ident", "sccs", "assert", "unassert" -> { }
                    case "line" -> {
                        List<Token> exp = expandList(rest);
                        if (!exp.isEmpty() && exp.get(0).kind == Token.Kind.NUMBER) {
                            try {
                                int n = Integer.parseInt(exp.get(0).text);
                                lineDelta = n - (d.line + 1);
                            } catch (NumberFormatException e) {
                                diags.error(d, "\"" + exp.get(0).text + "\" after #line is not a positive integer");
                            }
                        } else {
                            diags.error(d, "#line directive requires a simple digit sequence");
                        }
                    }
                    default -> {
                        if (d.kind == Token.Kind.NUMBER) {
                            // GNU line marker "# 12 "file"": same as #line.
                            try {
                                lineDelta = Integer.parseInt(d.text) - (d.line + 1);
                            } catch (NumberFormatException ignored) {
                                // not a line marker after all
                            }
                        } else {
                            diags.error(d, "invalid preprocessing directive #" + name);
                        }
                    }
                }
            }
        }
    }

    private void pragma(Token d, List<Token> rest) {
        if (rest.isEmpty() || !rest.get(0).is("pack"))
            return;
        StringBuilder sb = new StringBuilder(PRAGMA_PACK.substring(0, PRAGMA_PACK.length() - 1));
        if (rest.size() < 2 || !rest.get(1).is("(")) {
            diags.warning(d.file, d.line + (d.file == currentFile ? lineDelta : 0), d.col, "missing '(' after '#pragma pack' - ignored");
            return;
        }
        for (int i = 1; i < rest.size(); i++) {
            sb.append(rest.get(i).text);
            if (rest.get(i).is(")"))
                break;
        }
        Token p = rest.get(0);
        Token t = new Token(Token.Kind.IDENT, sb.toString(), p.file, p.line + (p.file == currentFile ? lineDelta : 0), p.col);
        out.add(t);
    }

    /** The text between the quotes of a "header" token; empty if it is unterminated (the lexer has reported that). */
    private static String quotedName(String text) {
        int a = text.indexOf('"'), b = text.lastIndexOf('"');
        return b > a ? text.substring(a + 1, b) : "";
    }

    private static String joinRaw(List<Token> toks) {
        StringBuilder sb = new StringBuilder();
        for (Token t : toks) {
            if (sb.length() > 0 && t.space)
                sb.append(' ');
            sb.append(t.text);
        }
        return sb.toString();
    }

    private void include(Token d, List<Token> rest) {
        String name;
        if (rest.isEmpty()) {
            diags.error(d, "#include expects \"FILENAME\" or <FILENAME>");
            return;
        }
        Token f = rest.get(0);
        if (f.kind == Token.Kind.STRING) {
            name = quotedName(f.text);
        } else if (f.is("<")) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (; i < rest.size() && !rest.get(i).is(">"); i++)
                sb.append(rest.get(i).text);
            if (i >= rest.size()) {
                diags.error(d, "missing terminating > character");
                return;
            }
            name = sb.toString();
        } else {
            diags.error(d, "#include expects \"FILENAME\" or <FILENAME>");
            return;
        }
        String bare = name.trim();
        if (HARMLESS.contains(bare))
            return;
        if (bare.equals("assert.h") || bare.equals("cassert")) {
            defineAssert(d);
            return;
        }
        if (UNSUPPORTED.contains(bare)) {
            String what = bare.equals("IWatchdog.h") ? "the independent watchdog (IWDG) library" : "the low-power and backup-domain libraries";
            diags.error(f.file, f.line + lineDelta, f.col, bare + ": " + what + " is not supported on this PLC");
            return;
        }
        if (COMMS.contains(bare)) {
            diags.error(f.file, f.line + lineDelta, f.col, bare + ": not available on this PLC (it has no communication or storage peripherals: no serial, SPI, I2C, CAN, Ethernet or EEPROM)");
            return;
        }
        diags.error(f.file, f.line + lineDelta, f.col, bare + ": No such file or directory");
    }

    /**
     * {@code <assert.h>}: newlib's {@code assert(e)} calls {@code __assert_func}, which
     * would print to stderr (nowhere, on this board) and then calls {@code abort()}:
     * a HardFault. Like the real header it is re-read at each inclusion, so it honours
     * {@code NDEBUG} as defined at that point.
     */
    private void defineAssert(Token at) {
        String body = isDefined("NDEBUG") ? "assert(e) ((void)0)" : "assert(e) ((e) ? (void)0 : abort())";
        List<Token> toks = new ArrayList<>(Lexer.lex(body, Token.INTERNAL, new Diagnostics()));
        toks.remove(toks.size() - 1);
        macros.remove("assert");
        Token d = new Token(Token.Kind.IDENT, "define", Token.INTERNAL, at.line, at.col);
        define(d, toks);
    }

    private void define(Token d, List<Token> rest) {
        if (rest.isEmpty() || rest.get(0).kind != Token.Kind.IDENT) {
            diags.error(d, "macro names must be identifiers");
            return;
        }
        Token nameTok = rest.get(0);
        String name = nameTok.text;
        if (name.equals("defined")) {
            diags.error(nameTok, "\"defined\" cannot be used as a macro name");
            return;
        }
        int i = 1;
        List<String> params = null;
        boolean variadic = false;
        if (rest.size() > 1 && rest.get(1).is("(") && !rest.get(1).space) {
            params = new ArrayList<>();
            i = 2;
            boolean ok = false;
            while (i < rest.size()) {
                Token p = rest.get(i++);
                if (p.is(")") && params.isEmpty() && !variadic) {
                    ok = true;
                    break;
                }
                if (p.is("...")) {
                    variadic = true;
                    params.add("__VA_ARGS__");
                    if (i < rest.size() && rest.get(i).is(")")) {
                        i++;
                        ok = true;
                    }
                    break;
                }
                if (p.kind != Token.Kind.IDENT) {
                    diags.error(p, "expected parameter name, found \"" + p.text + "\"");
                    return;
                }
                if (i < rest.size() && rest.get(i).is("...")) {
                    // GNU named variadic: #define f(args...) — treat args as __VA_ARGS__.
                    variadic = true;
                    params.add(p.text);
                    i++;
                    if (i < rest.size() && rest.get(i).is(")")) {
                        i++;
                        ok = true;
                    }
                    break;
                }
                params.add(p.text);
                if (i < rest.size() && rest.get(i).is(",")) {
                    i++;
                    continue;
                }
                if (i < rest.size() && rest.get(i).is(")")) {
                    i++;
                    ok = true;
                    break;
                }
                diags.error(p, "expected ',' or ')' in macro parameter list");
                return;
            }
            if (!ok) {
                diags.error(nameTok, "missing ')' in macro parameter list");
                return;
            }
        }
        List<Token> body = new ArrayList<>();
        for (int k = i; k < rest.size(); k++) {
            Token c = rest.get(k).copy();
            if (k == i)
                c.space = false;
            body.add(c);
        }
        if (params != null) {
            for (int k = 0; k < body.size(); k++) {
                if (body.get(k).is("#") && (k + 1 >= body.size() || !params.contains(body.get(k + 1).text))) {
                    diags.error(body.get(k), "'#' is not followed by a macro parameter");
                    return;
                }
            }
        }
        if (!body.isEmpty() && (body.get(0).is("##") || body.get(body.size() - 1).is("##"))) {
            diags.error(body.get(0), "'##' cannot appear at either end of a macro expansion");
            return;
        }
        Macro m = new Macro(name, params, variadic, body, false);
        Macro old = macros.get(name);
        if (old != null && !old.sameAs(m) && d.file == Token.SKETCH)
            diags.warning(nameTok.file, nameTok.line + lineDelta, nameTok.col, "\"" + name + "\" redefined");
        macros.put(name, m);
    }

    // ---- #if expressions ----

    /** Whether a name counts as a macro for {@code defined} and {@code #ifdef}; GCC also reports its special operators. */
    private boolean isDefined(String name) {
        return macros.containsKey(name) || name.equals("__has_include") || name.equals("__has_include_next");
    }

    /**
     * {@code __has_include(<h>)} / {@code __has_include("h")}: true for the headers that
     * resolve here (the core's and newlib's, which the prelude stands for), false for
     * anything else, communication libraries included, so a sketch that tests for
     * one takes its fallback path instead of failing on the #include.
     */
    private int hasInclude(List<Token> rest, int i, Token at, List<Token> toks) {
        int j = i + 1;
        if (j >= rest.size() || !rest.get(j).is("(")) {
            diags.error(at, "missing '(' before \"__has_include\" operand");
            return rest.size();
        }
        j++;
        String header = null;
        if (j < rest.size() && rest.get(j).kind == Token.Kind.STRING) {
            String s = rest.get(j).text;
            header = quotedName(s);
            j++;
        } else if (j < rest.size() && rest.get(j).is("<")) {
            StringBuilder sb = new StringBuilder();
            j++;
            while (j < rest.size() && !rest.get(j).is(">"))
                sb.append(rest.get(j++).text);
            j++;
            header = sb.toString();
        }
        if (header == null || j >= rest.size() || !rest.get(j).is(")")) {
            diags.error(at, "operator \"__has_include\" requires a header-name");
            return rest.size();
        }
        toks.add(new Token(Token.Kind.NUMBER, HARMLESS.contains(header.trim()) ? "1" : "0", at.file, at.line, at.col));
        return j;
    }

    private boolean evalCondition(Token d, List<Token> rest) {
        List<Token> toks = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) {
            Token t = rest.get(i);
            if (t.is("__has_include") || t.is("__has_include_next")) {
                i = hasInclude(rest, i, t, toks);
                continue;
            }
            if (t.is("defined")) {
                boolean paren = i + 1 < rest.size() && rest.get(i + 1).is("(");
                int j = paren ? i + 2 : i + 1;
                if (j >= rest.size() || rest.get(j).kind != Token.Kind.IDENT) {
                    diags.error(t, "operator \"defined\" requires an identifier");
                    return false;
                }
                boolean def = isDefined(rest.get(j).text);
                if (paren) {
                    if (j + 1 >= rest.size() || !rest.get(j + 1).is(")")) {
                        diags.error(t, "missing ')' after \"defined\"");
                        return false;
                    }
                    j++;
                }
                toks.add(new Token(Token.Kind.NUMBER, def ? "1" : "0", t.file, t.line, t.col));
                i = j;
            } else {
                toks.add(t);
            }
        }
        List<Token> exp = expandList(toks);
        if (exp.isEmpty()) {
            diags.error(d, "#" + d.text + " with no expression");
            return false;
        }
        IfExpr e = new IfExpr(exp, d);
        long v = e.ternary();
        if (e.pos < exp.size() && !e.failed) {
            diags.error(exp.get(e.pos), "missing binary operator before token \"" + exp.get(e.pos).text + "\"");
            return false;
        }
        return v != 0;
    }

    /** The integer constant expression of #if, evaluated in intmax_t as cpp does. */
    private final class IfExpr {
        final List<Token> t;
        final Token where;
        int pos;
        boolean failed;
        boolean unsigned;

        IfExpr(List<Token> t, Token where) {
            this.t = t;
            this.where = where;
        }

        Token peek() {
            return pos < t.size() ? t.get(pos) : null;
        }

        boolean accept(String s) {
            Token p = peek();
            if (p != null && p.kind == Token.Kind.PUNCT && p.text.equals(s)) {
                pos++;
                return true;
            }
            return false;
        }

        void fail(String msg) {
            if (!failed)
                diags.error(peek() != null ? peek() : where, msg);
            failed = true;
        }

        long ternary() {
            long c = binary(0);
            if (accept("?")) {
                long a = ternary();
                if (!accept(":"))
                    fail("expected ':' in preprocessor expression");
                long b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        private final String[][] levels = {
                { "||" }, { "&&" }, { "|" }, { "^" }, { "&" }, { "==", "!=" }, { "<", ">", "<=", ">=" },
                { "<<", ">>" }, { "+", "-" }, { "*", "/", "%" } };

        long binary(int level) {
            if (level == levels.length)
                return unary();
            long l = binary(level + 1);
            outer:
            while (true) {
                for (String op : levels[level]) {
                    if (accept(op)) {
                        if (op.equals("||")) {
                            long r = binary(level + 1);
                            l = (l != 0 || r != 0) ? 1 : 0;
                        } else if (op.equals("&&")) {
                            long r = binary(level + 1);
                            l = (l != 0 && r != 0) ? 1 : 0;
                        } else {
                            long r = binary(level + 1);
                            l = apply(op, l, r);
                        }
                        continue outer;
                    }
                }
                return l;
            }
        }

        long apply(String op, long l, long r) {
            switch (op) {
                case "|": return l | r;
                case "^": return l ^ r;
                case "&": return l & r;
                case "==": return l == r ? 1 : 0;
                case "!=": return l != r ? 1 : 0;
                case "<": return (unsigned ? Long.compareUnsigned(l, r) < 0 : l < r) ? 1 : 0;
                case ">": return (unsigned ? Long.compareUnsigned(l, r) > 0 : l > r) ? 1 : 0;
                case "<=": return (unsigned ? Long.compareUnsigned(l, r) <= 0 : l <= r) ? 1 : 0;
                case ">=": return (unsigned ? Long.compareUnsigned(l, r) >= 0 : l >= r) ? 1 : 0;
                case "<<": return l << r;
                case ">>": return unsigned ? l >>> r : l >> r;
                case "+": return l + r;
                case "-": return l - r;
                case "*": return l * r;
                case "/":
                    if (r == 0) {
                        fail("division by zero in #if");
                        return 0;
                    }
                    return unsigned ? Long.divideUnsigned(l, r) : l / r;
                case "%":
                    if (r == 0) {
                        fail("division by zero in #if");
                        return 0;
                    }
                    return unsigned ? Long.remainderUnsigned(l, r) : l % r;
                default: return 0;
            }
        }

        long unary() {
            if (accept("!"))
                return unary() == 0 ? 1 : 0;
            if (accept("-"))
                return -unary();
            if (accept("+"))
                return unary();
            if (accept("~"))
                return ~unary();
            if (accept("(")) {
                long v = ternary();
                if (!accept(")"))
                    fail("missing ')' in expression");
                return v;
            }
            Token p = peek();
            if (p == null) {
                fail("#if with no expression");
                return 0;
            }
            pos++;
            if (p.kind == Token.Kind.NUMBER) {
                Literals.IntValue iv = Literals.parseInt(p.text);
                if (iv == null) {
                    if (Literals.isFloat(p.text))
                        fail("floating constant in preprocessor expression");
                    else
                        fail("invalid integer constant \"" + p.text + "\" in #if");
                    return 0;
                }
                if (iv.type().isUnsigned())
                    unsigned = true;
                return iv.value();
            }
            if (p.kind == Token.Kind.CHAR) {
                long[] v = Literals.parseChar(p.text);
                return v == null ? 0 : v[0];
            }
            if (p.kind == Token.Kind.IDENT)
                return p.text.equals("true") ? 1 : 0;
            fail("token \"" + p.text + "\" is not valid in preprocessor expressions");
            return 0;
        }
    }

    // ---- macro expansion ----

    /** Macro-expands a standalone token list, as for an argument or an #if line. */
    private List<Token> expandList(List<Token> toks) {
        List<Token> base = new ArrayList<>(toks);
        Token last = toks.isEmpty() ? null : toks.get(toks.size() - 1);
        base.add(new Token(Token.Kind.EOF, "", last == null ? Token.SKETCH : last.file, last == null ? 0 : last.line, 0));
        Input in = new Input(base, false);
        List<Token> res = new ArrayList<>();
        while (in.peek().kind != Token.Kind.EOF)
            expandNext(in, res);
        return res;
    }

    /** Reads one token from {@code in}; expands it if it names a macro, else appends it to {@code sink}. */
    private void expandNext(Input in, List<Token> sink) {
        Token t = in.next();
        if (t.kind != Token.Kind.IDENT) {
            sink.add(adjust(t));
            return;
        }
        Macro m = macros.get(t.text);
        if (m == null || (t.hide != null && t.hide.contains(t.text))) {
            sink.add(adjust(t));
            return;
        }
        if (m.dynamic) {
            sink.add(dynamic(m, t));
            return;
        }
        if (m.params == null) {
            Set<String> hs = with(t.hide, m.name);
            List<Token> body = new ArrayList<>(m.body.size());
            for (int i = 0; i < m.body.size(); i++) {
                Token b = m.body.get(i);
                if (b.is("##") && i + 1 < m.body.size() && !body.isEmpty()) {
                    Token pasted = paste(body.get(body.size() - 1), m.body.get(i + 1), t);
                    body.set(body.size() - 1, pasted);
                    i++;
                    continue;
                }
                Token c = b.at(t);
                if (i == 0)
                    c.space = t.space;
                body.add(c);
            }
            for (Token b : body)
                b.hide = union(b.hide, hs);
            budget(body.size(), t);
            in.pushFront(body);
            return;
        }
        // Function-like: only a macro call if a '(' follows, possibly on a later line.
        if (!in.peek().is("(") || (in.file && in.pending.isEmpty() && in.peek().bol && isDirectiveStart(in))) {
            sink.add(adjust(t));
            return;
        }
        in.next();
        List<List<Token>> args = new ArrayList<>();
        List<Token> cur = new ArrayList<>();
        int depth = 0;
        Token rparen;
        while (true) {
            // GCC processes directives met while collecting a macro's arguments (an
            // extension; -pedantic warns): in "ID(" followed by an #ifdef/#else/#endif
            // group and ")", only the group's taken branch is an argument.
            boolean fromFile = in.file && in.pending.isEmpty();
            Token a = in.next();
            if (a.kind == Token.Kind.EOF) {
                diags.error(t, "unterminated argument list invoking macro \"" + m.name + "\"");
                return;
            }
            if (fromFile && a.bol && a.is("#") && !a.fromMacro) {
                directive(a, readLine(in), conds);
                continue;
            }
            if (fromFile && !(conds.isEmpty() || conds.peek().active))
                continue;
            if (a.is("(")) {
                depth++;
            } else if (a.is(")")) {
                if (depth == 0) {
                    rparen = a;
                    break;
                }
                depth--;
            } else if (a.is(",") && depth == 0 && !(m.variadic && args.size() == m.params.size() - 1)) {
                args.add(cur);
                cur = new ArrayList<>();
                continue;
            }
            cur.add(a);
        }
        args.add(cur);
        if (m.params.isEmpty() && args.size() == 1 && args.get(0).isEmpty())
            args.clear();
        if (m.variadic && args.size() == m.params.size() - 1)
            args.add(new ArrayList<>());
        if (args.size() != m.params.size()) {
            diags.error(t, "macro \"" + m.name + "\" " + (args.size() < m.params.size() ? "requires " : "passed ")
                    + (args.size() < m.params.size() ? m.params.size() + " arguments, but only " + args.size() + " given"
                    : args.size() + " arguments, but takes just " + m.params.size()));
            return;
        }
        Set<String> hs = with(intersect(t.hide, rparen.hide), m.name);
        List<Token> body = subst(m, args, t);
        for (Token b : body)
            b.hide = union(b.hide, hs);
        budget(body.size(), t);
        in.pushFront(body);
    }

    /** Counts {@code n} more expanded tokens; past the budget, stops the compile at the invocation. */
    private void budget(int n, Token at) {
        expansion += n;
        if (expansion > MAX_EXPANSION) {
            diags.error(at.file, at.line + (at.file == currentFile ? lineDelta : 0), at.col,
                    "macro expansion exceeds " + MAX_EXPANSION + " tokens (a macro that expands to itself twice, again and again?)");
            throw new Diagnostics.Abort();
        }
    }

    /**
     * The longest token {@code ##} or {@code #} may build. The expansion budget
     * counts tokens, and pasting nested to depth n makes one token of 2^n characters
     * while the count stays tiny, so a few hundred bytes of source could otherwise
     * build a token as large as the heap. A sketch is at most 64 K characters, so no
     * honest token comes near this.
     */
    static final int MAX_TOKEN_CHARS = 65536;

    private void tokenTooLong(Token at) {
        diags.error(at.file, at.line + (at.file == currentFile ? lineDelta : 0), at.col,
                "token built by '#' or '##' is longer than " + MAX_TOKEN_CHARS + " characters");
        throw new Diagnostics.Abort();
    }

    private static boolean isDirectiveStart(Input in) {
        return in.peek().is("#");
    }

    private List<Token> subst(Macro m, List<List<Token>> args, Token use) {
        return subst(m, m.body, args, use, new HashMap<>());
    }

    private List<Token> subst(Macro m, List<Token> body, List<List<Token>> args, Token use, Map<Integer, List<Token>> expanded) {
        List<Token> os = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            Token b = body.get(i);
            int pi;
            if (m.variadic && b.is("__VA_OPT__") && i + 1 < body.size() && body.get(i + 1).is("(")) {
                // C++20 __VA_OPT__(x): x when the variable arguments are not empty, else nothing.
                // GCC accepts it in every language mode.
                int depth = 0, j = i + 1;
                for (; j < body.size(); j++) {
                    if (body.get(j).is("("))
                        depth++;
                    else if (body.get(j).is(")") && --depth == 0)
                        break;
                }
                if (j >= body.size()) {
                    diags.error(use, "unterminated __VA_OPT__");
                    return os;
                }
                if (!args.get(args.size() - 1).isEmpty())
                    os.addAll(subst(m, body.subList(i + 2, j), args, use, expanded));
                i = j;
                continue;
            }
            if (b.is("#") && i + 1 < body.size() && (pi = m.params.indexOf(body.get(i + 1).text)) >= 0) {
                Token s = stringize(args.get(pi), use);
                s.space = b.space;
                os.add(s);
                i++;
                continue;
            }
            if (b.is("##") && i + 1 < body.size()) {
                Token next = body.get(i + 1);
                pi = m.params.indexOf(next.text);
                if (pi >= 0) {
                    List<Token> arg = args.get(pi);
                    boolean gnuComma = m.variadic && pi == m.params.size() - 1 && !os.isEmpty() && os.get(os.size() - 1).is(",");
                    if (arg.isEmpty()) {
                        // GNU: ", ## __VA_ARGS__" with nothing passed drops the comma.
                        if (gnuComma)
                            os.remove(os.size() - 1);
                    } else if (gnuComma) {
                        // ... and with something passed, pastes nothing: the comma stays.
                        for (Token a : arg)
                            os.add(a.at(use));
                    } else if (os.isEmpty()) {
                        for (Token a : arg)
                            os.add(a.at(use));
                    } else {
                        os.set(os.size() - 1, paste(os.get(os.size() - 1), arg.get(0), use));
                        for (int k = 1; k < arg.size(); k++)
                            os.add(arg.get(k).at(use));
                    }
                } else if (!os.isEmpty()) {
                    os.set(os.size() - 1, paste(os.get(os.size() - 1), next, use));
                } else {
                    os.add(next.at(use));
                }
                i++;
                continue;
            }
            pi = b.kind == Token.Kind.IDENT ? m.params.indexOf(b.text) : -1;
            if (pi >= 0) {
                boolean pastedAfter = i + 1 < body.size() && body.get(i + 1).is("##");
                List<Token> arg;
                if (pastedAfter) {
                    arg = args.get(pi);
                } else {
                    arg = expanded.get(pi);
                    if (arg == null) {
                        arg = expandList(args.get(pi));
                        expanded.put(pi, arg);
                    }
                }
                boolean first = true;
                for (Token a : arg) {
                    Token c = a.at(use);
                    if (first)
                        c.space = b.space;
                    first = false;
                    os.add(c);
                }
                continue;
            }
            Token c = b.at(use);
            if (os.isEmpty() && i == 0)
                c.space = use.space;
            os.add(c);
        }
        return os;
    }

    private Token stringize(List<Token> arg, Token use) {
        StringBuilder sb = new StringBuilder("\"");
        boolean first = true;
        for (Token a : arg) {
            if (sb.length() + 2L * a.text.length() + 2 > MAX_TOKEN_CHARS)
                tokenTooLong(use);
            if (!first && a.space)
                sb.append(' ');
            first = false;
            if (a.kind == Token.Kind.STRING || a.kind == Token.Kind.CHAR) {
                for (char c : a.text.toCharArray()) {
                    if (c == '"' || c == '\\')
                        sb.append('\\');
                    sb.append(c);
                }
            } else {
                sb.append(a.text);
            }
        }
        sb.append('"');
        Token t = new Token(Token.Kind.STRING, sb.toString(), use.file, use.line, use.col);
        t.fromMacro = true;
        return t;
    }

    private Token paste(Token l, Token r, Token use) {
        if ((long) l.text.length() + r.text.length() > MAX_TOKEN_CHARS)
            tokenTooLong(use);
        String text = l.text + r.text;
        Diagnostics quiet = new Diagnostics();
        List<Token> toks = Lexer.lex(text, use.file, quiet);
        if (toks.size() != 2 || quiet.hasErrors()) {
            diags.error(use, "pasting \"" + l.text + "\" and \"" + r.text + "\" does not give a valid preprocessing token");
            return l;
        }
        Token t = toks.get(0);
        Token res = new Token(t.kind, t.text, use.file, use.line, use.col);
        res.space = l.space;
        res.hide = l.hide;
        res.fromMacro = true;
        return res;
    }

    private Token dynamic(Macro m, Token t) {
        Token.Kind k = Token.Kind.NUMBER;
        String text;
        switch (m.name) {
            case "__LINE__" -> text = String.valueOf(t.line + (t.file == currentFile ? lineDelta : 0));
            case "__FILE__" -> {
                k = Token.Kind.STRING;
                text = t.file == Token.SKETCH ? "\"sketch.ino\"" : "\"Arduino.h\"";
            }
            case "__COUNTER__" -> text = String.valueOf(counter++);
            case "__DATE__" -> {
                k = Token.Kind.STRING;
                text = "\"Jan  1 2026\"";
            }
            default -> {
                k = Token.Kind.STRING;
                text = "\"00:00:00\"";
            }
        }
        Token r = new Token(k, text, t.file, t.line, t.col);
        r.space = t.space;
        r.fromMacro = true;
        return r;
    }

    // ---- hide sets ----
    //
    // Hide sets are immutable and interned: equal sets are one object, and each
    // operation is memoised on the identities of its operands. Expansion then makes a
    // handful of distinct sets however many tokens it produces; building a fresh set
    // per expansion (or per token) costs memory in proportion to expansion depth times
    // output, which a recursive macro chain turns into gigabytes before any budget trips.

    private static final Set<String> NONE = Set.of();
    private final Map<Set<String>, Set<String>> canon = new HashMap<>();
    private final Map<Set<String>, Map<String, Set<String>>> withMemo = new java.util.IdentityHashMap<>();
    private final Map<Set<String>, Map<Set<String>, Set<String>>> unionMemo = new java.util.IdentityHashMap<>();
    private final Map<Set<String>, Map<Set<String>, Set<String>>> interMemo = new java.util.IdentityHashMap<>();

    private Set<String> intern(Set<String> s) {
        Set<String> c = canon.putIfAbsent(s, s);
        return c != null ? c : s;
    }

    private Set<String> with(Set<String> s, String name) {
        return withMemo.computeIfAbsent(s == null ? NONE : s, k -> new HashMap<>()).computeIfAbsent(name, n -> {
            Set<String> r = s == null ? new HashSet<>() : new HashSet<>(s);
            r.add(n);
            return intern(java.util.Collections.unmodifiableSet(r));
        });
    }

    private Set<String> union(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty())
            return b;
        if (b == null || b.isEmpty() || a == b)
            return a;
        return unionMemo.computeIfAbsent(a, k -> new java.util.IdentityHashMap<>()).computeIfAbsent(b, k -> {
            Set<String> r = new HashSet<>(a);
            r.addAll(b);
            return intern(java.util.Collections.unmodifiableSet(r));
        });
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        if (a == null || b == null)
            return null;
        if (a == b)
            return a;
        return interMemo.computeIfAbsent(a, k -> new java.util.IdentityHashMap<>()).computeIfAbsent(b, k -> {
            Set<String> r = new HashSet<>(a);
            r.retainAll(b);
            return intern(java.util.Collections.unmodifiableSet(r));
        });
    }
}
