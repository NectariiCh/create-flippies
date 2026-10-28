package com.nectariich.create_flippies.content.computer.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One content pack (spec 12.4.3): a zip file named {@code *.flippack} holding {@code pack.json}
 * ({@link PackManifest}) and {@code hosts/<host>/<path>}, served at {@code http://<host>/<path>}
 * for every host the manifest names. Large files are deflated chunk folders as in the jar
 * ({@link DeflatedChunks}), stored in the zip rather than deflated again, as are the .debs.
 *
 * The zip is read with random access: sizes come from its central directory, and a file streams
 * from its entry when asked for, so opening a pack reads only the directory and pack.json. Safe
 * to read from several threads ({@link ZipFile} is). Closing the pack ends every stream open on
 * it; {@link PackSet} decides when.
 */
public final class PackContent implements ContentSource, Closeable {

    public static final String EXTENSION = ".flippack";
    public static final String MANIFEST = "pack.json";
    /** The folder of the served files inside the zip. */
    public static final String HOSTS = "hosts/";
    private static final int MAX_MANIFEST = 64 * 1024;

    private final Path file;
    private final ZipFile zip;
    private final PackManifest manifest;
    private final Set<String> hosts;

    private PackContent(Path file, ZipFile zip, PackManifest manifest) {
        this.file = file;
        this.zip = zip;
        this.manifest = manifest;
        this.hosts = Set.copyOf(manifest.hosts());
    }

    /** Opens the pack at {@code file}; IOException with a message naming the problem when it is not a usable pack. */
    public static PackContent open(Path file) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(file.toFile());
        } catch (IOException e) {
            throw new IOException("not a readable zip file (" + e.getMessage() + ")", e);
        }
        try {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null || entry.isDirectory())
                throw new IOException("no " + MANIFEST + " at its root");
            if (entry.getSize() > MAX_MANIFEST)
                throw new IOException(MANIFEST + " is larger than " + MAX_MANIFEST + " bytes");
            String json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readNBytes(MAX_MANIFEST), StandardCharsets.UTF_8);
            }
            try {
                return new PackContent(file, zip, PackManifest.parse(json));
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e instanceof IOException io ? io : new IOException(e.toString(), e);
        }
    }

    public Path file() {
        return file;
    }

    public PackManifest manifest() {
        return manifest;
    }

    @Override
    public Set<String> hosts() {
        return hosts;
    }

    /** The served paths ({@code host/dir/file}) of every file entry under a host this pack names. */
    public Set<String> paths() {
        Set<String> paths = new HashSet<>();
        zip.stream().forEach(e -> {
            String name = e.getName();
            if (e.isDirectory() || !name.startsWith(HOSTS))
                return;
            String path = name.substring(HOSTS.length());
            int slash = path.indexOf('/');
            if (slash > 0 && hosts.contains(path.substring(0, slash)))
                paths.add(path);
        });
        return paths;
    }

    /** Whether {@code path} is a file of this pack. */
    public boolean has(String path) {
        return entry(path) != null;
    }

    @Override
    public InputStream open(String path) throws IOException {
        ZipEntry e = entry(path);
        if (e == null)
            return null;
        try {
            return zip.getInputStream(e);
        } catch (IllegalStateException closed) {
            throw new IOException(file.getFileName() + " was closed", closed);
        }
    }

    /** From the zip's central directory; never reads the entry. */
    @Override
    public long size(String path) {
        ZipEntry e = entry(path);
        return e == null ? -1 : e.getSize();
    }

    /** Only paths on the manifest's hosts; a closed pack has none. */
    private ZipEntry entry(String path) {
        int slash = path.indexOf('/');
        if (slash <= 0 || !hosts.contains(path.substring(0, slash)))
            return null;
        try {
            ZipEntry e = zip.getEntry(HOSTS + path);
            return e == null || e.isDirectory() || e.getSize() < 0 ? null : e;
        } catch (IllegalStateException closed) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    @Override
    public String toString() {
        return file.getFileName() + " (" + manifest.id() + " " + manifest.version() + ")";
    }
}
