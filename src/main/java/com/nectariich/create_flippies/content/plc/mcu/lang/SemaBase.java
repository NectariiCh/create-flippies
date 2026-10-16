package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.lang.Ast.*;

/**
 * Scopes, type resolution, conversions and constant evaluation: the parts of the
 * semantic pass that expressions and declarations both lean on.
 *
 * Conversions follow C++ [conv] and [over.ics.rank] closely, because a sketch's
 * behaviour depends on them: the usual arithmetic conversions decide whether
 * {@code -1 < 1u} is true (it is not), integral promotion decides what
 * {@code (uint8_t)200 + (uint8_t)100} is (300, an int), and overload ranking
 * decides which {@code String} constructor a {@code char} picks.
 */
abstract class SemaBase {

    static final class Scope {
        final Scope parent;
        final Map<String, Object> names = new HashMap<>();
        /** The outermost scope of a function body (its parameters). */
        final boolean function;

        Scope(Scope parent, boolean function) {
            this.parent = parent;
            this.function = function;
        }
    }

    final Diagnostics d;
    final Scope global = new Scope(null, false);
    Scope cur = global;
    /** Top-level functions not yet declared, by name, so a sketch can call a function defined further down. */
    final Map<String, List<FuncDecl>> pending = new HashMap<>();
    final Map<FuncDecl, Boolean> declaring = new IdentityHashMap<>();

    /** Every .rodata/.data/.bss object, in definition order. */
    final List<Sym.Global> globals = new ArrayList<>();
    private final Map<String, Sym.Global> strings = new HashMap<>();
    private int stringCount;

    /** Temporaries created by the full-expression being checked; keyed by its root afterwards. */
    List<Temp> tempSink;
    final Map<Object, List<Temp>> temps = new IdentityHashMap<>();
    /** Inside sizeof/decltype: nothing is evaluated, so nothing is used or materialised. */
    int unevaluated;

    SemaBase(Diagnostics d) {
        this.d = d;
    }

    // ================= diagnostics =================

    void error(Node at, String msg) {
        d.error(at.file, at.line, at.col, msg);
    }

    void warning(Node at, String msg) {
        d.warning(at.file, at.line, at.col, msg);
    }

    void note(Node at, String msg) {
        d.note(at.file, at.line, at.col, msg);
    }

    // ================= scopes =================

    void push(boolean function) {
        cur = new Scope(cur, function);
    }

    void pop() {
        cur = cur.parent;
    }

    Object lookup(String name) {
        for (Scope s = cur; s != null; s = s.parent) {
            if (s == global)
                flushPending(name);
            Object o = s.names.get(name);
            if (o != null)
                return o;
        }
        return null;
    }

    Object lookupGlobal(String name) {
        flushPending(name);
        return global.names.get(name);
    }

    /** Declares every not-yet-seen top-level function called {@code name}: the .ino auto-prototypes. */
    void flushPending(String name) {
        List<FuncDecl> l = pending.remove(name);
        if (l == null)
            return;
        Scope save = cur;
        cur = global;
        try {
            for (FuncDecl fd : l)
                declareFunction(fd, true);
        } finally {
            cur = save;
        }
    }

    abstract Sym.Func declareFunction(FuncDecl fd, boolean prototypeOnly);

    void declare(Node at, String name, Object sym) {
        Object old = cur.names.get(name);
        if (old != null) {
            if (old instanceof Sym.TypeName ot && sym instanceof Sym.TypeName nt && ot.type.equals(nt.type))
                return;
            if (old instanceof Sym.TypeName && sym instanceof Sym.TypeName && at.file != Token.SKETCH) {
                // The core's headers may restate a typedef the compiler predefines; theirs wins.
                cur.names.put(name, sym);
                return;
            }
            if (old instanceof Sym.Var ov && sym instanceof Sym.Var nv && cur == global && (ov.isExtern || nv.isExtern)
                    && ov.type.sameUnqualified(nv.type))
                return;
            error(at, "redeclaration of '" + describeSym(name, sym) + "'");
            note(at, "previous declaration of '" + name + "'");
            return;
        }
        cur.names.put(name, sym);
    }

