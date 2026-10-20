package com.nectariich.create_flippies.content.computer.bus;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * The machine's bus (spec 1.2): RAM, ROM and VRAM as fast-path arrays, plus device register
 * windows and slow-path memory regions. The 8086 and 8088 machines use a second instance, with
 * no arrays, as their port space.
 *
 * Lookup order on the slow path: the ROM overlay at 0 (while on), the ROM at its home base, RAM,
 * VRAM, then mapped windows. Regions never overlap; the only exception is the reset-time ROM
 * overlay of the 68000 and ARM2 machines, which answers at 0 on top of whatever is there.
 *
 * Addresses are unsigned; ranges are kept as longs so a region may end exactly at 2^32.
 */
public final class SystemBus implements Bus {

    private static final byte[] NO_BYTES = new byte[0];
    private static final long[] NO_PAGES = new long[0];

    /** Called after a DMA transfer writes RAM, the hook a future decode cache invalidates through. */
    @FunctionalInterface
    public interface WriteListener {
        void written(int address, int length);
    }

    /** One device window or slow-path memory region. Exactly one of device and region is set. */
    private static final class Mapping {
        final long start;
        final long end;
        final BusDevice device;
        final MemoryRegion region;

        Mapping(long start, long end, BusDevice device, MemoryRegion region) {
            this.start = start;
            this.end = end;
            this.device = device;
            this.region = region;
        }
    }

    private final ByteOrder order;
    private final boolean little;

    private byte[] ram = NO_BYTES;
    private int ramBase;
    private byte[] rom = NO_BYTES;
    private int romHome;
    private boolean romOverlay;
    private byte[] vram = NO_BYTES;
    private int vramBase;
    private long[] vramDirty = NO_PAGES;
    private Mapping[] mappings = new Mapping[0];
    private WriteListener ramWriteListener;
    /** Applied to every DMA address: the address lines the machine decodes (24 bits on the 68000). */
    private int dmaAddressMask = -1;

    /** A bus with no RAM, ROM or VRAM: the arrays are empty and the sizes 0. */
    public SystemBus(ByteOrder order) {
        this.order = Objects.requireNonNull(order, "order");
        this.little = order == ByteOrder.LITTLE_ENDIAN;
    }

    // ---- configuration ----

    public void setRam(byte[] ram, int base) {
        Objects.requireNonNull(ram, "ram");
        checkFree(base, ram.length, Kind.RAM);
        this.ram = ram;
        this.ramBase = ram.length == 0 ? 0 : base;
    }

    /** {@code rom} is already padded with zeros to the platform's whole ROM window. */
    public void setRom(byte[] rom, int base) {
        Objects.requireNonNull(rom, "rom");
        checkFree(base, rom.length, Kind.ROM);
        this.rom = rom;
        this.romHome = rom.length == 0 ? 0 : base;
    }

    /** {@code dirtyPages} is the VGA device's page bitmap; it must cover the whole array. */
    public void setVram(byte[] vram, int base, long[] dirtyPages) {
        Objects.requireNonNull(vram, "vram");
        Objects.requireNonNull(dirtyPages, "dirtyPages");
        if ((long) dirtyPages.length * 64 * 4096 < vram.length)
            throw new IllegalArgumentException("dirty page bitmap of " + dirtyPages.length
                    + " words does not cover " + vram.length + " bytes of VRAM");
        checkFree(base, vram.length, Kind.VRAM);
        this.vram = vram;
        this.vramBase = vram.length == 0 ? 0 : base;
        this.vramDirty = vram.length == 0 ? NO_PAGES : dirtyPages;
    }

    /**
     * The address lines a DMA address is decoded with, as the CPU's own accesses are: 0xFF_FFFF on
     * the 68000 machine, whose bus is mapped at 24-bit addresses; all 32 bits (-1) otherwise.
     */
    public void setDmaAddressMask(int mask) {
        this.dmaAddressMask = mask;
    }

    /** ROM also answers at 0 and wins over RAM; romBase() reads 0 while on. 68000 and ARM2 only. */
    public void setRomOverlay(boolean on) {
        romOverlay = on;
    }

