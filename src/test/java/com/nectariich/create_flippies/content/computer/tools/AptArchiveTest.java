package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The deb.flip package archive (guest/apt/build.sh) as the installed Debian image uses it: a copy
 * of the image of guest/debian (128 MiB, the modem serving the archive) logs in on COM1,
 * trusts the archive's key (with the one documented command when the image predates the key),
 * runs {@code apt-get update}, installs vim and python3 with their dependencies from the archive,
 * and runs both.
 *
 * The modem serves the apps pack (guest/packs/out/debian-apps.flippack, or the one
 * FLIPPIES_APPS_PACK or the flippies.appsPack system property names) when it is built, as a server
 * with that pack installed does; otherwise guest/apt/out/archive straight from its folder. Skipped
 * while neither the pack nor the archive, or the image, is built.
 *
 * Tagged "linux" and slow: about 50 minutes emulated, most of it dpkg and Python compiling its
 * standard library at 25 MHz. The disk copy, the COM1 transcript and timings stay in
 * build/apt-archive.
 */
@Tag("linux")
class AptArchiveTest {

    private static final Path IMAGE = Path.of(System.getProperty("flippies.debianImage",
            System.getenv().getOrDefault("FLIPPIES_DEBIAN_IMAGE", "guest/debian/out/debian.img")));
    private static final Path APPS_PACK = Path.of(System.getProperty("flippies.appsPack",
            System.getenv().getOrDefault("FLIPPIES_APPS_PACK", "guest/packs/out/debian-apps.flippack")));
    private static final Path ARCHIVE = Path.of("guest/apt/out/archive");
    private static final Path OUT = Path.of("build/apt-archive");

    private static final long EPOCH = 1_792_944_000L;
    private static final String PROMPT = "flip@flippies:~\\$ ";
    /** The command guest/apt/build.sh documents for a disk installed before the archive's key existed. */
    private static final String ADD_KEY =
            "sudo wget -qO /etc/apt/trusted.gpg.d/flippies-archive.gpg http://deb.flip/flippies-archive.gpg";

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void installsVimAndPython3FromDebFlip() throws Exception {
        assumeTrue(Files.isRegularFile(IMAGE), IMAGE + " is not built (guest/debian/build.sh)");
        Path dir = LinuxDesktopTest.emptyFolder(OUT);
        String catalogue;
        String[] content;
        if (Files.isRegularFile(APPS_PACK)) {
            catalogue = APPS_PACK.toString();
            content = new String[] {"--pack", catalogue};
        } else {
            assumeTrue(Files.isRegularFile(ARCHIVE.resolve("dists/unstable/InRelease")),
                    ARCHIVE + " is not built (guest/apt/build.sh)");
            Path web = Files.createDirectories(dir.resolve("web"));
            Files.createSymbolicLink(web.resolve("deb.flip"), ARCHIVE.toAbsolutePath());
            catalogue = web.toString();
            content = new String[] {"--catalogue", catalogue};
        }
        System.out.println("the modem serves " + catalogue);
        // a writable copy synced every 10 s: the install writes a few hundred MB, which the
        // runner's read-only overlay would keep in the heap
        Path hdd = sparseCopy(IMAGE, dir.resolve("hdd.img"));

        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        Thread scenario = new Thread(() -> guest.play(AptArchiveTest::scenario), "apt-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", hdd.toString(), "--sync-seconds", "10",
                content[0], content[1], "--epoch", Long.toString(EPOCH), "--max-seconds", "6000"};
        int status;
        long hostStart = System.nanoTime();
        try {
            status = HeadlessPc.run(args, InputStream.nullInputStream(), guest,
                    new PrintStream(err, true, StandardCharsets.UTF_8), guest);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), guest.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("runner.txt"), err.toString(StandardCharsets.UTF_8));
        }
        String runner = err.toString(StandardCharsets.UTF_8);
        String timings = "catalogue: " + catalogue + "\n" + guest.timings()
                + String.format(Locale.ROOT, "test: %.2f s host for the run%n", (System.nanoTime() - hostStart) / 1e9);
        Files.writeString(dir.resolve("timings.txt"), timings);
        System.out.print(timings);

        assertTrue(guest.finished(), "the scenario did not finish:\n" + guest.tail());
        assertEquals(0, status, runner + "\n" + guest.tail());
    }

    /** Copies a disk image, leaving its all-zero 64 KiB blocks as holes. */
    static Path sparseCopy(Path from, Path to) throws IOException {
        byte[] block = new byte[65_536], zero = new byte[65_536];
        try (InputStream in = Files.newInputStream(from); RandomAccessFile out = new RandomAccessFile(to.toFile(), "rw")) {
            long at = 0;
            for (int n; (n = in.readNBytes(block, 0, block.length)) > 0; at += n) {
                if (!Arrays.equals(block, 0, n, zero, 0, n)) {
                    out.seek(at);
                    out.write(block, 0, n);
                }
            }
            out.setLength(at);
        }
        return to;
    }

    private static void scenario(LinuxDesktopTest.Guest g) {
        g.expect("login: ", 180);
        g.mark("login: on COM1");
        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);

        // the key: in a rebuilt image already, else the one documented command
        // the word is put together by the shell, so the echoed command line never holds "key-missing"
        String key = run(g, "echo key-$(test -f /etc/apt/trusted.gpg.d/flippies-archive.gpg && echo present || echo missing)", 30,
                "key-(present|missing)");
        if (key.contains("key-missing"))
            run(g, ADD_KEY + " && echo key-added", 60, "key-added");
        g.mark(key.contains("key-missing") ? "key added with the documented command" : "key already in the image");

        // apt reads the archive: the signed InRelease and the index, with no warning or error
        String update = run(g, "sudo apt-get -q update; echo apt-update-status=$?", 600,
                "Get:1 http://deb\\.flip unstable InRelease", "apt-update-status=0");
        assertFalse(Pattern.compile("(?m)^(W|E|Err):").matcher(update).find(), update);
        run(g, "apt-cache policy vim", 120, "Candidate: 2:9\\.2", "http://deb\\.flip unstable/main m68k Packages");

        // vim and python3 with everything they need that the image lacks (a newer image has python3 already)
        String install = run(g, "sudo DEBIAN_FRONTEND=noninteractive apt-get -y -q install vim python3; echo apt-install-status=$?",
                4500, "Setting up vim ", "Setting up python3 |python3 is already the newest version", "apt-install-status=0");
        assertFalse(Pattern.compile("(?m)^(E|Err):").matcher(install).find(), install);

        run(g, "vim -es '+call setline(1, \"written by vim\")' '+wq! /tmp/vim.txt'; cat /tmp/vim.txt", 120, "written by vim\r?\n");
        run(g, "python3 -c 'import sys; print(\"python\", sys.version_info[:2], 6 * 7)'", 300, "python \\(3, 1\\d\\) 42");
        g.type("sudo poweroff\r");
    }

    /** Types a command, waits up to {@code limitSeconds} for the prompt, and checks the output against each regex. */
    private static String run(LinuxDesktopTest.Guest g, String command, double limitSeconds, String... regexes) {
        g.type(command + "\r");
        String out = g.expect(PROMPT, limitSeconds);
        for (String regex : regexes)
            assertTrue(Pattern.compile(regex).matcher(out).find(), "\"" + command + "\" gave no " + regex + ":\n" + out);
        g.mark("done: " + command);
        return out;
    }
}
