package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The whole microcontroller, as the board around it drives it: power, reset, the
 * level on every input, and one run window at a time. Implemented by
 * {@code chip.Stm32f103}; this is all the Minecraft side may touch.
 *
 * Time model: the board calls {@link #runFor} once per game tick with the tick's
 * length. Inputs set before the call hold for the whole window, because they are
 * sampled at the tick boundary; edges on them are seen at the start of the window
 * and fire EXTI interrupts there. Outputs are read after the call through {@link #pin}.
 */
public interface PlcCore {

    /** Writes an image as a programmer would: holds reset, erases, writes, releases. Null erases. */
    void flash(CompiledProgram program);

    CompiledProgram program();

    /** Supply inside or outside the operating range; dropping it loses SRAM, as a power cut does. */
    void setPowered(boolean powered);

    boolean powered();

    /** NRST held low for as long as this is true; releasing it boots from flash. */
    void setResetHeld(boolean held);

    /** A momentary NRST pulse: reboots from flash. */
    void reset();

    /** The level the input front end presents to an MCU pin (Arduino numbering). */
    void setInput(int arduinoPin, boolean high);

    /**
     * Advances emulated time by {@code nanos}, spending at most {@code hostBudgetNanos}
     * of real time on it. If the budget runs out first the rest of the window is
     * dropped and emulated time falls behind; {@link #speed()} reports by how much.
     */
    void runFor(long nanos, long hostBudgetNanos);

    /** What an MCU pin did over the last window. */
    PinDrive pin(int arduinoPin);

    McuState state();

    /** The fault behind {@link McuState#HARD_FAULT}, or null. */
    HardFault fault();

    /** The sketch line that faulted, or 0. */
    int faultLine();

    /** Emulated time per requested time over the last window, 0..1. */
    double speed();

    /** Fraction of the last window's cycles not spent in {@link CycleClass#WAIT}, 0..1. */
    double cpuLoad();

    /** Average power drawn by the MCU's 3.3 V domain over the last window, in watts. */
    double mcuPowerWatts();

    /** Peak stack bytes since reset, or 0 when nothing is running. */
    int stackPeak();

    /** Peak heap bytes since reset, or 0 when nothing is running. */
    int heapPeak();

    /** Emulated time since the last reset, in milliseconds (what millis() would say). */
    long uptimeMillis();
}
