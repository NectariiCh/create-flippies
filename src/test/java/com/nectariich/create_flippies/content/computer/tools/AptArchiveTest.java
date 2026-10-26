package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.net.ClasspathContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The deb.flip package archive (guest/apt/build.sh) as the installed Debian image uses it: the
 * image of guest/debian (read-only, 128 MiB, the modem serving the archive) logs in on COM1,
 * trusts the archive's key (with the one documented command when the image predates the key),
 * runs {@code apt-get update}, installs vim and python3 with their dependencies from the archive,
 * and runs both.
 *
 * The modem serves the mod jar's bundled internet (build/libs, or the jar FLIPPIES_MOD_JAR or the
 * flippies.modJar system property names) when the jar holds deb.flip, as the game does; otherwise
 * guest/apt/out/archive straight from its folder. Skipped while neither the archive nor the image
 * is built.
 *
 * Tagged "linux" and slow: about 50 minutes emulated, most of it dpkg and Python compiling its
 * standard library at 25 MHz. The COM1 transcript and timings stay in build/apt-archive.
 */
@Tag("linux")
class AptArchiveTest {

    private static final Path IMAGE = Path.of(System.getProperty("flippies.debianImage",
            System.getenv().getOrDefault("FLIPPIES_DEBIAN_IMAGE", "guest/debian/out/debian.img")));
    private static final Path JAR = Path.of(System.getProperty("flippies.modJar",
            System.getenv().getOrDefault("FLIPPIES_MOD_JAR", "build/libs/create_flippies-0.1.0.jar")));
    private static final Path ARCHIVE = Path.of("guest/apt/out/archive");
    private static final Path OUT = Path.of("build/apt-archive");
    private static final String IN_RELEASE = "deb.flip/dists/unstable/InRelease";

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
        if (jarHoldsArchive()) {
            catalogue = JAR.toString();
        } else {
            assumeTrue(Files.isRegularFile(ARCHIVE.resolve("dists/unstable/InRelease")),
                    ARCHIVE + " is not built (guest/apt/build.sh)");
            Path web = Files.createDirectories(dir.resolve("web"));
            Files.createSymbolicLink(web.resolve("deb.flip"), ARCHIVE.toAbsolutePath());
            catalogue = web.toString();
        }
        System.out.println("the modem serves " + catalogue);

        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        Thread scenario = new Thread(() -> guest.play(AptArchiveTest::scenario), "apt-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", IMAGE.toString(), "--readonly",
                "--catalogue", catalogue, "--epoch", Long.toString(EPOCH), "--max-seconds", "6000"};
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

    private static boolean jarHoldsArchive() throws IOException {
        if (!Files.isRegularFile(JAR))
            return false;
        try (URLClassLoader jar = new URLClassLoader(new URL[] {JAR.toUri().toURL()}, null)) {
            return jar.getResource(ClasspathContent.ROOT + IN_RELEASE) != null;
        }
    }

    private static void scenario(LinuxDesktopTest.Guest g) {
        g.expect("login: ", 180);
        g.mark("login: on COM1");
        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);

        // the key: in a rebuilt image already, else the one documented command
        String key = run(g, "test -f /etc/apt/trusted.gpg.d/flippies-archive.gpg && echo key-present || echo key-missing", 30);
        if (key.contains("key-missing"))
            run(g, ADD_KEY + " && echo key-added", 60, "key-added");
        g.mark(key.contains("key-missing") ? "key added with the documented command" : "key already in the image");

        // apt reads the archive: the signed InRelease and the index, with no warning or error
        String update = run(g, "sudo apt-get -q update; echo apt-update-status=$?", 600,
                "Get:1 http://deb\\.flip unstable InRelease", "apt-update-status=0");
        assertFalse(Pattern.compile("(?m)^(W|E|Err):").matcher(update).find(), update);
        run(g, "apt-cache policy vim | grep -A1 Candidate", 120, "Candidate: 2:9\\.2", "http://deb\\.flip unstable/main m68k Packages");

        // vim and python3 with everything they need that the image lacks
        String install = run(g, "sudo DEBIAN_FRONTEND=noninteractive apt-get -y -q install vim python3; echo apt-install-status=$?",
                4500, "Setting up vim ", "Setting up python3 ", "apt-install-status=0");
        assertFalse(Pattern.compile("(?m)^(E|Err):").matcher(install).find(), install);

        run(g, "vim -es '+call setline(1, \"written by vim\")' '+wq! /tmp/vim.txt'; cat /tmp/vim.txt", 120, "\nwritten by vim\r?\n");
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
