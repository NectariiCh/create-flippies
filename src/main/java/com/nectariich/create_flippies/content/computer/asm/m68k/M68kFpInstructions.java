package com.nectariich.create_flippies.content.computer.asm.m68k;

import com.nectariich.create_flippies.content.computer.asm.AsmError;
import com.nectariich.create_flippies.content.computer.asm.Expr;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Ea;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Enc;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.Kind2;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.CONTROL;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.CONTROL_ALT;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DATA;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DATA_ALT;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DN;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.AN;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ALL;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ALTERABLE;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_B;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_D;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_L;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_P;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_S;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_W;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_X;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.POST;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.PRE;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ea;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.fitsSigned;

/**
 * The 68040 FPU instructions (spec 10.2.4, 10.7.6), all with coprocessor id 1 (operation words
 * 0xF2xx and 0xF3xx). Encodings follow the programmer's reference manual, section 5 (FPU
 * instructions), with its errata: FDBcc's displacement counts from the instruction address plus
 * four (the address of the displacement word), FMOD's opmode is 0x21.
 *
 * General instructions: operation word 0xF200 | EA, command word
 * {@code 0 R/M 0 src(12..10) dst(9..7) opmode(6..0)}; with R/M = 1 the source is the EA in the
 * format of bits 12..10, with R/M = 0 it is the FP register in bits 12..10. FMOVE to memory:
 * {@code 0 1 1 format dst-src k-factor}. FMOVE and FMOVEM of control registers:
 * {@code 1 0 dr select 0...}. FMOVEM of data registers: {@code 1 1 dr mode 000 list}.
 */
final class M68kFpInstructions {

    private M68kFpInstructions() {
    }

    enum G { ARITH, MONADIC, MOVE, SMOVE, TST, SINCOS, MOVECR, MOVEM, NOP, SAVE, RESTORE, BCC, DBCC, SCC, TRAPCC }

    record Def(G kind, int opmode) { }

    private static final Map<String, Def> DEFS = new HashMap<>();

