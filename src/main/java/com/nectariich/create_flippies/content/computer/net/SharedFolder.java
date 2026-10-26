package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The world's file share: a folder on the server's disk served live as one host,
 * {@link #HOST}, so a player drops files into {@code <world>/localfilesys/} and fetches them on a
 * PC with {@code wget} or a browser. Nothing is cached: a file added, changed or removed shows at
 * the next request. Read only.
 *
 * Nothing outside the folder is ever served. {@link Catalogue} has already refused '..', '.' and
 * empty segments; here every path is resolved to its real path (symbolic links followed) and must
 * still lie under the folder's own real path, both when a file is opened and when it is reopened
 * for a later block, and a listing leaves out links that lead out. Only regular files are
 * served, so a FIFO or device node never blocks the server thread.
 */
public final class SharedFolder implements ContentSource {

    /** The host name the share answers to. */
    public static final String HOST = "localfilesys.com";
    /** The folder's name in the world save. */
    public static final String FOLDER = "localfilesys";

    private final Path root;

    public SharedFolder(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The share of the world saved at {@code worldDir}, its folder made when missing. */
    public static SharedFolder ofWorld(Path worldDir) throws IOException {
        Path dir = worldDir.resolve(FOLDER);
        Files.createDirectories(dir);
        return new SharedFolder(dir);
    }

    public Path root() {
        return root;
    }

    @Override
    public Set<String> hosts() {
        return Set.of(HOST);
    }

    @Override
    public InputStream open(String path) throws IOException {
        Path p = real(path);
        return p != null && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.newInputStream(p) : null;
    }

    @Override
    public long size(String path) throws IOException {
        Path p = real(path);
        return p != null && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.size(p) : -1;
    }

    /**
     * Folders and regular files, links resolved (one that leads out of the share or nowhere is
     * left out), and names no request could reach ('\', control characters) left out too.
     */
    @Override
    public List<Entry> list(String path, int limit) throws IOException {
        Path dir = real(path);
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            return null;
        Path base = root.toRealPath();
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (entries.size() >= limit)
                    break;
                String name = child.getFileName().toString();
                if (!Catalogue.safePath(name))
                    continue;
                BasicFileAttributes a;
                try {
                    Path target = child.toRealPath();
                    if (!target.startsWith(base))
                        continue;
                    a = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    // A broken link, or gone since the folder was read
                    continue;
                }
                if (a.isDirectory())
                    entries.add(new Entry(name, true, 0));
                else if (a.isRegularFile())
                    entries.add(new Entry(name, false, a.size()));
            }
        }
        return entries;
    }

    /** The player's files are served as they are, never as deflated chunks. */
    @Override
    public boolean chunked() {
        return false;
    }

    /**
     * The real path of {@code path} ({@code HOST/dir/file}, or {@code HOST} for the top folder),
     * or null when it does not exist, is not on this host, or resolves outside the share.
     */
    private Path real(String path) {
        String rest;
        if (path.equals(HOST))
            rest = "";
        else if (path.startsWith(HOST + "/"))
            rest = path.substring(HOST.length() + 1);
        else
            return null;
        try {
            Path base = root.toRealPath();
            Path p = base.resolve(rest).normalize();
            if (!p.startsWith(base))
                return null;
            p = p.toRealPath();
            return p.startsWith(base) ? p : null;
        } catch (IOException | InvalidPathException e) {
            // Missing (or the share folder itself gone), or a name this file system cannot hold
            return null;
        }
    }
}
