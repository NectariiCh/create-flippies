package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.ContentSource;
import com.nectariich.create_flippies.content.computer.net.DeflatedChunks;
import com.nectariich.create_flippies.content.computer.net.Fdi;
import com.nectariich.create_flippies.content.computer.net.MemoryContent;
import com.nectariich.create_flippies.content.computer.net.Modem;
import com.nectariich.create_flippies.content.computer.net.PackContent;
import com.nectariich.create_flippies.content.computer.net.PackFiles;
import com.nectariich.create_flippies.content.computer.net.PackSet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The netinstall floppy of spec 12.5 on the 68040 PC, cold-booted from its boot ROM with a modem
 * on the network card: the floppy brings up the network (DHCP, DNS for get.flip), shows the
 * catalogue, checks the HDD's size, asks for YES, downloads a sparse {@code .fdi} into the HDD
 * and reads every chunk back. Keys are scripted on the PS/2 keyboard and the screens are read
 * from COM1, where the floppy mirrors them.
 *
 * The HDD starts full of a pattern, so the tests see which chunks the floppy wrote: with YES the
 * chunks the image leaves out keep the pattern (a blank HDD reads zeros there anyway), with WIPE
 * they become zeros; nothing past the image changes. One test drops the modem's link midway
 * (the download resumes with Range) and serves one chunk corrupted once (it is fetched again);
 * another corrupts a written chunk on the HDD during the verify pass (fetched again too).
 *
 * Needs guest/netinstall/out/netinstall.img (guest/netinstall/build.sh) and is skipped without it.
 */
class NetinstallE2eTest {

    static final Path FLOPPY = Path.of("guest/netinstall/out/netinstall.img");
    private static final int RAM = 32 << 20, CLOCK = 25_000_000;
    private static final long PC_MAC = 0x02_46_4C_50_43_01L, EPOCH = 1_792_944_000L;
    private static final long HDD_BYTES = 32L << 20;
    private static final long BUDGET_NANOS = 10_000_000_000L;
    private static final int TICKS_PER_SECOND = (int) (1_000_000_000L / Machine.TICK_NANOS);
    private static final String IMAGE_PATH = "get.flip/images/test.fdi";
    /** One chunk in seven is all zeros, so absent from the .fdi; the image's last chunk is 3 sectors. */
    private static final int ZERO_EVERY = 7, ZERO_AT = 3;

    private static byte[] floppy;

    @BeforeAll
    static void load() throws IOException {
        if (Files.isRegularFile(FLOPPY))
            floppy = Files.readAllBytes(FLOPPY);
    }

    private static void assumeBuilt() {
        assumeTrue(floppy != null, FLOPPY + " is not built (guest/netinstall/build.sh)");
    }

    /** A raw image of {@code chunks - 1} whole chunks and a last one of 3 sectors, every 7th chunk zero. */
    private static byte[] rawImage(int chunks, long seed) {
        byte[] raw = new byte[(chunks - 1) * Fdi.CHUNK + 3 * Fdi.SECTOR];
        new Random(seed).nextBytes(raw);
        for (int c = 0; c < chunks - 1; c++)
            if (c % ZERO_EVERY == ZERO_AT)
                Arrays.fill(raw, c * Fdi.CHUNK, (c + 1) * Fdi.CHUNK, (byte) 0);
        return raw;
    }

    /** The pattern the HDD holds before the install, different in every sector. */
    private static byte garbage(long offset) {
        return (byte) (0xA5 ^ offset >>> 9 ^ offset);
    }

    /** A content source that serves one byte of one file flipped, once, the first time a reader passes it. */
    private static final class FlakySource implements ContentSource {
        final MemoryContent inner = new MemoryContent();
        String path;
        long corruptAt = -1;
        int corrupted;

        @Override
        public Set<String> hosts() {
            return inner.hosts();
        }

        @Override
        public long size(String p) {
            return inner.size(p);
        }

        @Override
        public InputStream open(String p) {
            InputStream in = inner.open(p);
            if (in == null || !p.equals(path))
                return in;
            byte[] data;
            try {
                data = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new ByteArrayInputStream(data) {
                @Override
                public synchronized int read(byte[] b, int off, int len) {
                    int at = pos;
                    int n = super.read(b, off, len);
                    if (n > 0 && corruptAt >= at && corruptAt < at + n) {
                        b[off + (int) (corruptAt - at)] ^= 0x40;
                        corruptAt = -1;
                        corrupted++;
                    }
                    return n;
                }
            };
        }
    }

