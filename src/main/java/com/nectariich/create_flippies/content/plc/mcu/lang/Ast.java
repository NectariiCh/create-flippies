package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * The syntax tree. The parser builds it untyped; {@link Sema} then annotates each
 * expression with its type and value category and rewrites it into a canonical form
 * (names resolved to {@link Sym}s, implicit conversions made explicit as {@link Conv},
 * subscripts turned into pointer arithmetic, calls bound to their callee), which is
 * the only form {@link CodeGen} accepts.
 */
final class Ast {

    abstract static class Node {
        int file;
        int line;
        int col;

        <T extends Node> T at(Token t) {
            file = t.file;
            line = t.line;
            col = t.col;
            @SuppressWarnings("unchecked") T self = (T) this;
            return self;
        }

        <T extends Node> T at(Node n) {
            file = n.file;
            line = n.line;
            col = n.col;
            @SuppressWarnings("unchecked") T self = (T) this;
            return self;
        }
    }

    // ================= types as written =================

    static final class TypeNode extends Node {
        enum K { PRIM, NAMED, STRUCT, ENUM, AUTO, PTR, REF, ARRAY, FUNC, PLACEHOLDER, DECLTYPE }

        K k;
        CType prim;
        String name;
        StructDecl structDecl;
        EnumDecl enumDecl;
        boolean isConst;
        boolean isVolatile;
        TypeNode inner;
        Expr arraySize;
        List<Param> params;
        boolean variadic;
        Expr declExpr;
        /** For an rvalue reference ({@code T&&}), which is accepted where a sketch might write it. */
        boolean rvalueRef;

        TypeNode(K k) {
            this.k = k;
        }

        static TypeNode prim(CType t) {
            TypeNode n = new TypeNode(K.PRIM);
            n.prim = t;
            return n;
        }

        static TypeNode derived(K k, TypeNode inner) {
            TypeNode n = new TypeNode(k);
            n.inner = inner;
            return n;
        }

        void become(TypeNode o) {
            k = o.k;
            prim = o.prim;
            name = o.name;
            structDecl = o.structDecl;
            enumDecl = o.enumDecl;
            isConst = o.isConst;
            isVolatile = o.isVolatile;
            inner = o.inner;
            arraySize = o.arraySize;
            params = o.params;
            variadic = o.variadic;
            declExpr = o.declExpr;
            rvalueRef = o.rvalueRef;
        }
    }

    static final class Param extends Node {
        TypeNode type;
        String name;
        Expr defaultValue;
    }

    // ================= declarations =================

    static final class VarDecl extends Node {
        String name;
        TypeNode type;
        /** {@code = expr}, {@code = {...}} or {@code {...}} (an {@link InitList}); null if none. */
        Expr init;
        /** {@code T x(a, b)}: direct-initialisation arguments; null if not written that way. */
        List<Expr> ctorArgs;
        boolean isStatic;
        boolean isExtern;
        boolean isConstexpr;
        boolean isTypedef;
        /** {@code __attribute__((aligned(N)))} or {@code alignas(N)}: the N, unchecked; null if none. */
        Expr alignAttr;
        /** {@code __attribute__((section(".noinit")))} or GCC 12's {@code noinit}: kept out of .bss. */
        boolean noinit;
        /** Filled by {@link Sema}. */
        Sym.Var var;
    }

    static final class FuncDecl extends Node {
        String name;
        /** A {@link TypeNode.K#FUNC} node: the declared type with parameter names. */
        TypeNode type;
        Block body;
        boolean isStatic;
        boolean isInline;
        boolean isLambda;
        boolean isConstexpr;
        /**
         * {@code __attribute__((weak))}: a definition the sketch may replace with its own,
         * as STM32duino declares {@code yield()} so cooperative schedulers can hook it.
         */
        boolean isWeak;
        Sym.Func sym;
    }

    static final class StructDecl extends Node {
        String tag;
        boolean isUnion;
        boolean isClass;
        /** Null for a forward declaration or an elaborated reference. */
        List<MemberDecl> members;
        /** {@code __attribute__((packed))} before the tag or after the closing brace. */
        boolean packed;
        /** {@code __attribute__((aligned(N)))} or {@code alignas(N)} on the type, unchecked; null if none. */
        Expr alignAttr;
        /** {@code aligned} without an argument: the target's largest alignment, 8. */
        boolean alignDefault;
        /** The {@code #pragma pack} value in effect at the closing brace, or 0. */
        int pack;
        CType.StructInfo info;
        CType resolved;
    }

    static final class MemberDecl extends Node {
        String name;
        TypeNode type;
        Expr bitWidth;
        Expr defaultInit;
        boolean packed;
        Expr alignAttr;
        boolean alignDefault;
    }

    static final class EnumDecl extends Node {
        String tag;
        boolean scoped;
        TypeNode underlying;
        /** Null for an elaborated reference. */
        List<Enumerator> items;
        CType resolved;
    }

    static final class Enumerator extends Node {
        String name;
        Expr value;
    }

