package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kCpu;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Assembler and CPU cross-check (spec 10.12.4), as {@code EncoderDecoderCrossCheckTest} does for
 * RV32I. The 68k assembler and core were written independently, so assembling one instruction
 * with random registers, sizes and effective addressing modes, executing it on {@code M68kCpu}
 * from a random state, and comparing D0..D7, A0..A7, the CCR, the PC and a window of RAM with the
 * small reference model below finds any field-layout or EA-encoding mismatch between the two.
 */
class M68kCrossCheckTest {

    private static final int CODE = 0x2000;
    private static final int DATA = 0x4000;
    private static final int DATA_SPAN = 0x1000;
    private static final int ROUNDS = 40;

    private static final int[] MASK = {0xFF, 0xFFFF, -1};
    private static final int[] MSB = {0x80, 0x8000, 0x8000_0000};
    private static final int[] BYTES = {1, 2, 4};
    private static final String[] SZ = {"b", "w", "l"};
    private static final String[] CC = {"t", "f", "hi", "ls", "cc", "cs", "ne", "eq", "vc", "vs", "pl", "mi", "ge",
            "lt", "gt", "le"};
    private static final int X = 16, N = 8, Z = 4, V = 2, C = 1;
    private static final int[] SPECIAL = {0, 1, -1, 0x7F, 0x80, 0xFF, 0x7FFF, 0x8000, 0xFFFF, Integer.MAX_VALUE,
            Integer.MIN_VALUE, 0x8000_0001, 0x1234_5678, 0x9ABC_DEF0};

    private final Random rnd = new Random(0x68_040L);
    private CpuModel model;
    private Machine machine;
    private M68kCpu cpu;

    /** The machine state before and, in the model, after one instruction. */
    private static final class State {
        final int[] d = new int[8];
        final int[] a = new int[8];
        int ccr;
        int pc;
        final byte[] mem = new byte[DATA_SPAN];

        State copy() {
            State s = new State();
            System.arraycopy(d, 0, s.d, 0, 8);
            System.arraycopy(a, 0, s.a, 0, 8);
            s.ccr = ccr;
            s.pc = pc;
            System.arraycopy(mem, 0, s.mem, 0, DATA_SPAN);
            return s;
        }

        int read(int addr, int sz) {
            int off = addr - DATA;
            if (off < 0 || off + BYTES[sz] > DATA_SPAN)
                throw new AssertionError(String.format("model access at 0x%x outside the window", addr));
            int v = 0;
            for (int i = 0; i < BYTES[sz]; i++)
                v = v << 8 | (mem[off + i] & 0xFF);
            return v;
        }

        void write(int addr, int sz, int v) {
            int off = addr - DATA;
            if (off < 0 || off + BYTES[sz] > DATA_SPAN)
                throw new AssertionError(String.format("model access at 0x%x outside the window", addr));
            for (int i = BYTES[sz] - 1; i >= 0; i--, v >>>= 8)
                mem[off + i] = (byte) v;
        }

        void setD(int n, int sz, int v) {
            d[n] = (d[n] & ~MASK[sz]) | (v & MASK[sz]);
        }
    }

    /**
     * An effective address: 0 Dn, 1 An, 2 (An), 3 (An)+, 4 -(An), 5 d16(An), 6 d8(An,Xn), 7 abs.W,
     * 8 abs.L, 9 immediate.
     */
    private record Ea(int mode, int reg, int disp, int xreg, boolean xlong, int abs, int imm, String text) {
    }

    private State st;

    // ---- random operands ----

    private int value() {
        return rnd.nextInt(3) == 0 ? SPECIAL[rnd.nextInt(SPECIAL.length)] : rnd.nextInt();
    }

    private int dreg() {
        return rnd.nextInt(8);
    }

    /** A0..A6: A7 stays the stack. */
    private int areg() {
        return rnd.nextInt(7);
    }

