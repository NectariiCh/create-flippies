package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.RAM_BASE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.rv32.Rv32Cpu;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Encoder and decoder cross-check (spec 8.4). The assembler and the CPU were written
 * independently, so assembling one instruction with {@code Rv32Assembler}, executing it on
 * {@code Rv32Cpu} from a random register state, and comparing registers, memory, pc and CSRs with
 * the small reference model below finds any field-layout mismatch between the two without a
 * reference toolchain.
 */
class EncoderDecoderCrossCheckTest {

    private static final int CODE = RAM_BASE + 0x1000;
    private static final int SCRATCH = RAM_BASE + 0x2000;
    private static final int DATA = RAM_BASE + 0x10000;
    private static final int DATA_SPAN = 0x1000;
    private static final int HANDLER = RAM_BASE + 0x3000;
    private static final int MSCRATCH = 0x340, MEPC = 0x341, MCAUSE = 0x342, MTVAL = 0x343, MTVEC = 0x305;
    private static final int ROUNDS = 48;

    private static final String[] ABI = { "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1",
            "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9",
            "s10", "s11", "t3", "t4", "t5", "t6" };
    private static final int[] SPECIAL = { 0, 1, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x8000_0001,
            0xFFFF_F800, 0x7FF, 0x800, 31, 32, 0x1234_5678, 0x9ABC_DEF0 };
    private static final int[] IMM_EDGES = { 0, 1, -1, 2047, -2048, 0x7FF, -0x7FF, 1024, -1024 };

    private final Random rnd = new Random(0x5EED_C0DEL);
    private Machine machine;
    private Rv32Cpu cpu;
    private int[] regs;
    private String text;

    /** One reference outcome: the state the instruction must leave. */
    private static final class Model {
        final int[] x;
        int pc;
        final byte[] ram;

        Model(int[] x, int pc, byte[] ram) {
            this.x = x;
            this.pc = pc;
            this.ram = ram;
        }

        void set(int rd, int v) {
            if (rd != 0)
                x[rd] = v;
        }

        int load(int addr, int size, boolean signed) {
            int off = addr - RAM_BASE;
            int v = 0;
            for (int i = size - 1; i >= 0; i--)
                v = v << 8 | (ram[off + i] & 0xFF);
            if (signed && size < 4)
                v = v << (32 - 8 * size) >> (32 - 8 * size);
            return v;
        }

        void store(int addr, int size, int v) {
            int off = addr - RAM_BASE;
            for (int i = 0; i < size; i++)
                ram[off + i] = (byte) (v >>> (8 * i));
        }
    }

