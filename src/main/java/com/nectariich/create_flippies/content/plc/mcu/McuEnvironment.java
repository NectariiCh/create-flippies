package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The chip around the CPU, as the CPU needs to see it: implemented by the chip
 * model and handed to builtins through {@link Invocation#environment()}.
 */
public interface McuEnvironment {

    /**
     * The earliest cycle at which anything outside the sketch can change what the
     * sketch would observe: the end of the current run window (when inputs are next
     * sampled), a timer event, a tone ending. The idle-loop fast-forward never skips
     * past it.
     */
    long nextEventCycle();
}
