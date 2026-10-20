package com.nectariich.create_flippies.content.computer.cpu;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * One emulated CPU running real machine code from the buses in its {@link CpuHost}.
 *
 * Time: at the start of every {@link #run} the CPU loads its cycle count from
 * {@code clock.now()} (the machine may have moved the clock while it waited) and reloads the bus
 * arrays. Counter registers the guest can read (RV32I mcycle, time, instret) come from the CPU's
 * own counts, not from the clock, which is only published at slow-path accesses and on return.
 *
 * Pins: {@link #setIrq} may be called between runs and also during a run (a device raising its
 * line inside a slow-path access), so pin state lives in fields, never in run locals. Pins are
 * levels; inputs that are edge-triggered on the real chip (8086 NMI, 68000 level 7) are
 * edge-detected inside the CPU.
 *
 * A CPU never changes machine state itself: anything that resets devices or changes the memory
 * map goes through a recorded request (SysCon, {@link CpuHost#resetDevices}) that the machine
 * performs after run returns.
 */
public interface Cpu {

    /** Why {@link #run} came back. */
    enum Exit {
        /** Reached the horizon (untilCycle, or an earlier stop a device requested). */
        REACHED,
        /** The host-time deadline passed first: emulated time is running behind. */
        DEADLINE,
        /** The CPU is idle in its wait instruction until an interrupt pin wakes it. */
        WAITING,
        /** The CPU has stopped for good until reset; see {@link #haltReason()}. */
        HALTED
    }

    CpuModel model();

    /** Cold reset: registers to their reset values, PC to the reset vector. Memory and clock are not touched. */
    void reset();

    /**
     * Calls {@code clock.setHorizon(untilCycle)}, then executes until its cycle count reaches
     * {@code clock.horizon()} (re-read after every slow-path access), the CPU waits or halts, or
     * {@code System.nanoTime()} passes {@code hostDeadlineNanos} (checked at least every 4096
     * instructions). Never runs past the horizon by more than one instruction; returns REACHED at
     * once if the horizon is not after the clock. Called on a waiting CPU, returns WAITING at once
     * unless its wake rule now holds; on a halted CPU, returns HALTED. Publishes its final cycle
     * count to the clock before returning.
     */
    Exit run(long untilCycle, long hostDeadlineNanos);

    /** Drives interrupt input {@code pin} (numbering per CPU model, see the spec); a level. */
    void setIrq(int pin, boolean level);

    boolean halted();

    /** Why the CPU halted, or null. */
    String haltReason();

    /** Address of the next instruction to execute. */
    int pc();

    long instructionsRetired();

    /**
     * Writes the CPU's complete state (spec 1.5): its format version first, then registers, pc,
     * control state, pin levels, counters and any internal wait or halt state. Called between
     * runs only. Pure: no side effect. The memory map is not state (it is reloaded every run).
     */
    void saveState(DataOutput out) throws IOException;

    /**
     * Replaces the CPU's state with what {@link #saveState} wrote on a CPU of the same model and
     * options, between runs. StateFormatException for an unknown version or a CPU built
     * differently (for example RV32I without M). After any exception the state is unspecified;
     * {@code Machine.loadState} restores the machine.
     */
    void loadState(DataInput in) throws IOException;
}
