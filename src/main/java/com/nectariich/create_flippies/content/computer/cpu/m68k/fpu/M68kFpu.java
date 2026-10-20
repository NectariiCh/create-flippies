package com.nectariich.create_flippies.content.computer.cpu.m68k.fpu;

import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.cpu.m68k.FpuOpcodes;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kFpuHost;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kFpuUnit;
import com.nectariich.create_flippies.content.computer.cpu.m68k.M68kVectors;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * The 68040's built-in FPU (spec 10.4): FP0..FP7 as exact extended (sign and exponent, mantissa)
 * pairs, FPCR, FPSR, FPIAR, the general instructions, FMOVEM, the control register moves, the
 * condition predicates, FSAVE and FRESTORE. Arithmetic is {@link Fx80}; the support-package
 * instructions are {@link FpSupport}, {@link FpPacked} and {@link FpConstantRom}.
 *
 * Command word of a general instruction (opword 0xF200 | EA), bits 15..13 the class:
 * <ul>
 * <li>000 FPm to FPn: source register 12..10, destination 9..7, opmode 6..0;</li>
 * <li>010 EA to FPn: source format 12..10 (format 111 is FMOVECR, ROM offset in 6..0);</li>
 * <li>011 FMOVE FPn to EA: destination format 12..10, source register 9..7, k-factor 6..0
 * (static, format 011) or the k-factor register in 6..4 (dynamic, format 111);</li>
 * <li>100 and 101: FMOVE and FMOVEM of FPCR (12), FPSR (11), FPIAR (10), from (100) or to (101)
 * the EA; memory order FPCR, FPSR, FPIAR upwards;</li>
 * <li>110 and 111: FMOVEM of data registers from (110) or to (111) the EA; bits 12..11 mode
 * (00 static list predecrement, 01 dynamic predecrement, 10 static postincrement or control,
 * 11 dynamic), list in 7..0 or the list register in 6..4. The list's bit 7 is FP0 in the
 * postincrement and control form and FP7 in the predecrement form; memory always holds FP0
 * lowest.</li>
 * </ul>
 * Anything else (class 001, an unknown opmode, a register mode the instruction does not take, an
 * empty control register list) takes the F-line exception (vector 11, format 0, PC = the
 * instruction).
 *
 * Exceptions: every arithmetic instruction and FMOVE to memory clears the FPSR exception status
 * byte, sets the bits it raises, ORs the accrued byte (IOP from BSUN, SNAN, OPERR; OVFL; UNFL from
 * UNFL and INEX2; DZ; INEX from INEX1, INEX2, OVFL) and loads FPIAR. If an enabled bit is set it
 * takes the highest priority one (BSUN, SNAN, OPERR, OVFL, UNFL, DZ, INEX2 and INEX1) as a
 * post-instruction exception: format 3, PC = the next instruction, address = the effective
 * address (0 for a register operand). With SNAN, OPERR or DZ enabled and raised, the destination
 * register is not written (simplification of the 68040: overflow, underflow and inexact traps see
 * the default result in the register). FMOVE to memory always stores.
 *
 * Strict FPU (spec 10.4): an instruction the 68040 leaves to its support package takes vector 11
 * with format 2 (PC = the next instruction, address = the effective address). A packed, denormal
 * or unnormal operand takes vector 55 (UM040 9.6.2): format 0 (PC = the instruction) for an input
 * operand, format 3 (PC = the next instruction, address = the effective address) for FMOVE out.
 * Both come after the effective address was computed, load FPIAR and make the next FSAVE write
 * the unimplemented-instruction frame (vector 11) or the busy frame (vector 55, which the
 * support package's handler expects); their contents are zero.
 */
public final class M68kFpu implements M68kFpuUnit {

    /** Format version of {@link #saveState}. */
    private static final int STATE_VERSION = 1;

    // ---- cycle costs (spec 10.3.9: approximate 68040 figures) ----

    private static final int CYCLES_MOVE = 2;
    private static final int CYCLES_ADD = 3;
    private static final int CYCLES_MUL = 5;
    private static final int CYCLES_DIV = 38;
    private static final int CYCLES_SQRT = 103;
    /** Every support-package instruction and data type: a stand-in for the package's cost. */
    private static final int CYCLES_SUPPORT = 100;
    private static final int CYCLES_CONTROL = 2;
    private static final int CYCLES_PER_REGISTER = 2;
    private static final int CYCLES_SAVE = 3;
    private static final int CYCLES_CONDITION = 0;

    // ---- register masks and FPSR fields ----

    /** Defined FPCR bits: enables 15..8, precision 7..6, mode 5..4. */
    public static final int FPCR_MASK = 0x0000_FFF0;
    /** Defined FPSR bits: condition codes 27..24, quotient 23..16, status 15..8, accrued 7..3. */
    public static final int FPSR_MASK = 0x0FFF_FFF8;
    public static final int FPSR_N = 0x0800_0000;
    public static final int FPSR_Z = 0x0400_0000;
    public static final int FPSR_I = 0x0200_0000;
    public static final int FPSR_NAN = 0x0100_0000;
    private static final int FPSR_CC = 0x0F00_0000;
    private static final int FPSR_QUOTIENT = 0x00FF_0000;
    private static final int FPSR_STATUS = 0x0000_FF00;
    public static final int ACCRUED_IOP = 0x80;
    public static final int ACCRUED_OVFL = 0x40;
    public static final int ACCRUED_UNFL = 0x20;
    public static final int ACCRUED_DZ = 0x10;
    public static final int ACCRUED_INEX = 0x08;

    // ---- FSAVE frames (support package fpsp.h): version 0x41, size byte = bytes after the first long ----

    public static final int FRAME_NULL = 0x0000_0000;
    public static final int FRAME_IDLE = 0x4100_0000;
    public static final int FRAME_UNIMP = 0x4130_0000;
    public static final int FRAME_BUSY = 0x4160_0000;

    private static final int STATE_IDLE = 0;
    private static final int STATE_UNIMP = 1;
    private static final int STATE_BUSY = 2;

    private final M68kFpuHost host;
    private final boolean strict;

    private final int[] fpSe = new int[8];
    private final long[] fpMant = new long[8];
    private int fpcr;
    private int fpsr;
    private int fpiar;
    /** FSAVE writes the null frame: after reset or a null FRESTORE, before the next FP instruction. */
    private boolean nullState;
    /** The frame FSAVE writes when not null: STATE_IDLE, or after a strict trap STATE_UNIMP or STATE_BUSY. */
    private int frameState;

    /** Reused arithmetic context. */
    private final Fx80.Ctx ctx = new Fx80.Ctx();

    public M68kFpu(M68kFpuHost host, boolean strict) {
        this.host = host;
        this.strict = strict;
        reset();
    }

    // ---- accessors (debugging, tests) ----

    public int fpcr() {
        return fpcr;
    }

    public int fpsr() {
        return fpsr;
    }

    public int fpiar() {
        return fpiar;
    }

    /** Sign and exponent word of FPn. */
    public int registerSe(int n) {
        return fpSe[n];
    }

    /** Mantissa of FPn. */
    public long registerMant(int n) {
        return fpMant[n];
    }

    public void setRegister(int n, int se, long mant) {
        fpSe[n] = se & 0xFFFF;
        fpMant[n] = mant;
    }

    public void setFpcr(int v) {
        fpcr = v & FPCR_MASK;
    }

    public void setFpsr(int v) {
        fpsr = v & FPSR_MASK;
    }

    public boolean isNullState() {
        return nullState;
    }

    public boolean isStrict() {
        return strict;
    }

    // ---- M68kFpuUnit ----

    @Override
    public void reset() {
        for (int i = 0; i < 8; i++) {
            fpSe[i] = Fx80.DEFAULT_NAN_SE;
            fpMant[i] = Fx80.DEFAULT_NAN_MANT;
        }
        fpcr = 0;
        fpsr = 0;
        fpiar = 0;
        nullState = true;
        frameState = STATE_IDLE;
    }

    @Override
    public int general(int opword) {
        int cmd = host.fetch16();
        nullState = false;
        frameState = STATE_IDLE;
        return switch (cmd >>> 13) {
            case 0 -> arithmetic(opword, cmd, false);
            case 2 -> ((cmd >>> 10) & 7) == 7 ? moveConstant(cmd) : arithmetic(opword, cmd, true);
            case 3 -> moveOut(opword, cmd);
            case 4, 5 -> moveControl(opword, cmd);
            case 6, 7 -> moveMultiple(opword, cmd);
            default -> lineF();
        };
    }

    @Override
    public int condition(int predicate) {
        nullState = false;
        if ((predicate & 0x20) != 0) {
            lineF();
            return CONDITION_TRAPPED;
        }
        boolean nan = (fpsr & FPSR_NAN) != 0;
        if ((predicate & FpuOpcodes.PREDICATE_SIGNALING) != 0 && nan) {
            fpsr |= Fx80.BSUN | ACCRUED_IOP;
            if ((fpcr & Fx80.BSUN) != 0) {
                host.exception(M68kVectors.FP_BSUN, M68kVectors.FORMAT_0, host.instructionAddress(), 0);
                return CONDITION_TRAPPED;
            }
        }
        return test(predicate, fpsr) ? 1 : 0;
    }

    /** Predicate {@code predicate} (0..0x1F) against the FPSR condition codes; 0x10..0x1F mirror 0x00..0x0F. */
    static boolean test(int predicate, int fpsr) {
        boolean n = (fpsr & FPSR_N) != 0;
        boolean z = (fpsr & FPSR_Z) != 0;
        boolean nan = (fpsr & FPSR_NAN) != 0;
        return switch (predicate & 0xF) {
            case 0x0 -> false;
            case 0x1 -> z;
            case 0x2 -> !(nan || z || n);
            case 0x3 -> z || !(nan || n);
            case 0x4 -> n && !(nan || z);
            case 0x5 -> z || (n && !nan);
            case 0x6 -> !(nan || z);
            case 0x7 -> !nan;
            case 0x8 -> nan;
            case 0x9 -> nan || z;
            case 0xA -> nan || !(n || z);
            case 0xB -> nan || z || !n;
            case 0xC -> nan || (n && !z);
            case 0xD -> nan || z || n;
            case 0xE -> !z;
            default -> true;
        };
    }

    @Override
    public int save(int opword) {
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        // control alterable or predecrement
        if (mode < 2 || mode == 3 || (mode == 7 && reg > 1))
            return lineF();
        int header;
        if (nullState)
            header = FRAME_NULL;
        else if (frameState == STATE_UNIMP)
            header = FRAME_UNIMP;
        else if (frameState == STATE_BUSY)
            header = FRAME_BUSY;
        else
            header = FRAME_IDLE;
        int size = header == FRAME_NULL ? 4 : ((header >>> 16) & 0xFF) + 4;
        int addr = host.effectiveAddress(mode, reg, size);
        host.write32(addr, header);
        for (int i = 4; i < size; i += 4)
            host.write32(addr + i, 0);
        frameState = STATE_IDLE;
        return CYCLES_SAVE + size / 4;
    }

    @Override
    public int restore(int opword) {
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        // control or postincrement
        if (mode < 2 || mode == 4 || (mode == 7 && reg > 3))
            return lineF();
        int addr = mode == 3 ? host.register(8 + reg) : host.effectiveAddress(mode, reg, 4);
        int header = host.read32(addr);
        int size;
        if (header == FRAME_NULL) {
            size = 4;
        } else {
            int version = header >>> 24;
            int frameSize = (header >>> 16) & 0xFF;
            boolean ok = (header & 0xFFFF) == 0 && (version == 0x41 && (frameSize == 0 || frameSize == 0x30 || frameSize == 0x60)
                    || version == 0x40 && (frameSize == 0 || frameSize == 0x28 || frameSize == 0x60));
            if (!ok) {
                host.exception(M68kVectors.FORMAT_ERROR, M68kVectors.FORMAT_0, host.instructionAddress(), 0);
                return 0;
            }
            size = frameSize + 4;
        }
        if (mode == 3)
            host.effectiveAddress(3, reg, size);
        if (header == FRAME_NULL) {
            reset();
        } else {
            nullState = false;
            frameState = STATE_IDLE;
        }
        return CYCLES_SAVE + size / 4;
    }

    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeBoolean(strict);
        out.writeBoolean(nullState);
        out.writeInt(frameState);
        out.writeInt(fpcr);
        out.writeInt(fpsr);
        out.writeInt(fpiar);
        for (int i = 0; i < 8; i++) {
            out.writeShort(fpSe[i]);
            out.writeLong(fpMant[i]);
        }
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "68040 FPU", STATE_VERSION);
        boolean s = in.readBoolean();
        StateIo.check(s == strict, "saved 68040 FPU is " + (s ? "strict" : "not strict") + ", this one is "
                + (strict ? "strict" : "not strict"));
        nullState = in.readBoolean();
        frameState = StateIo.readCount(in, STATE_BUSY, "68040 FPU frame state");
        fpcr = in.readInt() & FPCR_MASK;
        fpsr = in.readInt() & FPSR_MASK;
        fpiar = in.readInt();
        for (int i = 0; i < 8; i++) {
            fpSe[i] = in.readUnsignedShort();
            fpMant[i] = in.readLong();
        }
    }

    // ---- helpers ----

    private int lineF() {
        host.exception(M68kVectors.LINE_F, M68kVectors.FORMAT_0, host.instructionAddress(), 0);
        return 0;
    }

    /** Strict FPU trap: unimplemented instruction, vector 11, format 2 after the effective address (UM040 9.6.1). */
    private int strictTrap(int ea) {
        frameState = STATE_UNIMP;
        fpiar = host.instructionAddress();
        host.exception(M68kVectors.LINE_F, M68kVectors.FORMAT_2, host.pc(), ea);
        return 0;
    }

    /**
     * Strict FPU trap: unsupported data type, vector 55 (UM040 9.6.2). An input operand
     * (opclass 000 and 010) is a pre-instruction exception, format 0 with PC = the instruction;
     * FMOVE to memory or a data register (opclass 011) is a post-instruction exception, format 3
     * with PC = the next instruction and the effective address.
     */
    private int unsupportedDataType(boolean post, int ea) {
        frameState = STATE_BUSY;
        fpiar = host.instructionAddress();
        if (post)
            host.exception(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, M68kVectors.FORMAT_3, host.pc(), ea);
        else
            host.exception(M68kVectors.FP_UNIMPLEMENTED_DATA_TYPE, M68kVectors.FORMAT_0, host.instructionAddress(), 0);
        return 0;
    }

    private int roundingMode() {
        return (fpcr >>> 4) & 3;
    }

    /** FPCR rounding precision (00 extended, 01 single, 10 double; 11 is undefined, taken as extended). */
    private int fpcrPrecision() {
        return switch ((fpcr >>> 6) & 3) {
            case 1 -> Fx80.PREC_SINGLE;
            case 2 -> Fx80.PREC_DOUBLE;
            default -> Fx80.PREC_EXTENDED;
        };
    }

    private void beginOp(int precision) {
        ctx.mode = roundingMode();
        ctx.precision = precision;
        ctx.enables = fpcr & FPSR_STATUS;
        ctx.flags = 0;
    }

    private static boolean unsupportedOperand(int se, long mant) {
        return Fx80.isDenormal(se, mant) || Fx80.isUnnormal(se, mant);
    }

    /**
     * Sets the exception status byte from {@code ctx.flags}, ORs the accrued byte, loads FPIAR and
     * takes the highest-priority enabled exception (format 3). Returns whether one was taken.
     */
    private boolean finish(int ea) {
        int status = ctx.flags & FPSR_STATUS;
        int accrued = 0;
        if ((status & (Fx80.BSUN | Fx80.SNAN | Fx80.OPERR)) != 0)
            accrued |= ACCRUED_IOP;
        if ((status & Fx80.OVFL) != 0)
            accrued |= ACCRUED_OVFL;
        if ((status & Fx80.UNFL) != 0 && (status & Fx80.INEX2) != 0)
            accrued |= ACCRUED_UNFL;
        if ((status & Fx80.DZ) != 0)
            accrued |= ACCRUED_DZ;
        if ((status & (Fx80.INEX1 | Fx80.INEX2 | Fx80.OVFL)) != 0)
            accrued |= ACCRUED_INEX;
        fpsr = (fpsr & ~FPSR_STATUS) | status | accrued;
        fpiar = host.instructionAddress();
        int pending = status & fpcr;
        if (pending == 0)
            return false;
        int vector = switch (Integer.highestOneBit(pending)) {
            case Fx80.BSUN -> M68kVectors.FP_BSUN;
            case Fx80.SNAN -> M68kVectors.FP_SIGNALING_NAN;
            case Fx80.OPERR -> M68kVectors.FP_OPERAND_ERROR;
            case Fx80.OVFL -> M68kVectors.FP_OVERFLOW;
            case Fx80.UNFL -> M68kVectors.FP_UNDERFLOW;
            case Fx80.DZ -> M68kVectors.FP_DIVIDE_BY_ZERO;
            default -> M68kVectors.FP_INEXACT;
        };
        host.exception(vector, M68kVectors.FORMAT_3, host.pc(), ea);
        return true;
    }

    private void setConditionCodes(int se, long mant) {
        fpsr = (fpsr & ~FPSR_CC) | (Fx80.conditionCodes(se, mant) << 24);
    }

    /** Whether the result may be stored: not when SNAN, OPERR or DZ is raised and enabled. */
    private boolean mayStore() {
        return (ctx.flags & fpcr & (Fx80.SNAN | Fx80.OPERR | Fx80.DZ)) == 0;
    }

    // ---- opmodes ----

    /** The base operation of an opmode (the 68040 FSxxx and FDxxx forms map to their plain one), or -1 if undefined. */
    static int baseOpmode(int opmode) {
        if (opmode < 0x40) {
            return switch (opmode) {
                case 0x05, 0x07, 0x0B, 0x13, 0x17, 0x1B, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x39, 0x3B, 0x3C, 0x3D,
                        0x3E, 0x3F -> -1;
                default -> opmode;
            };
        }
        return switch (opmode) {
            case FpuOpcodes.FSMOVE, FpuOpcodes.FDMOVE -> FpuOpcodes.FMOVE;
            case FpuOpcodes.FSSQRT, FpuOpcodes.FDSQRT -> FpuOpcodes.FSQRT;
            case FpuOpcodes.FSABS, FpuOpcodes.FDABS -> FpuOpcodes.FABS;
            case FpuOpcodes.FSNEG, FpuOpcodes.FDNEG -> FpuOpcodes.FNEG;
            case FpuOpcodes.FSDIV, FpuOpcodes.FDDIV -> FpuOpcodes.FDIV;
            case FpuOpcodes.FSADD, FpuOpcodes.FDADD -> FpuOpcodes.FADD;
            case FpuOpcodes.FSMUL, FpuOpcodes.FDMUL -> FpuOpcodes.FMUL;
            case FpuOpcodes.FSSUB, FpuOpcodes.FDSUB -> FpuOpcodes.FSUB;
            default -> -1;
        };
    }

    /** Whether the real 68040 executes {@code base} in hardware. */
    static boolean inHardware(int base) {
        return switch (base) {
            case FpuOpcodes.FMOVE, FpuOpcodes.FSQRT, FpuOpcodes.FABS, FpuOpcodes.FNEG, FpuOpcodes.FDIV, FpuOpcodes.FADD,
                    FpuOpcodes.FMUL, FpuOpcodes.FSUB, FpuOpcodes.FCMP, FpuOpcodes.FTST -> true;
            default -> false;
        };
    }

    private static boolean dyadic(int base) {
        return switch (base) {
            case FpuOpcodes.FDIV, FpuOpcodes.FMOD, FpuOpcodes.FADD, FpuOpcodes.FMUL, FpuOpcodes.FSGLDIV, FpuOpcodes.FREM,
                    FpuOpcodes.FSCALE, FpuOpcodes.FSGLMUL, FpuOpcodes.FSUB, FpuOpcodes.FCMP -> true;
            default -> false;
        };
    }

    private static int cycles(int base) {
        return switch (base) {
            case FpuOpcodes.FMOVE, FpuOpcodes.FABS, FpuOpcodes.FNEG -> CYCLES_MOVE;
            case FpuOpcodes.FADD, FpuOpcodes.FSUB, FpuOpcodes.FCMP, FpuOpcodes.FTST -> CYCLES_ADD;
            case FpuOpcodes.FMUL -> CYCLES_MUL;
            case FpuOpcodes.FDIV -> CYCLES_DIV;
            case FpuOpcodes.FSQRT -> CYCLES_SQRT;
            default -> CYCLES_SUPPORT;
        };
    }

    // ---- general arithmetic: FPm or EA to FPn ----

    private int arithmetic(int opword, int cmd, boolean memory) {
        int opmode = cmd & 0x7F;
        int dst = (cmd >>> 7) & 7;
        int spec = (cmd >>> 10) & 7;
        int base = baseOpmode(opmode);
        if (base < 0)
            return lineF();
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        int ea = 0;
        int raw = 0;
        if (memory) {
            if (mode == 1 || (mode == 0 && (spec == FpuOpcodes.FORMAT_X || spec == FpuOpcodes.FORMAT_P
                    || spec == FpuOpcodes.FORMAT_D || spec == FpuOpcodes.FORMAT_P_DYNAMIC)))
                return lineF();
            if (mode == 0)
                raw = host.register(reg);
            else
                ea = host.effectiveAddress(mode, reg, FpuOpcodes.formatBytes(spec));
        }
        if (strict && !inHardware(base))
            return strictTrap(ea);
        int cost = cycles(base);
        int precision = opmode < 0x40 ? fpcrPrecision() : (opmode & 4) == 0 ? Fx80.PREC_SINGLE : Fx80.PREC_DOUBLE;
        beginOp(Fx80.PREC_EXTENDED);
        // source operand
        int sSe;
        long sM;
        boolean unsupported;
        if (!memory) {
            sSe = fpSe[spec];
            sM = fpMant[spec];
            unsupported = unsupportedOperand(sSe, sM);
        } else {
            switch (spec) {
                case FpuOpcodes.FORMAT_L, FpuOpcodes.FORMAT_W, FpuOpcodes.FORMAT_B -> {
                    long v;
                    if (mode == 0)
                        v = spec == FpuOpcodes.FORMAT_L ? raw : spec == FpuOpcodes.FORMAT_W ? (short) raw : (byte) raw;
                    else
                        v = spec == FpuOpcodes.FORMAT_L ? host.read32(ea) : spec == FpuOpcodes.FORMAT_W ? (short) host.read16(ea)
                                : (byte) host.read8(ea);
                    Fx80.fromLong(ctx, v);
                    unsupported = false;
                }
                case FpuOpcodes.FORMAT_S -> {
                    int bits = mode == 0 ? raw : host.read32(ea);
                    Fx80.fromSingle(ctx, bits);
                    unsupported = (bits & 0x7F80_0000) == 0 && (bits & 0x7F_FFFF) != 0;
                }
                case FpuOpcodes.FORMAT_D -> {
                    long bits = ((long) host.read32(ea) << 32) | (host.read32(ea + 4) & 0xFFFF_FFFFL);
                    Fx80.fromDouble(ctx, bits);
                    unsupported = (bits & 0x7FF0_0000_0000_0000L) == 0 && (bits & 0xF_FFFF_FFFF_FFFFL) != 0;
                }
                case FpuOpcodes.FORMAT_X -> {
                    int se = host.read16(ea);
                    long m = ((long) host.read32(ea + 4) << 32) | (host.read32(ea + 8) & 0xFFFF_FFFFL);
                    ctx.set(se, m);
                    unsupported = unsupportedOperand(se, m);
                }
                default -> {
                    // packed decimal (static or dynamic k: same on input)
                    if (strict)
                        return unsupportedDataType(false, ea);
                    ctx.mode = roundingMode();
                    FpPacked.decode(ctx, host.read32(ea), host.read32(ea + 4), host.read32(ea + 8));
                    unsupported = false;
                    cost = CYCLES_SUPPORT;
                }
            }
            sSe = ctx.se;
            sM = ctx.mant;
        }
        int dSe = fpSe[dst];
        long dM = fpMant[dst];
        if (strict && (unsupported || (dyadic(base) && unsupportedOperand(dSe, dM))))
            return unsupportedDataType(false, ea);
        int convFlags = ctx.flags;
        beginOp(precision);
        ctx.flags = convFlags;
        switch (base) {
            case FpuOpcodes.FCMP -> {
                int r = Fx80.compare(ctx, dSe, dM, sSe, sM);
                int cc = r == Fx80.UNORDERED ? 1 : r < 0 ? 8 : r == 0 ? (Fx80.isNegative(dSe) ? 12 : 4) : 0;
                fpsr = (fpsr & ~FPSR_CC) | (cc << 24);
                finish(ea);
                return cost;
            }
            case FpuOpcodes.FTST -> {
                if (Fx80.isSignaling(sSe, sM))
                    ctx.flags |= Fx80.SNAN;
                setConditionCodes(sSe, sM);
                finish(ea);
                return cost;
            }
            case FpuOpcodes.FSINCOS, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37 -> {
                FpSupport.transcendental(ctx, FpuOpcodes.FCOS, sSe, sM);
                int cSe = ctx.se;
                long cM = ctx.mant;
                FpSupport.transcendental(ctx, FpuOpcodes.FSIN, sSe, sM);
                if (mayStore()) {
                    fpSe[opmode & 7] = cSe;
                    fpMant[opmode & 7] = cM;
                    fpSe[dst] = ctx.se;
                    fpMant[dst] = ctx.mant;
                }
                setConditionCodes(ctx.se, ctx.mant);
                finish(ea);
                return cost;
            }
            default -> compute(base, dSe, dM, sSe, sM);
        }
        if (mayStore()) {
            fpSe[dst] = ctx.se;
            fpMant[dst] = ctx.mant;
        }
        setConditionCodes(ctx.se, ctx.mant);
        finish(ea);
        return cost;
    }

    /** One operation into {@code ctx}; FMOD and FREM also set the quotient byte. */
    private void compute(int base, int dSe, long dM, int sSe, long sM) {
        switch (base) {
            case FpuOpcodes.FMOVE -> Fx80.round(ctx, sSe, sM);
            case FpuOpcodes.FINT -> Fx80.roundToInteger(ctx, sSe, sM, ctx.mode);
            case FpuOpcodes.FINTRZ -> Fx80.roundToInteger(ctx, sSe, sM, Fx80.RZ);
            case FpuOpcodes.FSQRT -> Fx80.sqrt(ctx, sSe, sM);
            case FpuOpcodes.FABS -> Fx80.round(ctx, Fx80.isNan(sSe, sM) ? sSe : sSe & ~Fx80.SIGN, sM);
            case FpuOpcodes.FNEG -> Fx80.round(ctx, Fx80.isNan(sSe, sM) ? sSe : sSe ^ Fx80.SIGN, sM);
            case FpuOpcodes.FDIV -> Fx80.div(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FADD -> Fx80.add(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FMUL -> Fx80.mul(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FSUB -> Fx80.sub(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FSGLDIV -> FpSupport.sglDiv(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FSGLMUL -> FpSupport.sglMul(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FMOD, FpuOpcodes.FREM -> {
                int q = FpSupport.modRem(ctx, dSe, dM, sSe, sM, base == FpuOpcodes.FREM);
                fpsr = (fpsr & ~FPSR_QUOTIENT) | (q << 16);
            }
            case FpuOpcodes.FSCALE -> FpSupport.scale(ctx, dSe, dM, sSe, sM);
            case FpuOpcodes.FGETEXP -> FpSupport.getExp(ctx, sSe, sM);
            case FpuOpcodes.FGETMAN -> FpSupport.getMan(ctx, sSe, sM);
            default -> FpSupport.transcendental(ctx, base, sSe, sM);
        }
    }

    // ---- FMOVECR ----

    private int moveConstant(int cmd) {
        int dst = (cmd >>> 7) & 7;
        if (strict)
            return strictTrap(0);
        beginOp(fpcrPrecision());
        FpConstantRom.load(ctx, cmd & 0x7F);
        fpSe[dst] = ctx.se;
        fpMant[dst] = ctx.mant;
        setConditionCodes(ctx.se, ctx.mant);
        finish(0);
        return CYCLES_SUPPORT;
    }

    // ---- FMOVE FPn to EA ----

    private int moveOut(int opword, int cmd) {
        int format = (cmd >>> 10) & 7;
        int src = (cmd >>> 7) & 7;
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        boolean toRegister = mode == 0;
        if (mode == 1 || (mode == 7 && reg > 1)
                || (toRegister && format != FpuOpcodes.FORMAT_L && format != FpuOpcodes.FORMAT_S
                && format != FpuOpcodes.FORMAT_W && format != FpuOpcodes.FORMAT_B))
            return lineF();
        int ea = toRegister ? 0 : host.effectiveAddress(mode, reg, FpuOpcodes.formatBytes(format));
        int se = fpSe[src];
        long m = fpMant[src];
        boolean packed = format == FpuOpcodes.FORMAT_P || format == FpuOpcodes.FORMAT_P_DYNAMIC;
        if (strict && (packed || unsupportedOperand(se, m)))
            return unsupportedDataType(true, ea);
        beginOp(Fx80.PREC_EXTENDED);
        switch (format) {
            case FpuOpcodes.FORMAT_L, FpuOpcodes.FORMAT_W, FpuOpcodes.FORMAT_B -> {
                int bits = format == FpuOpcodes.FORMAT_L ? 32 : format == FpuOpcodes.FORMAT_W ? 16 : 8;
                int v = Fx80.toInteger(ctx, se, m, bits);
                if (toRegister) {
                    int old = host.register(reg);
                    host.setRegister(reg, bits == 32 ? v : bits == 16 ? (old & 0xFFFF_0000) | (v & 0xFFFF) : (old & 0xFFFF_FF00) | (v & 0xFF));
                } else if (bits == 32) {
                    host.write32(ea, v);
                } else if (bits == 16) {
                    host.write16(ea, v);
                } else {
                    host.write8(ea, v);
                }
            }
            case FpuOpcodes.FORMAT_S -> {
                int v = Fx80.toSingle(ctx, se, m);
                if (toRegister)
                    host.setRegister(reg, v);
                else
                    host.write32(ea, v);
            }
            case FpuOpcodes.FORMAT_D -> {
                long v = Fx80.toDouble(ctx, se, m);
                host.write32(ea, (int) (v >>> 32));
                host.write32(ea + 4, (int) v);
            }
            case FpuOpcodes.FORMAT_X -> {
                if (Fx80.isSignaling(se, m)) {
                    ctx.flags |= Fx80.SNAN;
                    m |= Fx80.QUIET_BIT;
                }
                host.write32(ea, se << 16);
                host.write32(ea + 4, (int) (m >>> 32));
                host.write32(ea + 8, (int) m);
            }
            default -> {
                int kf = format == FpuOpcodes.FORMAT_P ? cmd : host.register((cmd >>> 4) & 7);
                int k = (kf << 25) >> 25;
                int[] w = FpPacked.encode(ctx, se, m, k);
                host.write32(ea, w[0]);
                host.write32(ea + 4, w[1]);
                host.write32(ea + 8, w[2]);
            }
        }
        finish(ea);
        return packed ? CYCLES_SUPPORT : CYCLES_MOVE;
    }

    // ---- FMOVE and FMOVEM of FPCR, FPSR, FPIAR ----

    private int moveControl(int opword, int cmd) {
        boolean toMemory = (cmd & 0x2000) != 0;
        int list = (cmd >>> 10) & 7;
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        int count = Integer.bitCount(list);
        if (list == 0 || (cmd & 0x03FF) != 0)
            return lineF();
        if ((mode == 0 && count != 1) || (mode == 1 && list != 1))
            return lineF();
        if (toMemory && mode == 7 && reg > 1)
            return lineF();
        if (mode <= 1) {
            int n = reg + (mode == 1 ? 8 : 0);
            if (toMemory)
                host.setRegister(n, controlRegister(list));
            else
                setControlRegister(list, host.register(n));
            return CYCLES_CONTROL;
        }
        int addr = host.effectiveAddress(mode, reg, 4 * count);
        for (int bit = 4; bit != 0; bit >>>= 1) {
            if ((list & bit) == 0)
                continue;
            if (toMemory)
                host.write32(addr, controlRegister(bit));
            else
                setControlRegister(bit, host.read32(addr));
            addr += 4;
        }
        return CYCLES_CONTROL + count;
    }

    /** One control register by its list bit: 4 FPCR, 2 FPSR, 1 FPIAR. */
    private int controlRegister(int bit) {
        return bit == 4 ? fpcr : bit == 2 ? fpsr : fpiar;
    }

    private void setControlRegister(int bit, int v) {
        if (bit == 4)
            fpcr = v & FPCR_MASK;
        else if (bit == 2)
            fpsr = v & FPSR_MASK;
        else
            fpiar = v;
    }

    // ---- FMOVEM of data registers ----

    private int moveMultiple(int opword, int cmd) {
        boolean toMemory = (cmd & 0x2000) != 0;
        int listMode = (cmd >>> 11) & 3;
        boolean dynamic = (listMode & 1) != 0;
        boolean predecrement = (listMode & 2) == 0;
        int mode = (opword >>> 3) & 7;
        int reg = opword & 7;
        if (mode < 2 || (cmd & 0x0700) != 0)
            return lineF();
        if (predecrement) {
            if (!toMemory || mode != 4)
                return lineF();
        } else {
            if (mode == 4)
                return lineF();
            if (toMemory && (mode == 3 || (mode == 7 && reg > 1)))
                return lineF();
            if (!toMemory && mode == 7 && reg == 4)
                return lineF();
        }
        int list = dynamic ? host.register((cmd >>> 4) & 7) & 0xFF : cmd & 0xFF;
        int count = Integer.bitCount(list);
        int addr = host.effectiveAddress(mode, reg, 12 * count);
        for (int i = 0; i < 8; i++) {
            int bit = predecrement ? 1 << i : 0x80 >>> i;
            if ((list & bit) == 0)
                continue;
            if (toMemory) {
                host.write32(addr, fpSe[i] << 16);
                host.write32(addr + 4, (int) (fpMant[i] >>> 32));
                host.write32(addr + 8, (int) fpMant[i]);
            } else {
                fpSe[i] = host.read16(addr) & 0xFFFF;
                fpMant[i] = ((long) host.read32(addr + 4) << 32) | (host.read32(addr + 8) & 0xFFFF_FFFFL);
            }
            addr += 12;
        }
        return CYCLES_CONTROL + CYCLES_PER_REGISTER * count;
    }
}
