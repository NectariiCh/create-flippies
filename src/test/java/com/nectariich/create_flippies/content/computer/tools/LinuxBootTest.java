package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Linux kernel of guest/linux (spec 12.6) on the 68040 PC in the headless runner, every
 * device in use in one session (spec 12.11): the kernel and its initramfs boot with the console
 * on COM1 and the framebuffer console, a shell answers on each, and on COM1 the session formats,
 * mounts, writes and remounts the hard disk, reads a raw partition and the floppy and writes the
 * floppy, gets an address from the modem by DHCP, pings it and downloads from it, reads the
 * clock, and lights the error light. Meanwhile the PS/2 keyboard types into the shell on tty1,
 * and a screenshot of display 0 is taken. Afterwards the disk images are checked from the host.
 *
 * Tagged "linux": the default test task leaves it out, {@code ./gradlew linuxTest} runs it. It
 * needs guest/linux/build.sh's vmlinux and guest/linux/mkinitramfs.sh's initramfs, and is
 * skipped without them. A second archive appended to the initramfs brings the repo's current
 * /init (so an initramfs built before a change to it still serves) and udhcpc's script. The
 * run's inputs, the COM1 transcript, the screenshot and the timings stay in build/linux-boot for
 * a look afterwards.
 */
@Tag("linux")
class LinuxBootTest {

    private static final Path KERNEL = Path.of("guest/linux/out/vmlinux");
    private static final Path INITRAMFS = Path.of("guest/linux/out/initramfs.cpio");
    private static final Path INIT = Path.of("guest/linux/initramfs/init");
    private static final Path OUT = Path.of("build/linux-boot");

    /** The RTC's fixed time, 2026-10-25T16:00:00Z. */
    private static final long EPOCH = 1_792_944_000L;
    private static final String CMDLINE = "console=tty0 console=ttyS0,115200";
    /** Spec 12.6: physical 0x1000 to _end. */
    private static final long KERNEL_TARGET = 6 << 20;

    /** Hard disk: 32 MiB; partition 1 Linux, 16 MiB, formatted by the guest; partition 2 raw data, 8 MiB. */
    private static final long HDD_BYTES = 32 << 20;
    private static final int P1_START = 2048, P1_SECTORS = 32768, P2_START = 34816, P2_SECTORS = 16384;

    // What is typed at the COM1 shell's prompts, in order. Each output is checked below; no
    // expected text appears in its own command, which the shell echoes.
    private static final String QUIET = "dmesg -n 1";
    private static final String UNAME = "uname -a";
    private static final String BOOT_LOG = "dmesg | grep -E 'Linux version|Memory:|Switched to'";
    private static final String DRIVERS = "dmesg | grep -E 'fb[0-2]:|ttyS[0-2] at|fhd[ab]:|eth0|rtc0|input:'";
    private static final String PARTITIONS = "cat /proc/partitions";
    private static final String READ_P2 = "dd if=/dev/fhda2 bs=64k count=4 iflag=direct 2>/dev/null | sha256sum";
    private static final String FORMAT = "mke2fs -L flippies /dev/fhda1 && mkdir -p /mnt";
    private static final String MOUNT = "mount -t ext2 /dev/fhda1 /mnt && echo \"note $((1000+234))\" > /mnt/note.txt";
    private static final String WRITE = "dd if=/dev/urandom of=/mnt/random.bin bs=1k count=256 2>/dev/null";
    private static final String UNMOUNT = "md5sum /mnt/random.bin > /mnt/random.md5 && umount /mnt";
    private static final String REMOUNT = "mount -o ro -t ext2 /dev/fhda1 /mnt && md5sum -c /mnt/random.md5";
    private static final String READ_BACK = "cat /mnt/note.txt; df -k /mnt; umount /mnt";
    private static final String READ_FLOPPY = "dd if=/dev/fhdb bs=64k count=1 iflag=direct 2>/dev/null | sha256sum";
    private static final String WRITE_FLOPPY = "echo floppy $((40+2)) | dd of=/dev/fhdb bs=512 seek=200 conv=sync oflag=direct";
    private static final String DHCP = "udhcpc -i eth0 -n -q -s /test/udhcpc.script";
    private static final String ADDRESS = "ip addr show eth0; ip route";
    private static final String PING = "ping -c 2 10.88.0.1";
    private static final String HELLO = "wget -q -O - http://get.flip/hello.txt";
    private static final String DOWNLOAD = "wget -q -O /tmp/test.bin http://get.flip/files/test.bin";
    private static final String CHECKSUM = "sha256sum /tmp/test.bin";
    private static final String CLOCK = "date -u; echo \"time $(date +%s)\"; cat /sys/class/rtc/rtc0/since_epoch";
    private static final String LIGHT_ON = "echo 1 > /sys/class/leds/flippies::fault/brightness";
    private static final String LIGHT_OFF = "cat /sys/class/leds/flippies::fault/brightness; echo 0 > /sys/class/leds/flippies::fault/brightness";
    private static final String TTY1 = "while [ ! -s /tmp/tty1.txt ]; do sleep 1; done; cat /tmp/tty1.txt";
    private static final String FRAMEBUFFER = "cat /sys/class/graphics/fb0/virtual_size /sys/class/graphics/fb0/modes";
    private static final String INTERRUPTS = "cat /proc/interrupts";
    private static final String POWER_OFF = "poweroff -f";
    private static final List<String> COMMANDS = List.of(QUIET, UNAME, BOOT_LOG, DRIVERS, PARTITIONS, READ_P2, FORMAT,
            MOUNT, WRITE, UNMOUNT, REMOUNT, READ_BACK, READ_FLOPPY, WRITE_FLOPPY, DHCP, ADDRESS, PING, HELLO, DOWNLOAD,
            CHECKSUM, CLOCK, LIGHT_ON, LIGHT_OFF, TTY1, FRAMEBUFFER, INTERRUPTS, POWER_OFF);

