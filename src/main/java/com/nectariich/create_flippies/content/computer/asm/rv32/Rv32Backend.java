package com.nectariich.create_flippies.content.computer.asm.rv32;

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

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The RV32I backend: RV32I, M, Zicsr and Zifencei in GNU as syntax, with the pseudo-instructions
 * of spec 5.2. Every instruction size is fixed in the first pass (no relaxation): {@code li} is
 * 4 bytes when its value is known by then and fits one instruction, else 8; {@code la},
 * {@code call}, {@code tail}, the global load and store forms and the 8-byte sign and zero
 * extensions are always 8.
 *
 * Encodings follow the RISC-V unprivileged spec, chapter "RV32I Base Integer Instruction Set"
 * (R, I, S, B, U and J formats) and the "RV32/64G Instruction Set Listings" opcode map.
 */
public final class Rv32Backend implements IsaBackend {

    public static final Rv32Backend INSTANCE = new Rv32Backend();

    // Major opcodes (unprivileged spec, "RISC-V base opcode map").
    static final int OP_LUI = 0b0110111;
    static final int OP_AUIPC = 0b0010111;
    static final int OP_JAL = 0b1101111;
    static final int OP_JALR = 0b1100111;
    static final int OP_BRANCH = 0b1100011;
    static final int OP_LOAD = 0b0000011;
    static final int OP_STORE = 0b0100011;
    static final int OP_IMM = 0b0010011;
    static final int OP_OP = 0b0110011;
    static final int OP_MISC_MEM = 0b0001111;
    static final int OP_SYSTEM = 0b1110011;

    static final int RA = 1;
    static final int T1 = 6;

    private static final LexerSyntax SYNTAX = new LexerSyntax(List.of("#", "//"), true, ';', (char) 0, false);
    private static final byte[] NOP = {0x13, 0, 0, 0};

    /** How an instruction's operands are read and how it is encoded. */
    enum Form {
        R, I, SHIFT, LOAD, STORE, BRANCH, U, JAL, JALR, CSR, CSRI, FIXED, FENCE,
        LI, LA, MV, EXT, SGT, BZ, BSWAP, J, JR, CALL, TAIL, CSRR, CSRW, CSRWI, RDCTR
    }

    /** One mnemonic: form, funct3, funct7 (or a fixed word, CSR number, base mnemonic data). */
    record Op(Form form, int opcode, int f3, int f7, int extra) { }

    private static final Map<String, Op> OPS = new HashMap<>();

    private static void op(String name, Form form, int opcode, int f3, int f7, int extra) {
        OPS.put(name, new Op(form, opcode, f3, f7, extra));
    }