    public boolean romOverlay() {
        return romOverlay;
    }

    /** IllegalArgumentException if windowSize is not a positive multiple of 4 or the window overlaps. */
    public void mapDevice(int base, int windowSize, BusDevice device) {
        Objects.requireNonNull(device, "device");
        if (windowSize <= 0 || (windowSize & 3) != 0)
            throw new IllegalArgumentException("device window size must be a positive multiple of 4: " + windowSize);
        if ((base & 3) != 0)
            throw new IllegalArgumentException(String.format("device window base 0x%08x is not word aligned", base));
        addMapping(base, windowSize, device, null);
    }

    /** Slow-path memory such as the 8086 banked VRAM view and text alias. */
    public void mapMemory(int base, MemoryRegion region) {
        Objects.requireNonNull(region, "region");
        addMapping(base, region.length(), null, region);
    }

    public void setRamWriteListener(WriteListener listener) {
        ramWriteListener = listener;
    }

    private void addMapping(int base, int size, BusDevice device, MemoryRegion region) {
        checkFree(base, size, Kind.MAPPING);
        long start = Integer.toUnsignedLong(base);
        Mapping[] next = Arrays.copyOf(mappings, mappings.length + 1);
        next[mappings.length] = new Mapping(start, start + size, device, region);
        Arrays.sort(next, (a, b) -> Long.compare(a.start, b.start));
        mappings = next;
    }

    private enum Kind { RAM, ROM, VRAM, MAPPING }

    /** Throws if {@code [base, base + size)} leaves the 32-bit space or overlaps anything but the {@code replacing} slot. */
    private void checkFree(int base, int size, Kind replacing) {
        if (size == 0)
            return;
        long start = Integer.toUnsignedLong(base);
        long end = start + size;
        if (end > 1L << 32)
            throw new IllegalArgumentException(String.format("region at 0x%08x of %d bytes passes the end of the address space", base, size));
        if (replacing != Kind.RAM)
            checkOverlap(start, end, ramBase, ram.length, "RAM");
        if (replacing != Kind.ROM)
            checkOverlap(start, end, romHome, rom.length, "ROM");
        if (replacing != Kind.VRAM)
            checkOverlap(start, end, vramBase, vram.length, "VRAM");
        for (Mapping m : mappings)
            if (start < m.end && m.start < end)
                throw new IllegalArgumentException(String.format("region at 0x%08x overlaps the window at 0x%08x", base, (int) m.start));
    }

    private static void checkOverlap(long start, long end, int otherBase, int otherSize, String what) {
        if (otherSize == 0)
            return;
        long os = Integer.toUnsignedLong(otherBase);
        if (start < os + otherSize && os < end)
            throw new IllegalArgumentException(String.format("region at 0x%08x overlaps %s", (int) start, what));
    }

    // ---- Bus: fast-path arrays ----

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
        return romOverlay ? 0 : romHome;
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

    // ---- Bus: accesses ----

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

    /**
     * Index of {@code address} in an array region at {@code base} of {@code size} bytes, -1 if the
     * address is outside it, or throws if the access starts inside but crosses the end.
     */
    private static int indexIn(long a, int base, int size, int width, int address, boolean write) {
        if (size == 0)
            return -1;
        long off = a - Integer.toUnsignedLong(base);
        if (off < 0 || off >= size)
            return -1;
        if (off + width > size)
            throw crossing(address, width, write);
        return (int) off;
    }

    private int read(int address, int width) {
        long a = Integer.toUnsignedLong(address);
        int i;
        if (romOverlay && (i = indexIn(a, 0, rom.length, width, address, false)) >= 0)
            return ByteArrays.load(rom, i, width, little);
        if ((i = indexIn(a, romHome, rom.length, width, address, false)) >= 0)
            return ByteArrays.load(rom, i, width, little);
        if ((i = indexIn(a, ramBase, ram.length, width, address, false)) >= 0)
            return ByteArrays.load(ram, i, width, little);
        if ((i = indexIn(a, vramBase, vram.length, width, address, false)) >= 0)
            return ByteArrays.load(vram, i, width, little);
        Mapping m = find(a);
        if (m == null)
            throw new BusFault(address, false, String.format("nothing at 0x%08x", address));
        long off = a - m.start;
        if (off + width > m.end - m.start)
            throw crossing(address, width, false);
        if (m.region != null)
            return m.region.load((int) off, width, little);
        if ((off & (width - 1)) != 0)
            throw misaligned(address, width, false);
        return m.device.read((int) off, width) & mask(width);
    }

