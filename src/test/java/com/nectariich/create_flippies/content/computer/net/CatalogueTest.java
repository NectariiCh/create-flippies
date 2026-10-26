package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The content catalogue (spec 12.4.3): plain and deflated files, path checks, the folder source and index.txt. */
class CatalogueTest {

    /** Three whole chunks and a short fourth. */
    private static final byte[] DATA = NetFrames.pattern(3 * 65536 + 1234, 9);

    /** Reads the whole file in pieces of {@code piece} bytes from {@code start}. */
    private static byte[] readAll(CatalogueFile f, long start, int piece) throws IOException {
        byte[] out = new byte[(int) (f.size() - start)];
        byte[] buf = new byte[piece];
        int at = 0;
        while (at < out.length) {
            int n = f.read(start + at, buf, 0, piece);
            assertTrue(n > 0);
            System.arraycopy(buf, 0, out, at, n);
            at += n;
        }
        assertEquals(0, f.read(f.size(), buf, 0, piece), "nothing at the end");
        return out;
    }

    private static byte[] slice(int from) {
        return java.util.Arrays.copyOfRange(DATA, from, DATA.length);
    }

    @Test
    void plainAndDeflatedFilesReadTheSame() throws IOException {
        MemoryContent m = new MemoryContent().put("a.flip/plain.bin", DATA).putAll(DeflatedChunks.store("a.flip/packed.bin", DATA));
        Catalogue c = new Catalogue(m);
        CatalogueFile plain = c.open("a.flip", "plain.bin"), packed = c.open("A.FLIP", "packed.bin");
        assertFalse(plain.deflated());
        assertTrue(packed.deflated());
        assertEquals(DATA.length, plain.size());
        assertEquals(DATA.length, packed.size());
        assertEquals("a.flip/packed.bin", packed.path());
        assertArrayEquals(DATA, readAll(plain, 0, 1460));
        assertArrayEquals(DATA, readAll(packed, 0, 1460));
        assertArrayEquals(slice(70000), readAll(packed, 70000, 4096), "from the middle of a chunk");
        assertArrayEquals(slice(1), readAll(plain, 1, 65536), "back to the start: the stream reopens");
        byte[] buf = new byte[10];
        assertEquals(10, plain.read(3 * 65536 + 5, buf, 0, 10), "the short last block");
        plain.close();
        assertEquals(10, plain.read(5, buf, 0, 10), "readable again after close");
        assertThrows(IllegalArgumentException.class, () -> plain.read(-1, buf, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> plain.read(0, buf, 5, 6));
        // 4 chunks: 0.z to 3.z, and the size
        Map<String, byte[]> store = DeflatedChunks.store("x/y", DATA);
        assertEquals(Set.of("x/y.z/size.txt", "x/y.z/0.z", "x/y.z/1.z", "x/y.z/2.z", "x/y.z/3.z"), store.keySet());
        assertEquals(DATA.length + "\n", new String(store.get("x/y.z/size.txt"), StandardCharsets.US_ASCII));
        assertTrue(store.get("x/y.z/0.z").length < 65536 * 2);
    }

    @Test
    void plainWinsAndMissingFilesAreNull() throws IOException {
        MemoryContent m = new MemoryContent().put("a.flip/f", "plain").putAll(DeflatedChunks.store("a.flip/f", DATA));
        Catalogue c = new Catalogue(m);
        assertEquals(5, c.open("a.flip", "f").size());
        assertNull(c.open("a.flip", "g"));
        assertNull(c.open("b.flip", "f"), "unknown host");
        assertNull(c.open(null, "f"));
        assertTrue(c.hasHost("A.Flip"));
        assertFalse(c.hasHost("b.flip"));
        assertFalse(c.hasHost(null));
        assertEquals(m, c.source());
    }

    @Test
    void brokenDeflatedStoresFailWhenRead() throws IOException {
        MemoryContent m = new MemoryContent().putAll(DeflatedChunks.store("a.flip/f", DATA));
        m.remove("a.flip/f.z/2.z");
        CatalogueFile f = new Catalogue(m).open("a.flip", "f");
        byte[] buf = new byte[100];
        assertEquals(100, f.read(0, buf, 0, 100));
        assertThrows(IOException.class, () -> f.read(2 * 65536, buf, 0, 100), "missing chunk");
        // A chunk that inflates to the wrong length
        m.putAll(DeflatedChunks.store("a.flip/f", new byte[2 * 65536 + 5]));
        m.put("a.flip/f.z/size.txt", String.valueOf(DATA.length));
        CatalogueFile g = new Catalogue(m).open("a.flip", "f");
        assertThrows(IOException.class, () -> g.read(2 * 65536, buf, 0, 100));
        m.put("a.flip/f.z/size.txt", "lots");
        assertThrows(IOException.class, () -> new Catalogue(m).open("a.flip", "f"));
        m.put("a.flip/f.z/size.txt", "-3");
        assertThrows(IOException.class, () -> new Catalogue(m).open("a.flip", "f"));
    }

    @Test
    void aPlainFileShorterThanItsSizeFails() throws IOException {
        ContentSource lying = new ContentSource() {
            @Override
            public Set<String> hosts() {
                return Set.of("a.flip");
            }

            @Override
            public InputStream open(String path) {
                return path.equals("a.flip/f") ? new ByteArrayInputStream(new byte[100]) : null;
            }

            @Override
            public long size(String path) {
                return path.equals("a.flip/f") ? 200 : -1;
            }
        };
        CatalogueFile f = new Catalogue(lying).open("a.flip", "f");
        assertThrows(IOException.class, () -> f.read(0, new byte[10], 0, 10));
    }

    @Test
    void theDefaultSizeReadsTheResourceThrough() throws IOException {
        ContentSource s = new ContentSource() {
            @Override
            public Set<String> hosts() {
                return Set.of();
            }

            @Override
            public InputStream open(String path) {
                return path.equals("x") ? new ByteArrayInputStream(DATA) : null;
            }
        };
        assertEquals(DATA.length, s.size("x"));
        assertEquals(-1, s.size("y"));
    }

    @Test
    void aNameTheStoreCannotHoldIsNotFound() throws IOException {
        // As Path.resolve on Windows does for ':', or a resource location for '+': the store
        // throws, and the answer is no file (a 404), not an exception out of the exchange phase
        MemoryContent m = new MemoryContent().put("a.flip/ok.txt", "ok");
        ContentSource picky = new ContentSource() {
            @Override
            public Set<String> hosts() {
                return m.hosts();
            }

            @Override
            public InputStream open(String path) {
                check(path);
                return m.open(path);
            }

            @Override
            public long size(String path) {
                check(path);
                return m.size(path);
            }

            private void check(String path) {
                if (path.indexOf(':') >= 0)
                    throw new java.nio.file.InvalidPathException(path, "Illegal char <:>");
            }
        };
        Catalogue c = new Catalogue(picky);
        assertNull(c.open("a.flip", "a:b"));
        assertEquals(2, c.open("a.flip", "ok.txt").size());
        HttpSession http = new HttpSession(c);
        byte[] get = "GET /a:b HTTP/1.1\r\nHost: a.flip\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        http.received(get, 0, get.length);
        byte[] out = new byte[(int) http.end()];
        for (int at = 0; at < out.length; )
            at += http.read(at, out, at, out.length - at);
        assertTrue(new String(out, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 404 "));
    }

    @Test
    void unsafePathsAreRefused() {
        for (String p : new String[] { "a/b.txt", "a", "dists/sid/Release", "a b", "x..y" })
            assertTrue(Catalogue.safePath(p), p);
        for (String p : new String[] { "", "/a", "a/", "a//b", "../a", "a/../b", "./a", "a/.", "a\\b", "a\u0000b", "a\nb", null })
            assertFalse(Catalogue.safePath(p), String.valueOf(p));
    }

    @Test
    void folderContent(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("get.flip/sub"));
        Files.createDirectories(dir.resolve("Not.Lower"));
        Files.writeString(dir.resolve("get.flip/sub/hello.txt"), "hello");
        Files.writeString(dir.resolve("stray.txt"), "not a host");
        for (Map.Entry<String, byte[]> e : DeflatedChunks.store("deb.flip/img", DATA).entrySet()) {
            Files.createDirectories(dir.resolve(e.getKey()).getParent());
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
        FolderContent folder = new FolderContent(dir);
        assertEquals(Set.of("get.flip", "deb.flip"), folder.hosts(), "lower-case folders only");
        Catalogue c = new Catalogue(folder);
        CatalogueFile hello = c.open("get.flip", "sub/hello.txt");
        assertEquals(5, hello.size());
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), readAll(hello, 0, 100));
        assertNull(c.open("get.flip", "sub"), "a folder is not a file");
        assertEquals(-1, folder.size("get.flip/none"));
        assertNull(folder.open("get.flip/none"));
        assertThrows(IllegalArgumentException.class, () -> folder.open("../outside"));
        CatalogueFile img = c.open("DEB.flip", "img");
        assertTrue(img.deflated());
        assertArrayEquals(DATA, readAll(img, 0, 30000));
    }

    @Test
    void indexParsesAndFormats() {
        String text = "# the catalogue\n\nimage /images/debian.fdi 400000000 Debian 13 (m68k) with a desktop\r\n"
                + "file\t/readme.txt\t12\t  Read me  \n";
        List<CatalogueIndex.Entry> e = CatalogueIndex.parse(text);
        assertEquals(List.of(new CatalogueIndex.Entry("image", "/images/debian.fdi", 400_000_000L, "Debian 13 (m68k) with a desktop"),
                new CatalogueIndex.Entry("file", "/readme.txt", 12, "Read me")), e);
        assertEquals(e, CatalogueIndex.parse(CatalogueIndex.format(e)));
        assertEquals("file /readme.txt 12 Read me\n", CatalogueIndex.format(e.subList(1, 2)));
        for (String bad : new String[] { "disk /a 1 T", "image a 1 T", "image /a -1 T", "image /a x T", "image /a 1" }) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> CatalogueIndex.parse("\n" + bad + "\n"), bad);
            assertTrue(x.getMessage().startsWith("index line 2:"), x.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new CatalogueIndex.Entry("file", "/a", 1, "two\nlines"));
    }
}