    static {
        // OP: funct3 / funct7 (spec 5.2).
        String[] rNames = {"add", "sll", "slt", "sltu", "xor", "srl", "or", "and"};
        int[] rF3 = {0, 1, 2, 3, 4, 5, 6, 7};
        for (int i = 0; i < rNames.length; i++)
            op(rNames[i], Form.R, OP_OP, rF3[i], 0, 0);
        op("sub", Form.R, OP_OP, 0, 0x20, 0);
        op("sra", Form.R, OP_OP, 5, 0x20, 0);
        String[] mNames = {"mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu"};
        for (int i = 0; i < mNames.length; i++)
            op(mNames[i], Form.R, OP_OP, i, 1, 0);
        // OP-IMM.
        op("addi", Form.I, OP_IMM, 0, 0, 0);
        op("slti", Form.I, OP_IMM, 2, 0, 0);
        op("sltiu", Form.I, OP_IMM, 3, 0, 0);
        op("xori", Form.I, OP_IMM, 4, 0, 0);
        op("ori", Form.I, OP_IMM, 6, 0, 0);
        op("andi", Form.I, OP_IMM, 7, 0, 0);
        op("slli", Form.SHIFT, OP_IMM, 1, 0, 0);
        op("srli", Form.SHIFT, OP_IMM, 5, 0, 0);
        op("srai", Form.SHIFT, OP_IMM, 5, 0x20, 0);
        // LOAD and STORE.
        op("lb", Form.LOAD, OP_LOAD, 0, 0, 0);
        op("lh", Form.LOAD, OP_LOAD, 1, 0, 0);
        op("lw", Form.LOAD, OP_LOAD, 2, 0, 0);
        op("lbu", Form.LOAD, OP_LOAD, 4, 0, 0);
        op("lhu", Form.LOAD, OP_LOAD, 5, 0, 0);
        op("sb", Form.STORE, OP_STORE, 0, 0, 0);
        op("sh", Form.STORE, OP_STORE, 1, 0, 0);
        op("sw", Form.STORE, OP_STORE, 2, 0, 0);
        // BRANCH.
        op("beq", Form.BRANCH, OP_BRANCH, 0, 0, 0);
        op("bne", Form.BRANCH, OP_BRANCH, 1, 0, 0);
        op("blt", Form.BRANCH, OP_BRANCH, 4, 0, 0);
        op("bge", Form.BRANCH, OP_BRANCH, 5, 0, 0);
        op("bltu", Form.BRANCH, OP_BRANCH, 6, 0, 0);
        op("bgeu", Form.BRANCH, OP_BRANCH, 7, 0, 0);
        // U, J, JALR.
        op("lui", Form.U, OP_LUI, 0, 0, 0);
        op("auipc", Form.U, OP_AUIPC, 0, 0, 0);
        op("jal", Form.JAL, OP_JAL, 0, 0, 0);
        op("jalr", Form.JALR, OP_JALR, 0, 0, 0);
        // Zicsr.
        op("csrrw", Form.CSR, OP_SYSTEM, 1, 0, 0);
        op("csrrs", Form.CSR, OP_SYSTEM, 2, 0, 0);
        op("csrrc", Form.CSR, OP_SYSTEM, 3, 0, 0);
        op("csrrwi", Form.CSRI, OP_SYSTEM, 5, 0, 0);
        op("csrrsi", Form.CSRI, OP_SYSTEM, 6, 0, 0);
        op("csrrci", Form.CSRI, OP_SYSTEM, 7, 0, 0);
        // Fixed words (spec 5.2).
        op("ecall", Form.FIXED, 0, 0, 0, 0x0000_0073);
        op("ebreak", Form.FIXED, 0, 0, 0, 0x0010_0073);
        op("mret", Form.FIXED, 0, 0, 0, 0x3020_0073);
        op("wfi", Form.FIXED, 0, 0, 0, 0x1050_0073);
        op("fence.i", Form.FIXED, 0, 0, 0, 0x0000_100F);
        op("fence.tso", Form.FIXED, 0, 0, 0, 0x8330_000F);
        op("pause", Form.FIXED, 0, 0, 0, 0x0100_000F);
        op("nop", Form.FIXED, 0, 0, 0, 0x0000_0013);
        op("unimp", Form.FIXED, 0, 0, 0, 0xC000_1073);
        op("ret", Form.FIXED, 0, 0, 0, 0x0000_8067);
        op("fence", Form.FENCE, OP_MISC_MEM, 0, 0, 0);
        // Pseudo-instructions.
        op("li", Form.LI, 0, 0, 0, 0);
        op("la", Form.LA, 0, 0, 0, 0);
        op("lla", Form.LA, 0, 0, 0, 0);
        for (String s : new String[] {"mv", "not", "neg", "seqz", "snez", "sltz", "sgtz", "zext.b"})
            op(s, Form.MV, 0, 0, 0, 0);
        for (String s : new String[] {"sext.b", "sext.h", "zext.h"})
            op(s, Form.EXT, 0, 0, 0, 0);
        op("sgt", Form.SGT, OP_OP, 2, 0, 0);
        op("sgtu", Form.SGT, OP_OP, 3, 0, 0);
        op("beqz", Form.BZ, OP_BRANCH, 0, 0, 0);
        op("bnez", Form.BZ, OP_BRANCH, 1, 0, 0);
        op("blez", Form.BZ, OP_BRANCH, 5, 0, 1);   // bge x0, rs
        op("bgez", Form.BZ, OP_BRANCH, 5, 0, 0);   // bge rs, x0
        op("bltz", Form.BZ, OP_BRANCH, 4, 0, 0);   // blt rs, x0
        op("bgtz", Form.BZ, OP_BRANCH, 4, 0, 1);   // blt x0, rs
        op("bgt", Form.BSWAP, OP_BRANCH, 4, 0, 0);
        op("ble", Form.BSWAP, OP_BRANCH, 5, 0, 0);
        op("bgtu", Form.BSWAP, OP_BRANCH, 6, 0, 0);
        op("bleu", Form.BSWAP, OP_BRANCH, 7, 0, 0);
        op("j", Form.J, OP_JAL, 0, 0, 0);
        op("jr", Form.JR, OP_JALR, 0, 0, 0);
        op("call", Form.CALL, 0, 0, 0, 0);
        op("tail", Form.TAIL, 0, 0, 0, 0);
        op("csrr", Form.CSRR, OP_SYSTEM, 2, 0, 0);
        op("csrw", Form.CSRW, OP_SYSTEM, 1, 0, 0);
        op("csrs", Form.CSRW, OP_SYSTEM, 2, 0, 0);
        op("csrc", Form.CSRW, OP_SYSTEM, 3, 0, 0);
        op("csrwi", Form.CSRWI, OP_SYSTEM, 5, 0, 0);
        op("csrsi", Form.CSRWI, OP_SYSTEM, 6, 0, 0);
        op("csrci", Form.CSRWI, OP_SYSTEM, 7, 0, 0);
        op("rdcycle", Form.RDCTR, 0, 0, 0, 0xC00);
        op("rdtime", Form.RDCTR, 0, 0, 0, 0xC01);
        op("rdinstret", Form.RDCTR, 0, 0, 0, 0xC02);
        op("rdcycleh", Form.RDCTR, 0, 0, 0, 0xC80);
        op("rdtimeh", Form.RDCTR, 0, 0, 0, 0xC81);
        op("rdinstreth", Form.RDCTR, 0, 0, 0, 0xC82);
    }

