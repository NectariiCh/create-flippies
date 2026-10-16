package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Operations the compiler implements itself instead of compiling C++ for them:
 * Arduino's {@code String} (whose members are written in Java against the
 * simulated heap, following WString.cpp line by line, so a sketch sees the same
 * allocations, failures and edge cases), and the heap (newlib-nano's malloc family,
 * operator new/delete).
 *
 * Each op carries the C++ signature it has in WString.h or stdlib.h; overload
 * resolution treats them like any other function. For a {@link Kind#METHOD} or
 * {@link Kind#CTOR} the object's address is an implicit first argument.
 */
final class Intrinsics {

    enum Kind { CTOR, METHOD, FUNC, INTERNAL }

    private static final CType S = CType.STRING;
    private static final CType SREF = CType.reference(CType.STRING.withConst(true));
    private static final CType CSTR = CType.CONST_CHAR_PTR;
    private static final CType U8 = CType.UCHAR;
    private static final CType U32 = CType.UINT;
    private static final CType SIZE = CType.UINT;
    private static final CType VOIDP = CType.VOID_PTR;
    private static final CType V = CType.VOID;

    enum Op {
        STR_CTOR_EMPTY(Kind.CTOR, "String", V, null),
        STR_CTOR_CSTR(Kind.CTOR, "String", V, null, CSTR),
        STR_CTOR_COPY(Kind.CTOR, "String", V, null, SREF),
        STR_CTOR_CHAR(Kind.CTOR, "String", V, null, CType.CHAR),
        STR_CTOR_UCHAR(Kind.CTOR, "String", V, new long[] { 10 }, U8, U8),
        STR_CTOR_INT(Kind.CTOR, "String", V, new long[] { 10 }, CType.INT, U8),
        STR_CTOR_UINT(Kind.CTOR, "String", V, new long[] { 10 }, CType.UINT, U8),
        STR_CTOR_LONG(Kind.CTOR, "String", V, new long[] { 10 }, CType.LONG, U8),
        STR_CTOR_ULONG(Kind.CTOR, "String", V, new long[] { 10 }, CType.ULONG, U8),
        STR_CTOR_FLOAT(Kind.CTOR, "String", V, new long[] { 2 }, CType.FLOAT, U8),
        STR_CTOR_DOUBLE(Kind.CTOR, "String", V, new long[] { 2 }, CType.DOUBLE, U8),
        /** ArduinoCore-API: String(const char *cstr, unsigned int length), the first {@code length} bytes. */
        STR_CTOR_CSTR_LEN(Kind.CTOR, "String", V, null, CSTR, U32),
        /** ArduinoCore-API: String(const uint8_t *cstr, unsigned int length). */
        STR_CTOR_BYTES_LEN(Kind.CTOR, "String", V, null, CType.pointer(U8.withConst(true)), U32),
        /** String(String &&): steals the buffer of a temporary. */
        STR_CTOR_MOVE(Kind.INTERNAL, "String", V, null, SREF),
        STR_DTOR(Kind.INTERNAL, "~String", V, null),
        STR_ASSIGN_COPY(Kind.METHOD, "operator=", SREF, null, SREF),
        STR_ASSIGN_CSTR(Kind.METHOD, "operator=", SREF, null, CSTR),
        STR_ASSIGN_MOVE(Kind.INTERNAL, "operator=", SREF, null, SREF),
        STR_RESERVE(Kind.METHOD, "reserve", U8, null, U32),
        STR_LENGTH(Kind.METHOD, "length", U32, null),
        STR_CONCAT_STR(Kind.METHOD, "concat", U8, null, SREF),
        STR_CONCAT_CSTR(Kind.METHOD, "concat", U8, null, CSTR),
        STR_CONCAT_CHAR(Kind.METHOD, "concat", U8, null, CType.CHAR),
        STR_CONCAT_UCHAR(Kind.METHOD, "concat", U8, null, U8),
        STR_CONCAT_INT(Kind.METHOD, "concat", U8, null, CType.INT),
        STR_CONCAT_UINT(Kind.METHOD, "concat", U8, null, CType.UINT),
        STR_CONCAT_LONG(Kind.METHOD, "concat", U8, null, CType.LONG),
        STR_CONCAT_ULONG(Kind.METHOD, "concat", U8, null, CType.ULONG),
        STR_CONCAT_FLOAT(Kind.METHOD, "concat", U8, null, CType.FLOAT),
        STR_CONCAT_DOUBLE(Kind.METHOD, "concat", U8, null, CType.DOUBLE),
        /** ArduinoCore-API: concat(const char *cstr, unsigned int length). */
        STR_CONCAT_CSTR_LEN(Kind.METHOD, "concat", U8, null, CSTR, U32),
        /** ArduinoCore-API: concat(const uint8_t *cstr, unsigned int length). */
        STR_CONCAT_BYTES_LEN(Kind.METHOD, "concat", U8, null, CType.pointer(U8.withConst(true)), U32),
        /** ArduinoCore-API: bool isEmpty() const. */
        STR_IS_EMPTY(Kind.METHOD, "isEmpty", CType.BOOL, null),
        /** ArduinoCore-API: char *begin(), the buffer; with end(), what a range-for walks. */
        STR_BEGIN(Kind.METHOD, "begin", CType.pointer(CType.CHAR), null),
        /** ArduinoCore-API: char *end(), the buffer plus the length. */
        STR_END(Kind.METHOD, "end", CType.pointer(CType.CHAR), null),
        STR_EQUALS_STR(Kind.METHOD, "equals", U8, null, SREF),
        STR_EQUALS_CSTR(Kind.METHOD, "equals", U8, null, CSTR),
        STR_EQUALS_IC(Kind.METHOD, "equalsIgnoreCase", U8, null, SREF),
        STR_COMPARE(Kind.METHOD, "compareTo", CType.INT, null, SREF),
        STR_STARTS(Kind.METHOD, "startsWith", U8, null, SREF),
        STR_STARTS_AT(Kind.METHOD, "startsWith", U8, null, SREF, U32),
        STR_ENDS(Kind.METHOD, "endsWith", U8, null, SREF),
        STR_CHAR_AT(Kind.METHOD, "charAt", CType.CHAR, null, U32),
        STR_SET_CHAR_AT(Kind.METHOD, "setCharAt", V, null, U32, CType.CHAR),
        /** operator[]: a char& into the buffer, or into a dummy when out of range. */
        STR_INDEX(Kind.INTERNAL, "operator[]", CType.CHAR, null, U32),
        STR_GET_BYTES(Kind.METHOD, "getBytes", V, new long[] { 0 }, CType.pointer(U8), U32, U32),
        STR_TO_CHAR_ARRAY(Kind.METHOD, "toCharArray", V, new long[] { 0 }, CType.pointer(CType.CHAR), U32, U32),
        STR_C_STR(Kind.METHOD, "c_str", CSTR, null),
        STR_INDEX_OF_CHAR(Kind.METHOD, "indexOf", CType.INT, null, CType.CHAR),
        STR_INDEX_OF_CHAR_FROM(Kind.METHOD, "indexOf", CType.INT, null, CType.CHAR, U32),
        STR_INDEX_OF_STR(Kind.METHOD, "indexOf", CType.INT, null, SREF),
        STR_INDEX_OF_STR_FROM(Kind.METHOD, "indexOf", CType.INT, null, SREF, U32),
        STR_LAST_INDEX_OF_CHAR(Kind.METHOD, "lastIndexOf", CType.INT, null, CType.CHAR),
        STR_LAST_INDEX_OF_CHAR_FROM(Kind.METHOD, "lastIndexOf", CType.INT, null, CType.CHAR, U32),
        STR_LAST_INDEX_OF_STR(Kind.METHOD, "lastIndexOf", CType.INT, null, SREF),
        STR_LAST_INDEX_OF_STR_FROM(Kind.METHOD, "lastIndexOf", CType.INT, null, SREF, U32),
        STR_SUBSTRING(Kind.METHOD, "substring", S, null, U32),
        STR_SUBSTRING2(Kind.METHOD, "substring", S, null, U32, U32),
        STR_REPLACE_CHAR(Kind.METHOD, "replace", V, null, CType.CHAR, CType.CHAR),
        STR_REPLACE_STR(Kind.METHOD, "replace", V, null, SREF, SREF),
        STR_REMOVE(Kind.METHOD, "remove", V, null, U32),
        STR_REMOVE2(Kind.METHOD, "remove", V, null, U32, U32),
        STR_TO_LOWER(Kind.METHOD, "toLowerCase", V, null),
        STR_TO_UPPER(Kind.METHOD, "toUpperCase", V, null),
        STR_TRIM(Kind.METHOD, "trim", V, null),
        STR_TO_INT(Kind.METHOD, "toInt", CType.LONG, null),
        STR_TO_FLOAT(Kind.METHOD, "toFloat", CType.FLOAT, null),
        STR_TO_DOUBLE(Kind.METHOD, "toDouble", CType.DOUBLE, null),
        /** The StringIfHelper conversion: a String is "true" when it holds a buffer. */
        STR_VALID(Kind.INTERNAL, "operator bool", CType.BOOL, null),
        /** StringSumHelper's concatenation: concat, and on failure invalidate. */
        STR_SUM_STR(Kind.INTERNAL, "operator+", V, null, SREF),
        STR_SUM_CSTR(Kind.INTERNAL, "operator+", V, null, CSTR),
        STR_SUM_CHAR(Kind.INTERNAL, "operator+", V, null, CType.CHAR),
        STR_SUM_UCHAR(Kind.INTERNAL, "operator+", V, null, U8),
        STR_SUM_INT(Kind.INTERNAL, "operator+", V, null, CType.INT),
        STR_SUM_UINT(Kind.INTERNAL, "operator+", V, null, CType.UINT),
        STR_SUM_LONG(Kind.INTERNAL, "operator+", V, null, CType.LONG),
        STR_SUM_ULONG(Kind.INTERNAL, "operator+", V, null, CType.ULONG),
        STR_SUM_FLOAT(Kind.INTERNAL, "operator+", V, null, CType.FLOAT),
        STR_SUM_DOUBLE(Kind.INTERNAL, "operator+", V, null, CType.DOUBLE),

        MALLOC(Kind.FUNC, "malloc", VOIDP, null, SIZE),
        CALLOC(Kind.FUNC, "calloc", VOIDP, null, SIZE, SIZE),
        REALLOC(Kind.FUNC, "realloc", VOIDP, null, VOIDP, SIZE),
        FREE(Kind.FUNC, "free", V, null, VOIDP),
        /** operator new / new[]: Arduino's new.cpp is malloc() with no failure handling. */
        NEW(Kind.INTERNAL, "operator new", VOIDP, null, SIZE),
        /** operator delete / delete[]: free(). */
        DELETE(Kind.INTERNAL, "operator delete", V, null, VOIDP),
        /** Default-constructs {@code count} Strings laid out {@code stride} bytes apart: String arrays, new String[n]. */
        STR_CTOR_ARRAY(Kind.INTERNAL, "String[]", V, null, VOIDP, U32, U32),
        /** Zeroes a run of bytes whose length is only known at run time (new T[n] of zero-initialised elements). */
        MEMSET0(Kind.INTERNAL, "memset", V, null, VOIDP, SIZE),
        /** Destroys {@code count} Strings laid out {@code stride} bytes apart: array and member destruction. */
        STR_DTOR_ARRAY(Kind.INTERNAL, "~String[]", V, null, VOIDP, U32, U32),

        /*
         * GCC's bit builtins, which bit-twiddling sketches call directly. They compile to
         * VM instructions with Cortex-M3 costs (CodeGen#bitBuiltin), not to calls: CLZ is
         * one instruction, ctz is RBIT + CLZ, popcount is libgcc's __popcountsi2. An
         * argument of zero is undefined behaviour in GCC; here it gives the width (32 or
         * 64), which is what CLZ itself produces.
         */
        BUILTIN_CLZ(Kind.FUNC, "__builtin_clz", CType.INT, null, CType.UINT),
        BUILTIN_CLZL(Kind.FUNC, "__builtin_clzl", CType.INT, null, CType.ULONG),
        BUILTIN_CLZLL(Kind.FUNC, "__builtin_clzll", CType.INT, null, CType.ULLONG),
        BUILTIN_CTZ(Kind.FUNC, "__builtin_ctz", CType.INT, null, CType.UINT),
        BUILTIN_CTZL(Kind.FUNC, "__builtin_ctzl", CType.INT, null, CType.ULONG),
        BUILTIN_CTZLL(Kind.FUNC, "__builtin_ctzll", CType.INT, null, CType.ULLONG),
        BUILTIN_POPCOUNT(Kind.FUNC, "__builtin_popcount", CType.INT, null, CType.UINT),
        BUILTIN_POPCOUNTL(Kind.FUNC, "__builtin_popcountl", CType.INT, null, CType.ULONG),
        BUILTIN_POPCOUNTLL(Kind.FUNC, "__builtin_popcountll", CType.INT, null, CType.ULLONG);

        final Kind kind;
        final String name;
        final CType ret;
        final List<CType> params;
        /** Trailing default argument values, applied right-aligned to {@link #params}. */
        final long[] defaults;

        Op(Kind kind, String name, CType ret, long[] defaults, CType... params) {
            this.kind = kind;
            this.name = name;
            this.ret = ret;
            this.params = List.of(params);
            this.defaults = defaults == null ? new long[0] : defaults;
        }

        CType fnType() {
            return CType.function(ret, params, false);
        }

        /** Whether it takes the object's address as a hidden first argument. */
        boolean hasThis() {
            return kind == Kind.CTOR || kind == Kind.METHOD || this == STR_CTOR_MOVE || this == STR_DTOR || this == STR_ASSIGN_MOVE
                    || this == STR_INDEX || this == STR_VALID || name().startsWith("STR_SUM_");
        }

        /** Whether the op's first argument is the address of a String it constructs. */
        boolean constructs() {
            return kind == Kind.CTOR || this == STR_CTOR_MOVE;
        }

        /** Whether the result is a String built into a temporary the caller provides. */
        boolean returnsString() {
            return ret.isString();
        }
    }

    static List<Op> methods(String name) {
        List<Op> r = new ArrayList<>();
        for (Op o : Op.values())
            if (o.kind == Kind.METHOD && o.name.equals(name))
                r.add(o);
        return r;
    }

    static List<Op> constructors() {
        List<Op> r = new ArrayList<>();
        for (Op o : Op.values())
            if (o.kind == Kind.CTOR)
                r.add(o);
        return r;
    }

    private Intrinsics() {}
}
