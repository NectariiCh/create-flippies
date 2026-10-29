package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's packs (spec 12.4.3): the world's folder over the server's, by file name inside a
 * folder, the first pack with a path wins, get.flip/index.txt joined from every pack, the
 * built-in pages under them all, and a reload while a file is being served.
 */
class PackRegistryTest {

    @TempDir
    Path dir;
    Path world, server;
    final List<String> warnings = new ArrayList<>();
    final MemoryContent base = new MemoryContent()
            .put("get.flip/index.txt", "# no OS\n").put("get.flip/index.html", "<p>built in</p>");

    @BeforeEach
    void setUp() throws IOException {
        world = Files.createDirectories(dir.resolve("world/flippies_packs"));
        server = Files.createDirectories(dir.resolve("config/create_flippies/packs"));
    }

    private PackRegistry registry() {
        PackRegistry r = new PackRegistry(List.of(world, server), base, warnings::add);
        r.reload(null);
        return r;
    }

    private static Map<String, byte[]> files(String... pathsAndTexts) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndTexts.length; i += 2)
            m.put(pathsAndTexts[i], pathsAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
        return m;
    }

    private static String text(Catalogue c, String host, String path) throws IOException {
        CatalogueFile f = c.open(host, path);
        if (f == null)
            return null;
        byte[] b = new byte[(int) f.size()];
        for (int at = 0; at < b.length; )
            at += f.read(at, b, at, b.length - at);
        f.close();
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void noPacksServesTheBuiltInPages() throws IOException {
        Catalogue c = new Catalogue(registry());
        assertEquals("# no OS\n", text(c, "get.flip", "index.txt"));
        assertEquals("<p>built in</p>", text(new Catalogue(registry()), "get.flip", "index.html"));
        CatalogueFile page = c.listing("get.flip", "");
        assertNotNull(page, "http://get.flip/ is the built-in index.html");
        assertEquals("get.flip/index.html", page.path());
        assertFalse(c.hasHost("deb.flip"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void lookupOrderWorldFirstThenByName() throws IOException {
        PackFiles.write(server.resolve("a.flippack"), "server-a", List.of("x.flip"), files("x.flip/f", "server a", "x.flip/s", "only server"));
        PackFiles.write(world.resolve("b.flippack"), "world-b", List.of("x.flip"), files("x.flip/f", "world b"));
        PackFiles.write(world.resolve("a.flippack"), "world-a", List.of("x.flip"), files("x.flip/f", "world a", "x.flip/g", "g"));
        PackRegistry r = registry();
        assertEquals(List.of("world-a", "world-b", "server-a"), r.current().packs().stream().map(p -> p.manifest().id()).toList());
        Catalogue c = new Catalogue(r);
        assertEquals("world a", text(c, "x.flip", "f"));
        assertEquals("only server", text(c, "x.flip", "s"), "a path no earlier pack has comes from a later one");
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("b.flippack") && warnings.get(0).contains("a.flippack") && warnings.get(0).contains("x.flip/f"),
                warnings.get(0));
    }

    /** A file is one path whether plain or deflated: a later pack's plain copy never shadows an earlier pack's chunks. */
    @Test
    void theFirstPackWinsAcrossStorageForms() throws IOException {
        byte[] big = new byte[150_000];
        new Random(5).nextBytes(big);
        PackFiles.write(world.resolve("a.flippack"), "a", List.of("get.flip"), DeflatedChunks.store("get.flip/os.fdi", big));
        PackFiles.write(world.resolve("b.flippack"), "b", List.of("get.flip"), files("get.flip/os.fdi", "plain and wrong"));
        Catalogue c = new Catalogue(registry());
        CatalogueFile f = c.open("get.flip", "os.fdi");
        assertNotNull(f);
        assertTrue(f.deflated());
        byte[] got = new byte[big.length];
        for (int at = 0; at < got.length; )
            at += f.read(at, got, at, got.length - at);
        assertArrayEquals(big, got);
        f.close();
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void theIndexIsEveryPacksLinesInLookupOrder() throws IOException {
        PackFiles.write(server.resolve("debian.flippack"), "debian-os", List.of("get.flip"),
                files("get.flip/index.txt", "image /debian.fdi 100 Debian"));
        PackFiles.write(world.resolve("other.flippack"), "other-os", List.of("get.flip"),
                files("get.flip/index.txt", "# other\nimage /other.fdi 200 Other\n"));
        PackFiles.write(world.resolve("apps.flippack"), "apps", List.of("deb.flip"), files("deb.flip/x", "x"));
        PackRegistry r = registry();
        String index = text(new Catalogue(r), "get.flip", "index.txt");
        assertEquals("# other\nimage /other.fdi 200 Other\nimage /debian.fdi 100 Debian\n", index);
        assertEquals(2, CatalogueIndex.parse(index).size());
        assertEquals(List.of(), warnings, "the index is joined, not a conflict");
        assertEquals(Set.of("get.flip", "deb.flip"), r.hosts());
    }

    @Test
    void anIndexWithNoEntriesLeavesTheBuiltInOne() throws IOException {
        PackFiles.write(world.resolve("a.flippack"), "a", List.of("get.flip"), files("get.flip/index.txt", "# nothing\n"));
        PackFiles.write(world.resolve("b.flippack"), "b", List.of("get.flip"), files("get.flip/index.txt", "image no-slash 1 Bad\n"));
        assertEquals("# no OS\n", text(new Catalogue(registry()), "get.flip", "index.txt"));
        assertEquals(1, warnings.size(), "the bad index is reported");
        assertTrue(warnings.get(0).contains("b.flippack"), warnings.get(0));
    }

    @Test
    void badPacksAreSkippedWithOneLineEach() throws IOException {
        Files.writeString(world.resolve("broken.flippack"), "not a zip");
        PackFiles.write(world.resolve("nojson.flippack"), null, files("get.flip/x", "x"));
        PackFiles.write(world.resolve("good.flippack"), "good", List.of("x.flip"), files("x.flip/f", "f"));
        PackFiles.write(server.resolve("same.flippack"), "good", List.of("x.flip"), files("x.flip/f", "other"));
        Files.writeString(server.resolve("good.flippack.1234.part"), "a download in progress");
        Files.writeString(server.resolve("notes.txt"), "not a pack");
        PackRegistry r = registry();
        assertEquals(1, r.current().packs().size());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("broken.flippack") && w.contains("zip")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("nojson.flippack") && w.contains("pack.json")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("same.flippack") && w.contains("already loaded")), warnings.toString());
    }

    /** A file being served when the packs reload is read to its end from the old pack, which closes after. */
    @Test
    void aReloadLetsFilesBeingServedFinish() throws IOException {
        byte[] old = new byte[200_000];
        new Random(1).nextBytes(old);
        Path file = PackFiles.write(world.resolve("os.flippack"), "os", List.of("get.flip"), DeflatedChunks.store("get.flip/os.fdi", old));
        PackRegistry r = registry();
        Catalogue c = new Catalogue(r);
        PackSet first = r.current();
        CatalogueFile reading = c.open("get.flip", "os.fdi");
        byte[] got = new byte[old.length];
        int at = reading.read(0, got, 0, 1000);
        assertEquals(1, first.holders());

        // the pack is replaced by a new version, as a download moves one into place
        Path next = PackFiles.write(dir.resolve("next.flippack"), "os", List.of("get.flip"), files("get.flip/os.fdi", "new"));
        Files.move(next, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        r.reload(null);
        assertFalse(first.closed(), "still being read");
        assertEquals("new", text(c, "get.flip", "os.fdi"), "new requests see the new pack");

        while (at < got.length)
            at += reading.read(at, got, at, got.length - at);
        assertArrayEquals(old, got, "the old one read to its end");
        reading.close();
        assertTrue(first.closed(), "closed once nothing reads it");
        assertEquals(0, r.current().holders());
        r.close();
        assertTrue(r.current().packs().isEmpty());
    }

    @Test
    void theShareComesBeforeThePacks() throws IOException {
        Path share = Files.createDirectories(dir.resolve("world/localfilesys"));
        Files.writeString(share.resolve("f.txt"), "shared");
        PackFiles.write(world.resolve("a.flippack"), "a", List.of("get.flip"), files("get.flip/index.txt", "image /a.fdi 1 A\n"));
        Catalogue c = new Catalogue(new SharedFolder(share), registry());
        assertEquals("shared", text(c, SharedFolder.HOST, "f.txt"));
        assertNull(text(c, "get.flip", "f.txt"));
        assertEquals("image /a.fdi 1 A\n", text(c, "get.flip", "index.txt"));
    }
}
