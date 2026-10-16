package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.lang.Ast.*;

/**
 * Type-checks expressions and rewrites them into the canonical typed form
 * {@link CodeGen} consumes. Every implicit conversion becomes a {@link Conv}, every
 * call is bound to one callee by C++ overload resolution, every String operation
 * becomes an {@link Intrinsic} on an object whose address is known, and every
 * class-typed prvalue gets a {@link Temp} to live in.
 */
abstract class SemaExpr extends SemaBase {

    /** The function whose body is being checked, or null at file scope. */
    Sym.Func curFn;
    /** Its return type; null while a lambda's {@code auto} return type is still being deduced. */
    CType curRet;
    boolean curRetAuto;

    SemaExpr(Diagnostics d) {
        super(d);
    }

    abstract CType resolve(TypeNode tn);

    abstract InitPlan planInit(CType t, Expr init, List<Expr> ctorArgs, Node at, boolean direct);

    abstract Sym.Func checkLambda(Lambda l);

    abstract void narrowingCheck(Expr v, CType t, Node at);

    // ================= entry points =================

    Temp newTemp(CType t) {
        Temp tmp = new Temp(t.unqualified());
        if (tempSink != null && unevaluated == 0)
            tempSink.add(tmp);
        return tmp;
    }

    /** A checked expression in a value context: arrays and functions decay to pointers. */
    Expr value(Expr e) {
        return decay(check(e));
    }

    Expr decay(Expr e) {
        if (e.type == null)
            return e;
        // [conv.prom]/5: a bit-field promotes to int when int holds all its values (even
        // when declared unsigned, or long long), else to unsigned int when that does.
        if (e instanceof Ast.Member m && m.field != null && m.field.isBitfield()) {
            CType.Kind k = m.type.kind;
            int w = m.field.bitWidth;
            boolean unsigned = m.type.isUnsigned();
            if ((k == CType.Kind.UINT || k == CType.Kind.ULONG || k == CType.Kind.ULLONG) && w < 32)
                return arith(e, CType.INT);
            if (k == CType.Kind.LLONG && w <= 32)
                return arith(e, CType.INT);
            if (k == CType.Kind.ULLONG && w == 32 && unsigned)
                return arith(e, CType.UINT);
        }
        if (e.type.isArray()) {
            markAddressTaken(e);
            return new Conv(Conv.K.DECAY, e, CType.pointer(e.type.base)).at(e);
        }
        if (e instanceof FuncRef f) {
            if (f.overloads != null && f.overloads.size() > 1) {
                error(e, "cannot resolve overloaded function '" + nameOf(f.overloads.get(0)) + "' based on conversion to type");
                pickOverload(f, f.overloads.get(0));
            }
            markFuncAddress(f);
            return new Conv(Conv.K.DECAY, f, CType.pointer(f.type)).at(e);
        }
        if (e instanceof Deref dr && e.type.isFunction())
            return dr.p;
        return e;
    }

    void markAddressTaken(Expr e) {
        if (e instanceof VarRef v)
            v.var.addressTaken = true;
        else if (e instanceof Ast.Member m && !m.arrow)
            markAddressTaken(m.obj);
        else if (e instanceof Conv c && c.k == Conv.K.DECAY)
            markAddressTaken(c.e);
    }

    void markFuncAddress(FuncRef f) {
        if (unevaluated > 0)
            return;
        if (f.func != null) {
            f.func.used = true;
            boolean first = !f.func.takesAddress;
            f.func.takesAddress = true;
            if (first && f.func.type != null && f.func.type.variadic)
                error(f, "taking the address of variadic function '" + f.func.name + "' is not supported on this PLC: " + NO_VARARGS);
        }
        if (f.builtin != null)
            f.builtin.used = true;
    }

    static String nameOf(Object callable) {
        if (callable instanceof Sym.Func f)
            return f.name;
        if (callable instanceof Sym.Builtin b)
            return b.name;
        if (callable instanceof Intrinsics.Op o)
            return o.kind == Intrinsics.Kind.CTOR ? "String::String" : "String::" + o.name;
        return "?";
    }

    static CType typeOfCallable(Object c) {
        if (c instanceof Sym.Func f)
            return f.type;
        if (c instanceof Sym.Builtin b)
            return b.type;
        return ((Intrinsics.Op) c).fnType();
    }

    private void pickOverload(FuncRef f, Object c) {
        f.func = c instanceof Sym.Func fn ? fn : null;
        f.builtin = c instanceof Sym.Builtin b ? b : null;
        f.type = typeOfCallable(c);
        f.overloads = null;
    }

    /**
     * [over.over]: the member of an overload set whose type is exactly the function
     * type a pointer is being made for, as GCC chooses it in an initialisation,
     * assignment or argument; null if none is.
     */
    static Object matchOverload(FuncRef f, CType fnType) {
        Object match = null;
        for (Object o : f.overloads != null ? f.overloads : List.of(f.func != null ? f.func : f.builtin)) {
            if (typeOfCallable(o).equals(fnType.unqualified()))
                match = o;
        }
        return match;
    }

    /** An overload set used as an argument: left unresolved (no error yet) until the parameter type is known. */
    private static boolean isOverloadSet(Expr e) {
        return e instanceof Conv c && c.k == Conv.K.DECAY && c.e instanceof FuncRef f && f.overloads != null;
    }

    /**
     * A call argument before overload resolution: decayed, except an array (a
     * reference-to-array parameter binds to the array itself) and an overload set
     * (resolved by the parameter's function pointer type).
     */
    private Expr argument(Expr a) {
        if (a instanceof FuncRef f && f.overloads != null)
            return new Conv(Conv.K.DECAY, f, CType.pointer(f.type)).at(f);
        if (a.type != null && a.type.isArray())
            return a;
        return decay(a);
    }

    // ================= dispatch =================

    Expr check(Expr e) {
        if (e.type != null && !(e instanceof IntLit) && !(e instanceof FloatLit))
            return e; // already checked (shared default arguments)
        Expr r = checkImpl(e);
        if (r.file == 0 && r.line == 0)
            r.at(e);
        return r;
    }

    private Expr checkImpl(Expr e) {
        if (e instanceof IntLit || e instanceof FloatLit)
            return e;
        if (e instanceof StrLit s) {
            s.global = stringGlobal(s.bytes);
            s.type = CType.array(CType.CHAR.withConst(true), s.bytes.length + 1);
            s.lvalue = true;
            return s;
        }
        if (e instanceof NullPtr n) {
            n.type = CType.NULLPTR;
            return n;
        }
        if (e instanceof Ident id)
            return ident(id);
        if (e instanceof Unary u)
            return unary(u);
        if (e instanceof Binary b)
            return binary(b);
        if (e instanceof Assign a)
            return assign(a);
        if (e instanceof Cond c)
            return conditional(c);
        if (e instanceof Call c)
            return call(c);
        if (e instanceof Ast.Member m)
            return member(m);
        if (e instanceof Index ix)
            return index(ix);
        if (e instanceof Cast c)
            return cast(c);
        if (e instanceof Construct c)
            return construct(c);
        if (e instanceof SizeofType s) {
            CType t = resolve(s.typeNode);
            if (!s.alignof && !t.isComplete() && !t.isString()) {
                error(s, "invalid application of 'sizeof' to " + (t.isFunction() ? "a function type" : "incomplete type '" + t + "'"));
                return new IntLit(1, CType.UINT).at(s);
            }
            if (t.isRef())
                t = t.base;
            return new IntLit(s.alignof ? t.align() : t.size(), CType.UINT).at(s);
        }
        if (e instanceof SizeofExpr s) {
            unevaluated++;
            Expr x;
            try {
                x = check(s.e);
            } finally {
                unevaluated--;
            }
            CType t = x.type;
            if (x instanceof VarRef vr && vr.var.vlaSize != null) {
                // A variable-length array's size is known only at run time.
                VarRef size = new VarRef(vr.var.vlaSize).at(s);
                size.type = CType.UINT;
                size.lvalue = true;
                vr.var.vlaSize.used = true;
                return value(size);
            }
            if (x instanceof Ast.Member m && m.field != null && m.field.isBitfield())
                error(s, "invalid application of 'sizeof' to a bit-field");
            if (t.isFunction() || x instanceof FuncRef) {
                error(s, "invalid application of 'sizeof' to a function type");
                return new IntLit(1, CType.UINT).at(s);
            }
            if (!t.isComplete() && !t.isString()) {
                error(s, "invalid application of 'sizeof' to incomplete type '" + t + "'");
                return new IntLit(1, CType.UINT).at(s);
            }
            return new IntLit(t.size(), CType.UINT).at(s);
        }
        if (e instanceof InitList il) {
            error(il, "braced initializer list used where an expression is expected");
            return new IntLit(0, CType.INT).at(il);
        }
        if (e instanceof Designated dz) {
            error(dz, "designated initializers are only allowed for the members of a struct or union");
            return new IntLit(0, CType.INT).at(dz);
        }
        if (e instanceof New n)
            return newExpr(n);
        if (e instanceof Delete dl)
            return deleteExpr(dl);
        if (e instanceof Lambda l) {
            Sym.Func f = checkLambda(l);
            FuncRef fr = new FuncRef().at(l);
            fr.func = f;
            fr.type = f.type;
            return fr;
        }
        // Already-typed forms built by Sema itself.
        return e;
    }

    // ================= names =================

    private Expr ident(Ident id) {
        Object s;
        if (id.scope != null && !id.scope.isEmpty() && !id.scope.equals("std")) {
            Object scope = lookup(id.scope);
            if (scope instanceof Sym.TypeName tn && tn.type.isEnum()) {
                Long v = tn.type.enumInfo.values.get(id.name);
                if (v == null) {
                    error(id, "'" + id.name + "' is not a member of '" + id.scope + "'");
                    return new IntLit(0, CType.INT).at(id);
                }
                return new IntLit(v, tn.type.unqualified()).at(id);
            }
            if (scope instanceof Sym.TypeName tn && tn.type.isStruct()) {
                CType.Field f = tn.type.struct.complete ? tn.type.struct.byName.get(id.name) : null;
                if (f == null) {
                    error(id, "'" + id.name + "' is not a member of '" + id.scope + "'");
                    return new IntLit(0, CType.INT).at(id);
                }
                if (unevaluated == 0) {
                    error(id, "invalid use of non-static data member '" + id.scope + "::" + id.name + "'");
                    return new IntLit(0, CType.INT).at(id);
                }
                // sizeof(S::x), decltype(S::x): C++11 lets an unevaluated operand name a member without an object.
                Ast.Member m = new Ast.Member().at(id);
                m.obj = new Deref(new IntLit(0, CType.pointer(tn.type)).at(id), tn.type).at(id);
                m.name = id.name;
                m.field = f;
                m.type = f.type;
                m.lvalue = true;
                return m;
            }
            error(id, "'" + id.scope + "' has not been declared");
            return new IntLit(0, CType.INT).at(id);
        }
        s = id.scope != null ? lookupGlobal(id.name) : lookup(id.name);
        if (s == null && curFn != null && (id.name.equals("__func__") || id.name.equals("__FUNCTION__") || id.name.equals("__PRETTY_FUNCTION__")))
            return functionName(id);
        if (s == null) {
            error(id, "'" + id.name + "' was not declared in this scope");
            if (COMMS_NAMES.contains(id.name))
                note(id, "this PLC has no serial port, I2C, SPI, CAN, Ethernet or EEPROM: it only has its 8 inputs and 9 outputs");
            Sym.Var dummy = new Sym.Var(id.name, CType.INT, Sym.Var.Storage.GLOBAL, id.file, id.line, id.col);
            declareErrorRecovery(id.name, dummy);
            return new IntLit(0, CType.INT).at(id);
        }
        if (s instanceof Sym.Var v) {
            if (curFn != null && v.storage != Sym.Var.Storage.GLOBAL && v.storage != Sym.Var.Storage.STATIC_LOCAL
                    && !curFnOwns(v)) {
                error(id, "use of local variable '" + id.name + "' inside a lambda: lambda captures are not supported");
                return new IntLit(0, CType.INT).at(id);
            }
            if (unevaluated == 0)
                v.used = true;
            VarRef r = new VarRef(v).at(id);
            r.type = v.objectType();
            r.lvalue = true;
            return r;
        }
        if (s instanceof Sym.EnumConst c)
            return new IntLit(c.value, c.type).at(id);
        if (s instanceof Sym.Overloads o) {
            if (o.items.isEmpty()) {
                // A function named in its own default argument, before its declaration completes.
                error(id, "'" + id.name + "' was not declared in this scope");
                return new IntLit(0, CType.INT).at(id);
            }
            FuncRef f = new FuncRef().at(id);
            if (o.items.size() == 1) {
                pickOverload(f, o.items.get(0));
            } else {
                f.overloads = new ArrayList<>(o.items);
                f.type = typeOfCallable(o.items.get(0));
            }
            return f;
        }
        error(id, "expected primary-expression before '" + id.name + "'");
        return new IntLit(0, CType.INT).at(id);
    }

