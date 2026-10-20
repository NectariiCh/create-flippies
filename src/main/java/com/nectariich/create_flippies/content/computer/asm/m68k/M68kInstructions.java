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

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ALL;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ALTERABLE;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.AN;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.CONTROL;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.CONTROL_ALT;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DATA;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DATA_ALT;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.DN;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_B;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_L;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.IMM_W;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.MEM_ALT;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.PCD;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.PCX;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.POST;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.PRE;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.ea;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.fitsSigned;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.immFor;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.immediate;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kOperands.signed32;

/**
 * The integer instructions of the 68000 and 68040 (spec 10.2.2, 10.2.3, 10.7): the mnemonic
 * table and one encoder per instruction form. Encodings follow the 68000 family programmer's
 * reference manual, section 4 (integer instructions) and section 8 (instruction format summary).
 *
 * Every encoder first emits a placeholder operation word, then the extension words in the order
 * the chip reads them (immediate data, then the source operand's, then the destination's; the
 * second word of MOVEM, MULx.L, DIVx.L, CHK2, MOVES, MOVEC and bitfields comes before the
 * operand's), and finally patches the operation word.
 */
final class M68kInstructions {

    private M68kInstructions() {
    }

    enum F {
        MOVE, MOVEA, MOVEQ, MOVEP, MOVEM, LEA, PEA, ADDSUB, ADDSUBA, IMMOP, QUICK, ADDSUBX, CMP, CMPA,
        CMPM, ANDOR, EOR, UNARY, TST, EXT, EXTB, SWAP, MULDIV, DIVL, BCD, NBCD, SHIFT, BIT, BCC, DBCC,
        SCC, JUMP, FIXED, STOP, RTD, TRAP, LINK, UNLK, CHK, CHK2, EXG, TAS, MOVEC, MOVES, CAS, CAS2,
        BITFIELD, PACK, MOVE16, BKPT, TRAPCC, CACHE, PFLUSH, PTEST, NOT040
    }

    /**
     * One mnemonic: its form, base operation word, an extra form-specific number, the sizes it
     * takes ("" for none) and the default size, and whether it is 68040-only.
     */
    record Def(F form, int opcode, int x, String sizes, char def, boolean only040) { }

    private static final Map<String, Def> DEFS = new HashMap<>();

    /** Condition codes in their encoding order (spec 10.2.1), with the aliases hs and lo. */
    static final Map<String, Integer> CC = Map.ofEntries(
            Map.entry("t", 0), Map.entry("f", 1), Map.entry("hi", 2), Map.entry("ls", 3),
            Map.entry("cc", 4), Map.entry("hs", 4), Map.entry("cs", 5), Map.entry("lo", 5),
            Map.entry("ne", 6), Map.entry("eq", 7), Map.entry("vc", 8), Map.entry("vs", 9),
            Map.entry("pl", 10), Map.entry("mi", 11), Map.entry("ge", 12), Map.entry("lt", 13),
            Map.entry("gt", 14), Map.entry("le", 15));

    private static void def(String name, F form, int opcode, int x, String sizes, char def) {
        DEFS.put(name, new Def(form, opcode, x, sizes, def, false));
    }

    private static void def040(String name, F form, int opcode, int x, String sizes, char def) {
        DEFS.put(name, new Def(form, opcode, x, sizes, def, true));
    }

