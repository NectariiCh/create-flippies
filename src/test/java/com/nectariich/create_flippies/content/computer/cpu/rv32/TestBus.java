package com.nectariich.create_flippies.content.computer.cpu.rv32;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.BusFault;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;

import java.nio.ByteOrder;

/**
 * A small {@link Bus} for the CPU tests (spec 7.1): RAM, ROM, VRAM and one device window, no
 * machine. It follows the whole bus contract ({@link TestBusContractTest} checks it), so the CPU
 * is tested against the same rules the machine's bus obeys.
 */
final class TestBus implements Bus {

    private final ByteOrder order;
    private final byte[] ram;
    private final int ramBase;
    private final byte[] rom;
    private final int romBase;
    private final byte[] vram;
    private final int vramBase;
    private final long[] vramDirty;
    private final int deviceBase;
    private final int deviceWindow;
    private final BusDevice device;

    TestBus(ByteOrder order, int ramBase, int ramSize, int romBase, byte[] rom, int vramBase, int vramSize,
            int deviceBase, int deviceWindow, BusDevice device) {
        this.order = order;
        this.ram = new byte[ramSize];
        this.ramBase = ramBase;
        this.rom = rom.clone();
        this.romBase = romBase;
        this.vram = new byte[vramSize];
        this.vramBase = vramBase;
        this.vramDirty = new long[(vramSize + (1 << 18) - 1) >>> 18];
        this.deviceBase = deviceBase;
        this.deviceWindow = device == null ? 0 : deviceWindow;
        this.device = device;
    }

    /** The RV32I machine's map (spec 4.1) with {@code ramSize} of RAM and {@code device} at COM1's window. */
    static TestBus rv32(int ramSize, byte[] romImage, BusDevice device) {
        byte[] rom = new byte[Rv32Map.ROM_SIZE];
        System.arraycopy(romImage, 0, rom, 0, romImage.length);
        return new TestBus(ByteOrder.LITTLE_ENDIAN, Rv32Map.RAM_BASE, ramSize, Rv32Map.ROM_BASE, rom,
                Rv32Map.VRAM_BASE, Rv32Map.VRAM_SIZE, Rv32Map.COM1_BASE, Rv32Map.DEVICE_WINDOW, device);
    }

    // ---- region lookup ----

    /** Offset of an access of {@code width} at {@code address} inside [base, base + size), -1 if it starts outside; faults if it crosses the end. */
    private static int offset(int address, int width, int base, int size, boolean write) {
        long o = Integer.toUnsignedLong(address) - Integer.toUnsignedLong(base);
        if (o < 0 || o >= size)
            return -1;
        if (o + width > size)
            throw new BusFault(address, write, "access crosses a region edge");
        return (int) o;
    }

    private int compose(byte[] a, int o, int width) {
        int v = 0;
        for (int i = 0; i < width; i++) {
            int b = a[o + i] & 0xFF;
            v |= order == ByteOrder.LITTLE_ENDIAN ? b << (8 * i) : b << (8 * (width - 1 - i));
        }
        return v;
    }

    private void store(byte[] a, int o, int width, int value) {
        for (int i = 0; i < width; i++) {
            int shift = order == ByteOrder.LITTLE_ENDIAN ? 8 * i : 8 * (width - 1 - i);
            a[o + i] = (byte) (value >>> shift);
        }
    }

    private int read(int address, int width) {
        int o;
        if ((o = offset(address, width, romBase, rom.length, false)) >= 0)
            return compose(rom, o, width);
        if ((o = offset(address, width, ramBase, ram.length, false)) >= 0)
            return compose(ram, o, width);
        if ((o = offset(address, width, vramBase, vram.length, false)) >= 0)
            return compose(vram, o, width);
        if ((o = deviceOffset(address, width, false)) >= 0) {
            int v = device.read(o, width);
            return width == 4 ? v : v & ((1 << (8 * width)) - 1);
        }
        throw new BusFault(address, false, "unmapped");
    }

    private void write(int address, int width, int value) {
        int o;
        if (offset(address, width, romBase, rom.length, true) >= 0)
            throw new BusFault(address, true, "write to ROM");
        if ((o = offset(address, width, ramBase, ram.length, true)) >= 0) {
            store(ram, o, width, value);
            return;
        }
        if ((o = offset(address, width, vramBase, vram.length, true)) >= 0) {
            store(vram, o, width, value);
            for (int i : new int[] { o, o + width - 1 })
                vramDirty[i >>> 18] |= 1L << ((i >>> 12) & 63);
            return;
        }
        if ((o = deviceOffset(address, width, true)) >= 0) {
            device.write(o, width, width == 4 ? value : value & ((1 << (8 * width)) - 1));
            return;
        }
        throw new BusFault(address, true, "unmapped");
    }

    private int deviceOffset(int address, int width, boolean write) {
        if (device == null)
            return -1;
        int o = offset(address, width, deviceBase, deviceWindow, write);
        if (o >= 0 && (o & (width - 1)) != 0)
            throw new BusFault(address, write, "misaligned device access");
        return o;
    }

    // ---- Bus ----

    @Override
    public int read8(int address) {
        return read(address, 1);
    }

    @Override
    public int read16(int address) {
        return read(address, 2);
    }

    @Override
    public int read32(int address) {
        return read(address, 4);
    }

    @Override
    public void write8(int address, int value) {
        write(address, 1, value);
    }

    @Override
    public void write16(int address, int value) {
        write(address, 2, value);
    }

    @Override
    public void write32(int address, int value) {
        write(address, 4, value);
    }

    @Override
    public ByteOrder order() {
        return order;
    }

    @Override
    public byte[] ramArray() {
        return ram;
    }

    @Override
    public int ramBase() {
        return ramBase;
    }

    @Override
    public int ramSize() {
        return ram.length;
    }

    @Override
    public byte[] romArray() {
        return rom;
    }

    @Override
    public int romBase() {
        return romBase;
    }

    @Override
    public int romSize() {
        return rom.length;
    }

    @Override
    public byte[] vramArray() {
        return vram;
    }

    @Override
    public int vramBase() {
        return vramBase;
    }

    @Override
    public int vramSize() {
        return vram.length;
    }

    @Override
    public long[] vramDirtyPages() {
        return vramDirty;
    }
}