    static {
        Object[][] monadic = {
                {"fint", M68kFpOpcodes.FINT}, {"fsinh", M68kFpOpcodes.FSINH}, {"fintrz", M68kFpOpcodes.FINTRZ},
                {"fsqrt", M68kFpOpcodes.FSQRT}, {"flognp1", M68kFpOpcodes.FLOGNP1},
                {"fetoxm1", M68kFpOpcodes.FETOXM1}, {"ftanh", M68kFpOpcodes.FTANH}, {"fatan", M68kFpOpcodes.FATAN},
                {"fasin", M68kFpOpcodes.FASIN}, {"fatanh", M68kFpOpcodes.FATANH}, {"fsin", M68kFpOpcodes.FSIN},
                {"ftan", M68kFpOpcodes.FTAN}, {"fetox", M68kFpOpcodes.FETOX}, {"ftwotox", M68kFpOpcodes.FTWOTOX},
                {"ftentox", M68kFpOpcodes.FTENTOX}, {"flogn", M68kFpOpcodes.FLOGN}, {"flog10", M68kFpOpcodes.FLOG10},
                {"flog2", M68kFpOpcodes.FLOG2}, {"fabs", M68kFpOpcodes.FABS}, {"fcosh", M68kFpOpcodes.FCOSH},
                {"fneg", M68kFpOpcodes.FNEG}, {"facos", M68kFpOpcodes.FACOS}, {"fcos", M68kFpOpcodes.FCOS},
                {"fgetexp", M68kFpOpcodes.FGETEXP}, {"fgetman", M68kFpOpcodes.FGETMAN},
                {"fssqrt", M68kFpOpcodes.FSSQRT}, {"fdsqrt", M68kFpOpcodes.FDSQRT}, {"fsabs", M68kFpOpcodes.FSABS},
                {"fsneg", M68kFpOpcodes.FSNEG}, {"fdabs", M68kFpOpcodes.FDABS}, {"fdneg", M68kFpOpcodes.FDNEG}};
        for (Object[] m : monadic)
            DEFS.put((String) m[0], new Def(G.MONADIC, (Integer) m[1]));
        Object[][] dyadic = {
                {"fdiv", M68kFpOpcodes.FDIV}, {"fmod", M68kFpOpcodes.FMOD}, {"fadd", M68kFpOpcodes.FADD},
                {"fmul", M68kFpOpcodes.FMUL}, {"fsgldiv", M68kFpOpcodes.FSGLDIV}, {"frem", M68kFpOpcodes.FREM},
                {"fscale", M68kFpOpcodes.FSCALE}, {"fsglmul", M68kFpOpcodes.FSGLMUL}, {"fsub", M68kFpOpcodes.FSUB},
                {"fcmp", M68kFpOpcodes.FCMP}, {"fsdiv", M68kFpOpcodes.FSDIV}, {"fsadd", M68kFpOpcodes.FSADD},
                {"fsmul", M68kFpOpcodes.FSMUL}, {"fddiv", M68kFpOpcodes.FDDIV}, {"fdadd", M68kFpOpcodes.FDADD},
                {"fdmul", M68kFpOpcodes.FDMUL}, {"fssub", M68kFpOpcodes.FSSUB}, {"fdsub", M68kFpOpcodes.FDSUB}};
        for (Object[] m : dyadic)
            DEFS.put((String) m[0], new Def(G.ARITH, (Integer) m[1]));
        DEFS.put("fmove", new Def(G.MOVE, M68kFpOpcodes.FMOVE));
        DEFS.put("fsmove", new Def(G.SMOVE, M68kFpOpcodes.FSMOVE));
        DEFS.put("fdmove", new Def(G.SMOVE, M68kFpOpcodes.FDMOVE));
        DEFS.put("ftst", new Def(G.TST, M68kFpOpcodes.FTST));
        DEFS.put("fsincos", new Def(G.SINCOS, M68kFpOpcodes.FSINCOS));
        DEFS.put("fmovecr", new Def(G.MOVECR, 0));
        DEFS.put("fmovem", new Def(G.MOVEM, 0));
        DEFS.put("fnop", new Def(G.NOP, 0));
        DEFS.put("fsave", new Def(G.SAVE, 0));
        DEFS.put("frestore", new Def(G.RESTORE, 0));
        for (int p = 0; p < M68kFpOpcodes.PREDICATES.length; p++) {
            String name = M68kFpOpcodes.PREDICATES[p];
            DEFS.putIfAbsent("fb" + name, new Def(G.BCC, p));
            DEFS.putIfAbsent("fdb" + name, new Def(G.DBCC, p));
            DEFS.putIfAbsent("fs" + name, new Def(G.SCC, p));
            DEFS.putIfAbsent("ftrap" + name, new Def(G.TRAPCC, p));
        }
    }

    static Def lookup(String base) {
        return DEFS.get(base);
    }

    /** Format code of a size letter (spec 10.2.4), or -1. */
    static int format(char c) {
        return switch (c) {
            case 'l' -> M68kFpOpcodes.FORMAT_L;
            case 's' -> M68kFpOpcodes.FORMAT_S;
            case 'x' -> M68kFpOpcodes.FORMAT_X;
            case 'p' -> M68kFpOpcodes.FORMAT_P;
            case 'w' -> M68kFpOpcodes.FORMAT_W;
            case 'd' -> M68kFpOpcodes.FORMAT_D;
            case 'b' -> M68kFpOpcodes.FORMAT_B;
            default -> -1;
        };
    }

    /** The immediate kind of a format letter. */
    static int immKind(char c) {
        return switch (c) {
            case 'b' -> IMM_B;
            case 'w' -> IMM_W;
            case 'l' -> IMM_L;
            case 's' -> IMM_S;
            case 'd' -> IMM_D;
            case 'p' -> IMM_P;
            default -> IMM_X;
        };
    }

    private static int fp(Ea ea, String base) {
        if (ea.kind != Kind2.FPREG)
            throw new AsmError(ea.column, "'" + base + "' expects an FP data register here, got '" + ea.text + "'");
        return ea.reg;
    }

    /** Checks a memory or register source in format {@code c}: Dn only for b, w, l, s. */
    private static int allowedSource(char c) {
        boolean small = c == 'b' || c == 'w' || c == 'l' || c == 's';
        return (DATA & ~(small ? 0 : DN));
    }

