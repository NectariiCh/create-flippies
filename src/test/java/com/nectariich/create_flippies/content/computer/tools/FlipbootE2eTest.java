package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.tools.FlipbootDisk.Slot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot chain of spec 12.7 on the 68040 PC, cold-booted from its boot ROM with no
 * {@code --kernel}: the ROM loads stage 1 from the MBR, stage 1 loads stage 2 (flipboot), and
 * flipboot loads a kernel from the boot area. A tiny hand-made kernel ELF covers the loader's
 * rules exactly (segments from max(p_paddr, 0x1000), the BSS zeroed, the bootinfo at _end, the
 * same records as {@link KernelLoader} plus BI_RNG_SEED, nothing written after them, the CPU state
 * at entry), the prompt and Esc, the header copies and the CRC checks, and flipboot-update. The
 * built Linux kernel, when present, boots from the disk too (tagged "linux").
 *
 * Needs guest/boot/build.sh's stage1.bin and stage2.bin, and is skipped without them; the
 * comparison with mkdisk.py also needs python3, and the flipboot-update test /bin/sh.
 */
class FlipbootE2eTest {

    private static final long EPOCH = 1_792_944_000L;
    private static final long DISK_BYTES = (FlipbootDisk.P3_START + 4096L) * FlipbootDisk.SECTOR;
    private static final int RAM = 32 << 20;

    /** Where the test kernel leaves SR, TC, VBR, SP and a mark at entry; zero in its file. */
    private static final int RESULTS = 0x1800, MARK = 0x4B45524E;
    private static final int ENTRY = 0x2000, END = 0x9000;
    private static final int SEG1_ADDR = 0x6000, SEG1_FILESZ = 0x2345, SEG1_MEMSZ = 0x2400;
    private static final int POWER_OFF_A = 0x5555, POWER_OFF_B = 0x00423333, POWER_OFF_C = 0x00433333;
    private static final String CMDLINE_A = "console=ttyS0,115200 slot=a";
    private static final String CMDLINE_B = "console=ttyS0,115200 slot=b";

    private static byte[] stage1, stage2, kernelA, kernelB, kernelC;

    @TempDir
    Path dir;

    @BeforeAll
    static void load() throws IOException {
        if (Files.isRegularFile(FlipbootDisk.STAGE1) && Files.isRegularFile(FlipbootDisk.STAGE2)) {
            stage1 = Files.readAllBytes(FlipbootDisk.STAGE1);
            stage2 = Files.readAllBytes(FlipbootDisk.STAGE2);
        }
        kernelA = testKernel(POWER_OFF_A, 1);
        kernelB = testKernel(POWER_OFF_B, 2);
        kernelC = testKernel(POWER_OFF_C, 3);
    }

    private static void assumeBuilt() {
        assumeTrue(stage1 != null, FlipbootDisk.STAGE1 + " is not built (guest/boot/build.sh)");
    }

    /**
     * A kernel ELF as Linux's is laid out: segment 0 from address 0 and file offset 0 (its first
     * page only the ELF header), code at 0x2000, BSS to 0x5000; segment 1 at 0x6000 with a file
     * size that is not a whole number of sectors and a zero tail; _end at 0x9000. At entry it
     * stores SR, TC, VBR and SP, a mark, and powers off with {@code power}.
     */
    private static byte[] testKernel(int power, int seed) {
        String source = String.format("""
                        move.w  sr,d0
                        move.l  d0,$%X
                        movec   tc,d0
                        move.l  d0,$%X
                        movec   vbr,d0
                        move.l  d0,$%X
                        move.l  sp,$%X
                        move.l  #$%X,$%X
                        move.l  #$%X,$FFF00000
                1:      bra.s   1b
                """, RESULTS, RESULTS + 4, RESULTS + 8, RESULTS + 12, MARK, RESULTS + 16, power);
        AsmResult r = M68kAssembler.assemble(source, AsmOptions.at(ENTRY), M68kTarget.M68040);
        assertTrue(r.ok(), r.diagnostics().toString());
        byte[] code = r.image().flatten(ENTRY, 64);
        Random random = new Random(seed);
        byte[] data0 = new byte[0xC00];
        byte[] data1 = new byte[SEG1_FILESZ];
        random.nextBytes(data0);
        random.nextBytes(data1);
        return new TestElf().entry(ENTRY)
                .put(0x2000, code).put(0x2400, data0).segment(0, 0, 0x3000, 0x5000)
                .put(0x3000, data1).segment(0x3000, SEG1_ADDR, SEG1_FILESZ, SEG1_MEMSZ)
                .symbol("_end", END).build();
    }