    @Test
    void everyInstructionAgreesWithTheReferenceModel() {
        machine = E2e.boot("""
                _start:
                    j _start
                """);
        cpu = (Rv32Cpu) machine.cpu();
        runOne(CODE, "nop");
        setCsr(MTVEC, HANDLER);

        String[] alu = { "add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and",
                "mul", "mulh", "mulhsu", "mulhu", "div", "divu", "rem", "remu" };
        String[] aluImm = { "addi", "slti", "sltiu", "xori", "ori", "andi" };
        String[] shiftImm = { "slli", "srli", "srai" };
        String[] loads = { "lb", "lh", "lw", "lbu", "lhu" };
        String[] stores = { "sb", "sh", "sw" };
        String[] branches = { "beq", "bne", "blt", "bge", "bltu", "bgeu" };
        String[] csrOps = { "csrrw", "csrrs", "csrrc" };
        String[] csrImmOps = { "csrrwi", "csrrsi", "csrrci" };

        for (int round = 0; round < ROUNDS; round++) {
            for (String op : alu) {
                int rd = reg(), rs1 = reg(), rs2 = reg();
                randomRegs();
                Model m = model();
                m.set(rd, alu(op, regs[rs1], regs[rs2]));
                m.pc = CODE + 4;
                check(String.format("%s %s, %s, %s", op, name(rd), name(rs1), name(rs2)), m);
            }
            for (String op : aluImm) {
                int rd = reg(), rs1 = reg(), imm = imm12();
                randomRegs();
                Model m = model();
                m.set(rd, alu(op.replaceFirst("i(u?)$", "$1"), regs[rs1], imm));
                m.pc = CODE + 4;
                check(String.format("%s %s, %s, %d", op, name(rd), name(rs1), imm), m);
            }
            for (String op : shiftImm) {
                int rd = reg(), rs1 = reg(), sh = rnd.nextInt(32);
                randomRegs();
                Model m = model();
                m.set(rd, alu(op.replaceFirst("i(u?)$", "$1"), regs[rs1], sh));
                m.pc = CODE + 4;
                check(String.format("%s %s, %s, %d", op, name(rd), name(rs1), sh), m);
            }
            {
                int rd = reg(), imm = rnd.nextInt(1 << 20);
                randomRegs();
                Model m = model();
                m.set(rd, imm << 12);
                m.pc = CODE + 4;
                check(String.format("lui %s, 0x%x", name(rd), imm), m);
                rd = reg();
                imm = rnd.nextInt(1 << 20);
                m = model();
                m.set(rd, CODE + (imm << 12));
                m.pc = CODE + 4;
                check(String.format("auipc %s, 0x%x", name(rd), imm), m);
            }
            for (String op : loads) {
                int rd = reg(), rs1 = nonZeroReg(), imm = imm12();
                int size = op.contains("b") ? 1 : op.contains("h") ? 2 : 4;
                int addr = DATA + rnd.nextInt(DATA_SPAN - 4);
                randomRegs();
                regs[rs1] = addr - imm;
                randomData();
                Model m = model();
                m.set(rd, m.load(addr, size, !op.endsWith("u")));
                m.pc = CODE + 4;
                check(String.format("%s %s, %d(%s)", op, name(rd), imm, name(rs1)), m);
            }
            for (String op : stores) {
                int rs2 = reg(), rs1 = nonZeroReg(), imm = imm12();
                int size = op.equals("sb") ? 1 : op.equals("sh") ? 2 : 4;
                int addr = DATA + rnd.nextInt(DATA_SPAN - 4);
                randomRegs();
                regs[rs1] = addr - imm;
                randomData();
                Model m = model();
                m.store(addr, size, regs[rs2]);
                m.pc = CODE + 4;
                check(String.format("%s %s, %d(%s)", op, name(rs2), imm, name(rs1)), m);
            }
            for (String op : branches) {
                int rs1 = reg(), rs2 = reg();
                int off = (rnd.nextInt(2048) - 1024) * 4;
                randomRegs();
                if (rs2 != 0 && rnd.nextInt(4) == 0)
                    regs[rs2] = regs[rs1];
                Model m = model();
                m.pc = branch(op, regs[rs1], regs[rs2]) ? CODE + off : CODE + 4;
                check(String.format("%s %s, %s, %d", op, name(rs1), name(rs2), off), m);
            }
            {
                int rd = reg();
                int off = (rnd.nextInt(1 << 18) - (1 << 17)) * 4;
                randomRegs();
                Model m = model();
                m.set(rd, CODE + 4);
                m.pc = CODE + off;
                check(String.format("jal %s, %d", name(rd), off), m);

                rd = reg();
                int rs1 = reg(), imm = imm12();
                randomRegs();
                // an aligned target, sometimes with bit 0 set (JALR clears it)
                int bit0 = rnd.nextInt(2);
                int target;
                if (rs1 != 0) {
                    target = (rnd.nextInt() & ~3) | bit0;
                    regs[rs1] = target - imm;
                } else {
                    imm = (imm & ~3) | bit0;
                    target = imm;
                }
                m = model();
                m.set(rd, CODE + 4);
                m.pc = target & ~1;
                check(String.format("jalr %s, %d(%s)", name(rd), imm, name(rs1)), m);
            }
            for (int k = 0; k < 3; k++) {
                int rd = reg(), rs1 = reg();
                randomRegs();
                int old = rnd.nextInt();
                setCsr(MSCRATCH, old);
                Model m = model();
                int src = regs[rs1];
                int now = switch (k) {
                    case 0 -> src;
                    case 1 -> old | src;
                    default -> old & ~src;
                };
                m.set(rd, old);
                m.pc = CODE + 4;
                check(String.format("%s %s, mscratch, %s", csrOps[k], name(rd), name(rs1)), m);
                assertEquals(now, cpu.csr(MSCRATCH), text);

                rd = reg();
                int uimm = rnd.nextInt(32);
                old = rnd.nextInt();
                setCsr(MSCRATCH, old);
                randomRegs();
                m = model();
                now = switch (k) {
                    case 0 -> uimm;
                    case 1 -> old | uimm;
                    default -> old & ~uimm;
                };
                m.set(rd, old);
                m.pc = CODE + 4;
                check(String.format("%s %s, 0x340, %d", csrImmOps[k], name(rd), uimm), m);
                assertEquals(now, cpu.csr(MSCRATCH), text);
            }
        }
    }

