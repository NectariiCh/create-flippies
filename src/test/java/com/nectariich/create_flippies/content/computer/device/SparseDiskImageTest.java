package com.nectariich.create_flippies.content.computer.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The sparse disk image (spec 3.8). */
class SparseDiskImageTest {

    private static final int MIB = 1024 * 1024;

    @Test
    void unwrittenChunksAreNotAllocatedAndReadZero() {
        SparseDiskImage disk = new SparseDiskImage(64L * MIB);
        assertEquals(131_072, disk.sectorCount());
        assertEquals(1024, disk.chunkCount());
        byte[] buf = new byte[512 * 4];
        buf[0] = 1;
        disk.read(1000, 4, buf, 0);
        assertArrayEquals(new byte[512 * 4], buf);
        assertEquals(0, disk.allocatedChunks(), "reading allocates nothing");
        assertEquals(0, disk.dirtyChunks().length);
        assertNull(disk.exportChunk(0));
    }

    @Test
    void writesAllocateTheirChunksAndMarkThemDirty() {
        SparseDiskImage disk = new SparseDiskImage(4L * MIB);
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) (i + 1);
        disk.write(127, 2, data, 0);
        assertTrue(disk.isAllocated(0));
        assertTrue(disk.isAllocated(1));
        assertFalse(disk.isAllocated(2));
        assertArrayEquals(new int[] { 0, 1 }, disk.dirtyChunks(), "the write crossed a chunk edge");
        byte[] back = new byte[1024];
        disk.read(127, 2, back, 0);
        assertArrayEquals(data, back);

        Map<Integer, byte[]> dirty = disk.takeDirtyChunks();
        assertEquals(2, dirty.size());
        assertEquals(0, disk.dirtyChunks().length, "taking clears the set");
        byte[] chunk1 = dirty.get(1);
        assertEquals(SparseDiskImage.CHUNK_SIZE, chunk1.length);
        assertEquals(data[512], chunk1[0]);
        chunk1[0] = 99;
        disk.read(128, 1, back, 0);
        assertEquals(data[512], back[0], "exports are copies");
    }

    @Test
    void loadChunkRestoresWithoutDirtying() {
        SparseDiskImage disk = new SparseDiskImage(SparseDiskImage.MIN_CAPACITY);
        byte[] chunk = new byte[SparseDiskImage.CHUNK_SIZE];
        chunk[512] = 42;
        disk.loadChunk(0, chunk);
        chunk[512] = 0;
        byte[] buf = new byte[512];
        disk.read(1, 1, buf, 0);
        assertEquals(42, buf[0]);
        assertEquals(0, disk.dirtyChunks().length);
        assertEquals(42, disk.exportChunk(0)[512]);
        disk.loadChunk(0, null);
        assertFalse(disk.isAllocated(0));
        assertThrows(IllegalArgumentException.class, () -> disk.loadChunk(0, new byte[10]));
    }

    @Test
    void capacityLimits() {
        assertThrows(IllegalArgumentException.class, () -> new SparseDiskImage(0));
        assertThrows(IllegalArgumentException.class, () -> new SparseDiskImage(64 * 1024 + 512));
        assertThrows(IllegalArgumentException.class, () -> new SparseDiskImage(2048L * MIB));
        assertEquals(2L * 1024 * 1024, new SparseDiskImage(1024L * MIB).sectorCount());
    }

    @Test
    void outOfRangeTransfersThrow() {
        SparseDiskImage disk = new SparseDiskImage(SparseDiskImage.MIN_CAPACITY);
        byte[] buf = new byte[1024];
        assertThrows(IndexOutOfBoundsException.class, () -> disk.read(127, 2, buf, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> disk.write(-1, 1, buf, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> disk.read(0, 2, buf, 1));
    }
}
