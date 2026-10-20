package com.nectariich.create_flippies.content.computer.asm;

import java.nio.ByteOrder;
import java.util.OptionalLong;

/**
 * What one CPU's assembler adds to the shared framework: its syntax, byte order, instructions,
 * relocation operators and any directives of its own. Public so backends can live in sibling
 * packages. Implementations hold no mutable state shared between runs.
 */
public interface IsaBackend {

    LexerSyntax syntax();

    ByteOrder byteOrder();

    /** Bytes per instruction slot (RV32I: 4); used for NOP padding. */
    int instructionAlignment();

    /** One NOP in byte order, repeated to pad text. */
    byte[] nopPadding();

    /** Whether {@code .align n} means 2^n (true) or n bytes. */
    boolean alignIsPowerOfTwo();

    /**
     * Whether instruction sizes depend on symbol values that are only known after layout. If so,
     * the first pass repeats with the previous layout's addresses until no size changes. Sizes
     * may only grow, except that an {@link #autoAlignment} pad follows the statement's current
     * offset (it may disappear when the statement moves to an aligned offset) and a
     * {@link #isDataStatement data statement} such as a classic {@code org} or {@code align} may
     * also shrink, since its size follows the code before it. The number of iterations is capped
     * at 16 plus the number of instruction statements. RV32I fixes every size in the first pass.
     */
    default boolean relaxes() {
        return false;
    }

    /**
     * Bytes in a {@code .word}: 4 for RV32I and the GNU-style backends, 2 for the 68k backend
     * (the classic 68k meaning; spec 10.7.3). {@code .short}, {@code .long} and the others keep
     * their fixed widths.
     */
    default int wordBytes() {
        return 4;
    }

    /**
     * Whether an instruction statement only reserves or emits data, so it is allowed in
     * {@code .bss} (68k {@code ds.b}, {@code dcb.w}, {@code even}). The backend still sizes and
     * encodes it; in {@code .bss} it must emit only zeros. Default: none.
     */
    default boolean isDataStatement(Statement statement) {
        return false;
    }

    /**
     * The boundary (a power of two) an instruction statement aligns itself to by padding inside
     * its own size, as 68k instructions and word data do (spec 10.7.3); 1 for none. Labels just
     * before such a statement are moved past the pad, so they name the aligned address. Must be
     * answerable from the mnemonic alone. Default: 1.
     */
    default int autoAlignment(Statement statement) {
        return 1;
    }

    /**
     * The alignment (a power of two) the text and data base addresses must have, because
     * {@link #autoAlignment} pads are decided from section offsets: 2 for the 68k backend. A
     * misaligned base is an error. Default: 1 (any base).
     */
    default int baseAlignment() {
        return 1;
    }

    /**
     * First pass: the size in bytes of an instruction statement. May evaluate what is already
     * known through {@link AsmContext#known}. Throws {@link AsmError} for errors it can see
     * already (unknown mnemonic, operand count).
     */
    int size(Statement statement, AsmContext ctx);

    /** Second pass: emits exactly {@link Statement#size()} bytes, or throws {@link AsmError}. */
    void encode(Statement statement, AsmContext ctx, Emitter out);

    /**
     * A directive the framework does not know; returns true if the backend handled it. Called in
     * both passes; backend directives emit nothing.
     */
    default boolean directive(Statement statement, AsmContext ctx) {
        return false;
    }

    /**
     * The value of relocation operator {@code %name(arg)}, or empty if the backend has no such
     * operator. Evaluates {@code arg} through {@code ctx}.
     */
    OptionalLong relocationOperator(String name, Expr arg, AsmContext ctx);
}
