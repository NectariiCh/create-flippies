package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A PS/2 keyboard speaking scancode set 2 only (spec 3.6). It holds a 32-byte queue; when the
 * queue fills, its last slot gets the set 2 overrun code 0x00 and further bytes are dropped until
 * that code is read. Typematic repeat runs in emulated time: the last key pressed repeats its make
 * code after the delay, at the rate, both set by command 0xF3.
 */
public final class Ps2Keyboard extends Ps2Device {

    /** Queue depth (spec 3.6). */
    public static final int CAPACITY = 32;
    /** Typematic byte after reset: 500 ms delay, 10.9 characters per second. */
    public static final int DEFAULT_TYPEMATIC = 0x2B;

    /** LED bits (command 0xED). */
    public static final int LED_SCROLL = 1, LED_NUM = 2, LED_CAPS = 4;

    private static final int ACK = 0xFA, RESEND = 0xFE, SELF_TEST_OK = 0xAA;

    private int leds;
    private boolean scanning = true;
    private int typematic = DEFAULT_TYPEMATIC;
    private int pendingCommand;
    private boolean overflowMarked;
    private Ps2Key repeatKey;
    private long nextRepeatMicros;

    public Ps2Keyboard(CycleClock clock, int clockHz) {
        super(clock, clockHz, CAPACITY);
        reset();
    }

    /** The LED state the guest last set: bit 0 Scroll, 1 Num, 2 Caps lock. */
    public int leds() {
        return leds;
    }

    public boolean scanning() {
        return scanning;
    }

    public int typematic() {
        return typematic;
    }

    /** Typematic delay from bits 6..5: 250 ms to 1 s. */
    public long repeatDelayMicros() {
        return (1 + ((typematic >>> 5) & 3)) * 250_000L;
    }

    /** Typematic period from bits 4..0: (8 + A) * 2^B * 4.17 ms, 30 down to 2 characters per second. */
    public long repeatPeriodMicros() {
        return (8 + (typematic & 7)) * (1L << ((typematic >>> 3) & 3)) * 4_170L;
    }

    // ---- host side ----

    public void keyDown(Ps2Key key) {
        advanceTo(clock.now());
        if (acceptsInput() && scanning) {
            pushAll(key.make());
            if (key != Ps2Key.PAUSE) {
                repeatKey = key;
                nextRepeatMicros = nowMicros() + repeatDelayMicros();
            }
        }
        notifyChanged();
    }

    public void keyUp(Ps2Key key) {
        advanceTo(clock.now());
        if (acceptsInput() && scanning)
            pushAll(key.brk());
        if (key == repeatKey)
            repeatKey = null;
        notifyChanged();
    }

    /** Raw bytes into the queue, as if the keyboard had sent them. */
    public void inject(byte... bytes) {
        advanceTo(clock.now());
        if (acceptsInput())
            pushAll(bytes);
        notifyChanged();
    }

    private void pushAll(byte[] bytes) {
        for (byte b : bytes)
            push(b & 0xFF);
    }

    // ---- queue with the set 2 overrun code ----

    @Override
    boolean push(int b) {
        if (overflowMarked) {
            setOverrun(true);
            return false;
        }
        if (free() == 1) {
            super.push(0x00);
            overflowMarked = true;
            setOverrun(true);
            return false;
        }
        return super.push(b);
    }

    @Override
    void afterPop() {
        if (queued() == 0)
            overflowMarked = false;
    }

    /** An emptied queue no longer holds the overrun code, so bytes may queue again. */
    @Override
    void clearQueue() {
        super.clearQueue();
        overflowMarked = false;
    }

    // ---- commands ----

    @Override
    void command(int b) {
        int pending = pendingCommand;
        pendingCommand = 0;
        switch (pending) {
            case 0xED -> {
                leds = b & 7;
                push(ACK);
                return;
            }
            case 0xF0 -> {
                switch (b) {
                    case 0 -> {
                        push(ACK);
                        push(0x02);
                    }
                    case 2 -> push(ACK);
                    default -> push(RESEND);
                }
                return;
            }
            case 0xF3 -> {
                typematic = b & 0x7F;
                push(ACK);
                return;
            }
            default -> { }
        }
        switch (b) {
            case 0xED, 0xF0, 0xF3 -> {
                push(ACK);
                pendingCommand = b;
            }
            case 0xEE -> push(0xEE);
            case 0xF2 -> {
                push(ACK);
                push(0xAB);
                push(0x83);
            }
            case 0xF4 -> {
                scanning = true;
                push(ACK);
            }
            case 0xF5 -> {
                defaults();
                scanning = false;
                push(ACK);
            }
            case 0xF6 -> {
                defaults();
                push(ACK);
            }
            case 0xFE -> resend();
            case 0xFF -> {
                reset();
                clearQueue();
                push(ACK);
                push(SELF_TEST_OK);
            }
            default -> push(RESEND);
        }
    }

    private void defaults() {
        typematic = DEFAULT_TYPEMATIC;
        repeatKey = null;
    }

    /** Power-on: defaults, LEDs off, scanning on, and the self-test byte 0xAA alone if plugged in. */
    @Override
    void reset() {
        resetQueue();
        overflowMarked = false;
        leds = 0;
        scanning = true;
        pendingCommand = 0;
        defaults();
        if (present())
            push(SELF_TEST_OK);
    }

    // ---- saved state ----

    @Override
    void saveState(DataOutput out) throws IOException {
        saveQueue(out);
        out.writeInt(leds);
        out.writeBoolean(scanning);
        out.writeInt(typematic);
        out.writeInt(pendingCommand);
        out.writeBoolean(overflowMarked);
        StateIo.writeNullableUtf(out, repeatKey == null ? null : repeatKey.name());
        out.writeLong(nextRepeatMicros);
    }

    @Override
    void loadState(DataInput in) throws IOException {
        loadQueue(in);
        leds = in.readInt() & 7;
        scanning = in.readBoolean();
        typematic = in.readInt() & 0x7F;
        pendingCommand = in.readInt() & 0xFF;
        overflowMarked = in.readBoolean();
        String key = StateIo.readNullableUtf(in);
        try {
            repeatKey = key == null ? null : Ps2Key.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new StateFormatException("unknown PS/2 key " + key);
        }
        nextRepeatMicros = in.readLong();
    }

    // ---- typematic ----

    @Override
    void advanceTo(long cycle) {
        if (repeatKey == null)
            return;
        long now = CycleClock.cyclesToMicros(cycle, clockHz);
        if (now < nextRepeatMicros)
            return;
        long period = repeatPeriodMicros();
        long due = (now - nextRepeatMicros) / period + 1;
        nextRepeatMicros += due * period;
        if (!acceptsInput() || !scanning)
            return;
        byte[] make = repeatKey.make();
        long n = Math.min(due, CAPACITY + 1);
        for (long i = 0; i < n; i++)
            pushAll(make);
    }

    @Override
    long nextEventCycle() {
        return repeatKey == null ? Long.MAX_VALUE : CycleClock.microsToCycles(nextRepeatMicros, clockHz);
    }
}
