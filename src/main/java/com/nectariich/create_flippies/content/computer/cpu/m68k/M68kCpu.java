package com.nectariich.create_flippies.content.computer.cpu.m68k;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.BusFault;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static com.nectariich.create_flippies.content.computer.cpu.m68k.M68kOpTable.*;

/**
 * The 68000 and 68040 integer core (spec 10.3): one class, parameterised by {@link M68kOptions},
 * running real 68k machine code from the bus's RAM, ROM and VRAM arrays.
 *
 * Structure: {@link #loop} runs instruction boundaries (horizon, interrupts, STOP, host deadline);
 * {@link #step} fetches one opcode word and dispatches on the model's op table
 * ({@link M68kOpTable}) through one dense switch whose cases call a private method per
 * instruction class. Model differences are final fields ({@code is040}, {@code addrMask}), so
 * the JIT treats them as constants per object.
 *
 * Faults: an address error, bus error or access error found inside an instruction builds its
 * exception frame at once and then throws {@link Abort} (a preallocated, stackless exception)
 * that unwinds the instruction to {@link #step}. Writes done before the fault stay done. A fault
 * while an exception frame is being built or a vector read (a double fault) halts the CPU.
 *
 * Deviations, all documented in the spec: no 68000 prefetch queue (a group 0 frame stacks the
 * instruction address + 2); the 68000's CLR, Scc and MOVE from SR do not read their destination
 * first; DIV overflow, divide by zero and CHK keep N, Z (and V) as they were; the 68040's timing
 * is the simple cost model of {@link M68kTiming}.
 *
 * Trace is taken inside the same {@link #step} as its instruction, so a run never ends between an
 * instruction and its trace and saved state needs no pending-trace field.
 */
public final class M68kCpu implements Cpu {

    /** Big-endian 16-bit view of a byte array; plain get and set accept any offset. */
    private static final VarHandle BE16 = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    /** Big-endian 32-bit view of a byte array. */
    private static final VarHandle BE32 = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /** Instructions between host-deadline checks (spec 1.4). */
    private static final int DEADLINE_STRIDE = 4096;
    /** Format version of {@link #saveState}. */
    private static final int STATE_VERSION = 1;

    private static final int XF = M68kSr.X;
    private static final int NF = M68kSr.N;
    private static final int ZF = M68kSr.Z;
    private static final int VF = M68kSr.V;
    private static final int CF = M68kSr.C;
    private static final int SF = M68kSr.S;
    private static final int MF = M68kSr.M;
    private static final int TRACE_BITS = M68kSr.T1 | M68kSr.T0;

    private static final int[] MASK = {0xFF, 0xFFFF, 0xFFFF_FFFF};
    private static final int[] MSB = {0x80, 0x8000, 0x8000_0000};
    private static final int[] BYTES = {1, 2, 4};

    /** MOVEC control register numbers (spec 10.2.3). */
    static final int CR_SFC = 0x000, CR_DFC = 0x001, CR_CACR = 0x002, CR_TC = 0x003, CR_ITT0 = 0x004,
            CR_ITT1 = 0x005, CR_DTT0 = 0x006, CR_DTT1 = 0x007, CR_USP = 0x800, CR_VBR = 0x801,
            CR_MSP = 0x803, CR_ISP = 0x804, CR_MMUSR = 0x805, CR_URP = 0x806, CR_SRP = 0x807;
    /** CACR bits that exist on the 68040: DE (31) and IE (15). */
    private static final int CACR_MASK = 0x8000_8000;
    /** TC bit 15 (E): paging can never be enabled (spec 10.5). */
    private static final int TC_ENABLE = 0x8000;

    /** Unwinds an instruction whose exception has been taken (or that halted the CPU). */
    private static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    private static final Abort ABORT = new Abort();

    private final Bus bus;
    private final CycleClock clock;
    private final Runnable resetDevices;
    private final M68kOptions options;
    private final boolean is040;
    /** Every bus address is masked with this: 24 bits on the 68000 (spec 10.2.2). */
    private final int addrMask;
    private final int srMask;
    private final short[] table;
    private final int[] eaTime;
    /** Cycles per data memory access: 0 on the 68000 (in its tables), 1 on the 68040. */
    private final int accessCost;
    private final M68kFpuUnit fpu;

    /** D0..D7 at 0..7, A0..A7 at 8..15; r[15] is the active stack pointer. */
    private final int[] r = new int[16];
    private int pc;
    /** Address of the instruction being executed. */
    private int instrPc;
    /** Its first word (the group 0 frame's instruction register). */
    private int opcode;
    /** CCR bits 4..0. */
    private int ccr;
    /** SR bits 15..8: trace, S, M, interrupt mask. */
    private int sys;
    private int usp;
    /** ISP on the 68040; the SSP on the 68000. */
    private int isp;
    private int msp;
    private int vbr;
    private int cacr;
    private int sfc;
    private int dfc;
    private int tc;
    private int itt0;
    private int itt1;
    private int dtt0;
    private int dtt1;
    private int urp;
    private int srp;
    private int mmusr;

    private long cycles;
    private long instret;
    /** The cycle to stop at: clock.horizon(), re-read after every device access. */
    private long limit;

    /** Pin levels, bit k for pin k (1..7); set by {@link #setIrq}, possibly inside a device access. */
    private int pins;
    /** A rising edge to level 7 not yet taken (spec 10.3.7). */
    private boolean nmiPending;
    /** Derived: nmiPending or the pin level above the mask. */
    private boolean irqPending;

    private boolean stopped;
    private boolean resetPending;
    private boolean halted;
    private String haltReason;

    // ---- per-instruction state ----

    /** The instruction changed the flow (for 68040 T0 trace). */
    private boolean flow;
    /** The instruction raised illegal, privilege, line A or line F: it does not retire and is not traced. */
    private boolean decodeFault;
    /** The instruction took its trace itself (STOP). */
    private boolean traced;
    /** An exception frame is being built: any fault now is a double fault and halts. */
    private boolean inException;
    /** A 68040 address error frame is being taken: an odd handler for it is a double fault. */
    private boolean inAddressError;
    /** Address computed by {@link #rmwRead} for the matching {@link #rmwWrite}. */
    private int eaSaved;
    /** The last effective address was PC relative: its reads are program references (PRM 2.2). */
    private boolean eaProgram;

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
    /** Fast-path bounds: an access of 2 or 4 bytes at offset o is fast when o <u these. */
    private int romW2, romW4, ramW2, ramW4, vramW2, vramW4;

    /**
     * @param host    the CPU's connections; {@code memory}, {@code clock} and {@code resetDevices} are used
     * @param options model and strict FPU flag; must match {@code host.model()}
     * @param fpu     builds the FPU: null on the 68000, required on the 68040
     */
    public M68kCpu(CpuHost host, M68kOptions options, M68kFpuUnit.Factory fpu) {
        if (host.model() != options.model())
            throw new IllegalArgumentException("host is a " + host.model().displayName() + ", options a " + options.model().displayName());
        if (options.hasFpu() != (fpu != null))
            throw new IllegalArgumentException(options.hasFpu() ? "a 68040 needs an FPU factory" : "a 68000 has no FPU");
        this.bus = host.memory();
        this.clock = host.clock();
        this.resetDevices = host.resetDevices();
        this.options = options;
        this.is040 = options.model() == CpuModel.CPU_68040;
        this.addrMask = is040 ? -1 : M68kMap.ADDRESS_MASK_68000;
        this.srMask = is040 ? M68kSr.MASK_68040 : M68kSr.MASK_68000;
        this.table = M68kOpTable.table(is040);
        this.eaTime = is040 ? M68kTiming.EA_68040 : M68kTiming.EA_68000;
        this.accessCost = is040 ? 1 : 0;
        this.fpu = fpu == null ? null : fpu.create(new FpuHost(), options.strictFpu());
        reset();
    }

    @Override
    public CpuModel model() {
        return options.model();
    }

    public M68kOptions options() {
        return options;
    }

    /**
     * Spec 10.3.8: registers to 0, SR 0x2700, VBR and CACR 0, FPU to its null state; the reset
     * vectors are read by the next run. Pin levels are external and kept; a held level 7 is not
     * a new edge.
     */
    @Override
    public void reset() {
        Arrays.fill(r, 0);
        pc = 0;
        instrPc = 0;
        opcode = 0;
        ccr = 0;
        sys = M68kSr.RESET & 0xFF00;
        usp = isp = msp = 0;
        vbr = cacr = sfc = dfc = tc = itt0 = itt1 = dtt0 = dtt1 = urp = srp = mmusr = 0;
        instret = 0;
        cycles = clock.now();
        nmiPending = false;
        stopped = false;
        halted = false;
        haltReason = null;
        resetPending = true;
        inException = false;
        if (fpu != null)
            fpu.reset();
        recomputeIrq();
    }

    @Override
    public Exit run(long untilCycle, long hostDeadlineNanos) {
        clock.setHorizon(untilCycle);
        if (halted)
            return Exit.HALTED;
        cycles = clock.now();
        limit = clock.horizon();
        if (cycles >= limit)
            return Exit.REACHED;
        if (stopped && !irqPending)
            return Exit.WAITING;
        loadMemoryMap();
        Exit exit;
        if (resetPending && !readResetVectors())
            exit = Exit.HALTED;
        else
            exit = loop(hostDeadlineNanos);
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
        romW2 = Math.max(0, romSize - 1);
        romW4 = Math.max(0, romSize - 3);
        ramW2 = Math.max(0, ramSize - 1);
        ramW4 = Math.max(0, ramSize - 3);
        vramW2 = Math.max(0, vramSize - 1);
        vramW4 = Math.max(0, vramSize - 3);
    }

    /** Spec 10.3.8: SSP from 0 and PC from 4; a fault or an odd PC halts. */
    private boolean readResetVectors() {
        resetPending = false;
        inException = true;
        try {
            int sp = read32(0);
            int newPc = read32(4);
            inException = false;
            if ((newPc & 1) != 0) {
                halt(String.format("reset PC 0x%08x is odd", newPc));
                return false;
            }
            r[15] = sp;
            pc = newPc;
            cycles += is040 ? M68kTiming.C040_RESET_SEQUENCE : M68kTiming.RESET_SEQUENCE;
            return true;
        } catch (Abort a) {
            inException = false;
            haltReason = "reset vector unreadable: " + haltReason;
            return false;
        }
    }

    private Exit loop(long deadline) {
        int countdown = DEADLINE_STRIDE;
        for (;;) {
            if (cycles >= limit)
                return Exit.REACHED;
            if (irqPending) {
                interrupt();
                if (halted)
                    return Exit.HALTED;
                continue;
            }
            if (stopped)
                return Exit.WAITING;
            if (--countdown == 0) {
                countdown = DEADLINE_STRIDE;
                if (System.nanoTime() > deadline)
                    return Exit.DEADLINE;
            }
            step();
            if (halted)
                return Exit.HALTED;
        }
    }

    /** One instruction and, if due, its trace (spec 10.3.7). */
    private void step() {
        final int trace = sys & TRACE_BITS;
        flow = false;
        decodeFault = false;
        traced = false;
        eaProgram = false;
        instrPc = pc;
        try {
            if ((pc & 1) != 0)
                addressErrorFetch(pc);
            int op = fetch16();
            opcode = op;
            execute(op);
            if (decodeFault)
                return;
            instret++;
            if (trace != 0 && !traced && ((trace & M68kSr.T1) != 0 || flow))
                takeTrace();
        } catch (Abort a) {
            // the exception is already taken, or the CPU halted
        }
    }