    static void encode(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        if (!e.is040())
            throw e.needs040(column, "'" + base + "'");
        if (given != 0 && format(given) < 0)
            throw new AsmError(column, "'" + base + "." + given + "': unknown size ." + given);
        switch (d.kind()) {
            case ARITH, MONADIC, SMOVE, TST -> general(e, d, base, given, ops, column);
            case MOVE -> fmove(e, base, given, ops, column);
            case SINCOS -> {
                if (ops.size() != 2)
                    throw new AsmError(column, "'fsincos' takes <ea>,FPc:FPs");
                Ea pair = ops.get(1);
                if (pair.kind != Kind2.PAIR)
                    throw new AsmError(pair.column, "'fsincos' writes a pair FPc:FPs, got '" + pair.text + "'");
                int c = fp(pair.first, base), s = fp(pair.second, base);
                source(e, base, given, ops.get(0), s, M68kFpOpcodes.FSINCOS | c);
            }
            case MOVECR -> {
                if (given != 0 && given != 'x')
                    throw new AsmError(column, "'fmovecr." + given + "': fmovecr takes .x");
                M68kInstructions.count(ops, 2, base, column);
                long v = e.value(M68kInstructions.imm(ops.get(0), base));
                e.checkRange(v, 0, 0x7F, ops.get(0).column, "constant ROM offset");
                int n = fp(ops.get(1), base);
                e.w(0xF200);
                e.w(0x5C00 | n << 7 | (int) (v & 0x7F));
            }
            case MOVEM -> fmovem(e, base, given, ops, column);
            case NOP -> {
                noSize(given, base, column);
                M68kInstructions.count(ops, 0, base, column);
                e.w(0xF280);
                e.w(0);
            }
            case SAVE, RESTORE -> {
                noSize(given, base, column);
                M68kInstructions.count(ops, 1, base, column);
                boolean save = d.kind() == G.SAVE;
                e.w(0);
                int f = ea(e, ops.get(0), save ? CONTROL_ALT | PRE : CONTROL | POST, IMM_L, base);
                e.op((save ? 0xF300 : 0xF340) | f);
            }
            case BCC -> fbcc(e, d, base, given, ops, column);
            case DBCC -> {
                if (given != 0 && given != 'w')
                    throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes .w");
                M68kInstructions.count(ops, 2, base, column);
                int r = M68kInstructions.dreg(ops.get(0), base);
                Expr t = M68kInstructions.target(ops.get(1), base);
                e.w(0xF248 | r);
                e.w(d.opmode());
                long v = distance(e, t, e.insnAddr + 4, ops.get(1).column);
                if (!e.sizing && !fitsSigned(v, 16))
                    throw new AsmError(ops.get(1).column, "branch target out of range (" + v
                            + " bytes, limit -32768..+32767)");
                e.w(v);
            }
            case SCC -> {
                if (given != 0 && given != 'b')
                    throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes .b");
                M68kInstructions.count(ops, 1, base, column);
                e.w(0);
                e.w(d.opmode());
                e.op(0xF240 | ea(e, ops.get(0), DATA_ALT, IMM_L, base));
            }
            case TRAPCC -> {
                if (ops.isEmpty()) {
                    if (given != 0)
                        throw new AsmError(column, "'" + base + "." + given + "' needs an immediate operand");
                    e.w(0xF27C);
                    e.w(d.opmode());
                } else {
                    M68kInstructions.count(ops, 1, base, column);
                    if (given != 0 && given != 'w' && given != 'l')
                        throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes .w or .l");
                    long v = e.value(M68kInstructions.imm(ops.get(0), base));
                    if (given == 'l') {
                        if (!e.sizing && !M68kOperands.fits32(v))
                            throw new AsmError(ops.get(0).column, "immediate " + v + " does not fit in 32 bits");
                        e.w(0xF27B);
                        e.w(d.opmode());
                        e.l(v);
                    } else {
                        e.checkRange(v, -32768, 65535, ops.get(0).column, "immediate");
                        e.w(0xF27A);
                        e.w(d.opmode());
                        e.w(v);
                    }
                }
            }
        }
    }

    private static void noSize(char given, String base, int column) {
        if (given != 0)
            throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes no size");
    }