    private void randomState() {
        st = new State();
        for (int i = 0; i < 8; i++)
            st.d[i] = value();
        for (int i = 0; i < 7; i++)
            st.a[i] = value();
        st.a[7] = RAM_BYTES - 0x100;
        st.ccr = rnd.nextInt(32);
        st.pc = CODE;
        rnd.nextBytes(st.mem);
    }

    /** A random EA of one of {@code modes}, with the registers it uses pointed into the data window. */
    private Ea ea(int sz, int... modes) {
        int mode = modes[rnd.nextInt(modes.length)];
        int r = mode == 0 ? dreg() : mode <= 6 ? areg() : 0;
        if (mode >= 2 && mode <= 6)
            st.a[r] = DATA + 0x200 + 4 * rnd.nextInt(0x200);
        return switch (mode) {
            case 0 -> new Ea(0, r, 0, 0, false, 0, 0, "d" + r);
            case 1 -> new Ea(1, r, 0, 0, false, 0, 0, "a" + r);
            case 2 -> new Ea(2, r, 0, 0, false, 0, 0, "(a" + r + ")");
            case 3 -> new Ea(3, r, 0, 0, false, 0, 0, "(a" + r + ")+");
            case 4 -> new Ea(4, r, 0, 0, false, 0, 0, "-(a" + r + ")");
            case 5 -> {
                int disp = 2 * (rnd.nextInt(129) - 64);
                yield new Ea(5, r, disp, 0, false, 0, 0, disp + "(a" + r + ")");
            }
            case 6 -> {
                int disp = 2 * (rnd.nextInt(65) - 32);
                int x = dreg();
                boolean xl = rnd.nextBoolean();
                st.d[x] = 2 * (rnd.nextInt(65) - 32);
                yield new Ea(6, r, disp, x, xl, 0, 0, disp + "(a" + r + ",d" + x + (xl ? ".l" : ".w") + ")");
            }
            case 7 -> {
                int abs = DATA + 4 * rnd.nextInt(0x3F0);
                yield new Ea(7, 0, 0, 0, false, abs, 0, String.format("($%x).w", abs));
            }
            case 8 -> {
                int abs = DATA + 4 * rnd.nextInt(0x3F0);
                yield new Ea(8, 0, 0, 0, false, abs, 0, String.format("($%x).l", abs));
            }
            default -> {
                int imm = value() & MASK[sz];
                yield new Ea(9, 0, 0, 0, false, 0, imm, String.format("#$%x", imm));
            }
        };
    }

    private static final int[] DATA_MODES = {0, 2, 3, 4, 5, 6, 7, 8, 9};
    private static final int[] ALL_MODES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    private static final int[] ALTERABLE = {0, 2, 3, 4, 5, 6, 7, 8};
    private static final int[] MEMORY_ALTERABLE = {2, 3, 4, 5, 6, 7, 8};
    private static final int[] CONTROL = {2, 5, 6, 7, 8};

    // ---- the reference model ----

    /** The address of a memory EA, with its (An)+ or -(An) side effect done once. */
    private static int address(State s, Ea e, int sz) {
        return switch (e.mode) {
            case 2 -> s.a[e.reg];
            case 3 -> {
                int v = s.a[e.reg];
                s.a[e.reg] += BYTES[sz];
                yield v;
            }
            case 4 -> s.a[e.reg] -= BYTES[sz];
            case 5 -> s.a[e.reg] + e.disp;
            case 6 -> s.a[e.reg] + e.disp + (e.xlong ? s.d[e.xreg] : (short) s.d[e.xreg]);
            case 7, 8 -> e.abs;
            default -> throw new AssertionError("not a memory EA: " + e.text);
        };
    }

    private static int read(State s, Ea e, int sz) {
        return switch (e.mode) {
            case 0 -> s.d[e.reg] & MASK[sz];
            case 1 -> s.a[e.reg] & MASK[sz];
            case 9 -> e.imm & MASK[sz];
            default -> s.read(address(s, e, sz), sz);
        };
    }

    /** Writes a register EA, or memory at an address already computed. */
    private static void write(State s, Ea e, int sz, int addr, int v) {
        if (e.mode == 0)
            s.setD(e.reg, sz, v);
        else
            s.write(addr, sz, v);
    }

