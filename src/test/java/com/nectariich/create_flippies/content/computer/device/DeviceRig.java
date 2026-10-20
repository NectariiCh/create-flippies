package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;

import java.nio.ByteOrder;

/**
 * A device under test mapped on a little-endian {@link SystemBus} at {@link #BASE}, driven
 * through bus accesses with offsets relative to the window, plus the clock the device reads.
 * Stands in for the CPU: it moves the clock between accesses.
 */
final class DeviceRig {

    static final int BASE = 0x1000_0000;
    static final int RAM_BASE = 0x8000_0000;
    static final int VRAM_BASE = 0x2000_0000;

    final CycleClock clock = new CycleClock();
    final SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);

    DeviceRig map(BusDevice device, int window) {
        bus.mapDevice(BASE, window, device);
        return this;
    }

    int r8(int off) {
        return bus.read8(BASE + off);
    }

    int r16(int off) {
        return bus.read16(BASE + off);
    }

    int r32(int off) {
        return bus.read32(BASE + off);
    }

    void w8(int off, int v) {
        bus.write8(BASE + off, v);
    }

    void w16(int off, int v) {
        bus.write16(BASE + off, v);
    }

    void w32(int off, int v) {
        bus.write32(BASE + off, v);
    }

    /** Moves the clock to {@code cycle}, as a CPU would before its next access. */
    void at(long cycle) {
        clock.advanceTo(cycle);
    }

    /** An interrupt line that records its level and how many times it changed. */
    static final class Line implements IrqSink {
        boolean level;
        int changes;

        @Override
        public void set(boolean level) {
            if (level != this.level)
                changes++;
            this.level = level;
        }
    }
}
