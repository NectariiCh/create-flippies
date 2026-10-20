package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.PagedDiskImage.CHUNK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.StateFormatException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The lazily loaded disk image (spec 11.1.6) over a fake chunk store, and the media error it causes. */
class PagedDiskImageTest {

    private static final UUID ID = UUID.fromString("12345678-0000-0000-0000-00000000d15c");

    /** A map of chunks that records every load and can be told to fail. */
    static final class FakeStore implements DiskChunkStore {
        final Map<Integer, byte[]> chunks = new HashMap<>();
        final List<Integer> loads = new ArrayList<>();
        boolean failing;

        @Override
        public byte[] load(int chunk) throws IOException {
            loads.add(chunk);
            if (failing)
                throw new IOException("disk file unreadable");
            byte[] c = chunks.get(chunk);
            return c == null ? null : c.clone();
        }
    }

    private static byte[] filled(int n, int value) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) value);
        return b;
    }

    @Test
    void loadsChunksLazilyOnFirstAccess() {
        FakeStore store = new FakeStore();
        store.chunks.put(3, filled(CHUNK_SIZE, 0x33));
        PagedDiskImage d = new PagedDiskImage(ID, 16L << 20, store, 4);
        assertEquals(ID, d.id());
        assertEquals(16L << 20, d.capacityBytes());
        assertEquals(256, d.chunkCount());
        assertEquals(32768, d.sectorCount());
        assertTrue(store.loads.isEmpty(), "nothing loaded up front");
        byte[] sector = new byte[512];
        d.read(3 * 128 + 5, 1, sector, 0);
        assertArrayEquals(filled(512, 0x33), sector);
        assertEquals(List.of(3), store.loads);
        d.read(3 * 128, 1, sector, 0);
        assertEquals(List.of(3), store.loads, "a cached chunk is not loaded again");
        d.read(10 * 128, 1, sector, 0);
        assertArrayEquals(new byte[512], sector, "a never-written chunk reads as zeros");
        assertFalse(d.hasDirtyChunks());
    }

    @Test
    void readsAndWritesAcrossChunkEdges() {
        FakeStore store = new FakeStore();
        PagedDiskImage d = new PagedDiskImage(null, 1L << 20, store, 2);
        byte[] data = new byte[4 * 512];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) (i * 7);
        d.write(126, 4, data, 0);       // sectors 126..129: two in chunk 0, two in chunk 1
        byte[] back = new byte[data.length];
        d.read(126, 4, back, 0);
        assertArrayEquals(data, back);
        assertThrows(IndexOutOfBoundsException.class, () -> d.read(2047, 2, new byte[1024], 0));
        assertThrows(IndexOutOfBoundsException.class, () -> d.write(0, 2, new byte[512], 0));
    }

    @Test
    void lruEvictsCleanChunksOnly() {
        FakeStore store = new FakeStore();
        PagedDiskImage d = new PagedDiskImage(ID, 1L << 20, store, 2);
        byte[] s = new byte[512];
        d.write(0, 1, filled(512, 1), 0);   // chunk 0 dirty
        d.read(128, 1, s, 0);               // chunk 1 clean
        d.read(256, 1, s, 0);               // chunk 2 clean
        d.read(384, 1, s, 0);               // chunk 3 clean: chunk 1 evicted
        assertEquals(2, d.cachedCleanChunks());
        store.loads.clear();
        d.read(256, 1, s, 0);
        d.read(384, 1, s, 0);
        assertTrue(store.loads.isEmpty(), "the two most recent clean chunks stay");
        d.read(128, 1, s, 0);
        assertEquals(List.of(1), store.loads, "the evicted chunk loads again");
        d.read(0, 1, s, 0);
        assertArrayEquals(filled(512, 1), s, "the dirty chunk was never evicted");
        assertEquals(List.of(1), store.loads);
    }

    @Test
    void takeDirtyChunksCopiesAndClears() {
        FakeStore store = new FakeStore();
        PagedDiskImage d = new PagedDiskImage(ID, 1L << 20, store, 4);
        d.write(130, 1, filled(512, 9), 0);
        d.write(5 * 128, 1, filled(512, 5), 0);
        assertTrue(d.hasDirtyChunks());
        Map<Integer, byte[]> dirty = d.takeDirtyChunks();
        assertEquals(List.of(1, 5), new ArrayList<>(dirty.keySet()));
        assertEquals(CHUNK_SIZE, dirty.get(1).length);
        assertEquals(9, dirty.get(1)[2 * 512]);
        assertFalse(d.hasDirtyChunks());
        assertTrue(d.takeDirtyChunks().isEmpty());
        store.loads.clear();
        dirty.get(1)[2 * 512] = 77;
        byte[] s = new byte[512];
        d.read(130, 1, s, 0);
        assertArrayEquals(filled(512, 9), s, "the copy is not the disk's own array");
        assertTrue(store.loads.isEmpty(), "the taken chunk stays cached as clean");
        d.write(130, 1, filled(512, 4), 0);
        assertNotSame(dirty.get(1), d.takeDirtyChunks().get(1));
    }

    @Test
    void theFloppysLastChunkIsHalfUsed() {
        FakeStore store = new FakeStore();
        store.chunks.put(22, filled(CHUNK_SIZE, 0x22));   // a store may hand a full-size last chunk
        PagedDiskImage d = new PagedDiskImage(ID, PagedDiskImage.FLOPPY_CAPACITY, store, 4);
        assertEquals(2880, d.sectorCount());
        assertEquals(23, d.chunkCount());
        byte[] s = new byte[512];
        d.read(2879, 1, s, 0);
        assertArrayEquals(filled(512, 0x22), s);
        d.write(2879, 1, filled(512, 1), 0);
        assertEquals(CHUNK_SIZE / 2, d.takeDirtyChunks().get(22).length, "the last chunk is 32 KiB");
        assertThrows(IndexOutOfBoundsException.class, () -> d.read(2880, 1, s, 0));
    }

    @Test
    void capacityAndCacheLimits() {
        FakeStore store = new FakeStore();
        new PagedDiskImage(ID, 512, store, 1);
        new PagedDiskImage(ID, PagedDiskImage.MAX_CAPACITY, store, 1);
        assertThrows(IllegalArgumentException.class, () -> new PagedDiskImage(ID, 0, store, 1));
        assertThrows(IllegalArgumentException.class, () -> new PagedDiskImage(ID, 1000, store, 1));
        assertThrows(IllegalArgumentException.class, () -> new PagedDiskImage(ID, PagedDiskImage.MAX_CAPACITY + 512, store, 1));
        assertThrows(IllegalArgumentException.class, () -> new PagedDiskImage(ID, 512, store, 0));
    }

    @Test
    void aStoreFailureIsAnUncheckedIoException() {
        FakeStore store = new FakeStore();
        store.failing = true;
        PagedDiskImage d = new PagedDiskImage(ID, 1L << 20, store, 2);
        assertThrows(UncheckedIOException.class, () -> d.read(0, 1, new byte[512], 0));
        store.failing = false;
        d.read(0, 1, new byte[512], 0);
    }

    @Test
    void aStoreFailureIsMediaErrorSevenOnTheHdd() {
        FakeStore store = new FakeStore();
        PagedDiskImage d = new PagedDiskImage(ID, 1L << 20, store, 2);
        DeviceRig rig = new DeviceRig();
        rig.bus.setRam(new byte[64 * 1024], DeviceRig.RAM_BASE);
        HddController hdd = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        hdd.setDisk(d);
        rig.map(hdd, 0x1000);
        store.failing = true;
        for (int cmd : new int[] { HddController.CMD_READ, HddController.CMD_WRITE }) {
            rig.w32(HddController.LBA, 0);
            rig.w32(HddController.COUNT, 1);
            rig.w32(HddController.DMA_ADDR, DeviceRig.RAM_BASE);
            rig.w32(HddController.COMMAND, cmd);
            rig.at(hdd.nextEventCycle());
            assertEquals(HddController.STATUS_ERROR | HddController.STATUS_MEDIA, rig.r32(HddController.STATUS));
            assertEquals(HddController.ERR_MEDIA, rig.r32(HddController.ERROR_CODE));
            rig.w32(HddController.STATUS, HddController.STATUS_ERROR);
        }
        assertTrue(hdd.takeActivity());
        assertFalse(hdd.takeActivity());
    }

    @Test
    void aStoreFailureIsMediaErrorSevenOnTheFloppy() {
        FakeStore store = new FakeStore();
        PagedDiskImage d = new PagedDiskImage(ID, PagedDiskImage.FLOPPY_CAPACITY, store, 2);
        DeviceRig rig = new DeviceRig();
        rig.bus.setRam(new byte[64 * 1024], DeviceRig.RAM_BASE);
        FloppyController fdc = new FloppyController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        fdc.reset();
        fdc.insert(d, false);
        rig.map(fdc, 0x1000);
        store.failing = true;
        for (int cmd : new int[] { FloppyController.CMD_READ, FloppyController.CMD_WRITE }) {
            rig.w32(FloppyController.LBA, 0);
            rig.w32(FloppyController.COUNT, 1);
            rig.w32(FloppyController.DMA_ADDR, DeviceRig.RAM_BASE);
            rig.w32(FloppyController.COMMAND, cmd);
            rig.at(fdc.nextEventCycle());
            assertEquals(FloppyController.STATUS_ERROR, rig.r32(FloppyController.STATUS) & 7);
            assertEquals(FloppyController.ERR_MEDIA, rig.r32(FloppyController.ERROR_CODE));
            rig.w32(FloppyController.STATUS, FloppyController.STATUS_ERROR);
        }
    }

    @Test
    void hddSavedStateChecksTheDiskId() throws IOException {
        DeviceRig rig = new DeviceRig();
        FakeStore store = new FakeStore();
        HddController a = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        a.setDisk(new PagedDiskImage(ID, 1L << 20, store, 1));
        byte[] state = DeviceStateTest.save(a);

        HddController same = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        same.setDisk(new PagedDiskImage(ID, 1L << 20, store, 1));
        DeviceStateTest.load(same, state);

        HddController other = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        other.setDisk(new PagedDiskImage(UUID.randomUUID(), 1L << 20, store, 1));
        var e = assertThrows(StateFormatException.class,
                () -> DeviceStateTest.load(other, state));
        assertTrue(e.getMessage().contains(ID.toString()), e.getMessage());

        HddController anonymous = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        anonymous.setDisk(new SparseDiskImage(1L << 20));
        assertThrows(StateFormatException.class,
                () -> DeviceStateTest.load(anonymous, state));
    }

    @Test
    void hddVersion1PayloadIsCheckedByCapacityOnly() throws IOException {
        DeviceRig rig = new DeviceRig();
        HddController a = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        a.setDisk(new SparseDiskImage(1L << 20));
        byte[] v2 = DeviceStateTest.save(a);
        // version 2 is version 1 with the id (here one "absent" byte) after the 8-byte capacity
        byte[] v1 = new byte[v2.length - 1];
        System.arraycopy(v2, 0, v1, 0, 12);
        System.arraycopy(v2, 13, v1, 12, v2.length - 13);
        v1[3] = 1;
        HddController b = new HddController(rig.clock, 10_000_000, rig.bus, new DeviceRig.Line());
        b.setDisk(new PagedDiskImage(ID, 1L << 20, new FakeStore(), 1));
        DeviceStateTest.load(b, v1);
        assertEquals(2048, b.read(HddController.CAPACITY, 4), "a version 1 state loads onto any disk of its capacity");
    }
}