    static {
        def("move", F.MOVE, 0, 0, "bwl", 'w');
        def("movea", F.MOVEA, 0, 0, "wl", 'w');
        def("moveq", F.MOVEQ, 0x7000, 0, "l", 'l');
        def("movep", F.MOVEP, 0x0108, 0, "wl", 'w');
        def("movem", F.MOVEM, 0x4880, 0, "wl", 'w');
        def("lea", F.LEA, 0x41C0, 0, "l", 'l');
        def("pea", F.PEA, 0x4840, 0, "l", 'l');
        // x: quick opcode for ADDQ/SUBQ, with the immediate opcode in the high half
        def("add", F.ADDSUB, 0xD000, 0x0600_5000, "bwl", 'w');
        def("sub", F.ADDSUB, 0x9000, 0x0400_5100, "bwl", 'w');
        def("adda", F.ADDSUBA, 0xD0C0, 0, "wl", 'w');
        def("suba", F.ADDSUBA, 0x90C0, 0, "wl", 'w');
        // x for IMMOP: the SR/CCR form exists (1) or not (0)
        def("addi", F.IMMOP, 0x0600, 0, "bwl", 'w');
        def("subi", F.IMMOP, 0x0400, 0, "bwl", 'w');
        def("cmpi", F.IMMOP, 0x0C00, 2, "bwl", 'w');
        def("andi", F.IMMOP, 0x0200, 1, "bwl", 'w');
        def("ori", F.IMMOP, 0x0000, 1, "bwl", 'w');
        def("eori", F.IMMOP, 0x0A00, 1, "bwl", 'w');
        def("addq", F.QUICK, 0x5000, 0, "bwl", 'w');
        def("subq", F.QUICK, 0x5100, 0, "bwl", 'w');
        def("addx", F.ADDSUBX, 0xD100, 0, "bwl", 'w');
        def("subx", F.ADDSUBX, 0x9100, 0, "bwl", 'w');
        def("cmp", F.CMP, 0xB000, 0, "bwl", 'w');
        def("cmpa", F.CMPA, 0xB0C0, 0, "wl", 'w');
        def("cmpm", F.CMPM, 0xB108, 0, "bwl", 'w');
        def("and", F.ANDOR, 0xC000, 0x0200, "bwl", 'w');
        def("or", F.ANDOR, 0x8000, 0x0000, "bwl", 'w');
        def("eor", F.EOR, 0xB100, 0x0A00, "bwl", 'w');
        def("clr", F.UNARY, 0x4200, 0, "bwl", 'w');
        def("neg", F.UNARY, 0x4400, 0, "bwl", 'w');
        def("negx", F.UNARY, 0x4000, 0, "bwl", 'w');
        def("not", F.UNARY, 0x4600, 0, "bwl", 'w');
        def("tst", F.TST, 0x4A00, 0, "bwl", 'w');
        def("ext", F.EXT, 0x4880, 0, "wl", 'w');
        def040("extb", F.EXTB, 0x49C0, 0, "l", 'l');
        def("swap", F.SWAP, 0x4840, 0, "w", 'w');
        // x: bit 0 signed, bit 1 divide
        def("mulu", F.MULDIV, 0xC0C0, 0, "wl", 'w');
        def("muls", F.MULDIV, 0xC1C0, 1, "wl", 'w');
        def("divu", F.MULDIV, 0x80C0, 2, "wl", 'w');
        def("divs", F.MULDIV, 0x81C0, 3, "wl", 'w');
        def040("divul", F.DIVL, 0x4C40, 0, "l", 'l');
        def040("divsl", F.DIVL, 0x4C40, 1, "l", 'l');
        def("abcd", F.BCD, 0xC100, 0, "b", 'b');
        def("sbcd", F.BCD, 0x8100, 0, "b", 'b');
        def("nbcd", F.NBCD, 0x4800, 0, "b", 'b');
        // x: type << 1 | left
        String[] shifts = {"asr", "asl", "lsr", "lsl", "roxr", "roxl", "ror", "rol"};
        for (int i = 0; i < shifts.length; i++)
            def(shifts[i], F.SHIFT, 0xE000, i, "bwl", 'w');
        def("btst", F.BIT, 0, 0, "bl", (char) 0);
        def("bchg", F.BIT, 0, 1, "bl", (char) 0);
        def("bclr", F.BIT, 0, 2, "bl", (char) 0);
        def("bset", F.BIT, 0, 3, "bl", (char) 0);
        def("bra", F.BCC, 0x6000, 0, "sbwl", (char) 0);
        def("bsr", F.BCC, 0x6100, 0, "sbwl", (char) 0);
        for (Map.Entry<String, Integer> c : CC.entrySet()) {
            int cc = c.getValue();
            if (cc >= 2)
                def("b" + c.getKey(), F.BCC, 0x6000 | cc << 8, 0, "sbwl", (char) 0);
            def("db" + c.getKey(), F.DBCC, 0x50C8 | cc << 8, 0, "w", 'w');
            def("s" + c.getKey(), F.SCC, 0x50C0 | cc << 8, 0, "b", 'b');
            def040("trap" + c.getKey(), F.TRAPCC, 0x50F8 | cc << 8, 0, "wl", (char) 0);
        }
        def("dbra", F.DBCC, 0x51C8, 0, "w", 'w');
        def("jmp", F.JUMP, 0x4EC0, 0, "", (char) 0);
        def("jsr", F.JUMP, 0x4E80, 0, "", (char) 0);
        def("nop", F.FIXED, 0x4E71, 0, "", (char) 0);
        def("rts", F.FIXED, 0x4E75, 0, "", (char) 0);
        def("rte", F.FIXED, 0x4E73, 0, "", (char) 0);
        def("rtr", F.FIXED, 0x4E77, 0, "", (char) 0);
        def("reset", F.FIXED, 0x4E70, 0, "", (char) 0);
        def("illegal", F.FIXED, 0x4AFC, 0, "", (char) 0);
        def("trapv", F.FIXED, 0x4E76, 0, "", (char) 0);
        def("stop", F.STOP, 0x4E72, 0, "", (char) 0);
        def040("rtd", F.RTD, 0x4E74, 0, "", (char) 0);
        def("trap", F.TRAP, 0x4E40, 0, "", (char) 0);
        def("link", F.LINK, 0x4E50, 0, "wl", 'w');
        def("unlk", F.UNLK, 0x4E58, 0, "", (char) 0);
        def("chk", F.CHK, 0x4180, 0, "wl", 'w');
        def040("chk2", F.CHK2, 0x00C0, 1, "bwl", 'w');
        def040("cmp2", F.CHK2, 0x00C0, 0, "bwl", 'w');
        def("exg", F.EXG, 0xC100, 0, "l", 'l');
        def("tas", F.TAS, 0x4AC0, 0, "b", 'b');
        def040("movec", F.MOVEC, 0x4E7A, 0, "l", 'l');
        def040("moves", F.MOVES, 0x0E00, 0, "bwl", 'w');
        def040("cas", F.CAS, 0x08C0, 0, "bwl", 'w');
        def040("cas2", F.CAS2, 0x08FC, 0, "wl", 'w');
        String[] bf = {"bftst", "bfextu", "bfchg", "bfexts", "bfclr", "bfffo", "bfset", "bfins"};
        for (int i = 0; i < bf.length; i++)
            def040(bf[i], F.BITFIELD, 0xE8C0 | i << 8, i, "", (char) 0);
        def040("pack", F.PACK, 0x8140, 0, "", (char) 0);
        def040("unpk", F.PACK, 0x8180, 0, "", (char) 0);
        def040("move16", F.MOVE16, 0xF600, 0, "", (char) 0);
        def040("bkpt", F.BKPT, 0x4848, 0, "", (char) 0);
        // x: scope (1 line, 2 page, 3 all)
        def040("cinvl", F.CACHE, 0xF400, 1, "", (char) 0);
        def040("cinvp", F.CACHE, 0xF400, 2, "", (char) 0);
        def040("cinva", F.CACHE, 0xF400, 3, "", (char) 0);
        def040("cpushl", F.CACHE, 0xF420, 1, "", (char) 0);
        def040("cpushp", F.CACHE, 0xF420, 2, "", (char) 0);
        def040("cpusha", F.CACHE, 0xF420, 3, "", (char) 0);
        def040("pflushn", F.PFLUSH, 0xF500, 1, "", (char) 0);
        def040("pflush", F.PFLUSH, 0xF508, 1, "", (char) 0);
        def040("pflushan", F.PFLUSH, 0xF510, 0, "", (char) 0);
        def040("pflusha", F.PFLUSH, 0xF518, 0, "", (char) 0);
        def040("ptestw", F.PTEST, 0xF548, 0, "", (char) 0);
        def040("ptestr", F.PTEST, 0xF568, 0, "", (char) 0);
        for (String s : new String[] {"callm", "rtm", "pmove", "pmovefd", "pload", "ploadr", "ploadw",
                "pvalid", "pflushr", "pflushs", "ptest", "psave", "prestore", "pdbcc", "pscc", "ptrapcc"})
            def(s, F.NOT040, 0, 0, "bwlsdxp", (char) 0);
    }

    static Def lookup(String base) {
        return DEFS.get(base);
    }

    // ---- shared helpers ----

    static int bytes(char c) {
        return switch (c) {
            case 'b' -> 1;
            case 'w' -> 2;
            default -> 4;
        };
    }

    /** The standard two-bit size field (bits 7..6): byte 00, word 01, long 10. */
    static int ss(int size) {
        return size == 1 ? 0 : size == 2 ? 1 : 2;
    }

    /** MOVE's size field (bits 13..12): byte 01, word 11, long 10. */
    static int moveSize(int size) {
        return size == 1 ? 1 : size == 2 ? 3 : 2;
    }