    private Path disk(int active, Slot a, Slot b) throws IOException {
        Path disk = dir.resolve("disk.img");
        FlipbootDisk.write(disk, DISK_BYTES, stage1, stage2, active, a, b, null);
        return disk;
    }

    /** What a boot left: the runner's status and messages, COM1, the machine, the last tick's time. */
    private record Run(int status, String com1, String runner, Machine machine, long lastNanos, byte[] ramAtFirstTick) {
    }

    private static Run boot(Path disk, int ram, double maxSeconds, BiConsumer<Machine, Long> eachTick) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Machine[] machine = {null};
        long[] last = {0};
        byte[][] first = {null};
        HeadlessPc.TickListener listener = (m, now) -> {
            machine[0] = m;
            last[0] = now;
            if (first[0] == null)
                first[0] = m.board().ram().clone();
            if (eachTick != null)
                eachTick.accept(m, now);
        };
        String[] args = {"--cpu", "68040", "--ram", Integer.toString(ram), "--hdd", disk.toString(), "--readonly",
                "--epoch", Long.toString(EPOCH), "--max-seconds", Double.toString(maxSeconds)};
        int status = HeadlessPc.run(args, InputStream.nullInputStream(), out,
                new PrintStream(err, true, StandardCharsets.UTF_8), listener);
        return new Run(status, out.toString(StandardCharsets.ISO_8859_1), err.toString(StandardCharsets.UTF_8),
                machine[0], last[0], first[0]);
    }

    /** Fills the test kernel's BSS, segment 1's tail and the two pages from _end with junk before stage 2 loads. */
    private static void junk(Machine m) {
        byte[] ram = m.board().ram();
        assertEquals(0, ByteBuffer.wrap(ram).getInt(ENTRY), "the kernel was loaded before the first tick");
        Arrays.fill(ram, 0x3000, 0x5000, (byte) 0xAA);
        Arrays.fill(ram, SEG1_ADDR + SEG1_FILESZ, SEG1_ADDR + SEG1_MEMSZ, (byte) 0xAA);
        Arrays.fill(ram, END, END + 0x2000, (byte) 0x5A);
    }

    /** The bootinfo records at {@code at} up to and including BI_LAST, as tag, size, data. */
    private static List<byte[]> records(byte[] ram, int at) {
        List<byte[]> list = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(ram);
        while (true) {
            int tag = b.getShort(at) & 0xFFFF, size = b.getShort(at + 2) & 0xFFFF;
            assertTrue(size >= 4 && size % 2 == 0 && at + size < ram.length, "bad record size " + size);
            list.add(Arrays.copyOfRange(ram, at, at + size));
            at += size;
            if (tag == 0)
                return list;
        }
    }

    private static int tag(byte[] record) {
        return ByteBuffer.wrap(record).getShort(0) & 0xFFFF;
    }

    /** Checks the loaded kernel, the bootinfo and the entry state; returns the RNG seed record. */
    private static byte[] checkLoaded(Run run, byte[] elf, String cmdline) {
        byte[] ram = run.machine().board().ram();
        ByteBuffer r = ByteBuffer.wrap(ram);
        assertEquals(MARK, r.getInt(RESULTS + 16), "the kernel did not run\n" + run.com1());
        assertEquals(0x2700, r.getInt(RESULTS) & 0xFFFF, "SR at entry");
        assertEquals(0, r.getInt(RESULTS + 4), "TC at entry: the MMU must be off");
        assertEquals(0, r.getInt(RESULTS + 8), "VBR at entry");
        assertEquals(0x1000, r.getInt(RESULTS + 12), "SP at entry");

        // page 0 still holds the ROM's vectors, not the ELF header
        int stubs = M68kBootRom.symbol(CpuModel.CPU_68040, "vec_stubs");
        for (int n = 2; n < 256; n++)
            assertEquals(stubs + 4 * n, r.getInt(4 * n), "vector " + n);
        // segment 0 from 0x1000, its BSS zeroed; segment 1 with its zero tail
        byte[] seg0 = Arrays.copyOfRange(elf, 0x1000, 0x3000);
        byte[] got0 = Arrays.copyOfRange(ram, 0x1000, 0x3000);
        Arrays.fill(got0, RESULTS - 0x1000, RESULTS - 0x1000 + 20, (byte) 0);
        assertArrayEquals(seg0, got0, "segment 0");
        assertArrayEquals(new byte[0x2000], Arrays.copyOfRange(ram, 0x3000, 0x5000), "segment 0's BSS");
        assertArrayEquals(Arrays.copyOfRange(elf, 0x3000, 0x3000 + SEG1_FILESZ),
                Arrays.copyOfRange(ram, SEG1_ADDR, SEG1_ADDR + SEG1_FILESZ), "segment 1");
        assertArrayEquals(new byte[SEG1_MEMSZ - SEG1_FILESZ],
                Arrays.copyOfRange(ram, SEG1_ADDR + SEG1_FILESZ, SEG1_ADDR + SEG1_MEMSZ), "segment 1's zero tail");

        // the bootinfo: KernelLoader's records, plus BI_RNG_SEED before the command line
        List<byte[]> records = records(ram, END);
        byte[] expectedRam = new byte[ram.length];
        KernelLoader.Loaded k = KernelLoader.load(expectedRam, elf, null, cmdline);
        assertEquals(END, k.end());
        List<byte[]> expected = records(expectedRam, END);
        byte[] seed = null;
        List<byte[]> withoutSeed = new ArrayList<>();
        for (byte[] rec : records) {
            if (tag(rec) == 0x0008)
                seed = rec;
            else
                withoutSeed.add(rec);
        }
        assertEquals(expected.size(), withoutSeed.size());
        for (int i = 0; i < expected.size(); i++)
            assertArrayEquals(expected.get(i), withoutSeed.get(i), "bootinfo record " + i);
        assertTrue(seed != null, "no BI_RNG_SEED record");
        assertEquals(0x0008, tag(records.get(records.size() - 3)), "BI_RNG_SEED comes before BI_COMMAND_LINE");
        assertEquals(40, seed.length);
        assertEquals(32, ByteBuffer.wrap(seed).getShort(4));
        assertFalse(Arrays.equals(new byte[32], Arrays.copyOfRange(seed, 6, 38)), "an all-zero seed");

        // nothing after the bootinfo: the junk is untouched up to two pages past _end
        int bootinfoEnd = k.bootinfoEnd() + seed.length;
        for (int a = bootinfoEnd; a < END + 0x2000; a++)
            assertEquals((byte) 0x5A, ram[a], String.format("byte at 0x%x after the bootinfo", a));
        return seed;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void bootsTheActiveSlotAfterThePrompt() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_B));
        boolean[] filled = {false};
        Run run = boot(disk, RAM, 10, (m, now) -> {
            if (!filled[0]) {
                junk(m);
                filled[0] = true;
            }
        });
        assertEquals(0, run.status(), run.runner() + run.com1());
        String com1 = run.com1();
        assertTrue(com1.startsWith("Create Flippies PC  68040  32768 KiB RAM\r\nflipboot\r\n"
                + "flipboot: slot A active, generation 1\r\n"
                + "Booting slot A in 2 seconds; press Esc for slot B\r\n"
                + "Loading slot A: " + kernelA.length + " bytes\r\n"
                + "Command line: " + CMDLINE_A + " flipboot.slot=A\r\n"
                + "Starting the kernel at 0x00002000, bootinfo 0x00009000..0x"), com1);
        assertTrue(run.lastNanos() >= 2_000_000_000L && run.lastNanos() < 2_500_000_000L,
                "kernel entered at " + run.lastNanos() / 1e9 + " s");
        checkLoaded(run, kernelA, CMDLINE_A + " flipboot.slot=A");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void escBootsTheOtherSlotAtOnce() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_B));
        int[] step = {0};
        Run run = boot(disk, RAM, 10, (m, now) -> {
            if (step[0] == 0) {
                junk(m);
                step[0] = 1;
            } else if (step[0] == 1 && now >= 500_000_000L) {
                m.ps2().keyboard(0).keyDown(Ps2Key.ESCAPE);
                step[0] = 2;
            } else if (step[0] == 2) {
                m.ps2().keyboard(0).keyUp(Ps2Key.ESCAPE);
                step[0] = 3;
            }
        });
        assertEquals(0x42, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("press Esc for slot B\r\nEsc: booting slot B\r\nLoading slot B: "), run.com1());
        assertTrue(run.com1().contains("Command line: " + CMDLINE_B + " flipboot.slot=B\r\n"), run.com1());
        assertTrue(run.lastNanos() < 1_000_000_000L, "kernel entered at " + run.lastNanos() / 1e9 + " s");
        checkLoaded(run, kernelB, CMDLINE_B + " flipboot.slot=B");
        // the PS/2 ports are left as the ROM left them: disabled
        assertEquals(0, run.machine().ps2().ctrl(0));
        assertEquals(0, run.machine().ps2().ctrl(1));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void activeSlotBWithAnEmptySlotABootsWithoutAPrompt() throws IOException {
        assumeBuilt();
        Run run = boot(disk(1, Slot.EMPTY, new Slot(kernelB, CMDLINE_B)), RAM, 5, null);
        assertEquals(0x42, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("flipboot: slot B active, generation 1\r\nLoading slot B: "), run.com1());
        assertTrue(run.lastNanos() < 500_000_000L, "kernel entered at " + run.lastNanos() / 1e9 + " s");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void anEmptyActiveSlotStops() throws IOException {
        assumeBuilt();
        Run run = boot(disk(0, Slot.EMPTY, Slot.EMPTY), RAM, 1, null);
        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status(), run.runner());
        assertTrue(run.com1().endsWith("flipboot: slot A: empty\r\n"), run.com1());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aKernelCrcErrorStops() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        long at = (long) FlipbootDisk.P1_START * FlipbootDisk.SECTOR + FlipbootDisk.SLOT_OFFSETS[0] + 0x2500;
        try (RandomAccessFile f = new RandomAccessFile(disk.toFile(), "rw")) {
            f.seek(at);
            int b = f.read();
            f.seek(at);
            f.write(b ^ 0x10);
        }
        Run run = boot(disk, RAM, 2, null);
        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status(), run.runner());
        String crc = String.format("0x%08X", FlipbootDisk.cksum(kernelA));
        assertTrue(run.com1().contains("flipboot: slot A: kernel CRC error (header " + crc + ", read 0x"), run.com1());
        assertTrue(run.com1().endsWith(")\r\n"), run.com1());
        assertEquals(0, ByteBuffer.wrap(run.machine().board().ram()).getInt(RESULTS + 16), "the kernel ran");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aDamagedFirstHeaderFallsBackToItsCopy() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        poke(disk, (long) FlipbootDisk.P1_START * FlipbootDisk.SECTOR + 16 + 3, 7);
        Run run = boot(disk, RAM, 5, null);
        assertEquals(0, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("flipboot: the boot area's first header is damaged, using its copy\r\n"
                + "flipboot: slot A active, generation 1\r\n"), run.com1());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void bothHeadersDamagedStops() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        long p1 = (long) FlipbootDisk.P1_START * FlipbootDisk.SECTOR;
        poke(disk, p1 + 600, 'x');
        poke(disk, p1 + FlipbootDisk.HEADER + 600, 'x');
        Run run = boot(disk, RAM, 1, null);
        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status(), run.runner());
        assertTrue(run.com1().endsWith("flipboot\r\nflipboot: boot area header CRC error\r\n"), run.com1());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void stage1NeedsSixteenMegabytes() throws IOException {
        assumeBuilt();
        Run run = boot(disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY), 8 << 20, 1, null);
        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status(), run.runner());
        assertTrue(run.com1().endsWith("flipboot\r\nflipboot: needs 16 MiB of RAM\r\n"), run.com1());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void stage1WithoutStage2Stops() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        poke(disk, FlipbootDisk.SECTOR + 4, 0);
        Run run = boot(disk, RAM, 1, null);
        assertEquals(HeadlessPc.EXIT_TIME_LIMIT, run.status(), run.runner());
        assertTrue(run.com1().endsWith("flipboot\r\nflipboot: no stage 2 on this disk\r\n"), run.com1());
    }

    private static void poke(Path file, long at, int value) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.seek(at);
            f.write(value);
        }
    }

    /** mkdisk.py, given the same inputs, writes the same image as FlipbootDisk. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void mkdiskPyWritesTheSameImage() throws IOException, InterruptedException {
        assumeBuilt();
        assumeTrue(runs("python3", "--version"), "no python3");
        Path a = Files.write(dir.resolve("a.elf"), kernelA);
        Path b = Files.write(dir.resolve("b.elf"), kernelB);
        byte[] root = new byte[100_000];
        new Random(3).nextBytes(root);
        Path rootFile = Files.write(dir.resolve("root.ext4"), root);
        Path py = dir.resolve("py.img");
        Process p = new ProcessBuilder("python3", FlipbootDisk.MKDISK.toString(), "create", py.toString(),
                "--size", Long.toString(DISK_BYTES), "--stage1", FlipbootDisk.STAGE1.toString(),
                "--stage2", FlipbootDisk.STAGE2.toString(), "--kernel-a", a.toString(), "--cmdline-a", CMDLINE_A,
                "--kernel-b", b.toString(), "--active", "b", "--root", rootFile.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
        Path java = dir.resolve("java.img");
        FlipbootDisk.write(java, DISK_BYTES, stage1, stage2, 1, new Slot(kernelA, CMDLINE_A),
                new Slot(kernelB, FlipbootDisk.DEFAULT_CMDLINE), root);
        assertEquals(-1, Files.mismatch(py, java), "the images differ");
    }

    /**
     * flipboot-update (the flippies-boot package's script, run by /bin/sh on the host) installs a
     * kernel into the inactive slot of a partition 1 image and flips; the disk then boots it.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void flipbootUpdateInstallsIntoTheInactiveSlotAndFlips() throws IOException, InterruptedException {
        assumeBuilt();
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh");
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        long p1 = (long) FlipbootDisk.P1_START * FlipbootDisk.SECTOR;
        int p1Bytes = FlipbootDisk.P1_SECTORS * FlipbootDisk.SECTOR;
        Path area = dir.resolve("p1.img");
        try (RandomAccessFile f = new RandomAccessFile(disk.toFile(), "r")) {
            byte[] bytes = new byte[p1Bytes];
            f.seek(p1);
            f.readFully(bytes);
            Files.write(area, bytes);
        }
        Path b = Files.write(dir.resolve("vmlinux-b"), kernelB);
        Path text = Files.writeString(dir.resolve("not-elf"), "not a kernel\n");

        String refused = update(1, "--device", area.toString(), text.toString());
        assertTrue(refused.contains("is not an ELF file"), refused);
        String installed = update(0, "--device", area.toString(), "--cmdline", CMDLINE_B, b.toString());
        assertTrue(installed.contains("in slot B, now active; command line \"" + CMDLINE_B + "\""), installed);
        String shown = update(0, "--device", area.toString(), "--show");
        assertTrue(shown.contains("generation 2, active slot B"), shown);
        assertTrue(shown.contains("slot B: offset 16777216, " + kernelB.length + " bytes, crc " + FlipbootDisk.cksum(kernelB)
                + ", command line \"" + CMDLINE_B + "\""), shown);

        // the header copies are what FlipbootDisk would write for this state
        byte[] bytes = Files.readAllBytes(area);
        byte[] expected = FlipbootDisk.header(1, 2, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_B));
        assertArrayEquals(expected, Arrays.copyOf(bytes, FlipbootDisk.HEADER));
        assertArrayEquals(expected, Arrays.copyOfRange(bytes, FlipbootDisk.HEADER, 2 * FlipbootDisk.HEADER));
        try (RandomAccessFile f = new RandomAccessFile(disk.toFile(), "rw")) {
            f.seek(p1);
            f.write(bytes);
        }
        Run run = boot(disk, RAM, 5, null);
        assertEquals(0x42, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("flipboot: slot B active, generation 2\r\n"
                + "Booting slot B in 2 seconds; press Esc for slot A\r\n"), run.com1());
        checkLoadedQuick(run, CMDLINE_B + " flipboot.slot=B");
    }

    // ---- flipboot-update across power losses, reinstalls and fallback boots ----

    private static final long P1 = (long) FlipbootDisk.P1_START * FlipbootDisk.SECTOR;

    /** Partition 1 of {@code disk} as a file of its own, as flipboot-update sees /dev/fhda1. */
    private Path area(Path disk) throws IOException {
        byte[] bytes = new byte[FlipbootDisk.P1_SECTORS * FlipbootDisk.SECTOR];
        try (RandomAccessFile f = new RandomAccessFile(disk.toFile(), "r")) {
            f.seek(P1);
            f.readFully(bytes);
        }
        return Files.write(dir.resolve("p1.img"), bytes);
    }

    private static void putArea(Path disk, Path area) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(disk.toFile(), "rw")) {
            f.seek(P1);
            f.write(Files.readAllBytes(area));
        }
    }

    private static byte[] slotBytes(Path area, int slot, int length) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(area.toFile(), "r")) {
            byte[] b = new byte[length];
            f.seek(FlipbootDisk.SLOT_OFFSETS[slot]);
            f.readFully(b);
            return b;
        }
    }

    private static byte[] headerCopy(Path area, int copy) throws IOException {
        byte[] bytes = Files.readAllBytes(area);
        return Arrays.copyOfRange(bytes, copy * FlipbootDisk.HEADER, (copy + 1) * FlipbootDisk.HEADER);
    }

    /**
     * The environment for a flipboot-update run that loses power right after its first write to
     * the first header copy: a {@code sync} ahead on PATH kills the script at the first sync that
     * finds copy 0 changed from what it was when the run began.
     */
    private Map<String, String> powerLossAfterHeaderCopy0(Path area) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("powerloss-bin"));
        Path sync = bin.resolve("sync");
        Files.writeString(sync, """
                #!/bin/sh
                dd if="$POWERLOSS_AREA" bs=4096 count=1 2>/dev/null | cmp -s - "$POWERLOSS_BEFORE" || kill -9 $PPID
                exit 0
                """);
        assertTrue(sync.toFile().setExecutable(true));
        Path before = Files.write(dir.resolve("copy0-before"), headerCopy(area, 0));
        return Map.of("PATH", bin + ":" + System.getenv("PATH"), "POWERLOSS_AREA", area.toString(),
                "POWERLOSS_BEFORE", before.toString());
    }

    /**
     * Two upgrades, each losing power while it writes the header: the first after copy 0 and
     * before copy 1, the second while copy 0 is being written (left torn). The disk must still
     * boot a whole kernel: the second upgrade first brings copy 1 up to date, so copy 1 then names
     * slot B with the first upgrade's kernel. (Before the fix copy 1 still named slot A with the
     * original kernel's CRC while slot A held the new kernel, and stage 2 stopped on a CRC error.)
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void twoPowerLossesDuringUpgradesLeaveABootableDisk() throws IOException, InterruptedException {
        assumeBuilt();
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh");
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY);
        Path area = area(disk);
        Path b = Files.write(dir.resolve("vmlinux-b"), kernelB);
        Path c = Files.write(dir.resolve("vmlinux-c"), kernelC);

        update(137, powerLossAfterHeaderCopy0(area), "--device", area.toString(), b.toString());
        byte[] copy0 = headerCopy(area, 0), copy1 = headerCopy(area, 1);
        assertArrayEquals(FlipbootDisk.header(1, 2, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_A)), copy0,
                "copy 0 after the first power loss");
        assertArrayEquals(FlipbootDisk.header(0, 1, new Slot(kernelA, CMDLINE_A), Slot.EMPTY), copy1,
                "copy 1 after the first power loss");

        String second = update(137, powerLossAfterHeaderCopy0(area), "--device", area.toString(), c.toString());
        assertTrue(second.contains("the header copies differ"), second);
        // copy 0 torn mid-write
        try (RandomAccessFile f = new RandomAccessFile(area.toFile(), "rw")) {
            f.seek(600);
            f.write(new byte[200]);
        }
        String shown = update(0, "--device", area.toString(), "--show");
        assertTrue(shown.contains("the first header copy is damaged, using the second"), shown);
        assertTrue(shown.contains("generation 2, active slot B"), shown);
        assertArrayEquals(kernelB, slotBytes(area, 1, kernelB.length), "slot B");

        putArea(disk, area);
        Run run = boot(disk, RAM, 5, null);
        assertEquals(0x42, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("flipboot: the boot area's first header is damaged, using its copy\r\n"
                + "flipboot: slot B active, generation 2\r\n"), run.com1());
    }

    /**
     * Slot B is active but the machine runs slot A (Esc at the prompt, or an earlier update in
     * this session): the new kernel goes into slot B, never over the running kernel in slot A,
     * which is made active first so that a power loss mid-write leaves it bootable.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void theRunningSlotIsNeverOverwritten() throws IOException, InterruptedException {
        assumeBuilt();
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh");
        Path disk = disk(1, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_B));
        Path area = area(disk);
        Path c = Files.write(dir.resolve("vmlinux-c"), kernelC);
        Path proc = Files.writeString(dir.resolve("cmdline"), CMDLINE_A + " flipboot.slot=A\n");

        String out = update(0, Map.of("FLIPBOOT_PROC_CMDLINE", proc.toString()), "--device", area.toString(), c.toString());
        assertTrue(out.contains("slot A is running but slot B is active; making slot A active first"), out);
        assertTrue(out.contains("in slot B, now active; command line \"" + CMDLINE_A + "\""), out);
        assertArrayEquals(kernelA, slotBytes(area, 0, kernelA.length), "the running kernel in slot A");
        byte[] expected = FlipbootDisk.header(1, 3, new Slot(kernelA, CMDLINE_A), new Slot(kernelC, CMDLINE_A));
        assertArrayEquals(expected, headerCopy(area, 0));
        assertArrayEquals(expected, headerCopy(area, 1));

        putArea(disk, area);
        Run run = boot(disk, RAM, 5, null);
        assertEquals(0x43, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("Command line: " + CMDLINE_A + " flipboot.slot=B\r\n"), run.com1());
    }

    /**
     * The kernel package's hook run twice for one kernel (dpkg-reconfigure, dpkg --configure -a):
     * the second run finds the kernel already in the active slot and leaves the other slot's
     * kernel alone. A command line with no room for stage 2's slot marker is refused.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void installingTheActiveKernelAgainChangesNothing() throws IOException, InterruptedException {
        assumeBuilt();
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh");
        Path area = area(disk(0, new Slot(kernelA, CMDLINE_A), Slot.EMPTY));
        Path b = Files.write(dir.resolve("vmlinux-b"), kernelB);
        update(0, "--device", area.toString(), b.toString());
        byte[] before = Files.readAllBytes(area);

        String again = update(0, "--device", area.toString(), b.toString());
        assertTrue(again.contains("is already in slot B, the active slot; nothing to do"), again);
        assertEquals(-1, Arrays.mismatch(before, Files.readAllBytes(area)), "the boot area changed");
        assertArrayEquals(kernelA, slotBytes(area, 0, kernelA.length), "slot A");

        String tooLong = update(1, "--device", area.toString(), "--cmdline", "x".repeat(240), b.toString());
        assertTrue(tooLong.contains("the command line is longer than 239 bytes"), tooLong);
    }

    /** The active slot's kernel fails its CRC: stage 2 says so and boots the other slot. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aKernelCrcErrorFallsBackToTheOtherSlot() throws IOException {
        assumeBuilt();
        Path disk = disk(0, new Slot(kernelA, CMDLINE_A), new Slot(kernelB, CMDLINE_B));
        poke(disk, P1 + FlipbootDisk.SLOT_OFFSETS[0] + 0x2500, 0x77);
        Run run = boot(disk, RAM, 10, null);
        assertEquals(0x42, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("flipboot: slot A: kernel CRC error (header "), run.com1());
        assertTrue(run.com1().contains(")\r\nflipboot: trying slot B\r\nLoading slot B: " + kernelB.length + " bytes\r\n"
                + "Command line: " + CMDLINE_B + " flipboot.slot=B\r\n"), run.com1());
    }

    /** A stored command line with no room for " flipboot.slot=A" reaches the kernel as it is. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void aLongCommandLineGoesWithoutTheSlotMarker() throws IOException {
        assumeBuilt();
        String cmdline = "console=ttyS0,115200 " + "x".repeat(250 - 21);
        boolean[] filled = {false};
        // slot B filled, for the prompt's 2 seconds in which junk() runs before the load
        Run run = boot(disk(0, new Slot(kernelA, cmdline), new Slot(kernelB, CMDLINE_B)), RAM, 5, (m, now) -> {
            if (!filled[0]) {
                junk(m);
                filled[0] = true;
            }
        });
        assertEquals(0, run.status(), run.runner() + run.com1());
        assertTrue(run.com1().contains("Command line: " + cmdline + "\r\n"), run.com1());
        checkLoaded(run, kernelA, cmdline);
    }

    private static void checkLoadedQuick(Run run, String cmdline) {
        assertTrue(run.com1().contains("Command line: " + cmdline + "\r\n"), run.com1());
        assertEquals(MARK, ByteBuffer.wrap(run.machine().board().ram()).getInt(RESULTS + 16));
    }

    private String update(int status, String... args) throws IOException, InterruptedException {
        return update(status, Map.of(), args);
    }

    /** Runs flipboot-update with {@code env} added to the environment and checks its exit status. */
    private String update(int status, Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/bin/sh", FlipbootDisk.UPDATE.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().remove("FLIPBOOT_DEVICE");
        pb.environment().remove("FLIPBOOT_CMDLINE");
        pb.environment().remove("FLIPBOOT_PROC_CMDLINE");
        pb.environment().put("TMPDIR", dir.toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(status, p.waitFor(), output);
        return output;
    }

    private static boolean runs(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- the built Linux kernel from the disk ----

    private static final Path VMLINUX = Path.of("guest/linux/out/vmlinux");
    private static final Path TESTROOT = Path.of("guest/boot/out/testroot.ext4");

    /** Linux from slot A with no root file system: the kernel runs, reads the bootinfo and stops at mounting root. */
    @Test
    @Tag("linux")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void linuxStartsFromTheDiskAndLooksForItsRoot() throws IOException {
        assumeBuilt();
        assumeTrue(Files.isRegularFile(VMLINUX), VMLINUX + " is not built (guest/linux/build.sh)");
        byte[] vmlinux = Files.readAllBytes(VMLINUX);
        String cmdline = "root=/dev/fhda3 console=ttyS0,115200";
        Path disk = disk(0, new Slot(vmlinux, cmdline), Slot.EMPTY);
        Run run = boot(disk, 128 << 20, 15, null);
        String com1 = run.com1();
        Files.writeString(Path.of("build").resolve("flipboot-linux-noroot.txt"), com1, StandardCharsets.ISO_8859_1);
        assertTrue(com1.contains("Starting the kernel at 0x00002000, bootinfo 0x"), com1);
        assertTrue(com1.contains("Kernel command line: " + cmdline), com1);
        assertTrue(com1.contains("Memory: "), com1);
        assertTrue(com1.contains("random: crng init done"), com1);
        assertTrue(com1.contains("VFS: Unable to mount root fs on \"/dev/fhda3\""), com1);
    }

    /** Linux from slot A with an ext4 root on partition 3 (no initramfs): to a busybox shell, then power off. */
    @Test
    @Tag("linux")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void linuxBootsFromTheDiskToAShellOnItsRootPartition() throws IOException {
        assumeBuilt();
        assumeTrue(Files.isRegularFile(VMLINUX), VMLINUX + " is not built (guest/linux/build.sh)");
        assumeTrue(Files.isRegularFile(TESTROOT), TESTROOT + " is not built (guest/boot/build.sh --testroot)");
        byte[] vmlinux = Files.readAllBytes(VMLINUX);
        String cmdline = "root=/dev/fhda3 rw rootfstype=ext4 init=/init console=ttyS0,115200";
        Path disk = dir.resolve("linux.img");
        byte[] root = Files.readAllBytes(TESTROOT);
        FlipbootDisk.write(disk, (long) FlipbootDisk.P3_START * FlipbootDisk.SECTOR + root.length, stage1, stage2, 0,
                new Slot(vmlinux, cmdline), Slot.EMPTY, root);
        String commands = "cat /proc/cmdline; grep ' / ' /proc/mounts; dmesg | grep -E 'crng init|EXT4-fs \\(fhda3\\)'; "
                + "echo done $((40+2)); poweroff -f\n";
        StringBuilder seen = new StringBuilder();
        boolean[] typed = {false};
        int[] at = {0};
        byte[] input = commands.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        HeadlessPc.TickListener listener = (m, now) -> {
            seen.setLength(0);
            seen.append(out.toString(StandardCharsets.ISO_8859_1));
            Uart16550 com1 = m.com(0);
            if (!typed[0] && seen.indexOf("/ # ") >= 0)
                typed[0] = true;
            if (typed[0] && at[0] < input.length && com1.hostPending() == 0)
                at[0] += com1.hostWrite(input, at[0], Math.min(Uart16550.FIFO_DEPTH, input.length - at[0]));
        };
        String[] args = {"--cpu", "68040", "--ram", "128M", "--hdd", disk.toString(), "--epoch", Long.toString(EPOCH),
                "--max-seconds", "120"};
        int status = HeadlessPc.run(args, InputStream.nullInputStream(), out,
                new PrintStream(err, true, StandardCharsets.UTF_8), listener);
        String com1 = out.toString(StandardCharsets.ISO_8859_1);
        Files.writeString(Path.of("build").resolve("flipboot-linux-shell.txt"), com1, StandardCharsets.ISO_8859_1);
        assertEquals(0, status, err.toString(StandardCharsets.UTF_8) + com1);
        assertTrue(com1.contains("\n" + cmdline + " flipboot.slot=A\r\n"), com1);
        assertTrue(com1.contains("\n/dev/root / ext4 rw"), com1);
        assertTrue(com1.contains("EXT4-fs (fhda3): mounted filesystem"), com1);
        assertTrue(com1.contains("random: crng init done"), com1);
        assertTrue(com1.contains("\ndone 42\r\n"), com1);
    }
}
