package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.CatalogueFile;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.PackContent;
import com.nectariich.create_flippies.content.computer.net.PackSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The whole netinstall path of spec 12.4 and 12.5 as a player meets it, outside Minecraft: the OS
 * pack (guest/packs/out/debian-os.flippack) over the jar's built-in pages is the modem's catalogue,
 * as a server with that pack installed serves it, the jar's netinstall floppy installs the Debian
 * image from it onto a blank 1 GiB HDD of a 68040 PC with 128 MiB in the headless runner, and after
 * the restart the HDD boots through flipboot to the JWM desktop (spec 12.16).
 * <ul>
 * <li>the pack serves get.flip/debian.fdi byte for byte as guest/debian/out/debian.fdi;</li>
 * <li>the install: the catalogue's only entry, YES, "netinstall: done" with no problems; the HDD
 * is then byte for byte guest/debian/out/debian.img, when that is there;</li>
 * <li>the floppy out and Enter: the ROM boots the HDD, "login:" on COM1, the desktop on display
 * 0; Debian fetches get.flip/index.txt from the modem with wget, then powers off.</li>
 * </ul>
 *
 * Tagged "linux": {@code ./gradlew :1.20.1-forge:linuxTest}, which builds the jar first. Needs the
 * OS pack (guest/packs/out/debian-os.flippack, or the one FLIPPIES_OS_PACK or the flippies.osPack
 * system property names) and the mod jar (the one FLIPPIES_MOD_JAR or the flippies.modJar system
 * property names) built with guest/netinstall/out/netinstall.img, which it takes from the jar;
 * skipped without them. A pack built before the image fails the byte-for-byte check rather than
 * installing an old image. The HDD file, the COM1 transcript, screenshots and timings stay in
 * build/netinstall-debian.
 */
@Tag("linux")
class NetinstallDebianTest {

    private static final Path JAR = Path.of(System.getProperty("flippies.modJar",
            System.getenv().getOrDefault("FLIPPIES_MOD_JAR", "build/libs/create_flippies-0.1.0.jar")));
    private static final Path OS_PACK = Path.of(System.getProperty("flippies.osPack",
            System.getenv().getOrDefault("FLIPPIES_OS_PACK", "guest/packs/out/debian-os.flippack")));
    private static final Path FDI = Path.of("guest/debian/out/debian.fdi");
    private static final Path IMG = Path.of("guest/debian/out/debian.img");
    private static final Path OUT = Path.of("build/netinstall-debian");
    private static final String FLOPPY = "data/create_flippies/floppies/netinstall.img";

    private static final long EPOCH = 1_792_944_000L;
    private static final double INSTALL_LIMIT_SECONDS = 400, LOGIN_LIMIT_SECONDS = 180, DESKTOP_LIMIT_SECONDS = 330;
    private static final String PROMPT = "flip@flippies:~\\$ ";
    private static final Pattern TIMES = Pattern.compile(
            "Download and write: ([0-9.]+) s, ([0-9.]+) MB/s\\. Verify: ([0-9.]+) s, ([0-9.]+) MB/s\\.");

    private static void assumePack() {
        assumeTrue(Files.isRegularFile(OS_PACK), OS_PACK + " is not built (the guest pack build)");
    }

