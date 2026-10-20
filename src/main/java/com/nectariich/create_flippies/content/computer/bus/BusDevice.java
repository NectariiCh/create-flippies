package com.nectariich.create_flippies.content.computer.bus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * A device's register block on a bus. Offsets are bytes from the window's base; widths are 1, 2
 * or 4, and the bus only passes naturally aligned accesses. The window a platform maps may be
 * larger than {@link #size()} (offsets the device does not decode read 0 and ignore writes) or
 * smaller (the 8086 VGA port window omits the direct palette).
 *
 * Devices are simulated lazily: they bring themselves up to {@code clock.now()} at the start of
 * every read and write, and the machine catches them up at the end of every CPU segment. When a
 * device schedules an event during a segment it calls {@link CycleClock#requestStopAt}. Requests
 * that change the machine (reset, power off, memory map) are recorded and stop the CPU through
 * {@code requestStopAt(clock.now())}; the machine performs them between runs.
 */
public interface BusDevice {

    /** Bytes of registers the device decodes; a multiple of 4. */
    int size();

    /** Reads {@code width} bytes at {@code offset}, zero-extended. */
    int read(int offset, int width);

    void write(int offset, int width, int value);

    /** Back to the power-on state; interrupt outputs low. */
    void reset();

    /** Catches the device's own activity up to {@code cycle}. Never called with a cycle earlier than a previous one. */
    default void advanceTo(long cycle) {
    }

    /**
     * The earliest cycle at which the device, by itself, would change one of its interrupt lines
     * (given its current enables) or change memory the CPU reads directly (DMA), or
     * Long.MAX_VALUE. Earlier is allowed (it only ends a segment early); later is a bug.
     */
    default long nextEventCycle() {
        return Long.MAX_VALUE;
    }

    /**
     * Writes the device's complete state (spec 1.5): its format version first, then every field,
     * cycle stamps as they are (absolute, so they stay valid when the clock is restored with them).
     * Pure: no catch-up, no side effect. Wiring (clock, sinks, bus, disk) is not state.
     */
    void saveState(DataOutput out) throws IOException;

    /**
     * Replaces the device's state with what {@link #saveState} wrote on a device built the same
     * way. Interrupt sinks are not driven: line levels are part of the restored state on both ends.
     * StateFormatException for an unknown version or values the device cannot hold. After any
     * exception the device's state is unspecified; {@code Machine.loadState} restores the machine.
     */
    void loadState(DataInput in) throws IOException;
}