    /** ARITH, MONADIC, FSMOVE/FDMOVE and FTST. */
    private static void general(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        boolean tst = d.kind() == G.TST;
        boolean monadic = d.kind() == G.MONADIC || d.kind() == G.SMOVE;
        if (tst) {
            M68kInstructions.count(ops, 1, base, column);
            source(e, base, given, ops.get(0), 0, d.opmode());
            return;
        }
        if (ops.size() == 1 && d.kind() == G.MONADIC) {
            int r = fp(ops.get(0), base);
            regSize(given, base, column);
            e.w(0xF200);
            e.w(r << 10 | r << 7 | d.opmode());
            return;
        }
        if (ops.size() != 2)
            throw new AsmError(column, "'" + base + "' expects " + (monadic ? "1 or 2 operands" : "2 operands")
                    + ", got " + ops.size());
        int dst = fp(ops.get(1), base);
        source(e, base, given, ops.get(0), dst, d.opmode());
    }

    private static void regSize(char given, String base, int column) {
        if (given != 0 && given != 'x')
            throw new AsmError(column, "'" + base + "." + given + "': FP register to register takes .x");
    }

    /** A general instruction whose source is FPm or an EA, destination register {@code dst}. */
    private static void source(Enc e, String base, char given, Ea src, int dst, int opmode) {
        if (src.kind == Kind2.FPREG) {
            regSize(given, base, src.column);
            e.w(0xF200);
            e.w(src.reg << 10 | dst << 7 | opmode);
            return;
        }
        char c = given == 0 ? 'x' : given;
        if (src.kind == Kind2.DREG && !(c == 'b' || c == 'w' || c == 'l' || c == 's'))
            throw new AsmError(src.column, "'" + base + "' from a data register needs .b, .w, .l or .s");
        e.w(0);
        e.w(0x4000 | format(c) << 10 | dst << 7 | opmode);
        e.op(0xF200 | ea(e, src, allowedSource(c), immKind(c), base));
    }

    private static void fmove(Enc e, String base, char given, List<Ea> ops, int column) {
        M68kInstructions.count(ops, 2, base, column);
        Ea a = ops.get(0), b = ops.get(1);
        if (a.kind == Kind2.FPCTRL || b.kind == Kind2.FPCTRL) {
            if (given != 0 && given != 'l')
                throw new AsmError(column, "'" + base + "." + given + "': FPU control registers move as .l");
            control(e, base, a, b, column);
            return;
        }
        if (b.kind == Kind2.FPREG) {
            source(e, base, given, a, b.reg, M68kFpOpcodes.FMOVE);
            return;
        }
        int src = fp(a, base);
        char c = given == 0 ? 'x' : given;
        if (b.kind == Kind2.DREG && !(c == 'b' || c == 'w' || c == 'l' || c == 's'))
            throw new AsmError(b.column, "'" + base + "' to a data register needs .b, .w, .l or .s");
        int fmt = format(c);
        int k = 0;
        if (c == 'p') {
            if (b.braces != null) {
                if (b.braces.size() != 1)
                    throw new AsmError(b.column, "the k-factor is written {#k} or {Dn}");
                M68kOperands.BraceItem kf = b.braces.get(0);
                if (kf.dreg() >= 0) {
                    fmt = M68kFpOpcodes.FORMAT_P_DYNAMIC;
                    k = kf.dreg() << 4;
                } else {
                    long v = e.value(kf.expr());
                    e.checkRange(v, -64, 17, kf.column(), "k-factor");
                    k = (int) (v & 0x7F);
                }
            }
        } else if (b.braces != null) {
            throw new AsmError(b.column, "only fmove.p takes a k-factor {...}");
        }
        e.w(0);
        e.w(0x6000 | fmt << 10 | src << 7 | k);
        e.op(0xF200 | ea(e, b, DATA_ALT, IMM_L, base));
    }

