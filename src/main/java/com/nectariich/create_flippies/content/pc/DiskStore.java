package com.nectariich.create_flippies.content.pc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.nectariich.create_flippies.content.computer.device.DiskChunkStore;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;

/**
 * The server's disk images (spec 11.2.8): {@code <world>/create_flippies/disks/
 * <DiskId>/<chunk>.bin}, each a deflated 64 KiB chunk; a chunk never written has
 * no file.
 *
 * One shared {@link PagedDiskImage} per id is open at a time, counted by the PCs
 * holding it, so a duplicated creative disk in two PCs is one disk. Dirty chunks go
 * to the background writer on world save, on chunk unload of a PC and when a disk
 * leaves a PC. Server thread only.
 */
public final class DiskStore {

    private record Open(PagedDiskImage image, int[] holders) {}

    private final Path root;
    private final PcFileWriter writer;
    private final int cachedChunks;
    private final Map<UUID, Open> open = new HashMap<>();

    public DiskStore(Path worldDir, PcFileWriter writer, int cachedChunks) {
        this.root = worldDir.resolve("create_flippies").resolve("disks");
        this.writer = writer;
        this.cachedChunks = cachedChunks;
    }

    /** The image for {@code id}, opening it if no PC holds it yet. Pair with {@link #release}. */
    public PagedDiskImage acquire(UUID id, long capacityBytes) {
        Open entry = open.get(id);
        if (entry == null) {
            entry = new Open(new PagedDiskImage(id, capacityBytes, chunks(id), cachedChunks), new int[1]);
            open.put(id, entry);
        }
        entry.holders()[0]++;
        return entry.image();
    }

    /** One holder lets go: its writes are flushed, and the image is closed when nobody holds it. */
    public void release(PagedDiskImage image) {
        if (image == null)
            return;
        flush(image);
        Open entry = open.get(image.id());
        if (entry == null || entry.image() != image)
            return;
        if (--entry.holders()[0] <= 0)
            open.remove(image.id());
    }

    /** Queues the image's dirty chunks for writing. */
    public void flush(PagedDiskImage image) {
        if (image == null || !image.hasDirtyChunks())
            return;
        for (Map.Entry<Integer, byte[]> chunk : image.takeDirtyChunks().entrySet())
            writer.write(chunkPath(image.id(), chunk.getKey()), deflate(chunk.getValue()));
    }

    public void flushAll() {
        for (Open entry : open.values())
            flush(entry.image());
    }

    private Path chunkPath(UUID id, int chunk) {
        return root.resolve(id.toString()).resolve(chunk + ".bin");
    }

    private DiskChunkStore chunks(UUID id) {
        return chunk -> {
            byte[] stored = writer.read(chunkPath(id, chunk));
            return stored == null ? null : inflate(stored);
        };
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
        byte[] buffer = new byte[8192];
        while (!deflater.finished())
            out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(PagedDiskImage.CHUNK_SIZE);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                    throw new IOException("truncated disk chunk");
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt disk chunk", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }
}
