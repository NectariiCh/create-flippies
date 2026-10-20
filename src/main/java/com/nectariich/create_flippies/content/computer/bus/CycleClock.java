package com.nectariich.create_flippies.content.computer.bus;

/**
 * A machine's one clock: CPU cycles since power-on. It only moves forward. The CPU keeps its own
 * running count in a local for speed; before every slow-path bus access it publishes the count
 * at the start of the current instruction, and before {@code run} returns it publishes its final
 * count, so devices always see the right "now".
 *
 * The horizon is the cycle the CPU must stop at. {@code Cpu.run} sets it from its
 * {@code untilCycle}; a device lowers it with {@link #requestStopAt} when it schedules an event
 * or needs the machine to act, and the CPU re-reads it after every slow-path access.
 */
public final class CycleClock {

    private long now;
    private long horizon = Long.MAX_VALUE;

    public long now() {
        return now;
    }

    /** Moves the clock to {@code cycle}; earlier values are ignored. */
    public void advanceTo(long cycle) {
        if (cycle > now)
            now = cycle;
    }

    public long horizon() {
        return horizon;
    }

    /** Sets the horizon outright; called by {@code Cpu.run} at entry. */
    public void setHorizon(long cycle) {
        horizon = cycle;
    }

    /** Lowers the horizon to {@code cycle} (never below now); a later cycle than the current horizon is ignored. */
    public void requestStopAt(long cycle) {
        long c = Math.max(cycle, now);
        if (c < horizon)
            horizon = c;
    }

    /** Back to cycle zero, at power-on. */
    public void reset() {
        now = 0;
        horizon = Long.MAX_VALUE;
    }

    /** floor(cycles * 1_000_000 / clockHz) without overflow, for cycles >= 0 and clockHz >= 1 MHz. */
    public static long cyclesToMicros(long cycles, int clockHz) {
        return cycles / clockHz * 1_000_000L + cycles % clockHz * 1_000_000L / clockHz;
    }

    /**
     * The smallest cycle count c with {@code cyclesToMicros(c, clockHz) >= micros}, for
     * micros >= 0; Long.MAX_VALUE if micros is negative or the result does not fit a long.
     */
    public static long microsToCycles(long micros, int clockHz) {
        if (micros < 0)
            return Long.MAX_VALUE;
        long whole = micros / 1_000_000L;
        long part = micros % 1_000_000L;
        if (whole > (Long.MAX_VALUE - clockHz) / clockHz)
            return Long.MAX_VALUE;
        return whole * clockHz + (part * clockHz + 999_999L) / 1_000_000L;
    }
}
