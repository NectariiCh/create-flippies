package com.nectariich.create_flippies.content.computer.device;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A disk loaded lazily in 64 KiB chunks from a {@link DiskChunkStore} (spec 11.1.6), so a 1 GiB
 * disk costs memory only for what the guest touches. A chunk is loaded on its first access;
 * clean chunks are kept in an LRU capped at {@code maxCachedChunks}, and written (dirty) chunks
 * are never evicted until {@link #takeDirtyChunks} hands them to the world save.
 *
 * Capacity is any multiple of 512 bytes from one sector to 1 GiB; the last chunk may be shorter
 * than 64 KiB (the 1.44 MB floppy's last chunk is half used). A store failure surfaces as
 * {@link UncheckedIOException} from {@link #read} or {@link #write}, which the controllers turn
 * into their media error. Single-threaded, like the machine it is attached to.
 */
public final class PagedDiskImage implements DiskImage {

    public static final int CHUNK_SIZE = 64 * 1024;
    public static final long FLOPPY_CAPACITY = 1_474_560L;
    public static final long MAX_CAPACITY = 1024L * 1024 * 1024;

    private static final int SECTORS_PER_CHUNK = CHUNK_SIZE / SECTOR_SIZE;

    private final UUID id;
    private final long capacityBytes;
    private final DiskChunkStore store;
    private final int maxCachedChunks;
    private final int chunkCount;
    /** Clean chunks in access order, eldest first. */
    private final LinkedHashMap<Integer, byte[]> clean = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, byte[]> dirty = new HashMap<>();

    /**
     * @param id              the disk's identity; null for an anonymous disk
     * @param capacityBytes   a multiple of 512 from 512 to {@link #MAX_CAPACITY}
     * @param maxCachedChunks clean chunks kept in memory, at least 1
     */
    public PagedDiskImage(UUID id, long capacityBytes, DiskChunkStore store, int maxCachedChunks) {
        if (capacityBytes < SECTOR_SIZE || capacityBytes > MAX_CAPACITY || capacityBytes % SECTOR_SIZE != 0)
            throw new IllegalArgumentException("disk capacity must be a multiple of 512 bytes from 512 bytes to 1 GiB: "
                    + capacityBytes);
        if (maxCachedChunks < 1)
            throw new IllegalArgumentException("the chunk cache must hold at least one chunk: " + maxCachedChunks);
        this.id = id;
        this.capacityBytes = capacityBytes;
        this.store = Objects.requireNonNull(store, "store");
        this.maxCachedChunks = maxCachedChunks;
        this.chunkCount = (int) ((capacityBytes + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    @Override
    public UUID id() {
        return id;
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public int chunkCount() {
        return chunkCount;
    }

    @Override
    public long sectorCount() {
        return capacityBytes / SECTOR_SIZE;
    }

    /** Clean chunks held in memory now, for tests. */
    int cachedCleanChunks() {
        return clean.size();
    }

    @Override
    public void read(long sector, int count, byte[] dst, int offset) {
        check(sector, count, dst, offset);
        long s = sector;
        int left = count;
        int at = offset;
        while (left > 0) {
            int chunk = (int) (s / SECTORS_PER_CHUNK);
            int inChunk = (int) (s % SECTORS_PER_CHUNK);
            int n = Math.min(left, SECTORS_PER_CHUNK - inChunk);
            System.arraycopy(chunk(chunk), inChunk * SECTOR_SIZE, dst, at, n * SECTOR_SIZE);
            s += n;
            left -= n;
            at += n * SECTOR_SIZE;
        }
    }

    @Override
    public void write(long sector, int count, byte[] src, int offset) {
        check(sector, count, src, offset);
        long s = sector;
        int left = count;
        int at = offset;
        while (left > 0) {
            int chunk = (int) (s / SECTORS_PER_CHUNK);
            int inChunk = (int) (s % SECTORS_PER_CHUNK);
            int n = Math.min(left, SECTORS_PER_CHUNK - inChunk);
            byte[] data = chunk(chunk);
            if (clean.remove(chunk) != null)
                dirty.put(chunk, data);
            System.arraycopy(src, at, data, inChunk * SECTOR_SIZE, n * SECTOR_SIZE);
            s += n;
            left -= n;
            at += n * SECTOR_SIZE;
        }
    }

    private void check(long sector, int count, byte[] buf, int offset) {
        if (sector < 0 || count < 0 || sector + count > sectorCount())
            throw new IndexOutOfBoundsException("sectors " + sector + " + " + count + " outside a disk of "
                    + sectorCount());
        Objects.checkFromIndexSize(offset, count * SECTOR_SIZE, buf.length);
    }

    /** Bytes of chunk {@code chunk}: 64 KiB, the last one possibly shorter. */
    private int chunkLength(int chunk) {
        return (int) Math.min(CHUNK_SIZE, capacityBytes - (long) chunk * CHUNK_SIZE);
    }

    /** The chunk's array, from the dirty set, the clean cache or the store. */
    private byte[] chunk(int chunk) {
        byte[] data = dirty.get(chunk);
        if (data != null)
            return data;
        data = clean.get(chunk);
        if (data != null)
            return data;
        int length = chunkLength(chunk);
        byte[] loaded;
        try {
            loaded = store.load(chunk);
        } catch (IOException e) {
            throw new UncheckedIOException("loading chunk " + chunk + " of disk " + id, e);
        }
        data = loaded == null ? new byte[length] : Arrays.copyOf(loaded, length);
        clean.put(chunk, data);
        evict();
        return data;
    }

    private void evict() {
        Iterator<byte[]> it = clean.values().iterator();
        while (clean.size() > maxCachedChunks && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public boolean hasDirtyChunks() {
        return !dirty.isEmpty();
    }

    /**
     * Copies of every chunk written since the last call, by chunk index in ascending order, each
     * of its chunk's length; the chunks become clean (so a save can run off the machine's thread).
     */
    public Map<Integer, byte[]> takeDirtyChunks() {
        Map<Integer, byte[]> out = new TreeMap<>();
        for (Map.Entry<Integer, byte[]> e : dirty.entrySet())
            out.put(e.getKey(), e.getValue().clone());
        for (Map.Entry<Integer, byte[]> e : new TreeMap<>(dirty).entrySet())
            clean.put(e.getKey(), e.getValue());
        dirty.clear();
        evict();
        return out;
    }
}
