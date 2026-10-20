package com.nectariich.create_flippies.content.computer.device;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A disk image kept in 64 KiB chunks that exist only once written (spec 3.8): a 64 MiB disk that
 * holds a 2 KiB program costs one chunk. Never-written chunks read as zeros. Every written chunk
 * is marked dirty so the world save can store only what changed; export and dirty-chunk calls
 * return copies, so a save can run off the machine's thread.
 */
public final class SparseDiskImage implements DiskImage {

    public static final int CHUNK_SIZE = 64 * 1024;
    /** Smallest and largest capacity the core accepts, in bytes (spec 1.1). */
    public static final long MIN_CAPACITY = 64 * 1024L;
    public static final long MAX_CAPACITY = 1024L * 1024 * 1024;

    private static final int SECTORS_PER_CHUNK = CHUNK_SIZE / SECTOR_SIZE;

    private final long capacityBytes;
    private final byte[][] chunks;
    private final BitSet dirty = new BitSet();

    /** IllegalArgumentException unless the capacity is a multiple of 64 KiB from 64 KiB to 1 GiB. */
    public SparseDiskImage(long capacityBytes) {
        if (capacityBytes < MIN_CAPACITY || capacityBytes > MAX_CAPACITY || capacityBytes % CHUNK_SIZE != 0)
            throw new IllegalArgumentException("disk capacity must be a multiple of 64 KiB from 64 KiB to 1 GiB: " + capacityBytes);
        this.capacityBytes = capacityBytes;
        this.chunks = new byte[(int) (capacityBytes / CHUNK_SIZE)][];
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    @Override
    public long sectorCount() {
        return capacityBytes / SECTOR_SIZE;
    }

    public int chunkCount() {
        return chunks.length;
    }

    @Override
    public void read(long sector, int count, byte[] dst, int offset) {
        checkRange(sector, count);
        Objects.checkFromIndexSize(offset, count * SECTOR_SIZE, dst.length);
        long pos = sector * SECTOR_SIZE;
        int left = count * SECTOR_SIZE;
        while (left > 0) {
            int chunk = (int) (pos / CHUNK_SIZE);
            int within = (int) (pos % CHUNK_SIZE);
            int n = Math.min(left, CHUNK_SIZE - within);
            byte[] data = chunks[chunk];
            if (data == null)
                Arrays.fill(dst, offset, offset + n, (byte) 0);
            else
                System.arraycopy(data, within, dst, offset, n);
            pos += n;
            offset += n;
            left -= n;
        }
    }

    @Override
    public void write(long sector, int count, byte[] src, int offset) {
        checkRange(sector, count);
        Objects.checkFromIndexSize(offset, count * SECTOR_SIZE, src.length);
        long pos = sector * SECTOR_SIZE;
        int left = count * SECTOR_SIZE;
        while (left > 0) {
            int chunk = (int) (pos / CHUNK_SIZE);
            int within = (int) (pos % CHUNK_SIZE);
            int n = Math.min(left, CHUNK_SIZE - within);
            byte[] data = chunks[chunk];
            if (data == null)
                data = chunks[chunk] = new byte[CHUNK_SIZE];
            System.arraycopy(src, offset, data, within, n);
            dirty.set(chunk);
            pos += n;
            offset += n;
            left -= n;
        }
    }

    private void checkRange(long sector, int count) {
        if (sector < 0 || count < 0 || sector + count > sectorCount())
            throw new IndexOutOfBoundsException("sectors " + sector + " + " + count + " outside a disk of " + sectorCount());
    }

    // ---- chunks, for the world save ----

    public boolean isAllocated(int chunk) {
        return chunks[chunk] != null;
    }

    public int allocatedChunks() {
        int n = 0;
        for (byte[] c : chunks)
            if (c != null)
                n++;
        return n;
    }

    /** Chunks written since the last {@link #takeDirtyChunks()}, ascending. */
    public int[] dirtyChunks() {
        return dirty.stream().toArray();
    }

    /** Copies of the dirty chunks keyed by index (ascending), and the dirty set cleared. */
    public Map<Integer, byte[]> takeDirtyChunks() {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        for (int i = dirty.nextSetBit(0); i >= 0; i = dirty.nextSetBit(i + 1))
            out.put(i, chunks[i].clone());
        dirty.clear();
        return out;
    }

    /** A copy of one chunk, or null if it was never written (all zeros). */
    public byte[] exportChunk(int chunk) {
        byte[] c = chunks[chunk];
        return c == null ? null : c.clone();
    }

    /** Loads a saved chunk (copied); null makes it unwritten again. Not marked dirty. */
    public void loadChunk(int chunk, byte[] data) {
        if (data == null) {
            chunks[chunk] = null;
        } else {
            if (data.length != CHUNK_SIZE)
                throw new IllegalArgumentException("a chunk is " + CHUNK_SIZE + " bytes, got " + data.length);
            chunks[chunk] = data.clone();
        }
        dirty.clear(chunk);
    }
}