    /**
     * {@code __func__}: C++11 [dcl.fct.def.general]/8, a function-local
     * {@code static const char __func__[] = "name";}. GCC's {@code __FUNCTION__} is the
     * same and {@code __PRETTY_FUNCTION__} spells the signature. Inside a lambda the
     * name is its {@code operator()}, as GCC has it.
     */
    private Expr functionName(Ident id) {
        String name;
        boolean lambda = curFn.def != null && curFn.def.isLambda;
        if (id.name.equals("__PRETTY_FUNCTION__"))
            name = lambda ? "<lambda()>" : curFn.type.base.spell(curFn.signature());
        else
            name = lambda ? "operator()" : curFn.name;
        StrLit s = new StrLit().at(id);
        s.bytes = name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        s.predefined = true;
        return check(s);
    }

    /** After an "undeclared" error, declare the name so it is reported once. */
    private void declareErrorRecovery(String name, Sym.Var v) {
        Scope s = cur;
        while (s.parent != null && !s.function)
            s = s.parent;
        s.names.putIfAbsent(name, v);
    }

    /** Whether a local belongs to the function being checked (and not to one enclosing a lambda). */
    boolean curFnOwns(Sym.Var v) {
        return curFn.locals.contains(v) || curFn.params.contains(v) || curFn.sret == v;
    }

    // ================= conversions =================

    /**
     * The rank of the implicit conversion sequence from {@code e} to {@code to}:
     * 0 exact match, 1 promotion, 2 conversion, 3 user-defined (a String built from a
     * char pointer), -1 none. {@code e} is already decayed.
     */
    int rank(Expr e, CType to) {
        if (e instanceof InitList il)
            return listRank(il, to);
        if (e.type != null && e.type.isArray() && !(to.isRef() && to.base.isArray()))
            e = new Conv(Conv.K.DECAY, e, CType.pointer(e.type.base)).at(e);
        CType from = e.type;
        if (from == null)
            return -1;
        if (to.isRef()) {
            CType target = to.base;
            if (target.isArray() && from.isArray()) {
                // int (&a)[3]: binds to an array lvalue of that element type and size.
                boolean ok = e.lvalue && target.length == from.length && target.base.sameUnqualified(from.base)
                        && (!from.base.isConst || target.base.isConst) && (!from.base.isVolatile || target.base.isVolatile);
                return ok ? 0 : -1;
            }
            boolean same = target.sameUnqualified(from);
            if (!target.isConst) {
                if (e.lvalue && same && !(from.isConst && !target.isConst) && !isTempClass(e))
                    return 0;
                return -1;
            }
            if ((e.lvalue || isTempClass(e)) && same)
                return 0;
            int r = rank(e, target.unqualified());
            return r;
        }
        if (to.isString()) {
            if (from.isString())
                return 0;
            if (from.isPointer() && isCharType(from.base) || isFlashString(from))
                return 3;
            return -1;
        }
        if (to.isStruct())
            return from.isStruct() && from.struct == to.struct ? 0 : -1;
        if (from.isString())
            return to.kind == CType.Kind.BOOL ? 3 : -1;
        if (from.isStruct())
            return -1;
        if (from.sameUnqualified(to))
            return 0;
        if (to.isArithmetic() || to.isScopedEnum()) {
            if (!(from.isArithmetic() || from.isEnum()))
                return to.kind == CType.Kind.BOOL && (from.isPointer() || from.kind == CType.Kind.NULLPTR) ? 2 : -1;
            if (to.isEnum() || from.isScopedEnum())
                return -1;
            if (from.isIntegral() && to.kind == CType.Kind.INT && promote(from).kind == CType.Kind.INT && from.kind != CType.Kind.INT)
                return 1;
            if (from.kind == CType.Kind.FLOAT && to.kind == CType.Kind.DOUBLE)
                return 1;
            return 2;
        }
        if (to.isPointer()) {
            if (isOverloadSet(e))
                return to.base.isFunction() && matchOverload((FuncRef) ((Conv) e).e, to.base) != null ? 0 : -1;
            if (from.kind == CType.Kind.NULLPTR || isNullConstant(e))
                return 2;
            if (!from.isPointer())
                return -1;
            CType fp = from.base, tp = to.base;
            // A string literal to char*: deprecated, ill-formed since C++11, but GCC only
            // warns (-Wwrite-strings) and treats it as a viable conversion.
            if (isStringLiteral(e) && tp.kind == CType.Kind.CHAR && fp.kind == CType.Kind.CHAR && !tp.isConst)
                return 2;
            if (fp.sameUnqualified(tp))
                return (!fp.isConst || tp.isConst) && (!fp.isVolatile || tp.isVolatile) ? 0 : -1;
            if (tp.isVoid() && !fp.isFunction())
                return (!fp.isConst || tp.isConst) ? 2 : -1;
            return -1;
        }
        if (to.kind == CType.Kind.NULLPTR)
            return from.kind == CType.Kind.NULLPTR ? 0 : -1;
        return -1;
    }

    /**
     * A braced list as an argument ({@code f({1, 2})}): it list-initialises the
     * parameter, which works for an aggregate, a String, or a scalar from at most one
     * element; never for a non-const lvalue reference.
     */
    private static int listRank(InitList il, CType to) {
        CType t = to.isRef() ? to.base : to;
        if (to.isRef() && !t.isConst)
            return -1;
        if (t.isStruct())
            return 2;
        if (t.isString())
            return 3;
        if (t.isScalar() && !t.isEnum())
            return il.items.size() <= 1 ? 2 : -1;
        return -1;
    }

    static boolean isCharType(CType t) {
        return t.kind == CType.Kind.CHAR || t.kind == CType.Kind.SCHAR || t.kind == CType.Kind.UCHAR;
    }

    /** Implicit conversion of a checked expression to {@code to}, reporting what GCC would. */
    Expr convert(Expr e, CType to, Node at, String context) {
        if (e instanceof FuncRef f && f.overloads != null && to.isPointer() && to.base.isFunction())
            e = new Conv(Conv.K.DECAY, f, CType.pointer(f.type)).at(f);
        else
            e = decay(e);
        if (to.isPointer() && to.base.isFunction() && e instanceof Conv c && c.k == Conv.K.DECAY && c.e instanceof FuncRef f) {
            // A function name (overloaded or not) converted to a function pointer: pick by type.
            Object match = matchOverload(f, to.base);
            if (match != null) {
                pickOverload(f, match);
                markFuncAddress(f);
                c.type = to.unqualified();
                return c;
            }
            if (f.overloads != null) {
                error(at, "no matches converting function '" + nameOf(f.overloads.get(0)) + "' to type '" + to + "'");
                for (Object o : f.overloads)
                    note(at, "candidate is: '" + describeCallable(o) + "'");
                pickOverload(f, f.overloads.get(0));
                markFuncAddress(f);
                return retype(c, to.unqualified());
            }
        }
        if (isOverloadSet(e)) {
            FuncRef f = (FuncRef) ((Conv) e).e;
            error(at, "cannot resolve overloaded function '" + nameOf(f.overloads.get(0)) + "' based on conversion to type '" + to + "'");
            pickOverload(f, f.overloads.get(0));
            markFuncAddress(f);
            ((Conv) e).type = CType.pointer(f.type);
        }
        CType from = e.type;
        if (from == null)
            return e;
        if (to.isRef())
            return bindRef(e, to, at);
        if (to.isVoid())
            return new Conv(Conv.K.TO_VOID, e, CType.VOID).at(e);
        if (to.isString())
            return stringObject(e, at);
        if (to.isStruct()) {
            if (from.isStruct() && from.struct == to.struct)
                return e;
            error(at, "conversion from '" + from + "' to non-scalar type '" + to + "' requested");
            return e;
        }
        if (to.isArray()) {
            error(at, "invalid array assignment");
            return e;
        }
        if (from.isString()) {
            if (to.kind == CType.Kind.BOOL)
                return new Conv(Conv.K.STRING_TO_BOOL, e, CType.BOOL).at(e);
            error(at, "cannot convert 'String' to '" + to + "'" + context);
            return new IntLit(0, to.unqualified()).at(e);
        }
        if (from.isStruct()) {
            error(at, "cannot convert '" + from + "' to '" + to + "'" + context);
            return new IntLit(0, to.unqualified()).at(e);
        }
        CType tu = to.unqualified();
        if (from.sameUnqualified(to))
            return e.type == tu || e.lvalue ? e : retype(e, tu);
        if (to.isArithmetic() || to.isEnum()) {
            if (from.isArithmetic() || from.isEnum()) {
                if (to.isEnum() && !(from.isEnum() && from.enumInfo == to.enumInfo)) {
                    error(at, "invalid conversion from '" + from + "' to '" + to + "'" + (to.isScopedEnum() ? "" : " [-fpermissive]"));
                    return new IntLit(0, tu).at(e);
                }
                if (from.isScopedEnum() && to.kind != CType.Kind.BOOL) {
                    error(at, "cannot convert '" + from + "' to '" + to + "'" + context);
                    return new IntLit(0, tu).at(e);
                }
                if (from.isScopedEnum()) {
                    error(at, "could not convert '" + from + "' to 'bool'");
                    return new IntLit(0, tu).at(e);
                }
                return arith(e, tu);
            }
            if (to.kind == CType.Kind.BOOL && (from.isPointer() || from.kind == CType.Kind.NULLPTR))
                return fold(new Conv(Conv.K.TO_BOOL, e, CType.BOOL).at(e));
            error(at, "invalid conversion from '" + from + "' to '" + to + "' [-fpermissive]");
            return new IntLit(0, tu).at(e);
        }
        if (to.isPointer()) {
            if (from.kind == CType.Kind.NULLPTR || isNullConstant(e))
                return new IntLit(0, tu).at(e);
            if (from.isPointer()) {
                int r = rank(e, to);
                if (r >= 0) {
                    if (r == 2 && isStringLiteral(e) && !to.base.isConst)
                        warning(at, "ISO C++ forbids converting a string constant to '" + to + "' [-Wwrite-strings]");
                    return retype(e, tu);
                }
                error(at, "invalid conversion from '" + from + "' to '" + to + "' [-fpermissive]");
                return retype(e, tu);
            }
            error(at, "invalid conversion from '" + from + "' to '" + to + "' [-fpermissive]");
            return new IntLit(0, tu).at(e);
        }
        if (to.kind == CType.Kind.NULLPTR) {
            if (from.kind == CType.Kind.NULLPTR)
                return e;
        }
        error(at, "cannot convert '" + from + "' to '" + to + "'" + context);
        return e;
    }

