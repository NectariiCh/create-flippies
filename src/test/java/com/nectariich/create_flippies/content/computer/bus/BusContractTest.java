package com.nectariich.create_flippies.content.computer.bus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/**
 * The {@link Bus} contract (spec 1.2 and 7.2), written once and run against every bus
 * implementation: the machine's {@code SystemBus} and the CPU tests' small {@code TestBus}. A
 * subclass only says how to build a bus for a {@link Layout}.
 *
 * Every test runs twice, on a little-endian and a big-endian layout, with a recording device
 * mapped in a window larger than the registers it decodes.
 */
public abstract class BusContractTest {

    protected record Layout(ByteOrder order, int ramBase, int ramSize, int romBase, byte[] rom,
                            int vramBase, int vramSize, int deviceBase, int deviceWindow) { }

    /** A bus with RAM, ROM (rom is already zero-padded), VRAM and {@code device} mapped per layout. */
    protected abstract Bus createBus(Layout layout, BusDevice device);

    /** Registers the recording device decodes; deliberately smaller than the window. */
    protected static final int DEVICE_SIZE = 0x10;

    /** Nothing is mapped here in either layout. */
    private static final int UNMAPPED = 0x4000_0000;

    /** One access as the device saw it. */
    protected record Access(boolean write, int offset, int width, int value) { }

    /**
     * A device that records every access and answers reads with a value made from the offset and
     * width, so a test can tell which access produced it.
     */
    protected static final class RecordingDevice implements BusDevice {

        public final List<Access> accesses = new ArrayList<>();

        /** The value a read of {@code width} bytes at {@code offset} returns. */
        public static int valueFor(int offset, int width) {
            int v = 0x5A00_0000 | (offset << 4) | width;
            return width == 4 ? v : v & ((1 << (8 * width)) - 1);
        }

        @Override
        public int size() {
            return DEVICE_SIZE;
        }

        @Override
        public int read(int offset, int width) {
            accesses.add(new Access(false, offset, width, 0));
            return valueFor(offset, width);
        }

        @Override
        public void write(int offset, int width, int value) {
            accesses.add(new Access(true, offset, width, value));
        }