    private static int nz(int r, int sz) {
        r &= MASK[sz];
        return (r == 0 ? Z : 0) | ((r & MSB[sz]) != 0 ? N : 0);
    }

    /** CCR after a logic result: X kept, V = C = 0. */
    private static int logic(int ccr, int r, int sz) {
        return (ccr & X) | nz(r, sz);
    }

    private static int addFlags(int s, int d, int sz) {
        int r = (s + d) & MASK[sz];
        long carry = (Integer.toUnsignedLong(s & MASK[sz]) + Integer.toUnsignedLong(d & MASK[sz])) >>> (8 * BYTES[sz]);
        boolean v = ((s ^ r) & (d ^ r) & MSB[sz]) != 0;
        return nz(r, sz) | (v ? V : 0) | (carry != 0 ? C | X : 0);
    }

    /** Flags of d - s, X = C. */
    private static int subFlags(int s, int d, int sz) {
        int r = (d - s) & MASK[sz];
        boolean borrow = Integer.compareUnsigned(s & MASK[sz], d & MASK[sz]) > 0;
        boolean v = ((s ^ d) & (r ^ d) & MSB[sz]) != 0;
        return nz(r, sz) | (v ? V : 0) | (borrow ? C | X : 0);
    }

    private static boolean cond(int cc, int ccr) {
        boolean c = (ccr & C) != 0, v = (ccr & V) != 0, z = (ccr & Z) != 0, n = (ccr & N) != 0;
        return switch (cc) {
            case 0 -> true;
            case 1 -> false;
            case 2 -> !c && !z;
            case 3 -> c || z;
            case 4 -> !c;
            case 5 -> c;
            case 6 -> !z;
            case 7 -> z;
            case 8 -> !v;
            case 9 -> v;
            case 10 -> !n;
            case 11 -> n;
            case 12 -> n == v;
            case 13 -> n != v;
            case 14 -> n == v && !z;
            default -> z || n != v;
        };
    }

    /** Shift or rotate {@code v} by {@code count}; returns the result, CCR in {@code out[0]}. */
    private static int shift(String op, int v, int count, int sz, int ccr, int[] out) {
        int bits = 8 * BYTES[sz];
        long mask = Integer.toUnsignedLong(MASK[sz]);
        long x = Integer.toUnsignedLong(v) & mask;
        boolean xf = (ccr & X) != 0;
        boolean c = false;
        boolean vf = false;
        long msb = 1L << (bits - 1);
        for (int i = 0; i < count; i++) {
            switch (op) {
                case "lsl", "asl" -> {
                    c = (x & msb) != 0;
                    long nx = (x << 1) & mask;
                    if (op.equals("asl") && ((nx ^ x) & msb) != 0)
                        vf = true;
                    x = nx;
                }
                case "lsr" -> {
                    c = (x & 1) != 0;
                    x >>>= 1;
                }
                case "asr" -> {
                    c = (x & 1) != 0;
                    x = (x >>> 1) | (x & msb);
                }
                case "rol" -> {
                    c = (x & msb) != 0;
                    x = ((x << 1) | (c ? 1 : 0)) & mask;
                }
                case "ror" -> {
                    c = (x & 1) != 0;
                    x = (x >>> 1) | (c ? msb : 0);
                }
                case "roxl" -> {
                    c = (x & msb) != 0;
                    x = ((x << 1) | (xf ? 1 : 0)) & mask;
                    xf = c;
                }
                default -> {   // roxr
                    c = (x & 1) != 0;
                    x = (x >>> 1) | (xf ? msb : 0);
                    xf = c;
                }
            }
            if (!op.startsWith("ro") || op.startsWith("rox"))
                xf = c;
        }
        int r = (int) x;
        int f = nz(r, sz) | (vf ? V : 0);
        if (count == 0) {
            f |= ccr & X;
            if (op.startsWith("rox") && (ccr & X) != 0)
                f |= C;
        } else {
            f |= c ? C : 0;
            if (op.startsWith("ro") && !op.startsWith("rox"))
                f |= ccr & X;
            else
                f |= xf ? X : 0;
        }
        out[0] = f;
        return r;
    }