    /** Same value, different static type (qualification, pointer kinds). */
    static Expr retype(Expr e, CType t) {
        Conv c = new Conv(Conv.K.NOP, e, t);
        c.at(e);
        return c;
    }

    /** Arithmetic-to-arithmetic conversion, folded when the operand is constant. */
    Expr arith(Expr e, CType to) {
        CType from = e.type;
        Conv.K k;
        if (to.kind == CType.Kind.BOOL && from.kind != CType.Kind.BOOL)
            k = Conv.K.TO_BOOL;
        else if (from.isFloating() && to.isFloating())
            k = from.kind == to.kind || (from.isDoubleLike() && to.isDoubleLike()) ? Conv.K.NOP : Conv.K.FLOAT_TO_FLOAT;
        else if (from.isFloating())
            k = Conv.K.FLOAT_TO_INT;
        else if (to.isFloating())
            k = Conv.K.INT_TO_FLOAT;
        else if (sameRepr(from, to))
            k = Conv.K.NOP;
        else
            k = Conv.K.INT;
        if (k == Conv.K.NOP && !e.lvalue && e.type.equals(to))
            return e;
        return fold(new Conv(k, e, to).at(e));
    }

    /** Integer types whose 32/64-bit register representation is identical. */
    static boolean sameRepr(CType a, CType b) {
        if (a.isEnum())
            a = a.base;
        if (b.isEnum())
            b = b.base;
        if (a.size() != b.size())
            return false;
        if (a.size() >= 4)
            return a.is64() == b.is64();
        return a.isUnsigned() == b.isUnsigned() && a.kind != CType.Kind.BOOL && b.kind != CType.Kind.BOOL;
    }

    /** Replaces a constant conversion by its literal value. */
    Expr fold(Expr e) {
        if (!(e instanceof Conv c) || c.k == Conv.K.DECAY || c.k == Conv.K.TO_VOID)
            return e;
        ConstVal v = eval(e);
        if (v == null || v.kind == ConstVal.ADDR)
            return e;
        if (v.kind == ConstVal.INT && (e.type.isIntegral() || e.type.isPointer()))
            return new IntLit(v.i, e.type).at(e);
        if (v.kind == ConstVal.FLOAT && e.type.isFloating())
            return new FloatLit(v.f, e.type).at(e);
        return e;
    }

    /** A reference bound to {@code e}: the value is an address. */
    Expr bindRef(Expr e, CType refType, Node at) {
        CType target = refType.base;
        if (target.isArray() && e.type != null && e.type.isArray() && e.lvalue && target.length == e.type.length
                && target.base.sameUnqualified(e.type.base) && (target.base.isConst || !e.type.base.isConst)
                && (target.base.isVolatile || !e.type.base.isVolatile)) {
            // const int (&)[2] bound to an int[2]: a qualification adjustment, the same address.
            markAddressTaken(e);
            return new AddrOf(e, CType.pointer(target)).at(e);
        }
        boolean same = e.type != null && target.sameUnqualified(e.type);
        if (same && (e.lvalue || isTempClass(e)) && (target.isConst || !e.type.isConst) && !(isTempClass(e) && !target.isConst)) {
            if (e instanceof Ast.Member m && m.field != null && m.field.isBitfield()) {
                error(at, "cannot bind bit-field '" + m.name + "' to '" + refType + "'");
            }
            markAddressTaken(e);
            return new AddrOf(e, CType.pointer(target)).at(e);
        }
        if (!target.isConst) {
            if (same && !e.lvalue)
                error(at, "cannot bind non-const lvalue reference of type '" + refType + "' to an rvalue of type '" + e.type + "'");
            else if (same)
                error(at, "binding reference of type '" + refType + "' to '" + e.type + "' discards qualifiers");
            else
                error(at, "invalid initialization of non-const reference of type '" + refType + "' from an rvalue of type '" + e.type + "'");
            return new AddrOf(e, CType.pointer(target)).at(e);
        }
        Temp t = newTemp(target);
        TempRef tr = new TempRef().at(e);
        tr.temp = t;
        tr.type = target.unqualified();
        tr.lvalue = true;
        if (target.isString()) {
            tr.plan = new InitPlan();
            tr.plan.leaves.add(stringLeaf(e, 0, at, true));
        } else if (target.isStruct()) {
            Expr v = convert(e, target.unqualified(), at, "");
            tr.plan = new InitPlan();
            Leaf l = new Leaf();
            l.k = Leaf.K.BLOCK_COPY;
            l.type = target.unqualified();
            l.value = v;
            tr.plan.leaves.add(l);
        } else {
            Expr v = convert(e, target.unqualified(), at, "");
            tr.plan = new InitPlan();
            Leaf l = new Leaf();
            l.k = Leaf.K.SCALAR;
            l.type = target.unqualified();
            l.value = v;
            tr.plan.leaves.add(l);
        }
        return new AddrOf(tr, CType.pointer(target)).at(e);
    }

    /**
     * How a String object at {@code offset} is built from {@code e} by copy-
     * initialisation: moved out of a temporary, copied from another String, or
     * constructed from a C string. With {@code direct}, the explicit constructors
     * (from char, numbers) are allowed too.
     */
    /**
     * {@code F("...")} is the literal cast to {@code const __FlashStringHelper *} (the
     * core's prelude, as in ArduinoCore-API). WString takes one wherever it takes a
     * {@code const char *}: its constructor, {@code =}, {@code +=}, {@code concat}, {@code +}
     * and the comparisons. On the F1 flash is in the address space, so it is read as
     * the C string it points to. Anywhere else it stays its own type, so passing one to
     * a function that wants {@code const char *} needs a cast, as with g++.
     */
    static boolean isFlashString(CType t) {
        return t != null && t.isPointer() && t.base.isStruct() && "__FlashStringHelper".equals(t.base.struct.name);
    }

    /** A {@code const __FlashStringHelper *} operand of a String operation, as the {@code const char *} it is. */
    static Expr flashAsChars(Expr e) {
        return isFlashString(e.type) ? retype(e, CType.CONST_CHAR_PTR) : e;
    }

    Leaf stringLeaf(Expr e, int offset, Node at, boolean direct) {
        e = flashAsChars(decay(e));
        Leaf l = new Leaf();
        l.k = Leaf.K.STRING_CTOR;
        l.offset = offset;
        l.type = CType.STRING;
        l.ctorArgs = new ArrayList<>();
        if (e.type != null && e.type.isString()) {
            l.ctor = isTempClass(e) ? Intrinsics.Op.STR_CTOR_MOVE : Intrinsics.Op.STR_CTOR_COPY;
            markAddressTaken(e);
            l.ctorArgs.add(new AddrOf(e, CType.pointer(CType.STRING)).at(e));
            return l;
        }
        if (e.type != null && (e.type.isPointer() && isCharType(e.type.base) || e.type.kind == CType.Kind.NULLPTR)) {
            l.ctor = Intrinsics.Op.STR_CTOR_CSTR;
            l.ctorArgs.add(convert(e, CType.CONST_CHAR_PTR, at, ""));
            return l;
        }
        if (direct) {
            List<Expr> args = new ArrayList<>();
            args.add(e);
            return stringCtorLeaf(args, offset, at);
        }
        error(at, "conversion from '" + e.type + "' to non-scalar type 'String' requested");
        l.ctor = Intrinsics.Op.STR_CTOR_EMPTY;
        l.ctorArgs.clear();
        return l;
    }

    /** {@code String(args...)}: direct-initialisation, every constructor a candidate. */
    Leaf stringCtorLeaf(List<Expr> rawArgs, int offset, Node at) {
        List<Expr> args = new ArrayList<>();
        for (Expr a : rawArgs)
            args.add(flashAsChars(decay(a)));
        Leaf l = new Leaf();
        l.k = Leaf.K.STRING_CTOR;
        l.offset = offset;
        l.type = CType.STRING;
        l.ctorArgs = new ArrayList<>();
        if (args.size() == 1 && args.get(0).type != null && args.get(0).type.isString())
            return stringLeaf(args.get(0), offset, at, false);
        List<Object> cands = new ArrayList<>(Intrinsics.constructors());
        Object best = overload(cands, args, at, "String");
        if (best == null) {
            l.ctor = Intrinsics.Op.STR_CTOR_EMPTY;
            return l;
        }
        Intrinsics.Op op = (Intrinsics.Op) best;
        l.ctor = op;
        l.ctorArgs.addAll(bindArgs(op, args, at));
        return l;
    }

    /** A String-typed expression standing for an object: the temporary itself, or a copy/construction into a new one. */
    Expr stringObject(Expr e, Node at) {
        e = decay(e);
        if (e.type != null && e.type.isString() && isTempClass(e))
            return e;
        Temp t = newTemp(CType.STRING);
        TempRef tr = new TempRef().at(e);
        tr.temp = t;
        tr.type = CType.STRING;
        tr.lvalue = true;
        tr.plan = new InitPlan();
        tr.plan.leaves.add(stringLeaf(e, 0, at, false));
        return tr;
    }

    // ================= overload resolution =================

    /**
     * Picks the best viable candidate for {@code args} (already decayed), as
     * [over.match.best] does with the ranks of {@link #rank}; reports "no matching"
     * or "ambiguous" the way GCC does and returns null then.
     */
    Object overload(List<Object> cands, List<Expr> args, Node at, String name) {
        Object best = null;
        int[] bestRanks = null;
        boolean ambiguous = false;
        List<Object> viable = new ArrayList<>();
        for (Object c : cands) {
            CType ft = typeOfCallable(c);
            int nParams = ft.params.size();
            int nDefaults = defaultCount(c);
            if (args.size() > nParams && !ft.variadic)
                continue;
            if (args.size() < nParams - nDefaults)
                continue;
            int[] ranks = new int[args.size()];
            boolean ok = true;
            for (int i = 0; i < args.size(); i++) {
                if (i >= nParams) {
                    ranks[i] = 4;
                    continue;
                }
                int r = rank(args.get(i), ft.params.get(i));
                if (r < 0) {
                    ok = false;
                    break;
                }
                ranks[i] = r;
            }
            if (!ok)
                continue;
            viable.add(c);
            if (best == null) {
                best = c;
                bestRanks = ranks;
                continue;
            }
            int cmp = compareCands(c, ranks, best, bestRanks);
            if (cmp < 0) {
                best = c;
                bestRanks = ranks;
                ambiguous = false;
            } else if (cmp == 0) {
                // Tie: a non-variadic, exactly-sized candidate wins over one using defaults or "...".
                int pa = typeOfCallable(c).params.size(), pb = typeOfCallable(best).params.size();
                if (pa == args.size() && pb != args.size()) {
                    best = c;
                    bestRanks = ranks;
                    ambiguous = false;
                } else if (!(pb == args.size() && pa != args.size())) {
                    ambiguous = true;
                }
            }
        }
        // A later candidate may have beaten an earlier one that was tied with a third.
        if (ambiguous && best != null) {
            ambiguous = false;
            for (Object c : viable) {
                if (c == best)
                    continue;
                int[] r = ranksOf(c, args);
                if (compareCands(c, r, best, bestRanks) == 0 && typeOfCallable(c).params.size() == typeOfCallable(best).params.size())
                    ambiguous = true;
            }
        }
        if (best == null) {
            error(at, "no matching function for call to '" + name + "(" + argTypes(args) + ")'");
            for (Object c : cands)
                note(at, "candidate: '" + describeCallable(c) + "'");
            return null;
        }
        if (ambiguous) {
            error(at, "call of overloaded '" + name + "(" + argTypes(args) + ")' is ambiguous");
            for (Object c : viable)
                note(at, "candidate: '" + describeCallable(c) + "'");
        }
        return best;
    }

