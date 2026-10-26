package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.device.Ps2Mouse;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Debian desktop image of guest/debian (spec 12.8) on the 68040 PC in the headless runner,
 * started from the boot ROM with no {@code --kernel}: the ROM loads flipboot from the MBR,
 * flipboot loads the kernel from the boot area, and the image comes up to the JWM desktop
 * (spec 12.11). In one session:
 * <ul>
 * <li>"login:" on COM1 within {@link #LOGIN_LIMIT_SECONDS} of power-on; the documented user logs
 * in there, pings the modem and fetches a file from it over HTTP;</li>
 * <li>the kernel postinst hook (run-parts over /etc/kernel/postinst.d, as the kernel package runs
 * it) finds the running kernel already in the active slot A and changes nothing; given a rebuilt
 * kernel (the same file with a byte appended) it installs that into slot B with flipboot-update
 * and makes it active;</li>
 * <li>the X desktop is checked from Java on display 0: the taskbar row against the desktop
 * background, the xterm, the cursor moving when the PS/2 mouse moves, and the start menu
 * opened by a mouse click on Start;</li>
 * <li>{@code reboot -f} comes back through the boot chain, now from slot B after the Esc
 * prompt, to the login and the desktop again;</li>
 * <li>JWM's exit (Start, "Exit to the text console") ends X and leaves a shell on tty1, with no
 * new X started; then {@code poweroff} ends the run with status 0.</li>
 * </ul>
 *
 * Tagged "linux": run with {@code ./gradlew linuxTest}. It needs the image of
 * guest/debian/build.sh, or the one the FLIPPIES_DEBIAN_IMAGE environment variable (or the
 * flippies.debianImage system property) names, and is skipped without it. The image is opened
 * read-only: what the guest writes stays in memory. No network is used: the modem is in-process
 * and serves a catalogue folder made here. Screenshots, the COM1 transcript and the timings stay
 * in build/linux-desktop.
 */
@Tag("linux")
class LinuxDesktopTest {

    private static final Path IMAGE = Path.of(System.getProperty("flippies.debianImage",
            System.getenv().getOrDefault("FLIPPIES_DEBIAN_IMAGE", "guest/debian/out/debian.img")));
    private static final Path OUT = Path.of("build/linux-desktop");
    private static final Path JWMRC = Path.of("guest/debian/rootfs/home/flip/.jwmrc");

    /** The RTC's fixed time, 2026-10-25T16:00:00Z. */
    private static final long EPOCH = 1_792_944_000L;
    /** Spec 12.11's "login: on COM1 within a set emulated time", from power-on (or the reset). */
    private static final double LOGIN_LIMIT_SECONDS = 180;
    /** Power-on (or the reset of the reboot) to the complete desktop. */
    private static final double DESKTOP_LIMIT_SECONDS = 330;
    /**
     * How long after X ends tty1 must still hold its shell and no Xorg run: a respawned autologin
     * would start X again within a few seconds.
     */
    private static final double NO_RESTART_SECONDS = 20;
    /** Any one command on COM1. */
    private static final double COMMAND_SECONDS = 120;

    private static final String PROMPT = "flip@flippies:~\\$ ";
    private static final String HELLO = "Hello from the modem over HTTP.";

    // Colours of ~/.jwmrc's theme and xterm's default background
    private static final int GREY = 0xC0C0C0, WHITE = 0xFFFFFF;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bootsFromTheDiskToTheDesktopAndBackAfterAReboot() throws Exception {
        assumeTrue(Files.isRegularFile(IMAGE), IMAGE + " is not built (guest/debian/build.sh)");
        Path dir = emptyFolder(OUT);
        Path web = dir.resolve("web");
        Files.createDirectories(web.resolve("get.flip"));
        Files.writeString(web.resolve("get.flip/hello.txt"), HELLO + "\n");

        Guest guest = new Guest(dir);
        Thread scenario = new Thread(() -> guest.play(this::scenario), "desktop-scenario");
        scenario.setDaemon(true);
        scenario.start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", IMAGE.toString(), "--readonly",
                "--catalogue", web.toString(), "--epoch", Long.toString(EPOCH), "--max-seconds", "1200"};
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
        String timings = guest.timings() + String.format(Locale.ROOT, "test: %.2f s host for the run%n",
                (System.nanoTime() - hostStart) / 1e9) + runner.lines().filter(l -> l.contains(" emulated in "))
                .map(l -> "runner: " + l.strip() + "\n").reduce("", String::concat);
        Files.writeString(dir.resolve("timings.txt"), timings);
        System.out.print(timings);

        assertTrue(guest.finished(), "the scenario did not finish:\n" + guest.tail());
        assertEquals(0, status, runner + "\n" + guest.tail());
        assertTrue(runner.contains("guest powered off, code 0x0000"), runner);
    }

    /** What happens in the guest, step by step; runs on its own thread in lockstep with the runner's ticks. */
    private void scenario(Guest g) throws IOException {
        // the boot chain, from the boot ROM: slot A, no prompt while slot B is empty
        g.expect("flipboot: slot A active, generation 1", 10);
        g.expect("Starting the kernel at 0x00002000", 10);
        g.mark("kernel entered (slot A)");
        g.expect("login: ", g.left(LOGIN_LIMIT_SECONDS));
        g.mark("login: on COM1");

        // the documented user (spec 12.8): flip, no password
        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);
        g.command("ping -c 3 10.88.0.1", "3 packets transmitted, 3 received, 0% packet loss");
        g.command("wget -qO- http://get.flip/hello.txt", Pattern.quote(HELLO) + "\r?\n");
        // the taskbar clock's calendar (~/.jwmrc) runs busybox's cal: the image has no other cal
        assertTrue(Files.readString(JWMRC).contains("<Clock format=\"%H:%M\">exec:xterm -T calendar -e sh -c \"busybox cal; read x\"</Clock>"),
                "the clock's command in " + JWMRC);
        g.command("busybox cal 10 2026", "[\r\n] +October 2026 *\r?\n");
        g.command("sudo flipboot-update --show", "generation 1, active slot A\r?\nslot A: offset 1048576, \\d+ bytes, "
                + "crc \\d+, command line \"root=/dev/fhda3 rw console=tty0 console=ttyS0,115200[^\"]*\"\r?\n"
                + "slot B: offset 0, 0 bytes.*\r?\nrunning slot A\r?\n");
        // the kernel package's postinst runs the hooks so; the installed kernel is already in slot A
        g.command("sudo run-parts --arg=$(uname -r) --arg=/boot/vmlinuz-$(uname -r) /etc/kernel/postinst.d",
                "flipboot-update: /boot/vmlinuz-6\\.18\\.58-flippies is already in slot A, the active slot; nothing to do");
        // a rebuilt kernel (one byte appended: stage 2 loads the ELF segments only) goes into slot B
        g.command("cp /boot/vmlinuz-$(uname -r) /tmp/vmlinuz-rebuilt && echo >> /tmp/vmlinuz-rebuilt", "");
        g.command("sudo run-parts --arg=$(uname -r) --arg=/tmp/vmlinuz-rebuilt /etc/kernel/postinst.d",
                "flipboot-update: /tmp/vmlinuz-rebuilt \\(\\d+ bytes\\) in slot B, now active");
        g.command("sudo flipboot-update --show", "generation 2, active slot B");

        // the desktop, checked from its pixels
        BufferedImage desktop = g.waitForScreen(LinuxDesktopTest::isDesktop, g.left(DESKTOP_LIMIT_SECONDS), "desktop.png");
        g.mark("desktop complete (slot A)");
        int background = desktop.getRGB(600, 200) & 0xFFFFFF;
        assertTrue(background != GREY && background != WHITE, String.format("desktop background %06x", background));

        // the cursor follows the mouse: only a cursor's worth of pixels change, above the taskbar
        g.sleep(2);
        BufferedImage before = g.screen();
        g.mouse().move(40, -30);
        g.sleep(1.5);
        BufferedImage after = g.save(g.screen(), "cursor-moved.png");
        int moved = changed(before, after, 0, 0, 640, 450);
        assertTrue(moved >= 20 && moved <= 2000, "the cursor's move changed " + moved + " pixels");

        // the start menu, opened by a click on Start in the bottom-left corner
        for (int i = 0; i < 8; i++) {
            g.mouse().move(-100, -100);
            g.sleep(0.2);
        }
        g.mouse().move(6, 4);
        g.sleep(0.5);
        BufferedImage closed = g.screen();
        g.mouse().buttons(1);
        g.sleep(0.2);
        g.mouse().buttons(0);
        // grey with its entries' black text drawn
        BufferedImage menu = g.waitForScreen(s -> share(s, GREY, 4, 270, 190, 440) > 0.6
                && share(s, 0x000000, 4, 270, 190, 440) > 0.02, 10, "start-menu.png");
        assertTrue(share(closed, GREY, 4, 270, 190, 440) < 0.2, "the menu's area was grey before the click");
        assertTrue(changed(closed, menu, 0, 250, 200, 456) > 5000, "the click on Start opened no menu");

        // reboot -f: back through the boot chain, now slot B after the Esc prompt, to the desktop again
        g.command("sudo reboot -f", null);
        g.expect("Create Flippies PC  68040", 60);
        g.reset();
        g.expect("flipboot: slot B active, generation 2", 5);
        g.expect("Booting slot B in 2 seconds; press Esc for slot A", 5);
        g.expect("Starting the kernel at 0x00002000", 10);
        g.mark("kernel entered (slot B, after reboot -f)");
        g.expect("login: ", g.left(LOGIN_LIMIT_SECONDS));
        g.mark("login: on COM1 after the reboot");
        g.waitForScreen(LinuxDesktopTest::isDesktop, g.left(DESKTOP_LIMIT_SECONDS), "desktop-after-reboot.png");
        g.mark("desktop complete after the reboot");

        g.sleep(1);
        g.type("flip\r");
        g.expect(PROMPT, 30);
        g.command("cat /proc/cmdline", "[\r\n]root=/dev/fhda3 rw console=tty0 console=ttyS0,115200 quiet flipboot\\.slot=B\r?\n");
        g.command("sudo flipboot-update --show", "\nrunning slot B\r?\n");

        // JWM's exit (the start menu's "Exit to the text console" runs the same): X ends, tty1
        // keeps its shell, and no new X starts
        g.command("pkill -x jwm; while pgrep -x Xorg > /dev/null; do sleep 1; done; echo X ended", "[\r\n]X ended\r?\n");
        g.mark("X ended");
        g.sleep(NO_RESTART_SECONDS);
        g.command("pgrep -c -x Xorg; ps -t tty1 -o comm=", "[\r\n]0\r?\nbash\r?\n");
        assertTrue(!isDesktop(g.save(g.screen(), "after-exit.png")), "the desktop is back after JWM's exit");
        g.command("sudo poweroff", null);
    }

    /**
     * The JWM desktop is up: a 640x480 graphics mode; the taskbar row grey and unlike the desktop
     * background beside the xterm; the xterm's white text area.
     */
    static boolean isDesktop(BufferedImage s) {
        if (s.getWidth() != 640 || s.getHeight() != 480)
            return false;
        int background = s.getRGB(600, 200) & 0xFFFFFF;
        return background != GREY && background != WHITE
                && share(s, GREY, 210, 462, 560, 474) > 0.9
                && share(s, background, 520, 100, 640, 300) > 0.9
                && share(s, WHITE, 100, 100, 400, 300) > 0.9;
    }

    /** The share of pixels of {@code rgb} in the rectangle [x0, x1) x [y0, y1). */
    private static double share(BufferedImage s, int rgb, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if ((s.getRGB(x, y) & 0xFFFFFF) == rgb)
                    n++;
        return n / (double) ((x1 - x0) * (y1 - y0));
    }

    /** Pixels that differ between two screens in the rectangle [x0, x1) x [y0, y1). */
    private static int changed(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if (a.getRGB(x, y) != b.getRGB(x, y))
                    n++;
        return n;
    }

    interface Scenario {
        void run(Guest g) throws Exception;
    }

    /**
     * The guest as the scenario sees it. The runner calls {@link #afterTick} once per tick; that
     * hands control to the scenario thread until it asks for the next tick, so the scenario reads
     * the machine and gives it input only between ticks, as the runner's own input does. COM1's
     * output arrives through {@link #write}.
     */
    static final class Guest extends OutputStream implements HeadlessPc.TickListener {

        private final Path dir;
        private final StringBuilder transcript = new StringBuilder();
        private final Semaphore toScenario = new Semaphore(0), toRunner = new Semaphore(0);
        private final List<String> marks = new ArrayList<>();
        private final long hostStart = System.nanoTime();
        private Machine machine;
        private long now;
        /** When the machine was last powered on or reset. */
        private long bootNanos;
        private int searchFrom;
        private volatile boolean done, finished;
        private volatile Throwable failure;

        Guest(Path dir) {
            this.dir = dir;
        }

        @Override
        public void write(int b) {
            transcript.append((char) (b & 0xFF));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = 0; i < len; i++)
                transcript.append((char) (b[off + i] & 0xFF));
        }

        @Override
        public void afterTick(Machine machine, long nowNanos) {
            if (done) {
                if (failure != null)
                    throw new AssertionError(failure.getMessage(), failure);
                return;
            }
            this.machine = machine;
            this.now = nowNanos;
            toScenario.release();
            toRunner.acquireUninterruptibly();
            if (failure != null)
                throw new AssertionError(failure.getMessage(), failure);
        }

        /** Runs the scenario on the calling thread, from the first tick. */
        void play(Scenario scenario) {
            toScenario.acquireUninterruptibly();
            try {
                scenario.run(this);
                finished = true;
            } catch (Throwable t) {
                failure = t;
            } finally {
                done = true;
                toRunner.release();
            }
        }

        /** Lets the machine run one tick. */
        private void tick() {
            toRunner.release();
            toScenario.acquireUninterruptibly();
        }

        boolean finished() {
            return finished;
        }

        double seconds() {
            return now / 1e9;
        }

        /** Marks now as the machine's reset, which the limits of {@link #left} count from. */
        void reset() {
            bootNanos = now;
            mark("reset");
        }

        /** What is left of {@code limit} seconds counted from power-on or the last {@link #reset}. */
        double left(double limitSeconds) {
            return Math.max(0, (bootNanos + limitSeconds * 1e9 - now) / 1e9);
        }

        void sleep(double seconds) {
            long until = now + (long) (seconds * 1e9);
            while (now < until)
                tick();
        }

        /** Types on COM1, one receive FIFO's worth per tick at most, so nothing overruns. */
        void type(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            Uart16550 com1 = machine.com(0);
            for (int typed = 0; typed < bytes.length; tick())
                if (com1.hostPending() == 0)
                    typed += com1.hostWrite(bytes, typed, Math.min(Uart16550.FIFO_DEPTH, bytes.length - typed));
        }

        /** Waits for {@code regex} on COM1 after what was matched last; returns the text up to it. */
        String expect(String regex, double limitSeconds) {
            Pattern p = Pattern.compile(regex);
            long deadline = now + (long) (limitSeconds * 1e9);
            while (true) {
                Matcher m = p.matcher(transcript);
                if (m.find(searchFrom)) {
                    String before = transcript.substring(searchFrom, m.end());
                    searchFrom = m.end();
                    return before;
                }
                if (now > deadline)
                    throw new AssertionError(String.format(Locale.ROOT, "no \"%s\" on COM1 within %.0f s (at %.2f s)%n%s",
                            regex, limitSeconds, seconds(), tail()));
                tick();
            }
        }

        /**
         * Types a command at the shell prompt and checks that its output holds {@code regex}
         * before the next prompt; with a null regex it only types it (a command that ends the
         * session).
         */
        void command(String command, String regex) {
            type(command + "\r");
            if (regex == null)
                return;
            String out = expect(PROMPT, COMMAND_SECONDS);
            assertTrue(Pattern.compile(regex).matcher(out).find(), "\"" + command + "\" gave no " + regex + ":\n" + out);
            marks.add(String.format(Locale.ROOT, "%8.2f s emulated %6.2f s host  done: %s", seconds(),
                    (System.nanoTime() - hostStart) / 1e9, command));
        }

        void mark(String what) {
            marks.add(String.format(Locale.ROOT, "%8.2f s emulated %6.2f s host  %s", seconds(),
                    (System.nanoTime() - hostStart) / 1e9, what));
        }

        Ps2Mouse mouse() {
            return machine.ps2().mouse(1);
        }

        Machine machine() {
            return machine;
        }

        /** Display 0 as 0xRRGGBB pixels; a 1x1 black image while it is in text mode. */
        BufferedImage screen() {
            VgaFrame frame = machine.vga(0).snapshot();
            if (frame.mode() == VgaDisplay.MODE_TEXT)
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            return Screenshot.image(frame);
        }

        BufferedImage save(BufferedImage image, String name) throws IOException {
            ImageIO.write(image, "png", dir.resolve(name).toFile());
            return image;
        }

        /** Looks at display 0 every half second until {@code test} holds; saves that screen as {@code name}. */
        BufferedImage waitForScreen(Predicate<BufferedImage> test, double limitSeconds, String name) throws IOException {
            long deadline = now + (long) (limitSeconds * 1e9);
            while (true) {
                BufferedImage s = screen();
                if (test.test(s))
                    return save(s, name);
                if (now > deadline) {
                    save(s, "failed-" + name);
                    throw new AssertionError(String.format(Locale.ROOT, "display 0 did not show %s within %.0f s (at %.2f s)",
                            name, limitSeconds, seconds()));
                }
                sleep(0.5);
            }
        }

        String transcript() {
            return transcript.toString();
        }

        String tail() {
            return transcript.substring(Math.max(0, transcript.length() - 3000));
        }

        String timings() {
            return String.join("\n", marks) + "\n";
        }
    }

    /** {@code dir}, emptied of what an earlier run left. */
    static Path emptyFolder(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
        return Files.createDirectories(dir);
    }
}