    /** A 68040 PC with the floppy, an HDD full of the pattern, a keyboard and a modem; ticks as the game does. */
    private static final class Rig {
        final SparseDiskImage hdd = new SparseDiskImage(HDD_BYTES);
        final Machine m;
        final Modem modem;
        final StringBuilder com1 = new StringBuilder();
        final byte[] buf = new byte[4096];
        long ticks;
        int linkDownTicks;

        Rig(ContentSource content) {
            byte[] chunk = new byte[(int) SparseDiskImage.CHUNK_SIZE];
            for (long at = 0; at < HDD_BYTES; at += chunk.length) {
                for (int i = 0; i < chunk.length; i++)
                    chunk[i] = garbage(at + i);
                hdd.write(at / 512, chunk.length / 512, chunk, 0);
            }
            hdd.takeDirtyChunks();
            m = new Machine(new MachineConfig(CpuModel.CPU_68040, CLOCK, RAM, hdd, BootRoms.image(CpuModel.CPU_68040),
                    () -> EPOCH, null, false, PC_MAC), StandardCpus.INSTANCE);
            PagedDiskImage f = new PagedDiskImage(UUID.randomUUID(), PagedDiskImage.FLOPPY_CAPACITY, c -> null, 64);
            f.write(0, floppy.length / 512, floppy, 0);
            m.floppy().insert(f, true);
            m.ps2().setDevice(0, Ps2DeviceKind.KEYBOARD);
            m.powerOn();
            m.com(0).setHostConnected(true);
            modem = new Modem(HeadlessPc.MODEM_MAC, new Catalogue(content));
        }

        double seconds() {
            return ticks / (double) TICKS_PER_SECOND;
        }

        void tick() {
            m.runTick(Machine.TICK_NANOS, BUDGET_NANOS);
            ticks++;
            for (int n; (n = m.com(0).hostRead(buf, 0, buf.length)) > 0; )
                com1.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            if (linkDownTicks > 0) {
                linkDownTicks--;
                modem.setLink(false);
                m.nic().setLink(false);
            } else {
                modem.exchange(m.nic());
                modem.tick();
            }
            if (m.state() == MachineState.HALTED || m.state() == MachineState.OFF_BY_GUEST)
                fail("the PC stopped (" + m.state() + ", " + m.haltReason() + ")\n" + tail());
        }

        String tail() {
            return com1.length() > 4000 ? com1.substring(com1.length() - 4000) : com1.toString();
        }

        void until(String what, BooleanSupplier done, double maxSeconds) {
            long max = ticks + (long) (maxSeconds * TICKS_PER_SECOND);
            while (!done.getAsBoolean()) {
                if (ticks >= max)
                    fail("not " + what + " after " + maxSeconds + " s emulated\n" + tail());
                tick();
            }
        }

        /** Runs until COM1 shows {@code text} after position {@code from}; returns where it ends. */
        int untilCom1(String text, int from, double maxSeconds) {
            until("\"" + text + "\" on COM1", () -> com1.indexOf(text, from) >= 0, maxSeconds);
            return com1.indexOf(text, from) + text.length();
        }

        void key(Ps2Key k) {
            m.keyboard(0).keyDown(k);
            m.keyboard(0).keyUp(k);
            tick();
        }

        void type(String text) {
            for (char c : text.toCharArray())
                key(Ps2Key.valueOf(String.valueOf(c)));
        }

        byte[] hddBytes(long at, int length) {
            byte[] b = new byte[length];
            hdd.read(at / 512, length / 512, b, 0);
            return b;
        }
    }