    private int[] ranksOf(Object c, List<Expr> args) {
        CType ft = typeOfCallable(c);
        int[] ranks = new int[args.size()];
        for (int i = 0; i < args.size(); i++)
            ranks[i] = i < ft.params.size() ? rank(args.get(i), ft.params.get(i)) : 4;
        return ranks;
    }

    /**
     * [over.match.best] between two viable candidates: -1 if {@code a} is better, 1
     * if worse, 0 if neither. Argument by argument, a lower rank wins; between two
     * exact matches that bind references (or convert to pointers) to the same type,
     * the less cv-qualified one wins ([over.ics.rank]/3.2.5-6), which is what picks
     * {@code f(int&)} over {@code f(const int&)} for a plain int.
     */
    private static int compareCands(Object a, int[] ra, Object b, int[] rb) {
        CType fa = typeOfCallable(a), fb = typeOfCallable(b);
        boolean better = false, worse = false;
        for (int i = 0; i < ra.length; i++) {
            int x = Integer.compare(ra[i], rb[i]);
            if (x == 0 && ra[i] == 0 && i < fa.params.size() && i < fb.params.size())
                x = cvTieBreak(fa.params.get(i), fb.params.get(i));
            if (x < 0)
                better = true;
            else if (x > 0)
                worse = true;
        }
        if (better && !worse)
            return -1;
        if (worse && !better)
            return 1;
        return 0;
    }

    private static int cvTieBreak(CType pa, CType pb) {
        if (!(pa.isRef() && pb.isRef()) && !(pa.isPointer() && pb.isPointer()))
            return 0;
        CType a = pa.base, b = pb.base;
        if (!a.sameUnqualified(b))
            return 0;
        boolean aInB = (!a.isConst || b.isConst) && (!a.isVolatile || b.isVolatile);
        boolean bInA = (!b.isConst || a.isConst) && (!b.isVolatile || a.isVolatile);
        if (aInB && !bInA)
            return -1;
        if (bInA && !aInB)
            return 1;
        return 0;
    }

