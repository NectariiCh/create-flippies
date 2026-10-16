package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoLibrary;
import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.lang.Ast.*;

/**
 * The semantic pass over a whole translation unit: declarations, initialisers and
 * statements (expressions are {@link SemaExpr}'s).
 *
 * <h2>Order, and the .ino prototypes</h2>
 * The unit is walked in source order, as C++ requires: a variable is visible from
 * its declaration on. Functions are the exception arduino-builder makes for
 * sketches: every top-level function is known by name from the start, and its
 * signature is resolved the first time anything refers to it. That reproduces what
 * the generated prototypes do without inserting text.
 *
 * <h2>Initialisers</h2>
 * Every initialiser, from {@code int x = 1} to a nested brace list with elided
 * braces, is flattened into an {@link InitPlan}: a list of leaves (scalar,
 * bit-field, chars, String constructor, block copy) at byte offsets. A global whose
 * leaves are all constant expressions is baked into .data/.rodata (or .bss if it is
 * all zero) with relocations for any addresses; anything else becomes code in the
 * static constructor that runs before setup(), exactly the split GCC makes between
 * constant and dynamic initialisation.
 */
final class Sema extends SemaExpr {

    final ArduinoLibrary lib;
    /** Functions with bodies, in definition order (lambdas included). */
    final List<Sym.Func> functions = new ArrayList<>();
    final List<Sym.Var> globalVars = new ArrayList<>();
    /** Globals whose initialisers run in the static constructor, in declaration order. */
    final List<Sym.Var> dynamicInits = new ArrayList<>();
    final Map<Sym.Var, InitPlan> dynamicPlans = new IdentityHashMap<>();
    final List<Sym.Builtin> builtins = new ArrayList<>();
    /** Initialisers of locals, by declaration. */
    final Map<VarDecl, InitPlan> localPlans = new IdentityHashMap<>();
    /** How a class-typed return value is built into the caller's slot, by return statement. */
    final Map<Return, InitPlan> returnPlans = new IdentityHashMap<>();
    /** GNU case ranges: upper bound by case label. */
    final Map<Case, Long> caseHi = new IdentityHashMap<>();
    Map<Case, Expr> parsedRanges = Map.of();
    boolean heapUsed;
    Sym.Func setup;
    Sym.Func loop;

    // ---- per-function context ----
    private Map<String, Labeled> labels;
    private List<Goto> gotos;
    private int loopDepth;
    private final ArrayDeque<Switch> switches = new ArrayDeque<>();
    private int staticCount;
    private int hiddenCount;

    /**
     * Jumps past initialisations ([stmt.dcl]/3): GCC refuses a {@code case} label or a
     * {@code goto} that enters the scope of a local after its initialisation (one with
     * an initialiser, or of a type with a constructor: String, a struct holding one or
     * having default member initialisers). {@link #live} holds such locals of the
     * function being checked, innermost last; a scope closing truncates it back.
     */
    private List<LiveDecl> live = new ArrayList<>();
    private final ArrayDeque<Integer> liveMarks = new ArrayDeque<>();
    private final Map<Switch, Integer> switchMarks = new IdentityHashMap<>();
    private Map<Goto, List<LiveDecl>> liveAtGoto = new IdentityHashMap<>();
    private Map<Labeled, List<LiveDecl>> liveAtLabel = new IdentityHashMap<>();

    private record LiveDecl(VarDecl decl, Sym.Var var) {}

    /** Builtins STM32duino defines weak, so a sketch may define its own (hooks.c: {@code yield}). */
    private static final Set<String> WEAK_BUILTINS = Set.of("yield");

    Sema(Diagnostics d, ArduinoLibrary lib) {
        super(d);
        this.lib = lib;
        global.names.put("String", new Sym.TypeName(CType.STRING));
        global.names.put("StringSumHelper", new Sym.TypeName(CType.STRING));
        for (Intrinsics.Op op : Intrinsics.Op.values()) {
            if (op.kind != Intrinsics.Kind.FUNC)
                continue;
            Sym.Builtin b = new Sym.Builtin(op.name, op.fnType(), new ArrayList<>(), null, op);
            overloadsOf(op.name).items.add(b);
            builtins.add(b);
        }
    }

    private Sym.Overloads overloadsOf(String name) {
        Object o = global.names.get(name);
        if (o instanceof Sym.Overloads ov)
            return ov;
        Sym.Overloads ov = new Sym.Overloads();
        if (o == null)
            global.names.put(name, ov);
        return ov;
    }

    @Override
    void markHeapUsed() {
        heapUsed = true;
    }

    @Override
    void push(boolean function) {
        super.push(function);
        liveMarks.push(live.size());
    }

    @Override
    void pop() {
        super.pop();
        int mark = liveMarks.pop();
        while (live.size() > mark)
            live.remove(live.size() - 1);
    }

    // ================= the unit =================

    void run(List<Node> items) {
        for (Node n : items)
            if (n instanceof FuncDecl fd)
                pending.computeIfAbsent(fd.name, k -> new ArrayList<>()).add(fd);
        boolean libDeclared = false;
        List<Builtin> retry = null;
        for (Node n : items) {
            if (!libDeclared && n.file != Token.INTERNAL) {
                retry = declareLibrary(lib.builtins(), false);
                libDeclared = true;
            }
            if (retry != null && n.file == Token.SKETCH) {
                declareLibrary(retry, true);
                retry = null;
            }
            try {
                topLevel(n);
            } catch (Diagnostics.Abort a) {
                throw a;
            }
        }
        if (!libDeclared)
            retry = declareLibrary(lib.builtins(), false);
        if (retry != null)
            declareLibrary(retry, true);
        // Anything still pending was never referred to: declare it now so its body is checked.
        for (List<FuncDecl> l : new ArrayList<>(pending.values()))
            for (FuncDecl fd : l)
                declareFunction(fd, true);
        pending.clear();
        link();
    }

    /** Declares the core's builtins; returns those whose prototypes did not parse yet (types still to come). */
    private List<Builtin> declareLibrary(List<Builtin> list, boolean last) {
        List<Builtin> failed = new ArrayList<>();
        Set<String> typeNames = new HashSet<>();
        for (Map.Entry<String, Object> e : global.names.entrySet())
            if (e.getValue() instanceof Sym.TypeName)
                typeNames.add(e.getKey());
        for (Builtin b : list) {
            Diagnostics local = new Diagnostics();
            FuncDecl fd = Parser.parsePrototype(b.prototype(), typeNames, local);
            if (fd == null || local.hasErrors()) {
                if (last)
                    d.error(Token.INTERNAL, 0, 0, "the core declares a builtin this compiler cannot read: " + b.prototype());
                else
                    failed.add(b);
                continue;
            }
            if (fd.name.equals("malloc") || fd.name.equals("free") || fd.name.equals("realloc") || fd.name.equals("calloc"))
                continue; // the heap is the compiler's own
            Scope save = cur;
            cur = global;
            try {
                CType ft = resolve(fd.type);
                List<Expr> defaults = new ArrayList<>();
                for (Param p : fd.type.params)
                    defaults.add(p.defaultValue == null ? null : check(p.defaultValue));
                Sym.Builtin sb = new Sym.Builtin(fd.name, ft, defaults, b, null);
                sb.weak = fd.isWeak || WEAK_BUILTINS.contains(fd.name);
                Object existing = global.names.get(fd.name);
                if (existing != null && !(existing instanceof Sym.Overloads)) {
                    d.error(Token.INTERNAL, 0, 0, "builtin '" + fd.name + "' clashes with a declaration in the core's prelude");
                    continue;
                }
                overloadsOf(fd.name).items.add(sb);
                builtins.add(sb);
            } finally {
                cur = save;
            }
        }
        return failed;
    }

    private void topLevel(Node n) {
        if (n instanceof FuncDecl fd) {
            if (fd.body != null)
                defineFunction(fd);
            else
                declareFunction(fd, true);
        } else if (n instanceof VarDecl vd) {
            if (vd.isTypedef)
                typedef(vd);
            else
                globalVar(vd);
        } else if (n instanceof StructDecl sd) {
            structType(sd);
        } else if (n instanceof EnumDecl ed) {
            enumType(ed);
        } else if (n instanceof StaticAssert sa) {
            staticAssert(sa);
        }
    }

    /** Link-time checks: undefined references, and the two functions every sketch must have. */
    private void link() {
        for (Map.Entry<String, Object> e : global.names.entrySet()) {
            if (e.getValue() instanceof Sym.Overloads ov) {
                for (Object o : ov.items) {
                    if (o instanceof Sym.Func f && f.used && f.body == null)
                        d.linkError("undefined reference to `" + f.signature() + "'");
                }
            } else if (e.getValue() instanceof Sym.Var v && v.isExtern && !v.defined && v.used) {
                d.linkError("undefined reference to `" + v.name + "'");
            }
        }
        setup = entry("setup");
        loop = entry("loop");
    }

    private Sym.Func entry(String name) {
        Object o = global.names.get(name);
        if (o instanceof Sym.Overloads ov) {
            for (Object c : ov.items) {
                if (c instanceof Sym.Func f && f.type.params.isEmpty() && f.body != null) {
                    f.used = true;
                    return f;
                }
            }
        }
        d.linkError("undefined reference to `" + name + "'");
        return null;
    }

    // ================= types =================

