package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * What names resolve to. A {@link Var} is an object with storage, a {@link Func} is
 * a sketch function, a {@link Builtin} is something the core or the compiler itself
 * provides, and a {@link Global} is a linker object: a run of bytes in .rodata,
 * .data or .bss that gets an address when the image is laid out.
 */
final class Sym {

    /** A linker section object. Its {@link #address} is assigned by {@link Linker}. */
    static final class Global {
        /**
         * NOINIT is arm-gcc's {@code .noinit}: laid out after .bss and left alone by the
         * startup code, so it keeps its bytes across a warm reset (NVIC_SystemReset, NRST)
         * while SRAM stays powered.
         */
        enum Section { RODATA, DATA, BSS, NOINIT }

        final String name;
        int size;
        int align;
        Section section;
        /** Initial bytes for RODATA/DATA (length {@link #size}); null for BSS. */
        byte[] data;
        final List<Reloc> relocs = new ArrayList<>();
        long address;
        /** The variable it stores, if any: the linker drops unused and constant-folded ones. */
        Var owner;
        /** Whether something must run to initialise it (a static constructor). */
        boolean dynamic;

        Global(String name, int size, int align, Section section) {
            this.name = name;
            this.size = size;
            this.align = Math.max(1, align);
            this.section = section;
        }
    }

    /** "Store the address of {@code target} plus {@code addend} at {@code offset}" (R_ARM_ABS32). */
    record Reloc(int offset, Object target, long addend) {}

    static final class Var {
        enum Storage { GLOBAL, STATIC_LOCAL, LOCAL, PARAM }

        final String name;
        final CType type;
        final Storage storage;
        final int file;
        final int line;
        final int col;
        Global global;
        /** Frame offset for LOCAL/PARAM, assigned by {@link CodeGen}. */
        int offset = -1;
        /** Its address escapes (&x, a reference bound to it, an array or struct): it cannot live in a register. */
        boolean addressTaken;
        /** Declared as a reference: its slot holds a pointer that every use follows. */
        boolean isRef;
        /**
         * A by-value parameter of class type (String, or a struct): passed, as the
         * Itanium/ARM C++ ABI does for non-trivial types, as the address of a copy the
         * caller made. Every use follows that address.
         */
        boolean byAddress;
        /** For a const integer or floating variable with a constant initialiser: its value (Long or Double). */
        Object constValue;
        /** For a STATIC_LOCAL with a dynamic initialiser: the guard byte. */
        Global guard;
        Ast.InitPlan staticInit;
        boolean isExtern;
        boolean defined;
        boolean used;

        Var(String name, CType type, Storage storage, int file, int line, int col) {
            this.name = name;
            this.type = type;
            this.storage = storage;
            this.file = file;
            this.line = line;
            this.col = col;
        }

        /**
         * A variable-length array (GCC's extension, {@code byte buf[len];}): its bound,
         * evaluated at the declaration, and the hidden local holding its size in bytes
         * for {@code sizeof}. The array itself is carved off the stack at run time, so
         * the variable's slot holds its address ({@link #byAddress}).
         */
        Ast.Expr vlaBound;
        Var vlaSize;

        /** Bytes it occupies in its frame or section. */
        int slotSize() {
            return isRef || byAddress ? 4 : type.size();
        }

        int slotAlign() {
            return Math.max(alignAttr, isRef || byAddress ? 4 : type.align());
        }

        /**
         * {@code alignas(N)} / {@code aligned(N)} on the variable itself, or 0. A local's
         * frame slot honours up to 8 (the stack's own alignment); GCC would realign the
         * frame for more, which no Cortex-M3 sketch has a use for.
         */
        int alignAttr;

        /** The type of the object a use of the name designates (references followed). */
        CType objectType() {
            return isRef ? type.base : type;
        }
    }

    static final class Func {
        final String name;
        CType type;
        final List<Var> params = new ArrayList<>();
        /** Default arguments, aligned with the declared parameters; null entries have none. */
        List<Ast.Expr> defaults = new ArrayList<>();
        Ast.FuncDecl def;
        Ast.Block body;
        boolean isStatic;
        boolean used;
        boolean takesAddress;
        int file;
        int line;
        int col;
        /** Struct or String return: the hidden first parameter, a pointer to the caller's slot. */
        Var sret;
        /** Locals in declaration order, for frame layout. */
        final List<Var> locals = new ArrayList<>();
        /** A {@code constexpr} function whose body has been checked: calls to it can be evaluated at compile time. */
        boolean constexprBody;
        /** Filled by CodeGen. */
        int index = -1;

        Func(String name) {
            this.name = name;
        }

        CType ret() {
            return type.base;
        }

        /** As GCC prints it in "undefined reference to `f(int)'". */
        String signature() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < type.params.size(); i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(type.params.get(i));
            }
            return sb.append(')').toString();
        }
    }

    /**
     * A function the program does not define itself: a core builtin with an
     * implementation, or one of the compiler's own intrinsics (malloc and friends).
     */
    static final class Builtin {
        final String name;
        final CType type;
        final List<Ast.Expr> defaults;
        final com.nectariich.create_flippies.content.plc.mcu.Builtin impl;
        final Intrinsics.Op op;
        int index = -1;
        boolean used;
        /** Declared weak by the core: a sketch definition with the same signature replaces it. */
        boolean weak;

        Builtin(String name, CType type, List<Ast.Expr> defaults,
                com.nectariich.create_flippies.content.plc.mcu.Builtin impl, Intrinsics.Op op) {
            this.name = name;
            this.type = type;
            this.defaults = defaults;
            this.impl = impl;
            this.op = op;
        }
    }

    static final class TypeName {
        final CType type;

        TypeName(CType type) {
            this.type = type;
        }
    }

    static final class EnumConst {
        final long value;
        final CType type;

        EnumConst(long value, CType type) {
            this.value = value;
            this.type = type;
        }
    }

    /** Every function sharing a name: sketch functions and builtins alike. */
    static final class Overloads {
        final List<Object> items = new ArrayList<>();
    }

    private Sym() {}
}
