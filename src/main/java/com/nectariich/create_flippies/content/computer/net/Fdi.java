package com.nectariich.create_flippies.content.computer.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The sparse disk image format {@code .fdi} (spec 12.4.4): all-zero chunks are never stored, so
 * they are never sent. Every number is big-endian, unsigned 32 bits (the reader is a 68k):
 *
 * - 0 magic "FDI1".
 * - 4 image size in 512-byte sectors.
 * - 8 chunk size in bytes, always {@link #CHUNK}.
 * - 12 number of chunks present.
 * - 16 CRC32 of the chunk table.
 * - 20 title, UTF-8, at most 63 bytes, padded with zeros to 64.
 * - 84 CRC32 of bytes 0 to 83.
 * - 88 the chunk table: per present chunk, its index then the CRC32 of its data. Indexes rise
 *   strictly; a chunk not in the table is all zeros.
 * - Then the present chunks' data in table order. Every chunk is {@link #CHUNK} bytes except the
 *   image's last chunk, which holds only the bytes inside the image; so entry k's data starts at
 *   {@code dataStart + k * CHUNK} (a Range request resumes there).
 *
 * CRC32 is the one of zlib and {@code java.util.zip.CRC32} (ISO 3309, polynomial 0x04C11DB7).
 */
public final class Fdi {

    private Fdi() {
    }

    public static final int CHUNK = 65536, SECTOR = 512, HEADER = 88, ENTRY = 8, TITLE = 64;
    private static final byte[] MAGIC = { 'F', 'D', 'I', '1' };

    /** One present chunk: its index in the image and the CRC32 of its data. */
    public record Entry(int index, int crc) {
    }

    /** A checked header and chunk table. */
    public record Header(long sectors, String title, List<Entry> entries) {

        public Header {
            entries = List.copyOf(entries);
        }

        public long imageBytes() {
            return sectors * SECTOR;
        }

        /** Chunks the image spans, present or not. */
        public long chunkCount() {
            return (imageBytes() + CHUNK - 1) / CHUNK;
        }

        /** Bytes of chunk {@code index}: {@link #CHUNK}, less for the image's last chunk. */
        public int chunkLength(long index) {
            return (int) Math.min(CHUNK, imageBytes() - index * CHUNK);
        }

        /** Where the chunk data starts in the file. */
        public long dataStart() {
            return HEADER + (long) ENTRY * entries.size();
        }

        /** Where table entry {@code k}'s data starts in the file. */
        public long dataOffset(int k) {
            return dataStart() + (long) k * CHUNK;
        }

        /** The file's total size. */
        public long fileSize() {
            long n = dataStart();
            for (Entry e : entries)
                n += chunkLength(e.index());
            return n;
        }
    }

    // ---- writer ----

    /** The {@code .fdi} of a raw image (its length a multiple of 512). */
    public static byte[] write(byte[] image, String title) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(image, title, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static void write(byte[] image, String title, OutputStream out) throws IOException {
        if (image.length % SECTOR != 0)
            throw new IllegalArgumentException("image length " + image.length + " is not whole sectors");
        long chunks = (image.length + (long) CHUNK - 1) / CHUNK;
        List<Entry> entries = new ArrayList<>();
        for (int c = 0; c < chunks; c++) {
            int from = c * CHUNK, to = (int) Math.min(image.length, from + (long) CHUNK);
            if (!allZero(image, from, to))
                entries.add(new Entry(c, crc(image, from, to - from)));
        }
        DataOutputStream d = new DataOutputStream(out);
        d.write(header(new Header(image.length / SECTOR, title, entries)));
        for (Entry e : entries) {
            int from = e.index() * CHUNK;
            d.write(image, from, Math.min(CHUNK, image.length - from));
        }
        d.flush();
    }

    /** The first {@code dataStart()} bytes of a file with this header and table. */
    public static byte[] header(Header h) {
        byte[] title = h.title().getBytes(StandardCharsets.UTF_8);
        if (title.length >= TITLE || h.title().indexOf('\0') >= 0)
            throw new IllegalArgumentException("title must be under " + TITLE + " bytes of UTF-8 without NUL");
        if (h.sectors() < 0 || h.sectors() > 0xFFFF_FFFFL)
            throw new IllegalArgumentException("sector count " + h.sectors() + " does not fit 32 bits");
        byte[] b = new byte[(int) h.dataStart()];
        for (int k = 0; k < h.entries().size(); k++) {
            Entry e = h.entries().get(k);
            if (e.index() < 0 || e.index() >= h.chunkCount() || k > 0 && e.index() <= h.entries().get(k - 1).index())
                throw new IllegalArgumentException("chunk table must rise strictly inside the image, entry " + k);
            Packets.put32(b, HEADER + ENTRY * k, e.index());
            Packets.put32(b, HEADER + ENTRY * k + 4, e.crc());
        }
        System.arraycopy(MAGIC, 0, b, 0, 4);
        Packets.put32(b, 4, (int) h.sectors());
        Packets.put32(b, 8, CHUNK);
        Packets.put32(b, 12, h.entries().size());
        Packets.put32(b, 16, crc(b, HEADER, ENTRY * h.entries().size()));
        System.arraycopy(title, 0, b, 20, title.length);
        Packets.put32(b, 84, crc(b, 0, 84));
        return b;
    }

    // ---- reader ----

    /** Reads and checks the header and chunk table; IOException naming what is wrong. */
    public static Header readHeader(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] h = new byte[HEADER];
        d.readFully(h);
        if (!Arrays.equals(h, 0, 4, MAGIC, 0, 4))
            throw new IOException("not an FDI1 image");
        if (crc(h, 0, 84) != Packets.i32(h, 84))
            throw new IOException("header CRC32 does not match");
        long sectors = Packets.i32(h, 4) & 0xFFFF_FFFFL;
        if (Packets.i32(h, 8) != CHUNK)
            throw new IOException("chunk size " + Packets.i32(h, 8) + " is not " + CHUNK);
        long count = Packets.i32(h, 12) & 0xFFFF_FFFFL;
        long chunks = (sectors * SECTOR + CHUNK - 1) / CHUNK;
        if (count > chunks)
            throw new IOException(count + " chunks present in an image of " + chunks);
        int end = 20;
        while (end < 20 + TITLE && h[end] != 0)
            end++;
        if (end == 20 + TITLE)
            throw new IOException("title is not terminated");
        String title = new String(h, 20, end - 20, StandardCharsets.UTF_8);
        byte[] table = new byte[(int) count * ENTRY];
        d.readFully(table);
        if (crc(table, 0, table.length) != Packets.i32(h, 16))
            throw new IOException("chunk table CRC32 does not match");
        List<Entry> entries = new ArrayList<>((int) count);
        long last = -1;
        for (int k = 0; k < count; k++) {
            long index = Packets.i32(table, k * ENTRY) & 0xFFFF_FFFFL;
            if (index <= last || index >= chunks)
                throw new IOException("chunk table entry " + k + " (chunk " + index + ") is out of order or outside the image");
            entries.add(new Entry((int) index, Packets.i32(table, k * ENTRY + 4)));
            last = index;
        }
        return new Header(sectors, title, entries);
    }

    /**
     * The next present chunk's data from {@code in}, positioned after the previous one; IOException
     * if it ends early or the CRC32 does not match.
     */
    public static byte[] readChunk(InputStream in, Header h, Entry e) throws IOException {
        byte[] data = new byte[h.chunkLength(e.index())];
        try {
            new DataInputStream(in).readFully(data);
        } catch (EOFException x) {
            throw new IOException("chunk " + e.index() + " ends early", x);
        }
        if (crc(data, 0, data.length) != e.crc())
            throw new IOException("chunk " + e.index() + " CRC32 does not match");
        return data;
    }

    /** The whole raw image of an {@code .fdi}, absent chunks as zeros; for tests and tools. */
    public static byte[] readImage(InputStream in) throws IOException {
        Header h = readHeader(in);
        if (h.imageBytes() > Integer.MAX_VALUE - 8)
            throw new IOException("image too large to hold in memory");
        byte[] image = new byte[(int) h.imageBytes()];
        for (Entry e : h.entries()) {
            byte[] data = readChunk(in, h, e);
            System.arraycopy(data, 0, image, e.index() * CHUNK, data.length);
        }
        return image;
    }

    public static int crc(byte[] b, int at, int length) {
        CRC32 c = new CRC32();
        c.update(b, at, length);
        return (int) c.getValue();
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++)
            if (b[i] != 0)
                return false;
        return true;
    }
}
