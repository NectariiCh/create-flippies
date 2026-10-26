package com.nectariich.create_flippies.content.computer.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Writes flipboot disk images (spec 12.7) the way guest/boot/mkdisk.py does, byte for byte, so the
 * boot chain's tests run without Python: stage 1 and the MBR in sector 0, stage 2 in sectors
 * 1..127, the boot area in partition 1 (two header copies, slot A's kernel at 1 MiB, slot B's at
 * 16 MiB), swap in partition 2 and the root file system in partition 3. The header format is in
 * guest/boot/flipboot.c.
 */
final class FlipbootDisk {

    private FlipbootDisk() {
    }

    static final Path STAGE1 = Path.of("guest/boot/out/stage1.bin");
    static final Path STAGE2 = Path.of("guest/boot/out/stage2.bin");
    static final Path MKDISK = Path.of("guest/boot/mkdisk.py");
    static final Path UPDATE = Path.of("guest/boot/flippies-boot/usr/sbin/flipboot-update");

    static final int SECTOR = 512;
    static final int P1_START = 2048, P1_SECTORS = 65536;
    static final int P2_START = P1_START + P1_SECTORS, P2_SECTORS = 262144;
    static final int P3_START = P2_START + P2_SECTORS;
    static final int HEADER = 4096, SLOT_RECORD = 32, SLOT_SIZE = 544, CMDLINE_FIELD = 512;
    static final int[] SLOT_OFFSETS = {1 << 20, 16 << 20};
    static final String DEFAULT_CMDLINE = "root=/dev/fhda3 rw console=tty0 console=ttyS0,115200";
    static final byte[] SIGNATURE = "FLPC".getBytes(StandardCharsets.US_ASCII);

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++)
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            TABLE[i] = c;
        }
    }

    /** The POSIX cksum CRC, as cksum prints it (unsigned). */
    static long cksum(byte[] data, int offset, int length) {
        int c = 0;
        for (int i = offset; i < offset + length; i++)
            c = (c << 8) ^ TABLE[((c >>> 24) ^ data[i]) & 0xFF];
        for (long n = length; n != 0; n >>>= 8)
            c = (c << 8) ^ TABLE[((c >>> 24) ^ (int) n) & 0xFF];
        return Integer.toUnsignedLong(~c);
    }

    static long cksum(byte[] data) {
        return cksum(data, 0, data.length);
    }

    /** One slot: the kernel's bytes (null for an empty slot) and its command line. */
    record Slot(byte[] kernel, String cmdline) {
        static final Slot EMPTY = new Slot(null, DEFAULT_CMDLINE);
    }

    static byte[] mbr(byte[] stage1, long sizeSectors) {
        if (stage1.length > 440)
            throw new IllegalArgumentException("stage 1 is " + stage1.length + " bytes");
        ByteBuffer b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN);
        b.put(stage1);
        b.position(440);
        b.put(SIGNATURE);
        b.position(446);
        entry(b, true, 0xDA, P1_START, P1_SECTORS);
        entry(b, false, 0x82, P2_START, P2_SECTORS);
        entry(b, false, 0x83, P3_START, sizeSectors - P3_START);
        b.put(510, (byte) 0x55).put(511, (byte) 0xAA);
        return b.array();
    }

    private static void entry(ByteBuffer b, boolean boot, int type, long start, long count) {
        b.put((byte) (boot ? 0x80 : 0)).put((byte) 0xFE).put((byte) 0xFF).put((byte) 0xFF);
        b.put((byte) type).put((byte) 0xFE).put((byte) 0xFF).put((byte) 0xFF);
        b.putInt((int) start).putInt((int) count);
    }

    /** A boot area header: active slot 0 (A) or 1 (B), generation, the two slots. */
    static byte[] header(int active, int generation, Slot a, Slot b) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN);
        h.put("FLBT".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(active).putInt(0).putInt(generation);
        Slot[] slots = {a, b};
        for (int i = 0; i < 2; i++) {
            if (slots[i].kernel() == null)
                continue;
            int r = SLOT_RECORD + SLOT_SIZE * i;
            h.putInt(r, SLOT_OFFSETS[i]).putInt(r + 4, slots[i].kernel().length).putInt(r + 8, (int) cksum(slots[i].kernel()));
            h.put(r + 32, slots[i].cmdline().getBytes(StandardCharsets.US_ASCII));
        }
        h.putInt(12, (int) cksum(h.array()));
        return h.array();
    }

    /** Writes a new image at {@code path}: as {@code mkdisk.py create} with the same arguments. */
    static void write(Path path, long sizeBytes, byte[] stage1, byte[] stage2, int active, Slot a, Slot b, byte[] root)
            throws IOException {
        if (stage2.length > 127 * SECTOR)
            throw new IllegalArgumentException("stage 2 is " + stage2.length + " bytes");
        if (root != null && root.length > sizeBytes - (long) P3_START * SECTOR)
            throw new IllegalArgumentException("the root image is larger than partition 3");
        byte[] header = header(active, 1, a, b);
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) {
            f.setLength(0);
            f.setLength(sizeBytes);
            f.write(mbr(stage1, sizeBytes / SECTOR));
            f.write(stage2);
            f.seek((long) P1_START * SECTOR);
            f.write(header);
            f.write(header);
            Slot[] slots = {a, b};
            for (int i = 0; i < 2; i++) {
                if (slots[i].kernel() != null) {
                    f.seek((long) P1_START * SECTOR + SLOT_OFFSETS[i]);
                    f.write(slots[i].kernel());
                }
            }
            if (root != null) {
                f.seek((long) P3_START * SECTOR);
                f.write(root);
            }
        }
    }
}