    private void takeTrace() {
        exception(M68kVectors.TRACE, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.EXCEPTION);
        stopped = false;
    }

    // ---- dispatch ----

    private void execute(int op) {
        switch (table[op]) {
            case ILLEGAL: case ILLEGAL_OP: illegal(); break;
            case LINE_A: decodeException(M68kVectors.LINE_A); break;
            case LINE_F: decodeException(M68kVectors.LINE_F); break;
            case ORI_CCR: ccrImmediate(0); break;
            case ANDI_CCR: ccrImmediate(1); break;
            case EORI_CCR: ccrImmediate(2); break;
            case ORI_SR: srImmediate(0); break;
            case ANDI_SR: srImmediate(1); break;
            case EORI_SR: srImmediate(2); break;
            case ORI: case ANDI: case SUBI: case ADDI: case EORI: immediateOp(op); break;
            case CMPI: cmpi(op); break;
            case BTST_IMM: case BCHG_IMM: case BCLR_IMM: case BSET_IMM: bitOp(op, true); break;
            case BTST_REG: case BCHG_REG: case BCLR_REG: case BSET_REG: bitOp(op, false); break;
            case MOVEP: movep(op); break;
            case CMP2_CHK2: cmp2(op); break;
            case CAS: cas(op); break;
            case CAS2: cas2(op); break;
            case MOVES: moves(op); break;
            case MOVE_B: move(op, 0); break;
            case MOVE_W: move(op, 1); break;
            case MOVE_L: move(op, 2); break;
            case MOVEA_W: movea(op, 1); break;
            case MOVEA_L: movea(op, 2); break;
            case MOVE_FROM_SR: moveFromSr(op, true); break;
            case MOVE_FROM_CCR: moveFromSr(op, false); break;
            case MOVE_TO_CCR: moveToCcr(op); break;
            case MOVE_TO_SR: moveToSr(op); break;
            case NEGX: case CLR: case NEG: case NOT: singleOp(op); break;
            case NBCD: nbcd(op); break;
            case LINK_L: link(op & 7, fetch32()); break;
            case LINK_W: link(op & 7, (short) fetch16()); break;
            case UNLK: unlk(op & 7); break;
            case SWAP: swap(op & 7); break;
            case PEA: pea(op); break;
            case EXT_W: ext(op & 7, 1); break;
            case EXT_L: ext(op & 7, 2); break;
            case EXTB_L: ext(op & 7, 3); break;
            case MOVEM_TO_MEM: movemToMem(op); break;
            case MOVEM_TO_REG: movemToReg(op); break;
            case TST: tst(op); break;
            case TAS: tas(op); break;
            case MUL_L: mulL(op); break;
            case DIV_L: divL(op); break;
            case TRAP: exception(M68kVectors.TRAP_BASE + (op & 15), M68kVectors.FORMAT_0, pc, 0, M68kTiming.EXCEPTION); break;
            case MOVE_TO_USP: moveUsp(op, true); break;
            case MOVE_FROM_USP: moveUsp(op, false); break;
            case RESET: resetInstruction(); break;
            case NOP: charge(4, M68kTiming.C040_BASE); break;
            case STOP: stop(); break;
            case RTE: rte(); break;
            case RTD: rtd(); break;
            case RTS: rts(); break;
            case TRAPV: trapv(); break;
            case RTR: rtr(); break;
            case MOVEC: movec(op); break;
            case JSR: jsr(op); break;
            case JMP: jmp(op); break;
            case CHK_W: chk(op, 1); break;
            case CHK_L: chk(op, 2); break;
            case LEA: lea(op); break;
            case ADDQ: case SUBQ: addqSubq(op); break;
            case ADDQ_A: case SUBQ_A: addqSubqA(op); break;
            case SCC: scc(op); break;
            case DBCC: dbcc(op); break;
            case TRAPCC: trapcc(op); break;
            case BRA: case BCC: bcc(op); break;
            case BSR: bsr(op); break;
            case MOVEQ: moveq(op); break;
            case OR_EA_DN: case AND_EA_DN: logicEaDn(op); break;
            case OR_DN_EA: case AND_DN_EA: logicDnEa(op); break;
            case EOR: eor(op); break;
            case DIVU_W: divW(op, false); break;
            case DIVS_W: divW(op, true); break;
            case MULU_W: mulW(op, false); break;
            case MULS_W: mulW(op, true); break;
            case SBCD_R: case ABCD_R: bcdReg(op); break;
            case SBCD_M: case ABCD_M: bcdMem(op); break;
            case PACK_R: pack(op, false); break;
            case PACK_M: pack(op, true); break;
            case UNPK_R: unpk(op, false); break;
            case UNPK_M: unpk(op, true); break;
            case ADD_EA_DN: case SUB_EA_DN: arithEaDn(op); break;
            case ADD_DN_EA: case SUB_DN_EA: arithDnEa(op); break;
            case ADDA: case SUBA: addaSuba(op); break;
            case ADDX_R: case SUBX_R: extendReg(op); break;
            case ADDX_M: case SUBX_M: extendMem(op); break;
            case CMP: cmp(op); break;
            case CMPA: cmpa(op); break;
            case CMPM: cmpm(op); break;
            case EXG: exg(op); break;
            case SHIFT_REG: shiftReg(op); break;
            case SHIFT_MEM: shiftMem(op); break;
            case BITFIELD: bitfield(op); break;
            case FPU_GENERAL: charge(0, M68kTiming.C040_BASE); cycles += fpu.general(op); break;
            case FSCC: fscc(op); break;
            case FDBCC: fdbcc(op); break;
            case FTRAPCC: ftrapcc(op); break;
            case FBCC_W: fbcc(op, false); break;
            case FBCC_L: fbcc(op, true); break;
            case FSAVE: if (privileged()) { charge(0, M68kTiming.C040_BASE); cycles += fpu.save(op); } break;
            case FRESTORE: if (privileged()) { charge(0, M68kTiming.C040_BASE); cycles += fpu.restore(op); } break;
            case CINV_CPUSH: case PFLUSH: if (privileged()) charge(0, M68kTiming.C040_BASE); break;
            case PTEST: ptest(op); break;
            case MOVE16: move16(op); break;
            default: illegal(); break;
        }
    }

    /** Adds the instruction's base cost of the running model. */
    private void charge(int c68000, int c68040) {
        cycles += is040 ? c68040 : c68000;
    }

    private boolean supervisor() {
        return (sys & SF) != 0;
    }

    /** True in supervisor mode; otherwise takes the privilege violation and returns false. */
    private boolean privileged() {
        if ((sys & SF) != 0)
            return true;
        decodeException(M68kVectors.PRIVILEGE_VIOLATION);
        return false;
    }

    private void illegal() {
        decodeException(M68kVectors.ILLEGAL_INSTRUCTION);
    }

    /** Illegal, privilege violation, line A, line F: format 0, PC = the instruction; no retire, no trace. */
    private void decodeException(int vector) {
        decodeFault = true;
        exception(vector, M68kVectors.FORMAT_0, instrPc, 0, M68kTiming.EXCEPTION);
    }

    // ---- instruction stream ----

    /** The next instruction word, zero-extended; advances pc by 2. */
    private int fetch16() {
        final int p = pc;
        final int a = p & addrMask;
        int o;
        int v;
        if (Integer.compareUnsigned(o = a - romBase, romW2) < 0)
            v = (short) BE16.get(rom, o) & 0xFFFF;
        else if (Integer.compareUnsigned(o = a - ramBase, ramW2) < 0)
            v = (short) BE16.get(ram, o) & 0xFFFF;
        else if (Integer.compareUnsigned(o = a - vramBase, vramW2) < 0)
            v = (short) BE16.get(vram, o) & 0xFFFF;
        else
            v = slowRead(p, a, 2, true);
        pc = p + 2;
        return v;
    }

    private int fetch32() {
        int hi = fetch16();
        return (hi << 16) | fetch16();
    }

    /** Immediate data of size {@code sz}: a byte is the low byte of a word. */
    private int immediate(int sz) {
        return sz == 0 ? fetch16() & 0xFF : sz == 1 ? fetch16() : fetch32();
    }

    // ---- data memory (spec 10.3.5) ----