    static String sizesText(String sizes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sizes.length(); i++) {
            if (i > 0)
                sb.append(i == sizes.length() - 1 ? " or " : ", ");
            sb.append('.').append(sizes.charAt(i));
        }
        return sb.toString();
    }

    /** The operation size: the given suffix if allowed, else the default; errors name the instruction. */
    static char size(char given, String allowed, char def, String base, int column) {
        if (given == 0)
            return def;
        if (allowed.isEmpty())
            throw new AsmError(column, "'" + base + "." + given + "': " + base + " takes no size");
        if (allowed.indexOf(given) < 0)
            throw new AsmError(column, "'" + base + "." + given + "': size ." + given + " is not allowed ("
                    + base + " takes " + sizesText(allowed) + ")");
        return given;
    }

    static void count(List<Ea> ops, int n, String base, int column) {
        if (ops.size() != n)
            throw new AsmError(column, "'" + base + "' expects " + n + " operand" + (n == 1 ? "" : "s")
                    + ", got " + ops.size());
    }

    static int dreg(Ea ea, String base) {
        if (ea.kind != Kind2.DREG)
            throw new AsmError(ea.column, "'" + base + "' expects a data register here, got '" + ea.text + "'");
        return ea.reg;
    }

    static int areg(Ea ea, String base) {
        if (ea.kind != Kind2.AREG)
            throw new AsmError(ea.column, "'" + base + "' expects an address register here, got '" + ea.text + "'");
        return ea.reg;
    }

    /** A data or address register as 0..15. */
    static int greg(Ea ea, String base) {
        if (ea.kind == Kind2.DREG)
            return ea.reg;
        if (ea.kind == Kind2.AREG)
            return 8 | ea.reg;
        throw new AsmError(ea.column, "'" + base + "' expects a register here, got '" + ea.text + "'");
    }

    static Expr imm(Ea ea, String base) {
        if (ea.kind != Kind2.IMM)
            throw new AsmError(ea.column, "'" + base + "' expects an immediate '#value' here, got '" + ea.text + "'");
        return ea.expr;
    }

    /** A branch or jump target written as a plain expression. */
    static Expr target(Ea ea, String base) {
        if (ea.kind != Kind2.ABS || ea.absSize != 0)
            throw new AsmError(ea.column, "'" + base + "' expects a branch target, got '" + ea.text + "'");
        return ea.expr;
    }

    static void require040(Enc e, int column, String what) {
        if (!e.is040())
            throw e.needs040(column, what);
    }

    // ---- the encoder ----

    static void encode(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        if (d.only040())
            require040(e, column, "'" + base + "'");
        String full = given == 0 ? base : base + "." + given;
        switch (d.form()) {
            case MOVE -> move(e, d, base, given, ops, column);
            case MOVEA -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                e.w(0);
                int src = ea(e, ops.get(0), ALL, immFor(bytes(s)), base);
                e.op(moveSize(bytes(s)) << 12 | areg(ops.get(1), base) << 9 | 0x40 | src);
            }
            case MOVEQ -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Expr v = imm(ops.get(0), base);
                int r = dreg(ops.get(1), base);
                long x = signed32(e.value(v));
                e.checkRange(x, -128, 127, ops.get(0).column, "'moveq' immediate");
                e.w(0x7000 | r << 9 | (int) (x & 0xFF));
            }
            case MOVEP -> movep(e, d, base, given, ops, column);
            case MOVEM -> movem(e, d, base, given, ops, column);
            case LEA -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                e.w(0);
                int src = ea(e, ops.get(0), CONTROL, IMM_L, base);
                e.op(0x41C0 | areg(ops.get(1), base) << 9 | src);
            }
            case PEA -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(0x4840 | ea(e, ops.get(0), CONTROL, IMM_L, base));
            }
            case ADDSUB -> addSub(e, d, base, given, ops, column);
            case ADDSUBA -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                e.w(0);
                int src = ea(e, ops.get(0), ALL, immFor(bytes(s)), base);
                e.op(d.opcode() | (s == 'l' ? 0x100 : 0) | areg(ops.get(1), base) << 9 | src);
            }
            case IMMOP -> immOp(e, d.opcode(), d.x(), base, size(given, d.sizes(), d.def(), base, column),
                    given, ops, column);
            case QUICK -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                quick(e, d.opcode(), s, ops.get(0), ops.get(1), base);
            }
            case ADDSUBX, BCD -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                int rm;
                if (a.kind == Kind2.DREG && b.kind == Kind2.DREG)
                    rm = 0;
                else if (a.kind == Kind2.PREDEC && b.kind == Kind2.PREDEC)
                    rm = 8;
                else
                    throw new AsmError(a.column, "'" + base + "' takes Dy,Dx or -(Ay),-(Ax)");
                int size = d.form() == F.BCD ? 0 : ss(bytes(s)) << 6;
                e.w(d.opcode() | b.reg << 9 | size | rm | a.reg);
            }
            case CMP -> cmp(e, d, base, given, ops, column);
            case CMPA -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                e.w(0);
                int src = ea(e, ops.get(0), ALL, immFor(bytes(s)), base);
                e.op(0xB0C0 | (s == 'l' ? 0x100 : 0) | areg(ops.get(1), base) << 9 | src);
            }
            case CMPM -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                cmpm(e, s, ops.get(0), ops.get(1), base);
            }
            case ANDOR -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                if (a.kind == Kind2.IMM || a.kind == Kind2.FIMM) {
                    immOp(e, d.x(), 1, base.equals("and") ? "andi" : "ori", s, given, ops, column);
                } else if (b.kind == Kind2.DREG) {
                    e.w(0);
                    int src = ea(e, a, DATA, immFor(bytes(s)), base);
                    e.op(d.opcode() | b.reg << 9 | ss(bytes(s)) << 6 | src);
                } else if (a.kind == Kind2.DREG) {
                    e.w(0);
                    int dst = ea(e, b, MEM_ALT, IMM_L, base);
                    e.op(d.opcode() | 0x100 | a.reg << 9 | ss(bytes(s)) << 6 | dst);
                } else {
                    throw new AsmError(a.column, "'" + base + "' needs a data register or an immediate as one operand");
                }
            }
            case EOR -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                if (a.kind == Kind2.IMM || a.kind == Kind2.FIMM) {
                    immOp(e, 0x0A00, 1, "eori", s, given, ops, column);
                } else {
                    int r = dreg(a, base);
                    e.w(0);
                    e.op(0xB100 | r << 9 | ss(bytes(s)) << 6 | ea(e, b, DATA_ALT, IMM_L, base));
                }
            }
            case UNARY -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(d.opcode() | ss(bytes(s)) << 6 | ea(e, ops.get(0), DATA_ALT, IMM_L, base));
            }
            case TST -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                Ea a = ops.get(0);
                int cat = a.category();
                if (!e.is040() && (cat & (AN | PCD | PCX | IMM)) != 0)
                    throw e.needs040(a.column, "'tst' with " + M68kOperands.describe(cat));
                if (cat == AN && s == 'b')
                    throw new AsmError(a.column, "'tst.b' does not take an address register");
                e.w(0);
                e.op(0x4A00 | ss(bytes(s)) << 6 | ea(e, a, ALL, immFor(bytes(s)), base));
            }
            case EXT -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w((s == 'l' ? 0x48C0 : 0x4880) | dreg(ops.get(0), base));
            }
            case EXTB -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0x49C0 | dreg(ops.get(0), base));
            }
            case SWAP -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0x4840 | dreg(ops.get(0), base));
            }
            case MULDIV -> mulDiv(e, d, base, given, ops, column);
            case DIVL -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea dst = ops.get(1);
                int dr, dq;
                if (dst.kind == Kind2.PAIR) {
                    dr = dreg(dst.first, base);
                    dq = dreg(dst.second, base);
                } else {
                    dr = dq = dreg(dst, base);
                }
                e.w(0);
                e.w(dq << 12 | d.x() << 11 | dr);
                e.op(0x4C40 | ea(e, ops.get(0), DATA, IMM_L, base));
            }
            case NBCD -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(0x4800 | ea(e, ops.get(0), DATA_ALT, IMM_L, base));
            }
            case SHIFT -> shift(e, d, base, given, ops, column);
            case BIT -> bit(e, d, base, given, ops, column);
            case BCC -> branch(e, d, base, given, ops, column);
            case DBCC -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                int r = dreg(ops.get(0), base);
                Expr t = target(ops.get(1), base);
                e.w(d.opcode() | r);
                long v = branchDistance(e, t, ops.get(1).column);
                if (!e.sizing && !fitsSigned(v, 16))
                    throw new AsmError(ops.get(1).column, "branch target out of range (" + v
                            + " bytes, limit -32768..+32767)");
                e.w(v);
            }
            case SCC -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(d.opcode() | ea(e, ops.get(0), DATA_ALT, IMM_L, base));
            }
            case JUMP -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(d.opcode() | ea(e, ops.get(0), CONTROL, IMM_L, base));
            }
            case FIXED -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 0, base, column);
                e.w(d.opcode());
            }
            case STOP, RTD -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                long v = e.value(imm(ops.get(0), base));
                if (d.form() == F.STOP)
                    e.checkRange(v, 0, 0xFFFF, ops.get(0).column, "'stop' immediate");
                else
                    e.checkRange(v, -32768, 32767, ops.get(0).column, "'rtd' displacement");
                e.w(d.opcode());
                e.w(v);
            }
            case TRAP -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                long v = e.value(imm(ops.get(0), base));
                e.checkRange(v, 0, 15, ops.get(0).column, "trap vector");
                e.w(0x4E40 | (int) (v & 15));
            }
            case BKPT -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                long v = e.value(imm(ops.get(0), base));
                e.checkRange(v, 0, 7, ops.get(0).column, "breakpoint number");
                e.w(0x4848 | (int) (v & 7));
            }
            case LINK -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                int r = areg(ops.get(0), base);
                long v = e.value(imm(ops.get(1), base));
                if (s == 'l') {
                    require040(e, column, "'link.l'");
                    if (!e.sizing && !M68kOperands.fits32(v))
                        throw new AsmError(ops.get(1).column, "'link.l' displacement " + v + " does not fit in 32 bits");
                    e.w(0x4808 | r);
                    e.l(v);
                } else {
                    e.checkRange(v, -32768, 32767, ops.get(1).column, "'link' displacement");
                    e.w(0x4E50 | r);
                    e.w(v);
                }
            }
            case UNLK -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0x4E58 | areg(ops.get(0), base));
            }
            case CHK -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                if (s == 'l')
                    require040(e, column, "'chk.l'");
                int r = dreg(ops.get(1), base);
                e.w(0);
                int src = ea(e, ops.get(0), DATA, immFor(bytes(s)), base);
                e.op((s == 'l' ? 0x4100 : 0x4180) | r << 9 | src);
            }
            case CHK2 -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                int r = greg(ops.get(1), base);
                e.w(0);
                e.w(r << 12 | d.x() << 11);
                int src = ea(e, ops.get(0), CONTROL, IMM_L, base);
                e.op(0x00C0 | ss(bytes(s)) << 9 | src);
            }
            case EXG -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                int a = greg(ops.get(0), base), b = greg(ops.get(1), base);
                if (a < 8 && b < 8)
                    e.w(0xC140 | a << 9 | b);
                else if (a >= 8 && b >= 8)
                    e.w(0xC148 | (a & 7) << 9 | (b & 7));
                else if (a < 8)
                    e.w(0xC188 | a << 9 | (b & 7));
                else
                    e.w(0xC188 | b << 9 | (a & 7));
            }
            case TAS -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 1, base, column);
                e.w(0);
                e.op(0x4AC0 | ea(e, ops.get(0), DATA_ALT, IMM_L, base));
            }
            case MOVEC -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                int ctrl = control(a);
                if (ctrl >= 0) {
                    e.w(0x4E7A);
                    e.w(greg(b, base) << 12 | ctrl);
                } else {
                    ctrl = control(b);
                    if (ctrl < 0)
                        throw new AsmError(b.column, "'movec' needs a control register (sfc, dfc, cacr, tc, itt0, itt1, "
                                + "dtt0, dtt1, usp, vbr, msp, isp, mmusr, urp or srp)");
                    e.w(0x4E7B);
                    e.w(greg(a, base) << 12 | ctrl);
                }
            }
            case MOVES -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 2, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                boolean toMem = a.kind == Kind2.DREG || a.kind == Kind2.AREG;
                Ea reg = toMem ? a : b, mem = toMem ? b : a;
                int r = greg(reg, base);
                e.w(0);
                e.w(r << 12 | (toMem ? 0x0800 : 0));
                e.op(0x0E00 | ss(bytes(s)) << 6 | ea(e, mem, MEM_ALT, IMM_L, base));
            }
            case CAS -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 3, base, column);
                int dc = dreg(ops.get(0), base), du = dreg(ops.get(1), base);
                e.w(0);
                e.w(du << 6 | dc);
                e.op(0x08C0 | (ss(bytes(s)) + 1) << 9 | ea(e, ops.get(2), MEM_ALT, IMM_L, base));
            }
            case CAS2 -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                count(ops, 3, base, column);
                Ea c = pair(ops.get(0), base), u = pair(ops.get(1), base), r = pair(ops.get(2), base);
                e.w((s == 'l' ? 0x0EFC : 0x0CFC));
                e.w(cas2Reg(r.first, base) << 12 | dreg(u.first, base) << 6 | dreg(c.first, base));
                e.w(cas2Reg(r.second, base) << 12 | dreg(u.second, base) << 6 | dreg(c.second, base));
            }
            case BITFIELD -> bitfield(e, d, base, given, ops, column);
            case PACK -> {
                size(given, d.sizes(), d.def(), base, column);
                count(ops, 3, base, column);
                Ea a = ops.get(0), b = ops.get(1);
                int rm;
                if (a.kind == Kind2.DREG && b.kind == Kind2.DREG)
                    rm = 0;
                else if (a.kind == Kind2.PREDEC && b.kind == Kind2.PREDEC)
                    rm = 8;
                else
                    throw new AsmError(a.column, "'" + base + "' takes Dx,Dy,#adj or -(Ax),-(Ay),#adj");
                long v = e.value(imm(ops.get(2), base));
                e.checkRange(v, -32768, 65535, ops.get(2).column, "adjustment");
                e.w(d.opcode() | b.reg << 9 | rm | a.reg);
                e.w(v);
            }
            case MOVE16 -> move16(e, base, ops, column);
            case TRAPCC -> {
                char s = size(given, d.sizes(), d.def(), base, column);
                if (ops.isEmpty()) {
                    if (s != 0)
                        throw new AsmError(column, "'" + full + "' needs an immediate operand");
                    e.w(d.opcode() | 4);
                } else {
                    count(ops, 1, base, column);
                    long v = e.value(imm(ops.get(0), base));
                    if (s == 'l') {
                        if (!e.sizing && !M68kOperands.fits32(v))
                            throw new AsmError(ops.get(0).column, "immediate " + v + " does not fit in 32 bits");
                        e.w(d.opcode() | 3);
                        e.l(v);
                    } else {
                        e.checkRange(v, -32768, 65535, ops.get(0).column, "immediate");
                        e.w(d.opcode() | 2);
                        e.w(v);
                    }
                }
            }
            case CACHE -> {
                size(given, d.sizes(), d.def(), base, column);
                int scope = d.x();
                count(ops, scope == 3 ? 1 : 2, base, column);
                int cache = cacheName(ops.get(0), base);
                int reg = 0;
                if (scope != 3) {
                    Ea r = ops.get(1);
                    if (r.kind != Kind2.IND)
                        throw new AsmError(r.column, "'" + base + "' expects (An) as its second operand");
                    reg = r.reg;
                }
                e.w(d.opcode() | cache << 6 | scope << 3 | reg);
            }
            case PFLUSH -> {
                size(given, d.sizes(), d.def(), base, column);
                if (ops.size() > d.x())
                    throw new AsmError(column, "'" + base + "' with function code operands is not on the 68040 "
                            + "(68040 forms: pflush (An), pflushn (An), pflusha, pflushan)");
                count(ops, d.x(), base, column);
                int reg = 0;
                if (d.x() == 1) {
                    Ea r = ops.get(0);
                    if (r.kind != Kind2.IND)
                        throw new AsmError(r.column, "'" + base + "' expects (An)");
                    reg = r.reg;
                }
                e.w(d.opcode() | reg);
            }
            case PTEST -> {
                size(given, d.sizes(), d.def(), base, column);
                if (ops.size() > 1)
                    throw new AsmError(column, "'" + base + "' with function code operands is not on the 68040 "
                            + "(68040 form: " + base + " (An))");
                count(ops, 1, base, column);
                Ea r = ops.get(0);
                if (r.kind != Kind2.IND)
                    throw new AsmError(r.column, "'" + base + "' expects (An)");
                e.w(d.opcode() | r.reg);
            }
            case NOT040 -> throw new AsmError(column, "'" + base + "' is not on the 68040"
                    + (e.is040() ? "" : " (nor on the 68000)"));
        }
    }

    private static int control(Ea ea) {
        if (ea.kind == Kind2.CONTROL)
            return ea.reg;
        if (ea.kind == Kind2.SPECIAL && ea.reg == M68kRegisters.USP)
            return 0x800;
        return -1;
    }

    private static Ea pair(Ea ea, String base) {
        if (ea.kind != Kind2.PAIR)
            throw new AsmError(ea.column, "'" + base + "' expects a pair 'x:y' here, got '" + ea.text + "'");
        return ea;
    }

    /** CAS2 address operand (Rn): (An) or (Dn), as 0..15. */
    private static int cas2Reg(Ea ea, String base) {
        if (ea.kind == Kind2.IND)
            return 8 | ea.reg;
        if (ea.kind == Kind2.MEM && ea.base == M68kOperands.BASE_NONE && ea.index != null && ea.bd == null
                && ea.indirect == M68kOperands.INDIRECT_NONE && ea.scaleExpr == null && !ea.index.suppressed())
            return ea.index.reg();
        throw new AsmError(ea.column, "'" + base + "' expects (Rn) here, got '" + ea.text + "'");
    }

    private static int cacheName(Ea ea, String base) {
        String name = ea.kind == Kind2.ABS && ea.expr instanceof Expr.Sym s ? s.name().toLowerCase() : "";
        return switch (name) {
            case "nc" -> 0;
            case "dc" -> 1;
            case "ic" -> 2;
            case "bc" -> 3;
            default -> throw new AsmError(ea.column, "'" + base + "' expects a cache name (nc, dc, ic or bc), got '"
                    + ea.text + "'");
        };
    }

    // ---- MOVE family ----

    private static void move(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        count(ops, 2, base, column);
        Ea src = ops.get(0), dst = ops.get(1);
        if (dst.kind == Kind2.SPECIAL) {
            if (dst.reg == M68kRegisters.USP) {
                size(given, "l", 'l', "move usp", column);
                e.w(0x4E60 | areg(src, base));
                return;
            }
            size(given, "w", 'w', dst.reg == M68kRegisters.SR ? "move to sr" : "move to ccr", column);
            e.w(0);
            int s = ea(e, src, DATA, IMM_W, base);
            e.op((dst.reg == M68kRegisters.SR ? 0x46C0 : 0x44C0) | s);
            return;
        }
        if (src.kind == Kind2.SPECIAL) {
            if (src.reg == M68kRegisters.USP) {
                size(given, "l", 'l', "move usp", column);
                e.w(0x4E68 | areg(dst, base));
                return;
            }
            size(given, "w", 'w', src.reg == M68kRegisters.SR ? "move from sr" : "move from ccr", column);
            if (src.reg == M68kRegisters.CCR)
                require040(e, src.column, "'move' from ccr");
            e.w(0);
            int t = ea(e, dst, DATA_ALT, IMM_W, base);
            e.op((src.reg == M68kRegisters.SR ? 0x40C0 : 0x42C0) | t);
            return;
        }
        char s = size(given, d.sizes(), d.def(), base, column);
        int size = bytes(s);
        if (dst.kind == Kind2.AREG) {
            if (s == 'b')
                throw new AsmError(dst.column, "'move.b' cannot write an address register");
            e.w(0);
            int sf = ea(e, src, ALL, immFor(size), base);
            e.op(moveSize(size) << 12 | dst.reg << 9 | 0x40 | sf);
            return;
        }
        if (src.kind == Kind2.IMM && dst.kind == Kind2.DREG && s == 'l') {
            OptionalLong v = e.sizing ? e.known(src.expr) : OptionalLong.empty();
            int wanted = v.isPresent() && fitsSigned(signed32(v.getAsLong()), 8) ? 0 : 1;
            if (e.level(wanted, 1) == 0) {
                long x = signed32(e.value(src.expr));
                e.w(0x7000 | dst.reg << 9 | (int) (x & 0xFF));
                return;
            }
        }
        if (size == 1 && src.kind == Kind2.AREG)
            throw new AsmError(src.column, "'move.b' does not take an address register");
        e.w(0);
        int sf = ea(e, src, ALL, immFor(size), base);
        int df = ea(e, dst, DATA_ALT, IMM_L, base);
        e.op(moveSize(size) << 12 | (df & 7) << 9 | (df >> 3) << 6 | sf);
    }

    private static void movep(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        char s = size(given, d.sizes(), d.def(), base, column);
        count(ops, 2, base, column);
        Ea a = ops.get(0), b = ops.get(1);
        boolean toMem = a.kind == Kind2.DREG;
        Ea reg = toMem ? a : b, mem = toMem ? b : a;
        int r = dreg(reg, base);
        int an;
        long disp = 0;
        if (mem.kind == Kind2.IND) {
            an = mem.reg;
        } else if (mem.kind == Kind2.MEM && mem.category() == M68kOperands.D16) {
            an = mem.base;
            disp = e.value(mem.bd);
            e.checkRange(disp, -32768, 32767, mem.column, "displacement");
        } else {
            throw new AsmError(mem.column, "'movep' takes (d16,An), got '" + mem.text + "'");
        }
        int opmode = (toMem ? 6 : 4) + (s == 'l' ? 1 : 0);
        e.w(0x0008 | r << 9 | opmode << 6 | an);
        e.w(disp);
    }

    private static boolean listLike(Ea ea) {
        return ea.kind == Kind2.REGLIST || ea.kind == Kind2.DREG || ea.kind == Kind2.AREG || ea.kind == Kind2.IMM;
    }

    private static void movem(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        char s = size(given, d.sizes(), d.def(), base, column);
        count(ops, 2, base, column);
        Ea a = ops.get(0), b = ops.get(1);
        boolean toMem;
        if (listLike(a) && listLike(b) && b.kind != Kind2.IMM)
            throw new AsmError(a.column, "'movem' needs a register list and a memory operand");
        if (listLike(a) && !(b.kind == Kind2.DREG || b.kind == Kind2.AREG || b.kind == Kind2.REGLIST))
            toMem = true;
        else if (listLike(b) && b.kind != Kind2.IMM)
            toMem = false;
        else
            throw new AsmError(a.column, "'movem' needs a register list and a memory operand");
        Ea list = toMem ? a : b, mem = toMem ? b : a;
        e.w(0);
        int mask;
        boolean numeric = list.kind == Kind2.IMM;
        if (numeric) {
            long v = e.value(list.expr);
            e.checkRange(v, 0, 0xFFFF, list.column, "register mask");
            mask = (int) v & 0xFFFF;
        } else {
            mask = list.kind == Kind2.REGLIST ? list.mask : list.kind == Kind2.DREG ? 1 << list.reg : 1 << (8 + list.reg);
        }
        if (toMem && mem.kind == Kind2.PREDEC && !numeric)
            mask = Integer.reverse(mask) >>> 16;
        e.w(mask);
        int f = ea(e, mem, toMem ? CONTROL_ALT | PRE : CONTROL | POST, IMM_L, base);
        e.op(0x4880 | (toMem ? 0 : 0x400) | (s == 'l' ? 0x40 : 0) | f);
    }

    // ---- arithmetic ----

    private static void addSub(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        char s = size(given, d.sizes(), d.def(), base, column);
        count(ops, 2, base, column);
        int size = bytes(s);
        Ea src = ops.get(0), dst = ops.get(1);
        int quickOp = d.x() & 0xFFFF, immOp = d.x() >>> 16;
        boolean isAdd = base.equals("add");
        if (src.kind == Kind2.IMM && (dst.category() & ALTERABLE) != 0 && !(dst.kind == Kind2.AREG && size == 1)) {
            OptionalLong v = e.sizing ? e.known(src.expr) : OptionalLong.empty();
            int wanted = v.isPresent() && v.getAsLong() >= 1 && v.getAsLong() <= 8 ? 0 : 1;
            if (e.level(wanted, 1) == 0) {
                quick(e, quickOp, s, src, dst, base);
                return;
            }
        }
        if (dst.kind == Kind2.AREG) {
            if (size == 1)
                throw new AsmError(dst.column, "'" + base + ".b' cannot write an address register");
            e.w(0);
            int sf = ea(e, src, ALL, immFor(size), base);
            e.op((isAdd ? 0xD0C0 : 0x90C0) | (size == 4 ? 0x100 : 0) | dst.reg << 9 | sf);
            return;
        }
        if (src.kind == Kind2.IMM || src.kind == Kind2.FIMM) {
            immOp(e, immOp, 0, isAdd ? "addi" : "subi", s, given, ops, column);
            return;
        }
        if (dst.kind == Kind2.DREG) {
            if (size == 1 && src.kind == Kind2.AREG)
                throw new AsmError(src.column, "'" + base + ".b' does not take an address register");
            e.w(0);
            int sf = ea(e, src, ALL, immFor(size), base);
            e.op(d.opcode() | dst.reg << 9 | ss(size) << 6 | sf);
            return;
        }
        if (src.kind == Kind2.DREG) {
            e.w(0);
            int df = ea(e, dst, MEM_ALT, IMM_L, base);
            e.op(d.opcode() | 0x100 | src.reg << 9 | ss(size) << 6 | df);
            return;
        }
        throw new AsmError(src.column, "'" + base + "' needs a data register, an address register destination "
                + "or an immediate source");
    }

    private static void quick(Enc e, int opcode, char s, Ea src, Ea dst, String base) {
        long v = e.value(imm(src, base));
        e.checkRange(v, 1, 8, src.column, "quick immediate");
        if (dst.kind == Kind2.AREG && s == 'b')
            throw new AsmError(dst.column, "'" + base + ".b' cannot write an address register");
        e.w(0);
        int df = ea(e, dst, ALTERABLE, IMM_L, base);
        e.op(opcode | (int) (v & 7) << 9 | ss(bytes(s)) << 6 | df);
    }

    /**
     * ADDI, SUBI, CMPI, ANDI, ORI, EORI. {@code special}: 1 if the CCR and SR forms exist, 2 for
     * CMPI (PC-relative destinations on the 68040).
     */
    private static void immOp(Enc e, int opcode, int special, String base, char s, char given,
                              List<Ea> ops, int column) {
        count(ops, 2, base, column);
        Ea src = ops.get(0), dst = ops.get(1);
        if (dst.kind == Kind2.SPECIAL && special == 1 && dst.reg != M68kRegisters.USP) {
            boolean sr = dst.reg == M68kRegisters.SR;
            if (given != 0 && given != (sr ? 'w' : 'b'))
                throw new AsmError(column, "'" + base + "." + given + "' to " + (sr ? "sr takes .w" : "ccr takes .b"));
            imm(src, base);
            e.w(opcode | (sr ? 0x7C : 0x3C));
            immediate(e, src, sr ? IMM_W : IMM_B);
            return;
        }
        if (src.kind != Kind2.IMM && src.kind != Kind2.FIMM)
            imm(src, base);
        int allowed = DATA_ALT;
        if (special == 2) {
            int cat = dst.category();
            if ((cat & (PCD | PCX)) != 0 && !e.is040())
                throw e.needs040(dst.column, "'cmpi' with a PC-relative operand");
            allowed |= PCD | PCX;
        }
        e.w(0);
        immediate(e, src, immFor(bytes(s)));
        e.op(opcode | ss(bytes(s)) << 6 | ea(e, dst, allowed, IMM_L, base));
    }

    private static void cmp(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        char s = size(given, d.sizes(), d.def(), base, column);
        count(ops, 2, base, column);
        int size = bytes(s);
        Ea src = ops.get(0), dst = ops.get(1);
        if (dst.kind == Kind2.AREG) {
            if (size == 1)
                throw new AsmError(dst.column, "'cmp.b' does not take an address register");
            e.w(0);
            int sf = ea(e, src, ALL, immFor(size), base);
            e.op(0xB0C0 | (size == 4 ? 0x100 : 0) | dst.reg << 9 | sf);
            return;
        }
        if (src.kind == Kind2.IMM || src.kind == Kind2.FIMM) {
            immOp(e, 0x0C00, 2, "cmpi", s, given, ops, column);
            return;
        }
        if (src.kind == Kind2.POSTINC && dst.kind == Kind2.POSTINC) {
            cmpm(e, s, src, dst, base);
            return;
        }
        int r = dreg(dst, base);
        if (size == 1 && src.kind == Kind2.AREG)
            throw new AsmError(src.column, "'cmp.b' does not take an address register");
        e.w(0);
        int sf = ea(e, src, ALL, immFor(size), base);
        e.op(0xB000 | r << 9 | ss(size) << 6 | sf);
    }

    private static void cmpm(Enc e, char s, Ea a, Ea b, String base) {
        if (a.kind != Kind2.POSTINC || b.kind != Kind2.POSTINC)
            throw new AsmError(a.column, "'cmpm' takes (Ay)+,(Ax)+");
        e.w(0xB108 | b.reg << 9 | ss(bytes(s)) << 6 | a.reg);
    }

    private static void mulDiv(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        char s = size(given, d.sizes(), d.def(), base, column);
        count(ops, 2, base, column);
        Ea src = ops.get(0), dst = ops.get(1);
        boolean signed = (d.x() & 1) != 0, divide = (d.x() & 2) != 0;
        if (s == 'w') {
            int r = dreg(dst, base);
            e.w(0);
            int sf = ea(e, src, DATA, IMM_W, base);
            e.op(d.opcode() | r << 9 | sf);
            return;
        }
        require040(e, column, "'" + base + ".l'");
        int ext;
        if (dst.kind == Kind2.PAIR) {
            int hi = dreg(dst.first, base), lo = dreg(dst.second, base);
            ext = lo << 12 | (signed ? 0x800 : 0) | 0x400 | hi;
        } else {
            int r = dreg(dst, base);
            ext = r << 12 | (signed ? 0x800 : 0) | (divide ? r : 0);
        }
        e.w(0);
        e.w(ext);
        e.op((divide ? 0x4C40 : 0x4C00) | ea(e, src, DATA, IMM_L, base));
    }

    private static void shift(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        int type = d.x() >> 1, left = d.x() & 1;
        if (ops.size() == 1 && ops.get(0).kind != Kind2.DREG) {
            size(given, "w", 'w', base, column);
            e.w(0);
            e.op(0xE0C0 | type << 9 | left << 8 | ea(e, ops.get(0), MEM_ALT, IMM_L, base));
            return;
        }
        char s = size(given, d.sizes(), d.def(), base, column);
        if (ops.size() == 1) {
            e.w(0xE000 | 1 << 9 | left << 8 | ss(bytes(s)) << 6 | type << 3 | ops.get(0).reg);
            return;
        }
        count(ops, 2, base, column);
        Ea cnt = ops.get(0), dst = ops.get(1);
        if (dst.kind != Kind2.DREG)
            throw new AsmError(dst.column, "'" + base + "' with a count shifts a data register; "
                    + "a memory operand takes no count (shifts by 1)");
        if (cnt.kind == Kind2.DREG) {
            e.w(0xE020 | cnt.reg << 9 | left << 8 | ss(bytes(s)) << 6 | type << 3 | dst.reg);
            return;
        }
        long v = e.value(imm(cnt, base));
        e.checkRange(v, 1, 8, cnt.column, "shift count");
        e.w(0xE000 | (int) (v & 7) << 9 | left << 8 | ss(bytes(s)) << 6 | type << 3 | dst.reg);
    }

    private static void bit(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        count(ops, 2, base, column);
        Ea src = ops.get(0), dst = ops.get(1);
        boolean reg = dst.kind == Kind2.DREG;
        if (given != 0) {
            if (given != 'b' && given != 'l')
                size(given, "bl", 'b', base, column);
            if (reg && given != 'l')
                throw new AsmError(column, "'" + base + "." + given + "' on a data register is .l");
            if (!reg && given != 'b')
                throw new AsmError(column, "'" + base + "." + given + "' on memory is .b");
        }
        boolean test = d.x() == 0;
        if (src.kind == Kind2.DREG) {
            e.w(0);
            e.op(0x0100 | src.reg << 9 | d.x() << 6 | ea(e, dst, test ? DATA : DATA_ALT, IMM_B, base));
            return;
        }
        long v = e.value(imm(src, base));
        e.checkRange(v, 0, reg ? 31 : 7, src.column, "bit number");
        e.w(0);
        e.w(v & 0xFF);
        e.op(0x0800 | d.x() << 6 | ea(e, dst, test ? DATA & ~IMM : DATA_ALT, IMM_L, base));
    }

    // ---- branches ----

    /** Displacement from the word after the operation word to the target (an address). */
    static long branchDistance(Enc e, Expr t, int column) {
        OptionalLong d = e.distance(t, e.insnAddr + 2, true);
        if (e.sizing)
            return d.orElse(0);
        long v = d.orElseThrow();
        if ((v & 1) != 0)
            throw new AsmError(column, "branch target is at an odd address (displacement " + v + ")");
        return v;
    }

    private static void branch(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        count(ops, 1, base, column);
        Expr t = target(ops.get(0), base);
        int oc = ops.get(0).column;
        int lvl;
        if (given != 0) {
            lvl = switch (given) {
                case 's', 'b' -> 0;
                case 'w' -> 1;
                case 'l' -> 2;
                default -> {
                    size(given, d.sizes(), d.def(), base, column);
                    yield 1;
                }
            };
            if (lvl == 2)
                require040(e, column, "'" + base + ".l'");
        } else {
            OptionalLong dist = e.sizing ? e.distance(t, e.insnAddr + 2, true) : OptionalLong.empty();
            int wanted = 0;
            if (dist.isPresent()) {
                long v = dist.getAsLong();
                if (v == 0 || v == -1 || !fitsSigned(v, 8))
                    wanted = fitsSigned(v, 16) ? 1 : 2;
            }
            lvl = e.level(wanted, e.is040() ? 2 : 1);
        }
        long v = branchDistance(e, t, oc);
        String name = given == 0 ? base : base + "." + given;
        switch (lvl) {
            case 0 -> {
                if (!e.sizing) {
                    if (v == 0)
                        throw new AsmError(oc, "'" + name + "' cannot branch to the next instruction "
                                + "(displacement 0 means a word branch); use " + base + ".w");
                    if (!fitsSigned(v, 8))
                        throw new AsmError(oc, "branch target out of range (" + v + " bytes, limit -128..+127 for .s)");
                }
                e.w(d.opcode() | (int) (v & 0xFF));
            }
            case 1 -> {
                if (!e.sizing && !fitsSigned(v, 16))
                    throw new AsmError(oc, "branch target out of range (" + v + " bytes, limit -32768..+32767 for .w"
                            + (given == 0 && !e.is040() ? "; .l needs a 68040" : "") + ")");
                e.w(d.opcode());
                e.w(v);
            }
            default -> {
                e.w(d.opcode() | 0xFF);
                e.l(v);
            }
        }
    }

    // ---- 68040 forms ----

    private static void bitfield(Enc e, Def d, String base, char given, List<Ea> ops, int column) {
        size(given, d.sizes(), d.def(), base, column);
        int op = d.x();
        boolean ext = op == 1 || op == 3 || op == 5;
        boolean ins = op == 7;
        count(ops, ext || ins ? 2 : 1, base, column);
        Ea field = ins ? ops.get(1) : ops.get(0);
        int reg = ext ? dreg(ops.get(1), base) : ins ? dreg(ops.get(0), base) : 0;
        if (field.braces == null || field.braces.size() != 2)
            throw new AsmError(field.column, "'" + base + "' needs a bit field '{offset:width}'");
        M68kOperands.BraceItem off = field.braces.get(0), wid = field.braces.get(1);
        int w = reg << 12;
        if (off.dreg() >= 0) {
            w |= 0x0800 | off.dreg() << 6;
        } else {
            long v = e.value(off.expr());
            e.checkRange(v, 0, 31, off.column(), "bit field offset");
            w |= (int) (v & 31) << 6;
        }
        if (wid.dreg() >= 0) {
            w |= 0x0020 | wid.dreg();
        } else {
            long v = e.value(wid.expr());
            e.checkRange(v, 1, 32, wid.column(), "bit field width");
            w |= (int) (v & 31);
        }
        boolean readOnly = op == 0 || ext;
        e.w(0);
        e.w(w);
        e.op(d.opcode() | ea(e, field, DN | (readOnly ? CONTROL : CONTROL_ALT), IMM_L, base));
    }

    private static void move16(Enc e, String base, List<Ea> ops, int column) {
        count(ops, 2, base, column);
        Ea a = ops.get(0), b = ops.get(1);
        if (a.kind == Kind2.POSTINC && b.kind == Kind2.POSTINC) {
            e.w(0xF620 | a.reg);
            e.w(0x8000 | b.reg << 12);
            return;
        }
        int opmode;
        Ea reg, abs;
        if (b.kind == Kind2.ABS && (a.kind == Kind2.POSTINC || a.kind == Kind2.IND)) {
            opmode = a.kind == Kind2.POSTINC ? 0 : 2;
            reg = a;
            abs = b;
        } else if (a.kind == Kind2.ABS && (b.kind == Kind2.POSTINC || b.kind == Kind2.IND)) {
            opmode = b.kind == Kind2.POSTINC ? 1 : 3;
            reg = b;
            abs = a;
        } else {
            throw new AsmError(a.column, "'move16' takes (Ax)+,(Ay)+ or (Ay)/(Ay)+ with an absolute address");
        }
        if (abs.absSize == 1)
            throw new AsmError(abs.column, "'move16' takes a long absolute address");
        long v = e.value(abs.expr);
        if (!e.sizing && !M68kOperands.fits32(v))
            throw new AsmError(abs.column, String.format("absolute address 0x%X does not fit in 32 bits", v));
        e.w(0xF600 | opmode << 3 | reg.reg);
        e.l(v);
    }
}