    private void write(int address, int width, int value) {
        long a = Integer.toUnsignedLong(address);
        int i;
        if (romOverlay && indexIn(a, 0, rom.length, width, address, true) >= 0)
            throw new BusFault(address, true, String.format("write to ROM at 0x%08x", address));
        if (indexIn(a, romHome, rom.length, width, address, true) >= 0)
            throw new BusFault(address, true, String.format("write to ROM at 0x%08x", address));
        if ((i = indexIn(a, ramBase, ram.length, width, address, true)) >= 0) {
            ByteArrays.store(ram, i, width, value, little);
            return;
        }
        if ((i = indexIn(a, vramBase, vram.length, width, address, true)) >= 0) {
            ByteArrays.store(vram, i, width, value, little);
            ByteArrays.markPages(vramDirty, i, width);
            return;
        }
        Mapping m = find(a);
        if (m == null)
            throw new BusFault(address, true, String.format("nothing at 0x%08x", address));
        long off = a - m.start;
        if (off + width > m.end - m.start)
            throw crossing(address, width, true);
        if (m.region != null) {
            if (!m.region.writable())
                throw new BusFault(address, true, String.format("write to read-only memory at 0x%08x", address));
            m.region.store((int) off, width, value, little);
            return;
        }
        if ((off & (width - 1)) != 0)
            throw misaligned(address, width, true);
        m.device.write((int) off, width, value & mask(width));
    }

    private Mapping find(long a) {
        Mapping[] ms = mappings;
        int lo = 0, hi = ms.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Mapping m = ms[mid];
            if (a < m.start)
                hi = mid - 1;
            else if (a >= m.end)
                lo = mid + 1;
            else
                return m;
        }
        return null;
    }

    private static int mask(int width) {
        return width == 4 ? -1 : (1 << (8 * width)) - 1;
    }

    private static BusFault crossing(int address, int width, boolean write) {
        return new BusFault(address, write, String.format("%d-byte access at 0x%08x crosses a region edge", width, address));
    }

    private static BusFault misaligned(int address, int width, boolean write) {
        return new BusFault(address, write, String.format("misaligned %d-byte device access at 0x%08x", width, address));
    }

    // ---- DMA ----

    /**
     * The array range for a DMA transfer, or null if it is not wholly inside RAM, VRAM or one
     * writable region. The address is first masked by {@link #setDmaAddressMask}.
     */
    public DmaWindow dma(int guestAddress, int length) {
        if (length <= 0)
            return null;
        final int address = guestAddress & dmaAddressMask;
        long a = Integer.toUnsignedLong(address);
        long end = a + length;
        if (romOverlay && rom.length > 0 && a < rom.length)
            return null;
        if (inside(a, end, ramBase, ram.length)) {
            int off = (int) (a - Integer.toUnsignedLong(ramBase));
            return new DmaWindow(ram, off, length, () -> {
                WriteListener l = ramWriteListener;
                if (l != null)
                    l.written(address, length);
            });
        }
        if (inside(a, end, vramBase, vram.length)) {
            int off = (int) (a - Integer.toUnsignedLong(vramBase));
            long[] pages = vramDirty;
            return new DmaWindow(vram, off, length, () -> ByteArrays.markPages(pages, off, length));
        }
        Mapping m = find(a);
        if (m != null && m.region != null && m.region.writable() && end <= m.end) {
            MemoryRegion r = m.region;
            int dataIndex = r.offset() + (int) (a - m.start);
            return new DmaWindow(r.data(), dataIndex, length, () -> r.markDirty(dataIndex, length));
        }
        return null;
    }

    private static boolean inside(long a, long end, int base, int size) {
        if (size == 0)
            return false;
        long b = Integer.toUnsignedLong(base);
        return a >= b && end <= b + size;
    }
}
