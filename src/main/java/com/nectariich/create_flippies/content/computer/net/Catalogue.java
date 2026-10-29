package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The modem's "internet" (spec 12.4.3): hosts and the files they serve, read through one or more
 * {@link ContentSource}s. A host belongs to the first source that names it: in game the world's
 * {@link SharedFolder}, then the server's packs over the jar's built-in pages ({@link PackRegistry}).
 * A file is found in the source's {@link ContentSource#snapshot} and read from that to its end. A file is plain at its path, or (in a source
 * that allows it) stored in deflated chunks ({@link DeflatedChunks}); plain wins when both exist.
 * A source that lists folders has them served as HTML index pages ({@link FolderListing}). Paths
 * are checked here so no source sees '..', '.' or empty segments.
 */
public final class Catalogue {

    /** Entries one index page shows at most, so a huge folder costs a bounded tick. */
    static final int MAX_LISTED = 2000;
    /** The page a folder that its source does not list serves at its path with a trailing '/', when there is one. */
    static final String INDEX_PAGE = "index.html";

    private final List<ContentSource> sources;

    public Catalogue(ContentSource source, ContentSource... more) {
        ContentSource[] all = new ContentSource[more.length + 1];
        all[0] = Objects.requireNonNull(source, "source");
        System.arraycopy(more, 0, all, 1, more.length);
        this.sources = List.of(all);
    }

    /** The first source. */
    public ContentSource source() {
        return sources.get(0);
    }

    /** A host the DNS server answers and the HTTP server serves; case does not matter. */
    public boolean hasHost(String host) {
        return sourceOf(host) != null;
    }

    private ContentSource sourceOf(String host) {
        if (host == null)
            return null;
        String h = host.toLowerCase(Locale.ROOT);
        for (ContentSource s : sources)
            if (s.hosts().contains(h))
                return s;
        return null;
    }

    /**
     * The file at {@code path} (decoded, no leading '/') on {@code host}, or null when the host or
     * the file does not exist or the path is not a safe one. IOException when the store is broken
     * (a deflated file's size unreadable).
     *
     * The path comes from the guest, and a store may refuse a name it cannot hold (a ':' in a
     * file system path on Windows, a character a resource location does not allow) by throwing.
     * Such a path names nothing there, so the answer is null (a 404), never an exception out of
     * the host's exchange phase.
     */
    public CatalogueFile open(String host, String path) throws IOException {
        ContentSource source = sourceOf(host);
        if (source == null || !safePath(path))
            return null;
        try {
            return find(source.snapshot(), host.toLowerCase(Locale.ROOT) + "/" + path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The HTML index page of the folder at {@code path} on {@code host} (decoded, no leading or
     * trailing '/', empty for the top folder), its {@link CatalogueFile#path} ending in '/'. Where
     * the host's source lists no such folder, the folder's {@link #INDEX_PAGE} file instead (the
     * built-in page at http://get.flip/), or null when there is none.
     */
    public CatalogueFile listing(String host, String path) throws IOException {
        ContentSource source = sourceOf(host);
        if (source == null || !path.isEmpty() && !safePath(path))
            return null;
        String h = host.toLowerCase(Locale.ROOT), full = path.isEmpty() ? h : h + "/" + path;
        List<ContentSource.Entry> entries;
        try {
            source = source.snapshot();
            entries = source.list(full, MAX_LISTED + 1);
            if (entries == null)
                return find(source, full + "/" + INDEX_PAGE);
        } catch (RuntimeException e) {
            return null;
        }
        byte[] html = FolderListing.html(path.isEmpty() ? "/" : "/" + path + "/", entries, MAX_LISTED)
                .getBytes(StandardCharsets.UTF_8);
        String page = full + "/";
        return new CatalogueFile(new MemoryContent().put(page, html), page, html.length, false);
    }

    /** Whether {@code path} (decoded, no leading '/') on {@code host} is a folder its source lists. */
    public boolean isFolder(String host, String path) throws IOException {
        ContentSource source = sourceOf(host);
        if (source == null || !safePath(path))
            return false;
        try {
            return source.snapshot().list(host.toLowerCase(Locale.ROOT) + "/" + path, 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static CatalogueFile find(ContentSource source, String full) throws IOException {
        long size = source.size(full);
        if (size >= 0)
            return new CatalogueFile(source, full, size, false);
        if (!source.chunked())
            return null;
        String sizePath = DeflatedChunks.sizePath(full);
        InputStream in = source.open(sizePath);
        if (in == null)
            return null;
        return new CatalogueFile(source, full, DeflatedChunks.readSize(in, sizePath), true);
    }

    /** Non-empty '/'-separated segments, none of them '.' or '..', no '\', no control characters. */
    static boolean safePath(String path) {
        if (path == null || path.isEmpty())
            return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\')
                return false;
        }
        for (String seg : path.split("/", -1))
            if (seg.isEmpty() || seg.equals(".") || seg.equals(".."))
                return false;
        return true;
    }
}