        @Override
        public void reset() {
            accesses.clear();
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

    // ---- layouts ----

    /** A 60 KiB ROM window with a recognisable first 256 bytes and zeros after. */
    private static byte[] rom() {
        byte[] rom = new byte[0xF000];
        for (int i = 0; i < 256; i++)
            rom[i] = (byte) (0x80 + i * 7);
        rom[0] = 0x12;
        rom[1] = 0x34;
        rom[2] = 0x56;
        rom[3] = 0x78;
        return rom;
    }

    /** The RV32I machine's map, the one the core builds now. */
    private static Layout littleEndian() {
        return new Layout(ByteOrder.LITTLE_ENDIAN, 0x8000_0000, 64 * 1024, 0x0000_1000, rom(),
                0x2000_0000, 0x8_0000, 0x1000_0000, 0x1000);
    }

    /** A different map in big-endian order, so nothing passes by assuming the RV32I addresses. */
    private static Layout bigEndian() {
        return new Layout(ByteOrder.BIG_ENDIAN, 0x0010_0000, 128 * 1024, 0x00F0_0000, rom(),
                0x0020_0000, 0x8_0000, 0x00E0_0000, 0x100);
    }

    /** Runs {@code test} on a fresh bus and device for each layout. */
    private void forEachLayout(BiConsumer<Fixture, String> test) {
        for (Layout layout : List.of(littleEndian(), bigEndian())) {
            RecordingDevice device = new RecordingDevice();
            Bus bus = createBus(layout, device);
            assertNotNull(bus, "createBus returned null");
            test.accept(new Fixture(layout, bus, device), layout.order().toString());
        }
    }

    private record Fixture(Layout layout, Bus bus, RecordingDevice device) {
        boolean little() {
            return layout.order() == ByteOrder.LITTLE_ENDIAN;
        }
    }

    // ---- fast-path arrays ----

    @Test
    void fastPathArraysBasesAndSizesMatchTheLayout() {
        forEachLayout((f, name) -> {
            Layout l = f.layout;
            Bus bus = f.bus;
            assertEquals(l.order(), bus.order(), name);
            assertEquals(l.ramBase(), bus.ramBase(), name);
            assertEquals(l.ramSize(), bus.ramSize(), name);
            assertEquals(l.ramSize(), bus.ramArray().length, name);
            assertEquals(l.romBase(), bus.romBase(), name);
            assertEquals(l.rom().length, bus.romSize(), name);
            assertEquals(bus.romSize(), bus.romArray().length, name);
            assertEquals(l.vramBase(), bus.vramBase(), name);
            assertEquals(l.vramSize(), bus.vramSize(), name);
            assertEquals(l.vramSize(), bus.vramArray().length, name);
            assertNotNull(bus.vramDirtyPages(), name);
            assertTrue(bus.vramDirtyPages().length * 64L * 4096 >= l.vramSize(), name + ": dirty bitmap covers VRAM");
        });
    }

    @Test
    void romArrayHoldsTheImageAndAgreesWithReads() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            assertArrayEquals(f.layout.rom(), bus.romArray(), name);
            int base = bus.romBase();
            for (int i = 0; i < 300; i++)
                assertEquals(bus.romArray()[i] & 0xFF, bus.read8(base + i), name + " rom byte " + i);
            assertEquals(0, bus.read32(base + bus.romSize() - 4), name + ": padding reads 0");
            assertEquals(f.little() ? 0x3412 : 0x1234, bus.read16(base), name);
            assertEquals(f.little() ? 0x7856_3412 : 0x1234_5678, bus.read32(base), name);
        });
    }

    @Test
    void ramArrayAndBusAccessesSeeTheSameBytes() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            byte[] ram = bus.ramArray();
            int base = bus.ramBase();
            ram[0x100] = (byte) 0xC3;
            assertEquals(0xC3, bus.read8(base + 0x100), name + ": array write seen by read8");
            bus.write8(base + 0x101, 0x1F7);
            assertEquals(0xF7, ram[0x101] & 0xFF, name + ": write8 uses the low byte, seen in the array");
            bus.write32(base + 0x200, 0xDEAD_BEEF);
            assertEquals(0xDEAD_BEEF, bus.read32(base + 0x200), name);
            int last = base + bus.ramSize() - 4;
            bus.write32(last, 0x0BAD_F00D);
            assertEquals(0x0BAD_F00D, bus.read32(last), name + ": last RAM word");
        });
    }

    @Test
    void vramArrayAndBusAccessesSeeTheSameBytes() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            bus.vramArray()[0x3000] = 0x66;
            assertEquals(0x66, bus.read8(bus.vramBase() + 0x3000), name);
            bus.write16(bus.vramBase() + 0x3002, 0xABCD);
            assertEquals(0xABCD, bus.read16(bus.vramBase() + 0x3002), name);
            byte hi = (byte) 0xAB, lo = (byte) 0xCD;
            assertEquals(f.little() ? lo : hi, bus.vramArray()[0x3002], name);
            assertEquals(f.little() ? hi : lo, bus.vramArray()[0x3003], name);
        });
    }

    // ---- byte order and alignment on memory ----

    @Test
    void multiByteRamValuesComposeInBusOrder() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int a = bus.ramBase() + 0x40;
            bus.write32(a, 0x1122_3344);
            byte[] ram = bus.ramArray();
            byte[] expected = f.little()
                    ? new byte[] { 0x44, 0x33, 0x22, 0x11 }
                    : new byte[] { 0x11, 0x22, 0x33, 0x44 };
            assertArrayEquals(expected, Arrays.copyOfRange(ram, 0x40, 0x44), name);
            assertEquals(f.little() ? 0x44 : 0x11, bus.read8(a), name);
            assertEquals(f.little() ? 0x3344 : 0x1122, bus.read16(a), name);
            assertEquals(f.little() ? 0x1122 : 0x3344, bus.read16(a + 2), name);

            bus.write16(a + 8, 0x8899);
            assertEquals(f.little() ? 0x99 : 0x88, bus.read8(a + 8), name);
            assertEquals(0x8899, bus.read16(a + 8), name + ": read16 zero-extends");
            bus.write16(a + 12, 0x1234_FFEE);
            assertEquals(0xFFEE, bus.read16(a + 12), name + ": write16 uses the low half");
        });
    }

    @Test
    void unalignedRamAccessesWork() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int a = bus.ramBase() + 0x81;
            bus.write32(a, 0xCAFE_BABE);
            assertEquals(0xCAFE_BABE, bus.read32(a), name);
            assertEquals(f.little() ? 0xBE : 0xCA, bus.read8(a), name);
            bus.write16(a + 6, 0x7E57);
            assertEquals(0x7E57, bus.read16(a + 6), name);
            assertEquals(0xFEBA, bus.read16(a + 1), name + ": middle bytes read the same in both orders");
        });
    }

    @Test
    void ramAccessCrossingTheRamEndFaults() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int end = bus.ramBase() + bus.ramSize();
            assertThrows(BusFault.class, () -> bus.read32(end - 2), name);
            assertThrows(BusFault.class, () -> bus.read16(end - 1), name);
            BusFault w = assertThrows(BusFault.class, () -> bus.write32(end - 3, 0), name);
            assertTrue(w.write, name);
            assertThrows(BusFault.class, () -> bus.read8(end), name + ": first byte past RAM");
        });
    }

    // ---- faults ----

    @Test
    void romWritesFault() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int base = bus.romBase();
            BusFault fault = assertThrows(BusFault.class, () -> bus.write8(base + 4, 0x99), name);
            assertTrue(fault.write, name);
            assertEquals(base + 4, fault.address, name);
            assertThrows(BusFault.class, () -> bus.write16(base, 0), name);
            assertThrows(BusFault.class, () -> bus.write32(base, 0), name);
            assertArrayEquals(f.layout.rom(), bus.romArray(), name + ": ROM unchanged");
        });
    }

    @Test
    void unmappedAccessesFaultWithTheirAddress() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            BusFault r = assertThrows(BusFault.class, () -> bus.read32(UNMAPPED), name);
            assertEquals(UNMAPPED, r.address, name);
            assertFalse(r.write, name);
            BusFault w = assertThrows(BusFault.class, () -> bus.write8(UNMAPPED + 1, 1), name);
            assertEquals(UNMAPPED + 1, w.address, name);
            assertTrue(w.write, name);
            assertThrows(BusFault.class, () -> bus.read8(0xFFFF_FFFF), name);
        });
    }

    // ---- devices ----

    @Test
    void deviceAccessesReachTheDeviceWithOffsetAndWidth() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            RecordingDevice dev = f.device;
            int base = f.layout.deviceBase();

            assertEquals(RecordingDevice.valueFor(8, 4), bus.read32(base + 8), name);
            assertEquals(RecordingDevice.valueFor(2, 2), bus.read16(base + 2), name);
            assertEquals(RecordingDevice.valueFor(1, 1), bus.read8(base + 1), name);
            assertEquals(List.of(new Access(false, 8, 4, 0), new Access(false, 2, 2, 0),
                    new Access(false, 1, 1, 0)), dev.accesses, name + ": one call per access");

            dev.accesses.clear();
            bus.write32(base + 4, 0x1234_5678);
            bus.write16(base + 6, 0xBEEF);
            bus.write8(base + 3, 0x42);
            assertEquals(3, dev.accesses.size(), name + ": one call per access, never split");
            assertDeviceWrite(dev.accesses.get(0), 4, 4, 0x1234_5678, name);
            assertDeviceWrite(dev.accesses.get(1), 6, 2, 0xBEEF, name);
            assertDeviceWrite(dev.accesses.get(2), 3, 1, 0x42, name);
        });
    }

    /** A device register value is the same whatever the bus order (spec 1.2). */
    @Test
    void deviceValuesAreNotByteSwapped() {
        forEachLayout((f, name) -> {
            int base = f.layout.deviceBase();
            assertEquals(RecordingDevice.valueFor(0, 4), f.bus.read32(base), name);
            f.bus.write32(base, 0x0102_0304);
            assertEquals(0x0102_0304, f.device.accesses.get(1).value(), name);
        });
    }

    @Test
    void offsetsPastTheDeviceSizeInsideTheWindowStillReachTheDevice() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int base = f.layout.deviceBase();
            int window = f.layout.deviceWindow();
            assertTrue(window > DEVICE_SIZE, "layout window must exceed the device size");
            bus.read32(base + DEVICE_SIZE);
            bus.read32(base + window - 4);
            bus.write8(base + window - 1, 7);
            assertEquals(List.of(new Access(false, DEVICE_SIZE, 4, 0), new Access(false, window - 4, 4, 0)),
                    f.device.accesses.subList(0, 2), name);
            assertDeviceWrite(f.device.accesses.get(2), window - 1, 1, 7, name);
            assertThrows(BusFault.class, () -> bus.read32(base + window), name + ": just past the window");
        });
    }

    @Test
    void misalignedDeviceAccessesFault() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            int base = f.layout.deviceBase();
            assertThrows(BusFault.class, () -> bus.read32(base + 2), name);
            assertThrows(BusFault.class, () -> bus.read32(base + 1), name);
            assertThrows(BusFault.class, () -> bus.read16(base + 1), name);
            BusFault w = assertThrows(BusFault.class, () -> bus.write32(base + 6, 0), name);
            assertTrue(w.write, name);
            assertThrows(BusFault.class, () -> bus.write16(base + 3, 0), name);
            assertTrue(f.device.accesses.isEmpty(), name + ": a misaligned access never reaches the device");
        });
    }

    // ---- VRAM dirty pages ----

    @Test
    void vramWritesSetTheirDirtyPageBit() {
        forEachLayout((f, name) -> {
            Bus bus = f.bus;
            long[] dirty = bus.vramDirtyPages();
            Arrays.fill(dirty, 0);

            bus.write8(bus.vramBase() + 0x5123, 1);
            assertEquals(1L << 5, dirty[0], name + ": page 5");

            bus.write8(bus.vramBase() + 0x4_1000, 1);
            assertEquals(1L << 1, dirty[1], name + ": page 65 is word 1, bit 1");

            bus.write32(bus.vramBase() + 0x7_FFFC, 1);
            assertEquals(1L << 63, dirty[1] & (1L << 63), name + ": last page");

            Arrays.fill(dirty, 0);
            bus.read32(bus.vramBase() + 0x2000);
            assertEquals(0, dirty[0] | dirty[1], name + ": reads do not dirty");
        });
    }

    private static void assertDeviceWrite(Access a, int offset, int width, int value, String name) {
        assertTrue(a.write(), name);
        assertEquals(offset, a.offset(), name);
        assertEquals(width, a.width(), name);
        int mask = width == 4 ? -1 : (1 << (8 * width)) - 1;
        assertEquals(value, a.value() & mask, name + ": low bits of the written value");
    }
}