    private Rv32Backend() {
    }

    @Override
    public LexerSyntax syntax() {
        return SYNTAX;
    }

    @Override
    public ByteOrder byteOrder() {
        return ByteOrder.LITTLE_ENDIAN;
    }

    @Override
    public int instructionAlignment() {
        return 4;
    }

    @Override
    public byte[] nopPadding() {
        return NOP.clone();
    }

    @Override
    public boolean alignIsPowerOfTwo() {
        return true;
    }

    /** Whether a mnemonic is known (instructions and pseudo-instructions). */
    public static boolean isMnemonic(String name) {
        return OPS.containsKey(name);
    }

    // ---- pass 1 ----

    @Override
    public int size(Statement st, AsmContext ctx) {
        Op op = op(st);
        int n = st.operands().size();
        switch (op.form()) {
            case R, I, SHIFT, BRANCH, CSR, CSRI, SGT, BSWAP -> count(st, n == 3, "3");
            case U, LI, LA, MV, EXT, BZ, CSRR, CSRW, CSRWI -> count(st, n == 2, "2");
            case LOAD -> count(st, n == 2, "2");
            case STORE -> count(st, n == 2 || n == 3, "2 or 3");
            case JAL, CALL -> count(st, n == 1 || n == 2, "1 or 2");
            case JALR -> count(st, n >= 1 && n <= 3, "1 to 3");
            case FIXED -> count(st, n == 0, "0");
            case FENCE -> count(st, n == 0 || n == 2, "0 or 2");
            case J, JR, TAIL, RDCTR -> count(st, n == 1, "1");
        }
        return switch (op.form()) {
            case LA, CALL, TAIL, EXT -> 8;
            case LOAD -> isMemory(st.operand(1)) ? 4 : 8;
            case STORE -> n == 3 ? 8 : 4;
            case LI -> liSize(st, ctx);
            default -> 4;
        };
    }

