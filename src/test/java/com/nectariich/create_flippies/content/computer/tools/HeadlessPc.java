package com.nectariich.create_flippies.content.computer.tools;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.ContentSource;
import com.nectariich.create_flippies.content.computer.net.FolderContent;
import com.nectariich.create_flippies.content.computer.net.MemoryContent;
import com.nectariich.create_flippies.content.computer.net.Modem;
import com.nectariich.create_flippies.content.computer.net.SharedFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The headless PC runner (spec 12.9): one {@link Machine} built from command-line options and run
 * outside Minecraft, as the development loop for guest code (the kernel, the boot chain, the
 * Debian image, the netinstall floppy).
 *
 * <pre>
 * ./gradlew -q --console=plain runHeadlessPc --args='--cpu 68040 --ram 32M --hdd hdd.img --floppy boot.img'
 *
 *   --cpu MODEL         rv32i, 68000 or 68040 (default 68040)
 *   --clock HZ          CPU clock: 25000000, 25MHz, 12.5M (default: the model's own)
 *   --ram SIZE          RAM: bytes, or with a K, M or G suffix (powers of 1024; default 8M)
 *   --hdd FILE          raw HDD image (sector 0 at byte 0), its size a multiple of 512, at most 1 GiB
 *   --hdd-size SIZE     create the HDD image at this size (sparse) when FILE does not exist
 *   --floppy FILE       raw 1.44 MB floppy image (1,474,560 bytes; a shorter file reads as zeros past its end)
 *   --readonly          keep the guest's disk writes in memory; never write the image files
 *   --sync-seconds S    also write disk changes back every S seconds of emulated time
 *   --catalogue DIR     link the NIC to a modem serving DIR (one lower-case folder per host, spec 12.4.3)
 *   --catalogue JAR     or serving the internet bundled in the mod's jar, as the network_modem block does
 *   --modem             link the NIC to a modem with an empty catalogue (DHCP, DNS, ping; HTTP 404s)
 *   --share DIR         also serve DIR as localfilesys.com, the world's file share (implies --modem)
 *   --mac MAC           the NIC's MAC, as 02:46:4c:50:43:01 (the default)
 *   --strict-fpu        68040 only: the FPU traps what the real chip leaves to software (spec 10.4)
 *   --epoch SECONDS     the RTC starts at this Unix time (default: host time)
 *   --max-seconds S     stop after S seconds of emulated time (decimals allowed)
 *   --screenshot FILE   at exit, save a display: PNG in graphics modes, FILE with ".txt" in text mode
 *   --screenshot-at S   take the --screenshot at S seconds of emulated time instead of at exit
 *   --display N         the display --screenshot saves: 0, 1 or 2 (default 0)
 *   --input FILE        play a script of keystrokes, mouse moves and screenshots at emulated times
 *   --realtime          hold emulated time to wall-clock time instead of running as fast as possible
 *   --kernel FILE       68040 only: boot this Linux kernel (an ELF vmlinux) instead of a disk
 *   --initrd FILE       with --kernel: an initramfs or initrd, placed at the top of RAM
 *   --cmdline TEXT      with --kernel: the kernel command line (default "console=ttyS0,115200")
 *   --help              print this text
 * </pre>
 *
 * The machine is cold-booted through its standard boot ROM (floppy first, then HDD). With
 * {@code --kernel} the ROM's start-up is skipped instead, as the boot chain's second stage will
 * leave the machine (spec 12.7): {@link KernelLoader} puts the kernel, its bootinfo and the initrd
 * into RAM, the ROM overlay is off, vectors 2..255 point at the ROM's per-vector stubs (a fault
 * before the kernel installs its own vectors is reported as the ROM reports it), and the CPU
 * starts at the kernel's entry in supervisor mode with interrupts masked and the MMU off. Input
 * that reaches COM1 before the guest's driver has opened the port overruns the UART's 16-byte
 * FIFO and is lost, as on a real serial line: feed a Linux shell only after its prompt (with
 * {@code --realtime} a delayed pipe works). COM1 is bridged to standard input and output as raw
 * bytes, no line ending translation: for an interactive shell run {@code stty raw -echo} first
 * (and {@code stty sane} after), or input reaches the guest a line at a time with the terminal
 * echoing it. A keyboard is plugged into PS/2 port 0 and a mouse into port 1, as the test rigs
 * do; {@code --input} gives them input from a script ({@link InputScript}: typed text, keys,
 * mouse moves and buttons, and screenshots, each at an emulated time). The runner's own messages
 * go to standard error.
 *
 * Each tick is 50 ms of emulated time, run with no host budget (spec 12.9: unthrottled), then
 * the exchange phase as the game runs it: COM1 to and from the terminal, and with a modem
 * {@code modem.exchange(nic)} then {@code modem.tick()} (spec 12.4.2, 12.4.5), then the input
 * script's events that are due and {@code --screenshot-at}. Both act at tick boundaries, so their
 * times are rounded up to a multiple of 50 ms; time 0 is before the first tick.
 *
 * Disk images are read-write: what the guest writes is kept in memory and written back to the
 * file at exit (any exit, Ctrl-C included), and every {@code --sync-seconds} if given.
 *
 * Exit status: the guest's, when it powers off through SysCon (POWER 0x5555 is 0; 0x3333 with
 * code C in bits 31..16 is C's low byte, or 1 if that is 0, since a process status has 8 bits).
 * The runner's own: 124 when --max-seconds ran out, 125 when the CPU halted (a double fault),
 * 130 on Ctrl-C (SIGINT), 64 for bad options, 70 for an error inside the runner. A guest may
 * of course exit with one of those numbers too; the last line on standard error says which it
 * was. On a signal the JVM's own status wins over the runner's (128 plus the signal number, once
 * the disks are written back), so SIGTERM exits with 143; {@link #EXIT_INTERRUPTED} is what
 * {@link #run} returns, and the process status only on SIGINT.
 *
 * Gradle's own exit status is 1 whenever the runner's is not 0; for the exact status run the
 * class with {@code java} on the test runtime classpath.
 */
public final class HeadlessPc {

    public static final int EXIT_TIME_LIMIT = 124, EXIT_HALTED = 125, EXIT_INTERRUPTED = 130,
            EXIT_USAGE = 64, EXIT_SOFTWARE = 70;

    /** Default MACs: locally administered unicast, "FLPC" and "FLMD". */
    public static final long DEFAULT_MAC = 0x02_46_4C_50_43_01L, MODEM_MAC = 0x02_46_4C_4D_44_01L;
    public static final int DEFAULT_RAM = 8 << 20;

    /** Host time a tick may take: far more than any tick needs, so no emulated time is dropped. */
    private static final long UNTHROTTLED_BUDGET_NANOS = 10_000_000_000L;
    private static final int TICKS_PER_SECOND = (int) (1_000_000_000L / Machine.TICK_NANOS);

    static final String USAGE = """
            usage: runHeadlessPc --args='[options]'
              --cpu MODEL         rv32i, 68000 or 68040 (default 68040)
              --clock HZ          CPU clock: 25000000, 25MHz, 12.5M (default: the model's own)
              --ram SIZE          RAM: bytes, or with a K, M or G suffix (default 8M)
              --hdd FILE          raw HDD image, read-write
              --hdd-size SIZE     create the HDD image at this size when FILE does not exist
              --floppy FILE       raw 1.44 MB floppy image, read-write
              --readonly          keep disk writes in memory; never write the image files
              --sync-seconds S    also write disk changes back every S emulated seconds
              --catalogue DIR     link the NIC to a modem serving DIR (one folder per host)
              --catalogue JAR     or the internet bundled in the mod jar JAR (a .jar file)
              --modem             link the NIC to a modem with an empty catalogue
              --share DIR         also serve DIR as localfilesys.com (the world's file share)
              --mac MAC           the NIC's MAC (default 02:46:4c:50:43:01)
              --strict-fpu        68040 only: the FPU traps what the chip leaves to software
              --epoch SECONDS     the RTC starts at this Unix time (default: host time)
              --max-seconds S     stop after S seconds of emulated time
              --screenshot FILE   at exit, save a display (PNG; text mode as FILE.txt)
              --screenshot-at S   take the --screenshot at S emulated seconds instead
              --display N         the display to save: 0, 1 or 2 (default 0)
              --input FILE        play a script of PS/2 input and screenshots (see InputScript)
              --realtime          hold emulated time to wall-clock time
              --kernel FILE       68040: boot this Linux kernel ELF instead of a disk
              --initrd FILE       with --kernel: an initramfs, placed at the top of RAM
              --cmdline TEXT      with --kernel: the kernel command line
              --help              print this text
            exit status: the guest's power-off code; 124 time limit, 125 CPU halted,
            130 interrupted (143 on SIGTERM), 64 bad options, 70 runner error
            """;

    /** The kernel command line when {@code --kernel} is given without {@code --cmdline}. */
    public static final String DEFAULT_CMDLINE = "console=ttyS0,115200";

    /**
     * For tests that drive the guest themselves: called once per tick at the end of the exchange
     * phase, after COM1's output went to the output stream, with the emulated time. It may feed
     * the machine input (COM1's {@code hostWrite}, say) and read its state. What it throws ends the
     * run once the disks are written back: a RuntimeException as a runner error (status 70), an
     * Error out of {@code run}.
     */
    interface TickListener {
        void afterTick(Machine machine, long nowNanos);
    }

    /** The parsed command line. Paths are as given (relative to the working directory). */
    record Options(CpuModel cpu, int clockHz, int ramBytes, Path hdd, long hddCreateBytes, Path floppy,
                   boolean readOnly, double syncSeconds, Path catalogue, Path share, boolean modem, long mac, boolean strictFpu,
                   Long epochSeconds, double maxSeconds, Path screenshot, double screenshotAt, int display,
                   boolean realtime, Path kernel, Path initrd, String cmdline, Path input, boolean help) {

        /** Throws IllegalArgumentException with a message for the user on anything it cannot use. */
        static Options parse(String... args) {
            CpuModel cpu = CpuModel.CPU_68040;
            Integer clock = null;
            int ram = DEFAULT_RAM;
            Path hdd = null, floppy = null, catalogue = null, share = null, screenshot = null, kernel = null, initrd = null,
                    input = null;
            String cmdline = null;
            long hddCreate = 0;
            boolean readOnly = false, modem = false, strictFpu = false, realtime = false, help = false;
            double sync = 0, maxSeconds = 0, screenshotAt = 0;
            long mac = DEFAULT_MAC;
            Long epoch = null;
            int display = 0;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                String value = null;
                int eq = arg.indexOf('=');
                if (arg.startsWith("--") && eq > 0) {
                    value = arg.substring(eq + 1);
                    arg = arg.substring(0, eq);
                }
                switch (arg) {
                    case "--readonly", "--modem", "--strict-fpu", "--realtime", "--help", "-h" -> {
                        if (value != null)
                            throw new IllegalArgumentException(arg + " takes no value");
                    }
                    default -> {
                        if (value == null) {
                            if (i + 1 >= args.length)
                                throw new IllegalArgumentException(arg.startsWith("--")
                                        ? arg + " needs a value" : "unexpected argument \"" + arg + "\"");
                            value = args[++i];
                        }
                    }
                }
                switch (arg) {
                    case "--cpu" -> cpu = cpu(value);
                    case "--clock" -> clock = clock(value);
                    case "--ram" -> ram = (int) size(value, "--ram", Integer.MAX_VALUE);
                    case "--hdd" -> hdd = Path.of(value);
                    case "--hdd-size" -> hddCreate = size(value, "--hdd-size", PagedDiskImage.MAX_CAPACITY);
                    case "--floppy" -> floppy = Path.of(value);
                    case "--readonly" -> readOnly = true;
                    case "--sync-seconds" -> sync = seconds(value, "--sync-seconds");
                    case "--catalogue" -> catalogue = Path.of(value);
                    case "--modem" -> modem = true;
                    case "--share" -> share = Path.of(value);
                    case "--mac" -> mac = mac(value);
                    case "--strict-fpu" -> strictFpu = true;
                    case "--epoch" -> epoch = number(value, "--epoch");
                    case "--max-seconds" -> maxSeconds = seconds(value, "--max-seconds");
                    case "--screenshot" -> screenshot = Path.of(value);
                    case "--screenshot-at" -> screenshotAt = seconds(value, "--screenshot-at");
                    case "--display" -> {
                        display = (int) number(value, "--display");
                        if (display < 0 || display >= Machine.DISPLAYS)
                            throw new IllegalArgumentException("--display must be 0, 1 or 2: " + value);
                    }
                    case "--realtime" -> realtime = true;
                    case "--kernel" -> kernel = Path.of(value);
                    case "--initrd" -> initrd = Path.of(value);
                    case "--cmdline" -> cmdline = value;
                    case "--input" -> input = Path.of(value);
                    case "--help", "-h" -> help = true;
                    default -> throw new IllegalArgumentException("unknown option " + arg);
                }
            }
            if (hddCreate > 0 && hdd == null)
                throw new IllegalArgumentException("--hdd-size needs --hdd");
            if (screenshotAt > 0 && screenshot == null)
                throw new IllegalArgumentException("--screenshot-at needs --screenshot");
            if ((initrd != null || cmdline != null) && kernel == null)
                throw new IllegalArgumentException((initrd != null ? "--initrd" : "--cmdline") + " needs --kernel");
            if (kernel != null && cpu != CpuModel.CPU_68040)
                throw new IllegalArgumentException("--kernel needs --cpu 68040");
            if (kernel != null && cmdline == null)
                cmdline = DEFAULT_CMDLINE;
            return new Options(cpu, clock != null ? clock : cpu.defaultClockHz(), ram, hdd, hddCreate, floppy,
                    readOnly, sync, catalogue, share, modem || catalogue != null || share != null, mac, strictFpu, epoch, maxSeconds,
                    screenshot, screenshotAt, display, realtime, kernel, initrd, cmdline, input, help);
        }

        private static CpuModel cpu(String value) {
            for (CpuModel m : CpuModel.values())
                if (m.emulated() && (m.displayName().equalsIgnoreCase(value) || m.name().equalsIgnoreCase(value)))
                    return m;
            throw new IllegalArgumentException("--cpu must be rv32i, 68000 or 68040: " + value);
        }

        /** Hz, or with a k or M suffix (decimal), "Hz" optional. */
        private static int clock(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.endsWith("hz"))
                v = v.substring(0, v.length() - 2);
            long scale = 1;
            if (v.endsWith("k") || v.endsWith("m")) {
                scale = v.endsWith("k") ? 1_000 : 1_000_000;
                v = v.substring(0, v.length() - 1);
            }
            try {
                BigDecimal hz = new BigDecimal(v).multiply(BigDecimal.valueOf(scale));
                if (hz.signum() > 0 && hz.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0)
                    return hz.intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                // reported below
            }
            throw new IllegalArgumentException("--clock must be a whole number of Hz, as 25000000 or 25MHz: " + value);
        }

        /** Bytes, or with a K, M or G suffix (powers of 1024), "iB" or "B" optional. */
        private static long size(String value, String option, long max) {
            String v = value.trim().toUpperCase(Locale.ROOT);
            if (v.endsWith("IB"))
                v = v.substring(0, v.length() - 2);
            else if (v.endsWith("B"))
                v = v.substring(0, v.length() - 1);
            int shift = 0;
            if (!v.isEmpty()) {
                switch (v.charAt(v.length() - 1)) {
                    case 'K' -> shift = 10;
                    case 'M' -> shift = 20;
                    case 'G' -> shift = 30;
                    default -> { }
                }
            }
            if (shift > 0)
                v = v.substring(0, v.length() - 1);
            try {
                long n = Long.parseLong(v);
                if (n > 0 && n <= max >> shift)
                    return n << shift;
            } catch (NumberFormatException e) {
                // reported below
            }
            throw new IllegalArgumentException(option + " must be a positive size, as 1048576, 64M or 1G: " + value);
        }

        private static double seconds(String value, String option) {
            try {
                double s = Double.parseDouble(value);
                if (s > 0 && s < 1e9)
                    return s;
            } catch (NumberFormatException e) {
                // reported below
            }
            throw new IllegalArgumentException(option + " must be a positive number of seconds: " + value);
        }

        private static long number(String value, String option) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a whole number: " + value);
            }
        }

        /** Six hex bytes separated by ':' or '-'. */
        private static long mac(String value) {
            String[] parts = value.trim().split("[:-]");
            if (parts.length == 6) {
                long mac = 0;
                try {
                    for (String p : parts) {
                        if (p.isEmpty() || p.length() > 2)
                            throw new NumberFormatException(p);
                        mac = mac << 8 | Integer.parseInt(p, 16);
                    }
                    return mac;
                } catch (NumberFormatException e) {
                    // reported below
                }
            }
            throw new IllegalArgumentException("--mac must be six hex bytes, as 02:46:4c:50:43:01: " + value);
        }
    }

    private final Options options;
    private final InputStream in;
    private final OutputStream out;
    private final PrintStream err;
    private TickListener listener;
    /** Set from another thread (Ctrl-C) to stop the run at the next tick. */
    private volatile boolean stopRequested;
    private final CountDownLatch finished = new CountDownLatch(1);

    HeadlessPc(Options options, InputStream in, OutputStream out, PrintStream err) {
        this.options = options;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("headless: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.help()) {
            System.out.print(USAGE);
            System.exit(0);
        }
        HeadlessPc pc = new HeadlessPc(options, System.in, System.out, System.err);
        // Ctrl-C: the run stops at the next tick and writes the disks back before the JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(pc::stopAndWait, "headless-pc-shutdown"));
        System.exit(pc.run());
    }

    /** Parses {@code args} and runs; for tests. Returns the exit status. */
    static int run(String[] args, InputStream in, OutputStream out, PrintStream err) {
        return run(args, in, out, err, null);
    }

    /** As {@link #run(String[], InputStream, OutputStream, PrintStream)}, calling {@code listener} after every tick. */
    static int run(String[] args, InputStream in, OutputStream out, PrintStream err, TickListener listener) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("headless: " + e.getMessage());
            return EXIT_USAGE;
        }
        HeadlessPc pc = new HeadlessPc(options, in, out, err);
        pc.listener = listener;
        return pc.run();
    }

    private void stopAndWait() {
        stopRequested = true;
        try {
            finished.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code hosts}, after the {@code --share} folder as localfilesys.com when one is given, as the game orders them. */
    private Catalogue catalogue(ContentSource hosts) {
        return options.share() != null ? new Catalogue(new SharedFolder(options.share()), hosts) : new Catalogue(hosts);
    }

    /** Builds the machine, runs it to an exit, saves the screenshot and the disks; returns the exit status. */
    int run() {
        List<RawDiskFile> disks = new ArrayList<>();
        URLClassLoader catalogueJar = null;
        try {
            RawDiskFile hdd = null, floppy = null;
            Machine machine;
            Modem modem = null;
            InputScript script = null;
            try {
                if (options.input() != null)
                    script = InputScript.read(options.input());
                if (options.hdd() != null) {
                    hdd = RawDiskFile.open(options.hdd(), options.hddCreateBytes(), options.readOnly());
                    disks.add(hdd);
                }
                if (options.floppy() != null) {
                    floppy = RawDiskFile.openAs(options.floppy(), PagedDiskImage.FLOPPY_CAPACITY, options.readOnly());
                    disks.add(floppy);
                }
                Path cat = options.catalogue();
                boolean jar = cat != null && Files.isRegularFile(cat) && cat.toString().endsWith(".jar");
                if (cat != null && !jar && !Files.isDirectory(cat))
                    throw new IOException("catalogue folder " + cat + " does not exist");
                if (options.share() != null && !Files.isDirectory(options.share()))
                    throw new IOException("share folder " + options.share() + " does not exist");
                Long epoch = options.epochSeconds();
                LongSupplier rtc = epoch == null ? null : () -> epoch;
                MachineConfig config = new MachineConfig(options.cpu(), options.clockHz(), options.ramBytes(),
                        hdd == null ? null : hdd.image(), BootRoms.image(options.cpu()), rtc, null,
                        options.strictFpu(), options.mac());
                machine = new Machine(config, StandardCpus.INSTANCE);
                if (jar) {
                    // the jar alone, no parent: the same lookups the block's ClasspathContent makes in game
                    catalogueJar = new URLClassLoader(new URL[] {cat.toUri().toURL()}, null);
                    modem = new Modem(MODEM_MAC, catalogue(new ClasspathContent(catalogueJar, ClasspathContent.ROOT)));
                } else if (options.modem()) {
                    modem = new Modem(MODEM_MAC, catalogue(cat != null ? new FolderContent(cat) : new MemoryContent()));
                }
            } catch (IOException | IllegalArgumentException e) {
                err.println("headless: " + e.getMessage());
                return EXIT_USAGE;
            }
            if (floppy != null)
                machine.floppy().insert(floppy.image(), false);
            machine.ps2().setDevice(0, Ps2DeviceKind.KEYBOARD);
            machine.ps2().setDevice(1, Ps2DeviceKind.MOUSE);
            machine.powerOn();
            if (options.kernel() != null) {
                try {
                    loadKernel(machine);
                } catch (IOException | IllegalArgumentException e) {
                    err.println("headless: " + e.getMessage());
                    return EXIT_USAGE;
                }
            }
            InputScript.Player player = script == null ? null
                    : new InputScript.Player(script, machine.ps2().keyboard(0), machine.ps2().mouse(1));
            return loop(machine, modem, player, disks);
        } catch (IOException | RuntimeException e) {
            err.println("headless: runner error: " + e);
            e.printStackTrace(err);
            return EXIT_SOFTWARE;
        } finally {
            for (RawDiskFile d : disks) {
                try {
                    int chunks = d.flush();
                    if (chunks > 0)
                        err.println("headless: wrote " + chunks + " changed 64 KiB chunk" + (chunks == 1 ? "" : "s")
                                + " back to " + d.path());
                    d.close();
                } catch (IOException e) {
                    err.println("headless: could not write " + d.path() + " back: " + e.getMessage());
                }
            }
            if (catalogueJar != null) {
                try {
                    catalogueJar.close();
                } catch (IOException e) {
                    // only the jar's file handle
                }
            }
            err.flush();
            finished.countDown();
        }
    }

    /**
     * Instead of the boot ROM's start-up (spec 12.7): the kernel, its bootinfo and the initrd in
     * RAM, the overlay off, the reset vectors (SSP below the kernel, unused since head.S sets its
     * own at once; PC the entry) and vectors 2..255 to the ROM's stubs. The CPU reads the reset
     * vectors on its first run, in supervisor mode with interrupts masked and the MMU off.
     */
    private void loadKernel(Machine machine) throws IOException {
        byte[] elf = Files.readAllBytes(options.kernel());
        byte[] initrd = options.initrd() == null ? null : Files.readAllBytes(options.initrd());
        byte[] ram = machine.board().ram();
        KernelLoader.Loaded k = KernelLoader.load(ram, elf, initrd, options.cmdline());
        machine.bus().setRomOverlay(false);
        ByteBuffer vectors = ByteBuffer.wrap(ram);
        vectors.putInt(0, KernelLoader.LOAD_FLOOR);
        vectors.putInt(4, k.entry());
        int stubs = M68kBootRom.symbol(options.cpu(), "vec_stubs");
        for (int n = 2; n < 256; n++)
            vectors.putInt(4 * n, stubs + 4 * n);
        err.printf(Locale.ROOT, "headless: kernel entry 0x%x, bootinfo 0x%x to 0x%x%s, command line \"%s\"%n",
                k.entry(), k.end(), k.bootinfoEnd(), k.initrdSize() > 0
                        ? String.format(Locale.ROOT, ", initrd 0x%x (%d bytes)", k.initrdAddress(), k.initrdSize()) : "",
                options.cmdline());
        if (k.end() != k.segmentsEnd())
            err.printf(Locale.ROOT, "headless: warning: _end 0x%x is not the end of the last segment 0x%x%n",
                    k.end(), k.segmentsEnd());
    }

    private int loop(Machine machine, Modem modem, InputScript.Player player, List<RawDiskFile> disks)
            throws IOException {
        Uart16550 com1 = machine.com(0);
        com1.setHostConnected(true);
        InputPump input = new InputPump(in);
        long maxTicks = options.maxSeconds() > 0 ? (long) Math.ceil(options.maxSeconds() * TICKS_PER_SECOND) : -1;
        long syncTicks = options.syncSeconds() > 0
                ? Math.max(1, Math.round(options.syncSeconds() * TICKS_PER_SECOND)) : -1;
        byte[] chunk = new byte[4096];
        long screenshotAtTicks = options.screenshotAt() > 0
                ? (long) Math.ceil(options.screenshotAt() * TICKS_PER_SECOND - 1e-9) : -1;
        boolean screenshotTaken = false;
        long startHost = System.nanoTime();
        long ticks = 0;
        int status;
        String reason;
        if (player != null)
            player.advance(0, (display, file) -> screenshot(machine, display, file, 0));
        while (true) {
            if (stopRequested) {
                status = EXIT_INTERRUPTED;
                reason = "interrupted";
                break;
            }
            machine.runTick(Machine.TICK_NANOS, UNTHROTTLED_BUDGET_NANOS);
            ticks++;

            // the exchange phase: COM1 both ways, then the modem (spec 11.2.10, 12.4.5)
            boolean wrote = false;
            for (int n; (n = com1.hostRead(chunk, 0, chunk.length)) > 0; wrote = true)
                out.write(chunk, 0, n);
            if (wrote)
                out.flush();
            input.feed(com1);
            if (modem != null) {
                modem.exchange(machine.nic());
                modem.tick();
            }
            long now = ticks * Machine.TICK_NANOS;
            if (player != null)
                player.advance(now, (display, file) -> screenshot(machine, display, file, now));
            if (!screenshotTaken && screenshotAtTicks >= 0 && ticks >= screenshotAtTicks) {
                screenshot(machine, options.display(), options.screenshot(), now);
                screenshotTaken = true;
            }
            if (listener != null)
                listener.afterTick(machine, now);

            MachineState state = machine.state();
            if (state == MachineState.OFF_BY_GUEST) {
                int code = machine.exitCode();
                status = code == 0 ? 0 : (code & 0xFF) != 0 ? code & 0xFF : 1;
                reason = String.format("guest powered off, code 0x%04X", code);
                break;
            }
            if (state == MachineState.HALTED) {
                status = EXIT_HALTED;
                reason = "CPU halted: " + machine.haltReason();
                break;
            }
            if (maxTicks >= 0 && ticks >= maxTicks) {
                status = EXIT_TIME_LIMIT;
                reason = "time limit reached";
                break;
            }
            if (syncTicks > 0 && ticks % syncTicks == 0)
                for (RawDiskFile d : disks)
                    d.flush();
            if (options.realtime()) {
                long ahead = startHost + ticks * Machine.TICK_NANOS - System.nanoTime();
                if (ahead > 0) {
                    try {
                        Thread.sleep(ahead / 1_000_000, (int) (ahead % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopRequested = true;
                    }
                }
            }
        }
        out.flush();
        if (options.screenshot() != null && !screenshotTaken) {
            if (screenshotAtTicks >= 0)
                err.printf(Locale.ROOT, "headless: the run ended before --screenshot-at %s s%n",
                        BigDecimal.valueOf(options.screenshotAt()).toPlainString());
            screenshot(machine, options.display(), options.screenshot(), ticks * Machine.TICK_NANOS);
        }
        double emulated = ticks / (double) TICKS_PER_SECOND;
        double host = (System.nanoTime() - startHost) / 1e9;
        err.printf(Locale.ROOT, "headless: %s after %.2f s emulated in %.2f s host (%.2fx real time), exit %d%n",
                reason, emulated, host, host > 0 ? emulated / host : 0, status);
        return status;
    }

    /** Saves a display as --screenshot does; a failure is reported and the run goes on. */
    private void screenshot(Machine machine, int display, Path file, long nowNanos) {
        try {
            Path saved = Screenshot.save(machine.vga(display).snapshot(), file);
            err.printf(Locale.ROOT, "headless: display %d saved to %s at %.2f s%n", display, saved, nowNanos / 1e9);
        } catch (IOException e) {
            err.println("headless: could not save the screenshot " + file + ": " + e.getMessage());
        }
    }

    /**
     * Standard input towards COM1: a daemon thread reads it as it arrives; each tick hands the
     * UART what it will take, keeping the rest for later ticks, so nothing typed is lost. End of
     * input only stops the reading; the guest runs on.
     */
    private static final class InputPump {
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private byte[] pending;
        private int pendingAt;

        InputPump(InputStream in) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    for (int n; (n = in.read(buf)) >= 0; )
                        if (n > 0)
                            queue.add(java.util.Arrays.copyOf(buf, n));
                } catch (IOException e) {
                    // input closed: the guest runs on without it
                }
            }, "headless-pc-stdin");
            t.setDaemon(true);
            t.start();
        }

        void feed(Uart16550 uart) {
            while (true) {
                if (pending == null) {
                    pending = queue.poll();
                    pendingAt = 0;
                    if (pending == null)
                        return;
                }
                pendingAt += uart.hostWrite(pending, pendingAt, pending.length - pendingAt);
                if (pendingAt < pending.length)
                    return;
                pending = null;
            }
        }
    }
}
