package com.nectariich.create_flippies.content.computer.asm;

/**
 * A parsed assembler expression. Evaluated by {@link AsmContext#eval}. Every node keeps the
 * column it starts at so errors point at the right place.
 */
public sealed interface Expr {

    int column();

    record Num(long value, int column) implements Expr { }

    /** A symbol reference; {@code .} alone is {@link Dot}. */
    record Sym(String name, int column) implements Expr { }

    /** {@code 1b} or {@code 1f}. */
    record LocalRef(int number, boolean forward, int column) implements Expr { }

    /** The location counter: the address of the current statement. */
    record Dot(int column) implements Expr { }

    /** {@code - ~ !}. */
    record Unary(String op, Expr operand, int column) implements Expr { }

    record Binary(String op, Expr left, Expr right, int column) implements Expr { }

    /** A backend relocation operator such as {@code %hi(x)}; {@code name} without the {@code %}. */
    record Reloc(String name, Expr arg, int column) implements Expr { }
}