    // ---- running one instruction ----

    private void check(String instruction, State expected) {
        AsmResult r = M68kAssembler.assemble(PRELUDE + " " + instruction + "\n", AsmOptions.at(CODE), target(model));
        if (!r.ok())
            fail(model.displayName() + ": '" + instruction + "' does not assemble: " + r.diagnostics());
        byte[] code = r.image().flatten(CODE, 24);
        machine.loadRam(CODE, code);
        System.arraycopy(st.mem, 0, machine.board().ram(), DATA, DATA_SPAN);
        for (int i = 0; i < 8; i++) {
            cpu.setD(i, st.d[i]);
            cpu.setA(i, st.a[i]);
        }
        cpu.setSrHost(0x2700 | st.ccr);
        cpu.setPc(CODE);
        long retired = cpu.instructionsRetired();
        Cpu.Exit exit = cpu.run(machine.clock().now() + 1, Long.MAX_VALUE);
        String where = model.displayName() + " '" + instruction + "'";
        if (exit != Cpu.Exit.REACHED || cpu.instructionsRetired() != retired + 1)
            fail(where + ": did not retire one instruction (" + exit + ", pc 0x" + Integer.toHexString(cpu.pc()) + ")");
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (cpu.d(i) != expected.d[i])
                diff.append(String.format(" d%d=%08x want %08x;", i, cpu.d(i), expected.d[i]));
            if (cpu.a(i) != expected.a[i])
                diff.append(String.format(" a%d=%08x want %08x;", i, cpu.a(i), expected.a[i]));
        }
        if ((cpu.sr() & 0x1F) != expected.ccr)
            diff.append(String.format(" ccr=%02x want %02x (before %02x);", cpu.sr() & 0x1F, expected.ccr, st.ccr));
        if (cpu.pc() != expected.pc)
            diff.append(String.format(" pc=%x want %x;", cpu.pc(), expected.pc));
        byte[] mem = Arrays.copyOfRange(machine.board().ram(), DATA, DATA + DATA_SPAN);
        if (!Arrays.equals(mem, expected.mem))
            diff.append(" memory differs at 0x").append(Integer.toHexString(DATA + Arrays.mismatch(mem, expected.mem))).append(';');
        if (diff.length() > 0)
            fail(where + ":" + diff);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void everyFormAgreesWithTheReferenceModel(CpuModel model) {
        this.model = model;
        machine = boot(model, "_start: bra.s _start\n");
        cpu = (M68kCpu) machine.cpu();
        cpu.run(machine.clock().now() + 200, Long.MAX_VALUE);   // reads the reset vectors
        int checked = 0;
        for (int round = 0; round < ROUNDS; round++) {
            checked += moves() + arithmetic() + quickAndUnary() + shifts() + multiplyDivide() + bits() + flow();
            if (model == CpuModel.CPU_68040)
                checked += m68040();
        }
        assertTrue(checked > 1000, checked + " instructions checked");
    }

    /** The PC after an instruction of {@code bytes} bytes at CODE. */
    private static int after(int bytes) {
        return CODE + bytes;
    }

    /** Instruction length from the assembler, for the expected PC. */
    private int length(String instruction) {
        AsmResult r = M68kAssembler.assemble(" " + instruction + "\nend_of_it:\n", AsmOptions.at(CODE), target(model));
        if (!r.ok())
            fail(model.displayName() + ": '" + instruction + "' does not assemble: " + r.diagnostics());
        return (int) r.image().symbol("end_of_it").getAsLong() - CODE;
    }

    private void run(String instruction, State m) {
        m.pc = after(length(instruction));
        check(instruction, m);
    }

    // ---- families ----