    static final class StaticAssert extends Node {
        Expr cond;
        String message;
    }

    // ================= statements =================

    abstract static class Stmt extends Node {}

    static final class Block extends Stmt {
        final List<Stmt> items = new ArrayList<>();
        /** Where its closing brace is, for diagnostics GCC places there. */
        int endLine;
        int endCol;
    }

    static final class ExprStmt extends Stmt {
        Expr expr;
    }

    static final class DeclStmt extends Stmt {
        final List<Node> decls = new ArrayList<>();
    }

    static final class If extends Stmt {
        Stmt init;
        Expr cond;
        Stmt then;
        Stmt otherwise;
    }

    static final class While extends Stmt {
        Expr cond;
        Stmt body;
    }

    static final class DoWhile extends Stmt {
        Stmt body;
        Expr cond;
    }

    static final class For extends Stmt {
        Stmt init;
        Expr cond;
        Expr step;
        Stmt body;
        /** Range-for: {@code for (decl : range)}. */
        VarDecl rangeVar;
        Expr range;
    }

    static final class Switch extends Stmt {
        Expr cond;
        Stmt body;
        /** Filled by {@link Sema}: every case label inside, in source order. */
        final List<Case> cases = new ArrayList<>();
        Case dflt;
    }

    static final class Case extends Stmt {
        /** Null for {@code default}. */
        Expr value;
        long constant;
        Stmt body;
    }

    static final class Labeled extends Stmt {
        String label;
        Stmt body;
    }

    static final class Goto extends Stmt {
        String label;
    }

    static final class Break extends Stmt {}

    static final class Continue extends Stmt {}

    static final class Return extends Stmt {
        Expr value;
    }

    static final class Empty extends Stmt {}

    // ================= expressions =================

    abstract static class Expr extends Node {
        CType type;
        boolean lvalue;
        /**
         * Set by {@link SemaBase#eval} once it has found this expression not to be a
         * constant in a way that cannot change (outside any constexpr call, with no
         * call beneath it), so a long chain of operators is not walked again for every
         * operator stacked on top of it.
         */
        boolean notConst;
    }

    static final class IntLit extends Expr {
        long value;

        IntLit(long value, CType type) {
            this.value = value;
            this.type = type;
        }
    }

    static final class FloatLit extends Expr {
        double value;

        FloatLit(double value, CType type) {
            this.value = value;
            this.type = type;
        }
    }

    static final class StrLit extends Expr {
        byte[] bytes;
        /**
         * {@code __func__} and friends: the same kind of object (a {@code const char[]} in
         * .rodata), but not a string literal, so it does not get a literal's permission
         * to convert to {@code char*}.
         */
        boolean predefined;
        /** Filled by {@link Sema}: the .rodata object holding it. */
        Sym.Global global;
    }

    static final class NullPtr extends Expr {}

    static final class Ident extends Expr {
        String name;
        /** {@code X::name}, or "" for {@code ::name}; null if unqualified. */
        String scope;
    }

    static final class Unary extends Expr {
        /** "-", "+", "!", "~", "*", "&", "++x", "--x", "x++", "x--". */
        String op;
        Expr e;
        /** Pointer increments: the pointee size. */
        int stride;
    }

    static final class Binary extends Expr {
        String op;
        Expr l;
        Expr r;
        /** The type the operation is performed in, after the usual arithmetic conversions. */
        CType opType;
        /** Pointer arithmetic: element size; for pointer difference, the divisor. */
        int stride;
    }

    static final class Assign extends Expr {
        /** "=" or a compound "+=" etc. */
        String op;
        Expr l;
        Expr r;
        /** For compound assignment: the type the arithmetic is done in. */
        CType opType;
        int stride;
    }

    static final class Cond extends Expr {
        Expr c;
        Expr a;
        Expr b;
    }

    static final class Call extends Expr {
        Expr fn;
        final List<Expr> args = new ArrayList<>();
        /** Filled by Sema: the bound callee. */
        Sym.Func target;
        Sym.Builtin builtin;
        /** Call through a pointer: the function type. */
        CType indirectType;
        /** Struct/String return: the temp slot the callee writes into. */
        Temp sret;
    }

    static final class Member extends Expr {
        Expr obj;
        String name;
        boolean arrow;
        CType.Field field;
    }

    static final class Index extends Expr {
        Expr a;
        Expr i;
    }

    static final class Cast extends Expr {
        static final int C = 0, FUNCTIONAL = 1, STATIC = 2, REINTERPRET = 3, CONST = 4;
        TypeNode typeNode;
        Expr e;
        int style;
    }

    /** {@code T(a, b)} or {@code T{...}} with a class type: a temporary built in place. */
    static final class Construct extends Expr {
        TypeNode typeNode;
        final List<Expr> args = new ArrayList<>();
        boolean braces;
    }

    static final class SizeofType extends Expr {
        TypeNode typeNode;
        boolean alignof;
    }

    static final class SizeofExpr extends Expr {
        Expr e;
    }

    static final class InitList extends Expr {
        final List<Expr> items = new ArrayList<>();
    }

