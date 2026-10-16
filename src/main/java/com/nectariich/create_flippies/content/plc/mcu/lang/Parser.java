package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.lang.Ast.*;

/**
 * Recursive-descent parser for the C++ a sketch is written in.
 *
 * C++ cannot be parsed without knowing which names are types ({@code a * b;} is a
 * declaration if {@code a} is a type and a multiplication otherwise), so the parser
 * tracks typedef, struct, union and enum names through its own scopes. It knows
 * nothing else about meaning: types, overloads and conversions are {@link Sema}'s.
 *
 * Where C++'s grammar is ambiguous the parser decides as g++ does: a statement that
 * can be a declaration is one; {@code T x();} declares a function; {@code (T)} followed
 * by an operand is a cast when {@code T} parses as a type-id. A parenthesised
 * expression that might be a cast is tried as a type-id first, without reporting
 * anything, and re-read as an expression if that fails.
 */
final class Parser {

    /** Thrown inside a speculative parse to abandon it; never escapes {@link #trial}. */
    private static final class Backtrack extends RuntimeException {
        Backtrack() {
            super(null, null, false, false);
        }
    }

    /** Raised after a reported syntax error to unwind to the next statement or declaration. */
    private static final class SyntaxError extends RuntimeException {
        SyntaxError() {
            super(null, null, false, false);
        }
    }

    private static final Set<String> TYPE_KEYWORDS = Set.of(
            "void", "bool", "char", "short", "int", "long", "signed", "unsigned", "float", "double",
            "struct", "union", "class", "enum", "auto", "const", "volatile", "decltype", "typename",
            "__signed__", "__unsigned__", "_Bool", "wchar_t", "char16_t", "char32_t");

    private static final Set<String> STORAGE_KEYWORDS = Set.of(
            "static", "extern", "register", "inline", "constexpr", "typedef", "mutable", "volatile",
            "__inline", "__inline__", "virtual", "explicit", "thread_local", "__extension__", "alignas", "_Alignas");

    private final List<Token> toks;
    private int pos;
    private final Diagnostics diags;
    private final ArrayDeque<Map<String, Boolean>> scopes = new ArrayDeque<>();
    private int trialDepth;

    /**
     * How deeply brackets, braces and statements may nest. GCC has no limit of its
     * own; this one is far beyond anything written by hand or by a generator (an
     * else-if chain does not count: each {@code else if} continues its chain rather
     * than nesting), and keeps a pathological input to a clean diagnostic instead of
     * running the compiler thread out of stack. The message is clang's.
     */
    static final int MAX_NESTING = 4096;
    private int nesting;

    /** {@code #pragma pack}: the value in effect (0 for none) and the pushed ones. */
    private int pack;
    private final ArrayDeque<Integer> packStack = new ArrayDeque<>();

    Parser(List<Token> toks, Diagnostics diags) {
        this.toks = toks;
        this.diags = diags;
        Map<String, Boolean> global = new HashMap<>();
        global.put("String", true);
        global.put("StringSumHelper", true);
        scopes.push(global);
    }

    /**
     * Parses one builtin's C prototype, with {@code typeNames} known as types. Returns
     * null and leaves the reason in {@code diags} if it is not a function declaration.
     */
    static FuncDecl parsePrototype(String text, java.util.Collection<String> typeNames, Diagnostics diags) {
        List<Token> toks = Lexer.lex(text + ";", Token.INTERNAL, diags);
        Parser p = new Parser(toks, diags);
        for (String n : typeNames)
            p.declareName(n, true);
        List<Node> items = new ArrayList<>();
        try {
            p.declaration(items, true);
        } catch (SyntaxError e) {
            return null;
        }
        for (Node n : items)
            if (n instanceof FuncDecl f)
                return f;
        return null;
    }

    // ================= token helpers =================

    private Token peek() {
        return toks.get(pos);
    }

    private Token peek(int k) {
        int i = Math.min(pos + k, toks.size() - 1);
        return toks.get(i);
    }

    private Token next() {
        Token t = toks.get(pos);
        if (t.kind != Token.Kind.EOF)
            pos++;
        return t;
    }

    private boolean is(String s) {
        Token t = peek();
        return (t.kind == Token.Kind.PUNCT || t.kind == Token.Kind.IDENT) && t.text.equals(s);
    }

    private boolean isAt(int k, String s) {
        Token t = peek(k);
        return (t.kind == Token.Kind.PUNCT || t.kind == Token.Kind.IDENT) && t.text.equals(s);
    }

