package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Where the modem's content comes from (spec 12.4.3): the game backs it with the server's
 * resource manager (the folder {@code data/create_flippies/internet/}), the headless runner and
 * tests with a folder ({@link FolderContent}) or memory ({@link MemoryContent}). A path is
 * {@code host/dir/file}, '/' separated, already decoded and checked by {@link Catalogue}; how a
 * path maps onto names the backing store allows is the implementation's business.
 *
 * Called on the thread that runs the modem; implementations should answer quickly.
 */
public interface ContentSource {

    /** One entry of a folder: its name (one path segment), whether it is a folder, and a file's size. */
    record Entry(String name, boolean folder, long size) {
    }

    /** The host names served, lower case (for example {@code get.flip}). */
    Set<String> hosts();

    /** The resource at {@code path}, or null when there is none. The caller closes it. */
    InputStream open(String path) throws IOException;

    /** The resource's size in bytes, or -1 when there is none. By default it reads it through. */
    default long size(String path) throws IOException {
        try (InputStream in = open(path)) {
            if (in == null)
                return -1;
            long n = 0;
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; )
                n += r;
            return n;
        }
    }

    /**
     * The entries of the folder at {@code path} ({@code host} alone for the host's top folder),
     * in no particular order and at most {@code limit} of them, or null when there is no such
     * folder. By default a source has no folders to list: a path ending in '/' is a 404.
     */
    default List<Entry> list(String path, int limit) throws IOException {
        return null;
    }

    /**
     * Whether a file missing at its path may be stored as {@link DeflatedChunks} instead. True
     * for the bundled internet; a folder of the player's own files serves exactly what it holds.
     */
    default boolean chunked() {
        return true;
    }
}