    private static Op op(Statement st) {
        Op op = OPS.get(st.mnemonic());
        if (op == null)
            throw new AsmError(st.column(), "unknown instruction '" + st.mnemonic() + "'");
        return op;
    }

    private static void count(Statement st, boolean ok, String expected) {
        if (!ok) {
            int n = st.operands().size();
            throw new AsmError(st.column(), "'" + st.mnemonic() + "' expects " + expected + " operand"
                    + (expected.equals("1") ? "" : "s") + ", got " + n);
        }
    }

    private static int liSize(Statement st, AsmContext ctx) {
        OptionalLong v = ctx.known(st.operand(1).expr());
        if (v.isEmpty())
            return 8;
        int x = li32(v.getAsLong(), st.operand(1).column());
        return fits12(x) || lo12(x) == 0 ? 4 : 8;
    }

    /** A {@code li} value normalised to signed 32 bits, as GNU as does on RV32. */
    private static int li32(long v, int column) {
        if (v < Integer.MIN_VALUE || v > 0xFFFF_FFFFL)
            throw new AsmError(column, "value " + v + " does not fit in 32 bits");
        return (int) v;
    }

    // ---- pass 2 ----

    @Override
    public void encode(Statement st, AsmContext ctx, Emitter out) {
        Op op = op(st);
        List<Operand> o = st.operands();
        switch (op.form()) {
            case R -> out.u32(r(op, reg(o.get(0)), reg(o.get(1)), reg(o.get(2))));
            case I -> out.u32(i(op.opcode(), op.f3(), reg(o.get(0)), reg(o.get(1)), imm12(ctx, o.get(2))));
            case SHIFT -> {
                int shamt = (int) range(ctx.evalAbs(o.get(2).expr()), 0, 31, o.get(2).column(), "shift amount");
                out.u32(i(op.opcode(), op.f3(), reg(o.get(0)), reg(o.get(1)), (op.f7() << 5) | shamt));
            }
            case LOAD -> load(st, op, ctx, out);
            case STORE -> store(st, op, ctx, out);
            case BRANCH -> out.u32(b(op.f3(), reg(o.get(0)), reg(o.get(1)), branchOffset(ctx, o.get(2))));
            case U -> {
                int rd = reg(o.get(0));
                long v = ctx.evalAbs(o.get(1).expr());
                if (v < 0 || v > 0xFFFFF)
                    throw new AsmError(o.get(1).column(), "immediate " + v + " does not fit in 20 bits (0..1048575)");
                if (op.opcode() == OP_AUIPC && o.get(1).expr() instanceof Expr.Reloc rel
                        && rel.name().equals("pcrel_hi"))
                    ctx.recordPcrelHi(ctx.dot(), ctx.eval(rel.arg()).value());
                out.u32(u(op.opcode(), rd, (int) v));
            }
            case JAL -> {
                int rd = o.size() == 1 ? RA : reg(o.get(0));
                out.u32(j(rd, jumpOffset(ctx, o.get(o.size() - 1))));
            }
            case JALR -> jalr(st, ctx, out, false);
            case JR -> jalr(st, ctx, out, true);
            case CSR -> {
                int rd = reg(o.get(0));
                int csr = csr(ctx, o.get(1));
                Operand src = o.get(2);
                if (isRegister(src))
                    out.u32(i(OP_SYSTEM, op.f3(), rd, reg(src), csr));
                else
                    out.u32(i(OP_SYSTEM, op.f3() | 4, rd, uimm5(ctx, src), csr));
            }
            case CSRI -> out.u32(i(OP_SYSTEM, op.f3(), reg(o.get(0)), uimm5(ctx, o.get(2)), csr(ctx, o.get(1))));
            case FIXED -> out.u32(op.extra());
            case FENCE -> {
                int pred = 0xF, succ = 0xF;
                if (o.size() == 2) {
                    pred = fenceSet(o.get(0));
                    succ = fenceSet(o.get(1));
                }
                out.u32((pred << 24) | (succ << 20) | OP_MISC_MEM);
            }
            case LI -> li(st, ctx, out);
            case LA -> {
                int rd = reg(o.get(0));
                int delta = pcDelta(ctx, o.get(1));
                out.u32(u(OP_AUIPC, rd, hi20(delta)));
                out.u32(i(OP_IMM, 0, rd, rd, lo12(delta)));
            }
            case MV -> mv(st.mnemonic(), reg(o.get(0)), reg(o.get(1)), out);
            case EXT -> {
                int rd = reg(o.get(0)), rs = reg(o.get(1));
                int sh = st.mnemonic().endsWith(".b") ? 24 : 16;
                int f7 = st.mnemonic().startsWith("sext") ? 0x20 : 0;   // srai or srli
                out.u32(i(OP_IMM, 1, rd, rs, sh));
                out.u32(i(OP_IMM, 5, rd, rd, (f7 << 5) | sh));
            }
            case SGT -> out.u32(r(op, reg(o.get(0)), reg(o.get(2)), reg(o.get(1))));
            case BZ -> {
                int rs = reg(o.get(0));
                int off = branchOffset(ctx, o.get(1));
                out.u32(op.extra() == 1 ? b(op.f3(), 0, rs, off) : b(op.f3(), rs, 0, off));
            }
            case BSWAP -> out.u32(b(op.f3(), reg(o.get(1)), reg(o.get(0)), branchOffset(ctx, o.get(2))));
            case J -> out.u32(j(0, jumpOffset(ctx, o.get(0))));
            case CALL, TAIL -> {
                int link;
                int scratch;
                Operand target;
                if (op.form() == Form.TAIL) {
                    link = 0;
                    scratch = T1;
                    target = o.get(0);
                } else {
                    link = o.size() == 2 ? reg(o.get(0)) : RA;
                    scratch = link;
                    target = o.get(o.size() - 1);
                }
                int delta = pcDelta(ctx, target);
                out.u32(u(OP_AUIPC, scratch, hi20(delta)));
                out.u32(i(OP_JALR, 0, link, scratch, lo12(delta)));
            }
            case CSRR -> out.u32(i(OP_SYSTEM, 2, reg(o.get(0)), 0, csr(ctx, o.get(1))));
            case CSRW -> {
                int csr = csr(ctx, o.get(0));
                Operand src = o.get(1);
                if (isRegister(src))
                    out.u32(i(OP_SYSTEM, op.f3(), 0, reg(src), csr));
                else
                    out.u32(i(OP_SYSTEM, op.f3() | 4, 0, uimm5(ctx, src), csr));
            }
            case CSRWI -> out.u32(i(OP_SYSTEM, op.f3(), 0, uimm5(ctx, o.get(1)), csr(ctx, o.get(0))));
            case RDCTR -> out.u32(i(OP_SYSTEM, 2, reg(o.get(0)), 0, op.extra()));
        }
    }