    private boolean accept(String s) {
        if (is(s)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expect(String s) {
        if (is(s))
            return next();
        Token t = peek();
        fail(t, "expected '" + s + "' before " + describe(t));
        return t;
    }

    private static String describe(Token t) {
        if (t.kind == Token.Kind.EOF)
            return "end of input";
        if (t.kind == Token.Kind.NUMBER)
            return "numeric constant";
        if (t.kind == Token.Kind.STRING)
            return "string constant";
        return "'" + t.text + "' token";
    }

    private RuntimeException fail(Token t, String msg) {
        if (trialDepth > 0)
            throw new Backtrack();
        diags.error(t, msg);
        throw new SyntaxError();
    }

    private String ident() {
        Token t = peek();
        if (t.kind != Token.Kind.IDENT || isKeyword(t.text))
            throw fail(t, "expected identifier before " + describe(t));
        pos++;
        return t.text;
    }

    private static boolean isKeyword(String s) {
        return TYPE_KEYWORDS.contains(s) || STORAGE_KEYWORDS.contains(s) || switch (s) {
            case "if", "else", "while", "do", "for", "switch", "case", "default", "break", "continue", "return",
                    "goto", "sizeof", "new", "delete", "true", "false", "nullptr", "this", "operator", "template",
                    "namespace", "using", "static_cast", "reinterpret_cast", "const_cast", "dynamic_cast",
                    "static_assert", "alignof", "public", "private", "protected", "friend", "throw", "try", "catch" -> true;
            default -> false;
        };
    }

    // ================= scopes of type names =================

    private void pushScope() {
        scopes.push(new HashMap<>());
    }

    private void popScope() {
        scopes.pop();
    }

    private void declareName(String name, boolean isType) {
        scopes.peek().put(name, isType);
    }

    private boolean isTypeName(String name) {
        for (Map<String, Boolean> s : scopes) {
            Boolean b = s.get(name);
            if (b != null)
                return b;
        }
        return false;
    }

    private boolean isTypeNameToken(Token t) {
        return t.kind == Token.Kind.IDENT && isTypeName(t.text);
    }

    /** Whether the tokens at {@code k} start a declaration specifier. */
    private boolean startsDeclSpec(int k) {
        Token t = peek(k);
        if (t.kind != Token.Kind.IDENT)
            return false;
        if (TYPE_KEYWORDS.contains(t.text) || STORAGE_KEYWORDS.contains(t.text))
            return true;
        if (t.text.equals("std") && isAt(k + 1, "::"))
            return isTypeNameToken(peek(k + 2));
        if (t.text.equals("__attribute__") || t.text.equals("PROGMEM"))
            return true;
        return isTypeName(t.text);
    }

    // ================= translation unit =================

    List<Node> translationUnit() {
        List<Node> items = new ArrayList<>();
        while (peek().kind != Token.Kind.EOF) {
            int start = pos;
            try {
                topLevel(items);
            } catch (SyntaxError e) {
                recoverTopLevel(start);
            }
        }
        return items;
    }

    private void recoverTopLevel(int start) {
        if (pos == start)
            pos++;
        int depth = 0;
        while (peek().kind != Token.Kind.EOF) {
            Token t = next();
            if (t.is("{"))
                depth++;
            else if (t.is("}")) {
                depth--;
                if (depth <= 0) {
                    accept(";");
                    return;
                }
            } else if (t.is(";") && depth == 0) {
                return;
            }
        }
    }

    private void topLevel(List<Node> items) {
        if (accept(";"))
            return;
        if (pragmaPack())
            return;
        Token t = peek();
        if (t.is("extern") && peek(1).kind == Token.Kind.STRING) {
            next();
            next();
            if (accept("{")) {
                while (!is("}") && peek().kind != Token.Kind.EOF)
                    topLevel(items);
                expect("}");
            } else {
                topLevel(items);
            }
            return;
        }
        if (t.is("template"))
            throw fail(t, "templates are not supported by this PLC's compiler");
        if (t.is("namespace")) {
            next();
            if (peek().kind == Token.Kind.IDENT)
                next();
            if (accept("{")) {
                while (!is("}") && peek().kind != Token.Kind.EOF)
                    topLevel(items);
                expect("}");
                return;
            }
            throw fail(peek(), "expected '{' after namespace name");
        }
        if (t.is("using")) {
            usingDecl(items);
            return;
        }
        if (t.is("static_assert")) {
            items.add(staticAssert());
            return;
        }
        declaration(items, true);
    }

    private void usingDecl(List<Node> out) {
        Token u = next();
        if (accept("namespace")) {
            ident();
            while (accept("::"))
                ident();
            expect(";");
            return;
        }
        if (peek().kind == Token.Kind.IDENT && isAt(1, "=")) {
            String name = ident();
            expect("=");
            DeclSpec ds = declSpecs();
            Declarator d = declarator(ds.type, false, true);
            expect(";");
            VarDecl v = new VarDecl().at(u);
            v.name = name;
            v.type = d.type;
            v.isTypedef = true;
            declareName(name, true);
            out.add(v);
            return;
        }
        // using std::foo; — names are global here anyway.
        while (!is(";") && peek().kind != Token.Kind.EOF)
            next();
        expect(";");
    }

    private StaticAssert staticAssert() {
        Token t = next();
        expect("(");
        StaticAssert s = new StaticAssert().at(t);
        s.cond = assignment();
        if (accept(",")) {
            Token m = next();
            if (m.kind != Token.Kind.STRING)
                throw fail(m, "expected string-literal before " + describe(m));
            StringBuilder sb = new StringBuilder(literalText(m));
            while (peek().kind == Token.Kind.STRING)
                sb.append(literalText(next()));
            s.message = sb.toString();
        }
        expect(")");
        expect(";");
        return s;
    }

    private String literalText(Token t) {
        byte[] b = Literals.parseString(t.text);
        if (b == null)
            throw fail(t, "malformed string literal");
        return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // ================= declarations =================

    /** What a run of {@code __attribute__((...))}, {@code [[...]]} and {@code alignas(...)} said. */
    static final class Attrs {
        boolean packed;
        boolean weak;
        /** aligned(N) / alignas(N): the N, unchecked. */
        Expr aligned;
        /** {@code aligned} with no argument. */
        boolean alignDefault;
        /** {@code section(".noinit")} (or a ".noinit.*" subsection) or {@code noinit}. */
        boolean noinit;
    }

    static final class DeclSpec {
        TypeNode type;
        final Attrs attrs = new Attrs();
        boolean isStatic, isExtern, isTypedef, isInline, isConstexpr;
        /** The type was a struct/enum definition: a declaration with no declarators is fine. */
        boolean definesTag;
        Node tagDecl;
        Token start;
    }

    static final class Declarator {
        String name;
        Token nameTok;
        TypeNode type;
        /** Attributes written before the name ({@code int __attribute__((aligned(4))) x}). */
        final Attrs attrs = new Attrs();
    }

    /**
     * A declaration at file scope ({@code topLevel}) or block scope, appended to
     * {@code out} as VarDecl / FuncDecl / StructDecl / EnumDecl nodes.
     */
    private void declaration(List<Node> out, boolean topLevel) {
        DeclSpec ds = declSpecs();
        if (ds.tagDecl != null)
            out.add(ds.tagDecl);
        if (accept(";")) {
            if (!ds.definesTag && ds.tagDecl == null)
                diags.warning(ds.start.file, ds.start.line, ds.start.col, "declaration does not declare anything");
            return;
        }
        boolean first = true;
        while (true) {
            Declarator d = declarator(ds.type, true, false);
            if (d.name == null)
                throw fail(peek(), "expected unqualified-id before " + describe(peek()));
            if (d.type.k == TypeNode.K.FUNC && !ds.isTypedef) {
                FuncDecl f = new FuncDecl().at(d.nameTok);
                f.name = d.name;
                f.type = d.type;
                f.isStatic = ds.isStatic;
                f.isInline = ds.isInline;
                f.isConstexpr = ds.isConstexpr;
                declareName(d.name, false);
                Attrs fa = new Attrs();
                attributes(fa);
                f.isWeak = ds.attrs.weak || d.attrs.weak || fa.weak;
                if (first && is("{")) {
                    if (!topLevel)
                        throw fail(peek(), "a function-definition is not allowed here before '{' token");
                    f.body = functionBody(d.type);
                    out.add(f);
                    return;
                }
                out.add(f);
            } else {
                VarDecl v = new VarDecl().at(d.nameTok);
                v.name = d.name;
                v.type = d.type;
                v.isStatic = ds.isStatic;
                v.isExtern = ds.isExtern;
                v.isConstexpr = ds.isConstexpr;
                v.isTypedef = ds.isTypedef;
                Attrs va = new Attrs();
                attributes(va);
                v.alignAttr = va.aligned != null ? va.aligned : d.attrs.aligned != null ? d.attrs.aligned : ds.attrs.aligned;
                v.noinit = va.noinit || d.attrs.noinit || ds.attrs.noinit;
                if (!ds.isTypedef) {
                    // The name is in scope in its own initialiser, as in C++.
                    declareName(d.name, false);
                    if (accept("=")) {
                        v.init = is("{") ? initList() : assignment();
                    } else if (is("{")) {
                        v.init = initList();
                    } else if (is("(")) {
                        Token p = next();
                        v.ctorArgs = new ArrayList<>();
                        if (!is(")")) {
                            do {
                                v.ctorArgs.add(assignment());
                            } while (accept(","));
                        }
                        expect(")");
                        if (v.ctorArgs.isEmpty())
                            throw fail(p, "expected expression before ')' token");
                    }
                } else {
                    declareName(d.name, true);
                }
                out.add(v);
            }
            first = false;
            if (accept(","))
                continue;
            expect(";");
            return;
        }
    }

    private Block functionBody(TypeNode fnType) {
        pushScope();
        for (Param p : fnType.params)
            if (p.name != null)
                declareName(p.name, false);
        Block b = block();
        popScope();
        return b;
    }

    private void skipAttributes() {
        attributes(null);
    }

    /**
     * Reads any run of GNU {@code __attribute__((...))}, C++11 {@code [[...]]} and
     * {@code alignas(...)}, recording into {@code into} (if not null) the ones that
     * change what the program does here: {@code packed}, {@code aligned(N)} and
     * {@code weak}. Others ({@code unused}, {@code always_inline}, {@code section},
     * ...) change nothing a sketch can observe on this target and are read past.
     */
    private void attributes(Attrs into) {
        while (true) {
            if (is("__attribute__") || is("__attribute")) {
                next();
                expect("(");
                expect("(");
                attributeList(into, ")");
                expect(")");
                expect(")");
            } else if (is("PROGMEM") || is("__extension__")) {
                next();
            } else if (is("[") && isAt(1, "[")) {
                next();
                next();
                attributeList(into, "]");
                expect("]");
                expect("]");
            } else if (is("alignas") || is("_Alignas")) {
                Token t = next();
                Expr n = alignasArgument(t);
                if (into != null && n != null)
                    into.aligned = n;
            } else {
                return;
            }
        }
    }

    /** The comma-separated attributes inside {@code __attribute__((...))} or {@code [[...]]}, up to {@code close}. */
    private void attributeList(Attrs into, String close) {
        while (!is(close)) {
            Token t = peek();
            if (t.kind == Token.Kind.EOF)
                throw fail(t, "expected '" + close + "' before end of input");
            if (accept(","))
                continue;
            String name = next().text;
            if (accept("::"))
                name = next().text; // gnu::packed
            if (name.startsWith("__") && name.endsWith("__") && name.length() > 4)
                name = name.substring(2, name.length() - 2);
            Expr arg = null;
            String section = null;
            if (is("(")) {
                if (name.equals("section") && peek(1).kind == Token.Kind.STRING) {
                    next();
                    byte[] bytes = Literals.parseString(next().text);
                    section = bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    expect(")");
                } else if (name.equals("aligned")) {
                    next();
                    arg = conditional();
                    expect(")");
                } else {
                    skipBalanced();
                }
            }
            if (into == null)
                continue;
            switch (name) {
                case "packed" -> into.packed = true;
                case "weak" -> into.weak = true;
                case "noinit" -> into.noinit = true;
                case "section" -> {
                    if (section != null && (section.equals(".noinit") || section.startsWith(".noinit.")))
                        into.noinit = true;
                }
                case "aligned" -> {
                    if (arg != null)
                        into.aligned = arg;
                    else
                        into.alignDefault = true;
                }
                default -> { }
            }
        }
    }

    /** {@code alignas(N)} or {@code alignas(type)}, after the keyword: the alignment as an expression. */
    private Expr alignasArgument(Token kw) {
        expect("(");
        Expr n;
        TypeNode tn = startsDeclSpec(0) ? trial(() -> {
            TypeNode tt = typeId();
            if (!is(")"))
                throw new Backtrack();
            return tt;
        }) : null;
        if (tn != null) {
            SizeofType s = new SizeofType().at(kw);
            s.typeNode = tn;
            s.alignof = true;
            n = s;
        } else {
            n = conditional();
        }
        expect(")");
        return n;
    }

    /**
     * {@code #pragma pack}, which the preprocessor hands on as one token (see
     * {@link Preprocessor#PRAGMA_PACK}): {@code pack(n)}, {@code pack()},
     * {@code pack(push[, n])} and {@code pack(pop)}, as GCC reads them.
     */
    private boolean pragmaPack() {
        Token t = peek();
        if (t.kind != Token.Kind.IDENT || !t.text.startsWith(Preprocessor.PRAGMA_PACK))
            return false;
        next();
        String body = t.text.substring(Preprocessor.PRAGMA_PACK.length());
        if (body.endsWith(")"))
            body = body.substring(0, body.length() - 1);
        List<String> args = new ArrayList<>();
        for (String a : body.split(","))
            if (!a.isBlank())
                args.add(a.trim());
        int value = -1;
        boolean push = false, pop = false;
        for (String a : args) {
            if (a.equals("push")) {
                push = true;
            } else if (a.equals("pop")) {
                pop = true;
            } else if (a.equals("show")) {
                diags.warning(t.file, t.line, t.col, "value of #pragma pack(show) == " + (pack == 0 ? 8 : pack));
            } else {
                Literals.IntValue iv = Literals.parseInt(a);
                if (iv == null) {
                    if (!push && !pop)
                        diags.warning(t.file, t.line, t.col, "malformed '#pragma pack' - ignored");
                    continue; // an identifier label, as in pack(push, label, 1)
                }
                long n = iv.value();
                if (n != 1 && n != 2 && n != 4 && n != 8 && n != 16) {
                    diags.warning(t.file, t.line, t.col, "alignment must be a small power of two, not " + n);
                    return true;
                }
                value = (int) n;
            }
        }
        if (push)
            packStack.push(pack);
        if (pop) {
            if (packStack.isEmpty())
                diags.warning(t.file, t.line, t.col, "#pragma pack (pop) encountered without matching #pragma pack (push)");
            else
                pack = packStack.pop();
        }
        if (value >= 0)
            pack = value;
        else if (!push && !pop)
            pack = 0; // pack(): back to the default
        return true;
    }

    private void skipBalanced() {
        Token open = peek();
        String o = open.text;
        String c = o.equals("(") ? ")" : o.equals("[") ? "]" : "}";
        if (!is(o))
            throw fail(open, "expected '('");
        int depth = 0;
        do {
            Token t = next();
            if (t.is(o))
                depth++;
            else if (t.is(c))
                depth--;
            if (t.kind == Token.Kind.EOF)
                throw fail(t, "unbalanced " + o);
        } while (depth > 0);
    }

    private DeclSpec declSpecs() {
        DeclSpec ds = new DeclSpec();
        ds.start = peek();
        int nVoid = 0, nBool = 0, nChar = 0, nShort = 0, nInt = 0, nLong = 0, nSigned = 0, nUnsigned = 0,
                nFloat = 0, nDouble = 0;
        boolean cConst = false, cVolatile = false, any = false;
        TypeNode named = null;
        while (true) {
            Token t = peek();
            if (is("[") && isAt(1, "[")) {
                attributes(ds.attrs); // [[maybe_unused]] int x;
                continue;
            }
            if (t.kind != Token.Kind.IDENT)
                break;
            String s = t.text;
            switch (s) {
                case "static" -> ds.isStatic = true;
                case "extern" -> ds.isExtern = true;
                case "typedef" -> ds.isTypedef = true;
                case "inline", "__inline", "__inline__" -> ds.isInline = true;
                case "constexpr" -> {
                    ds.isConstexpr = true;
                    cConst = true;
                }
                case "register", "mutable", "thread_local", "__extension__", "typename", "PROGMEM" -> { }
                case "virtual", "explicit", "friend" -> throw fail(t, "'" + s + "' is only allowed in class member declarations, which this PLC's compiler does not support");
                case "const" -> cConst = true;
                case "volatile" -> cVolatile = true;
                case "void" -> nVoid++;
                case "bool", "_Bool" -> nBool++;
                case "char" -> nChar++;
                case "short" -> nShort++;
                case "int" -> nInt++;
                case "long" -> nLong++;
                case "signed", "__signed__" -> nSigned++;
                case "unsigned", "__unsigned__" -> nUnsigned++;
                case "float" -> nFloat++;
                case "double" -> nDouble++;
                case "wchar_t", "char32_t" -> {
                    nInt++;
                    nUnsigned += s.equals("char32_t") ? 1 : 0;
                }
                case "char16_t" -> {
                    nShort++;
                    nUnsigned++;
                }
                case "__attribute__", "__attribute", "alignas", "_Alignas" -> {
                    attributes(ds.attrs);
                    continue;
                }
                case "struct", "union", "class" -> {
                    if (named != null || any)
                        throw fail(t, "two or more data types in declaration specifiers");
                    named = structSpec(ds);
                    any = true;
                    continue;
                }
                case "enum" -> {
                    if (named != null || any)
                        throw fail(t, "two or more data types in declaration specifiers");
                    named = enumSpec(ds);
                    any = true;
                    continue;
                }
                case "auto" -> {
                    if (named != null)
                        throw fail(t, "two or more data types in declaration specifiers");
                    named = new TypeNode(TypeNode.K.AUTO).at(t);
                }
                case "decltype" -> {
                    next();
                    expect("(");
                    TypeNode d = new TypeNode(TypeNode.K.DECLTYPE).at(t);
                    d.declExpr = expression();
                    expect(")");
                    named = d;
                    any = true;
                    continue;
                }
                default -> {
                    if (s.equals("std") && isAt(1, "::") && isTypeNameToken(peek(2))) {
                        next();
                        next();
                        continue;
                    }
                    if (named == null && !any && isTypeName(s)) {
                        named = new TypeNode(TypeNode.K.NAMED).at(t);
                        named.name = s;
                        any = true;
                        break;
                    }
                    return finishSpec(ds, named, nVoid, nBool, nChar, nShort, nInt, nLong, nSigned, nUnsigned, nFloat, nDouble, cConst, cVolatile);
                }
            }
            if (s.equals("void") || s.equals("bool") || s.equals("_Bool") || s.equals("char") || s.equals("short") || s.equals("int")
                    || s.equals("long") || s.equals("signed") || s.equals("unsigned") || s.equals("float") || s.equals("double")
                    || s.equals("__signed__") || s.equals("__unsigned__") || s.equals("wchar_t") || s.equals("char16_t") || s.equals("char32_t")) {
                if (named != null && named.k != TypeNode.K.AUTO)
                    throw fail(t, "two or more data types in declaration specifiers");
                any = true;
            }
            next();
        }
        return finishSpec(ds, named, nVoid, nBool, nChar, nShort, nInt, nLong, nSigned, nUnsigned, nFloat, nDouble, cConst, cVolatile);
    }

    private DeclSpec finishSpec(DeclSpec ds, TypeNode named, int nVoid, int nBool, int nChar, int nShort, int nInt,
            int nLong, int nSigned, int nUnsigned, int nFloat, int nDouble, boolean cConst, boolean cVolatile) {
        boolean prim = nVoid + nBool + nChar + nShort + nInt + nLong + nSigned + nUnsigned + nFloat + nDouble > 0;
        TypeNode t;
        if (named != null && !(named.k == TypeNode.K.AUTO && prim)) {
            t = named;
        } else if (prim) {
            CType c = primitive(ds.start, nVoid, nBool, nChar, nShort, nInt, nLong, nSigned, nUnsigned, nFloat, nDouble);
            t = TypeNode.prim(c).at(ds.start);
        } else {
            if (ds.isTypedef || ds.isStatic || ds.isExtern || cConst || cVolatile) {
                // "const x = 1;": C's implicit int, an error in C++.
                throw fail(peek(), "'" + peek().text + "' does not name a type");
            }
            throw fail(peek(), describeUnknownType(peek()));
        }
        if (cConst || cVolatile) {
            TypeNode q = new TypeNode(t.k);
            q.become(t);
            q.at(t);
            q.isConst |= cConst;
            q.isVolatile |= cVolatile;
            t = q;
        }
        ds.type = t;
        return ds;
    }

    private String describeUnknownType(Token t) {
        if (t.kind == Token.Kind.IDENT)
            return "'" + t.text + "' does not name a type";
        return "expected unqualified-id before " + describe(t);
    }

    private CType primitive(Token at, int nVoid, int nBool, int nChar, int nShort, int nInt, int nLong, int nSigned,
            int nUnsigned, int nFloat, int nDouble) {
        if (nSigned > 0 && nUnsigned > 0)
            throw fail(at, "'signed' and 'unsigned' specified together");
        if (nVoid > 0)
            return CType.VOID;
        if (nBool > 0)
            return CType.BOOL;
        if (nFloat > 0)
            return CType.FLOAT;
        if (nDouble > 0)
            return nLong > 0 ? CType.LDOUBLE : CType.DOUBLE;
        if (nChar > 0)
            return nSigned > 0 ? CType.SCHAR : nUnsigned > 0 ? CType.UCHAR : CType.CHAR;
        if (nShort > 0)
            return nUnsigned > 0 ? CType.USHORT : CType.SHORT;
        if (nLong >= 2)
            return nUnsigned > 0 ? CType.ULLONG : CType.LLONG;
        if (nLong == 1)
            return nUnsigned > 0 ? CType.ULONG : CType.LONG;
        return nUnsigned > 0 ? CType.UINT : CType.INT;
    }

    private TypeNode structSpec(DeclSpec ds) {
        Token kw = next();
        Attrs ta = new Attrs();
        attributes(ta);
        StructDecl sd = new StructDecl().at(kw);
        sd.isUnion = kw.text.equals("union");
        sd.isClass = kw.text.equals("class");
        if (peek().kind == Token.Kind.IDENT && !is("{")) {
            Token nt = next();
            sd.tag = nt.text;
            sd.at(nt);
        }
        if (is(":"))
            throw fail(peek(), "inheritance is not supported by this PLC's compiler");
        if (accept("{")) {
            if (sd.tag != null)
                declareName(sd.tag, true);
            sd.members = new ArrayList<>();
            boolean isPrivate = sd.isClass;
            while (!accept("}")) {
                if (peek().kind == Token.Kind.EOF)
                    throw fail(peek(), "expected '}' at end of input");
                if ((is("public") || is("private") || is("protected")) && isAt(1, ":")) {
                    isPrivate = !next().text.equals("public");
                    next();
                    continue;
                }
                if (accept(";"))
                    continue;
                if (pragmaPack())
                    continue;
                if (is("static_assert")) {
                    staticAssert();
                    continue;
                }
                if (sd.tag != null && is(sd.tag) && isAt(1, "("))
                    throw fail(peek(), "constructors are not supported by this PLC's compiler: use a plain struct and an initialiser");
                if (is("~"))
                    throw fail(peek(), "destructors are not supported by this PLC's compiler");
                memberDecl(sd);
            }
            sd.pack = pack;
            // struct S { ... } __attribute__((packed)): the type's attributes may follow the brace.
            if (is("__attribute__") || is("__attribute"))
                attributes(ta);
            ds.definesTag = true;
        } else if (sd.tag == null) {
            throw fail(peek(), "expected '{' before " + describe(peek()));
        } else if (!isTypeName(sd.tag)) {
            // A forward declaration, or an elaborated first use: introduces the name.
            declareName(sd.tag, true);
        }
        sd.packed |= ta.packed;
        if (ta.aligned != null)
            sd.alignAttr = ta.aligned;
        sd.alignDefault |= ta.alignDefault;
        ds.tagDecl = sd;
        TypeNode t = new TypeNode(TypeNode.K.STRUCT).at(kw);
        t.structDecl = sd;
        return t;
    }

    private void memberDecl(StructDecl sd) {
        DeclSpec ds = declSpecs();
        if (ds.tagDecl != null && ds.tagDecl != sd) {
            // A nested struct or enum definition: hoisted; its name is visible outside, as in C.
            pendingNested.add(ds.tagDecl);
        }
        if (accept(";")) {
            // An anonymous struct or union: one unnamed member whose members are the outer type's.
            if (ds.type.k == TypeNode.K.STRUCT && ds.type.structDecl.tag == null && ds.type.structDecl.members != null) {
                MemberDecl m = new MemberDecl().at(ds.type.structDecl);
                m.type = ds.type;
                sd.members.add(m);
            }
            return;
        }
        if (ds.isStatic)
            throw fail(ds.start, "static data members are not supported by this PLC's compiler");
        while (true) {
            Declarator d;
            if (is(":")) {
                d = new Declarator();
                d.type = ds.type;
                d.nameTok = peek();
            } else {
                d = declarator(ds.type, false, false);
            }
            if (d.type.k == TypeNode.K.FUNC || is("{"))
                throw fail(d.nameTok != null ? d.nameTok : peek(), "member functions are not supported by this PLC's compiler: use a plain function taking the struct by pointer or reference");
            MemberDecl m = new MemberDecl().at(d.nameTok != null ? d.nameTok : peek());
            m.name = d.name;
            m.type = d.type;
            Attrs ma = new Attrs();
            attributes(ma);
            if (accept(":"))
                m.bitWidth = conditional();
            attributes(ma);
            m.packed = ds.attrs.packed || d.attrs.packed || ma.packed;
            m.alignAttr = ma.aligned != null ? ma.aligned : d.attrs.aligned != null ? d.attrs.aligned : ds.attrs.aligned;
            m.alignDefault = ds.attrs.alignDefault || d.attrs.alignDefault || ma.alignDefault;
            if (accept("="))
                m.defaultInit = is("{") ? initList() : conditional();
            else if (is("{"))
                m.defaultInit = initList();
            sd.members.add(m);
            if (accept(","))
                continue;
            expect(";");
            return;
        }
    }

    /** Struct/enum definitions nested inside a struct, to be declared at file scope too. */
    final List<Node> pendingNested = new ArrayList<>();

    private TypeNode enumSpec(DeclSpec ds) {
        Token kw = next();
        EnumDecl ed = new EnumDecl().at(kw);
        if (is("class") || is("struct")) {
            next();
            ed.scoped = true;
        }
        skipAttributes();
        if (peek().kind == Token.Kind.IDENT && !is("{")) {
            Token nt = next();
            ed.tag = nt.text;
            ed.at(nt);
        }
        if (accept(":")) {
            DeclSpec u = declSpecs();
            ed.underlying = u.type;
        }
        if (accept("{")) {
            if (ed.tag != null)
                declareName(ed.tag, true);
            ed.items = new ArrayList<>();
            while (!accept("}")) {
                Token nt = peek();
                Enumerator e = new Enumerator().at(nt);
                e.name = ident();
                skipAttributes();
                if (accept("="))
                    e.value = conditional();
                ed.items.add(e);
                if (!ed.scoped)
                    declareName(e.name, false);
                if (!accept(",")) {
                    expect("}");
                    break;
                }
            }
            ds.definesTag = true;
        } else if (ed.tag == null) {
            throw fail(peek(), "expected '{' before " + describe(peek()));
        } else if (ed.underlying != null || ed.scoped) {
            declareName(ed.tag, true);
        }
        ds.tagDecl = ed;
        TypeNode t = new TypeNode(TypeNode.K.ENUM).at(kw);
        t.enumDecl = ed;
        return t;
    }

    /**
     * A declarator applied to {@code base}. With {@code named} the name is required
     * (a parameter's may still be absent); with {@code inParams} a parenthesised suffix
     * is always a parameter list.
     */
    private Declarator declarator(TypeNode base, boolean named, boolean abstractOk) {
        Attrs lead = new Attrs();
        attributes(lead);
        Declarator res = declarator0(base, named, abstractOk);
        res.attrs.packed |= lead.packed;
        res.attrs.noinit |= lead.noinit;
        res.attrs.weak |= lead.weak;
        res.attrs.alignDefault |= lead.alignDefault;
        if (lead.aligned != null && res.attrs.aligned == null)
            res.attrs.aligned = lead.aligned;
        return res;
    }

    private Declarator declarator0(TypeNode base, boolean named, boolean abstractOk) {
        Token at = peek();
        if (accept("*")) {
            TypeNode p = TypeNode.derived(TypeNode.K.PTR, base).at(at);
            while (true) {
                if (accept("const"))
                    p.isConst = true;
                else if (accept("volatile"))
                    p.isVolatile = true;
                else if (is("__restrict") || is("__restrict__") || is("restrict"))
                    next();
                else
                    break;
            }
            return declarator(p, named, abstractOk);
        }
        if (is("&") || is("&&")) {
            Token r = next();
            TypeNode p = TypeNode.derived(TypeNode.K.REF, base).at(at);
            p.rvalueRef = r.text.equals("&&");
            return declarator(p, named, abstractOk);
        }
        Declarator d = new Declarator();
        // "(name)": a parenthesised declarator. In a parameter list too, where an
        // abstract declarator could not start with a name that is no type:
        // "int x(int(y))" declares a function whose parameter is y.
        if (is("(") && (isAt(1, "*") || isAt(1, "&") || isAt(1, "&&") || isAt(1, "(")
                || (peek(1).kind == Token.Kind.IDENT && !startsDeclSpec(1) && !isKeyword(peek(1).text) && (!abstractOk || inParams > 0)))) {
            next();
            TypeNode hole = new TypeNode(TypeNode.K.PLACEHOLDER);
            Declarator inner = declarator(hole, named, abstractOk);
            expect(")");
            TypeNode outer = suffixes(base, inner.name != null);
            hole.become(outer);
            return inner;
        }
        if (peek().kind == Token.Kind.IDENT && !isKeyword(peek().text) && !(abstractOk && isTypeName(peek().text))) {
            d.nameTok = next();
            d.name = d.nameTok.text;
            if (is("::"))
                throw fail(peek(), "qualified names are not supported in declarations here");
        } else {
            d.nameTok = at;
        }
        d.type = suffixes(base, d.name != null);
        return d;
    }

    private TypeNode suffixes(TypeNode base, boolean hasName) {
        Token at = peek();
        if (is("[")) {
            if (isAt(1, "["))
                return base;
            next();
            Expr size = null;
            if (!is("]"))
                size = expression();
            expect("]");
            TypeNode elem = suffixes(base, hasName);
            TypeNode a = TypeNode.derived(TypeNode.K.ARRAY, elem).at(at);
            a.arraySize = size;
            return a;
        }
        if (is("(") && hasName && looksLikeParams() && !isAt(1, ")") && !isAt(1, "...")) {
            // "T x(U(...))" is a function declaration only if the parentheses hold a
            // parameter-declaration-clause ([dcl.ambig.res]); "String b(String("y"))"
            // and "int x(int(3.5))" cannot be one (a literal is no declarator), so they
            // are direct-initialisations, as g++ reads them. Try the parameter list
            // without reporting anything; if it fails and the parentheses read as
            // expressions, leave them to the declaration as constructor arguments.
            // Otherwise parse the parameters again for real, for their diagnostics.
            int save = pos;
            TypeNode f = trial(() -> parameterList(base));
            if (f != null)
                return f;
            Boolean asArgs = trial(() -> {
                next();
                do {
                    assignment();
                } while (accept(","));
                expect(")");
                return Boolean.TRUE;
            });
            pos = save;
            if (asArgs != null)
                return base;
        }
        if (is("(") && (!hasName || looksLikeParams()))
            return parameterList(base);
        return base;
    }

    /** {@code ( parameter-declaration-clause )} and what follows it, applied to {@code base}. */
    /** Parameter lists being parsed: inside one, "(name)" is a parenthesised declarator. */
    private int inParams;

    private TypeNode parameterList(TypeNode base) {
        Token at = peek();
        next();
        TypeNode f = new TypeNode(TypeNode.K.FUNC).at(at);
        f.params = new ArrayList<>();
        pushScope();
        inParams++;
        try {
            if (is("void") && isAt(1, ")")) {
                next();
            } else if (!is(")")) {
                while (true) {
                    if (accept("...")) {
                        f.variadic = true;
                        break;
                    }
                    Token pt = peek();
                    DeclSpec ds = declSpecs();
                    Declarator pd = declarator(ds.type, false, true);
                    Param p = new Param().at(pd.nameTok != null ? pd.nameTok : pt);
                    p.type = pd.type;
                    p.name = pd.name;
                    if (p.name != null)
                        declareName(p.name, false);
                    if (accept("="))
                        p.defaultValue = assignment();
                    f.params.add(p);
                    if (accept(","))
                        continue;
                    break;
                }
            }
        } finally {
            inParams--;
            popScope();
        }
        expect(")");
        while (is("const") || is("noexcept") || is("override"))
            next();
        TypeNode ret;
        if (accept("->")) {
            DeclSpec rs = declSpecs();
            ret = declarator(rs.type, false, true).type;
        } else {
            ret = suffixes(base, false);
        }
        f.inner = ret;
        return f;
    }

    /** After a declarator's name, whether {@code (} opens a parameter list rather than constructor arguments. */
    private boolean looksLikeParams() {
        if (isAt(1, ")"))
            return true;
        if (isAt(1, "..."))
            return true;
        return startsDeclSpec(1);
    }

    /** A type-id, as in a cast, sizeof or new. */
    private TypeNode typeId() {
        DeclSpec ds = declSpecs();
        if (ds.isStatic || ds.isExtern || ds.isTypedef)
            throw fail(ds.start, "storage class specified in a type name");
        Declarator d = declarator(ds.type, false, true);
        if (d.name != null)
            throw fail(d.nameTok, "expected ')' before '" + d.name + "'");
        return d.type;
    }

    // ================= statements =================

    private Block block() {
        Token open = expect("{");
        Block b = new Block().at(open);
        pushScope();
        try {
            while (!is("}")) {
                if (peek().kind == Token.Kind.EOF)
                    throw fail(peek(), "expected '}' at end of input");
                int start = pos;
                try {
                    if (pragmaPack())
                        continue;
                    b.items.add(statement());
                } catch (SyntaxError e) {
                    recoverStatement(start);
                }
            }
            b.endLine = peek().line;
            b.endCol = peek().col;
            next();
        } finally {
            popScope();
        }
        return b;
    }

    /** Counts one more level of nesting at {@code t}; past {@link #MAX_NESTING} the parse stops there. */
    private void deeper(Token t) {
        if (++nesting > MAX_NESTING) {
            nesting = Integer.MIN_VALUE / 2; // report once, and let the unwinding finish
            diags.error(t, "bracket nesting level exceeded maximum of " + MAX_NESTING);
            throw new Diagnostics.Abort();
        }
    }

    private void recoverStatement(int start) {
        if (pos == start)
            pos++;
        int depth = 0;
        while (peek().kind != Token.Kind.EOF) {
            Token t = peek();
            if (t.is("}") && depth == 0)
                return;
            next();
            if (t.is("{"))
                depth++;
            else if (t.is("}"))
                depth--;
            else if (t.is(";") && depth == 0)
                return;
        }
    }

    private Stmt statement() {
        deeper(peek());
        try {
            return statement0();
        } finally {
            nesting--;
        }
    }

    private Stmt statement0() {
        Token t = peek();
        if (t.kind == Token.Kind.IDENT) {
            switch (t.text) {
                case "if": {
                    next();
                    If s = new If().at(t);
                    expect("(");
                    pushScope();
                    try {
                        if (startsDeclaration()) {
                            DeclStmt ds = new DeclStmt().at(peek());
                            conditionDecl(ds);
                            if (accept(";")) {
                                s.init = ds;
                                s.cond = expression();
                            } else {
                                s.init = ds;
                                VarDecl v = (VarDecl) ds.decls.get(ds.decls.size() - 1);
                                Ident id = new Ident().at(v);
                                id.name = v.name;
                                s.cond = id;
                            }
                        } else {
                            s.cond = expression();
                        }
                        expect(")");
                        s.then = scopedStatement();
                        if (accept("else")) {
                            // "else if" continues this chain; it is not one level deeper.
                            boolean chain = is("if");
                            if (chain)
                                nesting--;
                            try {
                                s.otherwise = scopedStatement();
                            } finally {
                                if (chain)
                                    nesting++;
                            }
                        }
                    } finally {
                        popScope();
                    }
                    return s;
                }
                case "while": {
                    next();
                    While s = new While().at(t);
                    expect("(");
                    pushScope();
                    try {
                        if (startsDeclaration()) {
                            // "while (T z = e) body": z is declared afresh on every
                            // iteration ([stmt.while]/2), so it is lowered to
                            // "while (true) { if (T z = e) body else break; }".
                            DeclStmt ds = new DeclStmt().at(peek());
                            If test = conditionIf(ds);
                            expect(")");
                            test.then = scopedStatement();
                            test.otherwise = new Break().at(t);
                            s.cond = new IntLit(1, CType.BOOL).at(t);
                            Block b = new Block().at(t);
                            b.items.add(test);
                            b.endLine = t.line;
                            b.endCol = t.col;
                            s.body = b;
                            return s;
                        }
                        s.cond = expression();
                        expect(")");
                        s.body = scopedStatement();
                    } finally {
                        popScope();
                    }
                    return s;
                }
                case "do": {
                    next();
                    DoWhile s = new DoWhile().at(t);
                    s.body = scopedStatement();
                    expect("while");
                    expect("(");
                    s.cond = expression();
                    expect(")");
                    expect(";");
                    return s;
                }
                case "for":
                    return forStatement();
                case "switch": {
                    next();
                    Switch s = new Switch().at(t);
                    expect("(");
                    pushScope();
                    try {
                        // C++17 "switch (init; cond)" and "switch (T v = e)": the
                        // declarations are scoped to the switch, so it becomes
                        // "{ init; switch (cond) body }".
                        Block outer = null;
                        if (startsDeclaration()) {
                            outer = new Block().at(t);
                            DeclStmt ds = new DeclStmt().at(peek());
                            conditionDecl(ds);
                            outer.items.add(ds);
                            if (accept(";")) {
                                s.cond = expression();
                            } else {
                                VarDecl v = (VarDecl) ds.decls.get(ds.decls.size() - 1);
                                Ident id = new Ident().at(v);
                                id.name = v.name;
                                s.cond = id;
                            }
                        } else {
                            Expr e = expression();
                            if (accept(";")) {
                                outer = new Block().at(t);
                                ExprStmt es = new ExprStmt().at(e);
                                es.expr = e;
                                outer.items.add(es);
                                if (startsDeclaration()) {
                                    DeclStmt ds = new DeclStmt().at(peek());
                                    conditionDecl(ds);
                                    outer.items.add(ds);
                                    VarDecl v = (VarDecl) ds.decls.get(ds.decls.size() - 1);
                                    Ident id = new Ident().at(v);
                                    id.name = v.name;
                                    s.cond = id;
                                } else {
                                    s.cond = expression();
                                }
                            } else {
                                s.cond = e;
                            }
                        }
                        expect(")");
                        s.body = scopedStatement();
                        if (outer != null) {
                            outer.items.add(s);
                            outer.endLine = t.line;
                            outer.endCol = t.col;
                            return outer;
                        }
                    } finally {
                        popScope();
                    }
                    return s;
                }
                case "case": {
                    next();
                    Case c = new Case().at(t);
                    c.value = conditional();
                    if (accept("..."))
                        rangeHi.put(c, conditional()); // GNU case range, lowered by Sema
                    expect(":");
                    c.body = is("}") ? new Empty().at(peek()) : statement();
                    return c;
                }
                case "default": {
                    next();
                    Case c = new Case().at(t);
                    expect(":");
                    c.body = is("}") ? new Empty().at(peek()) : statement();
                    return c;
                }
                case "break":
                    next();
                    expect(";");
                    return new Break().at(t);
                case "continue":
                    next();
                    expect(";");
                    return new Continue().at(t);
                case "return": {
                    next();
                    Return r = new Return().at(t);
                    if (!is(";"))
                        r.value = is("{") ? initList() : expression();
                    expect(";");
                    return r;
                }
                case "goto": {
                    next();
                    Goto g = new Goto().at(t);
                    g.label = ident();
                    expect(";");
                    return g;
                }
                case "static_assert": {
                    DeclStmt ds = new DeclStmt().at(t);
                    ds.decls.add(staticAssert());
                    return ds;
                }
                case "using": {
                    DeclStmt ds = new DeclStmt().at(t);
                    usingDecl(ds.decls);
                    return ds;
                }
                case "try", "throw", "catch":
                    throw fail(t, "exception handling is disabled (-fno-exceptions)");
                default:
                    if (isAt(1, ":") && !isKeyword(t.text)) {
                        next();
                        next();
                        Labeled l = new Labeled().at(t);
                        l.label = t.text;
                        l.body = is("}") ? new Empty().at(peek()) : statement();
                        return l;
                    }
            }
        }
        if (is("{"))
            return block();
        if (accept(";"))
            return new Empty().at(t);
        if (startsDeclaration()) {
            DeclStmt ds = new DeclStmt().at(t);
            declaration(ds.decls, false);
            if (!pendingNested.isEmpty()) {
                ds.decls.addAll(0, pendingNested);
                pendingNested.clear();
            }
            return ds;
        }
        ExprStmt es = new ExprStmt().at(t);
        es.expr = expression();
        expect(";");
        return es;
    }

    /** GNU {@code case lo ... hi:} upper bounds, by case node. */
    final Map<Case, Expr> rangeHi = new HashMap<>();

    private void conditionDecl(DeclStmt ds) {
        DeclSpec spec = declSpecs();
        Declarator d = declarator(spec.type, true, false);
        VarDecl v = new VarDecl().at(d.nameTok);
        v.name = d.name;
        v.type = d.type;
        declareName(d.name, false);
        if (accept("="))
            v.init = assignment();
        else if (is("{"))
            v.init = initList();
        else
            throw fail(peek(), "expected initializer before " + describe(peek()));
        ds.decls.add(v);
    }

    /**
     * {@code T z = e} as a condition, after {@code (}: an {@code if} whose init is the
     * declaration and whose condition is the variable.
     */
    private If conditionIf(DeclStmt ds) {
        If test = new If().at(peek());
        conditionDecl(ds);
        test.init = ds;
        VarDecl v = (VarDecl) ds.decls.get(ds.decls.size() - 1);
        Ident id = new Ident().at(v);
        id.name = v.name;
        test.cond = id;
        return test;
    }

    private Stmt scopedStatement() {
        pushScope();
        try {
            return statement();
        } finally {
            popScope();
        }
    }

    private Stmt forStatement() {
        Token t = next();
        For f = new For().at(t);
        expect("(");
        pushScope();
        try {
            if (accept(";")) {
                f.init = null;
            } else if (startsDeclaration()) {
                // Range-for: "for (T x : arr)".
                int save = pos;
                DeclSpec ds = declSpecs();
                Declarator d = declarator(ds.type, true, false);
                if (d.name != null && accept(":")) {
                    VarDecl v = new VarDecl().at(d.nameTok);
                    v.name = d.name;
                    v.type = d.type;
                    declareName(d.name, false);
                    f.rangeVar = v;
                    f.range = is("{") ? initList() : expression();
                    expect(")");
                    f.body = scopedStatement();
                    return f;
                }
                pos = save;
                DeclStmt init = new DeclStmt().at(peek());
                declaration(init.decls, false);
                f.init = init;
            } else {
                ExprStmt es = new ExprStmt().at(peek());
                es.expr = expression();
                expect(";");
                f.init = es;
            }
            If condDecl = null;
            if (!is(";")) {
                if (startsDeclaration())
                    condDecl = conditionIf(new DeclStmt().at(peek()));
                else
                    f.cond = expression();
            }
            expect(";");
            if (!is(")"))
                f.step = expression();
            expect(")");
            if (condDecl != null) {
                // "for (init; T z = e; step) body": as for while, z is made afresh each
                // time round, as "for (init; ; step) { if (T z = e) body else break; }".
                // (The step cannot see z here; in C++ it could.)
                condDecl.then = scopedStatement();
                condDecl.otherwise = new Break().at(t);
                Block b = new Block().at(t);
                b.items.add(condDecl);
                b.endLine = t.line;
                b.endCol = t.col;
                f.body = b;
                return f;
            }
            f.body = scopedStatement();
            return f;
        } finally {
            popScope();
        }
    }

    /** Whether the statement here is a declaration (C++ [stmt.ambig]: if it can be, it is). */
    private boolean startsDeclaration() {
        Token t = peek();
        if (is("[") && isAt(1, "["))
            return true;
        if (t.kind != Token.Kind.IDENT)
            return false;
        if (TYPE_KEYWORDS.contains(t.text) && !t.text.equals("decltype"))
            return true;
        if (STORAGE_KEYWORDS.contains(t.text) || t.text.equals("typedef"))
            return true;
        if (t.text.equals("decltype") || t.text.equals("__attribute__"))
            return true;
        int k = 0;
        if (t.text.equals("std") && isAt(1, "::"))
            k = 2;
        if (!isTypeNameToken(peek(k)))
            return false;
        Token n = peek(k + 1);
        if (n.kind == Token.Kind.IDENT)
            return !isKeyword(n.text) || n.text.equals("const") || n.text.equals("volatile");
        if (n.is("*") || n.is("&") || n.is("&&")) {
            // "T * x" declares; "T * 3" cannot.
            Token a = peek(k + 2);
            return a.kind == Token.Kind.IDENT || a.is("*") || a.is("&") || a.is("(");
        }
        if (n.is("(")) {
            // "T (*fp)(int)" declares; "T(x)" is a functional cast.
            return isAt(k + 2, "*") || isAt(k + 2, "&");
        }
        return false;
    }

    // ================= expressions =================

    Expr expression() {
        Expr e = assignment();
        while (is(",")) {
            Token op = next();
            Binary b = new Binary().at(op);
            b.op = ",";
            b.l = e;
            b.r = assignment();
            e = b;
        }
        return e;
    }

    private static final Set<String> ASSIGN_OPS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=");

    Expr assignment() {
        Expr l = conditional();
        Token t = peek();
        if (t.kind == Token.Kind.PUNCT && ASSIGN_OPS.contains(t.text)) {
            next();
            Assign a = new Assign().at(t);
            a.op = t.text;
            a.l = l;
            a.r = is("{") ? initList() : assignment();
            return a;
        }
        return l;
    }

    Expr conditional() {
        Expr c = binary(0);
        if (is("?")) {
            Token q = next();
            Cond e = new Cond().at(q);
            e.c = c;
            e.a = expression();
            expect(":");
            e.b = assignment();
            return e;
        }
        return c;
    }

    private static final String[][] LEVELS = {
            { "||" }, { "&&" }, { "|" }, { "^" }, { "&" }, { "==", "!=" }, { "<", ">", "<=", ">=" },
            { "<<", ">>" }, { "+", "-" }, { "*", "/", "%" } };

    private Expr binary(int level) {
        if (level == LEVELS.length)
            return castExpr();
        Expr l = binary(level + 1);
        outer:
        while (true) {
            Token t = peek();
            if (t.kind == Token.Kind.PUNCT) {
                for (String op : LEVELS[level]) {
                    if (t.text.equals(op)) {
                        next();
                        Binary b = new Binary().at(t);
                        b.op = op;
                        b.l = l;
                        b.r = binary(level + 1);
                        l = b;
                        continue outer;
                    }
                }
            } else if (t.kind == Token.Kind.IDENT) {
                String alt = switch (t.text) {
                    case "and" -> "&&";
                    case "or" -> "||";
                    case "bitand" -> "&";
                    case "bitor" -> "|";
                    case "xor" -> "^";
                    case "not_eq" -> "!=";
                    default -> null;
                };
                if (alt != null) {
                    for (String op : LEVELS[level]) {
                        if (alt.equals(op)) {
                            next();
                            Binary b = new Binary().at(t);
                            b.op = op;
                            b.l = l;
                            b.r = binary(level + 1);
                            l = b;
                            continue outer;
                        }
                    }
                }
            }
            return l;
        }
    }

    private Expr castExpr() {
        if (is("(") && startsDeclSpec(1)) {
            int save = pos;
            Token open = peek();
            TypeNode tn = trial(() -> {
                next();
                TypeNode tt = typeId();
                if (!is(")"))
                    throw new Backtrack();
                next();
                return tt;
            });
            if (tn != null) {
                if (is("{")) {
                    // A compound literal "(T){...}": a GNU extension in C++, handled as T{...}.
                    Construct c = new Construct().at(open);
                    c.typeNode = tn;
                    c.braces = true;
                    InitList il = initList();
                    c.args.addAll(il.items);
                    return postfixOps(c);
                }
                Cast c = new Cast().at(open);
                c.typeNode = tn;
                c.style = Cast.C;
                c.e = castExpr();
                return c;
            }
            pos = save;
        }
        return unary();
    }

    private <T> T trial(java.util.function.Supplier<T> f) {
        int save = pos;
        trialDepth++;
        try {
            return f.get();
        } catch (Backtrack | SyntaxError e) {
            pos = save;
            return null;
        } finally {
            trialDepth--;
        }
    }

    private Expr unary() {
        Token t = peek();
        if (t.kind == Token.Kind.PUNCT) {
            switch (t.text) {
                case "++", "--": {
                    next();
                    Unary u = new Unary().at(t);
                    u.op = t.text + "x";
                    u.e = castExpr();
                    return u;
                }
                case "-", "+", "!", "~", "*", "&": {
                    next();
                    Unary u = new Unary().at(t);
                    u.op = t.text;
                    u.e = castExpr();
                    return u;
                }
                case "&&":
                    throw fail(t, "labels as values ('&&label') are not supported");
                default:
                    break;
            }
        } else if (t.kind == Token.Kind.IDENT) {
            switch (t.text) {
                case "not": {
                    next();
                    Unary u = new Unary().at(t);
                    u.op = "!";
                    u.e = castExpr();
                    return u;
                }
                case "compl": {
                    next();
                    Unary u = new Unary().at(t);
                    u.op = "~";
                    u.e = castExpr();
                    return u;
                }
                case "sizeof", "alignof", "__alignof__", "_Alignof": {
                    next();
                    boolean align = !t.text.equals("sizeof");
                    if (is("...")) {
                        throw fail(t, "variadic templates are not supported");
                    }
                    if (is("(") && startsDeclSpec(1)) {
                        TypeNode tn = trial(() -> {
                            next();
                            TypeNode tt = typeId();
                            if (!is(")"))
                                throw new Backtrack();
                            next();
                            return tt;
                        });
                        if (tn != null) {
                            SizeofType s = new SizeofType().at(t);
                            s.typeNode = tn;
                            s.alignof = align;
                            return s;
                        }
                    }
                    SizeofExpr s = new SizeofExpr().at(t);
                    s.e = unary();
                    if (align) {
                        // alignof(expr) is a GNU extension; same as alignof(decltype(expr)).
                        TypeNode d = new TypeNode(TypeNode.K.DECLTYPE).at(t);
                        d.declExpr = s.e;
                        SizeofType st = new SizeofType().at(t);
                        st.typeNode = d;
                        st.alignof = true;
                        return st;
                    }
                    return s;
                }
                case "new":
                    return newExpr();
                case "delete": {
                    next();
                    Delete d = new Delete().at(t);
                    if (is("[") && isAt(1, "]")) {
                        next();
                        next();
                        d.array = true;
                    }
                    d.e = castExpr();
                    return d;
                }
                default:
                    break;
            }
        }
        return postfix();
    }

    private Expr newExpr() {
        Token t = next();
        New n = new New().at(t);
        if (is("(")) {
            if (startsDeclSpec(1)) {
                next();
                n.typeNode = typeId();
                expect(")");
            } else {
                throw fail(peek(), "placement new is not supported");
            }
        } else {
            DeclSpec ds = declSpecs();
            // new T*[n], new T[n]: a restricted declarator with no parentheses.
            TypeNode type = ds.type;
            while (is("*")) {
                Token s = next();
                type = TypeNode.derived(TypeNode.K.PTR, type).at(s);
            }
            if (accept("[")) {
                n.count = expression();
                expect("]");
                List<TypeNode> dims = new ArrayList<>();
                while (is("[")) {
                    Token s = next();
                    TypeNode a = new TypeNode(TypeNode.K.ARRAY).at(s);
                    a.arraySize = expression();
                    expect("]");
                    dims.add(a);
                }
                // new T[n][a][b] allocates n elements of type T[a][b].
                for (int i = dims.size() - 1; i >= 0; i--) {
                    dims.get(i).inner = type;
                    type = dims.get(i);
                }
            }
            n.typeNode = type;
        }
        if (accept("(")) {
            n.args = new ArrayList<>();
            if (!is(")")) {
                do {
                    n.args.add(assignment());
                } while (accept(","));
            }
            expect(")");
        } else if (is("{")) {
            InitList il = initList();
            n.args = new ArrayList<>(il.items);
            n.braces = true;
        }
        return n;
    }

    private Expr postfix() {
        Expr e = primary();
        return postfixOps(e);
    }

    private Expr postfixOps(Expr e) {
        while (true) {
            Token t = peek();
            if (t.is("[")) {
                next();
                Index ix = new Index().at(t);
                ix.a = e;
                ix.i = expression();
                expect("]");
                e = ix;
            } else if (t.is("(")) {
                next();
                Call c = new Call().at(e);
                c.fn = e;
                if (!is(")")) {
                    do {
                        c.args.add(is("{") ? initList() : assignment());
                    } while (accept(","));
                }
                expect(")");
                e = c;
            } else if (t.is(".") || t.is("->")) {
                next();
                Ast.Member m = new Ast.Member().at(t);
                m.obj = e;
                m.arrow = t.text.equals("->");
                if (is("~"))
                    throw fail(peek(), "explicit destructor calls are not supported");
                Token nt = peek();
                m.name = ident();
                m.at(nt);
                e = m;
            } else if (t.is("++") || t.is("--")) {
                next();
                Unary u = new Unary().at(t);
                u.op = "x" + t.text;
                u.e = e;
                e = u;
            } else {
                return e;
            }
        }
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind) {
            case NUMBER: {
                next();
                if (Literals.isFloat(t.text)) {
                    Literals.FloatValue fv = Literals.parseFloat(t.text);
                    if (fv == null)
                        throw fail(t, "invalid floating constant '" + t.text + "'");
                    return new FloatLit(fv.value(), fv.type()).at(t);
                }
                Literals.IntValue iv = Literals.parseInt(t.text);
                if (iv == null)
                    throw fail(t, "invalid suffix or digits in integer constant '" + t.text + "'");
                if (iv.tooLarge())
                    diags.error(t, "integer constant is too large for its type");
                else if (iv.type() == CType.ULLONG && Literals.parseInt(t.text).value() < 0 && !t.text.toLowerCase().contains("u")
                        && !t.text.toLowerCase().startsWith("0x"))
                    diags.warning(t.file, t.line, t.col, "integer constant is so large that it is unsigned");
                return new IntLit(iv.value(), iv.type()).at(t);
            }
            case CHAR: {
                next();
                long[] v = Literals.parseChar(t.text);
                if (v == null)
                    throw fail(t, "empty character constant");
                return new IntLit(v[0], v[1] == 1 ? CType.CHAR : CType.INT).at(t);
            }
            case STRING: {
                StrLit s = new StrLit().at(t);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                while (peek().kind == Token.Kind.STRING) {
                    Token st = next();
                    byte[] b = Literals.parseString(st.text);
                    if (b == null)
                        throw fail(st, "malformed string literal");
                    bo.write(b, 0, b.length);
                }
                s.bytes = bo.toByteArray();
                return s;
            }
            case PUNCT: {
                if (t.is("(")) {
                    next();
                    deeper(t);
                    try {
                        Expr e = expression();
                        expect(")");
                        return e;
                    } finally {
                        nesting--;
                    }
                }
                if (t.is("["))
                    return lambda();
                if (t.is("::")) {
                    next();
                    Token nt = peek();
                    Ident id = new Ident().at(nt);
                    id.name = ident();
                    id.scope = "";
                    return id;
                }
                if (t.is("{"))
                    throw fail(t, "expected primary-expression before '{' token");
                throw fail(t, "expected primary-expression before " + describe(t));
            }
            case IDENT: {
                switch (t.text) {
                    case "true", "false":
                        next();
                        return new IntLit(t.text.equals("true") ? 1 : 0, CType.BOOL).at(t);
                    case "nullptr":
                        next();
                        return new NullPtr().at(t);
                    case "this":
                        throw fail(t, "invalid use of 'this' in non-member function");
                    case "static_cast", "reinterpret_cast", "const_cast", "dynamic_cast": {
                        next();
                        Cast c = new Cast().at(t);
                        c.style = switch (t.text) {
                            case "static_cast", "dynamic_cast" -> Cast.STATIC;
                            case "reinterpret_cast" -> Cast.REINTERPRET;
                            default -> Cast.CONST;
                        };
                        expect("<");
                        c.typeNode = typeIdInAngles();
                        expect(">");
                        expect("(");
                        c.e = expression();
                        expect(")");
                        return c;
                    }
                    default:
                        break;
                }
                if (startsDeclSpec(0) && !t.text.equals("const") && !t.text.equals("volatile")
                        && !STORAGE_KEYWORDS.contains(t.text)) {
                    // T(x), T{...}, unsigned int(x) is not C++ but GCC rejects it too.
                    if (isTypeNameToken(t) && isAt(1, "::"))
                        return scoped();
                    DeclSpec ds = declSpecs();
                    if (is("(")) {
                        Token open = next();
                        Construct c = new Construct().at(t);
                        c.typeNode = ds.type;
                        if (!is(")")) {
                            do {
                                c.args.add(assignment());
                            } while (accept(","));
                        }
                        expect(")");
                        if (c.args.size() == 1 && !(ds.type.k == TypeNode.K.NAMED && "String".equals(ds.type.name))) {
                            Cast cast = new Cast().at(t);
                            cast.typeNode = ds.type;
                            cast.style = Cast.FUNCTIONAL;
                            cast.e = c.args.get(0);
                            return cast;
                        }
                        return c;
                    }
                    if (is("{")) {
                        Construct c = new Construct().at(t);
                        c.typeNode = ds.type;
                        c.braces = true;
                        c.args.addAll(initList().items);
                        return c;
                    }
                    throw fail(peek(), "expected primary-expression before " + describe(peek()));
                }
                if (isAt(1, "::"))
                    return scoped();
                next();
                Ident id = new Ident().at(t);
                id.name = t.text;
                return id;
            }
            default:
                throw fail(t, "expected primary-expression before " + describe(t));
        }
    }

    private Expr scoped() {
        Token t = next();
        Ident id = new Ident().at(t);
        StringBuilder scope = new StringBuilder(t.text);
        expect("::");
        Token nt = peek();
        String name = ident();
        while (is("::")) {
            next();
            scope.append("::").append(name);
            nt = peek();
            name = ident();
        }
        id.scope = scope.toString();
        id.name = name;
        id.at(nt);
        return id;
    }

    /** The type inside {@code static_cast<...>}: '>' closes it, so no shifts inside. */
    private TypeNode typeIdInAngles() {
        return typeId();
    }

    private Expr lambda() {
        Token t = next();
        Lambda l = new Lambda().at(t);
        while (!is("]")) {
            Token c = next();
            if (c.kind == Token.Kind.EOF)
                throw fail(c, "expected ']'");
            if (!c.is("=") && !c.is("&") && !c.is(","))
                throw fail(c, "lambda captures are not supported by this PLC's compiler; use a global variable");
        }
        next();
        FuncDecl f = new FuncDecl().at(t);
        f.isLambda = true;
        f.name = "<lambda@" + t.line + ":" + t.col + ">";
        TypeNode ft = new TypeNode(TypeNode.K.FUNC).at(t);
        ft.params = new ArrayList<>();
        pushScope();
        try {
            if (accept("(")) {
                if (!is(")")) {
                    while (true) {
                        Token pt = peek();
                        DeclSpec ds = declSpecs();
                        Declarator pd = declarator(ds.type, false, true);
                        Param p = new Param().at(pd.nameTok != null ? pd.nameTok : pt);
                        p.type = pd.type;
                        p.name = pd.name;
                        if (p.name != null)
                            declareName(p.name, false);
                        ft.params.add(p);
                        if (!accept(","))
                            break;
                    }
                }
                expect(")");
            }
            while (is("mutable") || is("noexcept"))
                next();
            if (accept("->")) {
                DeclSpec rs = declSpecs();
                ft.inner = declarator(rs.type, false, true).type;
            } else {
                ft.inner = new TypeNode(TypeNode.K.AUTO).at(t);
            }
            f.type = ft;
            f.body = block();
        } finally {
            popScope();
        }
        l.fn = f;
        return l;
    }

    InitList initList() {
        Token open = expect("{");
        deeper(open);
        try {
            InitList il = new InitList().at(open);
            while (!is("}")) {
                if (is(".") && peek(1).kind == Token.Kind.IDENT) {
                    // GNU designated initialiser: ".x = 1" or ".x{1}".
                    Token dot = next();
                    Designated d = new Designated().at(dot);
                    d.field = next().text;
                    if (accept("="))
                        d.value = is("{") ? initList() : assignment();
                    else if (is("{"))
                        d.value = initList();
                    else
                        throw fail(peek(), "expected '=' before " + describe(peek()));
                    il.items.add(d);
                } else if (is("[") && !isAt(1, "]") && !isAt(1, "[")) {
                    throw fail(peek(), "array designators ('[n] = ...') are not supported in C++; list the elements in order");
                } else {
                    il.items.add(is("{") ? initList() : assignment());
                }
                if (!accept(","))
                    break;
            }
            expect("}");
            return il;
        } finally {
            nesting--;
        }
    }
}
