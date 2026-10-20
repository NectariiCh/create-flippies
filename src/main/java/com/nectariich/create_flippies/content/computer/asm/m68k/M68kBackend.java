package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmContext;
import com.nectariich.create_flippies.content.computer.asm.AsmError;
import com.nectariich.create_flippies.content.computer.asm.AsmToken;
import com.nectariich.create_flippies.content.computer.asm.Emitter;
import com.nectariich.create_flippies.content.computer.asm.Expr;
import com.nectariich.create_flippies.content.computer.asm.IsaBackend;
import com.nectariich.create_flippies.content.computer.asm.LexerSyntax;
import com.nectariich.create_flippies.content.computer.asm.Operand;
import com.nectariich.create_flippies.content.computer.asm.Statement;
import com.nectariich.create_flippies.content.computer.asm.Val;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Ea;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Enc;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Slots;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The 68000 and 68040 backend (spec 10.7): classic 68k syntax, big-endian, 2-byte {@code .word},
 * relaxation for branch, absolute-address and displacement sizes, and the classic data
 * statements without a dot ({@code dc.w}, {@code ds.l}, {@code dcb.b}, {@code even},
 * {@code align}, {@code org}, {@code end}).
 *
 * One instance serves one assembly run: the {@code .cpu} directive switches the target for the
 * statements after it, which is state that lives as long as the run (the passes revisit the
 * statements in order, and the target is reset to the default at the start of every pass).
 * {@link M68kAssembler} makes a fresh instance per call.
 *
 * Word-sized data and every instruction are aligned to an even address with one zero pad byte
 * when needed; the pad is part of the statement's size, and an instruction that needs it gets a
 * warning. Labels just before a padded statement name the address after the pad
 * ({@link #autoAlignment}).
 */
public final class M68kBackend implements IsaBackend {

    private static final LexerSyntax SYNTAX = new LexerSyntax(List.of(";"), false, (char) 0, (char) 0, true,
            true, true, true, true, true, true);
    private static final byte[] NOP = {0x4E, 0x71};
    private static final Set<String> DATA = Set.of("dc", "ds", "dcb", "even", "align", "org", "end");
    private static final String SIZE_LETTERS = "bwlsdxp";

    private final M68kTarget defaultTarget;
    private M68kTarget target;
    private final Map<Statement, Integer> order = new IdentityHashMap<>();
    private int lastIndex = -1;

    public M68kBackend(M68kTarget defaultTarget) {
        this.defaultTarget = defaultTarget;
        this.target = defaultTarget;
    }

    @Override
    public LexerSyntax syntax() {
        return SYNTAX;
    }

    @Override
    public ByteOrder byteOrder() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public int instructionAlignment() {
        return 2;
    }

    @Override
    public byte[] nopPadding() {
        return NOP.clone();
    }

    @Override
    public boolean alignIsPowerOfTwo() {
        return false;
    }

    @Override
    public boolean relaxes() {
        return true;
    }

    @Override
    public int wordBytes() {
        return 2;
    }

    @Override
    public boolean isDataStatement(Statement st) {
        String base = splitBase(st.mnemonic());
        return base.equals("ds") || base.equals("dcb") || base.equals("even") || base.equals("align")
                || base.equals("org");
    }

    /** Sections must start at even addresses, so the pads decided from section offsets give even addresses (spec 10.7.3). */
    @Override
    public int baseAlignment() {
        return 2;
    }

    /** Instructions and word-sized data pad themselves to an even address; byte data and the directives do not. */
    @Override
    public int autoAlignment(Statement st) {
        String name = st.mnemonic();
        String base = splitBase(name);
        if (!DATA.contains(base))
            return 2;
        boolean sized = base.equals("dc") || base.equals("ds") || base.equals("dcb");
        return sized && !name.endsWith(".b") ? 2 : 1;
    }

    @Override
    public OptionalLong relocationOperator(String name, Expr arg, AsmContext ctx) {
        return OptionalLong.empty();
    }

    /** The target in effect for the statement being processed. */
    M68kTarget target() {
        return target;
    }

    /**
     * Tracks the walk through the statements: a statement at or before the last one seen means a
     * new pass has started, so the target goes back to the default.
     */
    private void observe(Statement st) {
        Integer idx = order.get(st);
        if (idx == null) {
            idx = order.size();
            order.put(st, idx);
        }
        if (idx <= lastIndex)
            target = defaultTarget;
        lastIndex = idx;
    }

    @Override
    public boolean directive(Statement st, AsmContext ctx) {
        if (!st.mnemonic().equals(".cpu"))
            return false;
        observe(st);
        st.expectOperands(1);
        Operand op = st.operand(0);
        String text = op.tokens().size() == 1 ? op.tokens().get(0).text().toLowerCase(Locale.ROOT) : "";
        target = switch (text) {
            case "68000", "m68000", "mc68000" -> M68kTarget.M68000;
            case "68040", "m68040", "mc68040" -> M68kTarget.M68040;
            default -> throw new AsmError(op.column(), "'.cpu' expects 68000 or 68040, got '" + op.text() + "'");
        };
        return true;
    }

    // ---- parsed statements ----

    /** What the first pass parsed and decided for one statement. */
    private static final class Insn {
        String base;
        char size;
        M68kInstructions.Def def;
        M68kFpInstructions.Def fdef;
        List<Ea> ops;
        final Slots slots = new Slots();
    }

    private static String splitBase(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot == name.length() - 2 && SIZE_LETTERS.indexOf(name.charAt(dot + 1)) >= 0)
            return name.substring(0, dot);
        return name;
    }

    private Insn insn(Statement st) {
        if (st.backendData instanceof Insn in)
            return in;
        String name = st.mnemonic();
        Insn in = new Insn();
        in.base = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String suffix = name.substring(dot + 1);
            String head = name.substring(0, dot);
            if (suffix.length() == 1 && SIZE_LETTERS.indexOf(suffix.charAt(0)) >= 0) {
                in.base = head;
                in.size = suffix.charAt(0);
            } else if (known(head)) {
                throw new AsmError(st.column(), "unknown size suffix '." + suffix + "' in '" + name + "'");
            }
        }
        if (!DATA.contains(in.base)) {
            in.def = M68kInstructions.lookup(in.base);
            if (in.def == null) {
                in.fdef = M68kFpInstructions.lookup(in.base);
                if (in.fdef == null)
                    throw new AsmError(st.column(), "unknown instruction '" + name + "'");
            }
            List<Ea> ops = new ArrayList<>();
            for (Operand op : st.operands())
                ops.add(M68kOperands.parse(op));
            in.ops = ops;
        }
        st.backendData = in;
        return in;
    }

    private static boolean known(String base) {
        return DATA.contains(base) || M68kInstructions.lookup(base) != null || M68kFpInstructions.lookup(base) != null;
    }

    // ---- sizes and encoding ----

    /** Whether the context is in the first layout pass (labels known only as section offsets). */
    private static boolean relative(AsmContext ctx) {
        return ctx.firstPass() && ctx.known(new Expr.Dot(1)).isEmpty();
    }

    /** The current statement's offset in its section (parity and alignment are section-relative). */
    private static long sectionOffset(AsmContext ctx) {
        if (relative(ctx))
            return ctx.dot();
        String start = switch (ctx.currentSection()) {
            case TEXT -> "__text_start";
            case RODATA -> "__rodata_start";
            case INIT_ARRAY -> "__init_array_start";
            case FINI_ARRAY -> "__fini_array_start";
            case DATA -> "__data_start";
            case BSS -> "__bss_start";
        };
        return ctx.dot() - ctx.evalAbs(new Expr.Sym(start, 1));
    }

    @Override
    public int size(Statement st, AsmContext ctx) {
        observe(st);
        Insn in = insn(st);
        if (in.def == null && in.fdef == null)
            return (int) dataSize(st, in, ctx);
        int pad = (int) (sectionOffset(ctx) & 1);
        Enc e = new Enc(ctx, true, target, in.slots, ctx.dot() + pad, relative(ctx));
        run(e, in, st);
        return pad + 2 * e.n;
    }

    @Override
    public void encode(Statement st, AsmContext ctx, Emitter out) {
        observe(st);
        Insn in = insn(st);
        if (in.def == null && in.fdef == null) {
            emitData(st, in, ctx, out);
            return;
        }
        int pad = (int) (sectionOffset(ctx) & 1);
        Enc e = new Enc(ctx, false, target, in.slots, ctx.dot() + pad, false);
        run(e, in, st);
        if (pad + 2 * e.n != st.size())
            throw new AsmError(st.column(), "internal assembler error: '" + st.mnemonic() + "' is "
                    + (pad + 2 * e.n) + " bytes now but was sized " + st.size());
        if (pad != 0) {
            ctx.warning(st.column(), "instruction at odd address, padded with one zero byte");
            out.u8(0);
        }
        for (int i = 0; i < e.n; i++)
            out.u16(e.words[i]);
    }

    private void run(Enc e, Insn in, Statement st) {
        if (in.def != null)
            M68kInstructions.encode(e, in.def, in.base, in.size, in.ops, st.column());
        else
            M68kFpInstructions.encode(e, in.fdef, in.base, in.size, in.ops, st.column());
    }

    // ---- data statements ----

    private static int width(char size) {
        return switch (size) {
            case 'b' -> 1;
            case 'w' -> 2;
            case 'l', 's' -> 4;
            case 'd' -> 8;
            default -> 12;
        };
    }

    private static char sizeLetter(Statement st, Insn in, String allowed) {
        return M68kInstructions.size(in.size, allowed, 'w', in.base, st.column());
    }

    /** Bytes of a data statement, including the pad byte that aligns word-sized data. */
    private long dataSize(Statement st, Insn in, AsmContext ctx) {
        long off = sectionOffset(ctx);
        switch (in.base) {
            case "dc" -> {
                char s = sizeLetter(st, in, "bwlsdxp");
                int w = width(s);
                long n = 0;
                for (Operand op : st.operands()) {
                    String str = op.string();
                    if (str != null) {
                        if (s != 'b')
                            throw new AsmError(op.column(), "strings need dc.b");
                        n += str.length();
                    } else {
                        if (op.isEmpty())
                            throw new AsmError(op.column(), "missing value");
                        n += w;
                    }
                }
                if (st.operands().isEmpty())
                    throw new AsmError(st.column(), "'" + st.mnemonic() + "' expects at least one value");
                return n + (s != 'b' ? (off & 1) : 0);
            }
            case "ds" -> {
                char s = sizeLetter(st, in, "bwlsdxp");
                st.expectOperands(1);
                long count = ctx.evalNow(st.operand(0).expr(), "'" + st.mnemonic() + "' count");
                if (count < 0)
                    throw new AsmError(st.operand(0).column(), "negative count " + count);
                long n = count * width(s);
                if (count > Integer.MAX_VALUE / 16)
                    throw new AsmError(st.operand(0).column(), "reservation of " + count + " items is too large");
                return n + (s != 'b' ? (off & 1) : 0);
            }
            case "dcb" -> {
                char s = sizeLetter(st, in, "bwl");
                st.expectOperands(2);
                long count = ctx.evalNow(st.operand(0).expr(), "'" + st.mnemonic() + "' count");
                if (count < 0)
                    throw new AsmError(st.operand(0).column(), "negative count " + count);
                if (count > Integer.MAX_VALUE / 16)
                    throw new AsmError(st.operand(0).column(), "block of " + count + " items is too large");
                return count * width(s) + (s != 'b' ? (off & 1) : 0);
            }
            case "even" -> {
                noSize(st, in);
                st.expectOperands(0);
                return off & 1;
            }
            case "align" -> {
                noSize(st, in);
                st.expectOperands(1);
                long a = ctx.evalNow(st.operand(0).expr(), "alignment");
                if (a < 1 || a > 65536 || Long.bitCount(a) != 1)
                    throw new AsmError(st.operand(0).column(), "alignment " + a + " is not a power of two up to 65536");
                return (a - (off & (a - 1))) & (a - 1);
            }
            case "org" -> {
                noSize(st, in);
                st.expectOperands(1);
                Val v;
                try {
                    v = ctx.eval(st.operand(0).expr());
                } catch (AsmContext.Unknown u) {
                    throw new AsmError(st.operand(0).column(), "'org' target must be a constant known at this point");
                }
                long target;
                if (v.isAbs())
                    target = v.value();
                else if (v.section() == ctx.currentSection().ordinal())
                    target = v.value() - (ctx.dot() - off);
                else
                    throw new AsmError(st.operand(0).column(), "'org' target is not in the current section");
                if (target < off)
                    throw new AsmError(st.operand(0).column(), String.format(
                            "'org' moves the location counter backwards (from 0x%x to 0x%x)", off, target));
                return target - off;
            }
            default -> {
                noSize(st, in);
                return 0;   // end
            }
        }
    }

    private static void noSize(Statement st, Insn in) {
        if (in.size != 0)
            throw new AsmError(st.column(), "'" + st.mnemonic() + "': " + in.base + " takes no size");
    }

    private void emitData(Statement st, Insn in, AsmContext ctx, Emitter out) {
        long off = sectionOffset(ctx);
        long size = dataSize(st, in, ctx);
        if (size != st.size())
            throw new AsmError(st.column(), "internal assembler error: '" + st.mnemonic() + "' needs " + size
                    + " bytes here but was laid out with " + st.size());
        boolean wordAligned = (in.base.equals("dc") || in.base.equals("ds") || in.base.equals("dcb")) && in.size != 'b';
        if (wordAligned && (off & 1) != 0)
            out.u8(0);
        switch (in.base) {
            case "dc" -> {
                char s = in.size == 0 ? 'w' : in.size;
                for (Operand op : st.operands())
                    dcValue(op, s, ctx, out);
            }
            case "ds" -> out.fill(size - (wordAligned ? off & 1 : 0), 0);
            case "dcb" -> {
                char s = in.size == 0 ? 'w' : in.size;
                long count = ctx.evalAbs(st.operand(0).expr());
                long v = ctx.eval(st.operand(1).expr()).value();
                checkInt(v, width(s), st.operand(1).column());
                for (long i = 0; i < count; i++)
                    out.value(v, width(s));
            }
            case "align" -> {
                if (st.section() == com.nectariich.create_flippies.content.computer.asm.Section.TEXT && size % 2 == 0) {
                    for (long i = 0; i < size; i += 2)
                        out.bytes(NOP);
                } else {
                    out.fill(size, 0);
                }
            }
            default -> out.fill(size, 0);   // even, org, end
        }
    }

    private static void checkInt(long v, int width, int column) {
        long lo = -(1L << (8 * width - 1)), hi = (1L << (8 * width)) - 1;
        if (v < lo || v > hi)
            throw new AsmError(column, "value " + v + " does not fit in " + width + (width == 1 ? " byte" : " bytes"));
    }

    private void dcValue(Operand op, char s, AsmContext ctx, Emitter out) {
        String str = op.string();
        if (str != null) {
            out.bytes(str);
            return;
        }
        if ("bwl".indexOf(s) >= 0) {
            long v = ctx.eval(op.expr()).value();
            checkInt(v, width(s), op.column());
            out.value(v, width(s));
            return;
        }
        boolean neg = false;
        BigDecimal mag;
        List<AsmToken> t = op.tokens();
        int i = 0;
        if (t.size() == 2 && (t.get(0).isPunct("-") || t.get(0).isPunct("+"))) {
            neg = t.get(0).isPunct("-");
            i = 1;
        }
        try {
            if (t.size() == i + 1 && t.get(i).kind() == AsmToken.Kind.FLOAT) {
                mag = M68kFloat.parse(t.get(i).text());
            } else {
                long v = ctx.eval(op.expr()).value();
                neg = v < 0;
                mag = new BigDecimal(BigInteger.valueOf(v).abs());
            }
            switch (s) {
                case 's' -> out.u32(M68kFloat.single(neg, mag));
                case 'd' -> out.u64(M68kFloat.dbl(neg, mag));
                case 'x' -> {
                    for (int x : M68kFloat.extended(neg, mag))
                        out.u32(x);
                }
                default -> {
                    for (int x : M68kFloat.packed(neg, mag))
                        out.u32(x);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new AsmError(op.column(), e.getMessage());
        }
    }
}
