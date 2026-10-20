package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmContext;
import com.nectariich.create_flippies.content.computer.asm.AsmError;
import com.nectariich.create_flippies.content.computer.asm.AsmToken;
import com.nectariich.create_flippies.content.computer.asm.AsmToken.Kind;
import com.nectariich.create_flippies.content.computer.asm.Expr;
import com.nectariich.create_flippies.content.computer.asm.ExprParser;
import com.nectariich.create_flippies.content.computer.asm.Operand;
import com.nectariich.create_flippies.content.computer.asm.Val;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Operands of the 68k assembler: the parser from tokens to an {@link Ea} (spec 10.7.2), the
 * encoding context {@link Enc} shared by every encoder, and the effective-address encoder with
 * its size selection (abs.W or abs.L, brief or full extension words).
 *
 * Size selection works through "slots": each choice an instruction makes (an absolute address
 * size, a displacement size, a branch size, MOVEQ or MOVE.L) takes the next slot of the
 * statement. In the first pass a slot's level only grows (the smallest form that is known to fit,
 * or the starting guess while the value is unknown); the second pass reads the final levels and
 * range-checks the values against them. Every encoder visits its slots in the same order
 * whatever the levels are, so the slot numbering is stable between passes.
 */
final class M68kOperands {

    private M68kOperands() {
    }

    // ---- addressing categories (one bit per mode, PRM "effective address" classes) ----

    static final int DN = 1;
    static final int AN = 1 << 1;
    static final int IND = 1 << 2;
    static final int POST = 1 << 3;
    static final int PRE = 1 << 4;
    static final int D16 = 1 << 5;
    static final int IDX = 1 << 6;
    static final int ABS = 1 << 7;
    static final int PCD = 1 << 8;
    static final int PCX = 1 << 9;
    static final int IMM = 1 << 10;

    static final int ALL = DN | AN | IND | POST | PRE | D16 | IDX | ABS | PCD | PCX | IMM;
    static final int DATA = ALL & ~AN;
    static final int MEMORY = ALL & ~(DN | AN);
    static final int CONTROL = IND | D16 | IDX | ABS | PCD | PCX;
    static final int ALTERABLE = DN | AN | IND | POST | PRE | D16 | IDX | ABS;
    static final int DATA_ALT = ALTERABLE & ~AN;
    static final int MEM_ALT = ALTERABLE & ~(DN | AN);
    static final int CONTROL_ALT = IND | D16 | IDX | ABS;

    /** What a category bit is called in "does not take ..." messages. */
    static String describe(int cat) {
        return switch (cat) {
            case DN -> "a data register";
            case AN -> "an address register";
            case IND -> "(An)";
            case POST -> "(An)+";
            case PRE -> "-(An)";
            case D16 -> "(d16,An)";
            case IDX -> "an indexed or memory indirect address";
            case ABS -> "an absolute address";
            case PCD, PCX -> "a PC-relative address";
            case IMM -> "an immediate";
            default -> "this operand";
        };
    }

    // ---- parsed operands ----

    enum Kind2 { DREG, AREG, IND, POSTINC, PREDEC, MEM, ABS, IMM, FIMM, SPECIAL, CONTROL, FPREG, FPCTRL, REGLIST, FPLIST, PAIR }

    static final int BASE_NONE = -1;
    static final int BASE_PC = 16;

    static final int INDIRECT_NONE = 0;
    static final int INDIRECT_PRE = 1;
    static final int INDIRECT_POST = 2;

    /** One braced part of {@code {offset:width}} or {@code {#k}}: a data register or an expression. */
    record BraceItem(int dreg, Expr expr, int column) { }

    /** A parsed operand. Fields not used by its kind are null or zero. */
    static final class Ea {
        Kind2 kind;
        int column;
        String text;
        /** DREG, AREG, IND, POSTINC, PREDEC: 0..7; SPECIAL: the M68kRegisters constant; CONTROL: MOVEC number; FPREG: 0..7; FPCTRL: select bits. */
        int reg;
        /** ABS address, IMM value. */
        Expr expr;
        /** ABS: 0 automatic, 1 .w, 2 .l. */
        int absSize;
        /** FIMM: the literal's magnitude and sign. */
        BigDecimal fvalue;
        boolean fnegative;
        /** MEM: base register 0..7 (An), {@link #BASE_PC}, or {@link #BASE_NONE}. */
        int base;
        /** MEM: base written as ZAn/ZPC or omitted. */
        boolean baseSuppressed;
        Expr bd;
        int bdSize;
        M68kRegisters.Index index;
        int scale;
        Expr scaleExpr;
        int indirect;
        Expr od;
        int odSize;
        /** MEM: written so it needs the full extension format (brackets, suppressed base, index alone). */
        boolean forceFull;
        /** REGLIST: bit i = general register i; FPLIST: bit i = FPi; also a numeric #mask. */
        int mask;
        Expr maskExpr;
        /** PAIR: the two halves. */
        Ea first, second;
        /** {@code {...}} after the operand: bitfield {offset:width} or k-factor {k}. */
        List<BraceItem> braces;

        int category() {
            return switch (kind) {
                case DREG -> DN;
                case AREG -> AN;
                case IND -> M68kOperands.IND;
                case POSTINC -> POST;
                case PREDEC -> PRE;
                case ABS -> M68kOperands.ABS;
                case IMM, FIMM -> M68kOperands.IMM;
                case MEM -> {
                    boolean simple = index == null && indirect == INDIRECT_NONE && !forceFull;
                    if (base == BASE_PC)
                        yield simple ? PCD : PCX;
                    yield simple ? D16 : IDX;
                }
                default -> 0;
            };
        }
    }