    private int moves() {
        int n = 0;
        for (int sz = 0; sz < 3; sz++) {
            randomState();
            Ea src = ea(sz, sz == 0 ? DATA_MODES : ALL_MODES);
            Ea dst = ea(sz, ALTERABLE);
            State m = st.copy();
            int v = read(m, src, sz);
            int addr = dst.mode == 0 ? 0 : address(m, dst, sz);
            write(m, dst, sz, addr, v);
            m.ccr = logic(m.ccr, v, sz);
            run("move." + SZ[sz] + " " + src.text + "," + dst.text, m);
            n++;
        }
        for (int sz = 1; sz < 3; sz++) {
            randomState();
            Ea src = ea(sz, ALL_MODES);
            int an = areg();
            State m = st.copy();
            int v = read(m, src, sz);
            m.a[an] = sz == 1 ? (short) v : v;
            run("movea." + SZ[sz] + " " + src.text + ",a" + an, m);
            n++;
        }
        randomState();
        int dn = dreg(), imm = rnd.nextInt(256) - 128;
        State m = st.copy();
        m.d[dn] = imm;
        m.ccr = logic(m.ccr, imm, 2);
        run("moveq #" + imm + ",d" + dn, m);
        randomState();
        Ea src = ea(2, CONTROL);
        int an = areg();
        m = st.copy();
        m.a[an] = address(m, src, 2);
        run("lea " + src.text + ",a" + an, m);
        return n + 2;
    }

    private int arithmetic() {
        int n = 0;
        for (String op : new String[] {"add", "sub", "cmp", "and", "or"}) {
            for (int sz = 0; sz < 3; sz++) {
                randomState();
                boolean logical = op.equals("and") || op.equals("or");
                Ea src = ea(sz, sz == 0 || logical ? DATA_MODES : ALL_MODES);
                int dn = dreg();
                State m = st.copy();
                int s = read(m, src, sz);
                int d = m.d[dn] & MASK[sz];
                switch (op) {
                    case "add" -> {
                        m.ccr = addFlags(s, d, sz);
                        m.setD(dn, sz, s + d);
                    }
                    case "sub" -> {
                        m.ccr = subFlags(s, d, sz);
                        m.setD(dn, sz, d - s);
                    }
                    case "cmp" -> m.ccr = (subFlags(s, d, sz) & ~X) | (m.ccr & X);
                    case "and" -> {
                        m.setD(dn, sz, d & s);
                        m.ccr = logic(m.ccr, d & s, sz);
                    }
                    default -> {
                        m.setD(dn, sz, d | s);
                        m.ccr = logic(m.ccr, d | s, sz);
                    }
                }
                run(op + "." + SZ[sz] + " " + src.text + ",d" + dn, m);
                n++;
            }
        }
        for (String op : new String[] {"add", "sub", "eor"}) {
            for (int sz = 0; sz < 3; sz++) {
                randomState();
                int dn = dreg();
                Ea dst = ea(sz, op.equals("eor") ? ALTERABLE : MEMORY_ALTERABLE);
                State m = st.copy();
                int s = m.d[dn] & MASK[sz];
                int addr = dst.mode == 0 ? 0 : address(m, dst, sz);
                int d = dst.mode == 0 ? m.d[dst.reg] & MASK[sz] : m.read(addr, sz);
                int r;
                if (op.equals("add")) {
                    r = s + d;
                    m.ccr = addFlags(s, d, sz);
                } else if (op.equals("sub")) {
                    r = d - s;
                    m.ccr = subFlags(s, d, sz);
                } else {
                    r = d ^ s;
                    m.ccr = logic(m.ccr, r, sz);
                }
                write(m, dst, sz, addr, r);
                run(op + "." + SZ[sz] + " d" + dn + "," + dst.text, m);
                n++;
            }
        }
        for (String op : new String[] {"addx", "subx"}) {
            for (int sz = 0; sz < 3; sz++) {
                randomState();
                int dy = dreg(), dx = dreg();
                State m = st.copy();
                int s = m.d[dy] & MASK[sz], d = m.d[dx] & MASK[sz], x = (m.ccr & X) != 0 ? 1 : 0;
                int r = op.equals("addx") ? d + s + x : d - s - x;
                long wide = op.equals("addx")
                        ? Integer.toUnsignedLong(d) + Integer.toUnsignedLong(s) + x
                        : Integer.toUnsignedLong(d) - Integer.toUnsignedLong(s) - x;
                boolean carry = op.equals("addx") ? wide > Integer.toUnsignedLong(MASK[sz]) : wide < 0;
                int rm = r & MASK[sz];
                boolean v = op.equals("addx") ? ((s ^ rm) & (d ^ rm) & MSB[sz]) != 0 : ((s ^ d) & (rm ^ d) & MSB[sz]) != 0;
                int f = ((rm & MSB[sz]) != 0 ? N : 0) | (v ? V : 0) | (carry ? C | X : 0);
                f |= rm != 0 ? 0 : m.ccr & Z;
                m.setD(dx, sz, r);
                m.ccr = f;
                run(op + "." + SZ[sz] + " d" + dy + ",d" + dx, m);
                n++;
            }
        }
        return n;
    }

