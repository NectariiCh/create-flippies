package com.nectariich.create_flippies.content.plc.mcu;

import java.util.Arrays;

/**
 * The MCU's one clock: a cycle counter that only moves forward, and a breakdown of
 * what every cycle was charged to. Shared by the VM, the peripherals and the power
 * model, so there is exactly one notion of "now".
 */
public final class CycleLedger {

    private long now;
    private final long[] byClass = new long[CycleClass.values().length];

    public long now() {
        return now;
    }

    /** Spends {@code cycles} of class {@code cls}, advancing time by as much. */
    public void charge(CycleClass cls, long cycles) {
        if (cycles <= 0)
            return;
        now += cycles;
        byClass[cls.ordinal()] += cycles;
    }

    /** Total cycles charged to one class since the last {@link #clear()}. */
    public long cycles(CycleClass cls) {
        return byClass[cls.ordinal()];
    }

    /** A copy of the per-class totals, indexed by ordinal. */
    public long[] snapshot() {
        return byClass.clone();
    }

    /** Back to cycle zero, as on a reset. */
    public void clear() {
        now = 0;
        Arrays.fill(byClass, 0);
    }
}