    @Override
    CType resolve(TypeNode tn) {
        switch (tn.k) {
            case PRIM:
                return tn.prim.withCv(tn.prim.isConst || tn.isConst, tn.prim.isVolatile || tn.isVolatile);
            case NAMED: {
                Object o = lookup(tn.name);
                if (o instanceof Sym.TypeName t)
                    return t.type.withCv(t.type.isConst || tn.isConst, t.type.isVolatile || tn.isVolatile);
                error(tn, "'" + tn.name + "' does not name a type");
                return CType.INT;
            }
            case STRUCT:
                return structType(tn.structDecl).withCv(tn.isConst, tn.isVolatile);
            case ENUM:
                return enumType(tn.enumDecl).withCv(tn.isConst, tn.isVolatile);
            case AUTO:
                error(tn, "'auto' is not allowed here");
                return CType.INT;
            case DECLTYPE: {
                unevaluated++;
                try {
                    Expr e = check(tn.declExpr);
                    CType t = e instanceof VarRef v ? v.var.type : e.type;
                    if (e instanceof FuncRef f && f.type != null)
                        t = f.type;
                    return t.withCv(t.isConst || tn.isConst, t.isVolatile || tn.isVolatile);
                } finally {
                    unevaluated--;
                }
            }
            case PTR: {
                CType in = resolve(tn.inner);
                if (in.isRef()) {
                    error(tn, "cannot declare pointer to '" + in + "'");
                    in = in.base;
                }
                return CType.pointer(in).withCv(tn.isConst, tn.isVolatile);
            }
            case REF: {
                CType in = resolve(tn.inner);
                if (in.isVoid()) {
                    error(tn, "cannot declare reference to 'void'");
                    in = CType.INT;
                }
                if (in.isRef())
                    return in;
                return CType.reference(in);
            }
            case ARRAY: {
                CType elem = resolve(tn.inner);
                if (elem.isVoid() || elem.isFunction() || elem.isRef()) {
                    error(tn, "declaration of array of '" + elem + "'");
                    elem = CType.INT;
                }
                if (!elem.isComplete() && !elem.isString()) {
                    error(tn, "elements of array have incomplete type '" + elem + "'");
                    elem = CType.INT;
                }
                int len = -1;
                if (tn.arraySize != null) {
                    Expr s = value(tn.arraySize);
                    Long n = s.type.isIntegral() ? intConst(s) : null;
                    if (n == null && tn == vlaTop && s.type.isIntegral() && !s.type.isScopedEnum()) {
                        vlaBound = arith(s, CType.UINT);
                        len = 0;
                    } else if (n == null) {
                        error(tn, "array bound is not an integer constant before ']' token");
                        len = 1;
                    } else if (n < 0 || (s.type.isUnsigned() && n > Integer.MAX_VALUE)) {
                        error(tn, "size of array is negative");
                        len = 1;
                    } else if (n * elem.size() > 0x7FFFFFFFL) {
                        error(tn, "size of array is too large");
                        len = 1;
                    } else {
                        len = n.intValue();
                    }
                }
                return CType.array(elem, len);
            }
            case FUNC: {
                CType ret = tn.inner.k == TypeNode.K.AUTO ? CType.VOID : resolve(tn.inner);
                if (ret.isArray() || ret.isFunction()) {
                    error(tn, "function cannot return " + (ret.isArray() ? "an array" : "a function"));
                    ret = CType.INT;
                }
                List<CType> ps = new ArrayList<>();
                for (Param p : tn.params) {
                    CType pt = resolve(p.type);
                    if (pt.isVoid()) {
                        error(p, "invalid parameter type 'void'");
                        pt = CType.INT;
                    }
                    ps.add(adjustParam(pt));
                }
                return CType.function(ret, ps, tn.variadic);
            }
            default:
                error(tn, "invalid type");
                return CType.INT;
        }
    }

    private CType structType(StructDecl sd) {
        if (sd.resolved != null)
            return sd.resolved;
        if (sd.members == null) {
            Object o = sd.tag != null ? lookup(sd.tag) : null;
            if (o instanceof Sym.TypeName tn && tn.type.isStruct()) {
                sd.resolved = tn.type;
                return tn.type;
            }
            if (o instanceof Sym.TypeName tn && tn.type.isString()) {
                sd.resolved = tn.type;
                return tn.type;
            }
            CType.StructInfo info = new CType.StructInfo(sd.tag, sd.isUnion);
            CType t = CType.struct(info);
            if (sd.tag != null)
                cur.names.put(sd.tag, new Sym.TypeName(t));
            sd.resolved = t;
            return t;
        }
        CType.StructInfo info = null;
        if (sd.tag != null) {
            Object o = cur.names.get(sd.tag);
            if (o instanceof Sym.TypeName tn && tn.type.isStruct()) {
                if (tn.type.struct.complete) {
                    error(sd, "redefinition of '" + (sd.isUnion ? "union " : "struct ") + sd.tag + "'");
                } else {
                    info = tn.type.struct;
                }
            } else if (o != null) {
                error(sd, "'" + sd.tag + "' redeclared as different kind of entity");
            }
        }
        if (info == null)
            info = new CType.StructInfo(sd.tag, sd.isUnion);
        CType t = CType.struct(info);
        if (sd.tag != null)
            cur.names.put(sd.tag, new Sym.TypeName(t));
        sd.resolved = t;
        sd.info = info;
        info.packed = sd.packed;
        info.pack = sd.pack;
        info.alignAttr = alignment(sd.alignAttr, sd.alignDefault, sd);
        Set<String> seen = new HashSet<>();
        for (MemberDecl m : sd.members) {
            CType mt = resolve(m.type);
            if (mt.isFunction()) {
                error(m, "member functions are not supported by this PLC's compiler");
                continue;
            }
            if (mt.isRef()) {
                error(m, "reference members are not supported by this PLC's compiler");
                mt = CType.pointer(mt.base);
            }
            if (!mt.isComplete() && !mt.isString()) {
                if (mt.isStruct() && mt.struct == info)
                    error(m, "field '" + m.name + "' has incomplete type '" + mt + "'");
                else if (mt.isArray() && mt.length < 0)
                    error(m, "flexible array members are not supported");
                else
                    error(m, "field '" + m.name + "' has incomplete type '" + mt + "'");
                mt = CType.INT;
            }
            int width = -1;
            if (m.bitWidth != null) {
                Long w = intConst(value(m.bitWidth));
                if (!mt.isIntegral()) {
                    error(m, "bit-field '" + m.name + "' with non-integral type '" + mt + "'");
                } else if (w == null) {
                    error(m, "bit-field '" + m.name + "' width not an integer constant");
                } else if (w < 0) {
                    error(m, "negative width in bit-field '" + m.name + "'");
                } else {
                    width = (int) Math.min(w, mt.size() * 8L);
                    if (w > mt.size() * 8L)
                        warning(m, "width of '" + m.name + "' exceeds its type");
                    if (w == 0 && m.name != null)
                        error(m, "zero width for bit-field '" + m.name + "'");
                }
            }
            if (m.name != null && !seen.add(m.name)) {
                error(m, "redeclaration of '" + mt.spell(m.name) + "'");
                continue;
            }
            CType.Field f = new CType.Field(m.name, mt, width, m.line, m.col);
            if (m.defaultInit != null)
                f.defaultInit = m.defaultInit;
            f.packed = m.packed;
            f.alignAttr = alignment(m.alignAttr, m.alignDefault, m);
            info.fields.add(f);
            if (m.name != null)
                info.byName.put(m.name, f);
        }
        info.layout();
        String spelled = (sd.isUnion ? "union " : "struct ") + (sd.tag != null ? sd.tag : "<unnamed>");
        if (info.tooLarge)
            error(sd, "size of type '" + spelled + "' is too large");
        if (info.unreachableBitfield != null) {
            CType.Field f = info.unreachableBitfield;
            error(sd, "packed bit-field '" + f.name + "' spans more bytes than its type '" + f.type
                    + "' has; this PLC's compiler cannot reach it: give it a wider type");
        }
        if (info.duplicateLifted != null)
            error(sd, "redeclaration of '" + info.duplicateLifted + "' in an anonymous member of '" + spelled + "'");
        return t;
    }

    /**
     * The N of {@code aligned(N)} / {@code alignas(N)}, checked as GCC does: a positive
     * power of two; plain {@code aligned} means the target's largest alignment, 8.
     * 0 when there is none.
     */
    private int alignment(Expr e, boolean dflt, Node at) {
        if (e == null)
            return dflt ? 8 : 0;
        Long n = intConst(value(e));
        if (n == null) {
            error(at, "requested alignment is not an integer constant");
            return 0;
        }
        if (n <= 0 || (n & (n - 1)) != 0 || n > 1 << 28) {
            error(at, "requested alignment '" + n + "' is not a positive power of 2");
            return 0;
        }
        return n.intValue();
    }

    private CType enumType(EnumDecl ed) {
        if (ed.resolved != null)
            return ed.resolved;
        if (ed.items == null) {
            Object o = ed.tag != null ? lookup(ed.tag) : null;
            if (o instanceof Sym.TypeName tn && tn.type.isEnum()) {
                ed.resolved = tn.type;
                return tn.type;
            }
            if (ed.underlying == null && !ed.scoped) {
                error(ed, "use of enum '" + ed.tag + "' without previous declaration");
                ed.resolved = CType.INT;
                return CType.INT;
            }
            CType.EnumInfo info = new CType.EnumInfo(ed.tag, ed.scoped);
            info.fixed = true;
            info.underlying = ed.underlying != null ? resolve(ed.underlying).unqualified() : CType.INT;
            CType t = CType.enumType(info);
            if (ed.tag != null)
                cur.names.put(ed.tag, new Sym.TypeName(t));
            ed.resolved = t;
            return t;
        }
        CType.EnumInfo info = new CType.EnumInfo(ed.tag, ed.scoped);
        info.fixed = ed.underlying != null || ed.scoped;
        CType fixed = null;
        if (ed.underlying != null) {
            fixed = resolve(ed.underlying).unqualified();
            if (!fixed.isInteger() || fixed.kind == CType.Kind.BOOL && false) {
                error(ed, "underlying type '" + fixed + "' of '" + ed.tag + "' must be an integral type");
                fixed = CType.INT;
            }
        } else if (ed.scoped) {
            fixed = CType.INT;
        }
        info.underlying = fixed != null ? fixed : CType.INT;
        // Values first (enumerators are visible to later ones), then the final type.
        long next = 0;
        long min = 0, max = 0;
        List<Sym.EnumConst> consts = new ArrayList<>();
        for (Enumerator e : ed.items) {
            long v = next;
            if (e.value != null) {
                Expr x = value(e.value);
                Long c = x.type.isIntegral() ? intConst(x) : null;
                if (c == null)
                    error(e, "enumerator value for '" + e.name + "' is not an integer constant");
                else
                    v = c;
            }
            info.values.put(e.name, v);
            min = Math.min(min, v);
            max = Math.max(max, v);
            next = v + 1;
            Sym.EnumConst ec = new Sym.EnumConst(v, CType.INT);
            consts.add(ec);
            if (!ed.scoped)
                declare(e, e.name, ec);
        }
        if (fixed == null) {
            // Short enums (see CType): the smallest type that holds every value, as
            // GCC's -fshort-enums picks it, which is arm-none-eabi's default.
            if (min >= 0 && max <= 0xFF)
                info.underlying = CType.UCHAR;
            else if (min >= Byte.MIN_VALUE && max <= Byte.MAX_VALUE)
                info.underlying = CType.SCHAR;
            else if (min >= 0 && max <= 0xFFFF)
                info.underlying = CType.USHORT;
            else if (min >= Short.MIN_VALUE && max <= Short.MAX_VALUE)
                info.underlying = CType.SHORT;
            else if (min >= 0 && max <= 0xFFFFFFFFL)
                info.underlying = CType.UINT;
            else if (min >= Integer.MIN_VALUE && max <= Integer.MAX_VALUE)
                info.underlying = CType.INT;
            else
                info.underlying = min < 0 ? CType.LLONG : CType.ULLONG;
        }
        CType t = CType.enumType(info);
        if (ed.tag != null)
            cur.names.put(ed.tag, new Sym.TypeName(t));
        if (!ed.scoped) {
            int i = 0;
            for (Enumerator e : ed.items) {
                Object o = cur.names.get(e.name);
                if (o == consts.get(i))
                    cur.names.put(e.name, new Sym.EnumConst(consts.get(i).value, t));
                i++;
            }
        }
        ed.resolved = t;
        return t;
    }