    private static void mv(String name, int rd, int rs, Emitter out) {
        out.u32(switch (name) {
            case "mv" -> i(OP_IMM, 0, rd, rs, 0);
            case "not" -> i(OP_IMM, 4, rd, rs, -1);
            case "neg" -> r(0x20, 0, 0, rs, rd);
            case "seqz" -> i(OP_IMM, 3, rd, rs, 1);
            case "snez" -> r(0, 3, 0, rs, rd);
            case "sltz" -> r(0, 2, rs, 0, rd);
            case "sgtz" -> r(0, 2, 0, rs, rd);
            case "zext.b" -> i(OP_IMM, 7, rd, rs, 255);
            default -> throw new IllegalStateException(name);
        });
    }

    private void li(Statement st, AsmContext ctx, Emitter out) {
        List<Operand> o = st.operands();
        int rd = reg(o.get(0));
        int x = li32(ctx.evalAbs(o.get(1).expr()), o.get(1).column());
        if (st.size() == 4) {
            if (fits12(x))
                out.u32(i(OP_IMM, 0, rd, 0, x));
            else if (lo12(x) == 0)
                out.u32(u(OP_LUI, rd, hi20(x)));
            else
                throw new AsmError(o.get(1).column(), "'li' value changed between passes");
        } else {
            out.u32(u(OP_LUI, rd, hi20(x)));
            out.u32(i(OP_IMM, 0, rd, rd, lo12(x)));
        }
    }

