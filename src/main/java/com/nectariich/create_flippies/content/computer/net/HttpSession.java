package com.nectariich.create_flippies.content.computer.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HTTP/1.1 server side of one TCP connection (spec 12.4.2): GET and HEAD, keep-alive,
 * {@code Content-Length}, one {@code Range} for resumed downloads, 404 for unknown paths. A folder
 * whose source lists folders (the world's share) is an HTML index page at its path with a
 * trailing '/', and a 301 there from the path without one. Bytes
 * from the client go in through {@link #received}; the responses form one output stream that TCP
 * reads at any offset ({@link #read}) until it is acknowledged ({@link #acked}). A body is read
 * from the catalogue a block at a time, never held whole.
 *
 * Requests are answered in order (pipelining works). After a response that closes the connection
 * (an error that leaves the input out of step, {@code Connection: close}, or the client's FIN),
 * input is ignored and {@link #closing} turns true: TCP sends FIN after {@link #end}. While
 * {@link #MAX_QUEUED} responses wait to be acknowledged, no more input is taken: TCP leaves it
 * unacknowledged and the client sends it again.
 */
final class HttpSession {

    /** Longest request line plus headers (a client sending more gets 431 and the connection closes). */
    static final int MAX_HEAD = 8192;
    private static final int MAX_HEADERS = 100;
    /**
     * Responses queued and not yet acknowledged before input stops being read: a client that
     * pipelines requests and never takes the answers cannot make the queue grow without end.
     */
    static final int MAX_QUEUED = 32;

    /** One response: its place in the output stream, head bytes, then {@code length} bytes of {@code file} from {@code from}. */
    private record Response(long start, byte[] head, CatalogueFile file, long from, long length) {
        long end() {
            return start + head.length + length;
        }
    }

    private final Catalogue catalogue;
    private final ArrayDeque<Response> responses = new ArrayDeque<>();
    private final byte[] head = new byte[MAX_HEAD];
    private int headLength;
    /** Bytes of a request body still to skip. */
    private long discard;
    private long end;
    /** Output below this was acknowledged and may be gone. */
    private long released;
    private boolean closing;

    HttpSession(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    /** Bytes of the output stream so far. */
    long end() {
        return end;
    }

    /** No more responses will come: TCP closes once {@link #end} bytes are acknowledged. */
    boolean closing() {
        return closing;
    }

    /**
     * In-order bytes from the client; how many were taken. Fewer than {@code length} only while
     * {@link #MAX_QUEUED} responses wait: the rest must come again. Input after the session
     * closes is taken and ignored.
     */
    int received(byte[] b, int at, int length) {
        int i = at, stop = at + length;
        while (i < stop && !closing) {
            if (responses.size() >= MAX_QUEUED)
                return i - at;
            if (discard > 0) {
                int skip = (int) Math.min(discard, stop - i);
                discard -= skip;
                i += skip;
                continue;
            }
            byte c = b[i++];
            // Empty lines before a request line are ignored (RFC 9112 2.2)
            if (headLength == 0 && (c == '\r' || c == '\n'))
                continue;
            if (headLength == MAX_HEAD) {
                error(431, "Request Header Fields Too Large");
                return length;
            }
            head[headLength++] = c;
            if (c == '\n' && endsBlankLine()) {
                String text = new String(head, 0, headLength, StandardCharsets.ISO_8859_1);
                headLength = 0;
                request(text);
            }
        }
        return length;
    }

    /** The client's FIN: answer what was asked, then close. */
    void inputEnded() {
        closing = true;
    }

    /** The connection was idle too long (or is going away): finish what is queued, then close. */
    void stop() {
        closing = true;
    }

    private boolean endsBlankLine() {
        int n = headLength;
        return n >= 2 && head[n - 2] == '\n' || n >= 3 && head[n - 2] == '\r' && head[n - 3] == '\n';
    }

    // ---- requests ----

    private void request(String text) {
        String[] lines = text.split("\n");
        String[] first = strip(lines[0]).split(" ", -1);
        if (first.length != 3) {
            error(400, "Bad Request");
            return;
        }
        String method = first[0], target = first[1], version = first[2];
        boolean v11 = version.equals("HTTP/1.1");
        if (!v11 && !version.equals("HTTP/1.0")) {
            if (version.matches("HTTP/[2-9]\\.[0-9]"))
                error(505, "HTTP Version Not Supported");
            else
                error(400, "Bad Request");
            return;
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = strip(lines[i]);
            if (line.isEmpty())
                break;
            int colon = line.indexOf(':');
            // obs-fold (a line starting with white space) is refused, as RFC 9112 5.2 allows
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t' || i > MAX_HEADERS) {
                error(400, "Bad Request");
                return;
            }
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        String connection = headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT);
        boolean keepAlive = v11 ? !hasToken(connection, "close") : hasToken(connection, "keep-alive");
        if (headers.containsKey("transfer-encoding")) {
            error(501, "Not Implemented");
            return;
        }
        long bodyLength = 0;
        if (headers.containsKey("content-length")) {
            bodyLength = number(headers.get("content-length"));
            if (bodyLength < 0) {
                error(400, "Bad Request");
                return;
            }
        }
        discard = bodyLength;
        if (!method.equals("GET") && !method.equals("HEAD")) {
            reply(501, "Not Implemented", keepAlive, v11, false);
            return;
        }
        boolean headOnly = method.equals("HEAD");
        String host = headers.get("host"), path;
        if (target.regionMatches(true, 0, "http://", 0, 7)) {
            int slash = target.indexOf('/', 7);
            host = slash < 0 ? target.substring(7) : target.substring(7, slash);
            path = slash < 0 ? "/" : target.substring(slash);
        } else if (target.startsWith("/")) {
            path = target;
        } else {
            error(400, "Bad Request");
            return;
        }
        if (host == null || host.isEmpty()) {
            error(400, "Bad Request");
            return;
        }
        if (host.indexOf(':') >= 0)
            host = host.substring(0, host.indexOf(':'));
        int query = path.indexOf('?');
        if (query >= 0)
            path = path.substring(0, query);
        String decoded = decode(path);
        if (decoded == null) {
            error(400, "Bad Request");
            return;
        }
        CatalogueFile file;
        String rel = decoded.substring(1);
        try {
            if (rel.isEmpty() || rel.endsWith("/")) {
                // A folder: its index page, where the host's source lists folders
                file = catalogue.listing(host, rel.isEmpty() ? "" : rel.substring(0, rel.length() - 1));
            } else {
                file = catalogue.open(host, rel);
                if (file == null && catalogue.isFolder(host, rel)) {
                    // A folder named without its '/': relative links on its page need one
                    StringBuilder to = new StringBuilder("http://").append(host.toLowerCase(Locale.ROOT));
                    for (String seg : rel.split("/"))
                        to.append('/').append(FolderListing.encode(seg));
                    redirect(to.append('/').toString(), keepAlive, v11, headOnly);
                    return;
                }
            }
        } catch (IOException e) {
            reply(500, "Internal Server Error", keepAlive, v11, headOnly);
            return;
        }
        if (file == null) {
            reply(404, "Not Found", keepAlive, v11, headOnly);
            return;
        }
        serve(file, headers, keepAlive, v11, headOnly);
    }

    private void serve(CatalogueFile file, Map<String, String> headers, boolean keepAlive, boolean v11, boolean headOnly) {
        long size = file.size();
        long[] range = headers.containsKey("if-range") ? null : range(headers.get("range"), size);
        StringBuilder h = new StringBuilder();
        long from = 0, length = size;
        if (range != null && range[0] < 0) {
            // Unsatisfiable: 416 with the size (RFC 9110 15.5.17)
            h.append("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */").append(size)
                    .append("\r\nContent-Length: 0\r\n");
            length = 0;
        } else if (range != null) {
            from = range[0];
            length = range[1] - range[0] + 1;
            h.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes ").append(range[0]).append('-')
                    .append(range[1]).append('/').append(size).append("\r\n");
            h.append("Content-Type: ").append(contentType(file.path())).append("\r\nContent-Length: ").append(length).append("\r\n");
        } else {
            h.append("HTTP/1.1 200 OK\r\nContent-Type: ").append(contentType(file.path()))
                    .append("\r\nContent-Length: ").append(size).append("\r\n");
        }
        h.append("Accept-Ranges: bytes\r\n");
        connectionHeader(h, keepAlive, v11);
        h.append("\r\n");
        if (headOnly || length == 0) {
            closeQuietly(file);
            queue(h, null, 0, 0, keepAlive);
        } else {
            queue(h, file, from, length, keepAlive);
        }
    }

    /**
     * The single range of a {@code Range} header against a file of {@code size} bytes: null when
     * there is none to honour (absent, not bytes, several ranges, not valid: the whole file is
     * sent, RFC 9110 14.2), {first, last} inclusive, or {-1, -1} when it cannot be satisfied.
     */
    static long[] range(String value, long size) {
        if (value == null)
            return null;
        String v = value.trim();
        if (!v.regionMatches(true, 0, "bytes=", 0, 6) || v.indexOf(',') >= 0)
            return null;
        String spec = v.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0)
            return null;
        String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
        long first, last;
        if (a.isEmpty()) {
            long suffix = digits(b);
            if (suffix < 0)
                return null;
            if (suffix == 0 || size == 0)
                return new long[] { -1, -1 };
            first = Math.max(0, size - suffix);
            last = size - 1;
        } else {
            first = digits(a);
            last = b.isEmpty() ? Long.MAX_VALUE : digits(b);
            if (first < 0 || last < 0 || last < first)
                return null;
            if (first >= size)
                return new long[] { -1, -1 };
            last = Math.min(last, size - 1);
        }
        return new long[] { first, last };
    }

    /** A run of ASCII digits as a number (Long.MAX_VALUE if it is too long to fit), -1 if not digits. */
    private static long digits(String s) {
        if (s.isEmpty())
            return -1;
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9')
                return -1;
            n = n > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : n * 10 + (c - '0');
        }
        return n;
    }

    private static long number(String s) {
        long n = digits(s.trim());
        return n == Long.MAX_VALUE ? -1 : n;
    }

    /**
     * Percent-decoding of a path as UTF-8; null for a bad escape, an encoded NUL or '/' inside a
     * segment's escape, or bytes that are not UTF-8. The path's characters are the request's bytes
     * (read as ISO-8859-1), so a client that sends a name's UTF-8 unescaped is understood too.
     */
    static String decode(String path) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c != '%') {
                b.write(c);
                continue;
            }
            if (i + 2 >= path.length())
                return null;
            int hi = Character.digit(path.charAt(i + 1), 16), lo = Character.digit(path.charAt(i + 2), 16);
            if (hi < 0 || lo < 0 || hi == 0 && lo == 0 || hi == 2 && lo == 0xF)
                return null;
            b.write(hi << 4 | lo);
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** By the name's extension; a folder's index page (its path ends in '/') is HTML. */
    private static String contentType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".txt"))
            return "text/plain; charset=utf-8";
        if (p.endsWith(".html") || p.endsWith(".htm") || p.endsWith("/"))
            return "text/html; charset=utf-8";
        if (p.endsWith(".png"))
            return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg"))
            return "image/jpeg";
        if (p.endsWith(".gif"))
            return "image/gif";
        return "application/octet-stream";
    }

    private static boolean hasToken(String list, String token) {
        for (String t : list.split(","))
            if (t.trim().equals(token))
                return true;
        return false;
    }

    private static String strip(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    // ---- responses ----

    /** An error after which the input cannot be trusted to be in step: answer and close. */
    private void error(int status, String reason) {
        reply(status, reason, false, true, false);
    }

    /** A status with a one-line text body (only the headers for HEAD). */
    private void reply(int status, String reason, boolean keepAlive, boolean v11, boolean headOnly) {
        byte[] body = (status + " " + reason + "\n").getBytes(StandardCharsets.US_ASCII);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason)
                .append("\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ").append(body.length).append("\r\n");
        connectionHeader(h, keepAlive, v11);
        h.append("\r\n");
        if (!headOnly)
            h.append(new String(body, StandardCharsets.US_ASCII));
        queue(h, null, 0, 0, keepAlive);
    }

    /** 301 to {@code location}, with a one-line text body (only the headers for HEAD). */
    private void redirect(String location, boolean keepAlive, boolean v11, boolean headOnly) {
        byte[] body = ("301 Moved Permanently\n" + location + "\n").getBytes(StandardCharsets.ISO_8859_1);
        StringBuilder h = new StringBuilder("HTTP/1.1 301 Moved Permanently\r\nLocation: ").append(location)
                .append("\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ").append(body.length).append("\r\n");
        connectionHeader(h, keepAlive, v11);
        h.append("\r\n");
        if (!headOnly)
            h.append(new String(body, StandardCharsets.ISO_8859_1));
        queue(h, null, 0, 0, keepAlive);
    }

    private static void connectionHeader(StringBuilder h, boolean keepAlive, boolean v11) {
        if (!keepAlive)
            h.append("Connection: close\r\n");
        else if (!v11)
            h.append("Connection: keep-alive\r\n");
    }

    private void queue(StringBuilder h, CatalogueFile file, long from, long length, boolean keepAlive) {
        Response r = new Response(end, h.toString().getBytes(StandardCharsets.ISO_8859_1), file, from, length);
        responses.add(r);
        end = r.end();
        if (!keepAlive)
            closing = true;
    }

    // ---- the output stream, for TCP ----

    /**
     * Copies output bytes from {@code offset} (not yet acknowledged, below {@link #end}) into
     * {@code dst}; the count copied, which may be short of {@code length} at a response or block
     * boundary, 0 at {@link #end}.
     */
    int read(long offset, byte[] dst, int at, int length) throws IOException {
        if (offset < released)
            throw new IllegalArgumentException("offset " + offset + " was already acknowledged");
        for (Response r : responses) {
            if (offset >= r.end())
                continue;
            long in = offset - r.start();
            if (in < r.head().length) {
                int n = (int) Math.min(length, r.head().length - in);
                System.arraycopy(r.head(), (int) in, dst, at, n);
                return n;
            }
            long body = in - r.head().length;
            return r.file().read(r.from() + body, dst, at, (int) Math.min(length, r.length() - body));
        }
        return 0;
    }

    /** The client has every byte below {@code offset}: responses wholly before it are dropped. */
    void acked(long offset) {
        released = Math.max(released, offset);
        while (!responses.isEmpty() && responses.peek().end() <= offset)
            closeQuietly(responses.poll().file());
    }

    /** Releases every open file; the session is finished. */
    void close() {
        while (!responses.isEmpty())
            closeQuietly(responses.poll().file());
    }

    private static void closeQuietly(CatalogueFile f) {
        if (f == null)
            return;
        try {
            f.close();
        } catch (IOException e) {
            // Nothing left to do with it
        }
    }
}
