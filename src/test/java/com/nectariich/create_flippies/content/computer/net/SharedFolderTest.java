package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The world's file share served as localfilesys.com: index pages, decoding, the folder's bounds, live files. */
class SharedFolderTest {

    private static final byte[] BIG = NetFrames.pattern(200_000, 5);

    @TempDir
    Path tmp;
    private Path share, outside;
    private HttpSession http;
    private final TestClient reader = new TestClient();
    private long read;
    private int at;

    @BeforeEach
    void setUp() throws IOException {
        share = Files.createDirectories(tmp.resolve("world/localfilesys"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createDirectories(share.resolve("a dir/inner"));
        Files.writeString(share.resolve("a dir/note.txt"), "note");
        Files.writeString(share.resolve("b&c<d>.txt"), "odd");
        Files.writeString(share.resolve("über.txt"), "umlaut");
        Files.write(share.resolve("big.bin"), BIG);
        Catalogue catalogue = new Catalogue(new SharedFolder(share), new MemoryContent().put("get.flip/index.txt", "x"));
        http = new HttpSession(catalogue);
    }

    private void in(String text) {
        byte[] b = text.getBytes(StandardCharsets.ISO_8859_1);
        http.received(b, 0, b.length);
    }

    private TestClient.HttpReply next(boolean head) throws IOException {
        byte[] buf = new byte[1000];
        while (read < http.end()) {
            int n = http.read(read, buf, 0, (int) Math.min(777, http.end() - read));
            assertTrue(n > 0);
            reader.stream.write(buf, 0, n);
            read += n;
            http.acked(read);
        }
        TestClient.HttpReply r = reader.response(at, head);
        assertTrue(r != null, "a whole response");
        at = r.end();
        return r;
    }

    private TestClient.HttpReply get(String path, String... headers) throws IOException {
        in("GET " + path + " HTTP/1.1\r\nHost: localfilesys.com\r\n" + String.join("", headers) + "\r\n");
        return next(false);
    }

    private static String text(TestClient.HttpReply r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void worldFolderIsMadeWhenMissing(@TempDir Path world) throws IOException {
        SharedFolder s = SharedFolder.ofWorld(world);
        assertTrue(Files.isDirectory(world.resolve("localfilesys")));
        assertEquals(world.resolve("localfilesys").toAbsolutePath().normalize(), s.root());
        SharedFolder.ofWorld(world);
        assertTrue(new Catalogue(s).hasHost("LocalFileSys.com"), "DNS answers it, any case");
    }

    @Test
    void topFolderListing() throws IOException {
        TestClient.HttpReply r = get("/");
        assertEquals(200, r.status());
        assertEquals("text/html; charset=utf-8", r.header("content-type"));
        String page = text(r);
        assertTrue(page.contains("<title>Index of /</title>"), page);
        assertFalse(page.contains("href=\"../\""), "no parent at the top");
        assertTrue(page.contains("<a href=\"a%20dir/\">a dir/</a>"), page);
        assertTrue(page.contains("<a href=\"b%26c%3Cd%3E.txt\">b&amp;c&lt;d&gt;.txt</a>"), "names escaped, links encoded");
        assertTrue(page.contains("<a href=\"%C3%BCber.txt\">über.txt</a>"), "UTF-8 names percent-encoded");
        assertTrue(page.contains("195.3K"), "sizes shown");
        assertTrue(page.indexOf("a%20dir/") < page.indexOf("big.bin"), "folders first");
        assertTrue(page.indexOf("b%26c") < page.indexOf("big.bin"), "then files by name");
    }

    @Test
    void subfolderListingAndRedirect() throws IOException {
        TestClient.HttpReply r = get("/a%20dir/");
        assertEquals(200, r.status());
        String page = text(r);
        assertTrue(page.contains("<title>Index of /a dir/</title>"), page);
        assertTrue(page.contains("<a href=\"../\">../</a>"), "a parent link");
        assertTrue(page.contains("<a href=\"inner/\">inner/</a>"));
        assertTrue(page.contains("<a href=\"note.txt\">note.txt</a>"));
        r = get("/a%20dir");
        assertEquals(301, r.status(), "a folder without its '/'");
        assertEquals("http://localfilesys.com/a%20dir/", r.header("location"));
        assertFalse(http.closing(), "the redirect keeps the connection");
        assertEquals(404, get("/big.bin/").status(), "a file is not a folder");
        assertEquals(404, get("/nothing/").status());
        in("HEAD /a%20dir/ HTTP/1.1\r\nHost: localfilesys.com\r\n\r\n");
        assertEquals(200, next(true).status());
        assertEquals("note", text(get("/a%20dir/note.txt")), "and the stream is still in step");
    }

    @Test
    void namesAreDecoded() throws IOException {
        assertEquals("umlaut", text(get("/%C3%BCber.txt")));
        assertEquals("umlaut", text(get(new String("/über.txt".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1))),
                "UTF-8 sent unescaped");
        assertEquals("odd", text(get("/b%26c%3cd%3e.txt")));
        assertEquals("note", text(get("/a dir/note.txt".replace(" ", "%20"))));
        assertNull(HttpSession.decode("/%FF.txt"), "not UTF-8");
        assertEquals(400, get("/%FF.txt").status());
    }

    @Test
    void nothingAboveTheFolderIsServed() throws IOException {
        Files.writeString(share.getParent().resolve("level.dat"), "world");
        for (String path : new String[] { "/../level.dat", "/a%20dir/../../level.dat", "/%2e%2e/level.dat",
                "/a%20dir/%2E%2E/%2E%2E/outside/secret.txt", "/%5c..%5clevel.dat" })
            assertEquals(404, get(path).status(), path);
        // Last: a 400 closes the connection
        assertEquals(400, get("/..%2flevel.dat").status(), "an encoded '/' is refused");
        SharedFolder s = new SharedFolder(share);
        assertNull(s.open("localfilesys.com/../level.dat"));
        assertEquals(-1, s.size("localfilesys.com/a dir/../../level.dat"));
        assertNull(s.list("localfilesys.com/..", 10));
        assertNull(s.open("get.flip/index.txt"), "other hosts are not here");
    }

    @Test
    void linksOutOfTheFolderAreRefused() throws IOException {
        try {
            Files.createSymbolicLink(share.resolve("leak.txt"), outside.resolve("secret.txt"));
            Files.createSymbolicLink(share.resolve("leakdir"), outside);
            Files.createSymbolicLink(share.resolve("ok.txt"), share.resolve("a dir/note.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("no symbolic links here: " + e);
        }
        assertEquals(404, get("/leak.txt").status());
        assertEquals(404, get("/leakdir/").status());
        assertEquals(404, get("/leakdir").status(), "no redirect into it either");
        assertEquals(404, get("/leakdir/secret.txt").status());
        assertEquals("note", text(get("/ok.txt")), "a link that stays inside is followed");
        String page = text(get("/"));
        assertFalse(page.contains("leak"), "links that leave are not listed");
        assertTrue(page.contains("ok.txt"));
    }

    @Test
    void filesAddedLaterAreServed() throws IOException {
        assertEquals(404, get("/later.txt").status());
        Files.writeString(share.resolve("later.txt"), "added");
        assertEquals("added", text(get("/later.txt")));
        assertTrue(text(get("/")).contains("later.txt"));
        Files.delete(share.resolve("later.txt"));
        assertEquals(404, get("/later.txt").status(), "and gone when removed");
    }

    @Test
    void rangeOnAShareFile() throws IOException {
        TestClient.HttpReply r = get("/big.bin", "Range: bytes=70000-70009\r\n");
        assertEquals(206, r.status());
        assertEquals("bytes 70000-70009/200000", r.header("content-range"));
        assertArrayEquals(Arrays.copyOfRange(BIG, 70000, 70010), r.body());
        r = get("/big.bin", "Range: bytes=-5\r\n");
        assertArrayEquals(Arrays.copyOfRange(BIG, BIG.length - 5, BIG.length), r.body());
        r = get("/big.bin", "Range: bytes=131000-\r\n");
        assertArrayEquals(Arrays.copyOfRange(BIG, 131000, BIG.length), r.body(), "across a block boundary");
        assertEquals(416, get("/big.bin", "Range: bytes=200000-\r\n").status());
    }

    @Test
    void sharedFilesAreNeverDeflatedChunks() throws IOException {
        for (Map.Entry<String, byte[]> e : DeflatedChunks.store("packed", BIG).entrySet()) {
            Files.createDirectories(share.resolve(e.getKey()).getParent());
            Files.write(share.resolve(e.getKey()), e.getValue());
        }
        assertEquals(404, get("/packed").status(), "no plain file there");
        assertEquals(200, get("/packed.z/size.txt").status(), "its pieces are plain files");
    }

    @Test
    void bundledHostsAreUnchanged() throws IOException {
        in("GET / HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals(404, next(false).status(), "a source that lists no folders has no index pages");
        in("GET /index.txt HTTP/1.1\r\nHost: get.flip\r\n\r\n");
        assertEquals("x", text(next(false)));
    }

    @Test
    void longFoldersAreCut() {
        List<ContentSource.Entry> many = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            many.add(new ContentSource.Entry("f" + i, false, i));
        String page = FolderListing.html("/x/", many, 3);
        assertTrue(page.contains("f2") && !page.contains("f3"));
        assertTrue(page.contains("More entries are not shown."));
        assertEquals("1023", FolderListing.size(1023));
        assertEquals("1.0K", FolderListing.size(1024));
        assertEquals("1.5M", FolderListing.size(1536L * 1024));
    }
}
