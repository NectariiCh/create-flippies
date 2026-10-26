package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The sparse disk image format (spec 12.4.4): byte layout, round trip, and every check. */
class FdiTest {

    /** 5 chunks and 3 sectors: chunks 1 and 3 all zero, the last one 1536 bytes long. */
    private static byte[] image() {
        byte[] img = new byte[5 * 65536 + 3 * 512];
        byte[] p = NetFrames.pattern(img.length, 5);
        for (int c : new int[] { 0, 2, 4 })
            System.arraycopy(p, c * 65536, img, c * 65536, Math.min(65536, img.length - c * 65536));
        System.arraycopy(p, 5 * 65536, img, 5 * 65536, 1536);
        img[2 * 65536 + 7] = 0; // a zero byte inside a present chunk changes nothing
        return img;
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    @Test
    void crcIsTheZlibOne() {
        // The standard check value of CRC-32 (ISO 3309 / zlib) over "123456789"
        assertEquals(0xCBF4_3926, Fdi.crc("123456789".getBytes(StandardCharsets.US_ASCII), 0, 9));
    }

    @Test
    void layoutByteByByte() throws IOException {
        byte[] img = image();
        byte[] f = Fdi.write(img, "Test image");
        assertArrayEquals("FDI1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(f, 4));
        assertEquals(img.length / 512, be32(f, 4), "sectors");
        assertEquals(65536, be32(f, 8), "chunk size");
        assertEquals(4, be32(f, 12), "chunks present: 0, 2, 4 and the short 5");
        assertEquals("Test image", new String(f, 20, 10, StandardCharsets.UTF_8));
        for (int i = 30; i < 84; i++)
            assertEquals(0, f[i], "title padding " + i);
        assertEquals(Fdi.crc(f, 0, 84), be32(f, 84), "header CRC32");
        assertEquals(Fdi.crc(f, 88, 32), be32(f, 16), "table CRC32");
        int[] chunks = { 0, 2, 4, 5 };
        for (int k = 0; k < 4; k++) {
            int c = chunks[k], length = Math.min(65536, img.length - c * 65536);
            assertEquals(c, be32(f, 88 + 8 * k), "table index " + k);
            assertEquals(Fdi.crc(img, c * 65536, length), be32(f, 92 + 8 * k), "table CRC " + k);
            int data = 88 + 32 + k * 65536;
            assertArrayEquals(Arrays.copyOfRange(img, c * 65536, c * 65536 + length), Arrays.copyOfRange(f, data, data + length),
                    "chunk " + c + " at dataStart + k * 64 KiB");
        }
        assertEquals(88 + 32 + 3 * 65536 + 1536, f.length);

        Fdi.Header h = Fdi.readHeader(new ByteArrayInputStream(f));
        assertEquals(img.length / 512, h.sectors());
        assertEquals("Test image", h.title());
        assertEquals(List.of(new Fdi.Entry(0, Fdi.crc(img, 0, 65536)), new Fdi.Entry(2, Fdi.crc(img, 2 * 65536, 65536)),
                new Fdi.Entry(4, Fdi.crc(img, 4 * 65536, 65536)), new Fdi.Entry(5, Fdi.crc(img, 5 * 65536, 1536))), h.entries());
        assertEquals(6, h.chunkCount());
        assertEquals(1536, h.chunkLength(5));
        assertEquals(120, h.dataStart());
        assertEquals(120 + 2 * 65536, h.dataOffset(2));
        assertEquals(f.length, h.fileSize());
        assertArrayEquals(Arrays.copyOf(f, 120), Fdi.header(h), "the header writer alone");
    }

    @Test
    void roundTrip() throws IOException {
        byte[] img = image();
        assertArrayEquals(img, Fdi.readImage(new ByteArrayInputStream(Fdi.write(img, "x"))));
        byte[] empty = new byte[4 * 65536];
        byte[] f = Fdi.write(empty, "");
        assertEquals(88, f.length, "all zero: header only");
        assertArrayEquals(empty, Fdi.readImage(new ByteArrayInputStream(f)));
        assertArrayEquals(new byte[0], Fdi.readImage(new ByteArrayInputStream(Fdi.write(new byte[0], "none"))));
        String title = "é".repeat(31);
        assertEquals(title, Fdi.readHeader(new ByteArrayInputStream(Fdi.write(new byte[512], title))).title(), "62 bytes of UTF-8");
    }

    @Test
    void streamingReadChecksEachChunk() throws IOException {
        byte[] img = image();
        byte[] f = Fdi.write(img, "x");
        ByteArrayInputStream in = new ByteArrayInputStream(f);
        Fdi.Header h = Fdi.readHeader(in);
        for (Fdi.Entry e : h.entries()) {
            byte[] data = Fdi.readChunk(in, h, e);
            assertArrayEquals(Arrays.copyOfRange(img, e.index() * 65536, e.index() * 65536 + data.length), data);
        }
        byte[] bad = f.clone();
        bad[120 + 65536 + 3] ^= 1;
        IOException x = assertThrows(IOException.class, () -> Fdi.readImage(new ByteArrayInputStream(bad)));
        assertEquals("chunk 2 CRC32 does not match", x.getMessage());
        x = assertThrows(IOException.class, () -> Fdi.readImage(new ByteArrayInputStream(Arrays.copyOf(f, f.length - 1))));
        assertEquals("chunk 5 ends early", x.getMessage());
    }

    @Test
    void badHeadersAreRefused() {
        byte[] f = Fdi.write(image(), "x");
        String[][] cases = { { "0", "not an FDI1 image" }, { "5", "header CRC32 does not match" }, { "90", "chunk table CRC32 does not match" } };
        for (String[] c : cases) {
            byte[] b = f.clone();
            b[Integer.parseInt(c[0])] ^= 1;
            IOException x = assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(b)));
            assertEquals(c[1], x.getMessage());
        }
        // Fields that are wrong but carry a good header CRC
        byte[] b = f.clone();
        Packets.put32(b, 8, 4096);
        Packets.put32(b, 84, Fdi.crc(b, 0, 84));
        assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(b)), "chunk size");
        byte[] c = f.clone();
        Packets.put32(c, 12, 7);
        Packets.put32(c, 84, Fdi.crc(c, 0, 84));
        assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(c)), "more chunks than the image has");
        byte[] d = f.clone();
        Packets.put32(d, 96, 0); // entry 1 says chunk 0 again
        Packets.put32(d, 16, Fdi.crc(d, 88, 32));
        Packets.put32(d, 84, Fdi.crc(d, 0, 84));
        IOException x = assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(d)));
        assertTrue(x.getMessage().contains("out of order"), x.getMessage());
        byte[] e = f.clone();
        Arrays.fill(e, 20, 84, (byte) 'a');
        Packets.put32(e, 84, Fdi.crc(e, 0, 84));
        assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(e)), "title not terminated");
        assertThrows(IOException.class, () -> Fdi.readHeader(new ByteArrayInputStream(Arrays.copyOf(f, 50))), "short header");
    }

    @Test
    void writerChecksItsInput() {
        assertThrows(IllegalArgumentException.class, () -> Fdi.write(new byte[513], "x"), "whole sectors");
        assertThrows(IllegalArgumentException.class, () -> Fdi.write(new byte[512], "t".repeat(64)), "title too long");
        assertThrows(IllegalArgumentException.class, () -> Fdi.write(new byte[512], "a\0b"), "NUL in the title");
        assertThrows(IllegalArgumentException.class,
                () -> Fdi.header(new Fdi.Header(256, "x", List.of(new Fdi.Entry(1, 0), new Fdi.Entry(1, 0)))), "table must rise");
        assertThrows(IllegalArgumentException.class, () -> Fdi.header(new Fdi.Header(128, "x", List.of(new Fdi.Entry(1, 0)))),
                "inside the image");
        assertTrue(Fdi.header(new Fdi.Header(0xFFFF_FFFFL, "big", List.of())).length == 88, "the largest image size fits");
    }
}