    /** The PS/2 keyboard, into the shell on tty1 (up a few seconds after power-on), then a screenshot of it. */
    private static final String KEYS = """
            # typed into the shell on tty1, the framebuffer console's first terminal
            8 type echo typed on $(tty): $((6*7)) > /tmp/tty1.txt\\n
            +1 type uname -sr; cat /tmp/tty1.txt\\n
            +2 screenshot fbcon.png 0
            """;

    /** udhcpc's script: the lease onto the interface, the default route and the name server. */
    private static final String UDHCPC_SCRIPT = """
            #!/bin/sh
            case "$1" in
            deconfig)
                ip -4 addr flush dev "$interface"
                ip link set dev "$interface" up
                ;;
            bound|renew)
                ip addr add "$ip/$subnet" dev "$interface"
                ip route add default via "$router" dev "$interface"
                echo "nameserver $dns" > /etc/resolv.conf
                ;;
            esac
            exit 0
            """;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void oneSessionUsesEveryDevice() throws IOException {
        assumeTrue(Files.isRegularFile(KERNEL), KERNEL + " is not built (guest/linux/build.sh)");
        assumeTrue(Files.isRegularFile(INITRAMFS), INITRAMFS + " is not built (guest/linux/mkinitramfs.sh)");
        Path dir = emptyFolder(OUT);

        byte[] p2 = random(1, 256 << 10);
        Path hdd = dir.resolve("hdd.img");
        try (RandomAccessFile f = new RandomAccessFile(hdd.toFile(), "rw")) {
            f.setLength(HDD_BYTES);
            f.write(mbr());
            f.seek(P2_START * 512L);
            f.write(p2);
        }
        byte[] floppyHead = random(2, 64 << 10);
        byte[] floppyImage = Arrays.copyOf(floppyHead, (int) PagedDiskImage.FLOPPY_CAPACITY);
        Path floppy = Files.write(dir.resolve("floppy.img"), floppyImage);
        byte[] download = random(3, 1 << 20);
        Path web = dir.resolve("web");
        Files.createDirectories(web.resolve("get.flip/files"));
        Files.writeString(web.resolve("get.flip/hello.txt"), "hello from the modem\n");
        Files.write(web.resolve("get.flip/files/test.bin"), download);
        Path initrd = initrd(dir);
        Path keys = Files.writeString(dir.resolve("keys.txt"), KEYS);

        Session session = new Session(COMMANDS);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--kernel", KERNEL.toString(), "--initrd", initrd.toString(),
                "--cmdline", CMDLINE, "--hdd", hdd.toString(), "--floppy", floppy.toString(), "--catalogue",
                web.toString(), "--epoch", Long.toString(EPOCH), "--input", keys.toString(), "--max-seconds", "600"};
        int status;
        long hostStart = System.nanoTime();
        try {
            status = HeadlessPc.run(args, InputStream.nullInputStream(), session,
                    new PrintStream(err, true, StandardCharsets.UTF_8), session);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), session.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("runner.txt"), err.toString(StandardCharsets.UTF_8));
        }
        double hostSeconds = (System.nanoTime() - hostStart) / 1e9;
        String runner = err.toString(StandardCharsets.UTF_8);
        String timings = session.timings(runner, hostSeconds);
        Files.writeString(dir.resolve("timings.txt"), timings);
        System.out.print(timings);

        assertEquals(0, status, runner);
        assertTrue(runner.contains("guest powered off, code 0x0000"), runner);
        Matcher end = Pattern.compile("bootinfo 0x([0-9a-f]+)").matcher(runner);
        assertTrue(end.find(), runner);
        assertTrue(Long.parseLong(end.group(1), 16) - 0x1000 < KERNEL_TARGET, "kernel above 6 MiB: _end 0x" + end.group(1));
        assertTrue(session.promptNanos(0) < 15_000_000_000L, timings);

        // the kernel, its log and the drivers
        session.assertOutput(UNAME, "Linux \\(none\\) 6\\.18\\.58-flippies #\\d+ .* m68k GNU/Linux");
        session.assertOutput(BOOT_LOG, "Memory: \\d+K/131072K available");
        session.assertOutput(BOOT_LOG, "Switched to clocksource flippies-timer");
        session.assertOutput(DRIVERS, "fb0: 640x480 at 8 bits, 512 KiB of video memory at 0xffe00000, irq 14");
        session.assertOutput(DRIVERS, "fb2: 640x480 at 8 bits");
        session.assertOutput(DRIVERS, "ttyS0 at MMIO 0xfff02000 \\(irq = 9, base_baud = 115200\\) is a 16550A");
        session.assertOutput(DRIVERS, "ttyS2 at MMIO 0xfff09000 \\(irq = 16");
        session.assertOutput(DRIVERS, "fhda: hard disk, 65536 sectors");
        session.assertOutput(DRIVERS, "fhdb: floppy drive, 2880 sectors");
        session.assertOutput(DRIVERS, "eth0: registers at 0xfff0b000, irq 18, address 02:46:4c:50:43:01");
        session.assertOutput(DRIVERS, "registered as rtc0");
        session.assertOutput(DRIVERS, "AT Raw Set 2 keyboard as /devices/platform/flippies-ps2\\.0");
        session.assertOutput(DRIVERS, "PS/2 Generic Mouse as /devices/platform/flippies-ps2\\.1");

        // the hard disk: partitions, a raw read, then a file system made, written and read back
        session.assertOutput(PARTITIONS, "\\s16384 fhda1\\s");
        session.assertOutput(PARTITIONS, "\\s8192 fhda2\\s");
        session.assertOutput(PARTITIONS, "\\s1440 fhdb\\s");
        session.assertOutput(READ_P2, Pattern.quote(sha256(p2) + "  -"));
        session.assertOutput(FORMAT, "Filesystem label=flippies");
        session.assertOutput(REMOUNT, "/mnt/random\\.bin: OK");
        session.assertOutput(READ_BACK, "\nnote 1234\r?\n");

        // the floppy, read and written
        session.assertOutput(READ_FLOPPY, Pattern.quote(sha256(floppyHead) + "  -"));
        session.assertOutput(WRITE_FLOPPY, "1\\+0 records out");

        // the network: DHCP, ping and HTTP from the modem
        session.assertOutput(DHCP, "lease of 10\\.88\\.0\\.2 obtained from 10\\.88\\.0\\.1, lease time 86400");
        session.assertOutput(ADDRESS, "inet 10\\.88\\.0\\.2/24");
        session.assertOutput(ADDRESS, "default via 10\\.88\\.0\\.1 dev eth0");
        session.assertOutput(PING, "2 packets transmitted, 2 packets received, 0% packet loss");
        session.assertOutput(HELLO, "\nhello from the modem\r?\n");
        session.assertOutput(CHECKSUM, Pattern.quote(sha256(download) + "  /tmp/test.bin"));

        // the clock: the system time comes from the RTC (fixed by --epoch) and runs on from there
        session.assertOutput(CLOCK, "\n" + EPOCH + "\r?\n");
        Matcher time = Pattern.compile("\ntime (\\d+)").matcher(session.output(CLOCK));
        assertTrue(time.find(), session.output(CLOCK));
        long seconds = Long.parseLong(time.group(1));
        assertTrue(seconds >= EPOCH && seconds <= EPOCH + session.promptNanos(COMMANDS.indexOf(CLOCK) + 1) / 1_000_000_000L,
                "system time " + seconds);

        // the error light, seen from the host
        assertTrue(session.ledAfter(LIGHT_ON), "the error light did not come on");
        session.assertOutput(LIGHT_OFF, "\n1\r?\n");
        assertTrue(!session.ledAfter(LIGHT_OFF), "the error light stayed on");

        // the keyboard typed into the shell on the framebuffer console
        session.assertOutput(TTY1, "\ntyped on /dev/tty1: 42\r?\n");
        session.assertOutput(FRAMEBUFFER, "\n640,819\r?\nU:640x480p-0\r?\n");
        for (String device : List.of("flippies-ps2.0", "flippies-hdd", "flippies-fdc", "eth0", "ttyS0", "timer"))
            session.assertOutput(INTERRUPTS, "\\s[1-9]\\d* flippies\\s+" + Pattern.quote(device) + "\r?\n");
        assertTrue(runner.contains("display 0 saved to " + dir.toAbsolutePath().resolve("fbcon.png")), runner);
        BufferedImage shot = ImageIO.read(dir.resolve("fbcon.png").toFile());
        assertEquals(640, shot.getWidth());
        assertEquals(480, shot.getHeight());
        int lit = 0;
        for (int y = 0; y < 480; y++)
            for (int x = 0; x < 640; x++)
                if ((shot.getRGB(x, y) & 0xFFFFFF) != 0)
                    lit++;
        assertTrue(lit > 5000, "only " + lit + " pixels of text on the framebuffer console");

        // the disk images as the guest left them
        byte[] disk = Files.readAllBytes(hdd);
        assertArrayEquals(mbr(), Arrays.copyOf(disk, 512));
        assertArrayEquals(p2, Arrays.copyOfRange(disk, P2_START * 512, P2_START * 512 + p2.length));
        byte[] p1 = Arrays.copyOfRange(disk, P1_START * 512, (P1_START + P1_SECTORS) * 512);
        assertTrue(indexOf(p1, "note 1234\n".getBytes(StandardCharsets.US_ASCII)) >= 0, "the note is not on partition 1");
        assertTrue(indexOf(p1, "flippies".getBytes(StandardCharsets.US_ASCII)) >= 0, "no file system label on partition 1");
        byte[] fd = Files.readAllBytes(floppy);
        assertArrayEquals(floppyHead, Arrays.copyOf(fd, floppyHead.length));
        byte[] sector = Arrays.copyOf("floppy 42\n".getBytes(StandardCharsets.US_ASCII), 512);
        assertArrayEquals(sector, Arrays.copyOfRange(fd, 200 * 512, 201 * 512));
    }

    // The hot-plug session's commands. Both PS/2 ports are empty while the kernel probes them
    // (atkbd and psmouse try each empty port twice, done about 10 s after power-on); the keyboard
    // and the mouse are plugged in after that, while PLUGGED is typed.
    private static final String UNBOUND = "sleep 10; ls /sys/bus/serio/devices; grep -c flippies-ps2 /proc/bus/input/devices";
    private static final String PLUGGED = "for i in 1 2 3 4 5 6 7 8 9 10; do [ $(grep -c Sysfs=.*flippies-ps2 "
            + "/proc/bus/input/devices) -ge 2 ] && break; sleep 1; done; grep -E '^[NS]: ' /proc/bus/input/devices";
    private static final String PLUGGED_TTY1 = "while [ ! -s /tmp/tty1.txt ]; do sleep 1; done; cat /tmp/tty1.txt";
    /** Three evdev events (REL_X, REL_Y, SYN_REPORT) of 16 bytes each; the mouse moves while it waits. */
    private static final String MOUSE_EVENTS = "e=$(ls -d /sys/devices/platform/flippies-ps2.1/serio1/input/input*/event*); "
            + "head -c 48 /dev/input/${e##*/} | wc -c";
    private static final List<String> HOT_PLUG = List.of(QUIET, UNBOUND, PLUGGED, PLUGGED_TTY1, MOUSE_EVENTS, POWER_OFF);
    private static final String HOT_PLUG_KEYS = """
            # typed into the shell on tty1 once the keyboard is bound
            22 type echo plugged in on $(tty): $((6*7)) > /tmp/tty1.txt\\n
            """;

    /**
     * PS/2 cords connected after power-on (spec 11.1.4): the kernel probes two empty ports, then a
     * keyboard and a mouse are plugged in, atkbd and psmouse bind, the keyboard types into the
     * shell on tty1 and the mouse's movement reaches its evdev device.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void ps2DevicesPluggedInAfterBootBind() throws IOException {
        assumeTrue(Files.isRegularFile(KERNEL), KERNEL + " is not built (guest/linux/build.sh)");
        assumeTrue(Files.isRegularFile(INITRAMFS), INITRAMFS + " is not built (guest/linux/mkinitramfs.sh)");
        Path dir = emptyFolder(Path.of("build/linux-hotplug"));
        Path initrd = initrd(dir);
        Path keys = Files.writeString(dir.resolve("keys.txt"), HOT_PLUG_KEYS);

        Session session = new Session(HOT_PLUG);
        boolean[] plugged = {false, false};
        HeadlessPc.TickListener listener = (machine, nowNanos) -> {
            if (!plugged[0]) {
                // the runner plugs both in before power-on; the first tick is long before the probe
                machine.ps2().setDevice(0, Ps2DeviceKind.NONE);
                machine.ps2().setDevice(1, Ps2DeviceKind.NONE);
                plugged[0] = true;
            }
            session.afterTick(machine, nowNanos);
            if (!plugged[1] && session.prompts() > HOT_PLUG.indexOf(PLUGGED)) {
                machine.ps2().setDevice(0, Ps2DeviceKind.KEYBOARD);
                machine.ps2().setDevice(1, Ps2DeviceKind.MOUSE);
                plugged[1] = true;
            }
            if (session.prompts() == HOT_PLUG.indexOf(MOUSE_EVENTS) + 1)
                machine.ps2().mouse(1).move(3, 3);
        };
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = {"--cpu", "68040", "--ram", "128M", "--kernel", KERNEL.toString(), "--initrd", initrd.toString(),
                "--cmdline", CMDLINE, "--epoch", Long.toString(EPOCH), "--input", keys.toString(), "--max-seconds", "300"};
        int status;
        try {
            status = HeadlessPc.run(args, InputStream.nullInputStream(), session,
                    new PrintStream(err, true, StandardCharsets.UTF_8), listener);
        } finally {
            Files.writeString(dir.resolve("com1.txt"), session.transcript(), StandardCharsets.ISO_8859_1);
            Files.writeString(dir.resolve("runner.txt"), err.toString(StandardCharsets.UTF_8));
        }
        String runner = err.toString(StandardCharsets.UTF_8);
        assertEquals(0, status, runner);
        assertTrue(runner.contains("guest powered off, code 0x0000"), runner);

        // the ports registered, no driver bound to either
        session.assertOutput(UNBOUND, "\nserio0\\s+serio1\\s");
        session.assertOutput(UNBOUND, "\n0\r?\n");
        // plugged in: atkbd on port 0, psmouse on port 1
        session.assertOutput(PLUGGED, "N: Name=\"AT Raw Set 2 keyboard\"\r?\nS: Sysfs=/devices/platform/flippies-ps2\\.0/serio0/input/");
        session.assertOutput(PLUGGED, "N: Name=\"PS/2 Generic Mouse\"\r?\nS: Sysfs=/devices/platform/flippies-ps2\\.1/serio1/input/");
        session.assertOutput(PLUGGED_TTY1, "\nplugged in on /dev/tty1: 42\r?\n");
        session.assertOutput(MOUSE_EVENTS, "\n48\r?\n");
    }

    /**
     * COM1 typed at as a person would: waits for the shell's prompt, types the next command and
     * keeps what came back until the next prompt, with the emulated and host time of each prompt
     * and the error light's state then. At most one receive FIFO's worth (16 bytes) goes to the
     * UART per tick, so a guest that keeps interrupts off for a while loses no input.
     */
    private static final class Session extends OutputStream implements HeadlessPc.TickListener {

        private static final String PROMPT = "/ # ";
        /** Emulated time a command may take before the session gives up. */
        private static final long STEP_NANOS = 120_000_000_000L;

        private final List<String> commands;
        private final StringBuilder transcript = new StringBuilder();
        private final List<String> outputs = new ArrayList<>();
        private final List<Long> promptNanos = new ArrayList<>(), promptHostNanos = new ArrayList<>();
        private final List<Boolean> ledAtPrompt = new ArrayList<>();
        private final long hostStart = System.nanoTime();
        private int searchFrom;
        private byte[] typing = new byte[0];
        private int typed;
        private long waitingSince;

        Session(List<String> commands) {
            this.commands = commands;
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
            Uart16550 com1 = machine.com(0);
            if (typed < typing.length) {
                if (com1.hostPending() == 0)
                    typed += com1.hostWrite(typing, typed, Math.min(Uart16550.FIFO_DEPTH, typing.length - typed));
                return;
            }
            int n = promptNanos.size();
            if (n > commands.size() || n > 0 && commands.get(n - 1).equals(POWER_OFF))
                return;
            int at = transcript.indexOf(PROMPT, searchFrom);
            if (at < 0) {
                if (nowNanos - waitingSince > STEP_NANOS)
                    throw new AssertionError(String.format(Locale.ROOT, "no prompt %s %.0f s after %.2f s emulated%n%s",
                            n == 0 ? "after boot" : "after \"" + commands.get(n - 1) + "\"", STEP_NANOS / 1e9,
                            waitingSince / 1e9, tail()));
                return;
            }
            if (n > 0)
                outputs.add(transcript.substring(searchFrom, at));
            promptNanos.add(nowNanos);
            promptHostNanos.add(System.nanoTime() - hostStart);
            ledAtPrompt.add(machine.guestErrorLed());
            searchFrom = at + PROMPT.length();
            if (n < commands.size()) {
                typing = (commands.get(n) + "\n").getBytes(StandardCharsets.ISO_8859_1);
                typed = 0;
                waitingSince = nowNanos;
            }
        }

        String transcript() {
            return transcript.toString();
        }

        private String tail() {
            return transcript.substring(Math.max(0, transcript.length() - 3000));
        }

        /** What the shell echoed and printed for {@code command}, up to the next prompt. */
        String output(String command) {
            int i = commands.indexOf(command);
            assertTrue(i >= 0 && i < outputs.size(), "no output for \"" + command + "\"\n" + tail());
            return outputs.get(i);
        }

        void assertOutput(String command, String regex) {
            String out = output(command);
            assertTrue(Pattern.compile(regex).matcher(out).find(), "\"" + command + "\" gave no " + regex + ":\n" + out);
        }

        /** How many prompts have come; at n, command n - 1 is being typed or running. */
        int prompts() {
            return promptNanos.size();
        }

        /** Emulated time of prompt {@code n}: 0 is the first, n + 1 the one after command n. */
        long promptNanos(int n) {
            return promptNanos.get(n);
        }

        boolean ledAfter(String command) {
            return ledAtPrompt.get(commands.indexOf(command) + 1);
        }

        /** The kernel's size, the boot time to the first prompt, and each command's time from prompt to prompt, typing included. */
        String timings(String runner, double hostSeconds) {
            StringBuilder s = new StringBuilder();
            Matcher end = Pattern.compile("kernel entry 0x([0-9a-f]+), bootinfo 0x([0-9a-f]+)").matcher(runner);
            if (end.find()) {
                long loaded = Long.parseLong(end.group(2), 16) - 0x1000;
                s.append(String.format(Locale.ROOT, "kernel: entry 0x%s, _end 0x%s, %d bytes (%.2f MiB) loaded from 0x1000%n",
                        end.group(1), end.group(2), loaded, loaded / 1048576.0));
            }
            if (!promptNanos.isEmpty())
                s.append(String.format(Locale.ROOT, "boot to the COM1 shell prompt: %.2f s emulated, %.2f s host%n",
                        promptNanos.get(0) / 1e9, promptHostNanos.get(0) / 1e9));
            for (int i = 0; i + 1 < promptNanos.size(); i++)
                s.append(String.format(Locale.ROOT, "%8.2f s emulated %6.2f s host  %s%n",
                        (promptNanos.get(i + 1) - promptNanos.get(i)) / 1e9,
                        (promptHostNanos.get(i + 1) - promptHostNanos.get(i)) / 1e9, commands.get(i)));
            for (String line : runner.split("\n"))
                if (line.contains(" emulated in "))
                    s.append("runner: ").append(line.strip()).append('\n');
            s.append(String.format(Locale.ROOT, "test: %.2f s host for the run%n", hostSeconds));
            return s.toString();
        }
    }

    /**
     * The release's initramfs followed by a second archive: the kernel unpacks both, the later
     * file winning, so the repo's current /init and udhcpc's script are what the guest runs.
     */
    private static Path initrd(Path dir) throws IOException {
        Path initrd = dir.resolve("initrd.cpio");
        try (OutputStream o = Files.newOutputStream(initrd)) {
            Files.copy(INITRAMFS, o);
            o.write(cpio(List.of(new CpioEntry("init", Files.readAllBytes(INIT)), new CpioEntry("etc", null),
                    new CpioEntry("test", null),
                    new CpioEntry("test/udhcpc.script", UDHCPC_SCRIPT.getBytes(StandardCharsets.US_ASCII)))));
        }
        return initrd;
    }

    /** Partition 1 (type 0x83, Linux) and partition 2 (0xDA, raw data), by LBA; no CHS. */
    private static byte[] mbr() {
        byte[] mbr = new byte[512];
        ByteBuffer b = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN);
        mbr[446 + 4] = (byte) 0x83;
        b.putInt(446 + 8, P1_START).putInt(446 + 12, P1_SECTORS);
        mbr[462 + 4] = (byte) 0xDA;
        b.putInt(462 + 8, P2_START).putInt(462 + 12, P2_SECTORS);
        mbr[510] = 0x55;
        mbr[511] = (byte) 0xAA;
        return mbr;
    }

    /** A file, or a directory when {@code data} is null. */
    private record CpioEntry(String name, byte[] data) {}

    /**
     * An uncompressed newc cpio archive, the kernel's initramfs format (Documentation/
     * driver-api/early-userspace/buffer-format.rst): files mode 0755, directories 0755, root
     * owned, then the trailer.
     */
    private static byte[] cpio(List<CpioEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int ino = 0x7000;
        for (CpioEntry e : entries) {
            boolean dir = e.data() == null;
            cpioEntry(out, ino++, dir ? 040755 : 0100755, dir ? 2 : 1, e.name(), dir ? new byte[0] : e.data());
        }
        cpioEntry(out, 0, 0, 1, "TRAILER!!!", new byte[0]);
        return out.toByteArray();
    }

    private static void cpioEntry(ByteArrayOutputStream out, int ino, int mode, int links, String name, byte[] data) {
        byte[] n = (name + "\0").getBytes(StandardCharsets.US_ASCII);
        String header = String.format(Locale.ROOT, "070701%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x", ino,
                mode, 0, 0, links, EPOCH, data.length, 0, 0, 0, 0, n.length, 0);
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(n);
        out.writeBytes(new byte[-(header.length() + n.length) & 3]);
        out.writeBytes(data);
        out.writeBytes(new byte[-data.length & 3]);
    }

    private static byte[] random(long seed, int length) {
        byte[] b = new byte[length];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i + j] != needle[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    /** {@code dir}, emptied of what an earlier run left. */
    private static Path emptyFolder(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(p);
            }
        }
        return Files.createDirectories(dir);
    }
}