    private void typedef(VarDecl vd) {
        CType t = resolve(vd.type);
        declare(vd, vd.name, new Sym.TypeName(t));
    }

    private void staticAssert(StaticAssert sa) {
        Expr c = value(sa.cond);
        Long v = intConst(c);
        if (v == null)
            error(sa, "non-constant condition for static assertion");
        else if (v == 0)
            error(sa, "static assertion failed" + (sa.message != null ? ": " + sa.message : ""));
    }

    // ================= functions =================

    @Override
    Sym.Func declareFunction(FuncDecl fd, boolean prototypeOnly) {
        if (fd.sym != null)
            return fd.sym;
        if (declaring.containsKey(fd))
            return null;
        declaring.put(fd, true);
        Scope save = cur;
        cur = global;
        try {
            List<FuncDecl> l = pending.get(fd.name);
            if (l != null) {
                l.remove(fd);
                if (l.isEmpty())
                    pending.remove(fd.name);
            }
            CType ft = resolve(fd.type);
            Object o = global.names.get(fd.name);
            if (o != null && !(o instanceof Sym.Overloads)) {
                error(fd, "'" + fd.name + "' redeclared as different kind of entity");
                Sym.Func dummy = new Sym.Func(fd.name);
                dummy.type = ft;
                fd.sym = dummy;
                return dummy;
            }
            Sym.Overloads ov = overloadsOf(fd.name);
            for (Object c : ov.items) {
                CType ct = typeOfCallable(c);
                if (!sameParams(ct, ft))
                    continue;
                if (c instanceof Sym.Builtin b && b.weak && fd.body != null && b.type.base.equals(ft.base)) {
                    // The core's definition is weak: the sketch's replaces it, for every call.
                    ov.items.remove(b);
                    break;
                }
                if (c instanceof Sym.Builtin b) {
                    if (fd.body != null) {
                        error(fd, "redefinition of '" + fd.name + "': the Arduino core already defines '" + describeCallable(b) + "'");
                    }
                    Sym.Func dummy = new Sym.Func(fd.name);
                    dummy.type = ft;
                    fd.sym = dummy;
                    return dummy;
                }
                Sym.Func f = (Sym.Func) c;
                if (!f.type.base.equals(ft.base))
                    error(fd, "ambiguating new declaration of '" + ft.base.spell(fd.name + paramsSpelling(ft)) + "'");
                for (int i = 0; i < fd.type.params.size(); i++) {
                    Param p = fd.type.params.get(i);
                    if (p.defaultValue != null) {
                        if (f.defaults.get(i) != null && f.def != null && f.def != fd)
                            error(p, "default argument given for parameter " + (i + 1) + " of '" + f.signature() + "'");
                        else
                            f.defaults.set(i, check(p.defaultValue));
                    }
                }
                fd.sym = f;
                return f;
            }
            Sym.Func f = new Sym.Func(fd.name);
            f.type = ft;
            f.def = fd;
            f.isStatic = fd.isStatic;
            f.file = fd.file;
            f.line = fd.line;
            f.col = fd.col;
            for (Param p : fd.type.params)
                f.defaults.add(p.defaultValue == null ? null : check(p.defaultValue));
            boolean seenDefault = false;
            for (int i = 0; i < f.defaults.size(); i++) {
                if (f.defaults.get(i) != null)
                    seenDefault = true;
                else if (seenDefault)
                    error(fd.type.params.get(i), "default argument missing for parameter " + (i + 1) + " of '" + f.signature() + "'");
            }
            ov.items.add(f);
            fd.sym = f;
            return f;
        } finally {
            cur = save;
            declaring.remove(fd);
        }
    }

    private static boolean sameParams(CType a, CType b) {
        if (a.params.size() != b.params.size() || a.variadic != b.variadic)
            return false;
        for (int i = 0; i < a.params.size(); i++)
            if (!a.params.get(i).sameUnqualified(b.params.get(i)))
                return false;
        return true;
    }

