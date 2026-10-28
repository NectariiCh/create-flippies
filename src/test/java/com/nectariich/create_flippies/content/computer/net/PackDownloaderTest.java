package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fetching a configured pack (spec 12.4.3) from a local HTTP server: checked, moved into place, never half there. */
class PackDownloaderTest {

    @TempDir
    Path dir;
    Path folder;
    HttpServer http;
    byte[] pack;
    final AtomicInteger requests = new AtomicInteger();
    final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        folder = Files.createDirectories(dir.resolve("packs"));
        pack = Files.readAllBytes(PackFiles.write(dir.resolve("built.flippack"), "os", List.of("get.flip"),
                Map.of("get.flip/index.txt", "image /a.fdi 1 A\n".getBytes(StandardCharsets.UTF_8))));
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/os.flippack", x -> {
            requests.incrementAndGet();
            x.sendResponseHeaders(200, pack.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(pack);
            }
        });
        http.createContext("/notapack", x -> {
            byte[] b = "hello".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(b);
            }
        });
        http.createContext("/missing", x -> {
            x.sendResponseHeaders(404, -1);
            x.close();
        });
        http.start();
    }

    @AfterEach
    void tearDown() {
        http.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + http.getAddress().getPort() + path;
    }

    private PackDownloader.Result fetch(String name, String sha, String path, long max) {
        PackDownloader.Source s = PackDownloader.Source.parse(name + " " + sha + " " + url(path), true);
        return PackDownloader.fetch(s, folder, true, max, () -> false, log::add);
    }

    private String sha() throws IOException {
        return PackDownloader.sha256(dir.resolve("built.flippack"));
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void fetchesChecksAndMovesIntoPlace() throws IOException {
        assertEquals(PackDownloader.Result.DOWNLOADED, fetch("debian-os.flippack", sha(), "/os.flippack", 1 << 20));
        assertEquals(List.of("debian-os.flippack"), files(), "no temporary file left");
        assertArrayEquals(pack, Files.readAllBytes(folder.resolve("debian-os.flippack")));
        assertEquals(PackDownloader.Result.PRESENT, fetch("debian-os.flippack", sha(), "/os.flippack", 1 << 20));
        assertEquals(1, requests.get(), "a pack already there with the right hash is not fetched again");
        PackRegistry r = new PackRegistry(List.of(folder), null, log::add);
        r.reload(null);
        assertEquals(1, r.current().packs().size(), "and loads");
    }

    @Test
    void aPackAlreadyThereWins() throws IOException {
        Files.writeString(folder.resolve("debian-os.flippack"), "dropped in by hand");
        assertEquals(PackDownloader.Result.KEPT, fetch("debian-os.flippack", sha(), "/os.flippack", 1 << 20));
        assertEquals(0, requests.get());
        assertEquals("dropped in by hand", Files.readString(folder.resolve("debian-os.flippack")));
        assertTrue(log.get(0).contains("kept"), log.toString());
    }

    @Test
    void failuresLeaveNothingBehind() throws IOException {
        String wrong = "0".repeat(64);
        assertEquals(PackDownloader.Result.FAILED, fetch("a.flippack", wrong, "/os.flippack", 1 << 20));
        assertTrue(log.get(log.size() - 1).contains("SHA-256"), log.toString());
        assertEquals(PackDownloader.Result.FAILED, fetch("b.flippack", sha(), "/os.flippack", 100));
        assertTrue(log.get(log.size() - 1).contains("limit"), log.toString());
        assertEquals(PackDownloader.Result.FAILED, fetch("c.flippack", sha(), "/missing", 1 << 20));
        assertTrue(log.get(log.size() - 1).contains("404"), log.toString());
        String helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertEquals(PackDownloader.Result.FAILED, fetch("d.flippack", helloSha, "/notapack", 1 << 20));
        assertTrue(log.get(log.size() - 1).contains("not a usable pack"), log.toString());
        assertEquals(List.of(), files());
        PackDownloader.Source s = PackDownloader.Source.parse("e.flippack " + sha() + " " + url("/os.flippack"), true);
        assertEquals(PackDownloader.Result.FAILED, PackDownloader.fetch(s, folder, true, 1 << 20, () -> true, log::add),
                "cancelled when the server stops");
        assertEquals(List.of(), files());
        PackDownloader.Source closed = PackDownloader.Source.parse("f.flippack " + sha() + " http://127.0.0.1:1/x", true);
        assertEquals(PackDownloader.Result.FAILED, PackDownloader.fetch(closed, folder, true, 1 << 20, () -> false, log::add),
                "nothing listening");
    }

    @Test
    void unfinishedDownloadsAreRemovedAtTheNextStart() throws IOException {
        // What a server killed mid-download leaves (its daemon thread never reached the clean-up)
        Files.writeString(folder.resolve("debian-os.flippack.part"), "half");
        Files.writeString(folder.resolve("debian-apps.flippack.4711.part"), "half, an older name");
        Files.writeString(folder.resolve("keep.flippack"), "a pack");
        Files.writeString(folder.resolve("notes.part"), "not ours");
        Files.createDirectories(folder.resolve("dir.flippack.part"));
        PackDownloader.removeStaleParts(folder, log::add);
        assertEquals(List.of("dir.flippack.part", "keep.flippack", "notes.part"), files());
        assertEquals(2, log.size(), log.toString());
        PackDownloader.removeStaleParts(dir.resolve("absent"), log::add);
        assertEquals(2, log.size(), "a missing folder is fine");

        // A retry writes over its own leftover, so one pack never has more than one
        Files.writeString(folder.resolve("debian-os.flippack.part"), "half of an older try, longer than the pack".repeat(100));
        assertEquals(PackDownloader.Result.DOWNLOADED, fetch("debian-os.flippack", sha(), "/os.flippack", 1 << 20));
        assertArrayEquals(pack, Files.readAllBytes(folder.resolve("debian-os.flippack")));
        assertFalse(Files.exists(folder.resolve("debian-os.flippack.part")));
    }

    @Test
    void configLinesAreChecked() {
        String sha = "a".repeat(64);
        PackDownloader.Source s = PackDownloader.Source.parse("  debian-os.flippack  " + sha.toUpperCase() + "\thttps://example.com/p ", false);
        assertEquals("debian-os.flippack", s.name());
        assertEquals(sha, s.sha256());
        for (String bad : List.of("debian-os.flippack " + sha, "../x.flippack " + sha + " https://e.com/x",
                "Debian.flippack " + sha + " https://e.com/x", "x.zip " + sha + " https://e.com/x",
                "x.flippack abc https://e.com/x", "x.flippack " + sha + " http://e.com/x",
                "x.flippack " + sha + " file:///etc/passwd", "x.flippack " + sha + " ftp://e.com/x"))
            assertThrows(IllegalArgumentException.class, () -> PackDownloader.Source.parse(bad, false), bad);
        assertFalse(PackDownloader.Source.parse("x.flippack " + sha + " http://e.com/x", true).url().getScheme().isEmpty(),
                "http when allowed");
    }
}
