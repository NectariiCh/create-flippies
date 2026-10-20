package com.nectariich.create_flippies.content.computer.device;

import java.io.IOException;

/**
 * Where a {@link PagedDiskImage} loads its 64 KiB chunks from (spec 11.1.6). The game implements
 * it over files; tests use a map. Called on the machine's thread, on a chunk's first access.
 */
public interface DiskChunkStore {

    /** The chunk's bytes (CHUNK_SIZE, the last chunk may be shorter), or null if never written. */
    byte[] load(int chunk) throws IOException;
}