    // ---- parser ----

    /** Parses one operand. Throws {@link AsmError} for malformed operands. */
    static Ea parse(Operand op) {
        List<AsmToken> t = op.tokens();
        if (t.isEmpty())
            throw new AsmError(op.column(), "missing operand");
        return parse(op, t, 0, t.size());
    }

    private static Ea parse(Operand op, List<AsmToken> t, int from, int to) {
        int column = from < t.size() ? t.get(from).column() : op.column();
        if (from >= to)
            throw new AsmError(column, "missing operand");
        // trailing {...}
        if (t.get(to - 1).isPunct("}")) {
            int open = -1;
            for (int i = to - 1; i >= from; i--) {
                if (t.get(i).isPunct("{")) {
                    open = i;
                    break;
                }
            }
            if (open < 0)
                throw new AsmError(t.get(to - 1).column(), "'}' without '{'");
            List<BraceItem> braces = braces(op, t, open + 1, to - 1, t.get(open).column());
            Ea ea = parse(op, t, from, open);
            ea.braces = braces;
            return ea;
        }
        // a pair a:b at top level
        int depth = 0;
        for (int i = from; i < to; i++) {
            AsmToken x = t.get(i);
            if (x.isPunct("(") || x.isPunct("[") || x.isPunct("{"))
                depth++;
            else if (x.isPunct(")") || x.isPunct("]") || x.isPunct("}"))
                depth--;
            else if (depth == 0 && x.isPunct(":")) {
                Ea pair = base(Kind2.PAIR, column, op);
                pair.first = parse(op, t, from, i);
                pair.second = parse(op, t, i + 1, to);
                return pair;
            }
        }
        Ea ea = parseSimple(op, t, from, to, column);
        ea.text = text(t, from, to);
        return ea;
    }

    private static Ea base(Kind2 kind, int column, Operand op) {
        Ea ea = new Ea();
        ea.kind = kind;
        ea.column = column;
        ea.text = op.text();
        return ea;
    }

