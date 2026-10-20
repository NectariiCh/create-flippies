package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A PS/2 mouse with the standard 3-byte protocol and no wheel (spec 3.6). Movement from the host
 * accumulates; while reporting is enabled one packet goes out per sample period whenever there is
 * movement or a button change. Each packet carries at most -256..255 per axis (with the overflow
 * bit set when clamped); the remainder carries to the next packet. The 48-byte queue drops whole
 * packets on overflow, never partial ones.
 */
public final class Ps2Mouse extends Ps2Device {

    /** Queue depth (spec 3.6). */
    public static final int CAPACITY = 48;
    /** Host button mask bits, the same as the packet's byte 0 bits. */
    public static final int LEFT = 1, RIGHT = 2, MIDDLE = 4;

    private static final int ACK = 0xFA, RESEND = 0xFE;
    private static final int DEFAULT_RATE = 100, DEFAULT_RESOLUTION = 2;

    private boolean reporting;
    private boolean scaling21;
    private int resolution = DEFAULT_RESOLUTION;
    private int sampleRate = DEFAULT_RATE;
    private int pendingCommand;

    private int buttons;
    private int reportedButtons;
    private long dx;
    private long dy;
    /** Microsecond time movement last became pending, and of the last stream packet. */
    private long pendingSince;
    private long lastPacketMicros = Long.MIN_VALUE / 2;

    public Ps2Mouse(CycleClock clock, int clockHz) {
        super(clock, clockHz, CAPACITY);
        reset();
    }