    private int read8(int addr) {
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(o = a - romBase, romSize) < 0)
            return rom[o] & 0xFF;
        if (Integer.compareUnsigned(o = a - ramBase, ramSize) < 0)
            return ram[o] & 0xFF;
        if (Integer.compareUnsigned(o = a - vramBase, vramSize) < 0)
            return vram[o] & 0xFF;
        return slowRead(addr, a, 1, false);
    }

    private int read16(int addr) {
        if ((addr & 1) != 0 && !is040)
            addressErrorData(addr, true);
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(o = a - romBase, romW2) < 0)
            return (short) BE16.get(rom, o) & 0xFFFF;
        if (Integer.compareUnsigned(o = a - ramBase, ramW2) < 0)
            return (short) BE16.get(ram, o) & 0xFFFF;
        if (Integer.compareUnsigned(o = a - vramBase, vramW2) < 0)
            return (short) BE16.get(vram, o) & 0xFFFF;
        return slowRead(addr, a, 2, false);
    }

    private int read32(int addr) {
        if ((addr & 1) != 0 && !is040)
            addressErrorData(addr, true);
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(o = a - romBase, romW4) < 0)
            return (int) BE32.get(rom, o);
        if (Integer.compareUnsigned(o = a - ramBase, ramW4) < 0)
            return (int) BE32.get(ram, o);
        if (Integer.compareUnsigned(o = a - vramBase, vramW4) < 0)
            return (int) BE32.get(vram, o);
        return slowRead(addr, a, 4, false);
    }

    private void write8(int addr, int v) {
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(a - romBase, romSize) >= 0) {
            if (Integer.compareUnsigned(o = a - ramBase, ramSize) < 0) {
                ram[o] = (byte) v;
                return;
            }
            if (Integer.compareUnsigned(o = a - vramBase, vramSize) < 0) {
                vram[o] = (byte) v;
                vramDirty[o >>> 18] |= 1L << ((o >>> 12) & 63);
                return;
            }
        }
        slowWrite(addr, a, 1, v);
    }

    private void write16(int addr, int v) {
        if ((addr & 1) != 0 && !is040)
            addressErrorData(addr, false);
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(a - romBase, romSize) >= 0) {
            if (Integer.compareUnsigned(o = a - ramBase, ramW2) < 0) {
                BE16.set(ram, o, (short) v);
                return;
            }
            if (Integer.compareUnsigned(o = a - vramBase, vramW2) < 0) {
                BE16.set(vram, o, (short) v);
                dirty(o, 2);
                return;
            }
        }
        slowWrite(addr, a, 2, v);
    }

    private void write32(int addr, int v) {
        if ((addr & 1) != 0 && !is040)
            addressErrorData(addr, false);
        cycles += accessCost;
        final int a = addr & addrMask;
        int o;
        if (Integer.compareUnsigned(a - romBase, romSize) >= 0) {
            if (Integer.compareUnsigned(o = a - ramBase, ramW4) < 0) {
                BE32.set(ram, o, v);
                return;
            }
            if (Integer.compareUnsigned(o = a - vramBase, vramW4) < 0) {
                BE32.set(vram, o, v);
                dirty(o, 4);
                return;
            }
        }
        slowWrite(addr, a, 4, v);
    }

    private void dirty(int o, int width) {
        int end = o + width - 1;
        vramDirty[o >>> 18] |= 1L << ((o >>> 12) & 63);
        vramDirty[end >>> 18] |= 1L << ((end >>> 12) & 63);
    }

    /** Device or unmapped access: publish the clock, go through the bus, charge the device surcharge. */
    private int slowRead(int addr, int a, int width, boolean fetch) {
        clock.advanceTo(cycles);
        int v;
        try {
            v = width == 1 ? bus.read8(a) : width == 2 ? bus.read16(a) : bus.read32(a);
        } catch (BusFault fault) {
            limit = clock.horizon();
            busError(addr, true, width, fetch);
            return 0;
        }
        cycles += M68kMap.MMIO_EXTRA_CYCLES;
        limit = clock.horizon();
        return v;
    }

    private void slowWrite(int addr, int a, int width, int v) {
        clock.advanceTo(cycles);
        try {
            if (width == 1)
                bus.write8(a, v);
            else if (width == 2)
                bus.write16(a, v);
            else
                bus.write32(a, v);
        } catch (BusFault fault) {
            limit = clock.horizon();
            busError(addr, false, width, false);
            return;
        }
        cycles += M68kMap.MMIO_EXTRA_CYCLES;
        limit = clock.horizon();
    }

    private int readSz(int addr, int sz) {
        return sz == 0 ? read8(addr) : sz == 1 ? read16(addr) : read32(addr);
    }

    private void writeSz(int addr, int sz, int v) {
        if (sz == 0)
            write8(addr, v);
        else if (sz == 1)
            write16(addr, v);
        else
            write32(addr, v);
    }

    private void push16(int v) {
        int sp = r[15] - 2;
        r[15] = sp;
        write16(sp, v);
    }

    private void push32(int v) {
        int sp = r[15] - 4;
        r[15] = sp;
        write32(sp, v);
    }

    private int pop16() {
        int sp = r[15];
        int v = read16(sp);
        r[15] = sp + 2;
        return v;
    }

    private int pop32() {
        int sp = r[15];
        int v = read32(sp);
        r[15] = sp + 4;
        return v;
    }

    // ---- registers at a size ----

    private void setD(int reg, int sz, int v) {
        if (sz == 2)
            r[reg] = v;
        else if (sz == 1)
            r[reg] = (r[reg] & 0xFFFF_0000) | (v & 0xFFFF);
        else
            r[reg] = (r[reg] & 0xFFFF_FF00) | (v & 0xFF);
    }

    private static int sext(int v, int sz) {
        return sz == 0 ? (byte) v : sz == 1 ? (short) v : v;
    }

    // ---- effective addresses (spec 10.3.3) ----

    /**
     * The address of memory mode {@code mode} (2..7), register {@code reg}, for an operand of
     * {@code bytes} bytes, with every side effect done once: (An)+ and -(An) move An (a byte
     * operand moves A7 by 2), extension words are fetched, PC-relative modes use the address of
     * their first extension word. Immediate data gives its address in the instruction stream.
     * Reserved encodings take the illegal instruction exception.
     */
    private int eaAddr(int mode, int reg, int bytes) {
        final int an = 8 + reg;
        eaProgram = mode == 7 && (reg == 2 || reg == 3);
        switch (mode) {
            case 2:
                return r[an];
            case 3: {
                int a = r[an];
                r[an] = a + (bytes == 1 && reg == 7 ? 2 : bytes);
                return a;
            }
            case 4: {
                int a = r[an] - (bytes == 1 && reg == 7 ? 2 : bytes);
                r[an] = a;
                return a;
            }
            case 5:
                return r[an] + (short) fetch16();
            case 6:
                return indexed(r[an]);
            case 7:
                switch (reg) {
                    case 0:
                        return (short) fetch16();
                    case 1:
                        return fetch32();
                    case 2: {
                        int base = pc;
                        return base + (short) fetch16();
                    }
                    case 3:
                        return indexed(pc);
                    case 4: {
                        int a = pc;
                        pc = a + ((bytes + 1) & ~1);
                        return bytes == 1 ? a + 1 : a;
                    }
                    default:
                        illegal();
                        throw ABORT;
                }
            default:
                throw new IllegalArgumentException("EA mode " + mode + " is not a memory mode");
        }
    }

    /** {@link #eaAddr} plus the 68000's EA calculation time for an operand of size {@code sz}. */
    private int eaAddrT(int mode, int reg, int sz) {
        cycles += eaTime[((mode < 7 ? mode : 7 + reg) << 1) + (sz >> 1)];
        return eaAddr(mode, reg, BYTES[sz]);
    }

    /**
     * Indexed modes. {@code base} is An or the address of the extension word (PC-relative). The
     * 68000 reads only the brief format and ignores bits 10..8; the 68040 adds scale and the full
     * format with base and index suppress, base displacement and memory indirection.
     */
    private int indexed(int base) {
        final int ext = fetch16();
        if (!is040 || (ext & 0x100) == 0) {
            int idx = r[(ext >>> 12) & 15];
            if ((ext & 0x800) == 0)
                idx = (short) idx;
            if (is040)
                idx <<= (ext >> 9) & 3;
            return base + idx + (byte) ext;
        }
        final int bdSize = (ext >> 4) & 3;
        final boolean is = (ext & 0x40) != 0;
        final int iis = ext & 7;
        if ((ext & 8) != 0 || bdSize == 0 || iis == 4 || (is && iis > 4)) {
            illegal();
            throw ABORT;
        }
        int bd = bdSize == 2 ? (short) fetch16() : bdSize == 3 ? fetch32() : 0;
        if ((ext & 0x80) != 0)
            base = 0;
        int idx = 0;
        if (!is) {
            idx = r[(ext >>> 12) & 15];
            if ((ext & 0x800) == 0)
                idx = (short) idx;
            idx <<= (ext >> 9) & 3;
        }
        if (iis == 0)
            return base + bd + idx;
        int odSize = iis & 3;
        if (iis < 4) {
            int mid = read32(base + bd + idx);
            int od = odSize == 2 ? (short) fetch16() : odSize == 3 ? fetch32() : 0;
            return mid + od;
        }
        int mid = read32(base + bd);
        int od = odSize == 2 ? (short) fetch16() : odSize == 3 ? fetch32() : 0;
        return mid + idx + od;
    }

    /** Reads a general source operand of size {@code sz}, zero-extended, charging its EA time. */
    private int readEa(int mode, int reg, int sz) {
        switch (mode) {
            case 0:
                return r[reg] & MASK[sz];
            case 1:
                return r[8 + reg] & MASK[sz];
            case 7:
                if (reg == 4) {
                    cycles += eaTime[22 + (sz >> 1)];
                    return immediate(sz);
                }
                return readSz(eaAddrT(mode, reg, sz), sz);
            default:
                return readSz(eaAddrT(mode, reg, sz), sz);
        }
    }

    /** Reads a read-modify-write operand (Dn or memory), keeping its address for {@link #rmwWrite}. */
    private int rmwRead(int mode, int reg, int sz) {
        if (mode == 0)
            return r[reg] & MASK[sz];
        eaSaved = eaAddrT(mode, reg, sz);
        return readSz(eaSaved, sz);
    }

    private void rmwWrite(int mode, int reg, int sz, int v) {
        if (mode == 0)
            setD(reg, sz, v);
        else
            writeSz(eaSaved, sz, v);
    }

    /** Writes a destination (Dn or memory) without reading it; charges the EA time when {@code timed}. */
    private void writeEa(int mode, int reg, int sz, int v, boolean timed) {
        if (mode == 0) {
            setD(reg, sz, v);
            return;
        }
        writeSz(timed ? eaAddrT(mode, reg, sz) : eaAddr(mode, reg, BYTES[sz]), sz, v);
    }

    private static int kind(int mode, int reg) {
        return mode < 7 ? mode : 7 + reg;
    }

    // ---- condition codes (spec 10.3.4) ----

    /** N and Z of {@code v} at {@code sz}; V = C = 0; X unchanged. */
    private void nz(int v, int sz) {
        ccr = (ccr & XF) | ((v & MSB[sz]) != 0 ? NF : 0) | ((v & MASK[sz]) == 0 ? ZF : 0);
    }

    /** d + s at sz: X = C = carry, V, N, Z. Returns the result masked to the size. */
    private int addF(int s, int d, int sz) {
        int res = d + s;
        int m = MSB[sz];
        int c = ((s & d) | (~res & (s | d))) & m;
        int v = (s ^ res) & (d ^ res) & m;
        res &= MASK[sz];
        ccr = (c != 0 ? XF | CF : 0) | (v != 0 ? VF : 0) | ((res & m) != 0 ? NF : 0) | (res == 0 ? ZF : 0);
        return res;
    }

    /** d - s at sz: X = C = borrow, V, N, Z. */
    private int subF(int s, int d, int sz) {
        int res = d - s;
        int m = MSB[sz];
        int c = ((s & ~d) | (res & ~d) | (s & res)) & m;
        int v = (s ^ d) & (res ^ d) & m;
        res &= MASK[sz];
        ccr = (c != 0 ? XF | CF : 0) | (v != 0 ? VF : 0) | ((res & m) != 0 ? NF : 0) | (res == 0 ? ZF : 0);
        return res;
    }

    /** CMP: d - s flags with X unchanged. */
    private void cmpF(int s, int d, int sz) {
        int x = ccr & XF;
        subF(s, d, sz);
        ccr = (ccr & ~XF) | x;
    }

    /** ADDX: d + s + X; Z cleared if the result is nonzero, else unchanged. */
    private int addxF(int s, int d, int sz) {
        int z = ccr & ZF;
        int res = d + s + ((ccr >> 4) & 1);
        int m = MSB[sz];
        int c = ((s & d) | (~res & (s | d))) & m;
        int v = (s ^ res) & (d ^ res) & m;
        res &= MASK[sz];
        ccr = (c != 0 ? XF | CF : 0) | (v != 0 ? VF : 0) | ((res & m) != 0 ? NF : 0) | (res == 0 ? z : 0);
        return res;
    }

    /** SUBX and NEGX: d - s - X; Z cleared if the result is nonzero, else unchanged. */
    private int subxF(int s, int d, int sz) {
        int z = ccr & ZF;
        int res = d - s - ((ccr >> 4) & 1);
        int m = MSB[sz];
        int c = ((s & ~d) | (res & ~d) | (s & res)) & m;
        int v = (s ^ d) & (res ^ d) & m;
        res &= MASK[sz];
        ccr = (c != 0 ? XF | CF : 0) | (v != 0 ? VF : 0) | ((res & m) != 0 ? NF : 0) | (res == 0 ? z : 0);
        return res;
    }

    private boolean cond(int cc) {
        return M68kFlags.test(cc, ccr);
    }

    // ---- moves ----

    private void move(int op, int sz) {
        int v = readEa((op >> 3) & 7, op & 7, sz);
        int dm = (op >> 6) & 7;
        int dr = (op >> 9) & 7;
        writeEa(dm, dr, sz, v, false);
        nz(v, sz);
        if (is040)
            cycles += M68kTiming.C040_BASE;
        else
            cycles += 4 + M68kTiming.MOVE_DEST_68000[(kind(dm, dr) << 1) + (sz >> 1)];
    }

    private void movea(int op, int sz) {
        int v = readEa((op >> 3) & 7, op & 7, sz);
        r[8 + ((op >> 9) & 7)] = sz == 1 ? (short) v : v;
        charge(4, M68kTiming.C040_BASE);
    }

    private void moveq(int op) {
        int v = (byte) op;
        r[(op >> 9) & 7] = v;
        ccr = (ccr & XF) | (v < 0 ? NF : 0) | (v == 0 ? ZF : 0);
        charge(4, M68kTiming.C040_BASE);
    }

    private void moveFromSr(int op, boolean whole) {
        if (whole && is040 && !privileged())
            return;
        int v = whole ? getSr() : ccr;
        int mode = (op >> 3) & 7;
        writeEa(mode, op & 7, 1, v, true);
        charge(mode == 0 ? 6 : 8, M68kTiming.C040_BASE);
    }

    private void moveToCcr(int op) {
        ccr = readEa((op >> 3) & 7, op & 7, 1) & M68kSr.CCR_MASK;
        charge(12, M68kTiming.C040_BASE);
    }

    private void moveToSr(int op) {
        if (!privileged())
            return;
        setSr(readEa((op >> 3) & 7, op & 7, 1));
        flow = true;
        charge(12, M68kTiming.C040_BASE);
    }

    private void moveUsp(int op, boolean toUsp) {
        if (!privileged())
            return;
        if (toUsp)
            usp = r[8 + (op & 7)];
        else
            r[8 + (op & 7)] = usp;
        charge(4, M68kTiming.C040_BASE);
    }

    private void exg(int op) {
        int rx = (op >> 9) & 7;
        int ry = op & 7;
        int opmode = (op >> 3) & 0x1F;
        int a = opmode == 0x08 ? rx : rx + (opmode == 0x09 ? 8 : 0);
        int b = opmode == 0x08 ? ry : ry + 8;
        if (opmode == 0x11)
            a = rx;
        int t = r[a];
        r[a] = r[b];
        r[b] = t;
        charge(6, M68kTiming.C040_BASE);
    }

    private void swap(int reg) {
        int v = Integer.rotateLeft(r[reg], 16);
        r[reg] = v;
        nz(v, 2);
        charge(4, M68kTiming.C040_BASE);
    }

    /** EXT.W (sz 1), EXT.L (sz 2), EXTB.L (sz 3). */
    private void ext(int reg, int kindOf) {
        int v = r[reg];
        if (kindOf == 1) {
            v = (byte) v;
            setD(reg, 1, v);
            nz(v, 1);
        } else {
            v = kindOf == 2 ? (short) v : (byte) v;
            r[reg] = v;
            nz(v, 2);
        }
        charge(4, M68kTiming.C040_BASE);
    }

    private void lea(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        r[8 + ((op >> 9) & 7)] = eaAddr(mode, reg, 4);
        charge(M68kTiming.LEA[kind(mode, reg)], M68kTiming.C040_BASE);
    }

    private void pea(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        push32(eaAddr(mode, reg, 4));
        charge(M68kTiming.PEA[kind(mode, reg)], M68kTiming.C040_BASE);
    }

    private void link(int reg, int disp) {
        int sp = r[15] - 4;
        int v = reg == 7 ? sp : r[8 + reg];
        r[15] = sp;
        write32(sp, v);
        r[8 + reg] = sp;
        r[15] += disp;
        charge(16, M68kTiming.C040_BASE);
    }

    private void unlk(int reg) {
        r[15] = r[8 + reg];
        r[8 + reg] = pop32();
        charge(12, M68kTiming.C040_BASE);
    }

    private void movep(int op) {
        int dreg = (op >> 9) & 7;
        int addr = r[8 + (op & 7)] + (short) fetch16();
        int opmode = (op >> 6) & 3;
        boolean lng = (opmode & 1) != 0;
        int n = lng ? 4 : 2;
        if (opmode < 2) {
            int v = 0;
            for (int i = 0; i < n; i++)
                v = (v << 8) | read8(addr + 2 * i);
            setD(dreg, lng ? 2 : 1, v);
        } else {
            int v = r[dreg];
            for (int i = 0; i < n; i++)
                write8(addr + 2 * i, v >>> (8 * (n - 1 - i)));
        }
        charge(lng ? 24 : 16, M68kTiming.C040_BASE);
    }

    private void movemToMem(int op) {
        int mask = fetch16();
        int mode = (op >> 3) & 7, reg = op & 7;
        int sz = (op & 0x40) != 0 ? 2 : 1;
        int bytes = BYTES[sz];
        int count = Integer.bitCount(mask);
        if (mode == 4) {
            int an = 8 + reg;
            int start = r[an];
            int addr = start;
            for (int b = 0; b < 16; b++) {
                if ((mask & (1 << b)) == 0)
                    continue;
                int rn = 15 - b;
                addr -= bytes;
                int v = rn == an ? (is040 ? start - bytes : start) : r[rn];
                writeSz(addr, sz, v);
            }
            r[an] = addr;
        } else {
            int addr = eaAddr(mode, reg, bytes);
            for (int rn = 0; rn < 16; rn++) {
                if ((mask & (1 << rn)) == 0)
                    continue;
                writeSz(addr, sz, r[rn]);
                addr += bytes;
            }
        }
        charge(M68kTiming.MOVEM_TO_MEM[kind(mode, reg)] + count * (sz == 2 ? 8 : 4), M68kTiming.C040_BASE);
    }

    private void movemToReg(int op) {
        int mask = fetch16();
        int mode = (op >> 3) & 7, reg = op & 7;
        int sz = (op & 0x40) != 0 ? 2 : 1;
        int bytes = BYTES[sz];
        int count = Integer.bitCount(mask);
        int addr = mode == 3 ? r[8 + reg] : eaAddr(mode, reg, bytes);
        for (int rn = 0; rn < 16; rn++) {
            if ((mask & (1 << rn)) == 0)
                continue;
            int v = readSz(addr, sz);
            r[rn] = sz == 1 ? (short) v : v;
            addr += bytes;
        }
        if (mode == 3)
            r[8 + reg] = addr;
        charge(M68kTiming.MOVEM_TO_REG[kind(mode, reg)] + count * (sz == 2 ? 8 : 4), M68kTiming.C040_BASE);
    }

    // ---- arithmetic and logic ----

    private static boolean regOrImm(int mode, int reg) {
        return mode <= 1 || (mode == 7 && reg == 4);
    }

    private void arithEaDn(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int dn = (op >> 9) & 7;
        int s = readEa(mode, reg, sz);
        int d = r[dn] & MASK[sz];
        setD(dn, sz, (op & 0x4000) != 0 ? addF(s, d, sz) : subF(s, d, sz));
        charge(sz < 2 ? 4 : regOrImm(mode, reg) ? 8 : 6, M68kTiming.C040_BASE);
    }

    private void arithDnEa(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int s = r[(op >> 9) & 7] & MASK[sz];
        int d = rmwRead(mode, reg, sz);
        rmwWrite(mode, reg, sz, (op & 0x4000) != 0 ? addF(s, d, sz) : subF(s, d, sz));
        charge(sz < 2 ? 8 : 12, M68kTiming.C040_BASE);
    }

    private void addaSuba(int op) {
        int sz = (op & 0x100) != 0 ? 2 : 1;
        int mode = (op >> 3) & 7, reg = op & 7;
        int s = readEa(mode, reg, sz);
        if (sz == 1)
            s = (short) s;
        int an = 8 + ((op >> 9) & 7);
        r[an] = (op & 0x4000) != 0 ? r[an] + s : r[an] - s;
        charge(sz == 1 ? 8 : regOrImm(mode, reg) ? 8 : 6, M68kTiming.C040_BASE);
    }

    private void extendReg(int op) {
        int sz = (op >> 6) & 3;
        int dx = (op >> 9) & 7;
        int s = r[op & 7] & MASK[sz];
        int d = r[dx] & MASK[sz];
        setD(dx, sz, (op & 0x4000) != 0 ? addxF(s, d, sz) : subxF(s, d, sz));
        charge(sz < 2 ? 4 : 8, M68kTiming.C040_BASE);
    }

    private void extendMem(int op) {
        int sz = (op >> 6) & 3;
        int s = readSz(eaAddr(4, op & 7, BYTES[sz]), sz);
        int addr = eaAddr(4, (op >> 9) & 7, BYTES[sz]);
        int d = readSz(addr, sz);
        writeSz(addr, sz, (op & 0x4000) != 0 ? addxF(s, d, sz) : subxF(s, d, sz));
        charge(sz < 2 ? 18 : 30, M68kTiming.C040_BASE);
    }

    private void cmp(int op) {
        int sz = (op >> 6) & 3;
        int s = readEa((op >> 3) & 7, op & 7, sz);
        cmpF(s, r[(op >> 9) & 7] & MASK[sz], sz);
        charge(sz < 2 ? 4 : 6, M68kTiming.C040_BASE);
    }

    private void cmpa(int op) {
        int sz = (op & 0x100) != 0 ? 2 : 1;
        int s = readEa((op >> 3) & 7, op & 7, sz);
        cmpF(sz == 1 ? (short) s : s, r[8 + ((op >> 9) & 7)], 2);
        charge(6, M68kTiming.C040_BASE);
    }

    private void cmpm(int op) {
        int sz = (op >> 6) & 3;
        int s = readSz(eaAddr(3, op & 7, BYTES[sz]), sz);
        int d = readSz(eaAddr(3, (op >> 9) & 7, BYTES[sz]), sz);
        cmpF(s, d, sz);
        charge(sz < 2 ? 12 : 20, M68kTiming.C040_BASE);
    }

    private void cmpi(int op) {
        int sz = (op >> 6) & 3;
        int imm = immediate(sz);
        int mode = (op >> 3) & 7;
        int d = readEa(mode, op & 7, sz);
        cmpF(imm, d, sz);
        charge(mode == 0 ? (sz < 2 ? 8 : 14) : (sz < 2 ? 8 : 12), M68kTiming.C040_BASE);
    }

    /** ORI, ANDI, SUBI, ADDI, EORI to a data-alterable destination. */
    private void immediateOp(int op) {
        int sz = (op >> 6) & 3;
        int imm = immediate(sz);
        int mode = (op >> 3) & 7, reg = op & 7;
        int d = rmwRead(mode, reg, sz);
        int res;
        int kindOf = (op >> 9) & 7;
        switch (kindOf) {
            case 0: res = d | imm; nz(res, sz); break;
            case 1: res = d & imm; nz(res, sz); break;
            case 2: res = subF(imm, d, sz); break;
            case 3: res = addF(imm, d, sz); break;
            default: res = d ^ imm; nz(res, sz); break;
        }
        rmwWrite(mode, reg, sz, res);
        int c;
        if (mode == 0)
            c = sz < 2 ? 8 : kindOf == 1 ? 14 : 16;
        else
            c = sz < 2 ? 12 : 20;
        charge(c, M68kTiming.C040_BASE);
    }

    private void logicEaDn(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int dn = (op >> 9) & 7;
        int s = readEa(mode, reg, sz);
        int res = (op & 0x4000) != 0 ? r[dn] & s : r[dn] | s;
        setD(dn, sz, res);
        nz(res, sz);
        charge(sz < 2 ? 4 : regOrImm(mode, reg) ? 8 : 6, M68kTiming.C040_BASE);
    }

    private void logicDnEa(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int s = r[(op >> 9) & 7];
        int d = rmwRead(mode, reg, sz);
        int res = (op & 0x4000) != 0 ? d & s : d | s;
        rmwWrite(mode, reg, sz, res);
        nz(res, sz);
        charge(sz < 2 ? 8 : 12, M68kTiming.C040_BASE);
    }

    private void eor(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int d = rmwRead(mode, reg, sz);
        int res = d ^ r[(op >> 9) & 7];
        rmwWrite(mode, reg, sz, res);
        nz(res, sz);
        charge(mode == 0 ? (sz < 2 ? 4 : 8) : (sz < 2 ? 8 : 12), M68kTiming.C040_BASE);
    }

    private void addqSubq(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int data = ((op >> 9) & 7) == 0 ? 8 : (op >> 9) & 7;
        int d = rmwRead(mode, reg, sz);
        rmwWrite(mode, reg, sz, (op & 0x100) == 0 ? addF(data, d, sz) : subF(data, d, sz));
        charge(mode == 0 ? (sz < 2 ? 4 : 8) : (sz < 2 ? 8 : 12), M68kTiming.C040_BASE);
    }

    private void addqSubqA(int op) {
        int data = ((op >> 9) & 7) == 0 ? 8 : (op >> 9) & 7;
        int an = 8 + (op & 7);
        r[an] = (op & 0x100) == 0 ? r[an] + data : r[an] - data;
        charge(8, M68kTiming.C040_BASE);
    }

    /** NEGX, CLR, NEG, NOT. */
    private void singleOp(int op) {
        int sz = (op >> 6) & 3;
        int mode = (op >> 3) & 7, reg = op & 7;
        int which = (op >> 9) & 3;
        if (which == 1) {
            writeEa(mode, reg, sz, 0, true);
            ccr = (ccr & XF) | ZF;
        } else {
            int d = rmwRead(mode, reg, sz);
            int res;
            if (which == 0)
                res = subxF(d, 0, sz);
            else if (which == 2)
                res = subF(d, 0, sz);
            else {
                res = ~d;
                nz(res, sz);
            }
            rmwWrite(mode, reg, sz, res);
        }
        charge(mode == 0 ? (sz < 2 ? 4 : 6) : (sz < 2 ? 8 : 12), M68kTiming.C040_BASE);
    }

    private void tst(int op) {
        int sz = (op >> 6) & 3;
        nz(readEa((op >> 3) & 7, op & 7, sz), sz);
        charge(4, M68kTiming.C040_BASE);
    }

    private void tas(int op) {
        int mode = (op >> 3) & 7;
        int v = rmwRead(mode, op & 7, 0);
        nz(v, 0);
        rmwWrite(mode, op & 7, 0, v | 0x80);
        charge(mode == 0 ? 4 : 14, M68kTiming.C040_BASE);
    }

    private void ccrImmediate(int kindOf) {
        int imm = fetch16() & 0xFF;
        ccr = (kindOf == 0 ? ccr | imm : kindOf == 1 ? ccr & imm : ccr ^ imm) & M68kSr.CCR_MASK;
        charge(20, M68kTiming.C040_BASE);
    }

    private void srImmediate(int kindOf) {
        if (!privileged())
            return;
        int imm = fetch16();
        int sr = getSr();
        setSr(kindOf == 0 ? sr | imm : kindOf == 1 ? sr & imm : sr ^ imm);
        flow = true;
        charge(20, M68kTiming.C040_BASE);
    }

    // ---- BCD ----

    private void bcdReg(int op) {
        int dx = (op >> 9) & 7;
        int s = r[op & 7] & 0xFF;
        int d = r[dx] & 0xFF;
        int res = (op & 0x4000) != 0 ? M68kFlags.abcd(s, d, (ccr >> 4) & 1) : M68kFlags.sbcd(s, d, (ccr >> 4) & 1);
        setD(dx, 0, res);
        bcdFlags(res);
        charge(6, M68kTiming.C040_BASE);
    }

    private void bcdMem(int op) {
        int s = read8(eaAddr(4, op & 7, 1));
        int addr = eaAddr(4, (op >> 9) & 7, 1);
        int d = read8(addr);
        int res = (op & 0x4000) != 0 ? M68kFlags.abcd(s, d, (ccr >> 4) & 1) : M68kFlags.sbcd(s, d, (ccr >> 4) & 1);
        write8(addr, res);
        bcdFlags(res);
        charge(18, M68kTiming.C040_BASE);
    }

    private void nbcd(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        int d = rmwRead(mode, reg, 0);
        int res = M68kFlags.sbcd(d, 0, (ccr >> 4) & 1);
        rmwWrite(mode, reg, 0, res);
        bcdFlags(res);
        charge(mode == 0 ? 6 : 8, M68kTiming.C040_BASE);
    }

    /** X = C = decimal carry, Z cleared if nonzero (else unchanged), N = bit 7, V per the fixed rule. */
    private void bcdFlags(int res) {
        int c = (res & M68kFlags.BCD_C) != 0 ? XF | CF : 0;
        int z = (res & 0xFF) != 0 ? 0 : ccr & ZF;
        ccr = c | z | ((res & 0x80) != 0 ? NF : 0) | ((res & M68kFlags.BCD_V) != 0 ? VF : 0);
    }

    // ---- bits and shifts ----

    /** BTST, BCHG, BCLR, BSET with an immediate or Dn bit number: Z = !(old bit). */
    private void bitOp(int op, boolean imm) {
        int which = (op >> 6) & 3;
        int bit = imm ? fetch16() & 0xFF : r[(op >> 9) & 7];
        int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            int m = 1 << (bit & 31);
            int v = r[reg];
            ccr = (ccr & ~ZF) | ((v & m) == 0 ? ZF : 0);
            if (which == 1)
                r[reg] = v ^ m;
            else if (which == 2)
                r[reg] = v & ~m;
            else if (which == 3)
                r[reg] = v | m;
            int c = which == 0 ? 6 : which == 2 ? 10 : 8;
            charge(imm ? c + 4 : c, M68kTiming.C040_BASE);
            return;
        }
        int m = 1 << (bit & 7);
        if (which == 0) {
            int v = readEa(mode, reg, 0);
            ccr = (ccr & ~ZF) | ((v & m) == 0 ? ZF : 0);
            charge(imm ? 8 : 4, M68kTiming.C040_BASE);
            return;
        }
        int v = rmwRead(mode, reg, 0);
        ccr = (ccr & ~ZF) | ((v & m) == 0 ? ZF : 0);
        rmwWrite(mode, reg, 0, which == 1 ? v ^ m : which == 2 ? v & ~m : v | m);
        charge(imm ? 12 : 8, M68kTiming.C040_BASE);
    }

    private void shiftReg(int op) {
        int sz = (op >> 6) & 3;
        int cnt = (op >> 9) & 7;
        int n = (op & 0x20) != 0 ? r[cnt] & 63 : cnt == 0 ? 8 : cnt;
        int reg = op & 7;
        long res = M68kFlags.shift((op >> 3) & 3, (op & 0x100) != 0, r[reg], n, sz, ccr);
        setD(reg, sz, (int) res);
        ccr = (int) (res >>> 32);
        charge((sz == 2 ? 8 : 6) + 2 * n, M68kTiming.C040_BASE);
    }

    private void shiftMem(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        int v = rmwRead(mode, reg, 1);
        long res = M68kFlags.shift((op >> 9) & 3, (op & 0x100) != 0, v, 1, 1, ccr);
        rmwWrite(mode, reg, 1, (int) res);
        ccr = (int) (res >>> 32);
        charge(8, M68kTiming.C040_BASE);
    }

    private void bitfield(int op) {
        int type = (op >> 8) & 7; // 0 TST, 1 EXTU, 2 CHG, 3 EXTS, 4 CLR, 5 FFO, 6 SET, 7 INS
        int ext = fetch16();
        int mode = (op >> 3) & 7, reg = op & 7;
        int offset = (ext & 0x800) != 0 ? r[(ext >> 6) & 7] : (ext >> 6) & 31;
        int width = ((ext & 0x20) != 0 ? r[ext & 7] : ext) & 31;
        if (width == 0)
            width = 32;
        int dn = (ext >> 12) & 7;
        boolean modifies = type == 2 || type == 4 || type == 6 || type == 7;
        int field;
        int newField = 0;
        if (type == 7)
            newField = r[dn] & Bitfield.mask(width);
        else if (type == 2 || type == 6)
            newField = Bitfield.mask(width);
        if (mode == 0) {
            field = Bitfield.extract(r[reg], offset, width);
            if (type == 2)
                newField = ~field;
            if (modifies)
                r[reg] = Bitfield.insert(r[reg], offset, width, newField);
        } else {
            int addr = eaAddr(mode, reg, 1) + (offset >> 3);
            int bitOff = offset & 7;
            int nbytes = Bitfield.spanBytes(bitOff, width);
            long w = 0;
            for (int i = 0; i < nbytes; i++)
                w = (w << 8) | read8(addr + i);
            int shift = nbytes * 8 - bitOff - width;
            long fmask = (1L << width) - 1;
            field = (int) ((w >>> shift) & fmask);
            if (type == 2)
                newField = ~field;
            if (modifies) {
                w = (w & ~(fmask << shift)) | ((newField & fmask) << shift);
                for (int i = 0; i < nbytes; i++)
                    write8(addr + i, (int) (w >>> (8 * (nbytes - 1 - i))));
            }
        }
        ccr = (ccr & XF) | Bitfield.flags(type == 7 ? newField : field, width);
        if (type == 1)
            r[dn] = field & Bitfield.mask(width);
        else if (type == 3)
            r[dn] = Bitfield.signExtend(field, width);
        else if (type == 5)
            r[dn] = offset + Bitfield.firstOne(field, width);
        charge(0, M68kTiming.C040_BASE);
    }

    // ---- multiply and divide ----

    private void mulW(int op, boolean signed) {
        int s = readEa((op >> 3) & 7, op & 7, 1);
        int dn = (op >> 9) & 7;
        int res = signed ? (short) r[dn] * (short) s : (r[dn] & 0xFFFF) * s;
        r[dn] = res;
        nz(res, 2);
        charge(signed ? M68kTiming.muls(s) : M68kTiming.mulu(s), M68kTiming.C040_MUL_W);
    }

    private void divW(int op, boolean signed) {
        int s = readEa((op >> 3) & 7, op & 7, 1);
        int dn = (op >> 9) & 7;
        int d = r[dn];
        if (s == 0) {
            zeroDivide();
            return;
        }
        charge(signed ? M68kTiming.divs(d, s) : M68kTiming.divu(d, s), M68kTiming.C040_DIV_W);
        long q;
        long rem;
        if (signed) {
            q = (long) d / (short) s;
            rem = (long) d % (short) s;
            if (q < -32768 || q > 32767) {
                ccr = (ccr & (XF | NF | ZF)) | VF;
                return;
            }
        } else {
            long ud = d & 0xFFFF_FFFFL;
            q = ud / s;
            rem = ud % s;
            if (q > 0xFFFF) {
                ccr = (ccr & (XF | NF | ZF)) | VF;
                return;
            }
        }
        r[dn] = ((int) rem << 16) | ((int) q & 0xFFFF);
        nz((int) q, 1);
    }

    /** Zero divide: C = 0, N, Z, V kept; format 2 with the instruction's address; PC = next. */
    private void zeroDivide() {
        ccr &= ~CF;
        exception(M68kVectors.ZERO_DIVIDE, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.ZERO_DIVIDE);
    }

    private void mulL(int op) {
        int ext = fetch16();
        int s = readEa((op >> 3) & 7, op & 7, 2);
        int dl = (ext >> 12) & 7;
        int dh = ext & 7;
        boolean signed = (ext & 0x800) != 0;
        long a = signed ? r[dl] : r[dl] & 0xFFFF_FFFFL;
        long b = signed ? s : s & 0xFFFF_FFFFL;
        if ((ext & 0x400) != 0) {
            // two 32-bit operands: the signed or unsigned product is exact in 64 bits
            long full = a * b;
            r[dh] = (int) (full >>> 32);
            r[dl] = (int) full;
            ccr = (ccr & XF) | (full < 0 ? NF : 0) | (full == 0 ? ZF : 0);
        } else {
            long p = a * b;
            int lo = (int) p;
            boolean overflow = signed ? p != lo : (p >>> 32) != 0;
            r[dl] = lo;
            ccr = (ccr & XF) | (lo < 0 ? NF : 0) | (lo == 0 ? ZF : 0) | (overflow ? VF : 0);
        }
        charge(0, M68kTiming.C040_MUL_L);
    }

    private void divL(int op) {
        int ext = fetch16();
        int s = readEa((op >> 3) & 7, op & 7, 2);
        int dq = (ext >> 12) & 7;
        int dr = ext & 7;
        boolean signed = (ext & 0x800) != 0;
        boolean wide = (ext & 0x400) != 0;
        if (s == 0) {
            zeroDivide();
            return;
        }
        charge(0, M68kTiming.C040_DIV_L);
        long q;
        long rem;
        if (signed) {
            long dividend = wide ? ((long) r[dr] << 32) | (r[dq] & 0xFFFF_FFFFL) : r[dq];
            if (dividend == Long.MIN_VALUE && s == -1) {
                ccr = (ccr & (XF | NF | ZF)) | VF;
                return;
            }
            q = dividend / s;
            rem = dividend % s;
            if (q != (int) q) {
                ccr = (ccr & (XF | NF | ZF)) | VF;
                return;
            }
        } else {
            long dividend = wide ? ((long) r[dr] << 32) | (r[dq] & 0xFFFF_FFFFL) : r[dq] & 0xFFFF_FFFFL;
            long divisor = s & 0xFFFF_FFFFL;
            q = Long.divideUnsigned(dividend, divisor);
            rem = Long.remainderUnsigned(dividend, divisor);
            if ((q >>> 32) != 0) {
                ccr = (ccr & (XF | NF | ZF)) | VF;
                return;
            }
        }
        if (wide || dr != dq)
            r[dr] = (int) rem;
        r[dq] = (int) q;
        ccr = (ccr & XF) | ((int) q < 0 ? NF : 0) | ((int) q == 0 ? ZF : 0);
    }

    // ---- branches, jumps, returns ----

    /** Sets pc to a jump target; an odd target is an address error (both models). */
    private void jump(int target) {
        if ((target & 1) != 0)
            addressErrorFetch(target);
        pc = target;
        flow = true;
    }

    private void bcc(int op) {
        int base = pc;
        int disp = (byte) op;
        boolean word = false;
        if (disp == 0) {
            disp = (short) fetch16();
            word = true;
        } else if (disp == -1 && is040) {
            disp = fetch32();
            word = true;
        }
        if (cond((op >> 8) & 15)) {
            jump(base + disp);
            charge(10, M68kTiming.C040_BRANCH_TAKEN);
        } else {
            charge(word ? 12 : 8, M68kTiming.C040_BRANCH_NOT_TAKEN);
        }
    }

    private void bsr(int op) {
        int base = pc;
        int disp = (byte) op;
        if (disp == 0)
            disp = (short) fetch16();
        else if (disp == -1 && is040)
            disp = fetch32();
        int target = base + disp;
        if ((target & 1) != 0)
            addressErrorFetch(target);
        push32(pc);
        jump(target);
        charge(18, M68kTiming.C040_CALL);
    }

    private void dbcc(int op) {
        int base = pc;
        int disp = (short) fetch16();
        if (cond((op >> 8) & 15)) {
            charge(12, M68kTiming.C040_BRANCH_NOT_TAKEN);
            return;
        }
        int reg = op & 7;
        int v = (r[reg] - 1) & 0xFFFF;
        r[reg] = (r[reg] & 0xFFFF_0000) | v;
        if (v != 0xFFFF) {
            jump(base + disp);
            charge(10, M68kTiming.C040_BRANCH_TAKEN);
        } else {
            charge(14, M68kTiming.C040_BRANCH_NOT_TAKEN);
        }
    }

    private void scc(int op) {
        boolean t = cond((op >> 8) & 15);
        int mode = (op >> 3) & 7;
        writeEa(mode, op & 7, 0, t ? 0xFF : 0, true);
        charge(mode == 0 ? (t ? 6 : 4) : 8, M68kTiming.C040_BASE);
    }

    private void jmp(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        jump(eaAddr(mode, reg, 4));
        charge(M68kTiming.JMP[kind(mode, reg)], M68kTiming.C040_BRANCH_TAKEN);
    }

    private void jsr(int op) {
        int mode = (op >> 3) & 7, reg = op & 7;
        int target = eaAddr(mode, reg, 4);
        if ((target & 1) != 0)
            addressErrorFetch(target);
        push32(pc);
        jump(target);
        charge(M68kTiming.JSR[kind(mode, reg)], M68kTiming.C040_CALL);
    }

    private void rts() {
        jump(pop32());
        charge(16, M68kTiming.C040_RETURN);
    }

    private void rtr() {
        ccr = pop16() & M68kSr.CCR_MASK;
        jump(pop32());
        charge(20, M68kTiming.C040_RETURN);
    }

    private void rtd() {
        int target = pop32();
        r[15] += (short) fetch16();
        jump(target);
        charge(0, M68kTiming.C040_RETURN);
    }

    private void rte() {
        if (!privileged())
            return;
        for (;;) {
            int sp = r[15];
            int sr = read16(sp);
            int newPc = read32(sp + 2);
            if (!is040) {
                r[15] = sp + 6;
                setSr(sr);
                jump(newPc);
                charge(20, 0);
                return;
            }
            int format = read16(sp + 6) >>> 12;
            int bytes = M68kVectors.frameBytes(format);
            if (bytes < 0) {
                exception(M68kVectors.FORMAT_ERROR, M68kVectors.FORMAT_0, instrPc, 0, M68kTiming.EXCEPTION);
                return;
            }
            r[15] = sp + bytes;
            setSr(sr);
            if (format == M68kVectors.FORMAT_1)
                continue;
            jump(newPc);
            charge(0, M68kTiming.C040_RTE);
            return;
        }
    }

    private void trapv() {
        if ((ccr & VF) != 0)
            exception(M68kVectors.TRAPV, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.EXCEPTION);
        else
            charge(4, M68kTiming.C040_BASE);
    }

    /** TRAPcc (68040): no operand, .W or .L operand words (ignored), vector 7 when true. */
    private void trapcc(int op) {
        int reg = op & 7;
        if (reg == 2)
            fetch16();
        else if (reg == 3)
            fetch32();
        if (cond((op >> 8) & 15))
            exception(M68kVectors.TRAPV, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.EXCEPTION);
        else
            charge(0, M68kTiming.C040_BASE);
    }

    /** CHK.W and CHK.L: trap if Dn < 0 (N = 1) or Dn > bound (N = 0); Z, V, C kept. */
    private void chk(int op, int sz) {
        int bound = sext(readEa((op >> 3) & 7, op & 7, sz), sz);
        int v = sext(r[(op >> 9) & 7], sz);
        if (v < 0 || v > bound) {
            ccr = (ccr & ~NF) | (v < 0 ? NF : 0);
            exception(M68kVectors.CHK, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.CHK_TRAP);
            return;
        }
        charge(10, M68kTiming.C040_BASE);
    }

    private void stop() {
        if (!privileged())
            return;
        int imm = fetch16();
        int traceBefore = sys & TRACE_BITS;
        setSr(imm);
        flow = true;
        charge(4, M68kTiming.C040_BASE);
        if (traceBefore != 0 || (sys & TRACE_BITS) != 0) {
            // 68000 behaviour: trace is taken at once instead of stopping
            traced = true;
            takeTrace();
            return;
        }
        stopped = true;
    }

    private void resetInstruction() {
        if (!privileged())
            return;
        clock.advanceTo(cycles);
        if (resetDevices != null)
            resetDevices.run();
        charge(M68kTiming.RESET_INSTRUCTION, M68kTiming.C040_RESET);
        limit = clock.horizon();
    }

    // ---- 68040 integer additions ----

    private void cas(int op) {
        int sz = ((op >> 9) & 3) - 1;
        int ext = fetch16();
        int dc = ext & 7;
        int du = (ext >> 6) & 7;
        int addr = eaAddr((op >> 3) & 7, op & 7, BYTES[sz]);
        int v = readSz(addr, sz);
        cmpF(r[dc] & MASK[sz], v, sz);
        if ((ccr & ZF) != 0)
            writeSz(addr, sz, r[du]);
        else
            setD(dc, sz, v);
        charge(0, M68kTiming.C040_BASE);
    }

    private void cas2(int op) {
        int sz = op == 0x0CFC ? 1 : 2;
        int ext1 = fetch16();
        int ext2 = fetch16();
        int a1 = r[(ext1 >>> 12) & 15];
        int a2 = r[(ext2 >>> 12) & 15];
        int v1 = readSz(a1, sz);
        int v2 = readSz(a2, sz);
        int dc1 = ext1 & 7, dc2 = ext2 & 7;
        cmpF(r[dc1] & MASK[sz], v1, sz);
        if ((ccr & ZF) != 0) {
            cmpF(r[dc2] & MASK[sz], v2, sz);
            if ((ccr & ZF) != 0) {
                writeSz(a1, sz, r[(ext1 >> 6) & 7]);
                writeSz(a2, sz, r[(ext2 >> 6) & 7]);
                charge(0, M68kTiming.C040_BASE);
                return;
            }
        }
        setD(dc2, sz, v2);
        setD(dc1, sz, v1);
        charge(0, M68kTiming.C040_BASE);
    }

    /**
     * CMP2 and CHK2: bounds lower then upper at the EA. A data register compares at the size, an
     * address register against bounds sign-extended to 32 bits. Signed when lower > upper
     * unsigned, else unsigned. Z = equal to a bound, C = out of bounds; N and V kept.
     */
    private void cmp2(int op) {
        int sz = (op >> 9) & 3;
        int ext = fetch16();
        int rn = (ext >>> 12) & 15;
        int addr = eaAddr((op >> 3) & 7, op & 7, BYTES[sz]);
        int lo = readSz(addr, sz);
        int hi = readSz(addr + BYTES[sz], sz);
        int v;
        int esz = sz;
        if (rn >= 8) {
            lo = sext(lo, sz);
            hi = sext(hi, sz);
            v = r[rn];
            esz = 2;
        } else {
            v = r[rn] & MASK[sz];
        }
        boolean out;
        if (Integer.compareUnsigned(lo, hi) > 0) {
            int sv = sext(v, esz), sl = sext(lo, esz), sh = sext(hi, esz);
            out = sv < sl || sv > sh;
        } else {
            out = Integer.compareUnsigned(v, lo) < 0 || Integer.compareUnsigned(v, hi) > 0;
        }
        ccr = (ccr & (XF | NF | VF)) | (v == lo || v == hi ? ZF : 0) | (out ? CF : 0);
        if (out && (ext & 0x800) != 0) {
            exception(M68kVectors.CHK, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.CHK_TRAP);
            return;
        }
        charge(0, M68kTiming.C040_BASE);
    }

    private void pack(int op, boolean mem) {
        int adj = fetch16();
        int rx = op & 7, ry = (op >> 9) & 7;
        if (!mem) {
            int src = (r[rx] + adj) & 0xFFFF;
            setD(ry, 0, ((src >> 4) & 0xF0) | (src & 0x0F));
        } else {
            int lo = read8(eaAddr(4, rx, 1));
            int hi = read8(eaAddr(4, rx, 1));
            int src = ((hi << 8) | lo) + adj;
            write8(eaAddr(4, ry, 1), ((src >> 4) & 0xF0) | (src & 0x0F));
        }
        charge(0, M68kTiming.C040_BASE);
    }

    private void unpk(int op, boolean mem) {
        int adj = fetch16();
        int rx = op & 7, ry = (op >> 9) & 7;
        if (!mem) {
            int src = r[rx] & 0xFF;
            setD(ry, 1, (((src << 4) & 0x0F00) | (src & 0x0F)) + adj);
        } else {
            int src = read8(eaAddr(4, rx, 1));
            int res = (((src << 4) & 0x0F00) | (src & 0x0F)) + adj;
            write8(eaAddr(4, ry, 1), res);
            write8(eaAddr(4, ry, 1), res >>> 8);
        }
        charge(0, M68kTiming.C040_BASE);
    }

    /** MOVES: no MMU, so SFC and DFC are ignored and the access is a plain supervisor access. */
    private void moves(int op) {
        if (!privileged())
            return;
        int sz = (op >> 6) & 3;
        int ext = fetch16();
        int rn = (ext >>> 12) & 15;
        int addr = eaAddr((op >> 3) & 7, op & 7, BYTES[sz]);
        if ((ext & 0x800) != 0) {
            writeSz(addr, sz, r[rn]);
        } else {
            int v = readSz(addr, sz);
            if (rn >= 8)
                r[rn] = sext(v, sz);
            else
                setD(rn, sz, v);
        }
        charge(0, M68kTiming.C040_BASE);
    }

    private void move16(int op) {
        int src, dst;
        if ((op & 0x20) != 0) {
            int ext = fetch16();
            int ax = 8 + (op & 7), ay = 8 + ((ext >> 12) & 7);
            src = r[ax];
            dst = r[ay];
            r[ax] += 16;
            if (ay != ax)
                r[ay] += 16;
        } else {
            int ay = 8 + (op & 7);
            int abs = fetch32();
            int opm = (op >> 3) & 3;
            boolean fromAy = (opm & 1) == 0;
            src = fromAy ? r[ay] : abs;
            dst = fromAy ? abs : r[ay];
            if (opm < 2)
                r[ay] += 16;
        }
        src &= ~15;
        dst &= ~15;
        for (int i = 0; i < 16; i += 4)
            write32(dst + i, read32(src + i));
        charge(0, M68kTiming.C040_BASE);
    }

    private void movec(int op) {
        if (!privileged())
            return;
        int ext = fetch16();
        int rn = (ext >>> 12) & 15;
        int code = ext & 0xFFF;
        if (op == 0x4E7A) {
            long v = readControl(code);
            if (v == Long.MIN_VALUE) {
                illegal();
                return;
            }
            r[rn] = (int) v;
        } else if (!writeControl(code, r[rn])) {
            illegal();
            return;
        }
        charge(0, M68kTiming.C040_BASE);
    }

    /** A control register by MOVEC number, or Long.MIN_VALUE if there is none. */
    private long readControl(int code) {
        switch (code) {
            case CR_SFC: return sfc;
            case CR_DFC: return dfc;
            case CR_CACR: return cacr;
            case CR_TC: return tc;
            case CR_ITT0: return itt0;
            case CR_ITT1: return itt1;
            case CR_DTT0: return dtt0;
            case CR_DTT1: return dtt1;
            case CR_USP: return stackReg(0);
            case CR_VBR: return vbr;
            case CR_MSP: return stackReg(2);
            case CR_ISP: return stackReg(1);
            case CR_MMUSR: return mmusr;
            case CR_URP: return urp;
            case CR_SRP: return srp;
            default: return Long.MIN_VALUE;
        }
    }

    private boolean writeControl(int code, int v) {
        switch (code) {
            case CR_SFC: sfc = v & 7; return true;
            case CR_DFC: dfc = v & 7; return true;
            case CR_CACR: cacr = v & CACR_MASK; return true;
            case CR_TC: tc = v & ~TC_ENABLE; return true;
            case CR_ITT0: itt0 = v; return true;
            case CR_ITT1: itt1 = v; return true;
            case CR_DTT0: dtt0 = v; return true;
            case CR_DTT1: dtt1 = v; return true;
            case CR_USP: setStackReg(0, v); return true;
            case CR_VBR: vbr = v; return true;
            case CR_MSP: setStackReg(2, v); return true;
            case CR_ISP: setStackReg(1, v); return true;
            case CR_MMUSR: mmusr = v; return true;
            case CR_URP: urp = v; return true;
            case CR_SRP: srp = v; return true;
            default: return false;
        }
    }

    /** PTESTR, PTESTW: every address maps to itself through transparent translation (spec 10.5). */
    private void ptest(int op) {
        if (!privileged())
            return;
        mmusr = (r[8 + (op & 7)] & 0xFFFF_F000) | 0x3;
        charge(0, M68kTiming.C040_BASE);
    }

    // ---- FPU conditional instructions, run by the core (spec 10.4) ----

    private void fbcc(int op, boolean lng) {
        int base = pc;
        int disp = lng ? fetch32() : (short) fetch16();
        int c = fpu.condition(op & 0x3F);
        if (c == M68kFpuUnit.CONDITION_TRAPPED)
            return;
        if (c != 0) {
            jump(base + disp);
            charge(0, M68kTiming.C040_BRANCH_TAKEN);
        } else {
            charge(0, M68kTiming.C040_BRANCH_NOT_TAKEN);
        }
    }

    private void fscc(int op) {
        int pred = fetch16() & 0x3F;
        int c = fpu.condition(pred);
        if (c == M68kFpuUnit.CONDITION_TRAPPED)
            return;
        writeEa((op >> 3) & 7, op & 7, 0, c != 0 ? 0xFF : 0, true);
        charge(0, M68kTiming.C040_BASE);
    }

    private void fdbcc(int op) {
        int pred = fetch16() & 0x3F;
        int base = pc;
        int disp = (short) fetch16();
        int c = fpu.condition(pred);
        if (c == M68kFpuUnit.CONDITION_TRAPPED)
            return;
        if (c != 0) {
            charge(0, M68kTiming.C040_BRANCH_NOT_TAKEN);
            return;
        }
        int reg = op & 7;
        int v = (r[reg] - 1) & 0xFFFF;
        r[reg] = (r[reg] & 0xFFFF_0000) | v;
        if (v != 0xFFFF) {
            jump(base + disp);
            charge(0, M68kTiming.C040_BRANCH_TAKEN);
        } else {
            charge(0, M68kTiming.C040_BRANCH_NOT_TAKEN);
        }
    }

    private void ftrapcc(int op) {
        int pred = fetch16() & 0x3F;
        int reg = op & 7;
        if (reg == 2)
            fetch16();
        else if (reg == 3)
            fetch32();
        int c = fpu.condition(pred);
        if (c == M68kFpuUnit.CONDITION_TRAPPED)
            return;
        if (c != 0)
            exception(M68kVectors.TRAPV, M68kVectors.FORMAT_2, pc, instrPc, M68kTiming.EXCEPTION);
        else
            charge(0, M68kTiming.C040_BASE);
    }

    // ---- status register and stacks (spec 10.3.6) ----

    private int getSr() {
        return sys | ccr;
    }

    /** Writes SR (masked to the model's bits), switching A7 when S or M changes, and re-evaluates interrupts. */
    private void setSr(int v) {
        v &= srMask;
        int old = sys;
        ccr = v & M68kSr.CCR_MASK;
        sys = v & 0xFF00;
        switchStack(old, sys);
        recomputeIrq();
    }

    /** 0 USP, 1 ISP (the 68000's SSP), 2 MSP. */
    private static int stackSel(int sysBits) {
        return (sysBits & SF) == 0 ? 0 : (sysBits & MF) != 0 ? 2 : 1;
    }

    private void switchStack(int oldSys, int newSys) {
        int from = stackSel(oldSys);
        int to = stackSel(newSys);
        if (from == to)
            return;
        if (from == 0)
            usp = r[15];
        else if (from == 1)
            isp = r[15];
        else
            msp = r[15];
        r[15] = to == 0 ? usp : to == 1 ? isp : msp;
    }

    private int stackReg(int sel) {
        if (sel == stackSel(sys))
            return r[15];
        return sel == 0 ? usp : sel == 1 ? isp : msp;
    }

    private void setStackReg(int sel, int v) {
        if (sel == stackSel(sys))
            r[15] = v;
        else if (sel == 0)
            usp = v;
        else if (sel == 1)
            isp = v;
        else
            msp = v;
    }

    /** Exception entry: S = 1, T1 = T0 = 0, M kept, stacks switched. */
    private void enterSupervisor() {
        int old = sys;
        sys = (sys | SF) & ~TRACE_BITS;
        switchStack(old, sys);
    }

    // ---- exceptions (spec 10.3.7) ----

    /**
     * Takes {@code vector}: 68000 three-word frame (SR, PC); 68040 frame of {@code format} (0, 2
     * or 3, with {@code address} for 2 and 3). Charges {@code cost68000} on the 68000 or the
     * 68040's fixed entry cost. A fault while building the frame or reading the vector halts.
     */
    private void exception(int vector, int format, int stackedPc, int address, int cost68000) {
        int oldSr = getSr();
        inException = true;
        enterSupervisor();
        if (is040) {
            if (format == M68kVectors.FORMAT_2 || format == M68kVectors.FORMAT_3)
                push32(address);
            push16((format << 12) | (vector << 2));
        }
        push32(stackedPc);
        push16(oldSr);
        int handler = read32(vbr + (vector << 2));
        inException = false;
        cycles += is040 ? M68kTiming.C040_EXCEPTION : cost68000;
        flow = true;
        stopped = false;
        recomputeIrq();
        if ((handler & 1) != 0)
            addressErrorFetch(handler);
        pc = handler;
    }

    private void halt(String reason) {
        halted = true;
        haltReason = reason;
        inException = false;
        throw ABORT;
    }

    /**
     * Odd instruction fetch or jump target: 68000 group 0 frame; 68040 format 2 with PC = the
     * instruction that caused it and the address field = the odd address with bit 0 cleared
     * (68040 user's manual, 8.4). Never returns.
     */
    private void addressErrorFetch(int addr) {
        if (inException)
            halt(String.format("double fault: address error fetching 0x%08x during exception processing (pc 0x%08x)", addr, instrPc));
        if (is040) {
            if (inAddressError)
                halt(String.format("double fault: odd address error handler 0x%08x", addr));
            inAddressError = true;
            try {
                exception(M68kVectors.ADDRESS_ERROR, M68kVectors.FORMAT_2, instrPc, addr & ~1, 0);
            } finally {
                inAddressError = false;
            }
            throw ABORT;
        }
        group0(M68kVectors.ADDRESS_ERROR, addr, true, true, true);
    }

    /**
     * Odd word or long operand access on the 68000. A read through a PC-relative mode is a
     * program reference (PRM 2.2), so its function code is program space. Never returns.
     */
    private void addressErrorData(int addr, boolean read) {
        if (inException)
            halt(String.format("double fault: address error at 0x%08x during exception processing (pc 0x%08x)", addr, instrPc));
        group0(M68kVectors.ADDRESS_ERROR, addr, read, false, read && eaProgram);
    }

    /** A BusFault: 68000 bus error (group 0), 68040 access error (format 7). Never returns. */
    private void busError(int addr, boolean read, int width, boolean fetch) {
        if (inException)
            halt(String.format("double fault: bus error at 0x%08x during exception processing (pc 0x%08x)", addr, instrPc));
        if (!is040) {
            group0(M68kVectors.BUS_ERROR, addr, read, fetch, fetch || (read && eaProgram));
            return;
        }
        int oldSr = getSr();
        int super0 = sys & SF;
        inException = true;
        enterSupervisor();
        int sp = r[15] - 60;
        r[15] = sp;
        int size = width == 4 ? 0 : width == 1 ? 1 : 2;
        int tm = (super0 != 0 ? 4 : 0) | (fetch ? 2 : 1);
        int ssw = (read ? 0x100 : 0) | (size << 5) | tm;
        write16(sp, oldSr);
        write32(sp + 2, instrPc);
        write16(sp + 6, (M68kVectors.FORMAT_7 << 12) | (M68kVectors.BUS_ERROR << 2));
        write32(sp + 8, addr);
        write16(sp + 0x0C, ssw);
        write16(sp + 0x0E, 0);
        write16(sp + 0x10, 0);
        write16(sp + 0x12, 0);
        write32(sp + 0x14, addr);
        for (int o = 0x18; o < 0x3C; o += 4)
            write32(sp + o, 0);
        int handler = read32(vbr + (M68kVectors.BUS_ERROR << 2));
        inException = false;
        cycles += M68kTiming.C040_EXCEPTION;
        stopped = false;
        recomputeIrq();
        if ((handler & 1) != 0)
            addressErrorFetch(handler);
        pc = handler;
        throw ABORT;
    }

    /**
     * 68000 bus or address error: the 7-word group 0 frame (from SP up: status word with R/W bit
     * 4, I/N bit 3 and the function code, access address, instruction register, SR, PC = the
     * instruction + 2 as documented). {@code program} selects the program space function code
     * (an instruction fetch or a PC-relative operand read). An odd handler is a double fault.
     * Never returns.
     */
    private void group0(int vector, int addr, boolean read, boolean fetch, boolean program) {
        int oldSr = getSr();
        int fc = ((sys & SF) != 0 ? 4 : 0) | (program ? 2 : 1);
        inException = true;
        enterSupervisor();
        push32(instrPc + 2);
        push16(oldSr);
        push16(opcode);
        push32(addr);
        push16((read ? 0x10 : 0) | (fetch ? 0 : 0x08) | fc);
        int handler = read32(vector << 2);
        if ((handler & 1) != 0)
            halt(String.format("double fault: odd handler 0x%08x for vector %d (access 0x%08x)", handler, vector, addr));
        inException = false;
        cycles += M68kTiming.GROUP0;
        stopped = false;
        recomputeIrq();
        pc = handler;
        throw ABORT;
    }

    // ---- interrupts ----

    private int ipl() {
        return pins == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(pins);
    }

    private void recomputeIrq() {
        irqPending = nmiPending || ipl() > ((sys >> 8) & 7);
    }

    /** Takes the pending interrupt: autovector 24 + level, mask = level (spec 10.3.7). */
    private void interrupt() {
        int level = nmiPending ? 7 : ipl();
        if (level == 7)
            nmiPending = false;
        stopped = false;
        int vector = M68kVectors.AUTOVECTOR_BASE + level;
        instrPc = pc;
        try {
            int oldSr = getSr();
            inException = true;
            enterSupervisor();
            sys = (sys & ~M68kSr.IPL_MASK) | (level << 8);
            if (is040) {
                push16(vector << 2);
                push32(pc);
                push16(oldSr);
                if ((sys & MF) != 0) {
                    int old = sys;
                    sys &= ~MF;
                    switchStack(old, sys);
                    push16((M68kVectors.FORMAT_1 << 12) | (vector << 2));
                    push32(pc);
                    push16(oldSr | SF);
                }
            } else {
                push32(pc);
                push16(oldSr);
            }
            int handler = read32(vbr + (vector << 2));
            inException = false;
            cycles += is040 ? M68kTiming.C040_EXCEPTION : M68kTiming.INTERRUPT;
            recomputeIrq();
            if ((handler & 1) != 0)
                addressErrorFetch(handler);
            pc = handler;
        } catch (Abort a) {
            // halted, or an address error frame was built instead
        }
        recomputeIrq();
    }

    /** Pins 1..7 are the interrupt priority levels; a rising edge to level 7 is latched. */
    @Override
    public void setIrq(int pin, boolean level) {
        if (pin < 1 || pin > 7)
            throw new IllegalArgumentException("68k has no interrupt pin " + pin);
        int before = ipl();
        if (level)
            pins |= 1 << pin;
        else
            pins &= ~(1 << pin);
        if (ipl() == 7 && before < 7)
            nmiPending = true;
        recomputeIrq();
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

    // ---- saved state (spec 10.6) ----

    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeUTF(options.model().name());
        out.writeBoolean(options.strictFpu());
        for (int i = 0; i < 16; i++)
            out.writeInt(r[i]);
        out.writeInt(pc);
        out.writeShort(getSr());
        out.writeInt(usp);
        out.writeInt(isp);
        if (is040) {
            out.writeInt(msp);
            for (int v : new int[] {vbr, cacr, sfc, dfc, tc, itt0, itt1, dtt0, dtt1, urp, srp, mmusr})
                out.writeInt(v);
        }
        out.writeLong(cycles);
        out.writeLong(instret);
        out.writeInt(pins);
        out.writeBoolean(nmiPending);
        out.writeBoolean(stopped);
        out.writeBoolean(resetPending);
        out.writeBoolean(halted);
        StateIo.writeNullableUtf(out, haltReason);
        if (fpu != null)
            fpu.saveState(out);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "68k CPU", STATE_VERSION);
        String model = in.readUTF();
        boolean strict = in.readBoolean();
        StateIo.check(model.equals(options.model().name()) && strict == options.strictFpu(),
                "saved 68k CPU is a " + model + (strict ? " (strict FPU)" : "") + ", this one a "
                        + options.model().name() + (options.strictFpu() ? " (strict FPU)" : ""));
        for (int i = 0; i < 16; i++)
            r[i] = in.readInt();
        pc = in.readInt();
        int sr = in.readShort() & srMask;
        ccr = sr & M68kSr.CCR_MASK;
        sys = sr & 0xFF00;
        usp = in.readInt();
        isp = in.readInt();
        if (is040) {
            msp = in.readInt();
            vbr = in.readInt();
            cacr = in.readInt() & CACR_MASK;
            sfc = in.readInt() & 7;
            dfc = in.readInt() & 7;
            tc = in.readInt() & ~TC_ENABLE;
            itt0 = in.readInt();
            itt1 = in.readInt();
            dtt0 = in.readInt();
            dtt1 = in.readInt();
            urp = in.readInt();
            srp = in.readInt();
            mmusr = in.readInt();
        }
        cycles = in.readLong();
        instret = in.readLong();
        pins = in.readInt() & 0xFE;
        nmiPending = in.readBoolean();
        stopped = in.readBoolean();
        resetPending = in.readBoolean();
        halted = in.readBoolean();
        haltReason = StateIo.readNullableUtf(in);
        inException = false;
        if (fpu != null)
            fpu.loadState(in);
        recomputeIrq();
    }

    // ---- debugging and tests ----

    public int d(int n) {
        return r[n];
    }

    public int a(int n) {
        return r[8 + n];
    }

    public void setD(int n, int v) {
        r[n] = v;
    }

    public void setA(int n, int v) {
        r[8 + n] = v;
    }

    public int sr() {
        return getSr();
    }

    /** Sets SR as the host (no privilege check); switches stacks like a guest write. */
    public void setSrHost(int v) {
        setSr(v);
    }

    public void setPc(int v) {
        pc = v;
    }

    public long cycles() {
        return cycles;
    }

    public boolean stopped() {
        return stopped;
    }

    public int usp() {
        return stackReg(0);
    }

    /** The 68040's ISP or the 68000's SSP. */
    public int isp() {
        return stackReg(1);
    }

    public int msp() {
        return stackReg(2);
    }

    public int vbr() {
        return vbr;
    }

    /** A MOVEC control register, no side effects; IllegalArgumentException for an unknown number or a 68000. */
    public int controlRegister(int code) {
        long v = is040 ? readControl(code) : Long.MIN_VALUE;
        if (v == Long.MIN_VALUE)
            throw new IllegalArgumentException(String.format("no control register 0x%03x", code));
        return (int) v;
    }

    // ---- the FPU's view of the core (spec 10.4) ----

    private final class FpuHost implements M68kFpuHost {

        @Override
        public int fetch16() {
            return M68kCpu.this.fetch16();
        }

        @Override
        public int instructionAddress() {
            return instrPc;
        }

        @Override
        public int pc() {
            return pc;
        }

        @Override
        public int register(int n) {
            return r[n];
        }

        @Override
        public void setRegister(int n, int value) {
            r[n] = value;
        }

        @Override
        public int effectiveAddress(int mode, int reg, int bytes) {
            if (mode < 2 || mode > 7)
                throw new IllegalArgumentException("EA mode " + mode + " is not a memory mode");
            if (mode == 7 && reg > 4) {
                decodeException(M68kVectors.LINE_F);
                throw ABORT;
            }
            return eaAddr(mode, reg, bytes);
        }

        @Override
        public int read8(int address) {
            return M68kCpu.this.read8(address);
        }

        @Override
        public int read16(int address) {
            return M68kCpu.this.read16(address);
        }

        @Override
        public int read32(int address) {
            return M68kCpu.this.read32(address);
        }

        @Override
        public void write8(int address, int value) {
            M68kCpu.this.write8(address, value);
        }

        @Override
        public void write16(int address, int value) {
            M68kCpu.this.write16(address, value);
        }

        @Override
        public void write32(int address, int value) {
            M68kCpu.this.write32(address, value);
        }

        /**
         * A format 0 exception from the FPU (line F, BSUN, FRESTORE format error) is taken before
         * the instruction executes: like {@link #decodeException} it does not retire and is not
         * traced. Format 2 and 3 frames come after the effective address or the operation.
         */
        @Override
        public void exception(int vector, int format, int stackedPc, int address) {
            if (format == M68kVectors.FORMAT_0)
                decodeFault = true;
            M68kCpu.this.exception(vector, format, stackedPc, address, M68kTiming.EXCEPTION);
        }
    }
}
