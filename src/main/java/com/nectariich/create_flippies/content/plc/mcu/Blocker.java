package com.nectariich.create_flippies.content.plc.mcu;

/**
 * A builtin that does not return at once (delay(), pulseIn(), delayMicroseconds())
 * parked on the CPU. While it is parked the CPU runs no sketch code but still
 * services interrupts, and fast-forwards time between polls.
 */
public interface Blocker {

    /**
     * The cycle at which {@link #poll} should next be asked. The CPU may skip
     * straight there, charging the skipped cycles as {@link CycleClass#WAIT}.
     * {@code Long.MAX_VALUE} means "only when something external changes", which
     * the CPU bounds by {@link McuEnvironment#nextEventCycle()} and the run target.
     */
    long nextPollCycle();

    /**
     * Whether the call has finished. When it returns true it must already have set
     * the call's return value through {@code call}.
     */
    boolean poll(Invocation call);
}
