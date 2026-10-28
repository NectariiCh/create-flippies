package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One content pack read as the modem reads it (spec 12.4.3): pack.json, bad packs, and stored chunk folders. */
class PackContentTest {

    @TempDir
    Path dir;

    private static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void manifestIsRead() {
        PackManifest m = PackManifest.parse("""
                {"format": 1, "id": "debian-os", "version": "20261025T063552Z",
                 "title": "Debian GNU/Linux m68k desktop \\u00e9", "hosts": ["get.flip", "get.flip"], "extra": {"a": [1, null, true]}}
                """);
        assertEquals("debian-os", m.id());
        assertEquals("20261025T063552Z", m.version());
        assertEquals("Debian GNU/Linux m68k desktop é", m.title());
        assertEquals(List.of("get.flip"), m.hosts(), "duplicates dropped, unknown keys ignored");
    }

    @Test
    void badManifestsAreRefused() {
        String ok = "\"id\": \"a\", \"version\": \"1\", \"title\": \"A\", \"hosts\": [\"get.flip\"]";
        for (String bad : List.of(
                "", "[]", "{" + ok + "}", "{\"format\": 2, " + ok + "}", "{\"format\": \"1\", " + ok + "}",
                "{\"format\": 1, \"id\": \"Debian\", \"version\": \"1\", \"title\": \"A\", \"hosts\": [\"get.flip\"]}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"\", \"title\": \"A\", \"hosts\": [\"get.flip\"]}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"1\", \"hosts\": [\"get.flip\"]}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"1\", \"title\": \"A\", \"hosts\": []}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"1\", \"title\": \"A\", \"hosts\": [\"Get.Flip\"]}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"1\", \"title\": \"A\", \"hosts\": [\"../x\"]}",
                "{\"format\": 1, \"id\": \"a\", \"version\": \"1\", \"title\": \"A\", \"hosts\": [\"localfilesys.com\"]}",
                "{\"format\": 1, " + ok + "} x", "{\"format\": 1, " + ok))
            assertThrows(IllegalArgumentException.class, () -> PackManifest.parse(bad), bad);
    }

    @Test
    void badPacksAreRefusedWithAReason() throws IOException {
        Path notZip = Files.writeString(dir.resolve("a.flippack"), "not a zip");
        assertTrue(assertThrows(IOException.class, () -> PackContent.open(notZip)).getMessage().contains("zip"));
        Path noManifest = PackFiles.write(dir.resolve("b.flippack"), null, Map.of("get.flip/x", new byte[1]));
        assertTrue(assertThrows(IOException.class, () -> PackContent.open(noManifest)).getMessage().contains("pack.json"));
        Path badJson = PackFiles.write(dir.resolve("c.flippack"), "{\"format\": 1", Map.of());
        assertTrue(assertThrows(IOException.class, () -> PackContent.open(badJson)).getMessage().contains("JSON"));
        assertThrows(IOException.class, () -> PackContent.open(dir.resolve("missing.flippack")));
    }

    @Test
    void servesItsHostsOnly() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("get.flip/readme.txt", "hello\n".getBytes(StandardCharsets.UTF_8));
        files.put("other.flip/secret.txt", "no\n".getBytes(StandardCharsets.UTF_8));
        try (PackContent p = PackContent.open(PackFiles.write(dir.resolve("p.flippack"), "p", List.of("get.flip"), files))) {
            assertEquals(Set.of("get.flip"), p.hosts());
            assertEquals("p", p.manifest().id());
            assertEquals(6, p.size("get.flip/readme.txt"));
            try (InputStream in = p.open("get.flip/readme.txt")) {
                assertEquals("hello\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(-1, p.size("other.flip/secret.txt"), "a host the manifest does not name is not served");
            assertNull(p.open("other.flip/secret.txt"));
            assertEquals(-1, p.size("get.flip"), "nor a folder");
            assertNull(p.open("pack.json"));
            assertEquals(Set.of("get.flip/readme.txt"), p.paths());
        }
    }

    /** The deflated chunks of a large file, stored in the zip, served as the jar served them: whole, and from any offset. */
    @Test
    void storedChunkFoldersAreServed() throws IOException {
        byte[] big = random(300_000, 3);
        Map<String, byte[]> files = new LinkedHashMap<>(DeflatedChunks.store("get.flip/big.fdi", big));
        files.put("get.flip/index.txt", "image /big.fdi 300000 Big\n".getBytes(StandardCharsets.UTF_8));
        Path file = PackFiles.write(dir.resolve("os.flippack"), "os", List.of("get.flip"), files);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(ZipEntry.STORED, zip.getEntry("hosts/get.flip/big.fdi.z/0.z").getMethod(), "chunks are stored");
            assertEquals(ZipEntry.DEFLATED, zip.getEntry("hosts/get.flip/index.txt").getMethod());
        }
        try (PackContent p = PackContent.open(file)) {
            Catalogue c = new Catalogue(p);
            CatalogueFile f = c.open("get.flip", "big.fdi");
            assertNotNull(f);
            assertTrue(f.deflated());
            assertEquals(big.length, f.size());
            byte[] got = new byte[big.length];
            for (int at = 0; at < got.length; )
                at += f.read(at, got, at, got.length - at);
            assertArrayEquals(big, got);
            byte[] mid = new byte[50];
            assertEquals(50, f.read(200_000, mid, 0, 50), "a Range request's start");
            assertArrayEquals(Arrays.copyOfRange(big, 200_000, 200_050), mid);
            f.close();
            assertNull(c.open("get.flip", "big.fdi.z"), "the chunk folder is not a file");
        }
    }

    /** A plain file streams from its stored entry, skipping forward for a later block without reading what it skips. */
    @Test
    void plainFilesStreamFromTheZip() throws IOException {
        byte[] deb = random(200_000, 4);
        Path file = PackFiles.write(dir.resolve("apps.flippack"), "apps", List.of("deb.flip"),
                Map.of("deb.flip/pool/main/v/vim_9.2_m68k.deb", deb));
        try (PackContent p = PackContent.open(file)) {
            CatalogueFile f = new Catalogue(p).open("deb.flip", "pool/main/v/vim_9.2_m68k.deb");
            assertNotNull(f);
            assertEquals(deb.length, f.size());
            byte[] got = new byte[100];
            assertEquals(100, f.read(150_000, got, 0, 100));
            assertArrayEquals(Arrays.copyOfRange(deb, 150_000, 150_100), got);
            assertEquals(100, f.read(10, got, 0, 100), "back to an earlier block (a retransmission)");
            assertArrayEquals(Arrays.copyOfRange(deb, 10, 110), got);
            f.close();
        }
    }
}
