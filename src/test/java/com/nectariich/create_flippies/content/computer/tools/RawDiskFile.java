package com.nectariich.create_flippies.content.computer.tools;

import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A raw disk image file (sector 0 at byte 0, no header) as a {@link PagedDiskImage} for the
 * headless runner (spec 12.9). Chunks are read from the file on first access; what the guest
 * writes stays in memory until {@link #flush} writes the changed chunks back in place. A
 * read-only disk never writes the file; the guest's writes are kept in memory and lost at exit.
 *
 * A file shorter than the disk reads as zeros past its end, and grows when a chunk there is
 * written back.
 */
final class RawDiskFile implements Closeable {

    /** Clean chunks kept in memory: 16 MiB. */
    private static final int CACHED_CHUNKS = 256;

    private final Path path;
    private final RandomAccessFile file;
    private final PagedDiskImage image;
    private final boolean readOnly;

    private RawDiskFile(Path path, long capacityBytes, boolean readOnly) throws IOException {
        this.path = path;
        this.readOnly = readOnly;
        this.file = new RandomAccessFile(path.toFile(), readOnly ? "r" : "rw");
        this.image = new PagedDiskImage(null, capacityBytes, this::load, CACHED_CHUNKS);
    }

    /**
     * Opens {@code path} as a disk of its own size, which must be a multiple of 512 bytes. A
     * missing file is created empty at {@code createBytes} when that is positive (sparse where
     * the file system allows), and is an error otherwise.
     */
    static RawDiskFile open(Path path, long createBytes, boolean readOnly) throws IOException {
        if (!Files.exists(path)) {
            if (createBytes <= 0)
                throw new IOException(path + " does not exist");
            if (readOnly)
                throw new IOException(path + " does not exist, and a read-only disk is not created");
            try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) {
                f.setLength(createBytes);
            }
        }
        long size = Files.size(path);
        if (size == 0 || size % DiskImage.SECTOR_SIZE != 0)
            throw new IOException(path + " is " + size + " bytes, not a whole number of 512-byte sectors");
        return new RawDiskFile(path, size, readOnly);
    }

    /** Opens {@code path} as a disk of exactly {@code capacityBytes}; the file may be shorter, not longer. */
    static RawDiskFile openAs(Path path, long capacityBytes, boolean readOnly) throws IOException {
        long size = Files.size(path);
        if (size > capacityBytes)
            throw new IOException(path + " is " + size + " bytes, more than the " + capacityBytes + " the disk holds");
        return new RawDiskFile(path, capacityBytes, readOnly);
    }

    DiskImage image() {
        return image;
    }

    Path path() {
        return path;
    }

    /** Writes every chunk changed since the last flush back to the file; returns how many. None for a read-only disk. */
    int flush() throws IOException {
        if (readOnly || !image.hasDirtyChunks())
            return 0;
        Map<Integer, byte[]> chunks = image.takeDirtyChunks();
        for (Map.Entry<Integer, byte[]> e : chunks.entrySet()) {
            file.seek((long) e.getKey() * PagedDiskImage.CHUNK_SIZE);
            file.write(e.getValue());
        }
        file.getFD().sync();
        return chunks.size();
    }

    private byte[] load(int chunk) throws IOException {
        long at = (long) chunk * PagedDiskImage.CHUNK_SIZE;
        long length = file.length();
        if (at >= length)
            return null;
        byte[] data = new byte[(int) Math.min(PagedDiskImage.CHUNK_SIZE, length - at)];
        file.seek(at);
        file.readFully(data);
        return data;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