    /** The HDD holds the image's present chunks; absent ones are zeros or the old pattern; past the image, the pattern. */
    private static void checkHdd(Rig rig, byte[] raw, boolean wiped) {
        Fdi.Header h = header(raw);
        long imageBytes = h.imageBytes();
        Set<Integer> present = new java.util.HashSet<>();
        for (Fdi.Entry e : h.entries())
            present.add(e.index());
        for (int c = 0; c < h.chunkCount(); c++) {
            long at = (long) c * Fdi.CHUNK;
            int len = (int) Math.min(Fdi.CHUNK, imageBytes - at);
            byte[] got = rig.hddBytes(at, len);
            byte[] want;
            if (present.contains(c) || wiped) {
                want = Arrays.copyOfRange(raw, (int) at, (int) at + len);
            } else {
                want = new byte[len];
                for (int i = 0; i < len; i++)
                    want[i] = garbage(at + i);
            }
            assertArrayEquals(want, got, "chunk " + c + (present.contains(c) ? " (present)" : " (absent)"));
        }
        // the rest of the image's last chunk and everything after the image: untouched
        long end = imageBytes;
        byte[] rest = rig.hddBytes(end, (int) (HDD_BYTES - end));
        for (int i = 0; i < rest.length; i++)
            if (rest[i] != garbage(end + i))
                fail("the HDD changed past the image, at byte " + (end + i));
    }