    private void load(Statement st, Op op, AsmContext ctx, Emitter out) {
        List<Operand> o = st.operands();
        int rd = reg(o.get(0));
        if (st.size() == 4) {
            Mem m = memory(ctx, o.get(1));
            out.u32(i(OP_LOAD, op.f3(), rd, m.base, m.offset));
        } else {
            int delta = pcDelta(ctx, o.get(1));
            out.u32(u(OP_AUIPC, rd, hi20(delta)));
            out.u32(i(OP_LOAD, op.f3(), rd, rd, lo12(delta)));
        }
    }

    private void store(Statement st, Op op, AsmContext ctx, Emitter out) {
        List<Operand> o = st.operands();
        int rs2 = reg(o.get(0));
        if (o.size() == 2) {
            Mem m = memory(ctx, o.get(1));
            out.u32(s(op.f3(), m.base, rs2, m.offset));
        } else {
            int rt = reg(o.get(2));
            int delta = pcDelta(ctx, o.get(1));
            out.u32(u(OP_AUIPC, rt, hi20(delta)));
            out.u32(s(op.f3(), rt, rs2, lo12(delta)));
        }
    }

    /** {@code jalr} in all its forms, and {@code jr} (rd = x0). */
    private void jalr(Statement st, AsmContext ctx, Emitter out, boolean jr) {
        List<Operand> o = st.operands();
        int rd, base, offset;
        if (o.size() == 1) {
            rd = jr ? 0 : RA;
            Operand t = o.get(0);
            if (isMemory(t)) {
                Mem m = memory(ctx, t);
                base = m.base;
                offset = m.offset;
            } else {
                base = reg(t);
                offset = 0;
            }
        } else if (o.size() == 2) {
            rd = reg(o.get(0));
            Operand t = o.get(1);
            if (isMemory(t)) {
                Mem m = memory(ctx, t);
                base = m.base;
                offset = m.offset;
            } else {
                base = reg(t);
                offset = 0;
            }
        } else {
            rd = reg(o.get(0));
            base = reg(o.get(1));
            offset = imm12(ctx, o.get(2));
        }
        out.u32(i(OP_JALR, 0, rd, base, offset));
    }

    // ---- operands ----

    private record Mem(int base, int offset) { }

    /** {@code imm(reg)}, {@code (reg)} or {@code %lo(sym)(reg)}: the operand ends in a register in parentheses. */
    static boolean isMemory(Operand op) {
        List<AsmToken> t = op.tokens();
        int n = t.size();
        return n >= 3 && t.get(n - 1).isPunct(")") && t.get(n - 3).isPunct("(")
                && t.get(n - 2).kind() == AsmToken.Kind.IDENT && Rv32Registers.number(t.get(n - 2).text()) >= 0;
    }

    private Mem memory(AsmContext ctx, Operand op) {
        if (!isMemory(op))
            throw new AsmError(op.column(), "expected a memory operand like 8(sp), got '" + op.text() + "'");
        List<AsmToken> t = op.tokens();
        int n = t.size();
        int base = Rv32Registers.number(t.get(n - 2).text());
        int offset = 0;
        if (n > 3) {
            long v = ctx.evalAbs(op.expr(0, n - 3));
            offset = (int) check12(v, t.get(0).column());
        }
        return new Mem(base, offset);
    }

