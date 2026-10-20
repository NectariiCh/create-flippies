package com.nectariich.create_flippies.content.computer.cpu.rv32;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A device for the CPU tests: 64 value-addressed 32-bit registers that read back what was written,
 * a log of writes, and a hook run after every write so a test can lower the clock's horizon or
 * drive a CPU pin from inside a device access.
 */
final class TestDevice implements BusDevice {

    /** One write as the device saw it. */
    record Write(int offset, int width, int value) { }

    final int[] regs = new int[64];
    final List<Write> writes = new ArrayList<>();
    int reads;
    /** Run after every write; replace to give the device a side effect. */
    Runnable onWrite = () -> { };

    @Override
    public int size() {
        return regs.length * 4;
    }

    @Override
    public int read(int offset, int width) {
        reads++;
        if (offset >= size())
            return 0;
        int v = regs[offset >>> 2] >>> (8 * (offset & 3));
        return width == 4 ? v : v & ((1 << (8 * width)) - 1);
    }

    @Override
    public void write(int offset, int width, int value) {
        writes.add(new Write(offset, width, value));
        if (offset < size()) {
            int shift = 8 * (offset & 3);
            int mask = (width == 4 ? -1 : (1 << (8 * width)) - 1) << shift;
            int i = offset >>> 2;
            regs[i] = (regs[i] & ~mask) | ((value << shift) & mask);
        }
        onWrite.run();
    }

    @Override
    public void reset() {
        Arrays.fill(regs, 0);
        writes.clear();
    }

    @Override
    public void saveState(java.io.DataOutput out) {
        throw new UnsupportedOperationException("test device has no saved state");
    }

    @Override
    public void loadState(java.io.DataInput in) {
        throw new UnsupportedOperationException("test device has no saved state");
    }
}