    /** -1 if {@code a} is better, 1 if worse, 0 if neither. */
    static int compareRanks(int[] a, int[] b) {
        boolean better = false, worse = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i])
                better = true;
            else if (a[i] > b[i])
                worse = true;
        }
        if (better && !worse)
            return -1;
        if (worse && !better)
            return 1;
        return 0;
    }

    static int defaultCount(Object c) {
        if (c instanceof Sym.Func f) {
            int n = 0;
            for (int i = f.defaults.size() - 1; i >= 0 && f.defaults.get(i) != null; i--)
                n++;
            return n;
        }
        if (c instanceof Sym.Builtin b) {
            int n = 0;
            for (int i = b.defaults.size() - 1; i >= 0 && b.defaults.get(i) != null; i--)
                n++;
            return n;
        }
        return ((Intrinsics.Op) c).defaults.length;
    }

    static String argTypes(List<Expr> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0)
                sb.append(", ");
            Expr a = args.get(i);
            if (a instanceof InitList)
                sb.append("<brace-enclosed initializer list>");
            else if (a.type == null || isOverloadSet(a))
                sb.append("<unresolved overloaded function type>");
            else
                sb.append(a.type.toString());
        }
        return sb.toString();
    }

    static String describeCallable(Object c) {
        CType t = typeOfCallable(c);
        StringBuilder sb = new StringBuilder(t.base.toString()).append(' ').append(nameOf(c)).append('(');
        for (int i = 0; i < t.params.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(t.params.get(i));
        }
        if (t.variadic)
            sb.append(t.params.isEmpty() ? "..." : ", ...");
        return sb.append(')').toString();
    }

    /** Converts call arguments to the chosen callee's parameters and appends defaults. */
    /**
     * Why a sketch's own {@code ...} function cannot take extra arguments: its body
     * could only reach them through {@code va_list}/{@code va_arg}, which this compiler
     * does not implement, and passing them anyway would leave them where the callee
     * never looks (the named parameters would be bound wrong). So the call is refused
     * rather than miscompiled. The core's own variadic functions (snprintf and the
     * like) take extra arguments as usual.
     */
    static final String NO_VARARGS = "variadic arguments of the sketch's own functions (va_list, va_start, va_arg) are not implemented";

    List<Expr> bindArgs(Object callee, List<Expr> args, Node at) {
        CType ft = typeOfCallable(callee);
        if (callee instanceof Sym.Func f && ft.variadic && args.size() > ft.params.size())
            error(at, "calling variadic function '" + f.name + "' with arguments beyond its named parameters is not supported on this PLC: "
                    + NO_VARARGS);
        List<Expr> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Expr a = args.get(i);
            if (i < ft.params.size()) {
                out.add(passArg(a, ft.params.get(i), at));
            } else {
                out.add(variadicArg(a, at));
            }
        }
        for (int i = args.size(); i < ft.params.size(); i++) {
            Expr dv = defaultArg(callee, i);
            if (dv == null)
                break;
            out.add(passArg(dv, ft.params.get(i), at));
        }
        return out;
    }

    private Expr defaultArg(Object callee, int i) {
        if (callee instanceof Sym.Func f)
            return i < f.defaults.size() ? f.defaults.get(i) : null;
        if (callee instanceof Sym.Builtin b)
            return i < b.defaults.size() ? b.defaults.get(i) : null;
        Intrinsics.Op op = (Intrinsics.Op) callee;
        int first = op.params.size() - op.defaults.length;
        if (i < first)
            return null;
        return new IntLit(op.defaults[i - first], op.params.get(i));
    }

    /** One argument passed to a parameter of type {@code pt}. */
    Expr passArg(Expr a, CType pt, Node at) {
        if (a instanceof InitList il)
            a = listArgument(il, pt.isRef() ? pt.base.unqualified() : pt.unqualified());
        if (pt.isRef())
            return bindRef(a, pt, at);
        if (pt.isString() || pt.isStruct()) {
            // By value: the caller's copy, passed by address (see Sym.Var#byAddress).
            Expr obj;
            if (pt.isString()) {
                obj = stringObject(a, at);
            } else {
                Expr v = convert(a, pt, at, "");
                if (isTempClass(v)) {
                    obj = v;
                } else {
                    Temp t = newTemp(pt);
                    TempRef tr = new TempRef().at(a);
                    tr.temp = t;
                    tr.type = pt.unqualified();
                    tr.lvalue = true;
                    tr.plan = new InitPlan();
                    Leaf l = new Leaf();
                    l.k = Leaf.K.BLOCK_COPY;
                    l.type = pt.unqualified();
                    l.value = v;
                    tr.plan.leaves.add(l);
                    obj = tr;
                }
            }
            return new AddrOf(obj, CType.pointer(pt.unqualified())).at(a);
        }
        return convert(a, pt, at, "");
    }

    /** A braced list argument, list-initialising a temporary (or a value) of the parameter's type. */
    private Expr listArgument(InitList il, CType t) {
        if (t.isStruct())
            return constructFromList(t, il);
        if (t.isString())
            return constructString(il);
        if (il.items.isEmpty())
            return t.isFloating() ? new FloatLit(0, t).at(il) : new IntLit(0, t).at(il);
        if (il.items.size() > 1)
            error(il, "too many initializers for '" + t + "'");
        Expr v = check(il.items.get(0));
        narrowingCheck(v, t, il);
        return v;
    }

    /** An argument matched by {@code ...}: the default argument promotions. */
    Expr variadicArg(Expr a, Node at) {
        if (a instanceof InitList il) {
            error(il, "cannot pass a braced initializer list through '...'");
            return new IntLit(0, CType.INT).at(il);
        }
        a = decay(a);
        CType t = a.type;
        if (t.isString() || t.isStruct()) {
            error(at, "cannot pass objects of non-trivially-copyable type '" + t + "' through '...'");
            return new IntLit(0, CType.INT).at(a);
        }
        if (t.kind == CType.Kind.FLOAT)
            return arith(a, CType.DOUBLE);
        if (t.isIntegral() && !t.isScopedEnum()) {
            CType p = promote(t);
            return p.equals(t) ? a : arith(a, p);
        }
        if (t.kind == CType.Kind.NULLPTR)
            return new IntLit(0, CType.VOID_PTR).at(a);
        return a;
    }

    // ================= unary =================

    private Expr unary(Unary u) {
        switch (u.op) {
            case "&": {
                Expr e = check(u.e);
                if (e instanceof FuncRef f) {
                    if (f.overloads != null) {
                        // Resolved by the conversion that consumes it.
                        Conv c = new Conv(Conv.K.DECAY, f, CType.pointer(f.type));
                        c.at(u);
                        return c;
                    }
                    markFuncAddress(f);
                    return new Conv(Conv.K.DECAY, f, CType.pointer(f.type)).at(u);
                }
                if (e instanceof Deref dr)
                    return retype(dr.p, CType.pointer(e.type));
                if (!e.lvalue && !isTempClass(e)) {
                    error(u, "lvalue required as unary '&' operand");
                    return new IntLit(0, CType.pointer(e.type)).at(u);
                }
                if (isTempClass(e))
                    error(u, "taking address of rvalue [-fpermissive]");
                if (e instanceof Ast.Member m && m.field != null && m.field.isBitfield())
                    error(u, "attempt to take address of bit-field");
                markAddressTaken(e);
                return new AddrOf(e, CType.pointer(e.type)).at(u);
            }
            case "*": {
                Expr e = value(u.e);
                if (!e.type.isPointer()) {
                    error(u, "invalid type argument of unary '*' (have '" + e.type + "')");
                    return new IntLit(0, CType.INT).at(u);
                }
                if (e.type.base.isVoid()) {
                    error(u, "'void*' is not a pointer-to-object type");
                    return new IntLit(0, CType.INT).at(u);
                }
                return new Deref(e, e.type.base).at(u);
            }
            case "+", "-", "~": {
                Expr e = value(u.e);
                CType t = e.type;
                boolean ok = u.op.equals("~") ? t.isInteger() : (t.isArithmetic() || (u.op.equals("+") && t.isPointer()));
                if (!ok) {
                    error(u, "wrong type argument to " + (u.op.equals("~") ? "bit-complement" : "unary " + (u.op.equals("-") ? "minus" : "plus")));
                    return new IntLit(0, CType.INT).at(u);
                }
                if (t.isPointer())
                    return e;
                CType p = promote(t);
                u.e = arith(e, p);
                u.type = p;
                ConstVal v = eval(u);
                if (v != null && v.kind == ConstVal.INT)
                    return new IntLit(v.i, p).at(u);
                if (v != null && v.kind == ConstVal.FLOAT)
                    return new FloatLit(v.f, p).at(u);
                return u;
            }
            case "!": {
                u.e = condition(u.e);
                u.type = CType.BOOL;
                ConstVal v = eval(u);
                if (v != null && v.kind == ConstVal.INT)
                    return new IntLit(v.i, CType.BOOL).at(u);
                return u;
            }
            default: {
                // ++x, --x, x++, x--
                Expr e = check(u.e);
                String what = u.op.contains("++") ? "increment" : "decrement";
                if (!e.lvalue) {
                    error(u, "lvalue required as " + what + " operand");
                    return new IntLit(0, CType.INT).at(u);
                }
                if (e.type.isConst) {
                    error(u, readOnlyMessage(e, what));
                }
                CType t = e.type;
                if (t.kind == CType.Kind.BOOL) {
                    error(u, "use of an operand of type 'bool' in 'operator" + (u.op.contains("++") ? "++" : "--") + "' is forbidden in C++17");
                } else if (t.isEnum()) {
                    // An enum has no built-in ++/--: the int result would not convert back.
                    String o = u.op.contains("++") ? "++" : "--";
                    error(u, u.op.endsWith("x") ? "no match for 'operator" + o + "' (operand type is '" + t + "')"
                            : "no 'operator" + o + "(int)' declared for postfix '" + o + "' [-fpermissive]");
                    return new IntLit(0, CType.INT).at(u);
                } else if (t.isPointer()) {
                    if (!t.base.isComplete() && !t.base.isVoid()) {
                        error(u, "cannot " + what + " a pointer to incomplete type '" + t.base + "'");
                    }
                    u.stride = t.base.isVoid() ? 1 : t.base.size();
                } else if (!t.isArithmetic()) {
                    error(u, "no match for 'operator" + (u.op.contains("++") ? "++" : "--") + "' (operand type is '" + t + "')");
                    return new IntLit(0, CType.INT).at(u);
                }
                u.e = e;
                u.type = t.unqualified();
                u.lvalue = u.op.endsWith("x");
                return u;
            }
        }
    }

    static String readOnlyMessage(Expr e, String what) {
        if (e instanceof VarRef v)
            return what + " of read-only variable '" + v.var.name + "'";
        if (e instanceof Ast.Member m)
            return what + " of read-only member '" + m.name + "'";
        return what + " of read-only location";
    }

    /** A condition: contextually converted to bool. */
    Expr condition(Expr e) {
        Expr c = value(e);
        CType t = c.type;
        if (t.isString())
            return new Conv(Conv.K.STRING_TO_BOOL, c, CType.BOOL).at(c);
        if (t.isScopedEnum() || t.isStruct() || t.isVoid()) {
            error(c, "could not convert '" + describeExpr(c) + "' from '" + t + "' to 'bool'");
            return new IntLit(0, CType.BOOL).at(c);
        }
        if (t.kind == CType.Kind.BOOL)
            return c;
        if (t.isArithmetic() || t.isPointer() || t.isEnum() || t.kind == CType.Kind.NULLPTR)
            return fold(new Conv(Conv.K.TO_BOOL, c, CType.BOOL).at(c));
        error(c, "could not convert expression from '" + t + "' to 'bool'");
        return new IntLit(0, CType.BOOL).at(c);
    }

    static String describeExpr(Expr e) {
        if (e instanceof VarRef v)
            return v.var.name;
        return "expression";
    }

    // ================= binary =================

    private Expr binary(Binary b) {
        String op = b.op;
        if (op.equals(",")) {
            b.l = convert(check(b.l), CType.VOID, b, "");
            Expr r = check(b.r);
            b.r = r;
            b.type = r.type;
            b.lvalue = r.lvalue;
            return b;
        }
        if (op.equals("&&") || op.equals("||")) {
            b.l = condition(b.l);
            b.r = condition(b.r);
            b.type = CType.BOOL;
            return foldBinary(b);
        }
        Expr l = value(b.l), r = value(b.r);
        CType lt = l.type, rt = r.type;
        // String operators.
        if (lt.isString() || rt.isString() || l instanceof StringSum) {
            Expr s = stringOperator(b, l, r);
            if (s != null)
                return s;
        }
        if (lt.isStruct() || rt.isStruct() || lt.isString() || rt.isString() || lt.isVoid() || rt.isVoid()) {
            error(b, "no match for 'operator" + op + "' (operand types are '" + lt + "' and '" + rt + "')");
            return new IntLit(0, CType.INT).at(b);
        }
        switch (op) {
            case "+", "-": {
                if (lt.isPointer() || rt.isPointer())
                    return pointerArith(b, l, r);
                return arithmetic(b, l, r, false);
            }
            case "*", "/":
                return arithmetic(b, l, r, false);
            case "%", "&", "|", "^":
                return arithmetic(b, l, r, true);
            case "<<", ">>": {
                if (!lt.isInteger() || !rt.isIntegral() || rt.isScopedEnum()) {
                    error(b, "invalid operands of types '" + lt + "' and '" + rt + "' to binary 'operator" + op + "'");
                    return new IntLit(0, CType.INT).at(b);
                }
                CType p = promote(lt);
                b.l = arith(l, p);
                b.r = arith(r, CType.UINT);
                b.opType = p;
                b.type = p;
                Long amt = intConst(b.r);
                if (amt != null && (amt >= p.size() * 8 || amt < 0))
                    warning(b, (op.equals("<<") ? "left" : "right") + " shift count >= width of type [-Wshift-count-overflow]");
                return foldBinary(b);
            }
            default:
                return comparison(b, l, r);
        }
    }

    private Expr arithmetic(Binary b, Expr l, Expr r, boolean integral) {
        CType lt = l.type, rt = r.type;
        boolean scoped = lt.isScopedEnum() || rt.isScopedEnum();
        if (!(lt.isArithmetic() || lt.isEnum()) || !(rt.isArithmetic() || rt.isEnum()) || scoped
                || (integral && (lt.isFloating() || rt.isFloating()))) {
            error(b, "invalid operands of types '" + lt + "' and '" + rt + "' to binary 'operator" + b.op + "'");
            return new IntLit(0, CType.INT).at(b);
        }
        CType t = usual(lt, rt);
        b.l = arith(l, t);
        b.r = arith(r, t);
        b.opType = t;
        b.type = t;
        if ((b.op.equals("/") || b.op.equals("%")) && t.isIntegral()) {
            Long z = intConst(b.r);
            if (z != null && z == 0)
                warning(b, "division by zero [-Wdiv-by-zero]");
        }
        return foldBinary(b);
    }

    private Expr pointerArith(Binary b, Expr l, Expr r) {
        CType lt = l.type, rt = r.type;
        if (lt.isPointer() && rt.isPointer()) {
            if (!b.op.equals("-") || !lt.base.sameUnqualified(rt.base)) {
                error(b, "invalid operands of types '" + lt + "' and '" + rt + "' to binary 'operator" + b.op + "'");
                return new IntLit(0, CType.INT).at(b);
            }
            b.l = l;
            b.r = r;
            b.opType = lt;
            b.stride = lt.base.isVoid() ? 1 : Math.max(1, lt.base.size());
            b.type = CType.INT;
            return foldBinary(b);
        }
        Expr p = lt.isPointer() ? l : r;
        Expr n = lt.isPointer() ? r : l;
        if (!n.type.isInteger() || (b.op.equals("-") && !lt.isPointer())) {
            error(b, "invalid operands of types '" + lt + "' and '" + rt + "' to binary 'operator" + b.op + "'");
            return new IntLit(0, CType.INT).at(b);
        }
        CType pt = p.type;
        if (pt.base.isFunction()) {
            error(b, "pointer to a function used in arithmetic");
        } else if (!pt.base.isComplete() && !pt.base.isVoid() && !pt.base.isString()) {
            error(b, "invalid use of incomplete type '" + pt.base + "'");
        }
        b.stride = pt.base.isVoid() || pt.base.isFunction() ? 1 : pt.base.size();
        Expr idx = n.type.isUnsigned() && !n.type.is64() ? arith(n, CType.UINT) : arith(n, CType.INT);
        b.l = p;
        b.r = idx;
        b.opType = pt;
        b.type = pt;
        return foldBinary(b);
    }

    /**
     * Whether the signed side of a mixed-signedness comparison is a constant GCC can
     * prove non-negative. GCC's -Wsign-compare stays quiet then, because converting it
     * to unsigned cannot change its value: {@code millis() - last >= 100} is the most
     * common line in any sketch, and warning on it would bury the real warnings.
     */
    private static boolean nonNegativeConstant(Expr signedSide) {
        Long v = intConst(signedSide);
        return v != null && v >= 0;
    }

    private Expr comparison(Binary b, Expr l, Expr r) {
        CType lt = l.type, rt = r.type;
        b.type = CType.BOOL;
        boolean eq = b.op.equals("==") || b.op.equals("!=");
        if (lt.isPointer() || rt.isPointer() || lt.kind == CType.Kind.NULLPTR || rt.kind == CType.Kind.NULLPTR) {
            CType pt;
            if (lt.isPointer() && rt.isPointer()) {
                if (!lt.base.sameUnqualified(rt.base) && !lt.base.isVoid() && !rt.base.isVoid())
                    error(b, "comparison between distinct pointer types '" + lt + "' and '" + rt + "' lacks a cast [-fpermissive]");
                pt = lt;
            } else if (lt.isPointer() && (isNullConstant(r) || rt.kind == CType.Kind.NULLPTR)) {
                pt = lt;
                r = new IntLit(0, lt).at(r);
            } else if (rt.isPointer() && (isNullConstant(l) || lt.kind == CType.Kind.NULLPTR)) {
                pt = rt;
                l = new IntLit(0, rt).at(l);
            } else if (lt.kind == CType.Kind.NULLPTR && rt.kind == CType.Kind.NULLPTR) {
                pt = CType.VOID_PTR;
            } else if (eq && ((lt.kind == CType.Kind.NULLPTR && isNullConstant(r)) || (rt.kind == CType.Kind.NULLPTR && isNullConstant(l)))) {
                // nullptr == 0, nullptr == NULL: an integer constant zero is a null pointer
                // constant, converted to std::nullptr_t ([expr.eq]/7).
                pt = CType.VOID_PTR;
                l = new IntLit(0, pt).at(l);
                r = new IntLit(0, pt).at(r);
            } else {
                error(b, "ISO C++ forbids comparison between pointer and integer [-fpermissive]");
                pt = lt.isPointer() ? lt : rt;
                l = lt.isPointer() ? l : retype(arith(l, CType.UINT), pt);
                r = rt.isPointer() ? r : retype(arith(r, CType.UINT), pt);
            }
            if (!eq && (isNullConstant(l) || isNullConstant(r)) && !(lt.isPointer() && rt.isPointer()))
                warning(b, "ordered comparison of pointer with integer zero");
            b.l = l;
            b.r = r;
            b.opType = pt.unqualified();
            return foldBinary(b);
        }
        if (lt.isScopedEnum() || rt.isScopedEnum()) {
            if (!(lt.isEnum() && rt.isEnum() && lt.enumInfo == rt.enumInfo)) {
                error(b, "no match for 'operator" + b.op + "' (operand types are '" + lt + "' and '" + rt + "')");
                return new IntLit(0, CType.BOOL).at(b);
            }
            b.l = l;
            b.r = r;
            b.opType = lt.base;
            b.l = retype(l, lt.base);
            b.r = retype(r, lt.base);
            return foldBinary(b);
        }
        if (!(lt.isArithmetic() || lt.isEnum()) || !(rt.isArithmetic() || rt.isEnum())) {
            error(b, "invalid operands of types '" + lt + "' and '" + rt + "' to binary 'operator" + b.op + "'");
            return new IntLit(0, CType.BOOL).at(b);
        }
        CType t = usual(lt, rt);
        if (!eq && t.isUnsigned() && t.isInteger() && (lt.isUnsigned() != rt.isUnsigned())
                && (promote(lt).isUnsigned() != promote(rt).isUnsigned())
                && !nonNegativeConstant(lt.isUnsigned() ? r : l))
            warning(b, "comparison of integer expressions of different signedness: '" + lt + "' and '" + rt + "' [-Wsign-compare]");
        b.l = arith(l, t);
        b.r = arith(r, t);
        b.opType = t;
        return foldBinary(b);
    }

    private Expr foldBinary(Binary b) {
        // An operand that is itself an unfolded arithmetic operation was already found
        // not constant; evaluating it again at every level would make a long chain
        // ("a + b + c + ...", "x && y && ...") quadratic to check.
        if (knownNonConstant(b.l) || knownNonConstant(b.r))
            return b;
        ConstVal v = eval(b);
        if (v == null)
            return b;
        if (v.kind == ConstVal.INT && (b.type.isIntegral()))
            return new IntLit(v.i, b.type).at(b);
        if (v.kind == ConstVal.FLOAT && b.type.isFloating())
            return new FloatLit(v.f, b.type).at(b);
        return b;
    }

    /**
     * A checked arithmetic operation that was not folded into a literal: folding
     * turns every constant integer or floating result into one, so this one is not a
     * constant. (Pointer-valued operations are not folded, so they do not count.)
     */
    private static boolean knownNonConstant(Expr e) {
        while (e instanceof Conv c && c.k != Conv.K.DECAY)
            e = c.e;
        return (e instanceof Binary || e instanceof Unary) && e.type != null && e.type.isArithmetic();
    }

    /** {@code +} with a String operand (StringSumHelper), or a String comparison. Null if not a String operator. */
    private Expr stringOperator(Binary b, Expr l, Expr r) {
        String op = b.op;
        l = flashAsChars(l);
        r = flashAsChars(r);
        if (op.equals("+")) {
            StringSum sum;
            if (l instanceof StringSum ls) {
                sum = ls;
            } else {
                sum = new StringSum().at(b);
                sum.first = sumFirst(l, b);
                sum.temp = newTemp(CType.STRING);
                sum.type = CType.STRING;
                sum.lvalue = true;
            }
            sum.rest.add(sumOperand(r, b));
            return sum;
        }
        if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
            Expr s = l, o = r;
            boolean swapped = false;
            if (!l.type.isString()) {
                s = r;
                o = l;
                swapped = true;
            }
            Intrinsic in = new Intrinsic().at(b);
            markAddressTaken(s);
            in.args.add(new AddrOf(s, CType.pointer(CType.STRING)).at(s));
            if (op.equals("==") || op.equals("!=")) {
                if (o.type.isString()) {
                    in.op = Intrinsics.Op.STR_EQUALS_STR;
                    in.args.add(bindRef(o, CType.reference(CType.STRING.withConst(true)), b));
                } else if (o.type.isPointer() && isCharType(o.type.base) || isNullConstant(o)) {
                    in.op = Intrinsics.Op.STR_EQUALS_CSTR;
                    in.args.add(convert(o, CType.CONST_CHAR_PTR, b, ""));
                } else {
                    return null;
                }
                in.type = CType.UCHAR;
                if (op.equals("!=")) {
                    Unary not = new Unary().at(b);
                    not.op = "!";
                    not.e = new Conv(Conv.K.TO_BOOL, in, CType.BOOL).at(b);
                    not.type = CType.BOOL;
                    return not;
                }
                return new Conv(Conv.K.TO_BOOL, in, CType.BOOL).at(b);
            }
            if (rank(o, CType.reference(CType.STRING.withConst(true))) < 0)
                return null;
            in.op = Intrinsics.Op.STR_COMPARE;
            in.args.add(bindRef(o, CType.reference(CType.STRING.withConst(true)), b));
            in.type = CType.INT;
            String cop = op;
            if (swapped)
                cop = switch (op) {
                    case "<" -> ">";
                    case ">" -> "<";
                    case "<=" -> ">=";
                    default -> "<=";
                };
            Binary cmp = new Binary().at(b);
            cmp.op = cop;
            cmp.l = in;
            cmp.r = new IntLit(0, CType.INT).at(b);
            cmp.opType = CType.INT;
            cmp.type = CType.BOOL;
            return cmp;
        }
        return null;
    }

    /** The first operand of a sum, which StringSumHelper's converting constructors turn into the temporary. */
    private Leaf sumFirst(Expr e, Node at) {
        if (e.type.isString())
            return stringLeaf(e, 0, at, false);
        List<Expr> args = List.of(e);
        List<Object> cands = new ArrayList<>();
        for (Intrinsics.Op o : Intrinsics.constructors())
            if (o != Intrinsics.Op.STR_CTOR_EMPTY && o != Intrinsics.Op.STR_CTOR_COPY)
                cands.add(o);
        Leaf l = new Leaf();
        l.k = Leaf.K.STRING_CTOR;
        l.type = CType.STRING;
        l.ctorArgs = new ArrayList<>();
        Object best = overload(cands, args, at, "StringSumHelper");
        if (best == null) {
            l.ctor = Intrinsics.Op.STR_CTOR_EMPTY;
            return l;
        }
        l.ctor = (Intrinsics.Op) best;
        l.ctorArgs.addAll(bindArgs(best, args, at));
        return l;
    }

    private Intrinsic sumOperand(Expr e, Node at) {
        List<Object> cands = new ArrayList<>();
        for (Intrinsics.Op o : Intrinsics.Op.values())
            if (o.name().startsWith("STR_SUM_"))
                cands.add(o);
        Intrinsic in = new Intrinsic().at(e);
        Object best = overload(cands, List.of(e), at, "operator+");
        in.op = best == null ? Intrinsics.Op.STR_SUM_CSTR : (Intrinsics.Op) best;
        if (best != null)
            in.args.addAll(bindArgs(best, List.of(e), at));
        else
            in.args.add(new IntLit(0, CType.CONST_CHAR_PTR));
        in.type = CType.VOID;
        return in;
    }

    // ================= assignment =================

    private Expr assign(Assign a) {
        Expr l = check(a.l);
        if (l.type.isString())
            return stringAssign(a, l);
        if (!l.lvalue) {
            error(a, "lvalue required as left operand of assignment");
            check(a.r);
            return new IntLit(0, CType.INT).at(a);
        }
        if (l.type.isArray()) {
            error(a, "invalid array assignment");
            check(a.r);
            return l;
        }
        if (l.type.isConst)
            error(a, readOnlyMessage(l, "assignment"));
        if (l instanceof FuncRef) {
            error(a, "assignment of function");
            return l;
        }
        CType lt = l.type.unqualified();
        if (a.op.equals("=")) {
            Expr rhs = a.r;
            if (rhs instanceof InitList il) {
                if (lt.isStruct()) {
                    Construct c = new Construct().at(il);
                    c.typeNode = null;
                    a.r = constructFromList(lt, il);
                } else {
                    if (il.items.size() > 1)
                        error(il, "too many initializers for '" + lt + "'");
                    a.r = il.items.isEmpty() ? new IntLit(0, lt).at(il) : convert(check(il.items.get(0)), lt, a, " in assignment");
                }
            } else {
                a.r = convert(check(rhs), lt, a, " in assignment");
            }
            a.l = l;
            a.type = lt;
            a.lvalue = true;
            return a;
        }
        String bop = a.op.substring(0, a.op.length() - 1);
        Expr r = value(a.r);
        if (lt.isPointer() && (bop.equals("+") || bop.equals("-"))) {
            if (!r.type.isInteger()) {
                error(a, "invalid operands of types '" + lt + "' and '" + r.type + "' to binary 'operator" + bop + "'");
                return l;
            }
            a.stride = lt.base.isVoid() ? 1 : lt.base.size();
            a.r = r.type.isUnsigned() && !r.type.is64() ? arith(r, CType.UINT) : arith(r, CType.INT);
            a.opType = lt;
        } else {
            boolean integral = bop.equals("%") || bop.equals("&") || bop.equals("|") || bop.equals("^") || bop.equals("<<") || bop.equals(">>");
            if (!(lt.isArithmetic() || lt.isEnum()) || !(r.type.isArithmetic() || r.type.isEnum()) || lt.isScopedEnum() || r.type.isScopedEnum()
                    || (integral && (lt.isFloating() || r.type.isFloating())) || lt.isEnum()) {
                error(a, "invalid operands of types '" + lt + "' and '" + r.type + "' to binary 'operator" + bop + "'");
                return l;
            }
            if (bop.equals("<<") || bop.equals(">>")) {
                a.opType = promote(lt);
                a.r = arith(r, CType.UINT);
            } else {
                a.opType = usual(lt, r.type);
                a.r = arith(r, a.opType);
            }
        }
        a.l = l;
        a.type = lt;
        a.lvalue = true;
        return a;
    }

    /** {@code T{...}} for a struct, as a temporary. */
    Expr constructFromList(CType t, InitList il) {
        Temp tmp = newTemp(t);
        TempRef tr = new TempRef().at(il);
        tr.temp = tmp;
        tr.type = t.unqualified();
        tr.lvalue = true;
        tr.plan = planInit(t, il, null, il, false);
        return tr;
    }

    private Expr stringAssign(Assign a, Expr l) {
        if (!l.lvalue && !isTempClass(l)) {
            error(a, "lvalue required as left operand of assignment");
            return l;
        }
        if (l.type.isConst)
            error(a, "passing 'const String' as 'this' argument discards qualifiers [-fpermissive]");
        markAddressTaken(l);
        Intrinsic in = new Intrinsic().at(a);
        in.args.add(new AddrOf(l, CType.pointer(CType.STRING)).at(l));
        in.returnsThis = true;
        in.rhsFirst = true;
        if (a.op.equals("=")) {
            Expr r = a.r instanceof InitList il ? constructString(il) : flashAsChars(value(a.r));
            if (r.type.isString()) {
                in.op = isTempClass(r) ? Intrinsics.Op.STR_ASSIGN_MOVE : Intrinsics.Op.STR_ASSIGN_COPY;
                markAddressTaken(r);
                in.args.add(new AddrOf(r, CType.pointer(CType.STRING)).at(r));
            } else if (r.type.isPointer() && isCharType(r.type.base) || isNullConstant(r)) {
                in.op = Intrinsics.Op.STR_ASSIGN_CSTR;
                in.args.add(convert(r, CType.CONST_CHAR_PTR, a, ""));
            } else {
                error(a, "no match for 'operator=' (operand types are 'String' and '" + r.type + "')");
                return l;
            }
        } else if (a.op.equals("+=")) {
            Expr r = flashAsChars(value(a.r));
            List<Object> cands = new ArrayList<>(Intrinsics.methods("concat"));
            Object best = overload(cands, List.of(r), a, "String::operator+=");
            if (best == null)
                return l;
            in.op = (Intrinsics.Op) best;
            in.args.addAll(bindArgs(best, List.of(r), a));
        } else {
            error(a, "no match for 'operator" + a.op + "' (operand types are 'String' and '" + check(a.r).type + "')");
            return l;
        }
        in.type = CType.pointer(CType.STRING);
        return new Deref(in, CType.STRING).at(a);
    }

    private Expr constructString(InitList il) {
        Temp t = newTemp(CType.STRING);
        TempRef tr = new TempRef().at(il);
        tr.temp = t;
        tr.type = CType.STRING;
        tr.lvalue = true;
        tr.plan = new InitPlan();
        tr.plan.leaves.add(il.items.isEmpty() ? emptyStringLeaf() : stringCtorLeaf(il.items.stream().map(this::check).toList(), 0, il));
        return tr;
    }

    static Leaf emptyStringLeaf() {
        Leaf l = new Leaf();
        l.k = Leaf.K.STRING_CTOR;
        l.type = CType.STRING;
        l.ctor = Intrinsics.Op.STR_CTOR_EMPTY;
        l.ctorArgs = new ArrayList<>();
        return l;
    }

    // ================= conditional =================

    private Expr conditional(Cond c) {
        c.c = condition(c.c);
        Expr a = check(c.a), b = check(c.b);
        CType at = a.type, bt = b.type;
        if (at.isString() || bt.isString()) {
            // A String result: both arms build (or are) one temporary.
            if (at.isString() && bt.isString() && a.lvalue && b.lvalue && !isTempClass(a) && !isTempClass(b)) {
                c.a = a;
                c.b = b;
                c.type = CType.STRING;
                c.lvalue = true;
                return c;
            }
            Temp t = newTemp(CType.STRING);
            TempRef tr = new TempRef().at(c);
            tr.temp = t;
            tr.type = CType.STRING;
            tr.lvalue = true;
            Cond inner = new Cond().at(c);
            inner.c = c.c;
            inner.a = buildInto(t, a, c);
            inner.b = buildInto(t, b, c);
            inner.type = CType.VOID;
            tr.init = inner;
            return tr;
        }
        if (at.isVoid() || bt.isVoid()) {
            c.a = convert(a, CType.VOID, c, "");
            c.b = convert(b, CType.VOID, c, "");
            c.type = CType.VOID;
            return c;
        }
        if (at.isStruct() || bt.isStruct()) {
            if (at.isStruct() && bt.isStruct() && at.struct == bt.struct) {
                c.a = a;
                c.b = b;
                c.type = at.unqualified();
                c.lvalue = (a.lvalue || isTempClass(a)) && (b.lvalue || isTempClass(b));
                return c;
            }
            error(c, "operands to '?:' have different types '" + at + "' and '" + bt + "'");
            return a;
        }
        // Both lvalues of the same type: the result is that lvalue.
        if (a.lvalue && b.lvalue && at.sameUnqualified(bt) && !at.isArray() && !(a instanceof FuncRef)) {
            c.a = a;
            c.b = b;
            c.type = at.isConst || !bt.isConst ? at : bt;
            c.lvalue = true;
            return c;
        }
        a = decay(a);
        b = decay(b);
        at = a.type;
        bt = b.type;
        CType t;
        if ((at.isArithmetic() || at.isEnum()) && (bt.isArithmetic() || bt.isEnum())) {
            t = at.isEnum() && bt.isEnum() && at.enumInfo == bt.enumInfo ? at.unqualified() : usual(at, bt);
            c.a = arith(a, t);
            c.b = arith(b, t);
        } else if (at.isPointer() && bt.isPointer()) {
            if (at.base.sameUnqualified(bt.base))
                t = CType.pointer(at.base.withCv(at.base.isConst || bt.base.isConst, at.base.isVolatile || bt.base.isVolatile));
            else if (at.base.isVoid() || bt.base.isVoid())
                t = CType.pointer(CType.VOID.withConst(at.base.isConst || bt.base.isConst));
            else {
                error(c, "conditional expression between distinct pointer types '" + at + "' and '" + bt + "' lacks a cast");
                t = at;
            }
            c.a = retype(a, t);
            c.b = retype(b, t);
        } else if (at.isPointer() && (isNullConstant(b) || bt.kind == CType.Kind.NULLPTR)) {
            t = at;
            c.a = a;
            c.b = new IntLit(0, t).at(b);
        } else if (bt.isPointer() && (isNullConstant(a) || at.kind == CType.Kind.NULLPTR)) {
            t = bt;
            c.a = new IntLit(0, t).at(a);
            c.b = b;
        } else {
            error(c, "operands to '?:' have different types '" + at + "' and '" + bt + "'");
            t = at;
            c.a = a;
            c.b = a;
        }
        c.type = t;
        ConstVal v = eval(c);
        if (v != null && v.kind == ConstVal.INT && t.isIntegral())
            return new IntLit(v.i, t).at(c);
        return c;
    }

    /** An expression that constructs a String from {@code e} into an existing temporary. */
    private Expr buildInto(Temp t, Expr e, Node at) {
        TempRef tr = new TempRef().at(e);
        tr.temp = t;
        tr.type = CType.STRING;
        tr.lvalue = true;
        tr.plan = new InitPlan();
        tr.plan.leaves.add(stringLeaf(e, 0, at, false));
        return new Conv(Conv.K.TO_VOID, tr, CType.VOID).at(e);
    }

    // ================= calls =================

    private Expr call(Call c) {
        Expr fn = c.fn;
        if (fn instanceof Ast.Member m) {
            Expr obj = check(m.obj);
            CType ot = obj.type;
            if (m.arrow) {
                obj = decay(obj);
                ot = obj.type.isPointer() ? obj.type.base : obj.type;
            }
            if (ot.isString()) {
                Expr objLv = m.arrow ? new Deref(obj, ot).at(obj) : obj;
                return stringMethod(c, m, objLv);
            }
            fn = memberOf(m, obj);
        } else if (fn instanceof Ident id && id.scope == null) {
            Object s = lookup(id.name);
            if (s == null && "String".equals(id.name)) {
                fn = check(fn);
            } else {
                fn = check(fn);
            }
        } else {
            fn = check(fn);
        }
        List<Expr> args = new ArrayList<>();
        for (Expr a : c.args)
            args.add(a instanceof InitList il ? il : argument(check(a)));
        if (fn instanceof FuncRef f) {
            List<Object> cands = f.overloads != null ? f.overloads : List.of(f.func != null ? f.func : f.builtin);
            String name = nameOf(cands.get(0));
            Object best = overload(cands, args, c, name);
            if (best == null) {
                c.type = typeOfCallable(cands.get(0)).base;
                return errorValue(c);
            }
            return finishCall(c, best, bindArgs(best, args, c));
        }
        // Through a pointer (or a reference to a function).
        Expr callee = decay(fn);
        CType ft = callee.type.isPointer() ? callee.type.base : null;
        if (ft == null || !ft.isFunction()) {
            error(c, describeCallee(fn) + " cannot be used as a function");
            c.type = CType.INT;
            return errorValue(c);
        }
        int np = ft.params.size();
        if (args.size() < np || (args.size() > np && !ft.variadic)) {
            error(c, (args.size() < np ? "too few" : "too many") + " arguments to function");
            c.type = ft.base;
            return errorValue(c);
        }
        c.fn = callee;
        c.indirectType = ft;
        c.args.clear();
        for (int i = 0; i < args.size(); i++)
            c.args.add(i < np ? passArg(args.get(i), ft.params.get(i), c) : variadicArg(args.get(i), c));
        return callResult(c, ft.base);
    }

    private static String describeCallee(Expr fn) {
        if (fn instanceof VarRef v)
            return "'" + v.var.name + "'";
        return "expression";
    }

    private Expr errorValue(Call c) {
        CType t = c.type == null ? CType.INT : c.type;
        if (t.isVoid() || t.isString() || t.isStruct() || t.isRef())
            return new Conv(Conv.K.TO_VOID, new IntLit(0, CType.INT).at(c), CType.VOID).at(c);
        return new IntLit(0, t.unqualified()).at(c);
    }

    Expr finishCall(Call c, Object callee, List<Expr> args) {
        c.args.clear();
        c.args.addAll(args);
        if (callee instanceof Sym.Builtin b) {
            if (b.op != null) {
                Intrinsic in = new Intrinsic().at(c);
                in.op = b.op;
                in.args.addAll(args);
                in.type = b.op.ret;
                if (unevaluated == 0)
                    b.used = true;
                return in;
            }
            c.builtin = b;
            if (unevaluated == 0)
                b.used = true;
            return callResult(c, b.type.base);
        }
        Sym.Func f = (Sym.Func) callee;
        c.target = f;
        if (unevaluated == 0)
            f.used = true;
        return callResult(c, f.type.base);
    }

    private Expr callResult(Call c, CType ret) {
        if (ret.isRef()) {
            c.type = CType.pointer(ret.base);
            return new Deref(c, ret.base).at(c);
        }
        if (ret.isString() || ret.isStruct()) {
            c.sret = newTemp(ret);
            c.type = ret.unqualified();
            c.lvalue = false;
            return c;
        }
        c.type = ret.unqualified();
        return c;
    }

    private Expr stringMethod(Call c, Ast.Member m, Expr obj) {
        List<Intrinsics.Op> ms = Intrinsics.methods(m.name);
        if (ms.isEmpty()) {
            error(m, "'class String' has no member named '" + m.name + "'");
            if (m.name.equals("print") || m.name.equals("println") || m.name.equals("write"))
                note(m, "String has no printing members; this PLC has no serial port to print to");
            return new IntLit(0, CType.INT).at(c);
        }
        List<Expr> args = new ArrayList<>();
        for (Expr a : c.args)
            args.add(flashAsChars(decay(check(a))));
        Object best = overload(new ArrayList<>(ms), args, c, "String::" + m.name);
        if (best == null)
            return new IntLit(0, CType.INT).at(c);
        Intrinsics.Op op = (Intrinsics.Op) best;
        boolean mutating = switch (op) {
            case STR_IS_EMPTY, STR_BEGIN, STR_END,
                    STR_LENGTH, STR_EQUALS_STR, STR_EQUALS_CSTR, STR_EQUALS_IC, STR_COMPARE, STR_STARTS, STR_STARTS_AT,
                    STR_ENDS, STR_CHAR_AT, STR_GET_BYTES, STR_TO_CHAR_ARRAY, STR_C_STR, STR_INDEX_OF_CHAR,
                    STR_INDEX_OF_CHAR_FROM, STR_INDEX_OF_STR, STR_INDEX_OF_STR_FROM, STR_LAST_INDEX_OF_CHAR,
                    STR_LAST_INDEX_OF_CHAR_FROM, STR_LAST_INDEX_OF_STR, STR_LAST_INDEX_OF_STR_FROM, STR_SUBSTRING,
                    STR_SUBSTRING2, STR_TO_INT, STR_TO_FLOAT, STR_TO_DOUBLE -> false;
            default -> true;
        };
        if (mutating && obj.type.isConst)
            error(c, "passing 'const String' as 'this' argument discards qualifiers [-fpermissive]");
        markAddressTaken(obj);
        Intrinsic in = new Intrinsic().at(c);
        in.op = op;
        in.args.add(new AddrOf(obj, CType.pointer(CType.STRING)).at(obj));
        in.args.addAll(bindArgs(op, args, c));
        if (op.returnsString()) {
            in.result = newTemp(CType.STRING);
            in.type = CType.STRING;
            in.lvalue = false;
        } else {
            in.type = op.ret;
        }
        return in;
    }

    // ================= members and subscripts =================

    private Expr member(Ast.Member m) {
        Expr obj = check(m.obj);
        return memberOf(m, obj);
    }

    private Expr memberOf(Ast.Member m, Expr obj) {
        CType ot;
        if (m.arrow) {
            obj = decay(obj);
            if (!obj.type.isPointer()) {
                error(m, "base operand of '->' has non-pointer type '" + obj.type + "'");
                return new IntLit(0, CType.INT).at(m);
            }
            ot = obj.type.base;
        } else {
            ot = obj.type;
            if (ot.isPointer() && (ot.base.isStruct() || ot.base.isString())) {
                error(m, "request for member '" + m.name + "' in '" + describeExpr(obj) + "', which is of pointer type '" + ot + "' (maybe you meant to use '->' ?)");
                return new IntLit(0, CType.INT).at(m);
            }
        }
        if (ot.isString()) {
            if (!Intrinsics.methods(m.name).isEmpty())
                error(m, "invalid use of non-static member function 'String::" + m.name + "'");
            else
                error(m, "'class String' has no member named '" + m.name + "'");
            return new IntLit(0, CType.INT).at(m);
        }
        if (!ot.isStruct()) {
            error(m, "request for member '" + m.name + "' in '" + describeExpr(obj) + "', which is of non-class type '" + ot + "'");
            return new IntLit(0, CType.INT).at(m);
        }
        if (!ot.struct.complete) {
            error(m, "invalid use of incomplete type '" + ot + "'");
            return new IntLit(0, CType.INT).at(m);
        }
        CType.Field f = ot.struct.byName.get(m.name);
        if (f == null) {
            error(m, "'" + ot + "' has no member named '" + m.name + "'");
            return new IntLit(0, CType.INT).at(m);
        }
        m.obj = obj;
        m.field = f;
        m.type = f.type.withCv(f.type.isConst || ot.isConst, f.type.isVolatile || ot.isVolatile);
        m.lvalue = m.arrow || obj.lvalue || isTempClass(obj);
        return m;
    }

    private Expr index(Index ix) {
        Expr a = check(ix.a);
        if (a.type.isString()) {
            Expr i = value(ix.i);
            markAddressTaken(a);
            Intrinsic in = new Intrinsic().at(ix);
            in.op = Intrinsics.Op.STR_INDEX;
            in.args.add(new AddrOf(a, CType.pointer(CType.STRING)).at(a));
            in.args.add(convert(i, CType.UINT, ix, ""));
            in.type = CType.pointer(CType.CHAR);
            return new Deref(in, a.type.isConst ? CType.CHAR.withConst(true) : CType.CHAR).at(ix);
        }
        a = decay(a);
        Expr i = value(ix.i);
        if (i.type.isPointer() && a.type.isInteger()) {
            Expr t = a;
            a = i;
            i = t;
        }
        if (!a.type.isPointer() || !i.type.isInteger()) {
            error(ix, "invalid types '" + a.type + "[" + i.type + "]' for array subscript");
            return new IntLit(0, CType.INT).at(ix);
        }
        if (a.type.base.isVoid()) {
            error(ix, "'void*' is not a pointer-to-object type");
            return new IntLit(0, CType.INT).at(ix);
        }
        Binary add = new Binary().at(ix);
        add.op = "+";
        add.l = a;
        add.r = i.type.isUnsigned() && !i.type.is64() ? arith(i, CType.UINT) : arith(i, CType.INT);
        add.opType = a.type;
        add.type = a.type;
        add.stride = a.type.base.size();
        return new Deref(add, a.type.base).at(ix);
    }

    // ================= casts and construction =================

    private Expr cast(Cast c) {
        CType t = resolve(c.typeNode);
        if (t.isString()) {
            Construct k = new Construct().at(c);
            k.typeNode = c.typeNode;
            k.args.add(c.e);
            return construct(k, t);
        }
        Expr e = check(c.e);
        return explicitCast(e, t, c, c.style);
    }

    Expr explicitCast(Expr e, CType t, Node at, int style) {
        if (t.isVoid())
            return new Conv(Conv.K.TO_VOID, e, CType.VOID).at(at);
        if (t.isRef()) {
            if (!e.lvalue && !isTempClass(e)) {
                if (t.base.isConst)
                    return new Deref(bindRef(e, t, at), t.base).at(at);
                error(at, "invalid cast of an rvalue expression of type '" + e.type + "' to type '" + t + "'");
                return e;
            }
            markAddressTaken(e);
            Expr addr = new AddrOf(e, CType.pointer(e.type)).at(e);
            return new Deref(retype(addr, CType.pointer(t.base)), t.base).at(at);
        }
        if (t.isStruct()) {
            if (e.type.isStruct() && e.type.struct == t.struct)
                return e;
            error(at, "no matching function for call to '" + t + "::" + t + "(" + e.type + ")'");
            return e;
        }
        if (t.isArray()) {
            error(at, "ISO C++ forbids casting to an array type '" + t + "'");
            return e;
        }
        e = decay(e);
        CType f = e.type;
        CType tu = t.unqualified();
        if (f.isString()) {
            if (t.kind == CType.Kind.BOOL)
                return new Conv(Conv.K.STRING_TO_BOOL, e, CType.BOOL).at(at);
            error(at, "invalid cast from type 'String' to type '" + t + "'");
            return new IntLit(0, tu).at(at);
        }
        if (f.isStruct()) {
            error(at, "invalid cast from type '" + f + "' to type '" + t + "'");
            return new IntLit(0, tu).at(at);
        }
        if ((tu.isArithmetic() || tu.isEnum()) && (f.isArithmetic() || f.isEnum()))
            return arith(e, tu);
        if (tu.isPointer()) {
            if (f.isPointer() || f.kind == CType.Kind.NULLPTR)
                return retype(e, tu);
            if (f.isIntegral()) {
                if (style == Cast.STATIC && !isNullConstant(e))
                    error(at, "invalid 'static_cast' from type '" + f + "' to type '" + t + "'");
                return fold(new Conv(Conv.K.INT, e, tu).at(at));
            }
        }
        if (tu.isIntegral() && (f.isPointer() || f.kind == CType.Kind.NULLPTR)) {
            if (tu.kind == CType.Kind.BOOL)
                return new Conv(Conv.K.TO_BOOL, e, tu).at(at);
            if (tu.size() < 4)
                error(at, "cast from '" + f + "' to '" + t + "' loses precision [-fpermissive]");
            return new Conv(Conv.K.INT, e, tu).at(at);
        }
        if (tu.kind == CType.Kind.NULLPTR && f.kind == CType.Kind.NULLPTR)
            return e;
        error(at, "invalid cast from type '" + f + "' to type '" + t + "'");
        return new IntLit(0, tu.isScalar() ? tu : CType.INT).at(at);
    }

    private Expr construct(Construct c) {
        CType t = resolve(c.typeNode);
        return construct(c, t);
    }

    private Expr construct(Construct c, CType t) {
        if (t.isString()) {
            Temp tmp = newTemp(CType.STRING);
            TempRef tr = new TempRef().at(c);
            tr.temp = tmp;
            tr.type = CType.STRING;
            tr.lvalue = true;
            tr.plan = new InitPlan();
            List<Expr> args = new ArrayList<>();
            for (Expr a : c.args)
                args.add(check(a));
            tr.plan.leaves.add(args.isEmpty() ? emptyStringLeaf() : stringCtorLeaf(args, 0, c));
            return tr;
        }
        if (t.isStruct()) {
            InitList il = new InitList().at(c);
            il.items.addAll(c.args);
            if (!c.braces && !c.args.isEmpty() && !(c.args.size() == 1)) {
                error(c, "no matching function for call to '" + t + "::" + t + "(...)'");
            }
            if (!c.braces && c.args.size() == 1) {
                Expr e = check(c.args.get(0));
                return explicitCast(e, t, c, Cast.FUNCTIONAL);
            }
            return constructFromList(t, il);
        }
        if (t.isVoid() && c.args.isEmpty())
            return new Conv(Conv.K.TO_VOID, new IntLit(0, CType.INT).at(c), CType.VOID).at(c);
        if (c.args.isEmpty())
            return t.isFloating() ? new FloatLit(0, t.unqualified()).at(c) : new IntLit(0, t.unqualified()).at(c);
        if (c.args.size() > 1) {
            error(c, "expression list treated as compound expression in functional cast [-fpermissive]");
        }
        Expr e = check(c.args.get(c.args.size() - 1));
        if (c.braces)
            return convert(e, t, c, "");
        return explicitCast(e, t, c, Cast.FUNCTIONAL);
    }

    // ================= new / delete =================

    private Expr newExpr(New n) {
        CType t = resolve(n.typeNode);
        if (t.isRef() || t.isVoid() || t.isFunction()) {
            error(n, "invalid type '" + t + "' for new");
            return new IntLit(0, CType.VOID_PTR).at(n);
        }
        if (!t.isComplete() && !t.isString()) {
            error(n, "invalid use of incomplete type '" + t + "'");
            return new IntLit(0, CType.VOID_PTR).at(n);
        }
        n.allocType = t.unqualified();
        if (n.count != null) {
            n.count = convert(value(n.count), CType.UINT, n, "");
            if (n.args != null && n.args.isEmpty() && !n.braces) {
                // new T[n](): value-initialised, all zero.
                n.init = new InitPlan();
                n.init.zeroFill = true;
            } else if (n.args != null && !n.args.isEmpty()) {
                if (!n.braces) {
                    error(n, "parenthesized initializer in array new");
                } else {
                    InitList il = new InitList().at(n);
                    il.items.addAll(n.args);
                    Long cnt = intConst(n.count);
                    if (cnt == null)
                        error(n, "a brace initializer for new[] needs a constant element count here");
                    CType arr = CType.array(t, cnt == null ? n.args.size() : cnt.intValue());
                    n.init = planInit(arr, il, null, n, false);
                }
            } else if (t.isNonTrivial()) {
                // Default construction of every element: a String is all zero bytes.
                n.init = new InitPlan();
                n.init.zeroFill = true;
            }
        } else if (n.args != null) {
            if (n.braces) {
                InitList il = new InitList().at(n);
                il.items.addAll(n.args);
                n.init = planInit(t, il, null, n, true);
            } else if (n.args.isEmpty()) {
                // new T(): value-initialised, zeroed.
                n.init = planInit(t, new InitList().at(n), null, n, true);
            } else {
                n.init = planInit(t, null, n.args, n, true);
            }
        } else {
            n.init = planInit(t, null, null, n, false);
        }
        n.type = CType.pointer(t);
        if (unevaluated == 0)
            markHeapUsed();
        return n;
    }

    private Expr deleteExpr(Delete dl) {
        Expr e = value(dl.e);
        if (!e.type.isPointer()) {
            error(dl, "type '" + e.type + "' argument given to 'delete', expected pointer");
            return new Conv(Conv.K.TO_VOID, new IntLit(0, CType.INT).at(dl), CType.VOID).at(dl);
        }
        if (e.type.base.isVoid())
            warning(dl, "deleting 'void*' is undefined [-Wdelete-incomplete]");
        dl.e = e;
        dl.type = CType.VOID;
        if (unevaluated == 0)
            markHeapUsed();
        return dl;
    }

    abstract void markHeapUsed();
}
