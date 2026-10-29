package com.nectariich.create_flippies.content.computer.net;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Content held in memory, path to bytes (spec 12.4.3); for tests and small tools. */
public final class MemoryContent implements ContentSource {

    private final Map<String, byte[]> files = new TreeMap<>();

    /** Adds or replaces {@code path} ({@code host/dir/file}); returns this. */
    public MemoryContent put(String path, byte[] data) {
        files.put(path, data.clone());
        return this;
    }

    public MemoryContent put(String path, String text) {
        return put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds every entry of {@code more}, as {@link DeflatedChunks#store} returns them. */
    public MemoryContent putAll(Map<String, byte[]> more) {
        more.forEach(this::put);
        return this;
    }

    public MemoryContent remove(String path) {
        files.remove(path);
        return this;
    }

    /** The first path segment of every file. */
    @Override
    public Set<String> hosts() {
        Set<String> hosts = new TreeSet<>();
        for (String p : files.keySet())
            if (p.indexOf('/') > 0)
                hosts.add(p.substring(0, p.indexOf('/')));
        return hosts;
    }

    @Override
    public InputStream open(String path) {
        byte[] f = files.get(path);
        return f == null ? null : new ByteArrayInputStream(f);
    }

    @Override
    public long size(String path) {
        byte[] f = files.get(path);
        return f == null ? -1 : f.length;
    }
}