    public boolean reporting() {
        return reporting;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int resolution() {
        return resolution;
    }

    public boolean scaling21() {
        return scaling21;
    }

    private long periodMicros() {
        return 1_000_000L / Math.max(1, sampleRate);
    }

    private boolean pending() {
        return dx != 0 || dy != 0 || buttons != reportedButtons;
    }

    // ---- host side ----

    /** Relative movement; dy positive means up (the host converts from screen coordinates). */
    public void move(int dx, int dy) {
        advanceTo(clock.now());
        if (acceptsInput()) {
            markPending();
            this.dx += dx;
            this.dy += dy;
            advanceTo(clock.now());
        }
        notifyChanged();
    }

    /** Buttons held: bit 0 left, 1 right, 2 middle. */
    public void buttons(int mask) {
        advanceTo(clock.now());
        if (acceptsInput()) {
            markPending();
            buttons = mask & 7;
            advanceTo(clock.now());
        }
        notifyChanged();
    }

    private void markPending() {
        if (!pending())
            pendingSince = nowMicros();
    }

    // ---- packets ----

    private static int clamp(long v) {
        return (int) Math.max(-256, Math.min(255, v));
    }

    /** The standard 2:1 scaling transform. */
    private static int scale(int d) {
        int a = Math.abs(d);
        int s = switch (a) {
            case 0 -> 0;
            case 1, 2 -> 1;
            case 3 -> 3;
            case 4 -> 6;
            case 5 -> 9;
            default -> 2 * a;
        };
        return d < 0 ? -s : s;
    }

    /** Builds the next packet from the counters (consuming them) and queues it whole or drops it. */
    private void sendPacket(boolean applyScaling) {
        int x = clamp(dx);
        int y = clamp(dy);
        boolean xOverflow = x != dx;
        boolean yOverflow = y != dy;
        dx -= x;
        dy -= y;
        if (applyScaling && scaling21) {
            int sx = scale(x), sy = scale(y);
            x = clamp(sx);
            y = clamp(sy);
            xOverflow |= x != sx;
            yOverflow |= y != sy;
        }
        reportedButtons = buttons;
        int b0 = (buttons & 7) | 0x08 | (x < 0 ? 0x10 : 0) | (y < 0 ? 0x20 : 0)
                | (xOverflow ? 0x40 : 0) | (yOverflow ? 0x80 : 0);
        pushGroup(b0, x & 0xFF, y & 0xFF);
    }

    /** Queues all of {@code bytes} or, if they do not fit, none (setting overrun). */
    private void pushGroup(int... bytes) {
        if (free() < bytes.length) {
            setOverrun(true);
            return;
        }
        for (int b : bytes)
            push(b);
    }

    private void clearCounters() {
        dx = 0;
        dy = 0;
        reportedButtons = buttons;
    }

    // ---- commands ----

    @Override
    void command(int b) {
        int pending = pendingCommand;
        pendingCommand = 0;
        if (pending == 0xE8) {
            resolution = b & 3;
            pushGroup(ACK);
            return;
        }
        if (pending == 0xF3) {
            sampleRate = b == 0 ? DEFAULT_RATE : b;
            pushGroup(ACK);
            return;
        }
        switch (b) {
            case 0xE6 -> {
                scaling21 = false;
                pushGroup(ACK);
            }
            case 0xE7 -> {
                scaling21 = true;
                pushGroup(ACK);
            }
            case 0xE8, 0xF3 -> {
                pushGroup(ACK);
                pendingCommand = b;
            }
            case 0xE9 -> pushGroup(ACK, statusByte(), resolution, sampleRate);
            case 0xEA -> pushGroup(ACK);
            case 0xEB -> {
                pushGroup(ACK);
                sendPacket(false);
            }
            case 0xF2 -> pushGroup(ACK, 0x00);
            case 0xF4 -> {
                clearCounters();
                reporting = true;
                pushGroup(ACK);
            }
            case 0xF5 -> {
                reporting = false;
                pushGroup(ACK);
            }
            case 0xF6 -> {
                defaults();
                pushGroup(ACK);
            }
            case 0xFE -> resend();
            case 0xFF -> {
                reset();
                clearQueue();
                pushGroup(ACK, 0xAA, 0x00);
            }
            default -> pushGroup(RESEND);
        }
    }

    /** Status byte 1: bit 6 remote mode (never), 5 reporting, 4 scaling 2:1, 2 left, 1 middle, 0 right. */
    private int statusByte() {
        return (reporting ? 0x20 : 0) | (scaling21 ? 0x10 : 0) | ((buttons & LEFT) != 0 ? 4 : 0)
                | ((buttons & MIDDLE) != 0 ? 2 : 0) | ((buttons & RIGHT) != 0 ? 1 : 0);
    }

    private void defaults() {
        reporting = false;
        scaling21 = false;
        resolution = DEFAULT_RESOLUTION;
        sampleRate = DEFAULT_RATE;
        clearCounters();
    }

    /** Power-on: defaults with reporting off, then 0xAA 0x00 if plugged in. */
    @Override
    void reset() {
        resetQueue();
        pendingCommand = 0;
        buttons = 0;
        defaults();
        lastPacketMicros = Long.MIN_VALUE / 2;
        if (present())
            pushGroup(0xAA, 0x00);
    }

    // ---- saved state ----

    @Override
    void saveState(DataOutput out) throws IOException {
        saveQueue(out);
        out.writeBoolean(reporting);
        out.writeBoolean(scaling21);
        out.writeInt(resolution);
        out.writeInt(sampleRate);
        out.writeInt(pendingCommand);
        out.writeInt(buttons);
        out.writeInt(reportedButtons);
        out.writeLong(dx);
        out.writeLong(dy);
        out.writeLong(pendingSince);
        out.writeLong(lastPacketMicros);
    }

    @Override
    void loadState(DataInput in) throws IOException {
        loadQueue(in);
        reporting = in.readBoolean();
        scaling21 = in.readBoolean();
        resolution = in.readInt() & 3;
        sampleRate = in.readInt() & 0xFF;
        pendingCommand = in.readInt() & 0xFF;
        buttons = in.readInt() & 7;
        reportedButtons = in.readInt() & 7;
        dx = in.readLong();
        dy = in.readLong();
        pendingSince = in.readLong();
        lastPacketMicros = in.readLong();
    }

    // ---- sampling ----

    private long nextPacketMicros() {
        return Math.max(pendingSince, lastPacketMicros + periodMicros());
    }

    @Override
    void advanceTo(long cycle) {
        if (!reporting || !acceptsInput())
            return;
        long now = CycleClock.cyclesToMicros(cycle, clockHz);
        for (int guard = 0; guard < CAPACITY && pending(); guard++) {
            long at = nextPacketMicros();
            if (at > now)
                break;
            sendPacket(true);
            lastPacketMicros = at;
            pendingSince = at;
        }
    }

    @Override
    long nextEventCycle() {
        if (!reporting || !acceptsInput() || !pending())
            return Long.MAX_VALUE;
        return CycleClock.microsToCycles(nextPacketMicros(), clockHz);
    }
}
