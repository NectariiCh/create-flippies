package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A C++ type as arm-none-eabi-g++ lays it out for a Cortex-M3: the AAPCS data model.
 *
 * The numbers here are the ones a sketch can observe, through {@code sizeof}, through
 * overflow, through a pointer walked over a struct, so they are the ARM numbers and
 * not the ones a desktop would give: {@code int} and {@code long} are both 32 bits,
 * pointers are 4 bytes, {@code double} and {@code long double} are both IEEE double
 * and 8-byte aligned, as is {@code long long}. Plain {@code char} is
 * <em>unsigned</em> on ARM GCC, which is the classic surprise when a sketch ported
 * from an AVR compares a char against a negative number.
 *
 * An unscoped enumeration without a fixed underlying type is as small as its values
 * allow: arm-none-eabi targets the bare-metal AAPCS, for which GCC's
 * {@code ARM_DEFAULT_SHORT_ENUMS} is on, and STM32duino's platform does not pass
 * {@code -fno-short-enums}. So {@code enum Color {RED, GREEN}} is one byte on the
 * board, {@code enum {A = -1, B = 300}} two, and a struct holding one packs
 * accordingly (see {@code Sema.enumType}). {@code enum class} and an enum with
 * {@code : type} keep the type they were given.
 *
 * Types are immutable values. Qualifiers ride on the type object itself
 * ({@link #isConst}, {@link #isVolatile}); {@link #unqualified()} strips them. Struct
 * and enum types are compared by identity of their {@link StructInfo} or
 * {@link EnumInfo}, everything else structurally.
 */
final class CType {

    enum Kind {
        VOID, BOOL, CHAR, SCHAR, UCHAR, SHORT, USHORT, INT, UINT, LONG, ULONG, LLONG, ULLONG,
        FLOAT, DOUBLE, LDOUBLE, ENUM, NULLPTR, POINTER, REFERENCE, ARRAY, FUNCTION, STRUCT,
        /** Arduino's {@code String}: a 12-byte {char *buffer; unsigned capacity; unsigned len}. */
        STRING
    }

    final Kind kind;
    final boolean isConst;
    final boolean isVolatile;
    /** Pointee, referee, element, return type, or an enum's underlying type. */
    final CType base;
    /** Element count of an array, or -1 while it is still incomplete ({@code int a[];}). */
    final int length;
    final List<CType> params;
    final boolean variadic;
    final StructInfo struct;
    final EnumInfo enumInfo;

    private CType(Kind kind, boolean c, boolean v, CType base, int length, List<CType> params,
            boolean variadic, StructInfo struct, EnumInfo enumInfo) {
        this.kind = kind;
        this.isConst = c;
        this.isVolatile = v;
        this.base = base;
        this.length = length;
        this.params = params;
        this.variadic = variadic;
        this.struct = struct;
        this.enumInfo = enumInfo;
    }

    private static CType prim(Kind k) {
        return new CType(k, false, false, null, 0, null, false, null, null);
    }

    static final CType VOID = prim(Kind.VOID);
    static final CType BOOL = prim(Kind.BOOL);
    static final CType CHAR = prim(Kind.CHAR);
    static final CType SCHAR = prim(Kind.SCHAR);
    static final CType UCHAR = prim(Kind.UCHAR);
    static final CType SHORT = prim(Kind.SHORT);
    static final CType USHORT = prim(Kind.USHORT);
    static final CType INT = prim(Kind.INT);
    static final CType UINT = prim(Kind.UINT);
    static final CType LONG = prim(Kind.LONG);
    static final CType ULONG = prim(Kind.ULONG);
    static final CType LLONG = prim(Kind.LLONG);
    static final CType ULLONG = prim(Kind.ULLONG);
    static final CType FLOAT = prim(Kind.FLOAT);
    static final CType DOUBLE = prim(Kind.DOUBLE);
    static final CType LDOUBLE = prim(Kind.LDOUBLE);
    static final CType NULLPTR = prim(Kind.NULLPTR);
    static final CType STRING = prim(Kind.STRING);
    static final CType CONST_CHAR_PTR = pointer(CHAR.withConst(true));
    static final CType VOID_PTR = pointer(VOID);

    static CType pointer(CType to) {
        return new CType(Kind.POINTER, false, false, to, 0, null, false, null, null);
    }

    static CType reference(CType to) {
        return new CType(Kind.REFERENCE, false, false, to, 0, null, false, null, null);
    }

    static CType array(CType of, int length) {
        return new CType(Kind.ARRAY, false, false, of, length, null, false, null, null);
    }

    static CType function(CType ret, List<CType> params, boolean variadic) {
        return new CType(Kind.FUNCTION, false, false, ret, 0, List.copyOf(params), variadic, null, null);
    }

    static CType struct(StructInfo info) {
        return new CType(Kind.STRUCT, false, false, null, 0, null, false, info, null);
    }

    static CType enumType(EnumInfo info) {
        return new CType(Kind.ENUM, false, false, info.underlying, 0, null, false, null, info);
    }

    CType withConst(boolean c) {
        if (c == isConst)
            return this;
        return new CType(kind, c, isVolatile, base, length, params, variadic, struct, enumInfo);
    }

    CType withCv(boolean c, boolean v) {
        if (c == isConst && v == isVolatile)
            return this;
        return new CType(kind, c, v, base, length, params, variadic, struct, enumInfo);
    }

    CType unqualified() {
        return withCv(false, false);
    }

    // ---- classification ----

    boolean isVoid() { return kind == Kind.VOID; }
    boolean isPointer() { return kind == Kind.POINTER; }
    boolean isRef() { return kind == Kind.REFERENCE; }
    boolean isArray() { return kind == Kind.ARRAY; }
    boolean isFunction() { return kind == Kind.FUNCTION; }
    boolean isStruct() { return kind == Kind.STRUCT; }
    boolean isString() { return kind == Kind.STRING; }
    boolean isEnum() { return kind == Kind.ENUM; }
    boolean isScopedEnum() { return kind == Kind.ENUM && enumInfo.scoped; }
    boolean isFloating() { return kind == Kind.FLOAT || kind == Kind.DOUBLE || kind == Kind.LDOUBLE; }
    boolean isDoubleLike() { return kind == Kind.DOUBLE || kind == Kind.LDOUBLE; }

    /** Integer types proper, bool and unscoped enums included (they convert implicitly). */
    boolean isInteger() {
        return switch (kind) {
            case BOOL, CHAR, SCHAR, UCHAR, SHORT, USHORT, INT, UINT, LONG, ULONG, LLONG, ULLONG -> true;
            case ENUM -> !enumInfo.scoped;
            default -> false;
        };
    }

    /** Integral including scoped enums, for switch and explicit casts. */
    boolean isIntegral() {
        return isInteger() || kind == Kind.ENUM;
    }

    boolean isArithmetic() {
        return isInteger() || isFloating();
    }

    boolean isScalar() {
        return isArithmetic() || kind == Kind.POINTER || kind == Kind.NULLPTR || kind == Kind.ENUM;
    }

    boolean isUnsigned() {
        return switch (kind) {
            case BOOL, CHAR, UCHAR, USHORT, UINT, ULONG, ULLONG, POINTER, NULLPTR -> true;
            case ENUM -> base.isUnsigned();
            default -> false;
        };
    }

    boolean is64() {
        return switch (kind) {
            case LLONG, ULLONG -> true;
            case ENUM -> base.is64();
            default -> false;
        };
    }

    /** Needs a constructor/destructor: String, or an aggregate holding one. */
    boolean isNonTrivial() {
        return switch (kind) {
            case STRING -> true;
            case ARRAY -> base.isNonTrivial();
            case STRUCT -> struct.nonTrivial;
            default -> false;
        };
    }

    /** Memo for arrays (nested 15 000 deep they made these quadratic); see {@link #size()}. */
    private boolean completeKnown;
    private int sizeMemo = -1;

    boolean isComplete() {
        return switch (kind) {
            case VOID, FUNCTION -> false;
            case ARRAY -> {
                // Only "complete" is remembered: a struct element can still become complete.
                if (completeKnown)
                    yield true;
                boolean c = length >= 0 && base.isComplete();
                completeKnown = c;
                yield c;
            }
            case STRUCT -> struct.complete;
            default -> true;
        };
    }

    /** Integer conversion rank, C++ [conv.rank]; long outranks int even though they are the same size. */
    int rank() {
        return switch (kind) {
            case BOOL -> 0;
            case CHAR, SCHAR, UCHAR -> 1;
            case SHORT, USHORT -> 2;
            case INT, UINT -> 3;
            case LONG, ULONG -> 4;
            case LLONG, ULLONG -> 5;
            case ENUM -> base.rank();
            default -> 3;
        };
    }

    int size() {
        return switch (kind) {
            case VOID, FUNCTION -> 1;
            case BOOL, CHAR, SCHAR, UCHAR -> 1;
            case SHORT, USHORT -> 2;
            case INT, UINT, LONG, ULONG, FLOAT, POINTER, NULLPTR, REFERENCE -> 4;
            case LLONG, ULLONG, DOUBLE, LDOUBLE -> 8;
            case ENUM -> base.size();
            case ARRAY -> {
                if (sizeMemo >= 0)
                    yield sizeMemo;
                int n = Math.max(0, length) * base.size();
                // A complete element's size is final (an incomplete struct's is not yet known).
                if (isComplete())
                    sizeMemo = n;
                yield n;
            }
            case STRUCT -> struct.size;
            case STRING -> 12;
        };
    }

    int align() {
        return switch (kind) {
            case ARRAY -> base.align();
            case STRUCT -> struct.align;
            case STRING -> 4;
            case ENUM -> base.align();
            case VOID, FUNCTION -> 1;
            default -> size();
        };
    }

    /** The type an array decays to, or a function to; anything else is itself. */
    CType decay() {
        if (kind == Kind.ARRAY)
            return pointer(base);
        if (kind == Kind.FUNCTION)
            return pointer(this);
        return this;
    }

    /** The same type ignoring top-level qualifiers. */
    boolean sameUnqualified(CType o) {
        return unqualified().equals(o.unqualified());
    }

    /** A signed type's unsigned counterpart. */
    CType toUnsigned() {
        return switch (kind) {
            case CHAR, SCHAR -> UCHAR;
            case SHORT -> USHORT;
            case INT -> UINT;
            case LONG -> ULONG;
            case LLONG -> ULLONG;
            case ENUM -> base.toUnsigned();
            default -> this;
        };
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof CType o) || o.kind != kind || o.isConst != isConst || o.isVolatile != isVolatile)
            return false;
        return switch (kind) {
            case POINTER, REFERENCE -> base.equals(o.base);
            case ARRAY -> length == o.length && base.equals(o.base);
            case FUNCTION -> variadic == o.variadic && base.equals(o.base) && paramsEqual(params, o.params);
            case STRUCT -> struct == o.struct;
            case ENUM -> enumInfo == o.enumInfo;
            default -> true;
        };
    }

    private static boolean paramsEqual(List<CType> a, List<CType> b) {
        if (a.size() != b.size())
            return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).sameUnqualified(b.get(i)))
                return false;
        return true;
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + (base == null ? 0 : base.hashCode());
    }

    /** As GCC spells it in a diagnostic, e.g. {@code const char*} or {@code int [4]}. */
    @Override
    public String toString() {
        return spell("");
    }

    private String cv() {
        return (isConst ? "const " : "") + (isVolatile ? "volatile " : "");
    }

    String spell(String inner) {
        switch (kind) {
            case POINTER:
                if (base.kind == Kind.FUNCTION || base.kind == Kind.ARRAY)
                    return base.spell("(*" + (isConst ? " const" : "") + inner + ")");
                return base.spell("*" + (isConst ? " const" : "") + inner);
            case REFERENCE:
                if (base.kind == Kind.FUNCTION || base.kind == Kind.ARRAY)
                    return base.spell("(&" + inner + ")");
                return base.spell("&" + inner);
            case ARRAY:
                return base.spell(inner + "[" + (length >= 0 ? String.valueOf(length) : "") + "]");
            case FUNCTION: {
                StringBuilder sb = new StringBuilder();
                sb.append(inner).append('(');
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0)
                        sb.append(", ");
                    sb.append(params.get(i));
                }
                if (variadic)
                    sb.append(params.isEmpty() ? "..." : ", ...");
                sb.append(')');
                return base.spell(sb.toString());
            }
            default:
                String n = cv() + baseName();
                // GCC: "char*", "int(int)" for a function type, but "int (*)(int)" and "int (&)[3]".
                boolean tight = inner.startsWith("*") || inner.startsWith("&")
                        || (inner.startsWith("(") && !inner.startsWith("(*") && !inner.startsWith("(&"));
                return inner.isEmpty() ? n : n + (tight ? "" : " ") + inner;
        }
    }

    private String baseName() {
        return switch (kind) {
            case VOID -> "void";
            case BOOL -> "bool";
            case CHAR -> "char";
            case SCHAR -> "signed char";
            case UCHAR -> "unsigned char";
            case SHORT -> "short int";
            case USHORT -> "short unsigned int";
            case INT -> "int";
            case UINT -> "unsigned int";
            case LONG -> "long int";
            case ULONG -> "long unsigned int";
            case LLONG -> "long long int";
            case ULLONG -> "long long unsigned int";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case LDOUBLE -> "long double";
            case NULLPTR -> "std::nullptr_t";
            case STRING -> "String";
            case STRUCT -> struct.name == null ? "<unnamed struct>" : struct.name;
            case ENUM -> enumInfo.name == null ? "<unnamed enum>" : enumInfo.name;
            default -> kind.name();
        };
    }

    /** One data member of a struct or union. */
    static final class Field {
        final String name;
        final CType type;
        int offset;
        /** Bit position inside the container at {@link #offset}, for a bitfield. */
        int bitOffset;
        /** Bitfield width, or -1 for an ordinary member. */
        final int bitWidth;
        /** A C++11 default member initialiser, already checked, or null. */
        Object defaultInit;
        final int line;
        final int col;

        /** {@code __attribute__((packed))} on this member: byte alignment whatever its type. */
        boolean packed;
        /** {@code __attribute__((aligned(N)))} or {@code alignas(N)} on this member, or 0. */
        int alignAttr;
        /**
         * For a member of an anonymous struct or union that was lifted into the
         * enclosing type's {@link StructInfo#byName}: the unnamed member it lives in.
         * Its {@link #offset} is already relative to the enclosing type.
         */
        Field liftedFrom;

        Field(String name, CType type, int bitWidth, int line, int col) {
            this.name = name;
            this.type = type;
            this.bitWidth = bitWidth;
            this.line = line;
            this.col = col;
        }

        /** The unnamed struct or union member that holds a whole anonymous aggregate. */
        boolean isAnonymousAggregate() {
            return name == null && bitWidth < 0 && type.kind == Kind.STRUCT;
        }

        boolean isBitfield() {
            return bitWidth >= 0;
        }
    }

    /**
     * A struct or union's layout. AAPCS: each member at its natural alignment, the
     * aggregate as aligned as its strictest member and padded to a multiple of that.
     * Bitfields follow GCC's PCC_BITFIELD_TYPE_MATTERS rule as ARM uses it: a field
     * sits in a container of its declared type, starting at the next free bit unless
     * that would straddle a boundary of its declared type's alignment, and the
     * declared type counts towards the struct's alignment.
     *
     * <h2>packed, aligned and #pragma pack</h2>
     * GCC's rules, which byte-layout code (a struct laid over a received frame, a
     * union punning bytes and words) depends on: a member's alignment is its natural
     * one, lowered to 1 by {@code packed} (on the member or on the whole type) and
     * capped by a {@code #pragma pack(n)} in effect, then raised by an
     * {@code aligned(N)} on the member. The aggregate's alignment is the largest of its
     * members', raised by an {@code aligned(N)} on the type itself. Under packed or
     * any #pragma pack, GCC drops the straddling rule (its {@code place_field} applies
     * it only when {@code maximum_field_alignment} is 0 and the field is not packed),
     * so such a bit-field takes the very next free bits. The unaligned loads and stores
     * that result are fine on a Cortex-M3, which does them in hardware.
     *
     * <h2>Anonymous members</h2>
     * An unnamed struct or union member ({@code union { float f; uint32_t u; };}) is
     * laid out as one ordinary member; its own members are then entered in
     * {@link #byName} with their offsets made relative to this type (recursively, as
     * the inner type already did the same), so {@code v.f} finds them.
     */
    static final class StructInfo {
        final String name;
        final boolean isUnion;
        final List<Field> fields = new ArrayList<>();
        final Map<String, Field> byName = new LinkedHashMap<>();
        int size;
        int align = 1;
        boolean complete;
        boolean nonTrivial;
        boolean hasDefaultInits;
        /** {@code __attribute__((packed))} on the type. */
        boolean packed;
        /** The {@code #pragma pack} value in effect at its definition, or 0 for none. */
        int pack;
        /** {@code __attribute__((aligned(N)))} or {@code alignas(N)} on the type, or 0. */
        int alignAttr;
        /** Set by {@link #layout} when the type would be larger than any object can be (2 GB). */
        boolean tooLarge;
        /**
         * Set by {@link #layout} when a packed bit-field spans more bytes than its
         * declared type has: this compiler reaches a bit-field through one load of its
         * declared type, which cannot cover it.
         */
        Field unreachableBitfield;
        /** Set by {@link #layout} when two anonymous members both have a member of this name. */
        String duplicateLifted;

        StructInfo(String name, boolean isUnion) {
            this.name = name;
            this.isUnion = isUnion;
        }

        /** A member's alignment after packed, #pragma pack and aligned(N). */
        int fieldAlign(Field f) {
            int a = f.type.align();
            if (packed || f.packed)
                a = 1;
            if (pack > 0)
                a = Math.min(a, pack);
            if (f.alignAttr > 0)
                a = Math.max(a, f.alignAttr);
            return a;
        }

        /**
         * Places every field and fixes size and alignment. Positions are counted in
         * long: a member array may be close to 2 GB, and its end in bits is past what an
         * int holds (a wrapped size would let a 4 GB array of them through as empty).
         */
        void layout() {
            long bit = 0;
            long maxSize = 0;
            for (Field f : fields) {
                CType t = f.type;
                int a = fieldAlign(f);
                align = Math.max(align, a);
                if (t.isNonTrivial())
                    nonTrivial = true;
                if (f.defaultInit != null)
                    hasDefaultInits = true;
                if (isUnion) {
                    f.offset = 0;
                    f.bitOffset = 0;
                    maxSize = Math.max(maxSize, f.isBitfield() ? (f.bitWidth + 7) / 8 : (long) t.size());
                    continue;
                }
                if (f.isBitfield()) {
                    long unitBits = t.size() * 8L;
                    if (f.bitWidth == 0) {
                        // An unnamed zero-width field closes the container, packed or not.
                        bit = (bit + unitBits - 1) / unitBits * unitBits;
                        continue;
                    }
                    if (packed || f.packed || pack > 0) {
                        long containerStart = bit / 8 * 8;
                        f.offset = (int) Math.min(containerStart / 8, Integer.MAX_VALUE);
                        f.bitOffset = (int) (bit - containerStart);
                        if (f.bitOffset + f.bitWidth > unitBits && unreachableBitfield == null)
                            unreachableBitfield = f;
                    } else {
                        long alignBits = a * 8L;
                        if (bit / alignBits != (bit + f.bitWidth - 1) / alignBits)
                            bit = (bit + alignBits - 1) / alignBits * alignBits;
                        long containerStart = bit / alignBits * alignBits;
                        f.offset = (int) Math.min(containerStart / 8, Integer.MAX_VALUE);
                        f.bitOffset = (int) (bit - containerStart);
                    }
                    bit += f.bitWidth;
                } else {
                    bit = (bit + 7) / 8 * 8;
                    long off = (bit / 8 + a - 1) / a * a;
                    f.offset = (int) Math.min(off, Integer.MAX_VALUE);
                    f.bitOffset = 0;
                    bit = (off + t.size()) * 8;
                }
            }
            if (alignAttr > 0)
                align = Math.max(align, alignAttr);
            long bytes = isUnion ? maxSize : (bit + 7) / 8;
            long padded = (bytes + align - 1) / align * align;
            if (padded > Integer.MAX_VALUE) {
                tooLarge = true;
                padded = Integer.MAX_VALUE;
            }
            size = (int) padded;
            liftAnonymousMembers();
            complete = true;
        }

        /** Enters the members of each anonymous struct or union member under this type's own names. */
        private void liftAnonymousMembers() {
            for (Field f : fields) {
                if (!f.isAnonymousAggregate())
                    continue;
                for (Field inner : f.type.struct.byName.values()) {
                    Field lifted = new Field(inner.name, inner.type, inner.bitWidth, inner.line, inner.col);
                    lifted.offset = f.offset + inner.offset;
                    lifted.bitOffset = inner.bitOffset;
                    lifted.defaultInit = inner.defaultInit;
                    lifted.liftedFrom = f;
                    if (byName.putIfAbsent(inner.name, lifted) != null && duplicateLifted == null)
                        duplicateLifted = inner.name;
                }
            }
        }
    }

    /** An enumeration; C++11 {@code enum class} is {@link #scoped} and does not convert implicitly. */
    static final class EnumInfo {
        final String name;
        final boolean scoped;
        /** Declared with {@code : type} (or scoped): promotes to that type rather than to int. */
        boolean fixed;
        CType underlying = INT;
        final Map<String, Long> values = new LinkedHashMap<>();

        EnumInfo(String name, boolean scoped) {
            this.name = name;
            this.scoped = scoped;
        }
    }
}
