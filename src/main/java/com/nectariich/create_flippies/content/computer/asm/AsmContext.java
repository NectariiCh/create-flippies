package com.nectariich.create_flippies.content.computer.asm;

import com.nectariich.create_flippies.content.computer.asm.SymbolTable.Symbol;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * What a backend sees of the assembly in progress: which pass it is, the current location, and
 * expression evaluation against the symbol table.
 *
 * Values in the first pass: a symbol defined further on is not known yet, and during the first
 * layout pass a label is only known as an offset in its section (its section's base is decided
 * after the pass). Evaluating such a value throws {@link Unknown}; {@link #known} turns that into
 * an empty result for backends that pick a size from a value (RV32I {@code li}). In the second
 * pass everything is known, and an unresolved name is an error.
 */
public final class AsmContext {

    /** Thrown during the first pass for a value that is not known yet. Never escapes the assembler. */
    public static final class Unknown extends RuntimeException {
        static final Unknown INSTANCE = new Unknown();

        private Unknown() {
            super("value not known yet", null, false, false);
        }
    }

    /** For backends: the signal that a value is not known in the first pass (pc-relative operators). */
    public static Unknown notKnownYet() {
        return Unknown.INSTANCE;
    }

    final IsaBackend backend;
    final SymbolTable symbols;
    final Diagnostics diagnostics;
    /** 1 or 2. */
    int pass;
    /** First-pass iteration, from 1 (only backends that relax go past 1). */
    int iteration;
    /** Statement being processed: gives the location counter, the file scope and the order for 1b/1f. */
    Statement current;
    /** Section run addresses from the latest layout. */
    final long[] base = new long[Section.values().length];
    /** Iterations after the first repeat statements already reported: no new diagnostics. */
    boolean quiet;
    private final Map<Long, Long> pcrelHi = new HashMap<>();

    AsmContext(IsaBackend backend, SymbolTable symbols, Diagnostics diagnostics) {
        this.backend = backend;
        this.symbols = symbols;
        this.diagnostics = diagnostics;
    }

    public boolean firstPass() {
        return pass == 1;
    }

    /** True while labels are only known as section offsets (the first layout pass). */
    boolean relative() {
        return pass == 1 && iteration == 1;
    }

    public Section currentSection() {
        return current.section;
    }

    /** The current statement's address: absolute, except in the first layout pass (section offset). */
    public long dot() {
        return relative() ? current.offset : base[current.section.ordinal()] + current.offset;
    }

    /** The current statement's address as a value tagged with its section. */
    public Val dotValue() {
        return new Val(dot(), current.section.ordinal());
    }

    public void warning(int column, String message) {
        if (!quiet)
            diagnostics.warning(current.file, current.line, column, message);
    }

    /** Records that the AUIPC at {@code address} carries {@code %pcrel_hi(target)}, for {@code %pcrel_lo}. */
    public void recordPcrelHi(long address, long target) {
        pcrelHi.put(address, target);
    }

    /** The target recorded for the AUIPC at {@code address}, if any. */
    public OptionalLong pcrelHi(long address) {
        Long v = pcrelHi.get(address);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    /** Whether {@code name} resolves to a symbol from the current file. */
    public boolean isDefined(String name) {
        Symbol s = symbols.lookup(current.file, name);
        return s != null || (!firstPass() && symbols.isWeak(current.file, name));
    }

    /**
     * The value of an expression that must be a plain number or an absolute address. Throws
     * {@link Unknown} in the first pass if it is not known yet (including section-relative labels).
     */
    public long evalAbs(Expr e) {
        Val v = eval(e);
        if (relative() && v.section() >= 0)
            throw Unknown.INSTANCE;
        return v.value();
    }

    /** {@link #evalAbs} if the value is known now, else empty. Errors are still thrown. */
    public OptionalLong known(Expr e) {
        try {
            return OptionalLong.of(evalAbs(e));
        } catch (Unknown u) {
            return OptionalLong.empty();
        }
    }

    /** Like {@link #evalAbs} but turns "not known yet" into an error naming {@code what}. */
    public long evalNow(Expr e, String what) {
        try {
            return evalAbs(e);
        } catch (Unknown u) {
            throw new AsmError(e.column(), what + " must be a constant known at this point");
        }
    }

    public Val eval(Expr e) {
        if (e instanceof Expr.Num n)
            return Val.abs(n.value());
        if (e instanceof Expr.Dot)
            return dotValue();
        if (e instanceof Expr.Sym s)
            return symbol(s.name(), s.column());
        if (e instanceof Expr.LocalRef r)
            return localRef(r);
        if (e instanceof Expr.Unary u)
            return unary(u);
        if (e instanceof Expr.Binary b)
            return binary(b);
        if (e instanceof Expr.Reloc r) {
            OptionalLong v = backend.relocationOperator(r.name(), r.arg(), this);
            if (v.isEmpty())
                throw new AsmError(r.column(), "unknown relocation operator '%" + r.name() + "'");
            return Val.abs(v.getAsLong());
        }
        throw new IllegalStateException("unknown expression node " + e);
    }

    private Val symbol(String name, int column) {
        Symbol s = symbols.lookup(current.file, name);
        if (s == null) {
            if (firstPass())
                throw Unknown.INSTANCE;
            if (symbols.isWeak(current.file, name))
                return new Val(0, Val.ADDR);
            throw new AsmError(column, "undefined symbol '" + name + "'");
        }
        return value(s, column);
    }

    private Val localRef(Expr.LocalRef r) {
        Symbol s = symbols.numeric(current.file, r.number(), r.forward(), current.seq);
        if (s == null) {
            if (firstPass() && r.forward())
                throw Unknown.INSTANCE;
            throw new AsmError(r.column(), "no " + (r.forward() ? "following" : "preceding")
                    + " local label " + r.number());
        }
        return value(s, r.column());
    }

    /** A symbol's value as the current pass sees it. */
    Val value(Symbol s, int column) {
        switch (s.kind) {
            case LABEL -> {
                if (s.section == null)
                    throw new AsmError(column, "symbol '" + s.name + "' is not in a loaded section");
                long off = s.offset;
                return relative() ? new Val(off, s.section.ordinal())
                        : new Val(base[s.section.ordinal()] + off, s.section.ordinal());
            }
            case PREDEFINED -> {
                if (!s.known)
                    throw Unknown.INSTANCE;
                return s.value;
            }
            default -> {
                if (s.known) {
                    Val v = s.value;
                    if (s.relative && !relative())
                        return new Val(base[v.section()] + v.value(), v.section());
                    return v;
                }
                if (relative() || s.deferred == null)
                    throw Unknown.INSTANCE;
                return resolveDeferred(s, column);
            }
        }
    }

    /** Evaluates an equate whose value was not known when the first pass reached it. */
    private Val resolveDeferred(Symbol s, int column) {
        if (s.resolving)
            throw new AsmError(column, "circular definition of '" + s.name + "'");
        s.resolving = true;
        Statement saved = current;
        try {
            current = s.definedBy;
            Val v = eval(s.deferred);
            s.value = v;
            s.known = true;
            s.relative = false;
            return v;
        } catch (Unknown u) {
            throw new AsmError(column, "value of '" + s.name + "' is not known");
        } finally {
            current = saved;
            s.resolving = false;
        }
    }

    private Val unary(Expr.Unary u) {
        Val a = eval(u.operand());
        if (relative() && !a.isAbs())
            throw Unknown.INSTANCE;
        long x = a.value();
        return Val.abs(switch (u.op()) {
            case "-" -> -x;
            case "~" -> ~x;
            default -> x == 0 ? 1 : 0;
        });
    }

    private Val binary(Expr.Binary b) {
        Val a = eval(b.left());
        Val c = eval(b.right());
        long x = a.value(), y = c.value();
        String op = b.op();
        if (op.equals("+")) {
            if (a.isAbs())
                return new Val(x + y, c.section());
            if (c.isAbs())
                return new Val(x + y, a.section());
            if (relative())
                throw Unknown.INSTANCE;
            return Val.abs(x + y);
        }
        if (op.equals("-")) {
            if (c.isAbs())
                return new Val(x - y, a.section());
            if (a.section() == c.section())
                return Val.abs(x - y);
            if (relative())
                throw Unknown.INSTANCE;
            return Val.abs(x - y);
        }
        if (relative() && (!a.isAbs() || !c.isAbs()))
            throw Unknown.INSTANCE;
        return Val.abs(switch (op) {
            case "*" -> x * y;
            case "/" -> {
                if (y == 0)
                    throw new AsmError(b.right().column(), "division by zero");
                yield x / y;
            }
            case "%" -> {
                if (y == 0)
                    throw new AsmError(b.right().column(), "division by zero");
                yield x % y;
            }
            case "<<" -> x << shift(y, b);
            case ">>" -> x >> shift(y, b);
            case "<" -> x < y ? 1 : 0;
            case "<=" -> x <= y ? 1 : 0;
            case ">" -> x > y ? 1 : 0;
            case ">=" -> x >= y ? 1 : 0;
            case "==" -> x == y ? 1 : 0;
            case "!=" -> x != y ? 1 : 0;
            case "&" -> x & y;
            case "^" -> x ^ y;
            case "|" -> x | y;
            case "&&" -> (x != 0 && y != 0) ? 1 : 0;
            case "||" -> (x != 0 || y != 0) ? 1 : 0;
            default -> throw new IllegalStateException("unknown operator " + op);
        });
    }

    private static int shift(long y, Expr.Binary b) {
        if (y < 0 || y > 63)
            throw new AsmError(b.right().column(), "shift count " + y + " out of range (0..63)");
        return (int) y;
    }
}
