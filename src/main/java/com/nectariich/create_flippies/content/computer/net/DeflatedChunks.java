package com.nectariich.create_flippies.content.computer.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The deflated form of a large file in the content folder (spec 12.4.3), so the jar holds
 * compressed data while the wire carries plain bytes. A file {@code host/dir/name} stored this
 * way is a folder {@code host/dir/name.z/} holding:
 *
 * - {@code size.txt}: the plain size in bytes, decimal ASCII (surrounding white space ignored).
 * - {@code 0.z}, {@code 1.z}, ...: chunk n (decimal, no padding) as a zlib stream (RFC 1950),
 *   inflating to exactly {@link #CHUNK} bytes, except the last, which holds the rest.
 *
 * Each chunk is separate so the modem can start at any 64 KiB boundary (a Range request, a
 * retransmission) without inflating what comes before it. {@code guest/debian/mkfdi.py} writes
 * the same layout.
 */
public final class DeflatedChunks {

    private DeflatedChunks() {
    }

    /** Plain bytes per chunk (spec 12.4.3). */
    public static final int CHUNK = 65536;

    public static String sizePath(String path) {
        return path + ".z/size.txt";
    }

    public static String chunkPath(String path, long chunk) {
        return path + ".z/" + chunk + ".z";
    }

    /** Chunks a file of {@code size} bytes takes. */
    public static long chunkCount(long size) {
        return (size + CHUNK - 1) / CHUNK;
    }

    /** The resources, by path, that store {@code plain} as {@code path} in deflated form. */
    public static Map<String, byte[]> store(String path, byte[] plain) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(sizePath(path), (plain.length + "\n").getBytes(StandardCharsets.US_ASCII));
        for (int c = 0; c < chunkCount(plain.length); c++) {
            int from = c * CHUNK;
            out.put(chunkPath(path, c), deflate(plain, from, Math.min(CHUNK, plain.length - from)));
        }
        return out;
    }

    private static byte[] deflate(byte[] b, int at, int length) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bytes, d)) {
            z.write(b, at, length);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            d.end();
        }
        return bytes.toByteArray();
    }

    /** Inflates one chunk into {@code dst}; IOException unless it holds exactly {@code expected} bytes. */
    static void inflate(InputStream zlib, byte[] dst, int expected, String what) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(zlib)) {
            int n = in.readNBytes(dst, 0, expected);
            if (n != expected || in.read() >= 0)
                throw new IOException(what + " does not inflate to " + expected + " bytes");
        }
    }

    /** The plain size from a {@code size.txt}; IOException if it is not a number. */
    static long readSize(InputStream in, String what) throws IOException {
        try (in) {
            String text = new String(in.readNBytes(64), StandardCharsets.US_ASCII).trim();
            try {
                long size = Long.parseLong(text);
                if (size < 0)
                    throw new NumberFormatException();
                return size;
            } catch (NumberFormatException e) {
                throw new IOException(what + " is not a size: " + text);
            }
        }
    }
}