    private int quickAndUnary() {
        int n = 0;
        for (int sz = 0; sz < 3; sz++) {
            for (String op : new String[] {"addq", "subq"}) {
                randomState();
                int k = 1 + rnd.nextInt(8);
                Ea dst = ea(sz, ALTERABLE);
                State m = st.copy();
                int addr = dst.mode == 0 ? 0 : address(m, dst, sz);
                int d = dst.mode == 0 ? m.d[dst.reg] & MASK[sz] : m.read(addr, sz);
                m.ccr = op.equals("addq") ? addFlags(k, d, sz) : subFlags(k, d, sz);
                write(m, dst, sz, addr, op.equals("addq") ? d + k : d - k);
                run(op + "." + SZ[sz] + " #" + k + "," + dst.text, m);
                n++;
            }
            for (String op : new String[] {"neg", "not", "clr", "tst"}) {
                randomState();
                Ea dst = ea(sz, ALTERABLE);
                State m = st.copy();
                int addr = dst.mode == 0 ? 0 : address(m, dst, sz);
                int d = dst.mode == 0 ? m.d[dst.reg] & MASK[sz] : m.read(addr, sz);
                switch (op) {
                    case "neg" -> {
                        m.ccr = subFlags(d, 0, sz);
                        write(m, dst, sz, addr, -d);
                    }
                    case "not" -> {
                        m.ccr = logic(m.ccr, ~d, sz);
                        write(m, dst, sz, addr, ~d);
                    }
                    case "clr" -> {
                        m.ccr = (m.ccr & X) | Z;
                        write(m, dst, sz, addr, 0);
                    }
                    default -> m.ccr = logic(m.ccr, d, sz);
                }
                run(op + "." + SZ[sz] + " " + dst.text, m);
                n++;
            }
        }
        randomState();
        int an = areg(), k = 1 + rnd.nextInt(8);
        State m = st.copy();
        m.a[an] += k;
        run("addq.w #" + k + ",a" + an, m);
        randomState();
        int dn = dreg();
        m = st.copy();
        m.setD(dn, 1, (byte) m.d[dn]);
        m.ccr = logic(m.ccr, (byte) m.d[dn], 1);
        run("ext.w d" + dn, m);
        randomState();
        m = st.copy();
        m.d[dn] = (short) m.d[dn];
        m.ccr = logic(m.ccr, m.d[dn], 2);
        run("ext.l d" + dn, m);
        randomState();
        m = st.copy();
        m.d[dn] = Integer.rotateLeft(m.d[dn], 16);
        m.ccr = logic(m.ccr, m.d[dn], 2);
        run("swap d" + dn, m);
        randomState();
        int dy = dreg();
        m = st.copy();
        int t = m.d[dn];
        m.d[dn] = m.d[dy];
        m.d[dy] = t;
        run("exg d" + dn + ",d" + dy, m);
        return n + 5;
    }

