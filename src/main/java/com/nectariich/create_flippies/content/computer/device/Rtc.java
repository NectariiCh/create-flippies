package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The real-time clock (spec 3.4): Unix seconds from a host source plus an offset the guest sets.
 * It runs on host time, not emulated time, as a battery-backed clock would. A guest reset keeps
 * the offset; power-on ({@link #powerOn()}) clears it.
 */
public final class Rtc implements BusDevice {

    /** Register offsets (spec 3.4). */
    public static final int SECONDS_LO = 0x00, SECONDS_HI = 0x04, SET_LO = 0x08, SET_HI = 0x0C;
    public static final int SIZE = 0x10;

    /** The default source: host wall-clock seconds. */
    public static final LongSupplier HOST_TIME = () -> System.currentTimeMillis() / 1000;

    private static final int STATE_VERSION = 1;

    private final LongSupplier source;
    private long offset;
    private long latched;
    private int setLo;
    private int setHi;

    /** {@code source} null means host time. */
    public Rtc(LongSupplier source) {
        this.source = source == null ? HOST_TIME : source;
    }

    /** Seconds since the Unix epoch as the guest sees them. */
    public long seconds() {
        return source.getAsLong() + offset;
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        int v;
        switch (offset & ~3) {
            case SECONDS_LO -> {
                if (Regs.touchesByte0(offset))
                    latched = seconds();
                v = (int) latched;
            }
            case SECONDS_HI -> v = (int) (latched >>> 32);
            default -> v = 0;
        }
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        switch (offset & ~3) {
            case SET_LO -> setLo = Regs.merge(setLo, offset, width, value);
            case SET_HI -> {
                setHi = Regs.merge(setHi, offset, width, value);
                if (Regs.touchesByte3(offset, width)) {
                    long target = (long) setHi << 32 | Integer.toUnsignedLong(setLo);
                    this.offset = target - source.getAsLong();
                }
            }
            default -> { }
        }
    }

    /** Keeps the offset, as a battery-backed clock survives a reset. */
    @Override
    public void reset() {
        latched = 0;
        setLo = 0;
        setHi = 0;
    }

    /**
     * The guest's offset, the latch and the SET halves. The source is host time, so a restored
     * RTC shows the real time that passed while the PC was unloaded, as a battery clock would.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeLong(offset);
        out.writeLong(latched);
        out.writeInt(setLo);
        out.writeInt(setHi);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "RTC", STATE_VERSION);
        offset = in.readLong();
        latched = in.readLong();
        setLo = in.readInt();
        setHi = in.readInt();
    }

    /** Clears the offset too, at machine power-on. */
    public void powerOn() {
        reset();
        offset = 0;
    }
}
