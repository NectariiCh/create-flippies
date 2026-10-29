package com.nectariich.create_flippies.content.computer.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code get.flip/index.txt}, the list the netinstall floppy shows (spec 12.4.3). One entry per
 * line: kind, path, size in bytes, title, separated by spaces or tabs; the title is the rest of
 * the line. Kind {@code image} is a disk image (12.4.4), {@code file} a plain file. The path is
 * an absolute URL path on {@code get.flip}, starting with '/'. Blank lines and lines starting
 * with '#' are skipped. Lines end with LF (a CR before it is ignored).
 */
public final class CatalogueIndex {

    private CatalogueIndex() {
    }

    public static final String IMAGE = "image", FILE = "file";

    /** One line of the index. */
    public record Entry(String kind, String path, long size, String title) {
        public Entry {
            Objects.requireNonNull(title, "title");
            if (!IMAGE.equals(kind) && !FILE.equals(kind))
                throw new IllegalArgumentException("kind must be image or file: " + kind);
            if (path == null || !path.startsWith("/") || path.chars().anyMatch(c -> c <= ' '))
                throw new IllegalArgumentException("path must start with '/' and hold no spaces: " + path);
            if (size < 0)
                throw new IllegalArgumentException("negative size " + size);
            if (title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0)
                throw new IllegalArgumentException("title holds a line break");
        }
    }

    /** The entries of {@code text}; IllegalArgumentException naming the line of the first bad one. */
    public static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.isBlank() || line.startsWith("#"))
                continue;
            String[] f = line.trim().split("[ \t]+", 4);
            try {
                if (f.length < 4)
                    throw new IllegalArgumentException("needs kind, path, size and title");
                entries.add(new Entry(f[0], f[1], Long.parseLong(f[2]), f[3].trim()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("index line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return entries;
    }

    /** The text of an index holding {@code entries}, one line each. */
    public static String format(List<Entry> entries) {
        StringBuilder b = new StringBuilder();
        for (Entry e : entries)
            b.append(e.kind()).append(' ').append(e.path()).append(' ').append(e.size()).append(' ').append(e.title()).append('\n');
        return b.toString();
    }
}
