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
 * The interval timer (spec 3.3): a 1 MHz free-running counter and a periodic or one-shot
 * countdown, the timer for CPUs without their own. Time is microseconds since power-on derived
 * from the cycle clock; expiries are kept as absolute microsecond times so a periodic timer stays
 * on its grid however lazily it is caught up.
 */
public final class IntervalTimer implements BusDevice {

    /** Register offsets (spec 3.3). */
    public static final int COUNTER_LO = 0x00, COUNTER_HI = 0x04, PERIOD = 0x08, CTRL = 0x0C,
            STATUS = 0x10;
    public static final int SIZE = 0x14;

    /** CTRL bits. */
    public static final int CTRL_ENABLE = 1, CTRL_IRQ_ENABLE = 2, CTRL_ONE_SHOT = 4;
    /** STATUS bit, write 1 to clear. */
    public static final int STATUS_EXPIRED = 1;

    private static final int STATE_VERSION = 1;

    private final CycleClock clock;
    private final int clockHz;
    private final IrqSink irq;

    private long latched;
    private int period;
    private int ctrl;
    private boolean expired;
    /** Absolute microsecond time of the next expiry while enabled with a non-zero period. */
    private long nextExpiryMicros;
    private boolean irqLevel;

    public IntervalTimer(CycleClock clock, int clockHz, IrqSink irq) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.irq = Objects.requireNonNull(irq, "irq");
    }

    @Override
    public int size() {
        return SIZE;
    }

    private long nowMicros() {
        return CycleClock.cyclesToMicros(clock.now(), clockHz);
    }

    private boolean running() {
        return (ctrl & CTRL_ENABLE) != 0 && period != 0;
    }

    @Override
    public int read(int offset, int width) {
        advanceTo(clock.now());
        int v;
        switch (offset & ~3) {
            case COUNTER_LO -> {
                if (Regs.touchesByte0(offset))
                    latched = nowMicros();
                v = (int) latched;
            }
            case COUNTER_HI -> v = (int) (latched >>> 32);
            case PERIOD -> v = period;
            case CTRL -> v = ctrl;
            case STATUS -> v = expired ? STATUS_EXPIRED : 0;
            default -> v = 0;
        }
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        advanceTo(clock.now());
        switch (offset & ~3) {
            case PERIOD -> {
                period = Regs.merge(period, offset, width, value);
                if (Regs.touchesByte3(offset, width) && (ctrl & CTRL_ENABLE) != 0)
                    restart();
            }
            case CTRL -> {
                boolean wasEnabled = (ctrl & CTRL_ENABLE) != 0;
                ctrl = Regs.merge(ctrl, offset, width, value) & 7;
                boolean enabled = (ctrl & CTRL_ENABLE) != 0;
                if (enabled && (!wasEnabled || Regs.touchesByte3(offset, width)))
                    restart();
            }
            case STATUS -> {
                if ((Regs.written(offset, width, value) & STATUS_EXPIRED) != 0)
                    expired = false;
            }
            default -> { }
        }
        updateIrq();
        long next = nextEventCycle();
        if (next != Long.MAX_VALUE)
            clock.requestStopAt(next);
    }

    /** Starts the countdown from now. */
    private void restart() {
        nextExpiryMicros = nowMicros() + Integer.toUnsignedLong(period);
    }

    @Override
    public void reset() {
        latched = 0;
        period = 0;
        ctrl = 0;
        expired = false;
        nextExpiryMicros = 0;
        updateIrq();
    }

    @Override
    public void advanceTo(long cycle) {
        if (running()) {
            long now = CycleClock.cyclesToMicros(cycle, clockHz);
            if (now >= nextExpiryMicros) {
                expired = true;
                if ((ctrl & CTRL_ONE_SHOT) != 0) {
                    ctrl &= ~CTRL_ENABLE;
                } else {
                    long p = Integer.toUnsignedLong(period);
                    nextExpiryMicros += ((now - nextExpiryMicros) / p + 1) * p;
                }
            }
        }
        updateIrq();
    }

    /** Every register, the counter latch, the absolute next expiry and the line level. */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeLong(latched);
        out.writeInt(period);
        out.writeInt(ctrl);
        out.writeBoolean(expired);
        out.writeLong(nextExpiryMicros);
        out.writeBoolean(irqLevel);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "interval timer", STATE_VERSION);
        latched = in.readLong();
        period = in.readInt();
        ctrl = in.readInt() & 7;
        expired = in.readBoolean();
        nextExpiryMicros = in.readLong();
        irqLevel = in.readBoolean();
    }

    /** The next expiry while it would raise the line (IRQ enabled, EXPIRED clear). */
    @Override
    public long nextEventCycle() {
        if (!running() || (ctrl & CTRL_IRQ_ENABLE) == 0 || expired)
            return Long.MAX_VALUE;
        return CycleClock.microsToCycles(nextExpiryMicros, clockHz);
    }

    private void updateIrq() {
        boolean level = expired && (ctrl & CTRL_IRQ_ENABLE) != 0;
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }
}