    private static String paramsSpelling(CType ft) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ft.params.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(ft.params.get(i));
        }
        return sb.append(')').toString();
    }

    private void defineFunction(FuncDecl fd) {
        Sym.Func f = declareFunction(fd, false);
        if (f == null)
            return;
        if (f.body != null && f.def != fd) {
            error(fd, "redefinition of '" + f.type.base.spell(f.signature()) + "'");
            return;
        }
        if (f.body != null)
            return;
        f.def = fd;
        f.body = fd.body;
        f.file = fd.file;
        f.line = fd.line;
        f.col = fd.col;
        checkBody(f, fd, f.type.base, false);
        functions.add(f);
    }

    /** Checks a function or lambda body with a fresh function context. */
    private void checkBody(Sym.Func f, FuncDecl fd, CType ret, boolean autoRet) {
        Sym.Func saveFn = curFn;
        CType saveRet = curRet;
        boolean saveAuto = curRetAuto;
        Scope saveScope = cur;
        Map<String, Labeled> saveLabels = labels;
        List<Goto> saveGotos = gotos;
        int saveLoop = loopDepth;
        ArrayDeque<Switch> saveSwitches = new ArrayDeque<>(switches);
        List<Temp> saveSink = tempSink;
        List<LiveDecl> saveLive = live;
        Map<Goto, List<LiveDecl>> saveAtGoto = liveAtGoto;
        Map<Labeled, List<LiveDecl>> saveAtLabel = liveAtLabel;
        switches.clear();
        curFn = f;
        curRet = ret;
        curRetAuto = autoRet;
        labels = new HashMap<>();
        gotos = new ArrayList<>();
        loopDepth = 0;
        tempSink = null;
        live = new ArrayList<>();
        liveAtGoto = new IdentityHashMap<>();
        liveAtLabel = new IdentityHashMap<>();
        cur = new Scope(global, true);
        try {
            for (int i = 0; i < fd.type.params.size(); i++) {
                Param p = fd.type.params.get(i);
                CType pt = f.type.params.get(i);
                Sym.Var v = new Sym.Var(p.name == null ? "<anon>" : p.name, pt, Sym.Var.Storage.PARAM, p.file, p.line, p.col);
                v.isRef = pt.isRef();
                v.byAddress = pt.isString() || pt.isStruct();
                if (v.isRef || v.byAddress)
                    v.addressTaken = false;
                f.params.add(v);
                if (p.name != null)
                    declare(p, p.name, v);
            }
            for (Stmt s : fd.body.items)
                stmt(s);
            if (autoRet) {
                CType r = curRet == null ? CType.VOID : curRet;
                f.type = CType.function(r, f.type.params, f.type.variadic);
            }
            CType r = f.type.base;
            if (r.isString() || r.isStruct()) {
                f.sret = new Sym.Var("<sret>", CType.pointer(r), Sym.Var.Storage.PARAM, fd.file, fd.line, fd.col);
            }
            if (!r.isVoid() && fallsThrough(fd.body) && !fd.name.equals("main")) {
                // GCC words it by whether any return exists, and puts it at the closing brace.
                d.warning(fd.file, fd.body.endLine, Math.max(1, fd.body.endCol), hasReturn(fd.body)
                        ? "control reaches end of non-void function [-Wreturn-type]"
                        : "no return statement in function returning non-void [-Wreturn-type]");
            }
            for (Goto g : gotos) {
                Labeled target = labels.get(g.label);
                if (target == null) {
                    error(g, "label '" + g.label + "' used but not defined");
                    continue;
                }
                checkJump(g, target);
            }
            f.constexprBody = fd.isConstexpr;
        } finally {
            curFn = saveFn;
            curRet = saveRet;
            curRetAuto = saveAuto;
            cur = saveScope;
            labels = saveLabels;
            gotos = saveGotos;
            loopDepth = saveLoop;
            switches.clear();
            switches.addAll(saveSwitches);
            tempSink = saveSink;
            live = saveLive;
            liveAtGoto = saveAtGoto;
            liveAtLabel = saveAtLabel;
        }
    }

    /** A goto that enters the scope of an initialised local, as GCC reports it. */
    private void checkJump(Goto g, Labeled target) {
        List<LiveDecl> from = liveAtGoto.getOrDefault(g, List.of());
        List<LiveDecl> crossed = new ArrayList<>();
        for (LiveDecl l : liveAtLabel.getOrDefault(target, List.of()))
            if (!from.contains(l))
                crossed.add(l);
        if (crossed.isEmpty())
            return;
        error(target, "jump to label '" + target.label + "'");
        note(g, "  from here");
        for (LiveDecl l : crossed)
            note(l.decl, crossing(l.var));
    }

    /** Whether the body has a return statement anywhere (lambdas' own excepted). */
    private static boolean hasReturn(Stmt s) {
        if (s == null)
            return false;
        if (s instanceof Return)
            return true;
        if (s instanceof Block b) {
            for (Stmt x : b.items)
                if (hasReturn(x))
                    return true;
            return false;
        }
        if (s instanceof If i)
            return hasReturn(i.then) || hasReturn(i.otherwise);
        if (s instanceof While w)
            return hasReturn(w.body);
        if (s instanceof DoWhile dw)
            return hasReturn(dw.body);
        if (s instanceof For f)
            return hasReturn(f.body);
        if (s instanceof Switch sw)
            return hasReturn(sw.body);
        if (s instanceof Case c)
            return hasReturn(c.body);
        if (s instanceof Labeled l)
            return hasReturn(l.body);
        return false;
    }

    @Override
    Sym.Func checkLambda(Lambda l) {
        FuncDecl fd = l.fn;
        if (fd.sym != null)
            return fd.sym;
        Sym.Func f = new Sym.Func(fd.name);
        f.isStatic = true;
        f.def = fd;
        f.body = fd.body;
        f.file = fd.file;
        f.line = fd.line;
        f.col = fd.col;
        boolean auto = fd.type.inner.k == TypeNode.K.AUTO;
        Scope save = cur;
        cur = global;
        try {
            f.type = resolve(fd.type);
        } finally {
            cur = save;
        }
        fd.sym = f;
        checkBody(f, fd, auto ? null : f.type.base, auto);
        f.used = true;
        functions.add(f);
        return f;
    }

    /** Whether control can run off the end of {@code s}, as a cheap version of GCC's -Wreturn-type. */
    private static boolean fallsThrough(Stmt s) {
        if (s instanceof Return || s instanceof Goto)
            return false;
        if (s instanceof Block b) {
            for (Stmt x : b.items)
                if (!fallsThrough(x))
                    return false;
            return true;
        }
        if (s instanceof If i)
            return i.otherwise == null || fallsThrough(i.then) || fallsThrough(i.otherwise);
        if (s instanceof While w)
            return !(isTrue(w.cond) && !hasBreak(w.body));
        if (s instanceof DoWhile dw)
            return !(isTrue(dw.cond) && !hasBreak(dw.body)) && fallsThrough(dw.body);
        if (s instanceof For f)
            return !((f.cond == null || isTrue(f.cond)) && !hasBreak(f.body));
        if (s instanceof Labeled l)
            return fallsThrough(l.body);
        if (s instanceof Switch sw) {
            if (sw.dflt == null)
                return true;
            return hasBreak(sw.body) || fallsThrough(sw.body);
        }
        if (s instanceof Case c)
            return fallsThrough(c.body);
        if (s instanceof ExprStmt es && es.expr instanceof Call c && c.builtin != null && c.builtin.name.equals("abort"))
            return false;
        return true;
    }

    private static boolean isTrue(Expr e) {
        if (e == null)
            return true;
        Long v = intConst(e);
        return v != null && v != 0;
    }

    /** A break that leaves {@code s} itself (not one inside a nested loop or switch). */
    private static boolean hasBreak(Stmt s) {
        if (s == null)
            return false;
        if (s instanceof Break)
            return true;
        if (s instanceof Block b) {
            for (Stmt x : b.items)
                if (hasBreak(x))
                    return true;
            return false;
        }
        if (s instanceof If i)
            return hasBreak(i.then) || hasBreak(i.otherwise);
        if (s instanceof Labeled l)
            return hasBreak(l.body);
        if (s instanceof Case c)
            return hasBreak(c.body);
        if (s instanceof Goto)
            return true;
        return false;
    }

    // ================= variables =================

    /**
     * While an automatic variable's declared type is resolved: its outermost array
     * declarator, whose bound may then be a run-time value (a variable-length array,
     * which arm-none-eabi-g++ -std=gnu++17 accepts as an extension). The bound found
     * is left in {@link #vlaBound}. Globals and statics keep requiring a constant, as
     * in g++.
     */
    private TypeNode vlaTop;
    private Expr vlaBound;

    /** Resolves a declared type, deducing {@code auto} from the (already checked) initialiser. */
    private CType declaredType(VarDecl vd) {
        TypeNode tn = vd.type;
        TypeNode base = tn;
        while (base.k == TypeNode.K.PTR || base.k == TypeNode.K.REF)
            base = base.inner;
        if (base.k != TypeNode.K.AUTO)
            return resolve(tn);
        if (vd.init == null || vd.init instanceof InitList) {
            if (vd.init instanceof InitList il && il.items.size() == 1) {
                vd.init = check(il.items.get(0));
            } else {
                error(vd, "declaration of 'auto " + vd.name + "' has no initializer");
                return CType.INT;
            }
        }
        Expr init = check(vd.init);
        vd.init = init;
        if (tn.k == TypeNode.K.REF) {
            CType t = init.type;
            if (tn.inner.isConst)
                t = t.withConst(true);
            if (!init.lvalue && !isTempClass(init) && !t.isConst && !tn.rvalueRef) {
                error(vd, "cannot bind non-const lvalue reference to an rvalue");
            }
            return CType.reference(t);
        }
        CType t = decay(init).type;
        if (t == null || t.isVoid()) {
            error(vd, "variable '" + vd.name + "' declared void");
            return CType.INT;
        }
        t = t.unqualified();
        if (tn.k == TypeNode.K.PTR) {
            if (!t.isPointer()) {
                error(vd, "unable to deduce '" + "auto*" + "' from '" + t + "'");
                return CType.pointer(t);
            }
            CType pointee = t.base.withCv(t.base.isConst || tn.inner.isConst, t.base.isVolatile || tn.inner.isVolatile);
            return CType.pointer(pointee).withCv(tn.isConst, tn.isVolatile);
        }
        return t.withCv(tn.isConst, tn.isVolatile);
    }

    private void globalVar(VarDecl vd) {
        List<Temp> save = tempSink;
        tempSink = new ArrayList<>();
        try {
            CType t = declaredType(vd);
            if (t.isVoid() || t.isFunction()) {
                if (t.isFunction())
                    return;
                error(vd, "variable or field '" + vd.name + "' declared void");
                return;
            }
            Object existing = global.names.get(vd.name);
            Sym.Var v;
            if (existing instanceof Sym.Var ev && (ev.isExtern || vd.isExtern) && ev.type.sameUnqualified(t.isArray() && t.length < 0 && ev.type.isArray() ? ev.type : t)) {
                v = ev;
                if (vd.isExtern) {
                    vd.var = v;
                    return;
                }
                if (v.defined) {
                    error(vd, "redefinition of '" + t.spell(vd.name) + "'");
                    return;
                }
            } else {
                if (existing != null) {
                    error(vd, (existing instanceof Sym.Var ? "redefinition of '" : "'") + t.spell(vd.name)
                            + (existing instanceof Sym.Var ? "'" : "' redeclared as different kind of entity"));
                    return;
                }
                if (vd.isExtern && vd.init == null) {
                    v = new Sym.Var(vd.name, t, Sym.Var.Storage.GLOBAL, vd.file, vd.line, vd.col);
                    v.isExtern = true;
                    v.isRef = t.isRef();
                    global.names.put(vd.name, v);
                    vd.var = v;
                    return;
                }
                InitResult r = initialise(vd, t, true);
                v = new Sym.Var(vd.name, r.type, Sym.Var.Storage.GLOBAL, vd.file, vd.line, vd.col);
                v.isRef = r.type.isRef();
                global.names.put(vd.name, v);
                vd.var = v;
                v.defined = true;
                allocateStatic(v, r.plan, vd);
                if (!tempSink.isEmpty())
                    temps.put(v, tempSink);
                return;
            }
            InitResult r = initialise(vd, t, true);
            v.defined = true;
            v.isExtern = false;
            vd.var = v;
            allocateStatic(v, r.plan, vd);
            if (!tempSink.isEmpty())
                temps.put(v, tempSink);
        } finally {
            tempSink = save;
        }
    }

    private record InitResult(CType type, InitPlan plan) {}

    /** Checks a declaration's initialiser into a plan; completes an unsized array's type from it. */
    private InitResult initialise(VarDecl vd, CType t, boolean isStatic) {
        if (t.isRef()) {
            if (vd.init == null && vd.ctorArgs == null) {
                error(vd, "'" + vd.name + "' declared as reference but not initialized");
                return new InitResult(t, null);
            }
            Expr init = vd.init != null ? vd.init : vd.ctorArgs.get(0);
            if (init instanceof InitList il && il.items.size() == 1)
                init = il.items.get(0);
            Expr bound = bindRef(check(init), t, vd);
            InitPlan p = new InitPlan();
            Leaf l = new Leaf();
            l.k = Leaf.K.SCALAR;
            l.type = CType.pointer(t.base);
            l.value = bound;
            p.leaves.add(l);
            return new InitResult(t, p);
        }
        Expr unwrapped = bracedString(t, vd.init);
        if (unwrapped != null)
            vd.init = unwrapped;
        if (!t.isComplete() && !t.isString() && !(t.isArray() && t.length < 0)) {
            error(vd, "storage size of '" + vd.name + "' isn't known");
            return new InitResult(CType.INT, null);
        }
        if (t.isArray() && t.length < 0) {
            int n;
            if (vd.init instanceof InitList il) {
                n = countElements(t, il);
            } else if (vd.init != null && check(vd.init) instanceof StrLit s && isCharType(t.base)) {
                vd.init = s;
                n = s.bytes.length + 1;
            } else {
                error(vd, "storage size of '" + vd.name + "' isn't known");
                n = 1;
            }
            t = CType.array(t.base, n).withCv(t.isConst, t.isVolatile);
        }
        InitPlan plan = planInit(t, vd.init, vd.ctorArgs, vd, vd.ctorArgs != null);
        return new InitResult(t, plan);
    }

    /**
     * {@code char s[] = {"abc"}}: a string literal in braces initialises a character
     * array as the bare literal does ([dcl.init.string]/1, "optionally enclosed in
     * braces"). Returns the literal, or null if {@code init} is not that.
     */
    private static Expr bracedString(CType t, Expr init) {
        if (t.isArray() && isCharType(t.base) && init instanceof InitList il && il.items.size() == 1 && il.items.get(0) instanceof StrLit s)
            return s;
        return null;
    }

    private int countElements(CType t, InitList il) {
        Cursor c = new Cursor(il.items);
        InitPlan scratch = new InitPlan();
        int before = d.list.size();
        int n = aggregate(t, 0, c, scratch, il);
        // Diagnostics from this dry run would be reported again by the real pass.
        while (d.list.size() > before)
            d.list.remove(d.list.size() - 1);
        return Math.max(n, 0);
    }

    /** Gives a global or static local its storage, baking a constant initialiser or queueing a dynamic one. */
    private void allocateStatic(Sym.Var v, InitPlan plan, Node at) {
        if (at instanceof VarDecl vd && vd.alignAttr != null)
            v.alignAttr = alignment(vd.alignAttr, false, vd);
        CType t = v.type;
        int size = v.slotSize();
        Sym.Global g = new Sym.Global(v.storage == Sym.Var.Storage.STATIC_LOCAL ? curFn.name + "::" + v.name + "." + staticCount++ : v.name,
                Math.max(size, 1), v.slotAlign(), Sym.Global.Section.BSS);
        v.global = g;
        g.owner = v;
        globals.add(g);
        boolean isConst = (t.isConst || (t.isArray() && elementConst(t))) && !t.isNonTrivial() && !v.isRef;
        if (size > (isConst ? Stm32f103cb.FLASH_SIZE : Stm32f103cb.SRAM_SIZE)) {
            // Bigger than its whole memory region: the linker will refuse it (if anything
            // keeps it), so there is nothing to bake, and baking it would mean allocating
            // gigabytes here for an image that can never exist.
            g.section = isConst ? Sym.Global.Section.RODATA : Sym.Global.Section.BSS;
            return;
        }
        if (at instanceof VarDecl nd && nd.noinit && !isConst) {
            // .noinit: never zeroed, never loaded. arm-gcc puts an initialised one there
            // too, and its initialiser is simply never applied.
            if (plan != null && !plan.leaves.isEmpty())
                warning(at, "'" + v.name + "' is in section '.noinit', so its initializer is never applied");
            if (t.isNonTrivial())
                warning(at, "'" + v.name + "' is in section '.noinit', so its constructor still runs at every reset");
            g.section = Sym.Global.Section.NOINIT;
            if (t.isNonTrivial() && plan != null && !plan.leaves.isEmpty() && v.storage == Sym.Var.Storage.GLOBAL) {
                g.dynamic = true;
                dynamicInits.add(v);
                dynamicPlans.put(v, plan);
            }
            return;
        }
        if (plan == null || plan.leaves.isEmpty()) {
            g.section = isConst ? Sym.Global.Section.RODATA : Sym.Global.Section.BSS;
            if (isConst)
                g.data = new byte[g.size];
            if (isConst && t.isScalar())
                v.constValue = t.isFloating() ? (Object) 0.0 : (Object) 0L;
            if (plan != null && plan.zeroFill && t.isNonTrivial()) {
                // A default-constructed String is all zero bytes: .bss already is.
            }
            return;
        }
        byte[] data = new byte[g.size];
        List<Sym.Reloc> relocs = new ArrayList<>();
        if (bake(plan, data, relocs)) {
            boolean zero = relocs.isEmpty();
            for (byte b : data)
                if (b != 0)
                    zero = false;
            if (isConst) {
                g.section = Sym.Global.Section.RODATA;
            } else {
                g.section = zero ? Sym.Global.Section.BSS : Sym.Global.Section.DATA;
            }
            if (g.section != Sym.Global.Section.BSS) {
                g.data = data;
                g.relocs.addAll(relocs);
            }
            if (isConst && t.isScalar() && relocs.isEmpty() && plan.leaves.size() == 1) {
                ConstVal c = eval(plan.leaves.get(0).value);
                if (c != null && c.kind == ConstVal.INT)
                    v.constValue = c.i;
                else if (c != null && c.kind == ConstVal.FLOAT)
                    v.constValue = c.f;
            }
            return;
        }
        // Dynamic initialisation: zero now, run the plan before setup() (or on first use, for a static local).
        g.section = Sym.Global.Section.BSS;
        g.dynamic = true;
        if (v.storage == Sym.Var.Storage.STATIC_LOCAL) {
            v.staticInit = plan;
            Sym.Global guard = new Sym.Global("guard variable for " + g.name, 1, 1, Sym.Global.Section.BSS);
            globals.add(guard);
            v.guard = guard;
        } else {
            dynamicInits.add(v);
            dynamicPlans.put(v, plan);
        }
    }

    private static boolean elementConst(CType t) {
        while (t.isArray())
            t = t.base;
        return t.isConst;
    }

    /** Writes a constant plan into {@code data}; false if any leaf needs code to run. */
    private boolean bake(InitPlan plan, byte[] data, List<Sym.Reloc> relocs) {
        for (Leaf l : plan.leaves) {
            switch (l.k) {
                case CHARS:
                    System.arraycopy(l.chars, 0, data, l.offset, Math.min(l.chars.length, data.length - l.offset));
                    break;
                case SCALAR: {
                    ConstVal c = eval(l.value);
                    if (c == null)
                        return false;
                    CType t = l.type;
                    if (c.kind == ConstVal.ADDR) {
                        if (t.size() != 4)
                            return false;
                        relocs.add(new Sym.Reloc(l.offset, c.sym, c.i));
                        break;
                    }
                    long bits;
                    if (t.isFloating()) {
                        double f = c.kind == ConstVal.FLOAT ? c.f : c.i;
                        bits = t.kind == CType.Kind.FLOAT ? Float.floatToRawIntBits((float) f) & 0xFFFFFFFFL : Double.doubleToRawLongBits(f);
                    } else {
                        if (c.kind != ConstVal.INT)
                            return false;
                        bits = c.i;
                    }
                    for (int i = 0; i < t.size(); i++)
                        data[l.offset + i] = (byte) (bits >>> (8 * i));
                    break;
                }
                case BITFIELD: {
                    ConstVal c = eval(l.value);
                    if (c == null || c.kind != ConstVal.INT)
                        return false;
                    CType.Field f = l.field;
                    int csize = f.type.size();
                    long container = 0;
                    for (int i = 0; i < csize; i++)
                        container |= (data[l.offset + i] & 0xFFL) << (8 * i);
                    long mask = (f.bitWidth >= 64 ? -1L : (1L << f.bitWidth) - 1) << f.bitOffset;
                    container = (container & ~mask) | ((c.i << f.bitOffset) & mask);
                    for (int i = 0; i < csize; i++)
                        data[l.offset + i] = (byte) (container >>> (8 * i));
                    break;
                }
                default:
                    return false;
            }
        }
        return true;
    }

    private void localVar(VarDecl vd) {
        if (vd.isTypedef) {
            typedef(vd);
            return;
        }
        if (vd.isExtern) {
            Object o = lookupGlobal(vd.name);
            if (o instanceof Sym.Var gv) {
                declare(vd, vd.name, gv);
                vd.var = gv;
            } else {
                CType t = resolve(vd.type);
                Sym.Var v = new Sym.Var(vd.name, t, Sym.Var.Storage.GLOBAL, vd.file, vd.line, vd.col);
                v.isExtern = true;
                global.names.put(vd.name, v);
                declare(vd, vd.name, v);
                vd.var = v;
            }
            return;
        }
        List<Temp> save = tempSink;
        tempSink = new ArrayList<>();
        try {
            vlaTop = !vd.isStatic && vd.type.k == TypeNode.K.ARRAY ? vd.type : null;
            vlaBound = null;
            CType t;
            try {
                t = declaredType(vd);
            } finally {
                vlaTop = null;
            }
            if (vlaBound != null) {
                Expr bound = vlaBound;
                vlaBound = null;
                vlaLocal(vd, t, bound);
                if (!tempSink.isEmpty())
                    temps.put(vd, tempSink);
                return;
            }
            if (t.isVoid()) {
                error(vd, "variable or field '" + vd.name + "' declared void");
                return;
            }
            if (t.isFunction()) {
                // A block-scope function declaration.
                return;
            }
            InitResult r = initialise(vd, t, vd.isStatic);
            if (vd.isStatic) {
                Sym.Var v = new Sym.Var(vd.name, r.type, Sym.Var.Storage.STATIC_LOCAL, vd.file, vd.line, vd.col);
                v.isRef = r.type.isRef();
                v.defined = true;
                declare(vd, vd.name, v);
                vd.var = v;
                allocateStatic(v, r.plan, vd);
                if (!tempSink.isEmpty())
                    temps.put(vd, tempSink);
                return;
            }
            Sym.Var v = new Sym.Var(vd.name, r.type, Sym.Var.Storage.LOCAL, vd.file, vd.line, vd.col);
            v.isRef = r.type.isRef();
            v.defined = true;
            if (vd.alignAttr != null)
                v.alignAttr = alignment(vd.alignAttr, false, vd);
            if (r.type.isArray() || r.type.isStruct() || r.type.isString())
                v.addressTaken = true;
            declare(vd, vd.name, v);
            vd.var = v;
            curFn.locals.add(v);
            if (vd.init != null || vd.ctorArgs != null || r.type.isRef() || r.type.isNonTrivial() || hasDefaults(r.type))
                live.add(new LiveDecl(vd, v));
            if (r.plan != null)
                localPlans.put(vd, r.plan);
            if (!v.isRef && r.type.isConst && r.type.isScalar() && r.plan != null && r.plan.leaves.size() == 1) {
                ConstVal c = eval(r.plan.leaves.get(0).value);
                if (c != null && c.kind == ConstVal.INT)
                    v.constValue = c.i;
                else if (c != null && c.kind == ConstVal.FLOAT)
                    v.constValue = c.f;
            }
            if (!tempSink.isEmpty())
                temps.put(vd, tempSink);
        } finally {
            tempSink = save;
        }
    }

    /**
     * {@code T name[bound];} with a run-time bound: storage carved off the stack when
     * the declaration runs (CodeGen lowers SP by the size rounded up to 8, and puts it
     * back when the scope ends), as GCC does.
     */
    private void vlaLocal(VarDecl vd, CType t, Expr bound) {
        if (vd.init != null || vd.ctorArgs != null)
            error(vd, "variable-sized object '" + vd.name + "' may not be initialized");
        if (t.base.isNonTrivial())
            error(vd, "variable-length array of '" + t.base + "' is not supported on this PLC (its elements need constructors)");
        Sym.Var v = new Sym.Var(vd.name, t, Sym.Var.Storage.LOCAL, vd.file, vd.line, vd.col);
        v.defined = true;
        v.byAddress = true;
        v.addressTaken = true;
        v.vlaBound = bound;
        Sym.Var size = new Sym.Var(vd.name + ".size", CType.UINT, Sym.Var.Storage.LOCAL, vd.file, vd.line, vd.col);
        size.defined = true;
        v.vlaSize = size;
        declare(vd, vd.name, v);
        vd.var = v;
        curFn.locals.add(v);
        curFn.locals.add(size);
        // Jumping into its scope is as wrong as crossing an initialisation (g++: "jump into
        // scope of identifier with variably modified type").
        live.add(new LiveDecl(vd, v));
    }

    // ================= initialiser plans =================

    private static final class Cursor {
        final List<Expr> items;
        int i;

        Cursor(List<Expr> items) {
            this.items = items;
        }

        boolean has() {
            return i < items.size();
        }

        Expr peek() {
            return items.get(i);
        }
    }

    @Override
    InitPlan planInit(CType t, Expr init, List<Expr> ctorArgs, Node at, boolean direct) {
        InitPlan p = planInit0(t, init, ctorArgs, at, direct);
        if (p != null && t.isNonTrivial())
            completeStrings(p, t);
        return p;
    }

    /**
     * Gives every String the initialiser did not mention its default constructor, in
     * member order. Arduino's {@code String()} is {@code String(const char *cstr = "")}:
     * it allocates a one-byte buffer, so a default-constructed String is not the
     * all-zero object a C struct would be, and a run of them costs heap.
     */
    private void completeStrings(InitPlan p, CType t) {
        java.util.TreeSet<Integer> covered = new java.util.TreeSet<>();
        for (Leaf l : p.leaves) {
            if (l.k == Leaf.K.STRING_CTOR) {
                covered.add(l.offset);
            } else if (l.k == Leaf.K.BLOCK_COPY) {
                for (int o : CodeGen.stringOffsets(l.type))
                    covered.add(l.offset + o);
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int o : CodeGen.stringOffsets(t))
            if (!covered.contains(o))
                missing.add(o);
        int i = 0;
        while (i < missing.size()) {
            int j = i + 1;
            while (j < missing.size() && missing.get(j) == missing.get(j - 1) + 12)
                j++;
            int run = j - i;
            Leaf l = SemaExpr.emptyStringLeaf();
            l.offset = missing.get(i);
            if (run >= 3) {
                l.ctor = Intrinsics.Op.STR_CTOR_ARRAY;
                l.ctorArgs.add(new IntLit(run, CType.UINT));
                l.ctorArgs.add(new IntLit(12, CType.UINT));
                p.leaves.add(l);
                i = j;
            } else {
                p.leaves.add(l);
                i++;
            }
        }
        p.zeroFill = true;
        p.leaves.sort(java.util.Comparator.comparingInt(x -> x.offset));
    }

    private InitPlan planInit0(CType t, Expr init, List<Expr> ctorArgs, Node at, boolean direct) {
        InitPlan p = new InitPlan();
        if (init == null && ctorArgs == null) {
            if (t.isNonTrivial() || hasDefaults(t)) {
                p.zeroFill = true;
                defaults(t, 0, p, at);
                return p;
            }
            return null;
        }
        if (ctorArgs != null) {
            if (t.isString()) {
                List<Expr> args = new ArrayList<>();
                for (Expr a : ctorArgs)
                    args.add(check(a));
                p.leaves.add(stringCtorLeaf(args, 0, at));
                return p;
            }
            if (ctorArgs.size() != 1) {
                error(at, t.isScalar() ? "expression list treated as compound expression in initializer [-fpermissive]"
                        : "no matching function for call to '" + t + "::" + t + "(...)'");
                if (ctorArgs.isEmpty())
                    return null;
            }
            Expr e = check(ctorArgs.get(ctorArgs.size() - 1));
            if (t.isArray()) {
                error(at, "array must be initialized with a brace-enclosed initializer");
                return null;
            }
            if (t.isStruct()) {
                p.leaves.add(blockLeaf(t, 0, convert(e, t, at, "")));
                return p;
            }
            p.leaves.add(scalarLeaf(t, 0, convert(e, t, e, " in initialization")));
            return p;
        }
        if (init instanceof InitList il) {
            if (t.isString()) {
                if (il.items.isEmpty()) {
                    p.leaves.add(emptyStringLeaf());
                } else {
                    List<Expr> args = new ArrayList<>();
                    for (Expr a : il.items)
                        args.add(check(a));
                    p.leaves.add(stringCtorLeaf(args, 0, at));
                }
                return p;
            }
            if (t.isScalar()) {
                if (il.items.isEmpty()) {
                    p.leaves.add(scalarLeaf(t, 0, t.isFloating() ? new FloatLit(0, t.unqualified()).at(il) : new IntLit(0, t.unqualified()).at(il)));
                } else {
                    if (il.items.size() > 1)
                        error(il, "scalar object requires one element in initializer");
                    Expr e = il.items.get(0);
                    if (e instanceof InitList inner) {
                        if (inner.items.size() != 1) {
                            error(inner, "braces around scalar initializer for type '" + t + "'");
                            return null;
                        }
                        e = inner.items.get(0);
                    }
                    Expr v = check(e);
                    narrowingCheck(v, t, il);
                    p.leaves.add(scalarLeaf(t, 0, convert(v, t, il, " in initialization")));
                }
                return p;
            }
            p.zeroFill = true;
            Cursor c = new Cursor(il.items);
            aggregate(t, 0, c, p, il);
            if (c.has())
                error(c.peek(), "too many initializers for '" + t + "'");
            return p;
        }
        Expr e = check(init);
        if (t.isString()) {
            p.leaves.add(stringLeaf(e, 0, at, false));
            return p;
        }
        if (t.isArray()) {
            if (isCharType(t.base) && e instanceof StrLit s) {
                p.leaves.add(charsLeaf(t, 0, s, at));
                if (s.bytes.length + 1 < t.length)
                    p.zeroFill = true;
                return p;
            }
            error(at, "array must be initialized with a brace-enclosed initializer");
            return null;
        }
        if (t.isStruct()) {
            Expr v = convert(e, t, e, "");
            p.leaves.add(blockLeaf(t, 0, v));
            return p;
        }
        p.leaves.add(scalarLeaf(t, 0, convert(e, t, e, " in initialization")));
        return p;
    }

    /**
     * C++11 list-initialisation forbids narrowing. GCC makes a narrowing constant an
     * error ({@code uint8_t a[] = {300}}) and a narrowing non-constant a warning
     * ({@code uint8_t a[] = {x}} with an int x), both under -Wnarrowing.
     */
    void narrowingCheck(Expr v, CType t, Node at) {
        CType f = v.type;
        if (f == null || !t.isArithmetic() || !f.isArithmetic())
            return;
        ConstVal c = eval(v);
        boolean narrowing;
        if (f.isFloating() && t.isIntegral())
            narrowing = true;
        else if (f.isFloating() && t.isFloating())
            narrowing = f.size() > t.size() && c == null;
        else if (t.isFloating())
            narrowing = c == null;
        else
            narrowing = c == null ? (f.size() > t.size() || (f.size() == t.size() && f.isUnsigned() != t.isUnsigned()))
                    : (c.kind == ConstVal.INT && !fits(c.i, f, t));
        if (!narrowing || t.kind == CType.Kind.BOOL)
            return;
        String msg = "narrowing conversion of '" + describeConst(v, c) + "' from '" + f + "' to '" + t + "' [-Wnarrowing]";
        if (c != null)
            error(at, msg);
        else
            warning(at, msg);
    }

    /** Whether the constant {@code v} of type {@code from} keeps its value in {@code to}. */
    private static boolean fits(long v, CType from, CType to) {
        if (from.isUnsigned() && from.is64() && v < 0)
            return false; // above LLONG_MAX: nothing narrower holds it, and nothing signed
        return normalize(v, to) == v && (v >= 0 || !to.isUnsigned());
    }

    private static String describeConst(Expr v, ConstVal c) {
        if (c != null && c.kind == ConstVal.INT)
            return Long.toString(c.i);
        return describeExpr(v);
    }

    private boolean hasDefaults(CType t) {
        if (t.isStruct())
            return t.struct.hasDefaultInits || t.struct.fields.stream().anyMatch(f -> hasDefaults(f.type));
        if (t.isArray())
            return hasDefaults(t.base);
        return false;
    }

    /** Default member initialisers of a struct that nothing else initialised. */
    private void defaults(CType t, int off, InitPlan p, Node at) {
        if (t.isStruct()) {
            for (CType.Field f : t.struct.fields) {
                if (f.defaultInit != null)
                    leafFromDefault(f, off + f.offset, p, at);
                else if (hasDefaults(f.type))
                    defaults(f.type, off + f.offset, p, at);
                if (t.struct.isUnion)
                    break;
            }
        } else if (t.isArray() && hasDefaults(t.base)) {
            for (int i = 0; i < t.length; i++)
                defaults(t.base, off + i * t.base.size(), p, at);
        }
    }

    private void leafFromDefault(CType.Field f, int off, InitPlan p, Node at) {
        Expr e = (Expr) f.defaultInit;
        if (e instanceof InitList il) {
            braced(f.type, off, il, p, f);
            return;
        }
        Expr v = check(e);
        f.defaultInit = v;
        leaf(f.type, off, v, p, f, at);
    }

    /** Initialises one aggregate from a cursor (brace elision); returns how many elements it consumed. */
    private int aggregate(CType t, int off, Cursor c, InitPlan p, Node at) {
        if (t.isArray()) {
            CType et = t.base;
            int n = t.length;
            int i = 0;
            for (; (n < 0 || i < n) && c.has(); i++)
                element(et, off + i * et.size(), c, p, null, at);
            return i;
        }
        if (t.isStruct()) {
            if (c.has() && c.peek() instanceof Designated)
                return designated(t, off, c, p, at);
            int i = 0;
            for (CType.Field f : t.struct.fields) {
                if (f.name == null && !f.isAnonymousAggregate())
                    continue; // an unnamed bit-field takes no initialiser
                if (c.has()) {
                    element(f.type, off + f.offset, c, p, f, at);
                    i++;
                } else if (f.defaultInit != null) {
                    leafFromDefault(f, off + f.offset, p, at);
                } else if (hasDefaults(f.type)) {
                    defaults(f.type, off + f.offset, p, at);
                }
                if (t.struct.isUnion)
                    break;
            }
            return i;
        }
        if (c.has()) {
            element(t, off, c, p, null, at);
            return 1;
        }
        return 0;
    }

    /**
     * {@code {.x = 1, .z = 2}}: GNU designated initialisers, as g++ takes them in
     * gnu++17 (and C++20 standardised): each names a member, in declaration order;
     * members not named get their default member initialiser or zero.
     */
    private int designated(CType t, int off, Cursor c, InitPlan p, Node at) {
        CType.StructInfo s = t.struct;
        List<CType.Field> named = new ArrayList<>();
        int last = -1;
        int count = 0;
        while (c.has()) {
            Expr item = c.peek();
            c.i++;
            if (!(item instanceof Designated dz)) {
                error(item, "either all initializer clauses should be designated or none of them should be");
                continue;
            }
            CType.Field f = s.byName.get(dz.field);
            if (f == null) {
                error(dz, "'" + t + "' has no non-static data member named '" + dz.field + "'");
                continue;
            }
            CType.Field slot = f.liftedFrom != null ? f.liftedFrom : f;
            int idx = s.fields.indexOf(slot);
            if (idx <= last && !(s.isUnion && count == 0))
                error(dz, "designator order for field '" + t + "::" + dz.field + "' does not match declaration order in '" + t + "'");
            if (s.isUnion && count > 0)
                error(dz, "too many initializers for '" + t + "'");
            last = Math.max(last, idx);
            named.add(slot);
            Cursor one = new Cursor(new ArrayList<>(List.of(dz.value)));
            element(f.type, off + f.offset, one, p, f, dz);
            count++;
        }
        if (!s.isUnion) {
            for (CType.Field f : s.fields) {
                if (named.contains(f))
                    continue;
                if (f.defaultInit != null)
                    leafFromDefault(f, off + f.offset, p, at);
                else if (hasDefaults(f.type))
                    defaults(f.type, off + f.offset, p, at);
            }
        }
        return count;
    }

    private void element(CType et, int off, Cursor c, InitPlan p, CType.Field field, Node at) {
        Expr item = c.peek();
        if (item instanceof InitList il) {
            c.i++;
            braced(et, off, il, p, field);
            return;
        }
        if (item instanceof Designated dz) {
            c.i++;
            error(dz, et.isStruct() ? "either all initializer clauses should be designated or none of them should be"
                    : "designated initializer for '" + et + "', which is not a struct or union");
            return;
        }
        boolean aggregate = et.isArray() || et.isStruct();
        if (aggregate) {
            Expr v = check(item);
            c.items.set(c.i, v);
            if (et.isArray() && isCharType(et.base) && v instanceof StrLit s) {
                c.i++;
                p.leaves.add(charsLeaf(et, off, s, v));
                return;
            }
            if (et.isStruct() && v.type != null && v.type.isStruct() && v.type.struct == et.struct) {
                c.i++;
                p.leaves.add(blockLeaf(et, off, v));
                return;
            }
            aggregate(et, off, c, p, at);
            return;
        }
        c.i++;
        Expr v = check(item);
        if (et.isArithmetic())
            narrowingCheck(v, et, item);
        leaf(et, off, v, p, field, at);
    }

    private void braced(CType et, int off, InitList il, InitPlan p, CType.Field field) {
        Expr str = bracedString(et, il);
        if (str != null) {
            leaf(et, off, str, p, field, il);
            return;
        }
        if (et.isArray() || et.isStruct()) {
            Cursor sub = new Cursor(il.items);
            aggregate(et, off, sub, p, il);
            if (sub.has())
                error(sub.peek(), "too many initializers for '" + et + "'");
            return;
        }
        if (et.isString()) {
            if (il.items.isEmpty()) {
                Leaf l = emptyStringLeaf();
                l.offset = off;
                p.leaves.add(l);
            } else {
                List<Expr> args = new ArrayList<>();
                for (Expr a : il.items)
                    args.add(check(a));
                p.leaves.add(stringCtorLeaf(args, off, il));
            }
            return;
        }
        if (il.items.isEmpty())
            return; // zero, which zeroFill already provides
        if (il.items.size() > 1)
            error(il, "too many initializers for '" + et + "'");
        Expr v = check(il.items.get(0));
        narrowingCheck(v, et, il);
        leaf(et, off, v, p, field, il);
    }

    private void leaf(CType et, int off, Expr item, InitPlan p, CType.Field field, Node at) {
        Expr v = check(item);
        if (et.isString()) {
            p.leaves.add(stringLeaf(v, off, item, false));
            return;
        }
        if (et.isArray()) {
            if (isCharType(et.base) && v instanceof StrLit s) {
                p.leaves.add(charsLeaf(et, off, s, item));
                return;
            }
            error(item, "array must be initialized with a brace-enclosed initializer");
            return;
        }
        if (et.isStruct()) {
            p.leaves.add(blockLeaf(et, off, convert(v, et, item, "")));
            return;
        }
        Expr cv = convert(v, et, item, " in initialization");
        if (field != null && field.isBitfield()) {
            Leaf l = new Leaf();
            l.k = Leaf.K.BITFIELD;
            l.offset = off;
            l.type = field.type;
            l.field = field;
            l.value = cv;
            p.leaves.add(l);
            return;
        }
        p.leaves.add(scalarLeaf(et, off, cv));
    }

    private Leaf charsLeaf(CType arr, int off, StrLit s, Node at) {
        int n = arr.length;
        if (n >= 0 && s.bytes.length > n)
            error(at, "initializer-string for 'char [" + n + "]' is too long [-fpermissive]");
        else if (n >= 0 && s.bytes.length == n)
            error(at, "initializer-string for 'char [" + n + "]' is too long [-fpermissive]");
        Leaf l = new Leaf();
        l.k = Leaf.K.CHARS;
        l.offset = off;
        l.type = arr;
        int len = n < 0 ? s.bytes.length + 1 : Math.min(n, s.bytes.length + 1);
        l.chars = new byte[len];
        System.arraycopy(s.bytes, 0, l.chars, 0, Math.min(len, s.bytes.length));
        return l;
    }

    private static Leaf scalarLeaf(CType t, int off, Expr v) {
        Leaf l = new Leaf();
        l.k = Leaf.K.SCALAR;
        l.offset = off;
        l.type = t.unqualified();
        l.value = v;
        return l;
    }

    private static Leaf blockLeaf(CType t, int off, Expr v) {
        Leaf l = new Leaf();
        l.k = Leaf.K.BLOCK_COPY;
        l.offset = off;
        l.type = t.unqualified();
        l.value = v;
        return l;
    }

    // ================= statements =================

    private void stmt(Stmt s) {
        if (s instanceof Block b) {
            push(false);
            try {
                for (Stmt x : b.items)
                    stmt(x);
            } finally {
                pop();
            }
        } else if (s instanceof ExprStmt es) {
            es.expr = fullExpr(es, () -> check(es.expr));
        } else if (s instanceof DeclStmt ds) {
            for (Node n : ds.decls) {
                if (n instanceof VarDecl vd)
                    localVar(vd);
                else if (n instanceof StructDecl sd)
                    structType(sd);
                else if (n instanceof EnumDecl ed)
                    enumType(ed);
                else if (n instanceof StaticAssert sa)
                    staticAssert(sa);
                else if (n instanceof FuncDecl fd)
                    blockFunction(fd);
            }
        } else if (s instanceof If i) {
            push(false);
            try {
                if (i.init != null)
                    stmt(i.init);
                i.cond = fullExpr(i, () -> condition(i.cond));
                redeclaredInBody(i.init, i.then);
                if (i.otherwise != null)
                    redeclaredInBody(i.init, i.otherwise);
                scoped(i.then);
                if (i.otherwise != null)
                    scoped(i.otherwise);
            } finally {
                pop();
            }
        } else if (s instanceof While w) {
            w.cond = fullExpr(w, () -> condition(w.cond));
            loopDepth++;
            scoped(w.body);
            loopDepth--;
        } else if (s instanceof DoWhile dw) {
            loopDepth++;
            scoped(dw.body);
            loopDepth--;
            dw.cond = fullExpr(dw, () -> condition(dw.cond));
        } else if (s instanceof For f) {
            push(false);
            try {
                if (f.rangeVar != null)
                    lowerRangeFor(f);
                if (f.init != null)
                    stmt(f.init);
                if (f.cond != null)
                    f.cond = fullExpr(f, () -> condition(f.cond));
                if (f.step != null) {
                    Expr step = f.step;
                    Expr checked = fullExpr(step, () -> check(step));
                    if (checked != step) {
                        List<Temp> t = temps.remove(step);
                        if (t != null)
                            temps.put(checked, t);
                    }
                    f.step = checked;
                }
                redeclaredInBody(f.init, f.body);
                loopDepth++;
                scoped(f.body);
                loopDepth--;
            } finally {
                pop();
            }
        } else if (s instanceof Switch sw) {
            sw.cond = fullExpr(sw, () -> {
                Expr c = value(sw.cond);
                if (c.type.isString() || c.type.isStruct() || !(c.type.isIntegral())) {
                    error(sw, "switch quantity not an integer");
                    return new IntLit(0, CType.INT).at(c);
                }
                if (c.type.isScopedEnum())
                    return retype(c, c.type.base);
                return arith(c, promote(c.type));
            });
            switches.push(sw);
            switchMarks.put(sw, live.size());
            try {
                scoped(sw.body);
            } finally {
                switches.pop();
            }
        } else if (s instanceof Case c) {
            Switch sw = switches.peek();
            if (sw != null) {
                // Entering the scope of an initialised local declared since the switch began.
                int mark = switchMarks.getOrDefault(sw, live.size());
                if (live.size() > mark) {
                    error(c, "jump to case label");
                    for (int i = mark; i < live.size(); i++)
                        note(live.get(i).decl, crossing(live.get(i).var));
                }
            }
            if (sw == null) {
                error(c, c.value == null ? "'default' label not within a switch statement" : "case label not within a switch statement");
            } else if (c.value == null) {
                if (sw.dflt != null)
                    error(c, "multiple default labels in one switch");
                sw.dflt = c;
            } else {
                Expr v = value(c.value);
                Long k = v.type.isIntegral() ? intConst(arith(v.type.isScopedEnum() ? retype(v, v.type.base) : v, sw.cond.type)) : null;
                if (k == null) {
                    error(c, "case label does not reduce to an integer constant");
                } else {
                    c.constant = normalize(k, sw.cond.type);
                    Expr hiE = parsedRanges.get(c);
                    if (hiE != null) {
                        Long hi = intConst(value(hiE));
                        if (hi == null)
                            error(c, "case label does not reduce to an integer constant");
                        else
                            caseHi.put(c, normalize(hi, sw.cond.type));
                    }
                    CType ct = sw.cond.type;
                    long hi = caseHi.getOrDefault(c, c.constant);
                    if (caseCompare(hi, c.constant, ct) < 0) {
                        warning(c, "empty range specified");
                    } else {
                        for (Case o : sw.cases) {
                            long olo = o.constant, ohi = caseHi.getOrDefault(o, o.constant);
                            if (caseCompare(ohi, olo, ct) < 0)
                                continue;
                            if (caseCompare(c.constant, ohi, ct) <= 0 && caseCompare(hi, olo, ct) >= 0) {
                                error(c, "duplicate case value");
                                break;
                            }
                        }
                    }
                    sw.cases.add(c);
                }
            }
            stmt(c.body);
        } else if (s instanceof Labeled l) {
            if (labels.containsKey(l.label))
                error(l, "duplicate label '" + l.label + "'");
            labels.put(l.label, l);
            liveAtLabel.put(l, new ArrayList<>(live));
            stmt(l.body);
        } else if (s instanceof Goto g) {
            gotos.add(g);
            liveAtGoto.put(g, new ArrayList<>(live));
        } else if (s instanceof Break b) {
            if (loopDepth == 0 && switches.isEmpty())
                error(b, "break statement not within loop or switch");
        } else if (s instanceof Continue c) {
            if (loopDepth == 0)
                error(c, "continue statement not within a loop");
        } else if (s instanceof Return r) {
            returnStmt(r);
        }
    }

    /** The note naming a declaration a jump skips. */
    private static String crossing(Sym.Var v) {
        if (v.vlaBound != null)
            return "  enters scope of '" + v.name + "', which has variably modified type";
        return "  crosses initialization of '" + v.type.spell(v.name) + "'";
    }

    /**
     * A name declared in a for-init or an if's condition belongs to the same
     * declarative region as the outermost block of the statement it controls
     * ([basic.scope.block]/2), so {@code for (int i = 0; ...) { int i = 5; }} is a
     * redeclaration, as g++ says.
     */
    private void redeclaredInBody(Stmt init, Stmt body) {
        if (!(init instanceof DeclStmt ids) || !(body instanceof Block b))
            return;
        for (Node n : ids.decls) {
            if (!(n instanceof VarDecl iv) || iv.var == null)
                continue;
            for (Stmt st : b.items) {
                if (!(st instanceof DeclStmt ds))
                    continue;
                for (Node m : ds.decls) {
                    if (m instanceof VarDecl bv && !bv.isExtern && !bv.isTypedef && iv.name != null && iv.name.equals(bv.name)) {
                        error(bv, "redeclaration of '" + iv.var.type.spell(iv.name) + "'");
                        note(iv, "'" + iv.var.type.spell(iv.name) + "' previously declared here");
                    }
                }
            }
        }
    }

    /**
     * A function declared at block scope, {@code void setup() { int four(); ... }}:
     * it names the namespace-scope function (C++ [basic.link]/7), so it is declared
     * there like a prototype, and the name is made visible in this block so that it
     * hides a same-named local of an enclosing block, as in g++. A definition here is
     * the parser's error.
     */
    private void blockFunction(FuncDecl fd) {
        flushPending(fd.name);
        Sym.Func f = declareFunction(fd, true);
        if (f == null)
            return;
        Object ov = global.names.get(fd.name);
        if (ov instanceof Sym.Overloads && cur != global && cur.names.get(fd.name) != ov)
            declare(fd, fd.name, ov);
    }

    /**
     * Orders two case values as the switch's (promoted) type does. Values are kept
     * normalised, so only an unsigned 64-bit type needs an unsigned comparison: its
     * upper half is stored as negative longs.
     */
    static int caseCompare(long a, long b, CType t) {
        return t.is64() && t.isUnsigned() ? Long.compareUnsigned(a, b) : Long.compare(a, b);
    }

    private void scoped(Stmt s) {
        push(false);
        try {
            stmt(s);
        } finally {
            pop();
        }
    }

    private Expr fullExpr(Object key, java.util.function.Supplier<Expr> f) {
        List<Temp> save = tempSink;
        tempSink = new ArrayList<>();
        try {
            Expr r = f.get();
            if (!tempSink.isEmpty())
                temps.put(key, tempSink);
            return r;
        } finally {
            tempSink = save;
        }
    }

    /**
     * {@code for (T x : arr)} becomes a pointer walk over the array; over a String,
     * the walk from {@code begin()} to {@code end()} that ArduinoCore-API's String
     * provides, through a reference to the String evaluated once.
     */
    private void lowerRangeFor(For f) {
        VarDecl listDecl = null;
        if (f.range instanceof InitList il) {
            // "for (x : {1, 2, 3})": the list is a std::initializer_list<T>, whose
            // elements are a hidden const T[] array living as long as the loop.
            listDecl = rangeList(f, il);
            if (listDecl == null) {
                f.rangeVar = null;
                f.body = new Empty().at(f);
                return;
            }
        }
        // Checked here to see what it is; its temporaries (a String returned by a call)
        // belong to the hidden declaration that holds it, and live as long as the loop.
        List<Temp> save = tempSink;
        List<Temp> rangeTemps = new ArrayList<>();
        tempSink = rangeTemps;
        Expr range;
        try {
            if (listDecl != null) {
                range = ident(listDecl.name, f);
                range.type = resolve(listDecl.type);
                range.lvalue = true;
            } else {
                range = check(f.range);
            }
        } finally {
            tempSink = save;
        }
        if (range.type.isString()) {
            lowerStringRangeFor(f, range, rangeTemps);
            return;
        }
        if (!range.type.isArray() || range.type.length < 0) {
            error(f, "range-based 'for' over '" + range.type + "' is not supported: only arrays of known size and String");
            f.rangeVar = null;
            f.body = new Empty().at(f);
            return;
        }
        int n = hiddenCount++;
        String b = "__range_begin" + n, e = "__range_end" + n;
        CType pt = CType.pointer(range.type.base);
        DeclStmt init = new DeclStmt().at(f);
        VarDecl vb = new VarDecl().at(f);
        vb.name = b;
        vb.type = TypeNode.prim(pt);
        vb.init = listDecl != null ? ident(listDecl.name, f) : decay(range);
        if (!rangeTemps.isEmpty())
            temps.put(vb, rangeTemps);
        if (listDecl != null)
            init.decls.add(listDecl);
        VarDecl ve = new VarDecl().at(f);
        ve.name = e;
        ve.type = TypeNode.prim(pt);
        Binary end = new Binary().at(f);
        end.op = "+";
        end.l = ident(b, f);
        end.r = new IntLit(range.type.length, CType.INT).at(f);
        ve.init = end;
        init.decls.add(vb);
        init.decls.add(ve);
        f.init = init;
        Binary cond = new Binary().at(f);
        cond.op = "!=";
        cond.l = ident(b, f);
        cond.r = ident(e, f);
        f.cond = cond;
        Unary step = new Unary().at(f);
        step.op = "++x";
        step.e = ident(b, f);
        f.step = step;
        Block body = new Block().at(f);
        DeclStmt elem = new DeclStmt().at(f.rangeVar);
        VarDecl x = f.rangeVar;
        Unary deref = new Unary().at(f.rangeVar);
        deref.op = "*";
        deref.e = ident(b, f);
        x.init = deref;
        elem.decls.add(x);
        body.items.add(elem);
        body.items.add(f.body);
        body.endLine = f.line;
        f.body = body;
        f.rangeVar = null;
        f.range = null;
    }

    /**
     * The hidden array behind a braced range, {@code const T __range_list[n] = {...}},
     * with T deduced as {@code std::initializer_list<auto>} deduces it: every element
     * must have the same (decayed, unqualified) type. Null after an error.
     */
    private VarDecl rangeList(For f, InitList il) {
        CType et = null;
        InitList items = new InitList().at(il);
        for (Expr a : il.items) {
            if (a instanceof InitList) {
                error(a, "unable to deduce 'std::initializer_list<auto>' from a nested brace-enclosed list");
                return null;
            }
            Expr v = decay(check(a));
            CType t = v.type.unqualified();
            if (et == null) {
                et = t;
            } else if (!et.equals(t)) {
                error(a, "unable to deduce 'std::initializer_list<auto>' from '{...}': deduced conflicting types '" + et + "' and '" + t + "'");
                return null;
            }
            items.items.add(v);
        }
        if (et == null || et.isVoid()) {
            error(il, "unable to deduce 'std::initializer_list<auto>' from '{}'");
            return null;
        }
        VarDecl vl = new VarDecl().at(f);
        vl.name = "__range_list" + hiddenCount++;
        vl.type = TypeNode.prim(CType.array(et.withConst(true), items.items.size()));
        vl.init = items;
        return vl;
    }

    private void lowerStringRangeFor(For f, Expr range, List<Temp> rangeTemps) {
        int n = hiddenCount++;
        String r = "__range" + n, b = "__range_begin" + n, e = "__range_end" + n;
        DeclStmt init = new DeclStmt().at(f);
        VarDecl vr = new VarDecl().at(f);
        vr.name = r;
        TypeNode st = TypeNode.prim(CType.STRING);
        st.isConst = range.type.isConst || !range.lvalue || isTempClass(range); // auto&& binds a temporary too
        vr.type = TypeNode.derived(TypeNode.K.REF, st);
        vr.init = range;
        if (!rangeTemps.isEmpty())
            temps.put(vr, rangeTemps);
        VarDecl vb = new VarDecl().at(f);
        vb.name = b;
        vb.type = TypeNode.prim(CType.pointer(CType.CHAR));
        vb.init = stringCall(r, "begin", f);
        VarDecl ve = new VarDecl().at(f);
        ve.name = e;
        ve.type = TypeNode.prim(CType.pointer(CType.CHAR));
        ve.init = stringCall(r, "end", f);
        init.decls.add(vr);
        init.decls.add(vb);
        init.decls.add(ve);
        f.init = init;
        Binary cond = new Binary().at(f);
        cond.op = "!=";
        cond.l = ident(b, f);
        cond.r = ident(e, f);
        f.cond = cond;
        Unary step = new Unary().at(f);
        step.op = "++x";
        step.e = ident(b, f);
        f.step = step;
        Block body = new Block().at(f);
        DeclStmt elem = new DeclStmt().at(f.rangeVar);
        VarDecl x = f.rangeVar;
        Unary deref = new Unary().at(f.rangeVar);
        deref.op = "*";
        deref.e = ident(b, f);
        x.init = deref;
        elem.decls.add(x);
        body.items.add(elem);
        body.items.add(f.body);
        body.endLine = f.line;
        f.body = body;
        f.rangeVar = null;
        f.range = null;
    }

    private static Call stringCall(String obj, String method, Node at) {
        Ast.Member m = new Ast.Member().at(at);
        m.obj = ident(obj, at);
        m.name = method;
        Call c = new Call().at(at);
        c.fn = m;
        return c;
    }

    private static Ident ident(String name, Node at) {
        Ident i = new Ident().at(at);
        i.name = name;
        return i;
    }

    private void returnStmt(Return r) {
        if (curFn == null)
            return;
        if (r.value == null) {
            if (curRetAuto && curRet == null)
                curRet = CType.VOID;
            else if (curRet != null && !curRet.isVoid())
                error(r, "return-statement with no value, in function returning '" + curRet + "' [-fpermissive]");
            return;
        }
        List<Temp> save = tempSink;
        tempSink = new ArrayList<>();
        try {
            if (curRetAuto && curRet == null) {
                Expr v = check(r.value);
                r.value = v;
                curRet = v.type.isString() || v.type.isStruct() ? v.type.unqualified() : decay(v).type.unqualified();
            }
            CType rt = curRet;
            if (rt.isVoid()) {
                Expr v = check(r.value);
                if (!v.type.isVoid())
                    error(r, "return-statement with a value, in function returning 'void' [-fpermissive]");
                r.value = convert(v, CType.VOID, r, "");
            } else if (rt.isString()) {
                Expr v = r.value instanceof InitList il ? constructFromListString(il) : check(r.value);
                InitPlan p = new InitPlan();
                Leaf l = stringLeaf(v, 0, r, false);
                if (v instanceof VarRef vr && (vr.var.storage == Sym.Var.Storage.LOCAL || vr.var.storage == Sym.Var.Storage.PARAM)
                        && !vr.var.isRef && l.ctor == Intrinsics.Op.STR_CTOR_COPY)
                    l.ctor = Intrinsics.Op.STR_CTOR_MOVE; // implicit move of a local on return
                p.leaves.add(l);
                returnPlans.put(r, p);
                r.value = null;
            } else if (rt.isStruct()) {
                InitPlan p;
                if (r.value instanceof InitList il) {
                    p = planInit(rt, il, null, r, false);
                } else {
                    p = new InitPlan();
                    p.leaves.add(blockLeaf(rt, 0, convert(check(r.value), rt, r, " in return")));
                }
                returnPlans.put(r, p == null ? new InitPlan() : p);
                r.value = null;
            } else if (rt.isRef()) {
                Expr v = check(r.value);
                if (v instanceof VarRef vr && (vr.var.storage == Sym.Var.Storage.LOCAL || (vr.var.storage == Sym.Var.Storage.PARAM && !vr.var.isRef)))
                    warning(r, "reference to local variable '" + vr.var.name + "' returned [-Wreturn-local-addr]");
                r.value = bindRef(v, rt, r);
            } else {
                Expr v;
                if (r.value instanceof InitList il) {
                    v = il.items.isEmpty() ? new IntLit(0, rt).at(il) : check(il.items.get(0));
                } else {
                    v = check(r.value);
                }
                if (v instanceof AddrOf a && a.e instanceof VarRef vr && vr.var.storage == Sym.Var.Storage.LOCAL)
                    warning(r, "address of local variable '" + vr.var.name + "' returned [-Wreturn-local-addr]");
                r.value = convert(v, rt, r, " in return");
            }
            if (!tempSink.isEmpty())
                temps.put(r, tempSink);
        } finally {
            tempSink = save;
        }
    }

    private Expr constructFromListString(InitList il) {
        Temp t = newTemp(CType.STRING);
        TempRef tr = new TempRef().at(il);
        tr.temp = t;
        tr.type = CType.STRING;
        tr.lvalue = true;
        tr.plan = new InitPlan();
        List<Expr> args = new ArrayList<>();
        for (Expr a : il.items)
            args.add(check(a));
        tr.plan.leaves.add(args.isEmpty() ? emptyStringLeaf() : stringCtorLeaf(args, 0, il));
        return tr;
    }
}