    static String describeSym(String name, Object sym) {
        if (sym instanceof Sym.Var v)
            return v.type.spell(name);
        return name;
    }

    // ================= literals =================

    /** The .rodata object for a string literal; identical literals share one, as -fmerge-constants does. */
    Sym.Global stringGlobal(byte[] bytes) {
        String key = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        Sym.Global g = strings.get(key);
        if (g == null) {
            byte[] data = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, data, 0, bytes.length);
            g = new Sym.Global(".LC" + stringCount++, data.length, 1, Sym.Global.Section.RODATA);
            g.data = data;
            strings.put(key, g);
            globals.add(g);
        }
        return g;
    }

    // ================= types =================

    static CType promote(CType t) {
        switch (t.kind) {
            case BOOL, CHAR, SCHAR, UCHAR, SHORT, USHORT:
                return CType.INT;
            case ENUM:
                if (t.enumInfo.scoped)
                    return t.unqualified();
                // [conv.prom]/3: an unscoped enum without a fixed type promotes to int when int holds every value.
                if (!t.enumInfo.fixed && fitsInt(t.enumInfo))
                    return CType.INT;
                return promote(t.base);
            default:
                return t.unqualified();
        }
    }

    private static boolean fitsInt(CType.EnumInfo e) {
        for (long v : e.values.values())
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
                return false;
        return true;
    }

    /** C++ [expr.arith.conv], on ARM sizes. */
    static CType usual(CType a, CType b) {
        a = promote(a);
        b = promote(b);
        if (a.kind == CType.Kind.LDOUBLE || b.kind == CType.Kind.LDOUBLE)
            return CType.LDOUBLE;
        if (a.kind == CType.Kind.DOUBLE || b.kind == CType.Kind.DOUBLE)
            return CType.DOUBLE;
        if (a.kind == CType.Kind.FLOAT || b.kind == CType.Kind.FLOAT)
            return CType.FLOAT;
        if (a.kind == b.kind)
            return a;
        boolean ua = a.isUnsigned(), ub = b.isUnsigned();
        if (ua == ub)
            return a.rank() >= b.rank() ? a : b;
        CType u = ua ? a : b, s = ua ? b : a;
        if (u.rank() >= s.rank())
            return u;
        if (s.size() > u.size())
            return s;
        return s.toUnsigned();
    }

    /** Parameter type adjustments: arrays and functions become pointers, top-level cv is dropped. */
    static CType adjustParam(CType t) {
        if (t.isArray())
            return CType.pointer(t.base);
        if (t.isFunction())
            return CType.pointer(t);
        return t.unqualified();
    }

    // ================= value categories =================

    /** An expression designating a String or struct that lives in a temporary (a prvalue of class type). */
    static boolean isTempClass(Expr e) {
        return (e instanceof TempRef || e instanceof StringSum || (e instanceof Call c && c.sret != null)
                || (e instanceof Intrinsic i && i.result != null));
    }

    static boolean isNullConstant(Expr e) {
        if (e instanceof NullPtr)
            return true;
        if (e.type != null && e.type.isInteger() && !(e.type.kind == CType.Kind.BOOL)) {
            Long v = intConst(e);
            return v != null && v == 0;
        }
        return false;
    }

    /** A string literal, possibly behind its decay to a pointer. */
    static boolean isStringLiteral(Expr e) {
        if (e instanceof Conv c && c.k == Conv.K.DECAY)
            e = c.e;
        return e instanceof StrLit s && !s.predefined;
    }

    // ================= constant evaluation =================

    /**
     * The value of a constant expression: an integer, a floating value, or an address
     * (a linker object or function plus a byte offset), which the linker resolves.
     */
    static final class ConstVal {
        static final int INT = 0, FLOAT = 1, ADDR = 2;
        final int kind;
        final long i;
        final double f;
        final Object sym;

        private ConstVal(int kind, long i, double f, Object sym) {
            this.kind = kind;
            this.i = i;
            this.f = f;
            this.sym = sym;
        }

        static ConstVal ofInt(long v) {
            return new ConstVal(INT, v, 0, null);
        }

        static ConstVal ofFloat(double v) {
            return new ConstVal(FLOAT, 0, v, null);
        }

        static ConstVal ofAddr(Object sym, long off) {
            return new ConstVal(ADDR, off, 0, sym);
        }
    }

    static Long intConst(Expr e) {
        ConstVal v = eval(e);
        return v != null && v.kind == ConstVal.INT ? v.i : null;
    }

    /** Wraps a value into the range of an integer type, as storing it into one does. */
    static long normalize(long v, CType t) {
        switch (t.kind) {
            case BOOL:
                return v != 0 ? 1 : 0;
            case CHAR, UCHAR:
                return v & 0xFF;
            case SCHAR:
                return (byte) v;
            case SHORT:
                return (short) v;
            case USHORT:
                return v & 0xFFFF;
            case INT, LONG:
                return (int) v;
            case UINT, ULONG, POINTER, NULLPTR:
                return v & 0xFFFFFFFFL;
            case ENUM:
                return normalize(v, t.base);
            default:
                return v;
        }
    }

    /** Float to integer as libgcc's __aeabi_f2iz/__aeabi_d2iz and friends do, saturating. */
    static long floatToInt(double f, CType t) {
        CType u = t.isEnum() ? t.base : t;
        switch (u.kind) {
            case BOOL:
                return f != 0 ? 1 : 0;
            case LLONG:
                return (long) f;
            case ULLONG: {
                if (!(f > 0))
                    return 0;
                if (f >= 18446744073709551616.0)
                    return -1L;
                if (f < 9223372036854775808.0)
                    return (long) f;
                return (long) (f - 9223372036854775808.0) + Long.MIN_VALUE;
            }
            default:
                if (u.isUnsigned()) {
                    long v;
                    if (!(f > 0))
                        v = 0;
                    else if (f >= 4294967296.0)
                        v = 0xFFFFFFFFL;
                    else
                        v = (long) f;
                    return normalize(v, u);
                }
                return normalize((int) f, u);
        }
    }

    static double intToFloat(long v, CType from, boolean toFloat) {
        double r;
        if (from.kind == CType.Kind.ULLONG && v < 0) {
            // Exact rounding of a 64-bit unsigned: halve keeping the sticky bit, convert, double.
            long half = (v >>> 1) | (v & 1);
            if (toFloat)
                return (float) half * 2.0f;
            r = (double) half * 2.0;
            return r;
        }
        if (toFloat)
            return (float) v;
        return (double) v;
    }

    /** Evaluates a checked expression if it is a constant expression; null otherwise. */
    static ConstVal eval(Expr e) {
        try {
            return evalImpl(e);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    // ---- constexpr functions ----
    //
    // A call to a constexpr function is a constant expression when its arguments are
    // ([expr.const]), which is what lets "int arr[fact(3)]" compile. The body is
    // interpreted over the checked tree: the C++11 form (return statements, if/else,
    // blocks, constant locals of the caller's making) is what sketches write, and
    // anything outside it simply makes the call not constant, as it is then for
    // folding purposes. Depth is capped as -fconstexpr-depth=512 does, and the work
    // per outermost evaluation by a step budget, so a deep recursion costs the
    // compiler a bounded amount; past either, the call is not folded (and, where a
    // constant is required, reported as not being one).

    private static final int CONSTEXPR_DEPTH = 512;
    private static final int CONSTEXPR_STEPS = 200_000;

    /** One active constexpr call: its parameters' values, depth, and the shared step budget. */
    private record Frame(Frame outer, java.util.Map<Sym.Var, ConstVal> vars, int depth, int[] steps) {}

    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    /** What a statement that completes without returning yields, distinct from every value. */
    private static final ConstVal NO_RETURN = new ConstVal(-1, 0, 0, null);

    private static ConstVal constexprCall(Call c) {
        Sym.Func f = c.target;
        Frame outer = FRAME.get();
        int depth = outer == null ? 0 : outer.depth + 1;
        if (depth > CONSTEXPR_DEPTH || f.body == null || c.args.size() != f.params.size())
            return null;
        int[] steps = outer == null ? new int[] { CONSTEXPR_STEPS } : outer.steps;
        java.util.Map<Sym.Var, ConstVal> vars = new java.util.IdentityHashMap<>();
        for (int i = 0; i < c.args.size(); i++) {
            Sym.Var p = f.params.get(i);
            if (p.isRef || p.byAddress)
                return null;
            ConstVal v = evalImpl(c.args.get(i));
            if (v == null || v.kind == ConstVal.ADDR)
                return null;
            vars.put(p, v);
        }
        FRAME.set(new Frame(outer, vars, depth, steps));
        try {
            ConstVal r = exec(f.body, steps);
            return r == NO_RETURN ? null : r;
        } finally {
            FRAME.set(outer);
        }
    }

    private static ConstVal exec(Stmt s, int[] steps) {
        if (--steps[0] < 0)
            return null;
        if (s instanceof Return r)
            return r.value == null ? null : evalImpl(r.value);
        if (s instanceof Block b) {
            for (Stmt x : b.items) {
                ConstVal v = exec(x, steps);
                if (v != NO_RETURN)
                    return v;
            }
            return NO_RETURN;
        }
        if (s instanceof If i) {
            if (i.init != null)
                return null;
            ConstVal c = evalImpl(i.cond);
            if (c == null)
                return null;
            if (truth(c))
                return exec(i.then, steps);
            return i.otherwise == null ? NO_RETURN : exec(i.otherwise, steps);
        }
        if (s instanceof Empty)
            return NO_RETURN;
        return null;
    }

    /**
     * {@link #evalImpl0} with a memo of "not a constant". SemaExpr folds as it checks,
     * asking at every operator whether it is now constant; without the memo a chain
     * of n unary operators over a variable costs O(n^2) (60 000 of them took seconds).
     * The memo is only written and read outside constexpr calls, where a parameter
     * has a value, and only for operators whose operands' answers cannot change
     * either: a call beneath may become constant once its constexpr body is checked.
     */
    private static ConstVal evalImpl(Expr e) {
        boolean top = FRAME.get() == null;
        if (top && e.notConst)
            return null;
        ConstVal v = evalImpl0(e);
        if (v == null && top && stableNull(e))
            e.notConst = true;
        return v;
    }

    /** Whether {@code e}'s evaluating to no constant (just now, outside a constexpr call) is final. */
    private static boolean stableNull(Expr e) {
        if (e instanceof Unary u)
            return settled(u.e);
        if (e instanceof Conv c)
            return c.k != Conv.K.DECAY && settled(c.e);
        if (e instanceof Binary b)
            return settled(b.l) && settled(b.r);
        if (e instanceof Cond c)
            return settled(c.c) && settled(c.a) && settled(c.b);
        return false;
    }

    /** An operand whose constness is fixed: a literal, a variable, or a memoised non-constant. */
    private static boolean settled(Expr e) {
        return e.notConst || e instanceof IntLit || e instanceof FloatLit || e instanceof VarRef || e instanceof NullPtr;
    }

    private static ConstVal evalImpl0(Expr e) {
        if (e instanceof IntLit l)
            return ConstVal.ofInt(normalize(l.value, l.type));
        if (e instanceof FloatLit f)
            return ConstVal.ofFloat(f.type.kind == CType.Kind.FLOAT ? (float) f.value : f.value);
        if (e instanceof NullPtr)
            return ConstVal.ofInt(0);
        if (e instanceof Call c)
            return c.target != null && c.target.constexprBody && c.sret == null ? constexprCall(c) : null;
        if (e instanceof VarRef v) {
            Sym.Var var = v.var;
            Frame fr = FRAME.get();
            if (fr != null) {
                // Inside a constexpr call: its parameters have the values it was called with.
                ConstVal p = fr.vars.get(var);
                if (p != null)
                    return p;
            }
            if (var.constValue instanceof Long l)
                return ConstVal.ofInt(l);
            if (var.constValue instanceof Double dd)
                return ConstVal.ofFloat(dd);
            return null;
        }
        if (e instanceof GlobalAddr g)
            return ConstVal.ofAddr(g.global, g.offset);
        if (e instanceof Conv c) {
            if (c.k == Conv.K.DECAY)
                return addressOf(c.e);
            ConstVal v = evalImpl(c.e);
            if (v == null)
                return null;
            CType to = c.type;
            switch (c.k) {
                case NOP:
                    return v;
                case TO_VOID:
                    return null;
                case TO_BOOL:
                    if (v.kind == ConstVal.ADDR)
                        return ConstVal.ofInt(1);
                    return ConstVal.ofInt(v.kind == ConstVal.FLOAT ? (v.f != 0 ? 1 : 0) : (v.i != 0 ? 1 : 0));
                case INT:
                    if (v.kind == ConstVal.ADDR)
                        return to.size() == 4 ? v : null;
                    if (v.kind != ConstVal.INT)
                        return null;
                    return ConstVal.ofInt(normalize(v.i, to));
                case INT_TO_FLOAT:
                    if (v.kind != ConstVal.INT)
                        return null;
                    return ConstVal.ofFloat(intToFloat(v.i, c.e.type, to.kind == CType.Kind.FLOAT));
                case FLOAT_TO_INT:
                    if (v.kind != ConstVal.FLOAT)
                        return null;
                    return ConstVal.ofInt(floatToInt(v.f, to));
                case FLOAT_TO_FLOAT:
                    if (v.kind != ConstVal.FLOAT)
                        return null;
                    return ConstVal.ofFloat(to.kind == CType.Kind.FLOAT ? (float) v.f : v.f);
                default:
                    return null;
            }
        }
        if (e instanceof AddrOf a)
            return addressOf(a.e);
        if (e instanceof Unary u) {
            ConstVal v = evalImpl(u.e);
            if (v == null)
                return null;
            CType t = u.type;
            switch (u.op) {
                case "+":
                    return v;
                case "-":
                    if (v.kind == ConstVal.FLOAT)
                        return ConstVal.ofFloat(t.kind == CType.Kind.FLOAT ? (float) -v.f : -v.f);
                    if (v.kind == ConstVal.INT)
                        return ConstVal.ofInt(normalize(-v.i, t));
                    return null;
                case "~":
                    return v.kind == ConstVal.INT ? ConstVal.ofInt(normalize(~v.i, t)) : null;
                case "!":
                    if (v.kind == ConstVal.ADDR)
                        return ConstVal.ofInt(0);
                    return ConstVal.ofInt(v.kind == ConstVal.FLOAT ? (v.f == 0 ? 1 : 0) : (v.i == 0 ? 1 : 0));
                default:
                    return null;
            }
        }
        if (e instanceof Binary b) {
            if (b.op.equals("&&") || b.op.equals("||")) {
                ConstVal l = evalImpl(b.l);
                if (l == null)
                    return null;
                boolean lv = truth(l);
                if (b.op.equals("&&") && !lv)
                    return ConstVal.ofInt(0);
                if (b.op.equals("||") && lv)
                    return ConstVal.ofInt(1);
                ConstVal r = evalImpl(b.r);
                return r == null ? null : ConstVal.ofInt(truth(r) ? 1 : 0);
            }
            if (b.op.equals(","))
                return null;
            ConstVal l = evalImpl(b.l), r = evalImpl(b.r);
            if (l == null || r == null)
                return null;
            return binary(b.op, l, r, b.opType, b.type, b.stride, b.l.type, b.r.type);
        }
        if (e instanceof Cond c) {
            ConstVal cv = evalImpl(c.c);
            if (cv == null)
                return null;
            return truth(cv) ? evalImpl(c.a) : evalImpl(c.b);
        }
        return null;
    }

    static boolean truth(ConstVal v) {
        return v.kind == ConstVal.ADDR || (v.kind == ConstVal.FLOAT ? v.f != 0 : v.i != 0);
    }

    /** The address an lvalue designates, if it is a link-time constant. */
    private static ConstVal addressOf(Expr e) {
        if (e instanceof StrLit s)
            return ConstVal.ofAddr(s.global, 0);
        if (e instanceof VarRef v) {
            Sym.Var var = v.var;
            if ((var.storage == Sym.Var.Storage.GLOBAL || var.storage == Sym.Var.Storage.STATIC_LOCAL) && !var.isRef) {
                var.addressTaken = true;
                return ConstVal.ofAddr(var, 0);
            }
            return null;
        }
        if (e instanceof FuncRef f) {
            if (f.func != null)
                return ConstVal.ofAddr(f.func, 0);
            if (f.builtin != null)
                return ConstVal.ofAddr(f.builtin, 0);
            return null;
        }
        if (e instanceof Ast.Member m) {
            if (m.field == null || m.field.isBitfield())
                return null;
            ConstVal base = m.arrow ? evalImpl(m.obj) : addressOf(m.obj);
            if (base == null)
                return null;
            return offset(base, m.field.offset);
        }
        if (e instanceof Deref dr) {
            return evalImpl(dr.p);
        }
        return null;
    }

    private static ConstVal offset(ConstVal base, long off) {
        if (base.kind == ConstVal.ADDR)
            return ConstVal.ofAddr(base.sym, base.i + off);
        if (base.kind == ConstVal.INT)
            return ConstVal.ofInt(normalize(base.i + off, CType.UINT));
        return null;
    }

    static ConstVal binary(String op, ConstVal l, ConstVal r, CType opType, CType resType, int stride, CType lt, CType rt) {
        // Pointer arithmetic on addresses.
        if (l.kind == ConstVal.ADDR || r.kind == ConstVal.ADDR) {
            switch (op) {
                case "+":
                    if (l.kind == ConstVal.ADDR && r.kind == ConstVal.INT)
                        return ConstVal.ofAddr(l.sym, l.i + signedIndex(r.i, rt) * stride);
                    if (r.kind == ConstVal.ADDR && l.kind == ConstVal.INT)
                        return ConstVal.ofAddr(r.sym, r.i + signedIndex(l.i, lt) * stride);
                    return null;
                case "-":
                    if (l.kind == ConstVal.ADDR && r.kind == ConstVal.INT)
                        return ConstVal.ofAddr(l.sym, l.i - signedIndex(r.i, rt) * stride);
                    if (l.kind == ConstVal.ADDR && r.kind == ConstVal.ADDR && l.sym == r.sym && stride > 0)
                        return ConstVal.ofInt((l.i - r.i) / stride);
                    return null;
                case "==", "!=":
                    if (l.kind == ConstVal.ADDR && r.kind == ConstVal.ADDR && l.sym == r.sym)
                        return ConstVal.ofInt((l.i == r.i) == op.equals("==") ? 1 : 0);
                    if ((l.kind == ConstVal.INT && l.i == 0) || (r.kind == ConstVal.INT && r.i == 0))
                        return ConstVal.ofInt(op.equals("==") ? 0 : 1);
                    return null;
                default:
                    return null;
            }
        }
        if (opType == null)
            return null;
        if (opType.isPointer()) {
            // Integer-valued pointers (offsetof idioms): plain unsigned arithmetic.
            if (op.equals("+") && stride > 0)
                return ConstVal.ofInt(normalize(l.i + signedIndex(r.i, rt) * stride, CType.UINT));
            if (op.equals("-") && stride > 0 && rt.isInteger())
                return ConstVal.ofInt(normalize(l.i - signedIndex(r.i, rt) * stride, CType.UINT));
            if (op.equals("-") && stride > 0)
                return ConstVal.ofInt(normalize((int) (l.i - r.i) / stride, CType.INT));
            opType = CType.UINT;
        }
        if (opType.isFloating()) {
            double a = l.kind == ConstVal.FLOAT ? l.f : l.i, b = r.kind == ConstVal.FLOAT ? r.f : r.i;
            boolean fl = opType.kind == CType.Kind.FLOAT;
            double res;
            switch (op) {
                case "+": res = fl ? (float) a + (float) b : a + b; break;
                case "-": res = fl ? (float) a - (float) b : a - b; break;
                case "*": res = fl ? (float) a * (float) b : a * b; break;
                case "/": res = fl ? (float) a / (float) b : a / b; break;
                case "<": return ConstVal.ofInt(a < b ? 1 : 0);
                case ">": return ConstVal.ofInt(a > b ? 1 : 0);
                case "<=": return ConstVal.ofInt(a <= b ? 1 : 0);
                case ">=": return ConstVal.ofInt(a >= b ? 1 : 0);
                case "==": return ConstVal.ofInt(a == b ? 1 : 0);
                case "!=": return ConstVal.ofInt(a != b ? 1 : 0);
                default: return null;
            }
            return ConstVal.ofFloat(res);
        }
        if (l.kind != ConstVal.INT || r.kind != ConstVal.INT)
            return null;
        long a = l.i, b = r.i;
        boolean u = opType.isUnsigned();
        boolean wide = opType.is64();
        if (op.equals("<<") || op.equals(">>")) {
            long n = b & 0xFF;
            int bits = wide ? 64 : 32;
            long v;
            if (op.equals("<<"))
                v = n >= bits ? 0 : a << n;
            else if (u)
                v = n >= bits ? 0 : (wide ? a >>> n : (a & 0xFFFFFFFFL) >>> n);
            else
                v = n >= bits ? (a < 0 ? -1 : 0) : a >> n;
            return ConstVal.ofInt(normalize(v, opType));
        }
        long v;
        switch (op) {
            case "+": v = a + b; break;
            case "-": v = a - b; break;
            case "*": v = a * b; break;
            case "/":
                if (b == 0 && wide)
                    v = u ? (a != 0 ? -1L : 0) : (a > 0 ? Long.MAX_VALUE : a < 0 ? Long.MIN_VALUE : 0); // libgcc, as the VM
                else if (b == 0)
                    v = 0;
                else if (u && wide)
                    v = Long.divideUnsigned(a, b);
                else if (wide)
                    v = a / b;
                else if (u)
                    v = (a & 0xFFFFFFFFL) / (b & 0xFFFFFFFFL);
                else
                    v = (int) a == Integer.MIN_VALUE && (int) b == -1 ? Integer.MIN_VALUE : (int) a / (int) b;
                break;
            case "%":
                if (b == 0)
                    v = wide ? 0 : a;
                else if (u && wide)
                    v = Long.remainderUnsigned(a, b);
                else if (wide)
                    v = a % b;
                else if (u)
                    v = (a & 0xFFFFFFFFL) % (b & 0xFFFFFFFFL);
                else
                    v = (int) b == -1 ? 0 : (int) a % (int) b;
                break;
            case "&": v = a & b; break;
            case "|": v = a | b; break;
            case "^": v = a ^ b; break;
            case "<": return ConstVal.ofInt(cmp(a, b, u, wide) < 0 ? 1 : 0);
            case ">": return ConstVal.ofInt(cmp(a, b, u, wide) > 0 ? 1 : 0);
            case "<=": return ConstVal.ofInt(cmp(a, b, u, wide) <= 0 ? 1 : 0);
            case ">=": return ConstVal.ofInt(cmp(a, b, u, wide) >= 0 ? 1 : 0);
            case "==": return ConstVal.ofInt(normalize(a, opType) == normalize(b, opType) ? 1 : 0);
            case "!=": return ConstVal.ofInt(normalize(a, opType) != normalize(b, opType) ? 1 : 0);
            default: return null;
        }
        return ConstVal.ofInt(normalize(v, opType));
    }

    private static long signedIndex(long v, CType t) {
        if (t != null && t.isUnsigned() && !t.is64())
            return v & 0xFFFFFFFFL;
        return v;
    }

    private static int cmp(long a, long b, boolean u, boolean wide) {
        if (u)
            return wide ? Long.compareUnsigned(a, b) : Long.compare(a & 0xFFFFFFFFL, b & 0xFFFFFFFFL);
        return Long.compare(a, b);
    }

    /** Names of communication objects a sketch might reach for; GCC's message gets a note saying why. */
    static final Set<String> COMMS_NAMES = Set.of(
            "Serial", "Serial1", "Serial2", "Serial3", "SerialUSB", "Wire", "Wire1", "SPI", "Ethernet",
            "EthernetClient", "EthernetServer", "EthernetUDP", "EEPROM", "Keyboard", "Mouse", "WiFi", "CAN",
            "SoftwareSerial", "HardwareSerial", "Stream", "Print", "SPISettings", "SD", "File", "Udp", "IPAddress");
}
