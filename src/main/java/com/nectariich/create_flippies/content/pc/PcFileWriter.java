package com.nectariich.create_flippies.content.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.nectariich.create_flippies.CreateFlippies;

/**
 * The single background writer every PC side file goes through (spec 11.2.8):
 * machine states and disk chunks. A write lands in a temporary file that is then
 * renamed over the target, so a crash mid-write leaves the old file whole.
 *
 * What is queued and not yet on disk is kept in {@link #pending}, so a reader
 * that asks before the writer got there (a disk re-opened right after it was
 * flushed) sees the newest bytes, not the stale file.
 */
public final class PcFileWriter {

    /** Marks a queued delete in {@link #pending}. */
    private static final byte[] DELETED = new byte[0];

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Create Flippies PC writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Path, byte[]> pending = new ConcurrentHashMap<>();

    public void write(Path path, byte[] bytes) {
        pending.put(path, bytes);
        executor.execute(() -> {
            byte[] newest = pending.get(path);
            if (newest == null)
                return;
            try {
                if (newest == DELETED) {
                    Files.deleteIfExists(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                    Files.write(temp, newest);
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                CreateFlippies.LOGGER.error("Could not write PC file {}", path, e);
            } finally {
                pending.remove(path, newest);
            }
        });
    }

    public void delete(Path path) {
        write(path, DELETED);
    }

    /** The file's bytes as they will be once the queue drains: queued bytes first, else the file; null if none. */
    public byte[] read(Path path) throws IOException {
        byte[] queued = pending.get(path);
        if (queued != null)
            return queued == DELETED ? null : queued;
        return Files.exists(path) ? Files.readAllBytes(path) : null;
    }

    /** Waits for everything queued, for a server that is stopping. */
    public void drain() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS))
                CreateFlippies.LOGGER.error("PC writer did not finish within 30 s; some PC files may be stale");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