    /** {@code .name = value} inside a brace list: a GNU designated initialiser, as g++ accepts in gnu++17. */
    static final class Designated extends Expr {
        String field;
        /** An expression or a nested {@link InitList}. */
        Expr value;
    }

    static final class New extends Expr {
        TypeNode typeNode;
        Expr count;
        List<Expr> args;
        boolean braces;
        CType allocType;
        InitPlan init;
    }

    static final class Delete extends Expr {
        Expr e;
        boolean array;
    }

    static final class Lambda extends Expr {
        FuncDecl fn;
    }

    // ---- forms only Sema produces ----

    /** A use of a variable: an lvalue of its type (references already followed). */
    static final class VarRef extends Expr {
        Sym.Var var;

        VarRef(Sym.Var v) {
            this.var = v;
        }
    }

    /** A function designator; decays to a function pointer. */
    static final class FuncRef extends Expr {
        Sym.Func func;
        Sym.Builtin builtin;
        /** Before overload resolution: every candidate. */
        List<Object> overloads;
    }

    /** An address of a global object (string literal, rodata), as an rvalue pointer. */
    static final class GlobalAddr extends Expr {
        Sym.Global global;
        long offset;
    }

    /** A value conversion made explicit. */
    static final class Conv extends Expr {
        enum K {
            /** Integer to integer, or pointer/int reinterpretation: re-normalise to the target width. */
            INT,
            INT_TO_FLOAT, FLOAT_TO_INT, FLOAT_TO_FLOAT,
            /** Scalar to bool: compare with zero. */
            TO_BOOL,
            /** Array or function to pointer: the object's address. */
            DECAY,
            /** Discard (cast to void). */
            TO_VOID,
            /** Value unchanged; only the static type differs (pointer to pointer, enum to its type, qualification). */
            NOP,
            /** String as a condition: buffer != NULL. */
            STRING_TO_BOOL
        }

        K k;
        Expr e;

        Conv(K k, Expr e, CType to) {
            this.k = k;
            this.e = e;
            this.type = to;
        }
    }

    /** A temporary object living until the end of the full-expression (or longer, if bound). */
    static final class Temp {
        CType type;
        /** Frame offset, assigned by CodeGen. */
        int offset = -1;
        boolean needsDtor;

        Temp(CType type) {
            this.type = type;
            this.needsDtor = type.isNonTrivial();
        }
    }

    /**
     * An lvalue designating a temporary, after its initialiser runs: either a
     * {@link #plan} applied to the temporary, or an {@link #init} expression evaluated
     * for its effect (one that constructs into the temporary itself).
     */
    static final class TempRef extends Expr {
        Temp temp;
        InitPlan plan;
        Expr init;
    }

    /** An intrinsic operation the compiler implements itself: String members, heap, new/delete. */
    static final class Intrinsic extends Expr {
        Intrinsics.Op op;
        /** Arguments as values; the object's address first when the op has one. */
        final List<Expr> args = new ArrayList<>();
        /** Where a String result is built (substring), or the object a constructor builds. */
        Temp result;
        /** The expression's value is the object's address ({@code s += x} yields {@code s}). */
        boolean returnsThis;
        /**
         * An assignment operator ({@code s = x}, {@code s += x}): C++17 sequences the
         * right operand (the second argument) before the object, as for the built-in
         * assignment.
         */
        boolean rhsFirst;
    }

    /**
     * {@code a + b + c} where an operand is a String: Arduino's StringSumHelper. The
     * first operand is copied into one temporary and every later operand appended to
     * it in place.
     */
    static final class StringSum extends Expr {
        /** How the temporary is built from the first operand (a StringSumHelper constructor). */
        Leaf first;
        final List<Expr> rest = new ArrayList<>();
        Temp temp;
    }

    /** Reference binding: the address of an lvalue, carried as the reference's value. */
    static final class AddrOf extends Expr {
        Expr e;

        AddrOf(Expr e, CType ptrType) {
            this.e = e;
            this.type = ptrType;
        }
    }

    /** An lvalue read through a pointer: {@code *p}, also how references are followed. */
    static final class Deref extends Expr {
        Expr p;

        Deref(Expr p, CType t) {
            this.p = p;
            this.type = t;
            this.lvalue = true;
        }
    }

    /** How to initialise one object: what to store where, flattened from its initialiser. */
    static final class InitPlan {
        /** Leaves in address order: each stores one scalar, bitfield, String or chars at an offset. */
        final List<Leaf> leaves = new ArrayList<>();
        /** Whether bytes not covered by any leaf must be zeroed first. */
        boolean zeroFill;
    }

    static final class Leaf {
        enum K { SCALAR, BITFIELD, STRING_CTOR, CHARS, BLOCK_COPY }

        K k;
        int offset;
        CType type;
        Expr value;
        CType.Field field;
        byte[] chars;
        /** STRING_CTOR: the overload chosen. */
        Intrinsics.Op ctor;
        List<Expr> ctorArgs;
    }

    private Ast() {}
}