    @Test
    void systemInstructionsAgreeWithTheReferenceModel() {
        machine = E2e.boot("""
                _start:
                    j _start
                """);
        cpu = (Rv32Cpu) machine.cpu();
        setCsr(MTVEC, HANDLER);

        randomRegs();
        Model m = model();
        m.pc = HANDLER;
        check("ecall", m);
        assertEquals(11, cpu.csr(MCAUSE));
        assertEquals(CODE, cpu.csr(MEPC));
        assertEquals(0, cpu.csr(MTVAL));

        randomRegs();
        m = model();
        m.pc = HANDLER;
        check("ebreak", m);
        assertEquals(3, cpu.csr(MCAUSE));
        assertEquals(CODE, cpu.csr(MTVAL));

        setCsr(MEPC, RAM_BASE + 0x4440);
        randomRegs();
        m = model();
        m.pc = RAM_BASE + 0x4440;
        check("mret", m);

        for (String nop : new String[] { "fence", "fence.i", "fence rw, w", "fence.tso", "pause", "nop" }) {
            randomRegs();
            m = model();
            m.pc = CODE + 4;
            check(nop, m);
        }

        randomRegs();
        m = model();
        m.pc = HANDLER;
        check("unimp", m);
        assertEquals(2, cpu.csr(MCAUSE));
        assertEquals(0xC000_1073, cpu.csr(MTVAL));
    }

    // ---- reference model ----

    private static int alu(String op, int a, int b) {
        return switch (op) {
            case "add" -> a + b;
            case "sub" -> a - b;
            case "sll" -> a << (b & 31);
            case "slt" -> a < b ? 1 : 0;
            case "sltu" -> Integer.compareUnsigned(a, b) < 0 ? 1 : 0;
            case "xor" -> a ^ b;
            case "srl" -> a >>> (b & 31);
            case "sra" -> a >> (b & 31);
            case "or" -> a | b;
            case "and" -> a & b;
            case "mul" -> a * b;
            case "mulh" -> (int) (((long) a * b) >> 32);
            case "mulhsu" -> (int) (((long) a * Integer.toUnsignedLong(b)) >> 32);
            case "mulhu" -> (int) ((Integer.toUnsignedLong(a) * Integer.toUnsignedLong(b)) >>> 32);
            case "div" -> b == 0 ? -1 : (a == Integer.MIN_VALUE && b == -1) ? a : a / b;
            case "divu" -> b == 0 ? -1 : Integer.divideUnsigned(a, b);
            case "rem" -> b == 0 ? a : (a == Integer.MIN_VALUE && b == -1) ? 0 : a % b;
            case "remu" -> b == 0 ? a : Integer.remainderUnsigned(a, b);
            default -> throw new AssertionError(op);
        };
    }

