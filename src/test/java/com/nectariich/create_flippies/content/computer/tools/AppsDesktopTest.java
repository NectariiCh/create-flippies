package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The desktop programs a player gets from the content packs, as a server with both packs serves
 * them: a copy of the image of guest/debian (128 MiB) with the modem serving
 * guest/packs/out/debian-os.flippack and debian-apps.flippack and a world share
 * (localfilesys.com) holding a PNG, a JPEG, a short Ogg Theora clip, a text file and a subfolder.
 * In one session the user on COM1:
 * <ul>
 * <li>runs {@code apt-get update}, installs vim and python3, then the desktop set: the file
 * manager worker, the image viewer feh, the calculator xcalc (x11-apps), flippies-video, the browser
 * dillo and the editor nedit;</li>
 * <li>runs python3 and vim, fetches the share's index and a file with wget;</li>
 * <li>starts each program on display 0 (the share's pictures in feh, its clip in flippies-video,
 * its index in dillo), then Flippies Apps, Shared files and System info, and saves a PNG of each.
 * A program passes when it is still running and its window changed the screen; the PNGs are for
 * a person to judge.</li>
 * </ul>
 *
 * Tagged "linux" and slow: about two hours emulated, mostly dpkg at 25 MHz. Skipped while the
 * image or either pack is not built (guest/debian/build.sh, guest/packs/build.sh; the packs may
 * also be named by FLIPPIES_OS_PACK and FLIPPIES_APPS_PACK or flippies.osPack and
 * flippies.appsPack). The PNGs, the COM1 transcript and the timings stay in build/apps-desktop.
 */
@Tag("linux")
class AppsDesktopTest {

    private static final Path IMAGE = Path.of(System.getProperty("flippies.debianImage",
            System.getenv().getOrDefault("FLIPPIES_DEBIAN_IMAGE", "guest/debian/out/debian.img")));
    private static final Path OS_PACK = Path.of(System.getProperty("flippies.osPack",
            System.getenv().getOrDefault("FLIPPIES_OS_PACK", "guest/packs/out/debian-os.flippack")));
    private static final Path APPS_PACK = Path.of(System.getProperty("flippies.appsPack",
            System.getenv().getOrDefault("FLIPPIES_APPS_PACK", "guest/packs/out/debian-apps.flippack")));
    private static final Path OUT = Path.of("build/apps-desktop");

    private static final long EPOCH = 1_792_944_000L;
    private static final String PROMPT = "flip@flippies:~\\$ ";
    private static final String NOTES = "Notes from the world share.";
    /** A window's worth of changed pixels: more than the cursor, less than a small dialog. */
    private static final int WINDOW_PIXELS = 4000;