    private int shifts() {
        int n = 0;
        int[] f = new int[1];
        for (String op : new String[] {"lsl", "lsr", "asl", "asr", "rol", "ror", "roxl", "roxr"}) {
            int sz = rnd.nextInt(3);
            randomState();
            int dn = dreg(), k = 1 + rnd.nextInt(8);
            State m = st.copy();
            m.setD(dn, sz, shift(op, m.d[dn], k, sz, m.ccr, f));
            m.ccr = f[0];
            run(op + "." + SZ[sz] + " #" + k + ",d" + dn, m);

            randomState();
            int dc = dreg();
            dn = dreg();
            if (rnd.nextInt(4) == 0)
                st.d[dc] = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(70);
            m = st.copy();
            int count = m.d[dc] & 63;
            m.setD(dn, sz, shift(op, m.d[dn], count, sz, m.ccr, f));
            m.ccr = f[0];
            run(op + "." + SZ[sz] + " d" + dc + ",d" + dn, m);

            randomState();
            Ea mem = ea(1, MEMORY_ALTERABLE);
            m = st.copy();
            int addr = address(m, mem, 1);
            m.write(addr, 1, shift(op, m.read(addr, 1), 1, 1, m.ccr, f));
            m.ccr = f[0];
            run(op + ".w " + mem.text, m);
            n += 3;
        }
        return n;
    }

    private int multiplyDivide() {
        int n = 0;
        for (String op : new String[] {"mulu", "muls", "divu", "divs"}) {
            randomState();
            Ea src = ea(1, DATA_MODES);
            int dn = dreg();
            if (op.startsWith("div") && rnd.nextBoolean() && !(src.mode == 6 && src.xreg == dn))
                st.d[dn] = rnd.nextInt(1 << 20) * (op.equals("divs") && rnd.nextBoolean() ? -1 : 1);
            State probe = st.copy();
            int s = read(probe, src, 1);
            if (op.startsWith("div") && s == 0)
                continue;
            State m = st.copy();
            read(m, src, 1);
            int d = m.d[dn];
            switch (op) {
                case "mulu" -> {
                    int r = (d & 0xFFFF) * s;
                    m.d[dn] = r;
                    m.ccr = logic(m.ccr, r, 2);
                }
                case "muls" -> {
                    int r = (short) d * (short) s;
                    m.d[dn] = r;
                    m.ccr = logic(m.ccr, r, 2);
                }
                case "divu" -> {
                    long q = Integer.toUnsignedLong(d) / s, rem = Integer.toUnsignedLong(d) % s;
                    if (q > 0xFFFF) {
                        m.ccr = (m.ccr & (X | N | Z)) | V;
                    } else {
                        m.d[dn] = (int) (rem << 16 | q);
                        m.ccr = (m.ccr & X) | nz((int) q, 1);
                    }
                }
                default -> {
                    long q = (long) d / (short) s, rem = (long) d % (short) s;
                    if (q < -32768 || q > 32767) {
                        m.ccr = (m.ccr & (X | N | Z)) | V;
                    } else {
                        m.d[dn] = (int) ((rem & 0xFFFF) << 16 | (q & 0xFFFF));
                        m.ccr = (m.ccr & X) | nz((int) q, 1);
                    }
                }
            }
            run(op + ".w " + src.text + ",d" + dn, m);
            n++;
        }
        return n;
    }

    private int bits() {
        int n = 0;
        for (String op : new String[] {"btst", "bchg", "bclr", "bset"}) {
            randomState();
            int dn = dreg(), bit = rnd.nextInt(32);
            State m = st.copy();
            int mask = 1 << bit;
            m.ccr = (m.ccr & ~Z) | ((m.d[dn] & mask) == 0 ? Z : 0);
            m.d[dn] = switch (op) {
                case "bchg" -> m.d[dn] ^ mask;
                case "bclr" -> m.d[dn] & ~mask;
                case "bset" -> m.d[dn] | mask;
                default -> m.d[dn];
            };
            run(op + " #" + bit + ",d" + dn, m);

            randomState();
            int db = dreg();
            Ea mem = ea(0, MEMORY_ALTERABLE);
            if (mem.mode == 6 && mem.xreg == db)
                continue;
            m = st.copy();
            int addr = address(m, mem, 0);
            int b = m.read(addr, 0);
            int bm = 1 << (m.d[db] & 7);
            m.ccr = (m.ccr & ~Z) | ((b & bm) == 0 ? Z : 0);
            b = switch (op) {
                case "bchg" -> b ^ bm;
                case "bclr" -> b & ~bm;
                case "bset" -> b | bm;
                default -> b;
            };
            m.write(addr, 0, b);
            run(op + " d" + db + "," + mem.text, m);
            n += 2;
        }
        return n;
    }

