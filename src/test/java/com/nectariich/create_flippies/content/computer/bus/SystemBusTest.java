package com.nectariich.create_flippies.content.computer.bus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link SystemBus} beyond the shared contract (spec 1.2, 7.5, 8.3). */
class SystemBusTest {

    private static final int RAM = 0x8000_0000;
    private static final int ROM = 0x0000_1000;
    private static final int VRAM = 0x2000_0000;

    /** A device decoding 8 bytes that answers every read with its offset. */
    private static final class EchoDevice implements BusDevice {
        final List<Integer> offsets = new ArrayList<>();

        @Override
        public int size() {
            return 8;
        }

        @Override
        public int read(int offset, int width) {
            offsets.add(offset);
            return offset;
        }

        @Override
        public void write(int offset, int width, int value) {
            offsets.add(offset);
        }

        @Override
        public void reset() {
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

    private static SystemBus rv32Bus() {
        SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        bus.setRam(new byte[64 * 1024], RAM);
        byte[] rom = new byte[0xF000];
        rom[0] = 0x11;
        rom[1] = 0x22;
        bus.setRom(rom, ROM);
        bus.setVram(new byte[512 * 1024], VRAM, new long[2]);
        return bus;
    }

    @Test
    void windowSizeIsChosenAtMappingNotByTheDevice() {
        SystemBus bus = rv32Bus();
        EchoDevice dev = new EchoDevice();
        bus.mapDevice(0x1000_0000, 0x1000, dev);
        assertEquals(0x800, bus.read32(0x1000_0800), "offset past size() inside the window reaches the device");
        assertEquals(0xFFC, bus.read32(0x1000_0FFC));
        assertThrows(BusFault.class, () -> bus.read32(0x1000_1000), "just past the window");

        SystemBus ports = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        ports.mapDevice(0x3F8, 8, new EchoDevice());
        assertEquals(4, ports.read8(0x3FC));
        assertThrows(BusFault.class, () -> ports.read8(0x400), "an 8-port window ends at 0x3FF");
    }

    @Test
    void overlappingMappingsAreRejected() {
        SystemBus bus = rv32Bus();
        bus.mapDevice(0x1000_0000, 0x1000, new EchoDevice());
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0x1000_0FFC, 0x1000, new EchoDevice()));
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0x0FFF_F000, 0x2000, new EchoDevice()));
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(RAM + 0x100, 0x100, new EchoDevice()), "inside RAM");
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(VRAM, 0x100, new EchoDevice()), "on VRAM");
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0x2000, 4, new EchoDevice()), "inside ROM");
        assertThrows(IllegalArgumentException.class, () -> bus.mapMemory(0x1000_0800, new MemoryRegion(new byte[16], 0, 16, true, null)));
        assertThrows(IllegalArgumentException.class, () -> bus.setRam(new byte[4096], 0x1000_0000), "RAM onto a device");
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0x5000_0000, 6, new EchoDevice()), "size not a multiple of 4");
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0x5000_0000, 0, new EchoDevice()));
        assertThrows(IllegalArgumentException.class, () -> bus.mapDevice(0xFFFF_F000, 0x2000, new EchoDevice()), "past 4 GiB");
        bus.mapDevice(0x1000_1000, 0x1000, new EchoDevice());
        bus.mapDevice(0xFFFF_F000, 0x1000, new EchoDevice());
        assertEquals(0xFFC, bus.read32(0xFFFF_FFFC), "a window may end exactly at 2^32");
    }

    @Test
    void romOverlayAnswersAtZeroAndWinsOverRam() {
        SystemBus bus = new SystemBus(ByteOrder.BIG_ENDIAN);
        byte[] ram = new byte[0x10000];
        ram[0] = 0x7F;
        bus.setRam(ram, 0);
        byte[] rom = new byte[0x1000];
        rom[0] = 0x12;
        rom[1] = 0x34;
        bus.setRom(rom, 0x00F0_0000);
        assertEquals(0x00F0_0000, bus.romBase());
        assertEquals(0x7F, bus.read8(0));

        bus.setRomOverlay(true);
        assertEquals(0, bus.romBase(), "romBase reads 0 while the overlay is on");
        assertEquals(0x1234, bus.read16(0), "ROM wins over RAM at 0");
        assertEquals(0x1234, bus.read16(0x00F0_0000), "ROM still answers at home");
        assertThrows(BusFault.class, () -> bus.write8(0, 1), "the overlay is read-only");
        assertEquals(0, bus.read8(0x1000), "RAM above the ROM's length answers normally");
        assertNull(bus.dma(0, 512), "no DMA under the overlay");

        bus.setRomOverlay(false);
        assertEquals(0x00F0_0000, bus.romBase());
        assertEquals(0x7F, bus.read8(0), "RAM back at 0");
        bus.write8(0, 5);
        assertEquals(5, ram[0]);
    }

    @Test
    void bankedRegionFollowsSetOffsetAndMarksDataPages() {
        byte[] vram = new byte[512 * 1024];
        long[] dirty = new long[2];
        SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        MemoryRegion bank = new MemoryRegion(vram, 0, 0x10000, true, dirty);
        bus.mapMemory(0xA0000, bank);

        bus.write16(0xA0010, 0xBEEF);
        assertEquals((byte) 0xEF, vram[0x10]);
        assertEquals(1L, dirty[0], "page 0 of VRAM");

        bank.setOffset(3 * 0x10000);
        bus.write8(0xA2000, 0x42);
        assertEquals(0x42, vram[0x32000]);
        assertEquals(0x42, bus.read8(0xA2000));
        assertTrue((dirty[0] & (1L << 0x32)) != 0, "page 0x32 = bank 3 page 2");
        assertEquals(0, bus.read16(0xA0010), "bank 3 does not hold bank 0's bytes");

        bus.write32(0xA0FFE, 0x0102_0304);
        assertTrue((dirty[0] & (1L << 0x30)) != 0 && (dirty[0] & (1L << 0x31)) != 0, "a write across a page edge marks both");

        assertThrows(IllegalArgumentException.class, () -> bank.setOffset(512 * 1024 - 0x8000));
        assertThrows(BusFault.class, () -> bus.read32(0xAFFFE), "crossing the region end");

        MemoryRegion readOnly = new MemoryRegion(vram, 0, 0x1000, false, dirty);
        bus.mapMemory(0xB8000, readOnly);
        assertThrows(BusFault.class, () -> bus.write8(0xB8000, 1));
        assertNull(bus.dma(0xB8000, 512), "no DMA into a read-only region");
    }

    @Test
    void dmaWindowsInsideRamVramAndRegionsOnly() {
        SystemBus bus = rv32Bus();
        List<int[]> written = new ArrayList<>();
        bus.setRamWriteListener((address, length) -> written.add(new int[] { address, length }));

        DmaWindow w = bus.dma(RAM + 0x200, 1024);
        assertNotNull(w);
        assertTrue(w.array() == bus.ramArray());
        assertEquals(0x200, w.offset());
        assertEquals(1024, w.length());
        w.array()[w.offset()] = 9;
        w.onWrite().run();
        assertEquals(9, bus.read8(RAM + 0x200));
        assertEquals(1, written.size());
        assertArrayEquals(new int[] { RAM + 0x200, 1024 }, written.get(0), "the RAM write listener sees DMA writes");

        assertNotNull(bus.dma(RAM + 64 * 1024 - 512, 512), "the last sector of RAM");
        assertNull(bus.dma(RAM + 64 * 1024 - 256, 512), "across the RAM end");
        assertNull(bus.dma(ROM, 512), "not into ROM");
        assertNull(bus.dma(0x4000_0000, 512), "unmapped");
        assertNull(bus.dma(RAM, 0));

        long[] dirty = bus.vramDirtyPages();
        DmaWindow v = bus.dma(VRAM + 0x1F00, 0x200);
        assertTrue(v.array() == bus.vramArray());
        assertEquals(0, dirty[0]);
        v.onWrite().run();
        assertEquals(0b110L, dirty[0], "pages 1 and 2");

        byte[] other = new byte[0x4000];
        long[] otherDirty = new long[1];
        bus.mapMemory(0x3000_0000, new MemoryRegion(other, 0x1000, 0x2000, true, otherDirty));
        DmaWindow r = bus.dma(0x3000_0100, 0x100);
        assertTrue(r.array() == other);
        assertEquals(0x1100, r.offset(), "region offset plus window index");
        r.onWrite().run();
        assertEquals(0b10L, otherDirty[0]);
    }

    @Test
    void anEmptyBusHasEmptyArraysAndFaultsEverywhere() {
        SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, bus.ramArray().length);
        assertEquals(0, bus.romArray().length);
        assertEquals(0, bus.vramArray().length);
        assertEquals(0, bus.ramSize());
        assertEquals(0, bus.romSize());
        assertEquals(0, bus.vramSize());
        assertNotNull(bus.vramDirtyPages());
        assertEquals(0, bus.vramDirtyPages().length);
        assertThrows(BusFault.class, () -> bus.read8(0));
        assertFalse(bus.romOverlay());
    }

    @Test
    void deviceWritesAreMaskedToTheirWidth() {
        SystemBus bus = rv32Bus();
        int[] last = new int[1];
        bus.mapDevice(0x1000_0000, 0x100, new BusDevice() {
            @Override
            public int size() {
                return 4;
            }

            @Override
            public int read(int offset, int width) {
                return 0xFFFF_FFFF;
            }

            @Override
            public void write(int offset, int width, int value) {
                last[0] = value;
            }

            @Override
            public void reset() {
            }

            @Override
            public void saveState(java.io.DataOutput out) {
                throw new UnsupportedOperationException("test device has no saved state");
            }

            @Override
            public void loadState(java.io.DataInput in) {
                throw new UnsupportedOperationException("test device has no saved state");
            }
        });
        bus.write8(0x1000_0000, 0x1234);
        assertEquals(0x34, last[0]);
        bus.write16(0x1000_0000, 0x1234_5678);
        assertEquals(0x5678, last[0]);
        assertEquals(0xFF, bus.read8(0x1000_0001), "reads are zero-extended even if the device returns more");
        assertEquals(0xFFFF, bus.read16(0x1000_0002));
    }
}
