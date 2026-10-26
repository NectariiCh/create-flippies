package com.nectariich.create_flippies.content.computer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The modem's bundled content read from the class path (docs/PC_SPEC.md 12.4.3, 12.4.5): from a
 * folder and from a jar, as the game and a dev run see it, plus the content this build bundles.
 */
class ClasspathContentTest {

    private static final String ROOT = "data/create_flippies/internet/";

    @TempDir
    Path dir;

    /** A plain file and a deflated 200 KB one on get.flip, laid out under {@code root}. */
    private static Map<String, byte[]> files(byte[] big) {
        var files = new java.util.LinkedHashMap<String, byte[]>();
        files.put(ROOT + "hosts.txt", "# hosts\n\nget.flip\nDEB.flip\n".getBytes(StandardCharsets.UTF_8));
        files.put(ROOT + "get.flip/index.txt", "image /big.fdi 200000 Big\n".getBytes(StandardCharsets.UTF_8));
        DeflatedChunks.store("big.fdi", big).forEach((path, bytes) -> files.put(ROOT + "get.flip/" + path, bytes));
        return files;
    }

    private static byte[] big() {
        byte[] b = new byte[200_000];
        new Random(7).nextBytes(b);
        return b;
    }

    private ClassLoader folder(Map<String, byte[]> files) throws IOException {
        Path root = dir.resolve("classes");
        for (var e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    private ClassLoader jar(Map<String, byte[]> files) throws IOException {
        Path jar = dir.resolve("content.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            Set<String> dirs = new java.util.TreeSet<>();
            for (String path : files.keySet())
                for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1))
                    dirs.add(path.substring(0, i + 1));
            for (String d : dirs) {
                out.putNextEntry(new JarEntry(d));
                out.closeEntry();
            }
            for (var e : files.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    /**
     * The jar of {@link #jar} as Forge 1.20.1 serves a mod's resources: a union file system over it
     * (securejarhandler's installed provider), a found path for anything that exists, folders too,
     * and a "union:" URL whose connection, like Forge's, gives no length and reads the whole
     * resource into memory per stream ({@code cpw.mods.cl.UnionURLStreamHandler}). Streams opened
     * through the URL are counted in {@code streams}.
     */
    private ClassLoader union(Map<String, byte[]> files, AtomicInteger streams) throws IOException {
        jar(files);
        var provider = (cpw.mods.niofs.union.UnionFileSystemProvider) FileSystemProvider.installedProviders().stream()
                .filter(p -> "union".equals(p.getScheme())).findFirst().orElseThrow();
        var fs = provider.newFileSystem((path, base) -> true, dir.resolve("content.jar"));
        var forge = new cpw.mods.cl.UnionURLStreamHandler().inputStreamFunction();
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {}

                    @Override
                    public InputStream getInputStream() throws IOException {
                        streams.incrementAndGet();
                        try {
                            return forge.apply(url);
                        } catch (java.io.UncheckedIOException e) {
                            throw e.getCause();
                        }
                    }
                };
            }
        };
        return new ClassLoader(null) {
            @Override
            protected URL findResource(String name) {
                Path p = fs.getPath(name);
                if (!Files.exists(p))
                    return null;
                try {
                    return new URL(null, p.toUri().toString(), handler);
                } catch (java.net.MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private static byte[] readAll(CatalogueFile file) throws IOException {
        byte[] out = new byte[(int) file.size()];
        for (int at = 0; at < out.length; )
            at += file.read(at, out, at, out.length - at);
        return out;
    }

    private static void servesTheCatalogue(ClassLoader loader) throws IOException {
        byte[] big = big();
        ClasspathContent content = new ClasspathContent(loader, ROOT);
        assertEquals(Set.of("get.flip", "deb.flip"), content.hosts(), "comments skipped, names lower-cased");
        Catalogue catalogue = new Catalogue(content);
        assertTrue(catalogue.hasHost("GET.flip"));

        CatalogueFile index = catalogue.open("get.flip", "index.txt");
        assertNotNull(index);
        assertEquals(26, index.size());
        assertEquals(List.of(new CatalogueIndex.Entry("image", "/big.fdi", 200_000, "Big")),
                CatalogueIndex.parse(new String(readAll(index), StandardCharsets.US_ASCII)));

        CatalogueFile file = catalogue.open("get.flip", "big.fdi");
        assertNotNull(file);
        assertTrue(file.deflated());
        assertEquals(big.length, file.size());
        assertArrayEquals(big, readAll(file));
        byte[] tail = new byte[100];
        assertEquals(100, file.read(150_000, tail, 0, 100), "a read from the middle (a Range request)");
        assertArrayEquals(java.util.Arrays.copyOfRange(big, 150_000, 150_100), tail);

        assertNull(catalogue.open("get.flip", "missing.txt"));
        assertNull(catalogue.open("get.flip", "big.fdi.z"), "the chunk folder is not a file");
        assertNull(catalogue.open("get.flip", "big.fdi.z/"), "nor with a slash");
        assertNull(catalogue.open("other.flip", "index.txt"), "not a host");
        assertNull(content.open("get.flip/missing.txt"));
        assertEquals(-1, content.size("get.flip"));
        try (InputStream in = content.open("get.flip/big.fdi.z/size.txt")) {
            assertEquals("200000", new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
    }

    @Test
    void servesFromAFolder() throws IOException {
        servesTheCatalogue(folder(files(big())));
    }

    @Test
    void servesFromAJar() throws IOException {
        servesTheCatalogue(jar(files(big())));
    }

    /** Under Forge's union file system: sizes without reading, files streamed, folders absent. */
    @Test
    void servesFromForgesUnionFileSystem() throws IOException {
        AtomicInteger streams = new AtomicInteger();
        ClassLoader loader = union(files(big()), streams);
        servesTheCatalogue(loader);
        ClasspathContent content = new ClasspathContent(loader, ROOT);
        streams.set(0);
        assertEquals(26, content.size("get.flip/index.txt"));
        assertEquals(-1, content.size("get.flip/big.fdi.z"), "a folder is absent");
        assertNull(content.open("get.flip/big.fdi.z"));
        assertEquals(0, streams.get(), "no resource read through, nor into memory, to size it");
    }

    @Test
    void noHostsFileMeansNoHosts() throws IOException {
        ClasspathContent content = new ClasspathContent(folder(Map.of(ROOT + "get.flip/index.txt", new byte[1])), ROOT);
        assertEquals(Set.of(), content.hosts());
        assertNull(new Catalogue(content).open("get.flip", "index.txt"));
        assertThrows(IllegalArgumentException.class, () -> new ClasspathContent(getClass().getClassLoader(), "/x/"));
        assertThrows(IllegalArgumentException.class, () -> new ClasspathContent(getClass().getClassLoader(), "x"));
    }

    /**
     * What this build bundles (build.gradle copies guest/debian/out/chunks and generates
     * index.txt): get.flip is served, and every image the index lists is there, the size the
     * index says, starting with an FDI1 header whose title is the index's.
     */
    @Test
    void theBundledCatalogueIsWhole() throws IOException {
        ClasspathContent content = ClasspathContent.bundled();
        assertTrue(content.hosts().contains("get.flip"));
        Catalogue catalogue = new Catalogue(content);
        CatalogueFile index = catalogue.open("get.flip", "index.txt");
        assertNotNull(index, "processResources generates get.flip/index.txt");
        for (CatalogueIndex.Entry entry : CatalogueIndex.parse(new String(readAll(index), StandardCharsets.UTF_8))) {
            CatalogueFile file = catalogue.open("get.flip", entry.path().substring(1));
            assertNotNull(file, entry.path());
            assertEquals(entry.size(), file.size(), entry.path());
            byte[] head = new byte[84];
            assertEquals(84, file.read(0, head, 0, 84));
            assertEquals("FDI1", new String(head, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(entry.title(), new String(head, 20, 64, StandardCharsets.UTF_8).replace("\0", "").trim());
            byte[] last = new byte[1];
            assertEquals(1, file.read(file.size() - 1, last, 0, 1), "the last chunk is there and inflates");
            file.close();
        }
    }
}