    static boolean isRegister(Operand op) {
        String id = op.identifier();
        return id != null && Rv32Registers.number(id) >= 0;
    }

    private static int reg(Operand op) {
        String id = op.identifier();
        if (id == null) {
            if (op.isEmpty())
                throw new AsmError(op.column(), "missing operand");
            throw new AsmError(op.column(), "expected a register, got '" + op.text() + "'");
        }
        int n = Rv32Registers.number(id);
        if (n < 0)
            throw new AsmError(op.column(), "unknown register '" + id + "'");
        return n;
    }

    private static int imm12(AsmContext ctx, Operand op) {
        return (int) check12(ctx.evalAbs(op.expr()), op.column());
    }

    private static long check12(long v, int column) {
        if (v < -2048 || v > 2047)
            throw new AsmError(column, "immediate " + v + " does not fit in 12 signed bits");
        return v;
    }

    private static long range(long v, long lo, long hi, int column, String what) {
        if (v < lo || v > hi)
            throw new AsmError(column, what + " " + v + " out of range (" + lo + ".." + hi + ")");
        return v;
    }

    private static int uimm5(AsmContext ctx, Operand op) {
        return (int) range(ctx.evalAbs(op.expr()), 0, 31, op.column(), "immediate");
    }

    private static int csr(AsmContext ctx, Operand op) {
        String id = op.identifier();
        if (id != null) {
            int n = Rv32Csrs.number(id);
            if (n >= 0)
                return n;
            if (!ctx.isDefined(id))
                throw new AsmError(op.column(), "unknown CSR '" + id + "'");
        }
        return (int) range(ctx.evalAbs(op.expr()), 0, 4095, op.column(), "CSR number");
    }

    /** A fence set: letters from {@code iorw} (i = 8, o = 4, r = 2, w = 1), or the number 0 (empty set). */
    private static int fenceSet(Operand op) {
        String id = op.identifier();
        if (id == null) {
            List<AsmToken> t = op.tokens();
            if (t.size() == 1 && t.get(0).kind() == AsmToken.Kind.INT && t.get(0).value() == 0)
                return 0;
            throw new AsmError(op.column(), "bad fence operand '" + op.text() + "'");
        }
        int bits = 0;
        for (char c : id.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            int bit = switch (c) {
                case 'i' -> 8;
                case 'o' -> 4;
                case 'r' -> 2;
                case 'w' -> 1;
                default -> 0;
            };
            if (bit == 0 || (bits & bit) != 0)
                throw new AsmError(op.column(), "bad fence operand '" + id + "'");
            bits |= bit;
        }
        return bits;
    }

    /**
     * A branch target: a plain number is an offset, an address is a target. LLVM's assembler reads
     * a plain number the same way; GNU as is believed to read it as an absolute address (not
     * checked, no toolchain installed). Spec 5.2 keeps the offset reading.
     */
    private static long targetOffset(AsmContext ctx, Operand op) {
        Val v = ctx.eval(op.expr());
        return v.isAbs() ? v.value() : v.value() - ctx.dot();
    }

    private static int branchOffset(AsmContext ctx, Operand op) {
        long off = targetOffset(ctx, op);
        if (off < -4096 || off > 4094)
            throw new AsmError(op.column(), "branch target out of range (" + off + " bytes, limit +-4096)");
        checkEven(ctx, op, off, "branch");
        return (int) off;
    }

    private static int jumpOffset(AsmContext ctx, Operand op) {
        long off = targetOffset(ctx, op);
        if (off < -(1 << 20) || off > (1 << 20) - 2)
            throw new AsmError(op.column(), "jump target out of range (" + off + " bytes, limit +-1048576)");
        checkEven(ctx, op, off, "jump");
        return (int) off;
    }