    private static Fdi.Header header(byte[] raw) {
        try {
            return Fdi.readHeader(new ByteArrayInputStream(Fdi.write(raw, "Test image")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String index(byte[] fdi, byte[] big) {
        return "# netinstall test catalogue\n"
                + "file /readme.txt 6 Read me\n"
                + "image /images/test.fdi " + fdi.length + " Test image\n"
                + "image /images/big.fdi " + big.length + " Too big\n";
    }

    /** An .fdi of a 64 MiB image with no chunk present: only its header, for the size check. */
    private static byte[] bigFdi() {
        return Fdi.header(new Fdi.Header((64L << 20) / Fdi.SECTOR, "Too big", List.of()));
    }

    /** Boots, waits for the catalogue, picks entry {@code down} rows below the first. */
    private static int choose(Rig rig, int down, int from) {
        int at = rig.untilCom1("Choose what to install", from, 60);
        for (int i = 0; i < down; i++)
            rig.key(Ps2Key.DOWN);
        rig.key(Ps2Key.ENTER);
        return at;
    }

    private static final Pattern TIMES = Pattern.compile(
            "Download and write: ([0-9.]+) s, ([0-9.]+) MB/s\\. Verify: ([0-9.]+) s, ([0-9.]+) MB/s\\.");

    private static String measured(Rig rig, long hostNanos) {
        Matcher t = TIMES.matcher(rig.com1);
        assertTrue(t.find(), "the done screen gives the times\n" + rig.tail());
        return String.format(Locale.ROOT, "download and write %s s (%s MB/s), verify %s s (%s MB/s); "
                        + "%.1f s emulated in %.1f s host", t.group(1), t.group(2), t.group(3), t.group(4),
                rig.seconds(), hostNanos / 1e9);
    }

    @Test
    void installsAnImageFromTheCatalogue() {
        assumeBuilt();
        byte[] raw = rawImage(384, 1);                  // 24 MiB
        byte[] fdi = Fdi.write(raw, "Test image");
        byte[] big = bigFdi();
        // stored deflated, as the game's jar holds large files
        MemoryContent content = new MemoryContent().put("get.flip/readme.txt", "hello\n")
                .putAll(DeflatedChunks.store(IMAGE_PATH, fdi)).put("get.flip/images/big.fdi", big);
        content.put("get.flip/index.txt", index(fdi, big));
        Rig rig = new Rig(content);
        long host = System.nanoTime();

        int at = rig.untilCom1("Network: address 10.88.0.2, gateway 10.88.0.1, DNS 10.88.0.1", 0, 30);
        // the plain file: refused
        at = choose(rig, 0, at);
        at = rig.untilCom1("This is a plain file, not a disk image.", at, 10);
        rig.key(Ps2Key.ENTER);
        // the image larger than the HDD: refused after its header
        at = choose(rig, 2, at);
        at = rig.untilCom1("The image does not fit on this HDD.", at, 10);
        at = rig.untilCom1("The image needs 64.0 MB; the HDD holds 32.0 MB.", at, 1);
        rig.key(Ps2Key.ENTER);
        // the image
        at = choose(rig, 1, at);
        at = rig.untilCom1("This erases the HDD. Type YES to continue.", at, 10);
        assertTrue(rig.com1.indexOf("HDD:   FLIPPIES HDD") > 0, rig.tail());
        rig.type("YES");
        rig.key(Ps2Key.ENTER);
        at = rig.untilCom1("netinstall: done", at, 120);
        assertTrue(rig.com1.indexOf("CRC errors in transit 0, fetched again 0") > 0, rig.tail());
        assertTrue(rig.com1.indexOf("(the others left as they were)") > 0, rig.tail());
        assertEquals(-1, rig.com1.indexOf("problem:"), rig.tail());
        System.out.println("netinstall, 24 MiB image (" + header(raw).entries().size() + " chunks present): "
                + measured(rig, System.nanoTime() - host) + "; modem retransmissions " + rig.modem.retransmissions());
        checkHdd(rig, raw, false);
        assertEquals(0, rig.modem.retransmissions(), "nothing lost on the link");

        // Enter restarts through SysCon (POWER 0x7777): the ROM boots the floppy again
        int banners = count(rig.com1, "Booting from floppy");
        rig.key(Ps2Key.ENTER);
        rig.until("the restart", () -> count(rig.com1, "Booting from floppy") > banners, 10);
    }

    /** A server with no OS pack: the jar's built-in index lists nothing, and the floppy says why. */
    @Test
    void noOsPackIsExplained() {
        assumeBuilt();
        Rig rig = new Rig(new PackSet(List.of(), ClasspathContent.bundled(), w -> fail(w)));
        int at = rig.untilCom1("No OS is available on this server.", 0, 60);
        rig.untilCom1("Ask the server owner to install an OS pack.", at, 1);
    }

    /** The image as an OS pack carries it (spec 12.4.3): deflated chunks stored in a .flippack, over the built-in pages. */
    @Test
    void installsFromAPack(@TempDir Path dir) throws IOException {
        assumeBuilt();
        byte[] raw = rawImage(64, 5);                   // 4 MiB
        byte[] fdi = Fdi.write(raw, "Pack image");
        var files = new java.util.LinkedHashMap<>(DeflatedChunks.store("get.flip/images/pack.fdi", fdi));
        files.put("get.flip/index.txt", ("image /images/pack.fdi " + fdi.length + " Pack image\n").getBytes(StandardCharsets.UTF_8));
        Path pack = PackFiles.write(dir.resolve("os.flippack"), "test-os", List.of("get.flip"), files);
        PackSet packs = new PackSet(List.of(PackContent.open(pack)), ClasspathContent.bundled(), w -> fail(w));
        Rig rig = new Rig(packs);

        int at = choose(rig, 0, 0);
        at = rig.untilCom1("Type YES to continue.", at, 10);
        rig.type("YES");
        rig.key(Ps2Key.ENTER);
        rig.untilCom1("netinstall: done", at, 60);
        assertTrue(rig.com1.indexOf("CRC errors in transit 0, fetched again 0") > 0, rig.tail());
        checkHdd(rig, raw, false);
        packs.retire();
    }

    @Test
    void resumesAfterTheLinkDropsAndFetchesACorruptChunkAgain() {
        assumeBuilt();
        byte[] raw = rawImage(160, 2);                  // 10 MiB
        byte[] fdi = Fdi.write(raw, "Test image");
        byte[] big = bigFdi();
        Fdi.Header h = header(raw);
        FlakySource content = new FlakySource();
        content.inner.put("get.flip/readme.txt", "hello\n").put(IMAGE_PATH, fdi).put("get.flip/images/big.fdi", big)
                .put("get.flip/index.txt", index(fdi, big));
        content.path = IMAGE_PATH;
        content.corruptAt = h.dataOffset(10) + 1000;
        Rig rig = new Rig(content);
        long host = System.nanoTime();

        int at = choose(rig, 1, 0);
        at = rig.untilCom1("Type YES to continue.", at, 10);
        rig.type("WIPE");
        rig.key(Ps2Key.ENTER);
        at = rig.untilCom1("Downloading and writing the image", at, 60);
        long framesAtStart = rig.modem.framesOut();
        long half = fdi.length / 2 / 1460;
        rig.until("half the download", () -> rig.modem.framesOut() - framesAtStart > half, 60);
        rig.linkDownTicks = 10;                          // half a second without the modem
        at = rig.untilCom1("netinstall: done", at, 120);

        assertEquals(1, content.corrupted, "one chunk was served corrupted");
        String corrupt = "problem: Chunk " + h.entries().get(10).index() + " arrived with a CRC error.";
        assertTrue(rig.com1.indexOf(corrupt) > 0, rig.tail());
        assertTrue(rig.com1.indexOf("problem: The network link went down.") > 0, rig.tail());
        assertTrue(rig.com1.indexOf("CRC errors in transit 1, fetched again 0") > 0, rig.tail());
        assertTrue(rig.com1.indexOf("(the others zeroed)") > 0, rig.tail());
        System.out.println("netinstall with a dropped link and a corrupt chunk, 10 MiB image: "
                + measured(rig, System.nanoTime() - host) + "; modem retransmissions " + rig.modem.retransmissions());
        checkHdd(rig, raw, true);
    }

    @Test
    void aChunkThatReadsBackWrongIsFetchedAgain() {
        assumeBuilt();
        byte[] raw = rawImage(96, 3);                   // 6 MiB
        byte[] fdi = Fdi.write(raw, "Test image");
        byte[] big = bigFdi();
        Fdi.Header h = header(raw);
        MemoryContent content = new MemoryContent().put("get.flip/readme.txt", "hello\n").put(IMAGE_PATH, fdi)
                .put("get.flip/images/big.fdi", big).put("get.flip/index.txt", index(fdi, big));
        Rig rig = new Rig(content);
        rig.linkDownTicks = 60;                          // the modem comes up 3 s after power-on

        int at = rig.untilCom1("Network: no link.", 0, 10);
        at = choose(rig, 1, at);
        at = rig.untilCom1("Type YES to continue.", at, 10);
        rig.type("YES");
        rig.key(Ps2Key.ENTER);
        at = rig.untilCom1("Verifying: reading every chunk back", at, 60);
        // the last whole chunk present, damaged on the HDD before the verify pass reaches it
        Fdi.Entry victim = h.entries().get(h.entries().size() - 2);
        long where = (long) victim.index() * Fdi.CHUNK + 4096;
        byte[] sector = rig.hddBytes(where, 512);
        sector[17] ^= 1;
        rig.hdd.write(where / 512, 1, sector, 0);
        at = rig.untilCom1("netinstall: done", at, 60);
        assertTrue(rig.com1.indexOf("refetch: Chunk " + victim.index() + " read back with a CRC error.") > 0,
                rig.tail());
        assertTrue(rig.com1.indexOf("CRC errors in transit 0, fetched again 1") > 0, rig.tail());
        checkHdd(rig, raw, false);
    }

    @Test
    void moreBadChunksThanTheListHoldsAreAllFetchedAgain() {
        assumeBuilt();
        byte[] raw = rawImage(340, 4);                  // 21.25 MiB, 291 chunks present
        byte[] fdi = Fdi.write(raw, "Test image");
        byte[] big = bigFdi();
        Fdi.Header h = header(raw);
        MemoryContent content = new MemoryContent().put("get.flip/readme.txt", "hello\n").put(IMAGE_PATH, fdi)
                .put("get.flip/images/big.fdi", big).put("get.flip/index.txt", index(fdi, big));
        Rig rig = new Rig(content);

        int at = choose(rig, 1, 0);
        at = rig.untilCom1("Type YES to continue.", at, 10);
        rig.type("YES");
        rig.key(Ps2Key.ENTER);
        at = rig.untilCom1("Verifying: reading every chunk back", at, 60);
        // 281 chunks damaged on the HDD before the verify pass reaches them: more than its list of 256
        List<Fdi.Entry> victims = h.entries().subList(10, h.entries().size());
        assertTrue(victims.size() > 256);
        for (Fdi.Entry victim : victims) {
            long where = (long) victim.index() * Fdi.CHUNK + 512;
            byte[] sector = rig.hddBytes(where, 512);
            sector[3] ^= 1;
            rig.hdd.write(where / 512, 1, sector, 0);
        }
        at = rig.untilCom1("netinstall: done", at, 120);
        assertTrue(rig.com1.indexOf("CRC errors in transit 0, fetched again " + victims.size()) > 0, rig.tail());
        checkHdd(rig, raw, false);
    }

    private static int count(CharSequence s, String what) {
        int n = 0;
        for (int i = 0; (i = s.toString().indexOf(what, i)) >= 0; i += what.length())
            n++;
        return n;
    }
}