    private int flow() {
        int n = 0;
        for (int cc = 2; cc < 16; cc++) {
            randomState();
            State m = st.copy();
            int target = CODE + 0x40 + 2 * rnd.nextInt(32);
            boolean taken = cond(cc, m.ccr);
            String text = "b" + CC[cc] + ".w $" + Integer.toHexString(target);
            m.pc = taken ? target : CODE + 4;
            check(text, m);
            n++;
        }
        for (int cc = 0; cc < 16; cc++) {
            randomState();
            int dn = dreg();
            State m = st.copy();
            m.setD(dn, 0, cond(cc, m.ccr) ? 0xFF : 0);
            run("s" + CC[cc] + " d" + dn, m);
            n++;
        }
        for (int cc = 0; cc < 16; cc++) {
            randomState();
            int dn = dreg();
            if (rnd.nextBoolean())
                st.d[dn] = (st.d[dn] & ~0xFFFF) | rnd.nextInt(2);
            State m = st.copy();
            int target = CODE + 0x40;
            String text = "db" + CC[cc] + " d" + dn + ",$" + Integer.toHexString(target);
            if (cond(cc, m.ccr)) {
                m.pc = CODE + 4;
            } else {
                int count = (m.d[dn] - 1) & 0xFFFF;
                m.setD(dn, 1, count);
                m.pc = count == 0xFFFF ? CODE + 4 : target;
            }
            check(text, m);
            n++;
        }
        return n;
    }

    private int m68040() {
        int n = 0;
        randomState();
        int dn = dreg();
        State m = st.copy();
        m.d[dn] = (byte) m.d[dn];
        m.ccr = logic(m.ccr, m.d[dn], 2);
        run("extb.l d" + dn, m);
        n++;
        for (String op : new String[] {"mulu", "muls"}) {
            randomState();
            Ea src = ea(2, DATA_MODES);
            dn = dreg();
            m = st.copy();
            int s = read(m, src, 2);
            int d = m.d[dn];
            boolean overflow;
            int r;
            if (op.equals("mulu")) {
                long p = Integer.toUnsignedLong(d) * Integer.toUnsignedLong(s);
                r = (int) p;
                overflow = (p >>> 32) != 0;
            } else {
                long p = (long) d * s;
                r = (int) p;
                overflow = p != r;
            }
            m.d[dn] = r;
            m.ccr = (m.ccr & X) | nz(r, 2) | (overflow ? V : 0);
            run(op + ".l " + src.text + ",d" + dn, m);
            n++;
        }
        for (String op : new String[] {"divu", "divs"}) {
            randomState();
            Ea src = ea(2, DATA_MODES);
            dn = dreg();
            State probe = st.copy();
            int s = read(probe, src, 2);
            if (s == 0)
                continue;
            m = st.copy();
            read(m, src, 2);
            int d = m.d[dn];
            if (op.equals("divs") && d == Integer.MIN_VALUE && s == -1) {
                m.ccr = (m.ccr & (X | N | Z)) | V;
            } else {
                int q = op.equals("divu") ? Integer.divideUnsigned(d, s) : d / s;
                m.d[dn] = q;
                m.ccr = (m.ccr & X) | nz(q, 2);
            }
            run(op + ".l " + src.text + ",d" + dn, m);
            n++;
        }
        return n;
    }
}