    @Test
    @Timeout(value = 5, unit = TimeUnit.HOURS)
    void installsAndRunsTheDesktopPrograms() throws Exception {
        assumeTrue(Files.isRegularFile(IMAGE), IMAGE + " is not built (guest/debian/build.sh)");
        assumeTrue(Files.isRegularFile(OS_PACK), OS_PACK + " is not built (guest/packs/build.sh)");
        assumeTrue(Files.isRegularFile(APPS_PACK), APPS_PACK + " is not built (guest/packs/build.sh)");
        Path dir = LinuxDesktopTest.emptyFolder(OUT);
        Path share = share(dir.resolve("localfilesys"));
        // a writable copy synced every 10 s: the installs write a few hundred MB
        Path hdd = AptArchiveTest.sparseCopy(IMAGE, dir.resolve("hdd.img"));

        LinuxDesktopTest.Guest guest = new LinuxDesktopTest.Guest(dir);
        Thread scenario = new Thread(() -> guest.play(AppsDesktopTest::scenario), "apps-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", hdd.toString(), "--sync-seconds", "10",
                "--pack", OS_PACK.toString(), "--pack", APPS_PACK.toString(), "--share", share.toString(),
                "--epoch", Long.toString(EPOCH), "--max-seconds", "15000"};
        int status;
        long hostStart = System.nanoTime();
        try {
            status = HeadlessPc.run(args, InputStream.nullInputStream(), guest,
                    new PrintStream(err, true, StandardCharsets.UTF_8), guest);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), guest.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("runner.txt"), err.toString(StandardCharsets.UTF_8));
            Files.writeString(dir.resolve("timings.txt"), guest.timings()
                    + String.format(Locale.ROOT, "test: %.2f s host for the run%n", (System.nanoTime() - hostStart) / 1e9));
            Files.deleteIfExists(hdd);
        }
        String runner = err.toString(StandardCharsets.UTF_8);
        System.out.print(Files.readString(dir.resolve("timings.txt")));

        assertTrue(guest.finished(), "the scenario did not finish:\n" + guest.tail());
        assertEquals(0, status, runner + "\n" + guest.tail());
    }

    /** The world share: two pictures, a clip, a text file, and a subfolder for each kind. */
    private static Path share(Path share) throws IOException {
        Files.createDirectories(share.resolve("pictures"));
        Files.createDirectories(share.resolve("videos"));
        BufferedImage picture = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = picture.createGraphics();
        for (int y = 0; y < 240; y++) {
            g.setColor(new Color(40, 90 + y / 3, 200 - y / 2));
            g.drawLine(0, y, 319, y);
        }
        g.setColor(new Color(240, 200, 40));
        g.fillOval(220, 30, 60, 60);
        g.setColor(new Color(30, 120, 40));
        g.fillRect(0, 170, 320, 70);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString("localfilesys", 20, 60);
        g.dispose();
        ImageIO.write(picture, "png", share.resolve("pictures/photo.png").toFile());
        ImageIO.write(picture, "jpg", share.resolve("pictures/photo.jpg").toFile());
        try (InputStream clip = AppsDesktopTest.class.getResourceAsStream("/computer/clip.ogv")) {
            Files.write(share.resolve("videos/clip.ogv"), clip.readAllBytes());
        }
        Files.writeString(share.resolve("notes.txt"), NOTES + "\nSecond line.\n");
        return share;
    }

    private static void scenario(LinuxDesktopTest.Guest g) throws IOException {
        g.expect("login: ", 180);
        g.mark("login: on COM1");
        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);
        run(g, "until DISPLAY=:0 xset q > /dev/null 2>&1; do sleep 5; done; echo desktop-up", 600, "[\r\n]desktop-up\r?\n");
        // the taskbar is drawn; then whatever the session starts has a minute to settle
        g.waitForScreen(AppsDesktopTest::hasTaskbar, 120, "00-taskbar.png");
        g.sleep(60);
        BufferedImage desktop = g.save(g.screen(), "00-desktop.png");
        assertTrue(hasTaskbar(desktop), "display 0 shows no desktop");

        // the archive: its key is in the image, apt reads the signed indexes with no warning
        run(g, "test -f /etc/apt/trusted.gpg.d/flippies-archive.gpg; echo key-status=$?", 30, "key-status=0");
        g.mark("apt-get update: start");
        String update = run(g, "time sudo apt-get -q update; echo apt-update-status=$?", 900,
                "Get:1 http://deb\\.flip unstable InRelease", "apt-update-status=0");
        assertFalse(Pattern.compile("(?m)^(W|E|Err):").matcher(update).find(), update);
        g.mark("apt-get install vim python3: start");
        install(g, "vim python3", 3600, "vim");
        g.mark("apt-get install the desktop set: start");
        install(g, "worker feh x11-apps flippies-video dillo nedit", 9000, "worker", "feh", "x11-apps", "flippies-video", "dillo", "nedit");
        run(g, "df -h /", 30);

        // the command line programs
        run(g, "python3 -c 'import sys; print(\"python\", sys.version_info[:2], 6 * 7)'", 300, "python \\(3, 1\\d\\) 42");
        // vim's Ex mode (-E -s): the full screen one would wait on the serial terminal's replies
        run(g, "vim -E -s -c 'call setline(1, \"written by vim\")' -c 'wq! /tmp/vim.txt'; cat /tmp/vim.txt", 300,
                "[\r\n]written by vim\r?\n");
        run(g, "wget -qO- http://localfilesys.com/ | grep -o 'href=\"[^\"]*\"'", 60,
                "href=\"notes\\.txt\"", "href=\"pictures/\"", "href=\"videos/\"");
        run(g, "wget -q http://localfilesys.com/notes.txt && head -1 notes.txt", 60, "[\r\n]" + Pattern.quote(NOTES) + "\r?\n");
        run(g, "wget -q -P share http://localfilesys.com/pictures/photo.png http://localfilesys.com/pictures/photo.jpg "
                + "http://localfilesys.com/videos/clip.ogv && ls share", 120, "clip\\.ogv", "photo\\.jpg", "photo\\.png");

        // the desktop programs, one at a time on display 0
        app(g, desktop, "01-xcalc.png", "xcalc", "xcalc", 180);
        // worker's example configuration, which its first start offers to copy in with one click
        run(g, "mkdir -p ~/.worker && cp /usr/share/worker/config-english ~/.worker/config", 30);
        app(g, desktop, "02-worker.png", "worker ~/share", "^worker", 300);
        app(g, desktop, "03-feh.png", "feh ~/share/photo.jpg", "feh", 180);
        app(g, desktop, "04-nedit.png", "nedit ~/notes.txt", "nedit", 240);
        app(g, desktop, "05-dillo.png", "dillo http://localfilesys.com/", "dillo", 300);
        // the clip: GStreamer takes a minute or two before the first frame, then runs at about 2 frames a second
        launch(g, "flippies-video ~/share/clip.ogv");
        waitForWindow(g, desktop, "06-video-window.png", 300);
        g.sleep(90);
        g.save(g.screen(), "07-video-playing.png");
        g.sleep(90);
        g.save(g.screen(), "08-video-end.png");
        stop(g, "flippies-video");
        app(g, desktop, "09-flippies-apps.png", "flippies-apps", "flippies-apps", 300);
        app(g, desktop, "10-shared-files.png", "flippies-sharedfiles", "flippies-sharedfiles", 300);
        app(g, desktop, "11-system-info.png", "flippies-sysinfo", "flippies-sysinfo", 300);
        g.type("sudo poweroff\r");
    }

    /** apt-get install of {@code packages}, timed; each of {@code installed} must be installed after. */
    private static void install(LinuxDesktopTest.Guest g, String packages, double limitSeconds, String... installed) {
        String out = run(g, "time sudo DEBIAN_FRONTEND=noninteractive apt-get -y -q install " + packages
                + "; echo apt-install-status=$?", limitSeconds, "apt-install-status=0");
        assertFalse(Pattern.compile("(?m)^(E|Err):").matcher(out).find(), out);
        for (String p : installed)
            run(g, "dpkg-query -W -f='${Status} ${Package}\\n' " + p, 60, "install ok installed " + Pattern.quote(p));
    }

    /** Starts {@code command} on display 0, saves its window as {@code name}, checks it still runs, closes it. */
    private static void app(LinuxDesktopTest.Guest g, BufferedImage desktop, String name, String command, String process,
            double limitSeconds) throws IOException {
        launch(g, command);
        waitForWindow(g, desktop, name, limitSeconds);
        run(g, "pgrep -f '" + process + "' > /dev/null; echo running=$?", 30, "running=0");
        stop(g, process);
    }

    private static void launch(LinuxDesktopTest.Guest g, String command) {
        String log = "/tmp/" + command.split(" ")[0] + ".log";
        run(g, "(DISPLAY=:0 " + command + " > " + log + " 2>&1 &)", 30);
        g.mark("started: " + command);
    }

    private static void stop(LinuxDesktopTest.Guest g, String process) {
        run(g, "pkill -f '" + process + "'; sleep 3", 30);
    }

    /**
     * Looks at display 0 every 2 s until a window's worth of it differs from the desktop and the
     * screen stays the same for one more look, then waits 5 s for the window to finish drawing
     * and saves it.
     */
    private static void waitForWindow(LinuxDesktopTest.Guest g, BufferedImage desktop, String name, double limitSeconds)
            throws IOException {
        double deadline = g.seconds() + limitSeconds;
        BufferedImage last = null;
        while (true) {
            BufferedImage s = g.screen();
            if (changed(desktop, s) >= WINDOW_PIXELS && last != null && changed(last, s) < 50) {
                g.sleep(5);
                g.save(g.screen(), name);
                g.mark("window shown: " + name);
                return;
            }
            if (g.seconds() > deadline) {
                g.save(s, "failed-" + name);
                throw new AssertionError(String.format(Locale.ROOT, "no window for %s within %.0f s (at %.2f s)",
                        name, limitSeconds, g.seconds()));
            }
            last = s;
            g.sleep(2);
        }
    }

    /** A 640x480 screen whose bottom row is JWM's grey taskbar over a desktop of another colour. */
    private static boolean hasTaskbar(BufferedImage s) {
        if (s.getWidth() != 640 || s.getHeight() != 480 || (s.getRGB(600, 200) & 0xFFFFFF) == 0xC0C0C0)
            return false;
        int grey = 0;
        for (int x = 200; x < 560; x++)
            if ((s.getRGB(x, 468) & 0xFFFFFF) == 0xC0C0C0)
                grey++;
        return grey > 320;
    }

    private static int changed(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
            return Integer.MAX_VALUE;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y))
                    n++;
        return n;
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