    /** FMOVE.L or FMOVEM.L of FPCR, FPSR, FPIAR. */
    private static void control(Enc e, String base, Ea a, Ea b, int column) {
        boolean toMem = a.kind == Kind2.FPCTRL;
        Ea regs = toMem ? a : b, other = toMem ? b : a;
        if (other.kind == Kind2.FPCTRL)
            throw new AsmError(other.column, "'" + base + "' cannot move between two FPU control registers");
        int sel = regs.reg;
        boolean single = Integer.bitCount(sel) == 1;
        int allowed = toMem ? ALTERABLE : ALL;
        if (!single) {
            allowed &= ~(DN | AN);
            if (!toMem)
                allowed &= ~IMM;
        } else if (sel != M68kRegisters.FPIAR) {
            allowed &= ~AN;
        }
        e.w(0);
        e.w(0x8000 | (toMem ? 0x2000 : 0) | sel << 10);
        e.op(0xF200 | ea(e, other, allowed, IMM_L, base));
    }

    private static void fmovem(Enc e, String base, char given, List<Ea> ops, int column) {
        M68kInstructions.count(ops, 2, base, column);
        Ea a = ops.get(0), b = ops.get(1);
        if (a.kind == Kind2.FPCTRL || b.kind == Kind2.FPCTRL) {
            if (given != 0 && given != 'l')
                throw new AsmError(column, "'" + base + "." + given + "': FPU control registers move as .l");
            control(e, base, a, b, column);
            return;
        }
        if (given != 0 && given != 'x')
            throw new AsmError(column, "'" + base + "." + given + "': FP data registers move as .x");
        boolean toMem = isFpList(a) && !isFpList(b);
        if (!toMem && !isFpList(b))
            throw new AsmError(a.column, "'fmovem' needs an FP register list and a memory operand");
        Ea list = toMem ? a : b, mem = toMem ? b : a;
        boolean predec = mem.kind == Kind2.PREDEC;
        if (predec && !toMem)
            throw new AsmError(mem.column, "'fmovem' does not take -(An) as a source");
        int mode;
        int listBits;
        e.w(0);
        if (list.kind == Kind2.DREG) {
            mode = predec ? 1 : 3;
            listBits = list.reg << 4;
        } else {
            mode = predec ? 0 : 2;
            int mask;
            if (list.kind == Kind2.IMM) {
                long v = e.value(list.expr);
                e.checkRange(v, 0, 0xFF, list.column, "register mask");
                listBits = (int) (v & 0xFF);
            } else {
                mask = list.kind == Kind2.FPREG ? 1 << list.reg : list.mask;
                // bit 7 is FP0 in the control and postincrement modes, FP7 in predecrement mode
                listBits = predec ? mask : Integer.reverse(mask) >>> 24;
            }
        }
        e.w(0xC000 | (toMem ? 0x2000 : 0) | mode << 11 | listBits);
        e.op(0xF200 | ea(e, mem, toMem ? CONTROL_ALT | PRE : CONTROL | POST, IMM_L, base));
    }

    private static boolean isFpList(Ea ea) {
        return ea.kind == Kind2.FPLIST || ea.kind == Kind2.FPREG || ea.kind == Kind2.DREG || ea.kind == Kind2.IMM;
    }

    private static long distance(Enc e, Expr t, long from, int column) {
        OptionalLong d = e.distance(t, from, true);
        if (e.sizing)
            return d.orElse(0);
        long v = d.orElseThrow();
        if ((v & 1) != 0)
            throw new AsmError(column, "branch target is at an odd address (displacement " + v + ")");
        return v;
    }

    private static void fbcc(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        M68kInstructions.count(ops, 1, base, column);
        Expr t = M68kInstructions.target(ops.get(0), base);
        int oc = ops.get(0).column;
        int lvl;
        if (given == 'w') {
            lvl = 0;
        } else if (given == 'l') {
            lvl = 1;
        } else if (given != 0) {
            throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes .w or .l");
        } else {
            OptionalLong dist = e.sizing ? e.distance(t, e.insnAddr + 2, true) : OptionalLong.empty();
            int wanted = dist.isPresent() && !fitsSigned(dist.getAsLong(), 16) ? 1 : 0;
            lvl = e.level(wanted, 1);
        }
        long v = distance(e, t, e.insnAddr + 2, oc);
        if (lvl == 0) {
            if (!e.sizing && !fitsSigned(v, 16))
                throw new AsmError(oc, "branch target out of range (" + v + " bytes, limit -32768..+32767 for .w)");
            e.w(0xF280 | d.opmode());
            e.w(v);
        } else {
            e.w(0xF2C0 | d.opmode());
            e.l(v);
        }
    }
}
