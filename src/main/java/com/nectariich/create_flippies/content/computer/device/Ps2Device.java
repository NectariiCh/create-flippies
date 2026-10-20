package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * What a PS/2 keyboard and mouse share (spec 3.6): the byte queue towards the controller, the
 * command byte path, presence, and the port enable that gates host input. Bytes are available at
 * once; there is no byte-time emulation.
 *
 * The controller installs a change listener so it can update its interrupt lines when a host call
 * (a key press, a mouse move) queues bytes between CPU runs.
 */
public abstract class Ps2Device {

    protected final CycleClock clock;
    protected final int clockHz;
    private final byte[] queue;
    private int head;
    private int count;
    private boolean overrun;
    private int lastSent = 0xFE;
    private boolean present = true;
    private boolean portEnabled;
    private Runnable changed = () -> { };

    Ps2Device(CycleClock clock, int clockHz, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.queue = new byte[capacity];
    }

    // ---- queue ----

    /** Bytes waiting for the controller. */
    public int queued() {
        return count;
    }

    final int capacity() {
        return queue.length;
    }

    final int free() {
        return queue.length - count;
    }

    /** Queues one byte, or drops it (setting overrun) when full; {@link #push} may override. */
    boolean push(int b) {
        if (count == queue.length) {
            overrun = true;
            return false;
        }
        queue[(head + count) % queue.length] = (byte) b;
        count++;
        lastSent = b & 0xFF;
        return true;
    }

    /** Pops the next byte, 0 when empty. */
    int pop() {
        if (count == 0)
            return 0;
        int b = queue[head] & 0xFF;
        head = (head + 1) % queue.length;
        count--;
        afterPop();
        return b;
    }

    /** Hook after a byte left the queue. */
    void afterPop() {
    }

    void clearQueue() {
        head = 0;
        count = 0;
    }

    boolean overrun() {
        return overrun;
    }

    void setOverrun(boolean value) {
        overrun = value;
    }

    /** Queues the last byte sent again, for the 0xFE resend command. */
    void resend() {
        push(lastSent);
    }

    // ---- presence and port enable ----

    public boolean present() {
        return present;
    }

    /** Whether a device is plugged in; an absent device queues nothing and ignores commands. */
    public void setPresent(boolean present) {
        this.present = present;
        if (!present)
            clearQueue();
        changed.run();
    }

    boolean portEnabled() {
        return portEnabled;
    }

    void setPortEnabled(boolean enabled) {
        portEnabled = enabled;
    }

    /** Whether host input may reach the device: plugged in and its port enabled. */
    final boolean acceptsInput() {
        return present && portEnabled;
    }

    void setChangeListener(Runnable listener) {
        changed = Objects.requireNonNull(listener, "listener");
    }

    final void notifyChanged() {
        changed.run();
    }

    final long nowMicros() {
        return CycleClock.cyclesToMicros(clock.now(), clockHz);
    }

    // ---- per device ----

    /** A command or data byte written by the guest; replies are queued at once. */
    abstract void command(int b);

    /** Power-on state and its power-on bytes. */
    abstract void reset();

    /** Catches timed activity (typematic repeat, mouse sampling) up to {@code cycle}. */
    abstract void advanceTo(long cycle);

    /** The next cycle timed activity would queue bytes, or Long.MAX_VALUE. */
    abstract long nextEventCycle();

    /** The device's own state; the controller's format version covers it. */
    abstract void saveState(DataOutput out) throws IOException;

    abstract void loadState(DataInput in) throws IOException;

    /** The queue (oldest byte first), overrun, last byte sent, presence and port enable. */
    final void saveQueue(DataOutput out) throws IOException {
        StateIo.writeRing(out, queue, head, count);
        out.writeBoolean(overrun);
        out.writeInt(lastSent);
        out.writeBoolean(present);
        out.writeBoolean(portEnabled);
    }

    final void loadQueue(DataInput in) throws IOException {
        head = 0;
        count = StateIo.readRing(in, queue, "PS/2 queue");
        overrun = in.readBoolean();
        lastSent = in.readInt() & 0xFF;
        present = in.readBoolean();
        portEnabled = in.readBoolean();
    }

    /** Back to the power-on queue state, shared by both devices' resets. */
    final void resetQueue() {
        clearQueue();
        overrun = false;
        lastSent = 0xFE;
    }
}