    private static String text(List<AsmToken> t, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            AsmToken x = t.get(i);
            if (!sb.isEmpty() && x.kind() != Kind.PUNCT && sb.charAt(sb.length() - 1) != '('
                    && sb.charAt(sb.length() - 1) != '#' && sb.charAt(sb.length() - 1) != '[')
                sb.append(' ');
            sb.append(x.display());
        }
        return sb.toString();
    }

    private static List<BraceItem> braces(Operand op, List<AsmToken> t, int from, int to, int column) {
        List<BraceItem> out = new ArrayList<>();
        int start = from;
        for (int i = from; i <= to; i++) {
            if (i == to || t.get(i).isPunct(":")) {
                int c = start < to ? t.get(start).column() : column;
                if (start >= i)
                    throw new AsmError(c, "missing value in '{...}'");
                int s = start;
                if (t.get(s).isPunct("#"))
                    s++;
                if (s >= i)
                    throw new AsmError(c, "missing value in '{...}'");
                if (i - s == 1 && t.get(s).kind() == Kind.IDENT) {
                    int r = M68kRegisters.general(t.get(s).text());
                    if (r >= 0) {
                        if (r >= 8)
                            throw new AsmError(t.get(s).column(), "'{...}' takes a data register, not "
                                    + t.get(s).text());
                        out.add(new BraceItem(r, null, c));
                        start = i + 1;
                        continue;
                    }
                }
                out.add(new BraceItem(-1, ExprParser.parse(t, s, i, c), c));
                start = i + 1;
            }
        }
        return out;
    }

    private static boolean isIdent(List<AsmToken> t, int i, int to) {
        return i < to && t.get(i).kind() == Kind.IDENT;
    }

    private static Ea parseSimple(Operand op, List<AsmToken> t, int from, int to, int column) {
        AsmToken first = t.get(from);
        int n = to - from;
        // immediate
        if (first.isPunct("#")) {
            if (n == 1)
                throw new AsmError(first.column(), "missing value after '#'");
            int i = from + 1;
            boolean neg = false;
            if (t.get(i).isPunct("-") || t.get(i).isPunct("+")) {
                neg = t.get(i).isPunct("-");
                i++;
            }
            if (i == to - 1 && t.get(i).kind() == Kind.FLOAT) {
                Ea ea = base(Kind2.FIMM, column, op);
                try {
                    ea.fvalue = M68kFloat.parse(t.get(i).text());
                } catch (IllegalArgumentException e) {
                    throw new AsmError(t.get(i).column(), e.getMessage());
                }
                ea.fnegative = neg;
                return ea;
            }
            for (int k = from + 1; k < to; k++)
                if (t.get(k).kind() == Kind.FLOAT)
                    throw new AsmError(t.get(k).column(), "a floating-point literal cannot be part of an expression");
            Ea ea = base(Kind2.IMM, column, op);
            ea.expr = ExprParser.parse(t, from + 1, to, t.get(from + 1).column());
            return ea;
        }
        // a lone register name
        if (n == 1 && first.kind() == Kind.IDENT) {
            Ea r = register(op, first.text(), column);
            if (r != null)
                return r;
        }
        // register lists: d0-d3/a0, fp0-fp7, fpcr/fpsr
        if (isRegisterList(t, from, to))
            return registerList(op, t, from, to, column);
        // -(An)
        if (n == 4 && first.isPunct("-") && t.get(from + 1).isPunct("(") && isIdent(t, from + 2, to)
                && t.get(from + 3).isPunct(")")) {
            int r = M68kRegisters.general(t.get(from + 2).text());
            if (r >= 8) {
                Ea ea = base(Kind2.PREDEC, column, op);
                ea.reg = r - 8;
                return ea;
            }
            if (r >= 0)
                throw new AsmError(t.get(from + 2).column(), "-(...) takes an address register, not "
                        + t.get(from + 2).text());
        }
        // (An)+
        if (n == 4 && first.isPunct("(") && isIdent(t, from + 1, to) && t.get(from + 2).isPunct(")")
                && t.get(from + 3).isPunct("+")) {
            int r = M68kRegisters.general(t.get(from + 1).text());
            if (r >= 8) {
                Ea ea = base(Kind2.POSTINC, column, op);
                ea.reg = r - 8;
                return ea;
            }
            if (r >= 0)
                throw new AsmError(t.get(from + 1).column(), "(...)+ takes an address register, not "
                        + t.get(from + 1).text());
        }
        // trailing size on an absolute address: expr.w, (expr).l, and label.w (one identifier)
        List<AsmToken> split = splitSizeSuffix(t, to - 1);
        if (split != t) {
            t = split;
            to++;
            n++;
        }
        int absSize = 0;
        int end = to;
        if (n >= 2 && t.get(to - 1).kind() == Kind.IDENT) {
            String s = t.get(to - 1).text().toLowerCase(Locale.ROOT);
            if (s.equals(".w") || s.equals(".l")) {
                absSize = s.equals(".w") ? 1 : 2;
                end = to - 1;
            }
        }
        // a memory form ending in a parenthesised register group
        if (absSize == 0 && t.get(to - 1).isPunct(")")) {
            int open = matchOpen(t, from, to - 1);
            if (open < 0)
                throw new AsmError(t.get(to - 1).column(), "')' without '('");
            List<int[]> items = split(t, open + 1, to - 1);
            boolean regGroup = false;
            for (int[] item : items)
                regGroup |= startsWithRegisterOrBracket(t, item);
            if (open > from && !items.isEmpty() && !startsWithRegisterOrBracket(t, items.get(0)))
                regGroup = false;
            if (regGroup || containsBracket(t, open + 1, to - 1)) {
                Ea ea = base(Kind2.MEM, column, op);
                Expr outer = open > from ? ExprParser.parse(t, from, open, column) : null;
                memory(op, t, items, outer, ea);
                return finishMemory(ea);
            }
        }
        if (first.kind() == Kind.FLOAT)
            throw new AsmError(first.column(), "a floating-point literal needs '#'");
        if (end > from && t.get(from).kind() == Kind.IDENT && end - from == 1
                && M68kRegisters.isRegister(t.get(from).text()))
            throw new AsmError(t.get(from).column(), "register " + t.get(from).text() + " cannot be used here");
        for (int k = from; k < end; k++) {
            AsmToken x = t.get(k);
            if (x.kind() == Kind.IDENT && M68kRegisters.isRegister(x.text()))
                throw new AsmError(x.column(), "unexpected register " + x.text() + " in '" + text(t, from, to) + "'");
            if (x.isPunct("[") || x.isPunct("]") || x.isPunct("{") || x.isPunct("}") || x.isPunct("#"))
                throw new AsmError(x.column(), "unexpected '" + x.text() + "' in '" + text(t, from, to) + "'");
        }
        Ea ea = base(Kind2.ABS, column, op);
        ea.expr = ExprParser.parse(t, from, end, column);
        ea.absSize = absSize;
        return ea;
    }

    /**
     * {@code label.w} lexes as one identifier, because {@code .} is an identifier character: when
     * the token at {@code i} is an identifier ending in {@code .w} or {@code .l} that is not a
     * register, returns a copy of the list with it split into the name and the size.
     */
    static List<AsmToken> splitSizeSuffix(List<AsmToken> t, int i) {
        if (i < 0 || i >= t.size())
            return t;
        AsmToken x = t.get(i);
        if (x.kind() != Kind.IDENT || x.text().length() < 3 || M68kRegisters.isRegister(x.text()))
            return t;
        String s = x.text().substring(x.text().length() - 2).toLowerCase(Locale.ROOT);
        if (!s.equals(".w") && !s.equals(".l"))
            return t;
        String name = x.text().substring(0, x.text().length() - 2);
        if (name.equals(".") || name.isEmpty())
            return t;
        List<AsmToken> copy = new ArrayList<>(t);
        copy.set(i, new AsmToken(Kind.IDENT, name, 0, x.line(), x.column()));
        copy.add(i + 1, new AsmToken(Kind.IDENT, x.text().substring(name.length()), 0, x.line(),
                x.column() + name.length()));
        return copy;
    }

    private static Ea register(Operand op, String name, int column) {
        int r = M68kRegisters.general(name);
        if (r >= 0) {
            Ea ea = base(r < 8 ? Kind2.DREG : Kind2.AREG, column, op);
            ea.reg = r & 7;
            return ea;
        }
        int s = M68kRegisters.special(name);
        if (s != 0 && s != M68kRegisters.PC && s != M68kRegisters.ZPC) {
            Ea ea = base(Kind2.SPECIAL, column, op);
            ea.reg = s;
            return ea;
        }
        int c = M68kRegisters.control(name);
        if (c >= 0) {
            Ea ea = base(Kind2.CONTROL, column, op);
            ea.reg = c;
            return ea;
        }
        int f = M68kRegisters.fp(name);
        if (f >= 0) {
            Ea ea = base(Kind2.FPREG, column, op);
            ea.reg = f;
            return ea;
        }
        int fc = M68kRegisters.fpControl(name);
        if (fc != 0) {
            Ea ea = base(Kind2.FPCTRL, column, op);
            ea.reg = fc;
            return ea;
        }
        return null;
    }

    private static boolean isRegisterList(List<AsmToken> t, int from, int to) {
        boolean sep = false;
        for (int i = from; i < to; i++) {
            AsmToken x = t.get(i);
            boolean even = (i - from) % 2 == 0;
            if (even) {
                if (x.kind() != Kind.IDENT || !(M68kRegisters.general(x.text()) >= 0
                        || M68kRegisters.fp(x.text()) >= 0 || M68kRegisters.fpControl(x.text()) != 0))
                    return false;
            } else {
                if (!x.isPunct("/") && !x.isPunct("-"))
                    return false;
                sep = true;
            }
        }
        return sep && (to - from) % 2 == 1;
    }

    private static Ea registerList(Operand op, List<AsmToken> t, int from, int to, int column) {
        int family = family(t.get(from).text());
        int mask = 0;
        for (int i = from; i < to; i += 2) {
            AsmToken a = t.get(i);
            if (family(a.text()) != family)
                throw new AsmError(a.column(), "register list mixes " + familyName(family) + " and " + a.text());
            int lo = number(a.text());
            int hi = lo;
            if (i + 2 < to && t.get(i + 1).isPunct("-")) {
                AsmToken b = t.get(i + 2);
                if (family(b.text()) != family)
                    throw new AsmError(b.column(), "register range " + a.text() + "-" + b.text() + " mixes kinds");
                if (family == 2)
                    throw new AsmError(t.get(i + 1).column(), "FPU control registers are listed with '/', not ranges");
                hi = number(b.text());
                if (hi < lo)
                    throw new AsmError(a.column(), "register range " + a.text() + "-" + b.text() + " runs backwards");
                i += 2;
            }
            for (int r = lo; r <= hi; r++) {
                int bit = family == 2 ? r : 1 << r;
                if ((mask & bit) != 0)
                    throw new AsmError(a.column(), "register listed twice in '" + text(t, from, to) + "'");
                mask |= bit;
            }
        }
        Ea ea = base(family == 0 ? Kind2.REGLIST : family == 1 ? Kind2.FPLIST : Kind2.FPCTRL, column, op);
        if (family == 2)
            ea.reg = mask;
        else
            ea.mask = mask;
        return ea;
    }

    /** 0 general registers, 1 FP data registers, 2 FPU control registers. */
    private static int family(String name) {
        if (M68kRegisters.general(name) >= 0)
            return 0;
        if (M68kRegisters.fp(name) >= 0)
            return 1;
        return 2;
    }

    private static String familyName(int f) {
        return f == 0 ? "integer registers" : f == 1 ? "FP data registers" : "FPU control registers";
    }

    private static int number(String name) {
        int r = M68kRegisters.general(name);
        if (r >= 0)
            return r;
        r = M68kRegisters.fp(name);
        if (r >= 0)
            return r;
        return M68kRegisters.fpControl(name);
    }

    private static int matchOpen(List<AsmToken> t, int from, int close) {
        int depth = 0;
        for (int i = close; i >= from; i--) {
            if (t.get(i).isPunct(")"))
                depth++;
            else if (t.get(i).isPunct("(") && --depth == 0)
                return i;
        }
        return -1;
    }

    /** Splits tokens [from, to) at commas outside () and []; each item is {start, end}. */
    private static List<int[]> split(List<AsmToken> t, int from, int to) {
        List<int[]> items = new ArrayList<>();
        int depth = 0, start = from;
        for (int i = from; i <= to; i++) {
            if (i < to) {
                AsmToken x = t.get(i);
                if (x.isPunct("(") || x.isPunct("["))
                    depth++;
                else if (x.isPunct(")") || x.isPunct("]"))
                    depth--;
                if (!(depth == 0 && x.isPunct(",")))
                    continue;
            }
            items.add(new int[] {start, i});
            start = i + 1;
        }
        return items;
    }

    private static boolean startsWithRegisterOrBracket(List<AsmToken> t, int[] item) {
        if (item[0] >= item[1])
            return false;
        AsmToken x = t.get(item[0]);
        if (x.isPunct("["))
            return true;
        return x.kind() == Kind.IDENT && isBaseOrIndex(x.text());
    }

    private static boolean containsBracket(List<AsmToken> t, int from, int to) {
        for (int i = from; i < to; i++)
            if (t.get(i).isPunct("["))
                return true;
        return false;
    }

    private static boolean isBaseOrIndex(String name) {
        int s = M68kRegisters.special(name);
        return M68kRegisters.index(name) != null || s == M68kRegisters.PC || s == M68kRegisters.ZPC
                || M68kRegisters.suppressedBase(name) >= 0;
    }

    /**
     * Fills a MEM operand from the items inside its parentheses. {@code outer} is the
     * displacement written before the parentheses (classic {@code d16(An)}), or null.
     */
    private static void memory(Operand op, List<AsmToken> t, List<int[]> items, Expr outer, Ea ea) {
        ea.base = BASE_NONE;
        ea.baseSuppressed = true;
        boolean haveBase = false;
        boolean sawBracket = false;
        boolean afterBracket = false;
        if (outer != null) {
            ea.bd = outer;
        }
        for (int[] item : items) {
            int s = item[0], e = item[1];
            int c = s < t.size() ? t.get(Math.min(s, t.size() - 1)).column() : op.column();
            if (s >= e)
                throw new AsmError(c, "empty item in '" + ea.text + "'");
            AsmToken x = t.get(s);
            if (x.isPunct("[")) {
                if (sawBracket)
                    throw new AsmError(x.column(), "more than one '[...]' in an address");
                if (outer != null)
                    throw new AsmError(x.column(), "a memory indirect address takes its displacements inside the parentheses");
                if (!t.get(e - 1).isPunct("]"))
                    throw new AsmError(x.column(), "missing ']'");
                sawBracket = true;
                ea.indirect = INDIRECT_POST;
                for (int[] in : split(t, s + 1, e - 1)) {
                    if (in[0] >= in[1])
                        throw new AsmError(x.column(), "empty item in '[...]'");
                    if (memoryItem(t, in, ea, haveBase, true)) {
                        haveBase = true;
                    }
                }
                if (ea.index != null)
                    ea.indirect = INDIRECT_PRE;
                afterBracket = true;
                continue;
            }
            if (afterBracket) {
                // index (post-indexed) or outer displacement
                if (x.kind() == Kind.IDENT && M68kRegisters.index(x.text()) != null) {
                    if (ea.index != null)
                        throw new AsmError(x.column(), "more than one index register");
                    indexItem(t, item, ea);
                    ea.indirect = INDIRECT_POST;
                } else {
                    if (ea.od != null)
                        throw new AsmError(x.column(), "more than one outer displacement");
                    sizedExpr(t, item, ea, false);
                }
                continue;
            }
            if (memoryItem(t, item, ea, haveBase, false))
                haveBase = true;
        }
        if (outer != null && !haveBase && ea.index == null)
            throw new AsmError(ea.column, "expected a base register in '" + ea.text + "'");
    }

    private static String stripSize(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".w") || n.endsWith(".l") ? n.substring(0, n.length() - 2) : n;
    }

    /**
     * One item before (or inside) the brackets: a displacement, a base register or an index.
     * Returns true if it set the base.
     */
    private static boolean memoryItem(List<AsmToken> t, int[] item, Ea ea, boolean haveBase, boolean inBracket) {
        AsmToken x = t.get(item[0]);
        if (x.kind() == Kind.IDENT && isBaseOrIndex(x.text())) {
            String name = x.text();
            int special = M68kRegisters.special(name);
            int za = M68kRegisters.suppressedBase(name);
            M68kRegisters.Index idx = M68kRegisters.index(name);
            boolean plain = item[1] - item[0] == 1;
            boolean sized = !stripSize(name).equals(name.toLowerCase(Locale.ROOT));
            if (!haveBase && plain && !sized && (special == M68kRegisters.PC || special == M68kRegisters.ZPC)) {
                ea.base = BASE_PC;
                ea.baseSuppressed = special == M68kRegisters.ZPC;
                if (ea.baseSuppressed)
                    ea.forceFull = true;
                return true;
            }
            if (!haveBase && plain && !sized && za >= 0) {
                ea.base = BASE_NONE;
                ea.baseSuppressed = true;
                ea.reg = za;
                ea.forceFull = true;
                return true;
            }
            if (!haveBase && plain && !sized && idx != null && !idx.suppressed() && idx.reg() >= 8
                    && ea.index == null) {
                ea.base = idx.reg() - 8;
                ea.baseSuppressed = false;
                return true;
            }
            if (special == M68kRegisters.PC || special == M68kRegisters.ZPC || za >= 0)
                throw new AsmError(x.column(), name + " must come first in the address");
            if (ea.index != null)
                throw new AsmError(x.column(), "more than one index register");
            indexItem(t, item, ea);
            return false;
        }
        if (haveBase || ea.index != null)
            throw new AsmError(x.column(), "the displacement must come before the registers");
        if (ea.bd != null)
            throw new AsmError(x.column(), "more than one displacement");
        sizedExpr(t, item, ea, true);
        return false;
    }

    private static void indexItem(List<AsmToken> t, int[] item, Ea ea) {
        AsmToken x = t.get(item[0]);
        M68kRegisters.Index idx = M68kRegisters.index(x.text());
        if (idx == null)
            throw new AsmError(x.column(), "expected an index register, got '" + x.text() + "'");
        ea.index = idx;
        ea.scale = 0;
        if (idx.suppressed())
            ea.forceFull = true;
        if (item[1] - item[0] > 1) {
            if (!t.get(item[0] + 1).isPunct("*"))
                throw new AsmError(t.get(item[0] + 1).column(), "expected '*scale' after the index register");
            if (item[0] + 2 >= item[1])
                throw new AsmError(t.get(item[0] + 1).column(), "missing scale after '*'");
            ea.scaleExpr = ExprParser.parse(t, item[0] + 2, item[1], t.get(item[0] + 2).column());
        }
    }

    /** A displacement with an optional {@code .w}/{@code .l} size: bd ({@code base}) or od. */
    private static void sizedExpr(List<AsmToken> t, int[] item, Ea ea, boolean base) {
        List<AsmToken> split = splitSizeSuffix(t, item[1] - 1);
        if (split != t) {
            t = split;
            item = new int[] {item[0], item[1] + 1};
        }
        int end = item[1];
        int size = 0;
        AsmToken last = t.get(end - 1);
        if (end - item[0] >= 2 && last.kind() == Kind.IDENT) {
            String s = last.text().toLowerCase(Locale.ROOT);
            if (s.equals(".w") || s.equals(".l")) {
                size = s.equals(".w") ? 2 : 3;
                end--;
            }
        }
        for (int k = item[0]; k < end; k++) {
            AsmToken x = t.get(k);
            if (x.kind() == Kind.IDENT && M68kRegisters.isRegister(x.text()))
                throw new AsmError(x.column(), "unexpected register " + x.text() + " in a displacement");
        }
        Expr e = ExprParser.parse(t, item[0], end, t.get(item[0]).column());
        if (base) {
            ea.bd = e;
            ea.bdSize = size;
        } else {
            ea.od = e;
            ea.odSize = size;
        }
        if (size != 0)
            ea.forceFull = true;
    }

    private static Ea finishMemory(Ea ea) {
        if (ea.base == BASE_NONE && !ea.forceFull && ea.indirect == INDIRECT_NONE) {
            // (d,Xn) or (Xn) without a base: full format with the base suppressed
            ea.forceFull = true;
        }
        if (ea.indirect != INDIRECT_NONE)
            ea.forceFull = true;
        if (ea.base >= 0 && ea.base < 8 && ea.bd == null && ea.index == null && !ea.forceFull) {
            Ea ind = new Ea();
            ind.kind = Kind2.IND;
            ind.reg = ea.base;
            ind.column = ea.column;
            ind.text = ea.text;
            return ind;
        }
        return ea;
    }

    // ---- encoding context ----

    /** Per statement, what the first pass decided. */
    static final class Slots {
        final int[] levels = new int[12];
    }

    /**
     * Encoding of one instruction: collects 16-bit words, evaluates values (as placeholders while
     * sizing), and hands out size-selection slots.
     */
    static final class Enc {
        final AsmContext ctx;
        final boolean sizing;
        final M68kTarget target;
        final Slots slots;
        /** Address of the operation word: absolute, or a section offset in the first layout pass. */
        final long insnAddr;
        final boolean relative;
        int slot;
        int[] words = new int[12];
        int n;

        Enc(AsmContext ctx, boolean sizing, M68kTarget target, Slots slots, long insnAddr, boolean relative) {
            this.ctx = ctx;
            this.sizing = sizing;
            this.target = target;
            this.slots = slots;
            this.insnAddr = insnAddr;
            this.relative = relative;
        }

        boolean is040() {
            return target == M68kTarget.M68040;
        }

        void w(long v) {
            if (n == words.length)
                words = java.util.Arrays.copyOf(words, n * 2);
            words[n++] = (int) (v & 0xFFFF);
        }

        void l(long v) {
            w(v >>> 16);
            w(v);
        }

        /** Replaces the first word (the operation word) once the operands are known. */
        void op(int v) {
            words[0] = v & 0xFFFF;
        }

        /** Address of the next word to be emitted. */
        long here() {
            return insnAddr + 2L * n;
        }

        /** The value, or 0 while sizing if it is not known yet. */
        long value(Expr e) {
            if (sizing) {
                OptionalLong v = ctx.known(e);
                return v.orElse(0);
            }
            return ctx.eval(e).value();
        }

        /** The value if it is known now (always in the second pass). */
        OptionalLong known(Expr e) {
            if (sizing)
                return ctx.known(e);
            return OptionalLong.of(ctx.eval(e).value());
        }

        /** A value that must be a constant even while sizing (scale factors, bitfield widths). */
        long constant(Expr e, String what) {
            return ctx.evalNow(e, what);
        }

        /**
         * The next slot's level: while sizing, the larger of the earlier level and {@code wanted}
         * (capped at {@code max}); in the second pass, the final level.
         */
        int level(int wanted, int max) {
            int i = slot++;
            if (i >= slots.levels.length)
                throw new AsmError(1, "too many size choices in one instruction");
            if (sizing)
                slots.levels[i] = Math.max(slots.levels[i], Math.min(wanted, max));
            return slots.levels[i];
        }

        /**
         * {@code target - from} where {@code target} is an address: known if both are known now.
         * A plain number counts as an absolute address when {@code numberIsAddress}, else it is
         * returned as it is (a displacement written out).
         */
        OptionalLong distance(Expr target, long from, boolean numberIsAddress) {
            Val t;
            try {
                t = ctx.eval(target);
            } catch (AsmContext.Unknown u) {
                return OptionalLong.empty();
            }
            if (t.isAbs() && !numberIsAddress)
                return OptionalLong.of(t.value());
            if (relative) {
                Val d = ctx.dotValue();
                if (t.section() >= 0 && t.section() == d.section())
                    return OptionalLong.of(t.value() - from);
                return OptionalLong.empty();
            }
            return OptionalLong.of(t.value() - from);
        }

        void checkRange(long v, long lo, long hi, int column, String what) {
            if (!sizing && (v < lo || v > hi))
                throw new AsmError(column, what + " " + v + " out of range (" + lo + ".." + hi + ")");
        }

        AsmError needs040(int column, String what) {
            return new AsmError(column, what + " needs a 68040 (assembling for the 68000)");
        }
    }

    // ---- immediate kinds ----

    static final int IMM_B = 1;
    static final int IMM_W = 2;
    static final int IMM_L = 4;
    static final int IMM_S = 5;
    static final int IMM_D = 8;
    static final int IMM_X = 12;
    static final int IMM_P = 13;

    static int immFor(int size) {
        return switch (size) {
            case 1 -> IMM_B;
            case 2 -> IMM_W;
            default -> IMM_L;
        };
    }

    static boolean fitsSigned(long v, int bits) {
        long lo = -(1L << (bits - 1)), hi = (1L << (bits - 1)) - 1;
        return v >= lo && v <= hi;
    }

    /** A 32-bit value read as signed (0xFFFF_8000 is -32768). */
    static long signed32(long v) {
        if (v >= 0x8000_0000L && v <= 0xFFFF_FFFFL)
            return v - 0x1_0000_0000L;
        return v;
    }

    static boolean fits32(long v) {
        return v >= -0x8000_0000L && v <= 0xFFFF_FFFFL;
    }

    // ---- effective address encoding ----

    /**
     * Checks the operand against the allowed categories and encodes it: returns the 6-bit
     * mode/register field and appends the extension words. {@code imm} gives the immediate's
     * size (IMM_*).
     */
    static int ea(Enc e, Ea ea, int allowed, int imm, String mnemonic) {
        int cat = ea.category();
        if (cat == 0)
            throw new AsmError(ea.column, "'" + mnemonic + "' does not take " + kindName(ea));
        if ((allowed & cat) == 0)
            throw new AsmError(ea.column, "'" + mnemonic + "' does not take " + describe(cat));
        return encode(e, ea, imm);
    }

    static String kindName(Ea ea) {
        return switch (ea.kind) {
            case SPECIAL, CONTROL, FPCTRL -> "register " + ea.text;
            case FPREG -> "an FP data register";
            case REGLIST, FPLIST -> "a register list";
            case PAIR -> "a register pair";
            default -> describe(ea.category());
        };
    }

    static int encode(Enc e, Ea ea, int imm) {
        switch (ea.kind) {
            case DREG -> { return ea.reg; }
            case AREG -> { return 8 | ea.reg; }
            case IND -> { return 0x10 | ea.reg; }
            case POSTINC -> { return 0x18 | ea.reg; }
            case PREDEC -> { return 0x20 | ea.reg; }
            case ABS -> { return absolute(e, ea); }
            case IMM, FIMM -> {
                immediate(e, ea, imm);
                return 0x3C;
            }
            case MEM -> { return memory(e, ea); }
            default -> throw new AsmError(ea.column, "unexpected " + kindName(ea));
        }
    }

    private static int absolute(Enc e, Ea ea) {
        int lvl;
        if (ea.absSize != 0) {
            lvl = ea.absSize - 1;
        } else {
            OptionalLong v = e.sizing ? e.known(ea.expr) : OptionalLong.empty();
            int wanted = v.isPresent() && fitsSigned(signed32(v.getAsLong()), 16) ? 0 : 1;
            lvl = e.level(wanted, 1);
        }
        long v = e.value(ea.expr);
        if (lvl == 0) {
            if (!e.sizing && !fitsSigned(signed32(v), 16))
                throw new AsmError(ea.column, String.format("absolute address 0x%X does not fit .w "
                        + "(0..0x7FFF or 0xFFFF8000..0xFFFFFFFF)", v));
            e.w(v);
            return 0x38;
        }
        if (!e.sizing && !fits32(v))
            throw new AsmError(ea.column, String.format("absolute address 0x%X does not fit in 32 bits", v));
        e.l(v);
        return 0x39;
    }

    /** Appends an immediate of kind {@code imm}. */
    static void immediate(Enc e, Ea ea, int imm) {
        if (ea.kind == Kind2.FIMM) {
            if (imm < IMM_S)
                throw new AsmError(ea.column, "a floating-point literal needs a floating-point size (.s, .d, .x or .p)");
            floatImmediate(e, ea.fnegative, ea.fvalue, imm, ea.column);
            return;
        }
        long v = e.value(ea.expr);
        switch (imm) {
            case IMM_B -> {
                e.checkRange(v, -128, 255, ea.column, "immediate");
                e.w(v & 0xFF);
            }
            case IMM_W -> {
                e.checkRange(v, -32768, 65535, ea.column, "immediate");
                e.w(v);
            }
            case IMM_L -> {
                if (!e.sizing && !fits32(v))
                    throw new AsmError(ea.column, "immediate " + v + " does not fit in 32 bits");
                e.l(v);
            }
            default -> {
                // an integer in a floating-point format: its value converted exactly
                boolean neg = v < 0;
                BigDecimal mag = new BigDecimal(java.math.BigInteger.valueOf(v).abs());
                floatImmediate(e, neg, mag, imm, ea.column);
            }
        }
    }

    static void floatImmediate(Enc e, boolean neg, BigDecimal mag, int imm, int column) {
        try {
            switch (imm) {
                case IMM_S -> e.l(Integer.toUnsignedLong(M68kFloat.single(neg, mag)));
                case IMM_D -> {
                    long d = M68kFloat.dbl(neg, mag);
                    e.l(d >>> 32);
                    e.l(d);
                }
                case IMM_X -> {
                    for (int x : M68kFloat.extended(neg, mag))
                        e.l(x);
                }
                case IMM_P -> {
                    for (int x : M68kFloat.packed(neg, mag))
                        e.l(x);
                }
                default -> throw new AsmError(column, "a floating-point literal needs a floating-point size");
            }
        } catch (IllegalArgumentException ex) {
            throw new AsmError(column, ex.getMessage());
        }
    }

    private static int memory(Enc e, Ea ea) {
        boolean pc = ea.base == BASE_PC;
        int scale = 0;
        if (ea.index != null && ea.scaleExpr != null) {
            long k = e.constant(ea.scaleExpr, "scale");
            scale = switch ((int) k) {
                case 1 -> 0;
                case 2 -> 1;
                case 4 -> 2;
                case 8 -> 3;
                default -> -1;
            };
            if (scale < 0 || k != (int) k)
                throw new AsmError(ea.column, "scale " + k + " must be 1, 2, 4 or 8");
            if (scale != 0 && !e.is040())
                throw e.needs040(ea.column, "scaled index");
        }
        if (ea.forceFull && !e.is040())
            throw e.needs040(ea.column, ea.indirect != INDIRECT_NONE ? "memory indirect addressing"
                    : "this addressing mode (full extension word)");
        int field = pc ? 0x3B : 0x30 | (ea.base >= 0 ? ea.base : ea.reg);
        long extAddr = e.here();
        if (!ea.forceFull && ea.index == null) {
            // (d16,An) or (d16,PC); on the 68040 a full format with bd.l when it does not fit
            OptionalLong d = disp(e, ea.bd, pc, extAddr);
            int wanted = d.isPresent() && !fitsSigned(d.getAsLong(), 16) ? 1 : 0;
            int lvl = e.level(wanted, e.is040() ? 1 : 0);
            long v = e.sizing ? 0 : disp(e, ea.bd, pc, extAddr).orElse(0);
            if (lvl == 0) {
                if (!e.sizing && !fitsSigned(v, 16))
                    throw new AsmError(ea.column, "displacement " + v + " does not fit in 16 bits"
                            + (e.is040() ? "" : " (a 68040 could use a 32-bit displacement)"));
                e.w(v);
                return pc ? 0x3A : 0x28 | ea.base;
            }
            e.w(0x0100 | 0x0040 | 0x0030);   // full: IS, bd.l, no memory indirect
            e.l(v);
            return field;
        }
        boolean noIndex = ea.index == null || ea.index.suppressed();
        int indexBits = 0;
        if (!noIndex)
            indexBits = (ea.index.reg() >= 8 ? 0x8000 : 0) | (ea.index.reg() & 7) << 12
                    | (ea.index.isLong() ? 0x0800 : 0) | scale << 9;
        if (!ea.forceFull) {
            // brief format, or full with a word or long base displacement on the 68040
            OptionalLong d = ea.bd == null ? OptionalLong.of(0) : disp(e, ea.bd, pc, extAddr);
            int wanted = 0;
            if (d.isPresent())
                wanted = fitsSigned(d.getAsLong(), 8) ? 0 : fitsSigned(d.getAsLong(), 16) ? 1 : 2;
            int lvl = e.level(wanted, e.is040() ? 2 : 0);
            long v = e.sizing || ea.bd == null ? 0 : disp(e, ea.bd, pc, extAddr).orElse(0);
            if (lvl == 0) {
                if (!e.sizing && !fitsSigned(v, 8))
                    throw new AsmError(ea.column, "displacement " + v + " does not fit in 8 bits"
                            + (e.is040() ? "" : " (a 68040 could use a larger displacement)"));
                e.w(indexBits | (int) (v & 0xFF));
                return field;
            }
            e.w(indexBits | 0x0100 | (lvl == 1 ? 0x20 : 0x30));
            if (lvl == 1) {
                if (!e.sizing && !fitsSigned(v, 16))
                    throw new AsmError(ea.column, "displacement " + v + " does not fit in 16 bits");
                e.w(v);
            } else {
                e.l(v);
            }
            return field;
        }
        // full format, explicitly
        int ext = indexBits | 0x0100;
        boolean bs = ea.baseSuppressed;
        if (bs)
            ext |= 0x0080;
        if (noIndex)
            ext |= 0x0040;
        int extPos = e.n;
        e.w(0);   // patched below
        int bdLevel = 0;   // 1 null, 2 word, 3 long
        long bdValue = 0;
        if (ea.bd == null) {
            bdLevel = 1;
        } else {
            boolean pcRelative = pc && !bs;
            OptionalLong d = pcRelative ? disp(e, ea.bd, true, extAddr) : e.known(ea.bd);
            if (ea.bdSize != 0) {
                bdLevel = ea.bdSize;
            } else {
                int wanted = d.isPresent() && !fitsSigned(signed32(d.getAsLong()), 16) ? 1 : 0;
                bdLevel = 2 + e.level(wanted, 1);
            }
            bdValue = e.sizing ? 0 : pcRelative ? disp(e, ea.bd, true, extAddr).orElse(0) : e.value(ea.bd);
        }
        ext |= bdLevel << 4;
        if (bdLevel == 2) {
            if (!e.sizing && !fitsSigned(signed32(bdValue), 16))
                throw new AsmError(ea.column, "base displacement " + bdValue + " does not fit in 16 bits");
            e.w(bdValue);
        } else if (bdLevel == 3) {
            if (!e.sizing && !fits32(bdValue))
                throw new AsmError(ea.column, "base displacement " + bdValue + " does not fit in 32 bits");
            e.l(bdValue);
        }
        if (ea.indirect != INDIRECT_NONE) {
            int odLevel;
            long odValue = 0;
            if (ea.od == null) {
                odLevel = 1;
            } else {
                if (ea.odSize != 0) {
                    odLevel = ea.odSize;
                } else {
                    OptionalLong d = e.known(ea.od);
                    int wanted = d.isPresent() && !fitsSigned(signed32(d.getAsLong()), 16) ? 1 : 0;
                    odLevel = 2 + e.level(wanted, 1);
                }
                odValue = e.value(ea.od);
            }
            int iis = noIndex ? odLevel : (ea.indirect == INDIRECT_POST ? 4 : 0) | odLevel;
            ext |= iis;
            if (odLevel == 2) {
                if (!e.sizing && !fitsSigned(signed32(odValue), 16))
                    throw new AsmError(ea.column, "outer displacement " + odValue + " does not fit in 16 bits");
                e.w(odValue);
            } else if (odLevel == 3) {
                if (!e.sizing && !fits32(odValue))
                    throw new AsmError(ea.column, "outer displacement " + odValue + " does not fit in 32 bits");
                e.l(odValue);
            }
        } else if (ea.od != null) {
            throw new AsmError(ea.column, "an outer displacement needs a memory indirect address '[...]'");
        }
        e.words[extPos] = ext & 0xFFFF;
        return field;
    }

    /**
     * A displacement: from An as written, a 32-bit value read as signed (so {@code $FFFFFFFE(a0)}
     * is -2, as GNU as reads it); from PC relative to the extension word.
     */
    private static OptionalLong disp(Enc e, Expr bd, boolean pc, long extAddr) {
        if (bd == null)
            return OptionalLong.of(0);
        if (pc)
            return e.distance(bd, extAddr, false);
        OptionalLong v = e.known(bd);
        return v.isPresent() ? OptionalLong.of(signed32(v.getAsLong())) : v;
    }
}