    private static URLClassLoader jarLoader() throws IOException {
        return new URLClassLoader(new URL[] {JAR.toUri().toURL()}, null);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void thePackServesTheDebianImageByteForByte() throws IOException {
        assumePack();
        assumeTrue(Files.isRegularFile(FDI), FDI + " is not built (guest/debian/build.sh)");
        PackSet packs = new PackSet(List.of(PackContent.open(OS_PACK)), ClasspathContent.bundled(), w -> fail(w));
        try {
            Catalogue catalogue = new Catalogue(packs);
            assertTrue(catalogue.hasHost("get.flip"));
            try (CatalogueFile index = catalogue.open("get.flip", "index.txt")) {
                assertNotNull(index);
                byte[] text = new byte[(int) index.size()];
                for (int at = 0; at < text.length; )
                    at += index.read(at, text, at, text.length - at);
                assertTrue(("\n" + new String(text, StandardCharsets.UTF_8)).contains("\nimage /debian.fdi " + Files.size(FDI) + " "),
                        new String(text, StandardCharsets.UTF_8));
            }
            assertEquals(null, catalogue.open("get.flip", "debian.fdi.z"), "the chunk folder is not served");
            long host = System.nanoTime();
            try (CatalogueFile file = catalogue.open("get.flip", "debian.fdi");
                 InputStream want = Files.newInputStream(FDI)) {
                assertNotNull(file);
                assertTrue(file.deflated());
                assertEquals(Files.size(FDI), file.size());
                byte[] got = new byte[65_536], expected = new byte[65_536];
                for (long at = 0; at < file.size(); ) {
                    int n = file.read(at, got, 0, got.length);
                    assertTrue(n > 0, "nothing read at " + at);
                    assertEquals(n, want.readNBytes(expected, 0, n));
                    if (!Arrays.equals(got, 0, n, expected, 0, n))
                        fail("debian.fdi differs from the pack's in the block at " + at);
                    at += n;
                }
                assertEquals(-1, want.read());
            }
            System.out.printf(Locale.ROOT, "the pack served debian.fdi (%d bytes) byte for byte in %.1f s host%n",
                    Files.size(FDI), (System.nanoTime() - host) / 1e9);
        } finally {
            packs.retire();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void installsDebianFromThePackAndBootsToTheDesktop() throws Exception {
        assumePack();
        assumeTrue(Files.isRegularFile(JAR), JAR + " is not built (./gradlew :1.20.1-forge:jar)");
        Path dir = LinuxDesktopTest.emptyFolder(OUT);
        // the floppy the game copies onto a new disk on first insert (FloppyPresets), from the jar
        Path floppy = dir.resolve("netinstall.img");
        try (URLClassLoader jar = jarLoader(); InputStream in = jar.getResourceAsStream(FLOPPY)) {
            assumeTrue(in != null, JAR + " holds no " + FLOPPY + " (guest/netinstall/build.sh, then ./gradlew jar)");
            Files.copy(in, floppy);
        }
        Path hdd = dir.resolve("hdd.img");

        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        String[] result = new String[1];
        Thread scenario = new Thread(() -> guest.play(g -> result[0] = scenario(g)), "netinstall-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // read-write with a sync, so the 258 MB written leave the heap as they reach hdd.img
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", hdd.toString(), "--hdd-size", "1G",
                "--sync-seconds", "10", "--floppy", floppy.toString(), "--pack", OS_PACK.toString(),
                "--epoch", Long.toString(EPOCH), "--max-seconds", "1200"};
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
        String timings = guest.timings() + (result[0] == null ? "" : result[0] + "\n")
                + String.format(Locale.ROOT, "test: %.2f s host for the run%n", (System.nanoTime() - hostStart) / 1e9)
                + runner.lines().filter(l -> l.contains(" emulated in ")).map(l -> "runner: " + l.strip() + "\n")
                .reduce("", String::concat);
        Files.writeString(dir.resolve("timings.txt"), timings);
        System.out.print(timings);

        assertTrue(guest.finished(), "the scenario did not finish:\n" + guest.tail());
        assertEquals(0, status, runner + "\n" + guest.tail());
        assertTrue(runner.contains("guest powered off, code 0x0000"), runner);
    }

    /** The player's part; returns the netinstall's own times. */
    private static String scenario(LinuxDesktopTest.Guest g) throws IOException {
        g.expect("Network: address 10\\.88\\.0\\.2, gateway 10\\.88\\.0\\.1, DNS 10\\.88\\.0\\.1", 30);
        g.mark("netinstall: network up");
        g.expect("Choose what to install", 30);
        g.expect("Debian GNU/Linux m68k desktop", 1);
        key(g, Ps2Key.ENTER);
        g.expect("This erases the HDD\\. Type YES to continue\\.", 30);
        for (Ps2Key k : new Ps2Key[] {Ps2Key.Y, Ps2Key.E, Ps2Key.S, Ps2Key.ENTER})
            key(g, k);
        g.mark("YES typed");
        String install = g.expect("netinstall: done", INSTALL_LIMIT_SECONDS);
        g.mark("netinstall: done");
        assertTrue(!install.contains("problem:"), install);
        assertTrue(install.contains("CRC errors in transit 0, fetched again 0"), install);
        Matcher t = TIMES.matcher(install);
        assertTrue(t.find(), install);
        String times = String.format(Locale.ROOT, "netinstall: download and write %s s (%s MB/s), verify %s s (%s MB/s)",
                t.group(1), t.group(2), t.group(3), t.group(4));

        if (Files.isRegularFile(IMG)) {
            long host = System.nanoTime();
            compareHdd(g.machine().config().disk(), IMG);
            g.mark(String.format(Locale.ROOT, "the HDD equals %s (compared in %.1f s host)", IMG,
                    (System.nanoTime() - host) / 1e9));
        }

        // the floppy out, then Enter: SysCon POWER 0x7777, and the ROM finds only the HDD
        g.machine().floppy().eject();
        key(g, Ps2Key.ENTER);
        g.expect("Create Flippies PC  68040", 10);
        g.reset();
        g.expect("flipboot: slot A active, generation 1", 10);
        g.expect("Starting the kernel at 0x00002000", 10);
        g.mark("kernel entered from the installed HDD");
        g.expect("login: ", g.left(LOGIN_LIMIT_SECONDS));
        g.mark("login: on COM1");
        g.waitForScreen(LinuxDesktopTest::isDesktop, g.left(DESKTOP_LIMIT_SECONDS), "desktop.png");
        g.mark("desktop complete");

        g.type("flip\r");
        g.expect(PROMPT, 30);
        g.type("wget -qO- http://get.flip/index.txt\r");
        String index = g.expect(PROMPT, 120);
        assertTrue(index.contains("image /debian.fdi "), index);
        g.command("sudo poweroff", null);
        return times;
    }

    /** One key press on the keyboard in PS/2 port 0, then a tick. */
    private static void key(LinuxDesktopTest.Guest g, Ps2Key k) {
        g.machine().keyboard(0).keyDown(k);
        g.machine().keyboard(0).keyUp(k);
        g.sleep(0.05);
    }

    /** The whole disk against the raw image file, which may be shorter (zeros past its end). */
    private static void compareHdd(DiskImage disk, Path image) throws IOException {
        int chunk = 65_536, sectors = chunk / DiskImage.SECTOR_SIZE;
        byte[] got = new byte[chunk], want = new byte[chunk];
        long bytes = disk.sectorCount() * DiskImage.SECTOR_SIZE;
        try (RandomAccessFile f = new RandomAccessFile(image.toFile(), "r")) {
            assertTrue(f.length() <= bytes, image + " is larger than the HDD");
            for (long at = 0; at < bytes; at += chunk) {
                disk.read(at / DiskImage.SECTOR_SIZE, sectors, got, 0);
                Arrays.fill(want, (byte) 0);
                if (at < f.length()) {
                    f.seek(at);
                    f.readFully(want, 0, (int) Math.min(chunk, f.length() - at));
                }
                if (!Arrays.equals(got, want))
                    fail("the HDD differs from " + image + " in the 64 KiB at " + at);
            }
        }
    }
}
