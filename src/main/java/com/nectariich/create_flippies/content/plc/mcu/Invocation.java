package com.nectariich.create_flippies.content.plc.mcu;

/**
 * One call from a sketch into a {@link Builtin}, as the VM presents it.
 *
 * Arguments arrive already converted to the builtin's declared parameter types:
 * integers sign- or zero-extended into a long, pointers as unsigned 32-bit
 * addresses, float as {@code Float.floatToRawIntBits}, double as
 * {@code Double.doubleToRawLongBits}. Return values go back the same way.
 */
public interface Invocation {

    int argCount();

    long arg(int index);

    float argFloat(int index);

    double argDouble(int index);

    void returnValue(long value);

    void returnFloat(float value);

    void returnDouble(double value);

    Memory memory();

    CycleLedger ledger();

    /** The chip the call is running on; builtins cast it to their own implementation. */
    McuEnvironment environment();

    /** Spends cycles on the builtin's behalf. Every builtin must charge what its real counterpart costs. */
    void charge(CycleClass cls, long cycles);

    /** Parks the calling program until {@code blocker} finishes; see {@link Blocker}. */
    void block(Blocker blocker);

    /**
     * Says the call read the clock. The VM's idle-loop fast-forward may then only
     * skip ahead to the next multiple of {@code resolutionCycles} (72000 for millis,
     * 72 for micros) rather than to the next external event.
     */
    void noteTimeRead(long resolutionCycles);

    /**
     * Says the call changed state the sketch can observe later: a pin level or mode,
     * the PRNG, an attached interrupt. A loop iteration that does this is not idle.
     * A call that writes the value already there should not say so.
     */
    void noteSideEffect();

    void setInterruptsEnabled(boolean enabled);

    boolean interruptsEnabled();

    /** The sketch line the call came from, for diagnostics. */
    int sourceLine();
}
