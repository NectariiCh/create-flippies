package com.nectariich.create_flippies.content.computer.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The HTML index page of a folder served over HTTP: a parent link (except at the top), then
 * folders, then files, each by name, with a file's size. Links are relative and percent-encoded
 * (UTF-8), folders' with a trailing '/', so a browser and {@code wget -r --no-parent} walk it.
 * Plain HTML 3.2, which dillo shows as it is.
 */
final class FolderListing {

    private FolderListing() {
    }

    /** The page for the folder at {@code urlPath} (decoded, starting and ending with '/'), showing at most {@code max} entries. */
    static String html(String urlPath, List<ContentSource.Entry> entries, int max) {
        List<ContentSource.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing((ContentSource.Entry e) -> !e.folder()).thenComparing(ContentSource.Entry::name));
        String title = "Index of " + escape(urlPath);
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\">\n<html><head>\n")
                .append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n")
                .append("<title>").append(title).append("</title>\n</head><body>\n<h1>").append(title).append("</h1>\n<pre>\n");
        if (!urlPath.equals("/"))
            h.append("<a href=\"../\">../</a>\n");
        int shown = Math.min(max, sorted.size());
        for (ContentSource.Entry e : sorted.subList(0, shown)) {
            String name = e.folder() ? e.name() + "/" : e.name();
            h.append("<a href=\"").append(encode(e.name())).append(e.folder() ? "/" : "").append("\">")
                    .append(escape(name)).append("</a>");
            if (!e.folder())
                h.append(" ".repeat(Math.max(1, 50 - name.length()))).append(size(e.size()));
            h.append('\n');
        }
        h.append("</pre>\n");
        if (sorted.size() > shown)
            h.append("<p>More entries are not shown.</p>\n");
        return h.append("</body></html>\n").toString();
    }

    /** '&', '<', '>', '"' and '\'' as entities. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** One path segment percent-encoded as UTF-8: everything but RFC 3986's unreserved characters. */
    static String encode(String segment) {
        StringBuilder b = new StringBuilder();
        for (byte x : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~')
                b.append((char) c);
            else
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
        }
        return b.toString();
    }

    /** Bytes, or K, M, G with one decimal (1024 based). */
    static String size(long bytes) {
        if (bytes < 1024)
            return bytes + "";
        double v = bytes;
        String units = "KMGT";
        int u = -1;
        while (v >= 1024 && u < units.length() - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f%c", v, units.charAt(u));
    }
}
