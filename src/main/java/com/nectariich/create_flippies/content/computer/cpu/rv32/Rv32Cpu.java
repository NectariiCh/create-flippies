package com.nectariich.create_flippies.content.computer.cpu.rv32;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.BusFault;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The RV32I CPU: an in-order 32-bit core implementing RV32I, optionally M, plus Zicsr, Zicntr and
 * Zifencei, machine mode only (spec 2.6, 4.4 to 4.6, 7.3). It interprets real RISC-V machine
 * code straight from the bus's RAM, ROM and VRAM arrays.
 *
 * Structure: {@link #loop} is the hot interpreter. It keeps pc, the cycle count, the retired
 * count and the memory map in locals and completes every common instruction inline: ALU, M
 * multiply and divide, LUI, AUIPC, branches and jumps with aligned targets, and loads and stores
 * that hit the fast arrays. Everything else (device accesses, CSRs, SYSTEM, fences, traps and
 * illegal words) goes through one out-of-line call, {@link #exec}, which works on the fields.
 * The loop writes its locals to the fields before that call and reloads them after, so the
 * out-of-line code always sees exact state. Keeping the rare paths out of the loop keeps it
 * under HotSpot's 8000-bytecode limit for JIT compilation.
 *
 * Interrupts are evaluated only where they can change (spec 7.3): at the start of a run, after a
 * device access, after a CSR write to mstatus or mie, after MRET and after a WFI that does not
 * sleep. Pin levels only change inside device accesses or between runs, so this is exact.
 *
 * Instructions execute only from ROM, RAM and VRAM. A fetch anywhere else (devices, unmapped
 * space) is an instruction access fault without a bus access.
 */
public final class Rv32Cpu implements Cpu {

    /** Little-endian 32-bit view of a byte array; plain get and set accept unaligned offsets. */
    private static final VarHandle LE32 = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    /** Little-endian 16-bit view of a byte array. */
    private static final VarHandle LE16 = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /** Instructions between host-deadline checks (spec 1.4). */
    private static final int DEADLINE_STRIDE = 4096;

    // ---- cycle costs, spec 4.6 ----

    /** Extra cycles of a load. */
    private static final int LOAD_EXTRA = 1;
    /** Extra cycles of a misaligned load or store. */
    private static final int MISALIGNED_EXTRA = 1;
    /** Extra cycles of a taken branch, JAL or JALR (pipeline refill). */
    private static final int JUMP_EXTRA = 2;
    /** Extra cycles of MUL, MULH, MULHSU, MULHU. */
    private static final int MUL_EXTRA = 2;
    /** Extra cycles of DIV, DIVU, REM, REMU (one quotient bit per cycle). */
    private static final int DIV_EXTRA = 32;
    /** Extra cycles of trap entry, on top of the instruction. */
    private static final int TRAP_EXTRA = 2;
    /** Extra cycles of MRET. */
    private static final int MRET_EXTRA = 2;

    // ---- opcodes, bits 6..2 of the instruction (unprivileged spec, table "RISC-V base opcode map") ----

    private static final int OPC_LOAD = 0x00;
    private static final int OPC_MISC_MEM = 0x03;
    private static final int OPC_OP_IMM = 0x04;
    private static final int OPC_AUIPC = 0x05;
    private static final int OPC_STORE = 0x08;
    private static final int OPC_OP = 0x0C;
    private static final int OPC_LUI = 0x0D;
    private static final int OPC_BRANCH = 0x18;
    private static final int OPC_JALR = 0x19;
    private static final int OPC_JAL = 0x1B;
    private static final int OPC_SYSTEM = 0x1C;

    // ---- whole SYSTEM words with funct3 000 (privileged spec, "Instruction listing") ----

    private static final int ECALL = 0x0000_0073;
    private static final int EBREAK = 0x0010_0073;
    private static final int MRET = 0x3020_0073;
    private static final int WFI = 0x1050_0073;

    // ---- exception codes (spec 4.5) ----

    private static final int CAUSE_FETCH_MISALIGNED = 0;
    private static final int CAUSE_FETCH_FAULT = 1;
    private static final int CAUSE_ILLEGAL = 2;
    private static final int CAUSE_BREAKPOINT = 3;
    private static final int CAUSE_LOAD_FAULT = 5;
    private static final int CAUSE_STORE_FAULT = 7;
    private static final int CAUSE_ECALL_M = 11;
    /** mcause bit 31: the trap is an interrupt. */
    private static final int CAUSE_INTERRUPT = 0x8000_0000;

    /** mstatus.MIE and MPIE bits; MPP (bits 12..11) reads 0b11 (spec 4.4). */
    private static final int MSTATUS_MIE = 1 << 3;
    private static final int MSTATUS_MPIE = 1 << 7;
    private static final int MSTATUS_MPP = 3 << 11;
    /** The three implemented interrupt bits of mie and mip (spec 4.3). */
    private static final int IRQ_MASK = (1 << Rv32Map.IRQ_PIN_MSIP) | (1 << Rv32Map.IRQ_PIN_MTIP) | (1 << Rv32Map.IRQ_PIN_MEIP);
    /** misa: MXL = 1 (32-bit), extension I, and M when enabled (spec 4.4). */
    private static final int MISA_I = 0x4000_0100;
    private static final int MISA_M = 1 << 12;
    /** readCsr's answer for a CSR this core does not implement. */
    private static final long NO_CSR = Long.MIN_VALUE;
    /** Format version of {@link #saveState}. */
    private static final int STATE_VERSION = 1;

    private final Bus bus;
    private final CycleClock clock;
    private final int clockHz;
    private final int resetPc;
    private final boolean mExtension;

    /** x0..x31; x[0] is cleared after every instruction rather than guarded on every write. */
    private final int[] x = new int[32];
    private int pc;
    /** The CPU's cycle count; equals clock.now() at the start of every run. */
    private long cycles;
    /** Instructions retired since reset. */
    private long instret;
    /** The cycle to stop at: clock.horizon(), re-read after every device access. */
    private long limit;

    // ---- CSR state ----

    private boolean mstatusMie;
    private boolean mstatusMpie;
    private int mieBits;
    /** Pin levels, one bit per mip bit; set by {@link #setIrq}, possibly from inside a device access. */
    private int mip;
    private int mtvec;
    private int mscratch;
    private int mepc;
    private int mcause;
    private int mtval;
    /** mcycle = cycles + cycleOffset; changed by reset and by guest writes. */
    private long cycleOffset;
    /** minstret = instret + instretOffset; changed by guest writes. */
    private long instretOffset;
    /** Set by reset: the next run zeroes mcycle against the clock it finds (the machine may reset the clock after the CPU). */
    private boolean zeroCountersOnRun;

    // ---- run state ----

    private boolean waiting;
    private boolean halted;
    private String haltReason;
    /** True once a trap has been entered; with {@link #trapInstret} it detects a handler that cannot be fetched. */
    private boolean trapArmed;
    private long trapInstret;

    // ---- fast-path memory map, reloaded from the bus at the start of every run ----

    private byte[] rom;
    private int romBase;
    private int romSize;
    private byte[] ram;
    private int ramBase;
    private int ramSize;
    private byte[] vram;
    private int vramBase;
    private int vramSize;
    private long[] vramDirty;
    /** Offset into the array {@link #fastArray} last returned; slow path only. */
    private int fastOffset;

    /**
     * @param host       the CPU's connections; only {@code memory}, {@code clock}, {@code clockHz}
     *                   and {@code resetPc} are used
     * @param mExtension whether the M extension exists; when false its encodings are illegal
     */
    public Rv32Cpu(CpuHost host, boolean mExtension) {
        this.bus = host.memory();
        this.clock = host.clock();
        this.clockHz = host.clockHz();
        this.resetPc = host.resetPc();
        this.mExtension = mExtension;
        reset();
    }

    @Override
    public CpuModel model() {
        return CpuModel.RV32I;
    }

    /** Spec 4.5: registers, CSRs and counters to zero, PC to the reset PC. Pin levels are external and kept. */
    @Override
    public void reset() {
        Arrays.fill(x, 0);
        pc = resetPc;
        mstatusMie = false;
        mstatusMpie = false;
        mieBits = 0;
        mtvec = 0;
        mscratch = 0;
        mepc = 0;
        mcause = 0;
        mtval = 0;
        instret = 0;
        instretOffset = 0;
        cycles = clock.now();
        cycleOffset = -cycles;
        zeroCountersOnRun = true;
        waiting = false;
        halted = false;
        haltReason = null;
        trapArmed = false;
        trapInstret = 0;
    }

    @Override
    public Exit run(long untilCycle, long hostDeadlineNanos) {
        clock.setHorizon(untilCycle);
        if (halted)
            return Exit.HALTED;
        cycles = clock.now();
        if (zeroCountersOnRun) {
            cycleOffset = -cycles;
            zeroCountersOnRun = false;
        }
        limit = clock.horizon();
        if (cycles >= limit)
            return Exit.REACHED;
        if (waiting) {
            // Wake rule (spec 1.4, 4.5): any interrupt pending and enabled in mie, whatever mstatus.MIE.
            if ((mip & mieBits) == 0)
                return Exit.WAITING;
            waiting = false;
        }
        loadMemoryMap();
        checkInterrupt();
        Exit exit = loop(hostDeadlineNanos);
        clock.advanceTo(cycles);
        return exit;
    }

    private void loadMemoryMap() {
        rom = bus.romArray();
        romBase = bus.romBase();
        romSize = bus.romSize();
        ram = bus.ramArray();
        ramBase = bus.ramBase();
        ramSize = bus.ramSize();
        vram = bus.vramArray();
        vramBase = bus.vramBase();
        vramSize = bus.vramSize();
        vramDirty = bus.vramDirtyPages();
    }

    /**
     * The hot interpreter. A word access at {@code off} into a region is fast when
     * {@code off <u size - 3}; byte and halfword accesses use the same bound, so the last three
     * bytes of a region go through {@link #exec}, which handles them exactly.
     */
    private Exit loop(long deadline) {
        final int[] x = this.x;
        final boolean mExt = mExtension;
        final byte[] rom = this.rom;
        final byte[] ram = this.ram;
        final byte[] vram = this.vram;
        final long[] dirty = this.vramDirty;
        final int romBase = this.romBase;
        final int ramBase = this.ramBase;
        final int vramBase = this.vramBase;
        final int romW = Math.max(0, romSize - 3);
        final int ramW = Math.max(0, ramSize - 3);
        final int vramW = Math.max(0, vramSize - 3);

        int pc = this.pc;
        long cyc = this.cycles;
        long ret = this.instret;
        long limit = this.limit;
        int countdown = DEADLINE_STRIDE;

        for (;;) {
            if (cyc >= limit) {
                this.pc = pc;
                this.cycles = cyc;
                this.instret = ret;
                return Exit.REACHED;
            }
            if (--countdown == 0) {
                countdown = DEADLINE_STRIDE;
                if (System.nanoTime() > deadline) {
                    this.pc = pc;
                    this.cycles = cyc;
                    this.instret = ret;
                    return Exit.DEADLINE;
                }
            }

            int inst;
            int o;
            if (Integer.compareUnsigned(o = pc - romBase, romW) < 0)
                inst = (int) LE32.get(rom, o);
            else if (Integer.compareUnsigned(o = pc - ramBase, ramW) < 0)
                inst = (int) LE32.get(ram, o);
            else if (Integer.compareUnsigned(o = pc - vramBase, vramW) < 0)
                inst = (int) LE32.get(vram, o);
            else {
                this.pc = pc;
                this.cycles = cyc;
                this.instret = ret;
                fetchFault();
                if (halted)
                    return Exit.HALTED;
                pc = this.pc;
                cyc = this.cycles;
                continue;
            }

            decode:
            {
                if ((inst & 3) != 3)
                    break decode;
                final int rd = (inst >>> 7) & 31;
                final int f3 = (inst >>> 12) & 7;
                switch ((inst >>> 2) & 31) {
                    case OPC_OP_IMM: {
                        final int a = x[(inst >>> 15) & 31];
                        final int imm = inst >> 20;
                        switch (f3) {
                            case 0: x[rd] = a + imm; break;
                            case 1:
                                if ((inst >>> 25) != 0)
                                    break decode;
                                x[rd] = a << imm;
                                break;
                            case 2: x[rd] = a < imm ? 1 : 0; break;
                            case 3: x[rd] = Integer.compareUnsigned(a, imm) < 0 ? 1 : 0; break;
                            case 4: x[rd] = a ^ imm; break;
                            case 5: {
                                final int f7 = inst >>> 25;
                                if (f7 == 0)
                                    x[rd] = a >>> imm;
                                else if (f7 == 0x20)
                                    x[rd] = a >> imm;
                                else
                                    break decode;
                                break;
                            }
                            case 6: x[rd] = a | imm; break;
                            default: x[rd] = a & imm; break;
                        }
                        pc += 4;
                        break;
                    }
                    case OPC_OP: {
                        final int a = x[(inst >>> 15) & 31];
                        final int b = x[(inst >>> 20) & 31];
                        final int f7 = inst >>> 25;
                        if (f7 == 0) {
                            switch (f3) {
                                case 0: x[rd] = a + b; break;
                                case 1: x[rd] = a << b; break;
                                case 2: x[rd] = a < b ? 1 : 0; break;
                                case 3: x[rd] = Integer.compareUnsigned(a, b) < 0 ? 1 : 0; break;
                                case 4: x[rd] = a ^ b; break;
                                case 5: x[rd] = a >>> b; break;
                                case 6: x[rd] = a | b; break;
                                default: x[rd] = a & b; break;
                            }
                        } else if (f7 == 0x20) {
                            if (f3 == 0)
                                x[rd] = a - b;
                            else if (f3 == 5)
                                x[rd] = a >> b;
                            else
                                break decode;
                        } else if (f7 == 1 && mExt) {
                            switch (f3) {
                                case 0: x[rd] = a * b; cyc += MUL_EXTRA; break;
                                case 1: x[rd] = (int) (((long) a * b) >> 32); cyc += MUL_EXTRA; break;
                                case 2: x[rd] = (int) (((long) a * (b & 0xFFFF_FFFFL)) >> 32); cyc += MUL_EXTRA; break;
                                case 3: x[rd] = (int) (((a & 0xFFFF_FFFFL) * (b & 0xFFFF_FFFFL)) >>> 32); cyc += MUL_EXTRA; break;
                                case 4: x[rd] = div(a, b); cyc += DIV_EXTRA; break;
                                case 5: x[rd] = divu(a, b); cyc += DIV_EXTRA; break;
                                case 6: x[rd] = rem(a, b); cyc += DIV_EXTRA; break;
                                default: x[rd] = remu(a, b); cyc += DIV_EXTRA; break;
                            }
                        } else {
                            break decode;
                        }
                        pc += 4;
                        break;
                    }
                    case OPC_LUI:
                        x[rd] = inst & 0xFFFF_F000;
                        pc += 4;
                        break;
                    case OPC_AUIPC:
                        x[rd] = pc + (inst & 0xFFFF_F000);
                        pc += 4;
                        break;
                    case OPC_LOAD: {
                        final int addr = x[(inst >>> 15) & 31] + (inst >> 20);
                        final byte[] arr;
                        if (Integer.compareUnsigned(o = addr - romBase, romW) < 0)
                            arr = rom;
                        else if (Integer.compareUnsigned(o = addr - ramBase, ramW) < 0)
                            arr = ram;
                        else if (Integer.compareUnsigned(o = addr - vramBase, vramW) < 0)
                            arr = vram;
                        else
                            break decode;
                        switch (f3) {
                            case 0: x[rd] = arr[o]; break;
                            case 1: x[rd] = (short) LE16.get(arr, o); break;
                            case 2: x[rd] = (int) LE32.get(arr, o); break;
                            case 4: x[rd] = arr[o] & 0xFF; break;
                            case 5: x[rd] = (short) LE16.get(arr, o) & 0xFFFF; break;
                            default: break decode;
                        }
                        cyc += (addr & ((1 << (f3 & 3)) - 1)) != 0 ? LOAD_EXTRA + MISALIGNED_EXTRA : LOAD_EXTRA;
                        pc += 4;
                        break;
                    }
                    case OPC_STORE: {
                        final int addr = x[(inst >>> 15) & 31] + (((inst >> 25) << 5) | ((inst >>> 7) & 31));
                        if (f3 > 2 || Integer.compareUnsigned(addr - romBase, romW) < 0)
                            break decode;
                        final int v = x[(inst >>> 20) & 31];
                        final byte[] arr;
                        if (Integer.compareUnsigned(o = addr - ramBase, ramW) < 0) {
                            arr = ram;
                        } else if (Integer.compareUnsigned(o = addr - vramBase, vramW) < 0) {
                            arr = vram;
                            final int end = o + (1 << f3) - 1;
                            dirty[o >>> 18] |= 1L << ((o >>> 12) & 63);
                            dirty[end >>> 18] |= 1L << ((end >>> 12) & 63);
                        } else {
                            break decode;
                        }
                        if (f3 == 0)
                            arr[o] = (byte) v;
                        else if (f3 == 1)
                            LE16.set(arr, o, (short) v);
                        else
                            LE32.set(arr, o, v);
                        if ((addr & ((1 << f3) - 1)) != 0)
                            cyc += MISALIGNED_EXTRA;
                        pc += 4;
                        break;
                    }
                    case OPC_BRANCH: {
                        final int a = x[(inst >>> 15) & 31];
                        final int b = x[(inst >>> 20) & 31];
                        final boolean taken;
                        switch (f3) {
                            case 0: taken = a == b; break;
                            case 1: taken = a != b; break;
                            case 4: taken = a < b; break;
                            case 5: taken = a >= b; break;
                            case 6: taken = Integer.compareUnsigned(a, b) < 0; break;
                            case 7: taken = Integer.compareUnsigned(a, b) >= 0; break;
                            default: break decode;
                        }
                        if (taken) {
                            final int target = pc + bImm(inst);
                            if ((target & 3) != 0)
                                break decode;
                            pc = target;
                            cyc += JUMP_EXTRA;
                        } else {
                            pc += 4;
                        }
                        break;
                    }
                    case OPC_JAL: {
                        final int target = pc + jImm(inst);
                        if ((target & 3) != 0)
                            break decode;
                        x[rd] = pc + 4;
                        pc = target;
                        cyc += JUMP_EXTRA;
                        break;
                    }
                    case OPC_JALR: {
                        final int target = (x[(inst >>> 15) & 31] + (inst >> 20)) & ~1;
                        if (f3 != 0 || (target & 3) != 0)
                            break decode;
                        x[rd] = pc + 4;
                        pc = target;
                        cyc += JUMP_EXTRA;
                        break;
                    }
                    default:
                        break decode;
                }
                x[0] = 0;
                cyc++;
                ret++;
                continue;
            }

            // Not completed inline: device access, CSR, SYSTEM, fence, trap or illegal word.
            this.pc = pc;
            this.cycles = cyc;
            this.instret = ret;
            exec(inst);
            pc = this.pc;
            cyc = this.cycles;
            ret = this.instret;
            limit = this.limit;
            if (waiting)
                return Exit.WAITING;
        }
    }

    // ---- immediates (unprivileged spec 2.3, "Immediate Encoding Variants") ----

    /** B-type: imm[12|10:5] in bits 31..25, imm[4:1|11] in bits 11..7. */
    private static int bImm(int inst) {
        return ((inst >> 31) << 12) | ((inst << 4) & 0x800) | ((inst >>> 20) & 0x7E0) | ((inst >>> 7) & 0x1E);
    }

    /** J-type: imm[20|10:1|11|19:12] in bits 31..12. */
    private static int jImm(int inst) {
        return ((inst >> 31) << 20) | (inst & 0xF_F000) | ((inst >>> 9) & 0x800) | ((inst >>> 20) & 0x7FE);
    }

    /** S-type: imm[11:5] in bits 31..25, imm[4:0] in bits 11..7. */
    private static int sImm(int inst) {
        return ((inst >> 25) << 5) | ((inst >>> 7) & 31);
    }

    // ---- M division (unprivileged spec 13.2: no traps; Java's / and % throw on zero, so test first) ----

    /** x / 0 = -1; (-2^31) / -1 = -2^31, which Java's / already gives. */
    private static int div(int a, int b) {
        return b == 0 ? -1 : a / b;
    }

    private static int divu(int a, int b) {
        return b == 0 ? -1 : Integer.divideUnsigned(a, b);
    }

    /** x % 0 = x; (-2^31) % -1 = 0, which Java's % already gives. */
    private static int rem(int a, int b) {
        return b == 0 ? a : a % b;
    }

    private static int remu(int a, int b) {
        return b == 0 ? a : Integer.remainderUnsigned(a, b);
    }

    // ---- out-of-line execution, on the fields ----

    /**
     * Completes one instruction the loop did not: pc, cycles and instret are in the fields and
     * {@code cycles} is the count at the start of the instruction. Only reached for the opcodes
     * below and for words the loop found illegal, so everything else is illegal here.
     */
    private void exec(int inst) {
        if ((inst & 3) != 3) {
            illegal(inst);
            return;
        }
        final int rd = (inst >>> 7) & 31;
        final int rs1 = (inst >>> 15) & 31;
        final int f3 = (inst >>> 12) & 7;
        switch ((inst >>> 2) & 31) {
            case OPC_LOAD:
                load(inst, rd, x[rs1] + (inst >> 20), f3);
                return;
            case OPC_STORE:
                store(inst, x[rs1] + sImm(inst), x[(inst >>> 20) & 31], f3);
                return;
            case OPC_MISC_MEM:
                // FENCE (any fm, pred, succ, rd, rs1) and FENCE.I (fields ignored) are no-ops (spec 4.5).
                if (f3 > 1)
                    illegal(inst);
                else
                    retire(1);
                return;
            case OPC_BRANCH:
                branch(inst, x[rs1], x[(inst >>> 20) & 31], f3);
                return;
            case OPC_JAL:
                jump(rd, pc + jImm(inst));
                return;
            case OPC_JALR:
                if (f3 != 0)
                    illegal(inst);
                else
                    jump(rd, (x[rs1] + (inst >> 20)) & ~1);
                return;
            case OPC_SYSTEM:
                system(inst, rd, rs1, f3);
                return;
            default:
                illegal(inst);
        }
    }

    /** Moves past a completed instruction costing {@code cost} cycles. */
    private void retire(int cost) {
        pc += 4;
        cycles += cost;
        instret++;
        x[0] = 0;
    }

    private void branch(int inst, int a, int b, int f3) {
        final boolean taken;
        switch (f3) {
            case 0: taken = a == b; break;
            case 1: taken = a != b; break;
            case 4: taken = a < b; break;
            case 5: taken = a >= b; break;
            case 6: taken = Integer.compareUnsigned(a, b) < 0; break;
            case 7: taken = Integer.compareUnsigned(a, b) >= 0; break;
            default:
                illegal(inst);
                return;
        }
        if (!taken) {
            retire(1);
            return;
        }
        int target = pc + bImm(inst);
        if ((target & 3) != 0) {
            exception(CAUSE_FETCH_MISALIGNED, target, 1);
            return;
        }
        pc = target;
        cycles += 1 + JUMP_EXTRA;
        instret++;
    }

    /** JAL or JALR to {@code target}; a misaligned target traps on the jump and leaves rd alone (spec 4.5). */
    private void jump(int rd, int target) {
        if ((target & 3) != 0) {
            exception(CAUSE_FETCH_MISALIGNED, target, 1);
            return;
        }
        x[rd] = pc + 4;
        x[0] = 0;
        pc = target;
        cycles += 1 + JUMP_EXTRA;
        instret++;
    }

    /** Returns the array holding all {@code width} bytes at {@code addr} (ROM only if {@code read}), with {@link #fastOffset}; or null. */
    private byte[] fastArray(int addr, int width, boolean read) {
        int o = addr - romBase;
        if (Integer.compareUnsigned(o, romSize) < 0)
            return read && o + width <= romSize ? setOffset(rom, o) : null;
        o = addr - ramBase;
        if (Integer.compareUnsigned(o, ramSize) < 0)
            return o + width <= ramSize ? setOffset(ram, o) : null;
        o = addr - vramBase;
        if (Integer.compareUnsigned(o, vramSize) < 0)
            return o + width <= vramSize ? setOffset(vram, o) : null;
        return null;
    }

    private byte[] setOffset(byte[] arr, int o) {
        fastOffset = o;
        return arr;
    }

    private void load(int inst, int rd, int addr, int f3) {
        final int width;
        switch (f3) {
            case 0: case 4: width = 1; break;
            case 1: case 5: width = 2; break;
            case 2: width = 4; break;
            default:
                illegal(inst);
                return;
        }
        int cost = 1 + LOAD_EXTRA + ((addr & (width - 1)) != 0 ? MISALIGNED_EXTRA : 0);
        int v;
        boolean device = false;
        byte[] arr = fastArray(addr, width, true);
        if (arr != null) {
            int o = fastOffset;
            v = width == 1 ? arr[o] & 0xFF : width == 2 ? (short) LE16.get(arr, o) & 0xFFFF : (int) LE32.get(arr, o);
        } else {
            clock.advanceTo(cycles);
            try {
                v = width == 1 ? bus.read8(addr) : width == 2 ? bus.read16(addr) : bus.read32(addr);
            } catch (BusFault fault) {
                limit = clock.horizon();
                exception(CAUSE_LOAD_FAULT, addr, cost);
                return;
            }
            cost += Rv32Map.MMIO_EXTRA_CYCLES;
            device = true;
        }
        switch (f3) {
            case 0: v = (byte) v; break;
            case 1: v = (short) v; break;
            default: break;
        }
        x[rd] = v;
        retire(cost);
        if (device)
            afterDeviceAccess();
    }

    private void store(int inst, int addr, int v, int f3) {
        if (f3 > 2) {
            illegal(inst);
            return;
        }
        final int width = 1 << f3;
        int cost = 1 + ((addr & (width - 1)) != 0 ? MISALIGNED_EXTRA : 0);
        byte[] arr = fastArray(addr, width, false);
        if (arr != null) {
            int o = fastOffset;
            if (width == 1)
                arr[o] = (byte) v;
            else if (width == 2)
                LE16.set(arr, o, (short) v);
            else
                LE32.set(arr, o, v);
            if (arr == vram) {
                int end = o + width - 1;
                vramDirty[o >>> 18] |= 1L << ((o >>> 12) & 63);
                vramDirty[end >>> 18] |= 1L << ((end >>> 12) & 63);
            }
            retire(cost);
            return;
        }
        clock.advanceTo(cycles);
        try {
            if (width == 1)
                bus.write8(addr, v);
            else if (width == 2)
                bus.write16(addr, v);
            else
                bus.write32(addr, v);
        } catch (BusFault fault) {
            limit = clock.horizon();
            exception(CAUSE_STORE_FAULT, addr, cost);
            return;
        }
        retire(cost + Rv32Map.MMIO_EXTRA_CYCLES);
        afterDeviceAccess();
    }

    /** A device access may have lowered the horizon or changed a pin (spec 7.3). */
    private void afterDeviceAccess() {
        limit = clock.horizon();
        checkInterrupt();
    }

    private void system(int inst, int rd, int rs1, int f3) {
        if (f3 == 0) {
            switch (inst) {
                case ECALL:
                    exception(CAUSE_ECALL_M, 0, 1);
                    return;
                case EBREAK:
                    exception(CAUSE_BREAKPOINT, pc, 1);
                    return;
                case MRET:
                    pc = mepc;
                    mstatusMie = mstatusMpie;
                    mstatusMpie = true;
                    cycles += 1 + MRET_EXTRA;
                    instret++;
                    checkInterrupt();
                    return;
                case WFI:
                    retire(1);
                    if ((mip & mieBits) == 0)
                        waiting = true;
                    else
                        checkInterrupt();
                    return;
                default:
                    illegal(inst);
                    return;
            }
        }
        if (f3 == 4) {
            illegal(inst);
            return;
        }
        // Zicsr: funct3 1/5 RW, 2/6 RS, 3/7 RC; 5..7 take rs1 as a 5-bit immediate.
        final int csr = inst >>> 20;
        final int op = f3 & 3;
        final int src = f3 >= 5 ? rs1 : x[rs1];
        final boolean writes = op == 1 || rs1 != 0;
        final long old = readCsr(csr);
        if (old == NO_CSR || (writes && Rv32Csr.readOnly(csr))) {
            illegal(inst);
            return;
        }
        final long mcycleBefore = cycles + cycleOffset;
        final long minstretBefore = instret + instretOffset;
        x[rd] = (int) old;
        retire(1);
        if (writes) {
            int v = op == 1 ? src : op == 2 ? (int) old | src : (int) old & ~src;
            writeCsr(csr, v, mcycleBefore, minstretBefore);
        }
    }

    /** A CSR's value as an instruction starting now reads it, or {@link #NO_CSR}. No side effects. */
    private long readCsr(int csr) {
        switch (csr) {
            case Rv32Csr.MVENDORID:
            case Rv32Csr.MARCHID:
            case Rv32Csr.MHARTID:
            case Rv32Csr.MCONFIGPTR:
            case Rv32Csr.MSTATUSH:
            case Rv32Csr.MCOUNTINHIBIT:
                return 0;
            case Rv32Csr.MIMPID:
                return 1;
            case Rv32Csr.MSTATUS:
                return (mstatusMie ? MSTATUS_MIE : 0) | (mstatusMpie ? MSTATUS_MPIE : 0) | MSTATUS_MPP;
            case Rv32Csr.MISA:
                return mExtension ? MISA_I | MISA_M : MISA_I;
            case Rv32Csr.MIE:
                return mieBits;
            case Rv32Csr.MTVEC:
                return mtvec;
            case Rv32Csr.MSCRATCH:
                return mscratch;
            case Rv32Csr.MEPC:
                return mepc;
            case Rv32Csr.MCAUSE:
                return mcause;
            case Rv32Csr.MTVAL:
                return mtval;
            case Rv32Csr.MIP:
                return mip;
            case Rv32Csr.MCYCLE:
            case Rv32Csr.CYCLE:
                return (int) (cycles + cycleOffset);
            case Rv32Csr.MCYCLEH:
            case Rv32Csr.CYCLEH:
                return (int) ((cycles + cycleOffset) >>> 32);
            case Rv32Csr.MINSTRET:
            case Rv32Csr.INSTRET:
                return (int) (instret + instretOffset);
            case Rv32Csr.MINSTRETH:
            case Rv32Csr.INSTRETH:
                return (int) ((instret + instretOffset) >>> 32);
            case Rv32Csr.TIME:
                return (int) CycleClock.cyclesToMicros(cycles, clockHz);
            case Rv32Csr.TIMEH:
                return (int) (CycleClock.cyclesToMicros(cycles, clockHz) >>> 32);
            default:
                return Rv32Csr.hardwiredZero(csr) ? 0 : NO_CSR;
        }
    }

    /**
     * Writes a writable CSR after its instruction retired. A counter write sets what the next
     * instruction reads, so the writing instruction's own increment is suppressed; writing one
     * half keeps the other half as the instruction read it (spec 4.4).
     */
    private void writeCsr(int csr, int v, long mcycleBefore, long minstretBefore) {
        final long lo = v & 0xFFFF_FFFFL;
        switch (csr) {
            case Rv32Csr.MSTATUS:
                mstatusMie = (v & MSTATUS_MIE) != 0;
                mstatusMpie = (v & MSTATUS_MPIE) != 0;
                checkInterrupt();
                return;
            case Rv32Csr.MIE:
                mieBits = v & IRQ_MASK;
                checkInterrupt();
                return;
            case Rv32Csr.MTVEC:
                mtvec = (v & 3) >= 2 ? v & ~3 : v;
                return;
            case Rv32Csr.MSCRATCH:
                mscratch = v;
                return;
            case Rv32Csr.MEPC:
                mepc = v & ~3;
                return;
            case Rv32Csr.MCAUSE:
                mcause = v;
                return;
            case Rv32Csr.MTVAL:
                mtval = v;
                return;
            case Rv32Csr.MCYCLE:
                cycleOffset = ((mcycleBefore & 0xFFFF_FFFF_0000_0000L) | lo) - cycles;
                return;
            case Rv32Csr.MCYCLEH:
                cycleOffset = ((lo << 32) | (mcycleBefore & 0xFFFF_FFFFL)) - cycles;
                return;
            case Rv32Csr.MINSTRET:
                instretOffset = ((minstretBefore & 0xFFFF_FFFF_0000_0000L) | lo) - instret;
                return;
            case Rv32Csr.MINSTRETH:
                instretOffset = ((lo << 32) | (minstretBefore & 0xFFFF_FFFFL)) - instret;
                return;
            default:
                // misa, mip, mstatush, mcountinhibit and the hardwired-zero counters ignore writes.
        }
    }

    private void illegal(int inst) {
        exception(CAUSE_ILLEGAL, inst, 1);
    }

    /** The instruction at pc raises {@code cause} after costing {@code cost} cycles; it does not retire. */
    private void exception(int cause, int tval, int cost) {
        cycles += cost;
        trap(cause, tval, pc);
    }

    /** No fast array holds pc: an instruction access fault, or the double-fault halt (spec 4.5). */
    private void fetchFault() {
        if (trapArmed && instret == trapInstret) {
            halted = true;
            haltReason = String.format("trap handler at 0x%08x is not executable (mcause %d, mepc 0x%08x)",
                    pc, Integer.toUnsignedLong(mcause), mepc);
            return;
        }
        exception(CAUSE_FETCH_FAULT, pc, 1);
    }

    /** Takes the highest-priority pending interrupt if mstatus.MIE allows: MEI, then MSI, then MTI (spec 4.5). */
    private void checkInterrupt() {
        if (!mstatusMie)
            return;
        int pending = mip & mieBits;
        if (pending == 0)
            return;
        int cause = (pending & (1 << Rv32Map.IRQ_PIN_MEIP)) != 0 ? Rv32Map.IRQ_PIN_MEIP
                : (pending & (1 << Rv32Map.IRQ_PIN_MSIP)) != 0 ? Rv32Map.IRQ_PIN_MSIP
                : Rv32Map.IRQ_PIN_MTIP;
        trap(CAUSE_INTERRUPT | cause, 0, pc);
    }

    /** Trap entry: 2 cycles, mepc/mcause/mtval, MPIE = MIE, MIE = 0, pc to the handler (spec 4.5). */
    private void trap(int cause, int tval, int epc) {
        mepc = epc;
        mcause = cause;
        mtval = tval;
        mstatusMpie = mstatusMie;
        mstatusMie = false;
        int base = mtvec & ~3;
        pc = cause < 0 && (mtvec & 3) == 1 ? base + 4 * (cause & 0x7FFF_FFFF) : base;
        cycles += TRAP_EXTRA;
        trapArmed = true;
        trapInstret = instret;
    }

    @Override
    public void setIrq(int pin, boolean level) {
        if (pin != Rv32Map.IRQ_PIN_MSIP && pin != Rv32Map.IRQ_PIN_MTIP && pin != Rv32Map.IRQ_PIN_MEIP)
            throw new IllegalArgumentException("RV32I has no interrupt pin " + pin);
        if (level)
            mip |= 1 << pin;
        else
            mip &= ~(1 << pin);
    }

    @Override
    public boolean halted() {
        return halted;
    }

    @Override
    public String haltReason() {
        return haltReason;
    }

    @Override
    public int pc() {
        return pc;
    }

    @Override
    public long instructionsRetired() {
        return instret;
    }

    // ---- saved state (spec 1.5) ----

    /**
     * Version, M flag, x0..x31, pc, cycle and retired counts, the CSR fields, counter offsets and
     * the run state (waiting, halted with reason, the double-fault guard). RV32I has no A
     * extension, so there is no reservation to keep. The run limit and the memory map are set by
     * every run and not stored.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeBoolean(mExtension);
        for (int r = 0; r < 32; r++)
            out.writeInt(x[r]);
        out.writeInt(pc);
        out.writeLong(cycles);
        out.writeLong(instret);
        out.writeBoolean(mstatusMie);
        out.writeBoolean(mstatusMpie);
        out.writeInt(mieBits);
        out.writeInt(mip);
        out.writeInt(mtvec);
        out.writeInt(mscratch);
        out.writeInt(mepc);
        out.writeInt(mcause);
        out.writeInt(mtval);
        out.writeLong(cycleOffset);
        out.writeLong(instretOffset);
        out.writeBoolean(zeroCountersOnRun);
        out.writeBoolean(waiting);
        out.writeBoolean(halted);
        StateIo.writeNullableUtf(out, haltReason);
        out.writeBoolean(trapArmed);
        out.writeLong(trapInstret);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "RV32I CPU", STATE_VERSION);
        boolean m = in.readBoolean();
        StateIo.check(m == mExtension, "saved RV32I CPU " + (m ? "has" : "lacks")
                + " the M extension, this one " + (mExtension ? "has" : "lacks") + " it");
        for (int r = 0; r < 32; r++)
            x[r] = in.readInt();
        x[0] = 0;
        pc = in.readInt();
        cycles = in.readLong();
        instret = in.readLong();
        mstatusMie = in.readBoolean();
        mstatusMpie = in.readBoolean();
        mieBits = in.readInt() & IRQ_MASK;
        mip = in.readInt() & IRQ_MASK;
        mtvec = in.readInt();
        mscratch = in.readInt();
        mepc = in.readInt();
        mcause = in.readInt();
        mtval = in.readInt();
        cycleOffset = in.readLong();
        instretOffset = in.readLong();
        zeroCountersOnRun = in.readBoolean();
        waiting = in.readBoolean();
        halted = in.readBoolean();
        haltReason = StateIo.readNullableUtf(in);
        trapArmed = in.readBoolean();
        trapInstret = in.readLong();
    }

    // ---- debugging and tests (spec 7.3) ----

    public int reg(int index) {
        return x[index];
    }

    /** Writes to x0 are ignored. */
    public void setReg(int index, int value) {
        if (index != 0)
            x[index] = value;
    }

    /** Sets pc as the host; a fetch fault there is a plain fault, never the double-fault halt. */
    public void setPc(int pc) {
        this.pc = pc;
        trapArmed = false;
    }

    /** Debug read of an implemented CSR, no side effects; IllegalArgumentException if not implemented. */
    public int csr(int address) {
        long v = readCsr(address);
        if (v == NO_CSR)
            throw new IllegalArgumentException(String.format("CSR 0x%03x is not implemented", address));
        return (int) v;
    }

    /** The CPU's cycle count (mcycle without the write offset). */
    public long cycles() {
        return cycles;
    }
}
