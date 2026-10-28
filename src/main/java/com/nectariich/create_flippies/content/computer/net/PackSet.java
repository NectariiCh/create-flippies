package com.nectariich.create_flippies.content.computer.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content packs layered in lookup order over a base source, the jar's built-in pages (spec
 * 12.4.3). Fixed once made; {@link PackRegistry} makes a new one to reload.
 *
 * - A file belongs to the first pack that has it, plain or as a deflated chunk folder; later
 *   packs' copies are never seen, and are logged once, when the set is made. The base serves
 *   only what no pack has, on its own hosts.
 * - {@code get.flip/index.txt}, the list the netinstall floppy shows, is the exception: it is
 *   every pack's own index lines one after the other, in lookup order, so each OS pack shows.
 *   When the packs list no entry the base's index is served (the jar's says no OS is installed).
 * - The packs stay open while a {@link CatalogueFile} reads from the set ({@link #retain}). A
 *   retired set closes them once the last reader lets go, so a download that started before a
 *   reload finishes from the files it started on.
 */
public final class PackSet implements ContentSource {

    /** The catalogue the netinstall floppy reads (spec 12.4.3). */
    public static final String INDEX = "get.flip/index.txt";
    /** Longest index one pack may bring (the floppy reads at most 16 KiB in all). */
    static final int MAX_INDEX = 64 * 1024;
    /** A path inside a deflated chunk folder: the file it stores, then size.txt or N.z. */
    private static final Pattern CHUNK = Pattern.compile("(.+)\\.z/(size\\.txt|[0-9]+\\.z)");

    private final List<PackContent> packs;
    private final ContentSource base;
    private final Set<String> hosts;
    private final byte[] index;

    private int holders;
    private boolean retired, closed;

    /**
     * Over {@code packs} in lookup order (the first wins) and {@code base} (null for none). Each
     * conflict and a bad index are reported to {@code warn} once, here. The set owns the packs.
     */
    public PackSet(List<PackContent> packs, ContentSource base, Consumer<String> warn) {
        this.packs = List.copyOf(packs);
        this.base = base;
        Set<String> all = new TreeSet<>();
        for (PackContent p : this.packs)
            all.addAll(p.hosts());
        if (base != null)
            all.addAll(base.hosts());
        this.hosts = Collections.unmodifiableSet(all);
        reportConflicts(warn);
        this.index = index(warn);
    }

    public List<PackContent> packs() {
        return packs;
    }

    /** The pack serving {@code path} ({@code host/dir/file}), or null when none does. */
    public PackContent owner(String path) {
        Matcher chunk = CHUNK.matcher(path);
        if (chunk.matches()) {
            // A piece of a deflated file belongs to the pack that serves the file
            String file = chunk.group(1);
            for (PackContent p : packs)
                if (serves(p, file))
                    return p.has(path) ? p : null;
            for (PackContent p : packs)
                if (p.has(path))
                    return p;
            return null;
        }
        for (PackContent p : packs)
            if (serves(p, path))
                return p;
        return null;
    }

    /** {@code file} is in the pack plain or as a deflated chunk folder. */
    private static boolean serves(PackContent p, String file) {
        return p.has(file) || p.has(DeflatedChunks.sizePath(file));
    }

    @Override
    public Set<String> hosts() {
        return hosts;
    }

    @Override
    public InputStream open(String path) throws IOException {
        if (path.equals(INDEX) && index != null)
            return new ByteArrayInputStream(index);
        PackContent p = path.equals(INDEX) ? null : owner(path);
        if (p != null)
            return p.open(path);
        return onBase(path) ? base.open(path) : null;
    }

    @Override
    public long size(String path) throws IOException {
        if (path.equals(INDEX) && index != null)
            return index.length;
        PackContent p = path.equals(INDEX) ? null : owner(path);
        if (p != null)
            return p.size(path);
        return onBase(path) ? base.size(path) : -1;
    }

    /** Folders of the base only (packs list none). */
    @Override
    public List<Entry> list(String path, int limit) throws IOException {
        return onBase(path) ? base.list(path, limit) : null;
    }

    private boolean onBase(String path) {
        if (base == null)
            return false;
        int slash = path.indexOf('/');
        return base.hosts().contains(slash < 0 ? path : path.substring(0, slash));
    }

    // ---- readers and reloads ----

    @Override
    public synchronized void retain() {
        holders++;
    }

    @Override
    public synchronized void release() {
        if (--holders <= 0) {
            holders = 0;
            if (retired)
                closePacks();
        }
    }

    /** No new reader will come: the packs close now, or when the last reader lets go. */
    public synchronized void retire() {
        retired = true;
        if (holders == 0)
            closePacks();
    }

    /** Readers holding the set; for tests and the packs command. */
    public synchronized int holders() {
        return holders;
    }

    public synchronized boolean closed() {
        return closed;
    }

    private void closePacks() {
        if (closed)
            return;
        closed = true;
        for (PackContent p : packs) {
            try {
                p.close();
            } catch (IOException e) {
                // Nothing more to do with it
            }
        }
    }

    // ---- made once ----

    /** One line per pair of packs where the later has paths the earlier serves. */
    private void reportConflicts(Consumer<String> warn) {
        Map<String, PackContent> owners = new HashMap<>();
        for (PackContent p : packs) {
            Map<PackContent, List<String>> lost = new LinkedHashMap<>();
            Set<String> files = new TreeSet<>();
            for (String path : p.paths()) {
                Matcher chunk = CHUNK.matcher(path);
                files.add(chunk.matches() ? chunk.group(1) : path);
            }
            files.remove(INDEX);
            for (String file : files) {
                PackContent winner = owners.putIfAbsent(file, p);
                if (winner != null)
                    lost.computeIfAbsent(winner, w -> new ArrayList<>()).add(file);
            }
            lost.forEach((winner, paths) -> warn.accept("Pack " + p.file().getFileName() + ": " + paths.size()
                    + " file(s) also in " + winner.file().getFileName() + ", which is served (first: " + paths.get(0) + ")"));
        }
    }

    /** Every pack's get.flip/index.txt in lookup order, or null when they list no entry. */
    private byte[] index(Consumer<String> warn) {
        StringBuilder b = new StringBuilder();
        int entries = 0;
        for (PackContent p : packs) {
            long size = p.size(INDEX);
            if (size < 0)
                continue;
            String text;
            try {
                if (size > MAX_INDEX)
                    throw new IOException("larger than " + MAX_INDEX + " bytes");
                try (InputStream in = p.open(INDEX)) {
                    text = new String(in.readNBytes(MAX_INDEX), StandardCharsets.UTF_8);
                }
                entries += CatalogueIndex.parse(text).size();
            } catch (IOException | IllegalArgumentException e) {
                warn.accept("Pack " + p.file().getFileName() + ": its " + INDEX + " is left out: " + e.getMessage());
                continue;
            }
            b.append(text);
            if (!text.isEmpty() && !text.endsWith("\n"))
                b.append('\n');
        }
        return entries == 0 ? null : b.toString().getBytes(StandardCharsets.UTF_8);
    }
}