    private static void checkEven(AsmContext ctx, Operand op, long off, String what) {
        if ((off & 1) != 0)
            throw new AsmError(op.column(), what + " target offset " + off + " is odd");
        if ((off & 3) != 0)
            ctx.warning(op.column(), what + " target offset " + off
                    + " is not a multiple of 4 (the CPU traps on it)");
    }

    /** {@code sym - .} for the AUIPC pairs, wrapped to 32 bits (every address is reachable). */
    private static int pcDelta(AsmContext ctx, Operand op) {
        return (int) (ctx.eval(op.expr()).value() - ctx.dot());
    }

    // ---- relocation operators ----

    @Override
    public OptionalLong relocationOperator(String name, Expr arg, AsmContext ctx) {
        switch (name) {
            case "hi" -> {
                return OptionalLong.of(hi20((int) ctx.evalAbs(arg)));
            }
            case "lo" -> {
                return OptionalLong.of(lo12((int) ctx.evalAbs(arg)));
            }
            case "pcrel_hi" -> {
                if (ctx.firstPass())
                    throw AsmContext.notKnownYet();
                return OptionalLong.of(hi20((int) (ctx.eval(arg).value() - ctx.dot())));
            }
            case "pcrel_lo" -> {
                if (ctx.firstPass())
                    throw AsmContext.notKnownYet();
                long label = ctx.eval(arg).value();
                OptionalLong target = ctx.pcrelHi(label);
                if (target.isEmpty())
                    throw new AsmError(arg.column(), "%pcrel_lo needs a label on an auipc with %pcrel_hi");
                return OptionalLong.of(lo12((int) (target.getAsLong() - label)));
            }
            default -> {
                return OptionalLong.empty();
            }
        }
    }

    @Override
    public boolean directive(Statement st, AsmContext ctx) {
        if (!st.mnemonic().equals(".option"))
            return false;
        for (Operand op : st.operands())
            if ("rvc".equals(op.identifier()))
                throw new AsmError(op.column(), "C extension not supported");
        return true;
    }

    // ---- encoders (unprivileged spec, "Base Instruction Formats") ----

    static int hi20(int x) {
        return ((x + 0x800) >>> 12) & 0xFFFFF;
    }

    static int lo12(int x) {
        return (x << 20) >> 20;
    }

    static boolean fits12(int x) {
        return x >= -2048 && x <= 2047;
    }

    private static int r(Op op, int rd, int rs1, int rs2) {
        return (op.f7() << 25) | (rs2 << 20) | (rs1 << 15) | (op.f3() << 12) | (rd << 7) | op.opcode();
    }

    /** R-type on OP with explicit fields (pseudo-instructions). */
    private static int r(int f7, int f3, int rs1, int rs2, int rd) {
        return (f7 << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | OP_OP;
    }

    static int i(int opcode, int f3, int rd, int rs1, int imm) {
        return ((imm & 0xFFF) << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | opcode;
    }

    static int s(int f3, int rs1, int rs2, int imm) {
        return (((imm >> 5) & 0x7F) << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | ((imm & 0x1F) << 7) | OP_STORE;
    }

    static int b(int f3, int rs1, int rs2, int imm) {
        return (((imm >> 12) & 1) << 31) | (((imm >> 5) & 0x3F) << 25) | (rs2 << 20) | (rs1 << 15)
                | (f3 << 12) | (((imm >> 1) & 0xF) << 8) | (((imm >> 11) & 1) << 7) | OP_BRANCH;
    }

    static int u(int opcode, int rd, int imm20) {
        return (imm20 << 12) | (rd << 7) | opcode;
    }

    static int j(int rd, int imm) {
        return (((imm >> 20) & 1) << 31) | (((imm >> 1) & 0x3FF) << 21) | (((imm >> 11) & 1) << 20)
                | (((imm >> 12) & 0xFF) << 12) | (rd << 7) | OP_JAL;
    }
}
