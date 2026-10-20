package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * The RV32I machine timer and software interrupt (spec 4.2), laid out like the SiFive CLINT.
 * {@code mtime} is derived from the cycle clock at 1 MHz and is read-only, so it always agrees
 * with the CPU's {@code time} CSR. MTIP is a level: mtime >= mtimecmp, unsigned 64-bit.
 */
public final class Clint implements BusDevice {

    /** Register offsets (spec 4.2). */
    public static final int MSIP = 0x0000, MTIMECMP_LO = 0x4000, MTIMECMP_HI = 0x4004,
            MTIME_LO = 0xBFF8, MTIME_HI = 0xBFFC;
    /** Window decoded, 64 KiB (spec 4.1). */
    public static final int SIZE = 0x1_0000;

    private static final int STATE_VERSION = 1;

    private final CycleClock clock;
    private final int clockHz;
    private IrqSink msipSink = IrqSink.NONE;
    private IrqSink mtipSink = IrqSink.NONE;

    private boolean msip;
    private long mtimecmp = -1L;
    private boolean mtip;

    public Clint(CycleClock clock, int clockHz) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
    }

    /** Connects the two outputs and drives them with their current levels at once. */
    public void setOutputs(IrqSink msip, IrqSink mtip) {
        msipSink = msip == null ? IrqSink.NONE : msip;
        mtipSink = mtip == null ? IrqSink.NONE : mtip;
        msipSink.set(this.msip);
        mtipSink.set(this.mtip);
    }

    public long mtime() {
        return CycleClock.cyclesToMicros(clock.now(), clockHz);
    }

    public long mtimecmp() {
        return mtimecmp;
    }

    public boolean msip() {
        return msip;
    }

    public boolean mtip() {
        return mtip;
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        advanceTo(clock.now());
        int v = switch (offset & ~3) {
            case MSIP -> msip ? 1 : 0;
            case MTIMECMP_LO -> (int) mtimecmp;
            case MTIMECMP_HI -> (int) (mtimecmp >>> 32);
            case MTIME_LO -> (int) mtime();
            case MTIME_HI -> (int) (mtime() >>> 32);
            default -> 0;
        };
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        advanceTo(clock.now());
        switch (offset & ~3) {
            case MSIP -> {
                if (Regs.touchesByte0(offset))
                    setMsip((value & 1) != 0);
            }
            case MTIMECMP_LO -> setMtimecmp((mtimecmp & 0xFFFF_FFFF_0000_0000L)
                    | Integer.toUnsignedLong(Regs.merge((int) mtimecmp, offset, width, value)));
            case MTIMECMP_HI -> setMtimecmp((mtimecmp & 0xFFFF_FFFFL)
                    | (long) Regs.merge((int) (mtimecmp >>> 32), offset, width, value) << 32);
            default -> { }
        }
    }

    private void setMtimecmp(long value) {
        mtimecmp = value;
        updateMtip(clock.now());
        long next = nextEventCycle();
        if (next != Long.MAX_VALUE)
            clock.requestStopAt(next);
    }

    private void setMsip(boolean level) {
        if (level != msip) {
            msip = level;
            msipSink.set(level);
        }
    }

    private void updateMtip(long cycle) {
        boolean level = Long.compareUnsigned(CycleClock.cyclesToMicros(cycle, clockHz), mtimecmp) >= 0;
        if (level != mtip) {
            mtip = level;
            mtipSink.set(level);
        }
    }

    @Override
    public void reset() {
        setMsip(false);
        mtimecmp = -1L;
        updateMtip(clock.now());
    }

    @Override
    public void advanceTo(long cycle) {
        updateMtip(cycle);
    }

    /** MSIP, mtimecmp and the MTIP level; mtime is the clock, restored by the machine. */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeBoolean(msip);
        out.writeLong(mtimecmp);
        out.writeBoolean(mtip);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "CLINT", STATE_VERSION);
        msip = in.readBoolean();
        mtimecmp = in.readLong();
        mtip = in.readBoolean();
    }

    /** The first cycle at which mtime reaches mtimecmp; MAX_VALUE once MTIP is high or never. */
    @Override
    public long nextEventCycle() {
        if (mtip)
            return Long.MAX_VALUE;
        return CycleClock.microsToCycles(mtimecmp, clockHz);
    }
}
