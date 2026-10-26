package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Content from a folder on disk laid out like the mod's resources (spec 12.4.3, 12.9): one
 * subfolder per host, named in lower case (as resource paths must be), files at their paths.
 * Used by the headless runner and tests.
 */
public final class FolderContent implements ContentSource {

    private final Path root;

    public FolderContent(Path root) {
        this.root = root;
    }

    /** Every subfolder of the root whose name is lower case; others are skipped, so case-sensitive and case-insensitive file systems agree. */
    @Override
    public Set<String> hosts() {
        Set<String> hosts = new TreeSet<>();
        try (Stream<Path> list = Files.list(root)) {
            list.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> name.equals(name.toLowerCase(Locale.ROOT))).forEach(hosts::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hosts;
    }

    @Override
    public InputStream open(String path) throws IOException {
        Path p = resolve(path);
        return Files.isRegularFile(p) ? Files.newInputStream(p) : null;
    }

    @Override
    public long size(String path) throws IOException {
        Path p = resolve(path);
        return Files.isRegularFile(p) ? Files.size(p) : -1;
    }

    /** {@link Catalogue} has already refused '..' and empty segments; this is a second guard. */
    private Path resolve(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root.normalize()))
            throw new IllegalArgumentException("path leaves the content folder: " + path);
        return p;
    }
}
