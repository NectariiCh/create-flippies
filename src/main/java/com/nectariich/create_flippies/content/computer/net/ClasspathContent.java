package com.nectariich.create_flippies.content.computer.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content bundled with the mod (spec 12.4.3, 12.4.5): resources under a classpath folder, by
 * default {@code data/create_flippies/internet/}, one subfolder per host. Nothing is read until
 * asked for, and a file streams from the jar, so a large image costs no memory up front.
 *
 * A class loader cannot list a folder inside a jar, so the hosts are named in {@code hosts.txt}
 * at the root: one lower-case host per line, '#' lines and blank lines skipped. It is read once.
 */
public final class ClasspathContent implements ContentSource {

    /** Where the game's bundled internet lives in the jar. */
    public static final String ROOT = "data/create_flippies/internet/";
    public static final String HOSTS = "hosts.txt";

    private final ClassLoader loader;
    private final String root;
    private Set<String> hosts;

    /** {@code root} ends with '/' and has no leading '/'. */
    public ClasspathContent(ClassLoader loader, String root) {
        this.loader = Objects.requireNonNull(loader, "loader");
        if (!root.endsWith("/") || root.startsWith("/"))
            throw new IllegalArgumentException("root must end with '/' and not start with one: " + root);
        this.root = root;
    }

    /** The mod's own bundled content, from this class's loader. */
    public static ClasspathContent bundled() {
        return new ClasspathContent(ClasspathContent.class.getClassLoader(), ROOT);
    }

    @Override
    public synchronized Set<String> hosts() {
        if (hosts == null)
            hosts = readHosts();
        return hosts;
    }

    private Set<String> readHosts() {
        Set<String> set = new TreeSet<>();
        try (InputStream in = loader.getResourceAsStream(root + HOSTS)) {
            if (in != null)
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String host = line.trim();
                    if (!host.isEmpty() && !host.startsWith("#"))
                        set.add(host.toLowerCase(Locale.ROOT));
                }
        } catch (IOException e) {
            // No hosts file, no hosts: the modem still answers ARP, ping and DHCP
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public InputStream open(String path) throws IOException {
        URL url = loader.getResource(root + path);
        if (url == null)
            return null;
        Path file = path(url);
        if (file != null)
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        return url.getPath().endsWith("/") ? null : url.openStream();
    }

    /**
     * From the file system when the URL names a path there, else from the jar entry's or the
     * URL's length, else by reading it through. One that cannot be opened is absent.
     */
    @Override
    public long size(String path) {
        URL url = loader.getResource(root + path);
        if (url == null)
            return -1;
        try {
            Path file = path(url);
            if (file != null)
                return Files.isRegularFile(file) ? Files.size(file) : -1;
            if (url.getPath().endsWith("/"))
                return -1;
            var connection = url.openConnection();
            // A plain jar finds "dir" as its entry "dir/" and reports it 0 bytes long
            if (connection instanceof JarURLConnection jar && jar.getJarEntry().isDirectory())
                return -1;
            long length = connection.getContentLengthLong();
            return length >= 0 ? length : ContentSource.super.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * The resource as a file system path, when a provider is installed for its URL's scheme: a
     * "file:" URL in a dev run, and Forge's "union:" URL for a mod jar. There a folder is told
     * apart without reading, and a file streams (Forge's own union URL reads the whole resource
     * into memory and gives no length). Null for a plain "jar:" URL and anything else.
     */
    private static Path path(URL url) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException
                | ProviderNotFoundException | SecurityException e) {
            return null;
        }
    }
}
