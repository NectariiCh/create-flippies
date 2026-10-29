package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The server's content packs (spec 12.4.3): every {@code *.flippack} in its pack folders, in
 * lookup order (the folders in the order given, the world's first; inside a folder by file
 * name), over the jar's built-in pages. Served as one {@link ContentSource} whose
 * {@link #snapshot} is the current {@link PackSet}; {@link #reload} scans the folders again and
 * swaps in a new set.
 *
 * On a reload the old set is retired, not closed: a file a modem is serving keeps reading the old
 * pack until its response ends, and only then is that pack's zip closed. New requests see the new
 * set. A pack that cannot be used (not a zip, no valid pack.json, an id already loaded) is skipped
 * with one line to {@code warn}.
 */
public final class PackRegistry implements ContentSource {

    private final List<Path> folders;
    private final ContentSource base;
    private final Consumer<String> warn;
    private volatile PackSet current;

    /** {@code folders} in lookup order; {@code base} serves what no pack has (null for nothing). */
    public PackRegistry(List<Path> folders, ContentSource base, Consumer<String> warn) {
        this.folders = List.copyOf(folders);
        this.base = base;
        this.warn = Objects.requireNonNull(warn, "warn");
        this.current = new PackSet(List.of(), base, warn);
    }

    public List<Path> folders() {
        return folders;
    }

    /** The packs served now. */
    public PackSet current() {
        return current;
    }

    /**
     * Scans the folders and serves what is there from now on; returns the new set. Problems go to
     * the registry's warn and to {@code also} (the reload command's caller), one line each.
     */
    public synchronized PackSet reload(Consumer<String> also) {
        Consumer<String> report = also == null ? warn : warn.andThen(also);
        List<PackContent> packs = new ArrayList<>();
        Map<String, PackContent> ids = new HashMap<>();
        for (Path folder : folders) {
            for (Path file : packFiles(folder, report)) {
                PackContent pack;
                try {
                    pack = PackContent.open(file);
                } catch (IOException e) {
                    report.accept("Skipping pack " + file + ": " + e.getMessage());
                    continue;
                }
                PackContent same = ids.putIfAbsent(pack.manifest().id(), pack);
                if (same != null) {
                    report.accept("Skipping pack " + file + ": its id " + pack.manifest().id() + " is already loaded from " + same.file());
                    close(pack);
                    continue;
                }
                packs.add(pack);
            }
        }
        PackSet next = new PackSet(packs, base, report);
        PackSet old = current;
        current = next;
        old.retire();
        return next;
    }

    /** The {@code *.flippack} regular files in {@code folder}, by name; none when it is missing. */
    static List<Path> packFiles(Path folder, Consumer<String> report) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(folder))
            return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + PackContent.EXTENSION)) {
            for (Path p : stream)
                if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."))
                    files.add(p);
        } catch (IOException e) {
            report.accept("Could not read the pack folder " + folder + ": " + e.getMessage());
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return files;
    }

    private static void close(PackContent pack) {
        try {
            pack.close();
        } catch (IOException e) {
            // Nothing more to do with it
        }
    }

    /** Retires the current set: its packs close once nothing reads them. */
    public synchronized void close() {
        PackSet old = current;
        current = new PackSet(List.of(), base, warn);
        old.retire();
    }

    // ---- ContentSource: the current set ----

    @Override
    public Set<String> hosts() {
        return current.hosts();
    }

    @Override
    public InputStream open(String path) throws IOException {
        return current.open(path);
    }

    @Override
    public long size(String path) throws IOException {
        return current.size(path);
    }

    @Override
    public List<Entry> list(String path, int limit) throws IOException {
        return current.list(path, limit);
    }

    @Override
    public ContentSource snapshot() {
        return current;
    }
}