    private static boolean branch(String op, int a, int b) {
        return switch (op) {
            case "beq" -> a == b;
            case "bne" -> a != b;
            case "blt" -> a < b;
            case "bge" -> a >= b;
            case "bltu" -> Integer.compareUnsigned(a, b) < 0;
            case "bgeu" -> Integer.compareUnsigned(a, b) >= 0;
            default -> throw new AssertionError(op);
        };
    }

    // ---- harness ----

    private int reg() {
        return rnd.nextInt(8) == 0 ? 0 : rnd.nextInt(32);
    }

    private int nonZeroReg() {
        return 1 + rnd.nextInt(31);
    }

    private String name(int r) {
        return rnd.nextBoolean() ? "x" + r : ABI[r];
    }

    private int imm12() {
        return rnd.nextInt(3) == 0 ? IMM_EDGES[rnd.nextInt(IMM_EDGES.length)] : rnd.nextInt(4096) - 2048;
    }

    private int value() {
        return rnd.nextInt(3) == 0 ? SPECIAL[rnd.nextInt(SPECIAL.length)] : rnd.nextInt();
    }

    private void randomRegs() {
        regs = new int[32];
        for (int i = 1; i < 32; i++)
            regs[i] = value();
    }

    private void randomData() {
        byte[] ram = machine.board().ram();
        byte[] bytes = new byte[DATA_SPAN];
        rnd.nextBytes(bytes);
        System.arraycopy(bytes, 0, ram, DATA - RAM_BASE, DATA_SPAN);
    }

    private Model model() {
        return new Model(regs.clone(), CODE, machine.board().ram().clone());
    }

    /** Assembles {@code line} at CODE, runs exactly it from {@link #regs}, and compares with {@code expected}. */
    private void check(String line, Model expected) {
        text = line;
        for (int i = 0; i < 32; i++)
            cpu.setReg(i, regs[i]);
        byte[] word = runOne(CODE, line);
        System.arraycopy(word, 0, expected.ram, CODE - RAM_BASE, 4);   // the instruction itself
        int[] actual = new int[32];
        for (int i = 0; i < 32; i++)
            actual[i] = cpu.reg(i);
        assertArrayEquals(expected.x, actual, () -> line + ": registers " + diff(expected.x, actual));
        assertEquals(expected.pc, cpu.pc(), () -> line + ": pc");
        if (!Arrays.equals(expected.ram, machine.board().ram()))
            fail(line + ": memory differs");
    }

    /** Puts the one instruction {@code line} at {@code address}, executes exactly it, and returns its bytes. */
    private byte[] runOne(int address, String line) {
        AsmResult r = Rv32Assembler.assemble(line, AsmOptions.at(address));
        if (!r.ok())
            fail("'" + line + "' does not assemble: " + r.diagnostics());
        byte[] word = r.image().flatten(address, 4);
        machine.loadRam(address, word);
        cpu.setPc(address);
        long retired = cpu.instructionsRetired();
        Cpu.Exit exit = cpu.run(machine.clock().now() + 1, Long.MAX_VALUE);
        assertEquals(Cpu.Exit.REACHED, exit, () -> line + ": " + cpu.haltReason());
        long n = cpu.instructionsRetired() - retired;
        if (n > 1)
            fail(line + ": ran " + n + " instructions");
        return word;
    }

    private void setCsr(int csr, int value) {
        int save = cpu.reg(31);
        cpu.setReg(31, value);
        runOne(SCRATCH, String.format("csrrw x0, 0x%x, x31", csr));
        cpu.setReg(31, save);
        assertEquals(csr == MEPC ? value & ~3 : value, cpu.csr(csr));
    }

    private static String diff(int[] want, int[] got) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++)
            if (want[i] != got[i])
                sb.append(String.format("x%d want 0x%08x got 0x%08x; ", i, want[i], got[i]));
        return sb.toString();
    }
}
