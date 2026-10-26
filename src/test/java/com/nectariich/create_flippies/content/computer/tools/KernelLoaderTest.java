package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * {@link KernelLoader} against small hand-made ELF files laid out like the kernel's: a first
 * segment from address 0 whose bytes below 0x1000 are the ELF header and padding, code at
 * 0x1000 followed by BSS, and a second segment (the kernel's init section) with a zero tail
 * ending at {@code _end}.
 */
class KernelLoaderTest {

    private static final int RAM = 4 << 20;
    private static final byte[] CODE = { 0x4E, 0x71, 0x4E, 0x71, 0x60, (byte) 0xFE };   // nop, nop, bra.s *
    private static final byte[] DATA = "init data".getBytes(StandardCharsets.ISO_8859_1);
    private static final int BSS = 0x200;

    /** Code at 0x1000 with BSS after it, data at 0x3000 with a zero tail to 0x4000 = _end. */
    private static TestElf kernel() {
        byte[] padding = new byte[0x1000 - 116];
        Arrays.fill(padding, (byte) 0xAB);
        return new TestElf().entry(0x1000)
                .put(116, padding)
                .put(0x1000, CODE)
                .segment(0, 0, 0x1000 + CODE.length, 0x1000 + CODE.length + BSS)
                .put(0x2000, DATA)
                .segment(0x2000, 0x3000, DATA.length, 0x1000);
    }

    private static byte[] ram() {
        byte[] ram = new byte[RAM];
        Arrays.fill(ram, (byte) 0x55);
        return ram;
    }

    @Test
    void segmentsLoadFromPageOneAndBssIsZeroed() {
        byte[] ram = ram();
        KernelLoader.Loaded k = KernelLoader.load(ram, kernel().symbol("_start", 0x1000).symbol("_end", 0x4000).build(),
                null, "console=ttyS0");

        assertEquals(0x1000, k.entry());
        assertEquals(0x4000, k.end());
        assertEquals(0x4000, k.segmentsEnd());
        byte[] page0 = new byte[0x1000];
        Arrays.fill(page0, (byte) 0x55);
        assertArrayEquals(page0, Arrays.copyOf(ram, 0x1000), "page 0 (the vector table) is not loaded");
        assertArrayEquals(CODE, Arrays.copyOfRange(ram, 0x1000, 0x1000 + CODE.length));
        assertArrayEquals(new byte[BSS], Arrays.copyOfRange(ram, 0x1000 + CODE.length, 0x1000 + CODE.length + BSS));
        assertEquals(0x55, ram[0x1000 + CODE.length + BSS], "nothing is written past a segment");
        assertArrayEquals(DATA, Arrays.copyOfRange(ram, 0x3000, 0x3000 + DATA.length));
        assertArrayEquals(new byte[0x1000 - DATA.length], Arrays.copyOfRange(ram, 0x3000 + DATA.length, 0x4000));
    }

    @Test
    void bootinfoStartsAtEndWithEveryRecord() {
        byte[] ram = ram();
        byte[] initrd = new byte[5000];
        Arrays.fill(initrd, (byte) 7);
        String cmdline = "console=ttyS0,115200 rdinit=/init";
        KernelLoader.Loaded k = KernelLoader.load(ram, kernel().symbol("_end", 0x4000).build(), initrd, cmdline);

        ByteBuffer bi = ByteBuffer.wrap(ram);
        int at = 0x4000;
        int[][] expected = {
                { KernelLoader.BI_MACHTYPE, 8, 15 },
                { KernelLoader.BI_CPUTYPE, 8, 4 },
                { KernelLoader.BI_FPUTYPE, 8, 4 },
                { KernelLoader.BI_MMUTYPE, 8, 4 },
                { KernelLoader.BI_MEMCHUNK, 12, 0, RAM },
                { KernelLoader.BI_RAMDISK, 12, k.initrdAddress(), 5000 },
        };
        for (int[] record : expected) {
            assertEquals(record[0], bi.getShort(at), "tag at 0x" + Integer.toHexString(at));
            assertEquals(record[1], bi.getShort(at + 2), "size of tag " + record[0]);
            for (int i = 2; i < record.length; i++)
                assertEquals(record[i], bi.getInt(at + 4 * (i - 1)), "value " + (i - 1) + " of tag " + record[0]);
            at += record[1];
        }
        assertEquals(KernelLoader.BI_COMMAND_LINE, bi.getShort(at));
        int size = bi.getShort(at + 2);
        assertEquals(0, size % 4, "records are padded to 4 bytes");
        assertTrue(size >= 4 + cmdline.length() + 1);
        assertEquals(cmdline + "\0", new String(ram, at + 4, cmdline.length() + 1, StandardCharsets.ISO_8859_1));
        at += size;
        assertEquals(KernelLoader.BI_LAST, bi.getShort(at));
        assertEquals(4, bi.getShort(at + 2));
        assertEquals(at + 4, k.bootinfoEnd());

        // the initrd: page aligned at the top of RAM
        assertEquals((RAM - 5000) & -4096, k.initrdAddress());
        assertEquals(5000, k.initrdSize());
        assertArrayEquals(initrd, Arrays.copyOfRange(ram, k.initrdAddress(), k.initrdAddress() + 5000));
    }

    @Test
    void withoutASymbolTableEndIsTheLastSegmentRoundedUpToAPage() {
        byte[] padding = new byte[0x1000 - 116];
        byte[] elf = new TestElf().entry(0x1000).put(116, padding).put(0x1000, CODE)
                .segment(0, 0, 0x1000 + CODE.length, 0x2F00).build();
        byte[] ram = ram();
        KernelLoader.Loaded k = KernelLoader.load(ram, elf, null, "");

        assertEquals(0x3000, k.end());
        assertEquals(KernelLoader.BI_MACHTYPE, ByteBuffer.wrap(ram).getShort(0x3000));
        // no initrd: no BI_RAMDISK, and the empty command line is a single NUL
        assertEquals(0, k.initrdSize());
        assertEquals(0x3000 + 4 * 8 + 12 + 8 + 4, k.bootinfoEnd());
    }

    @Test
    void theEndSymbolWinsOverTheSegments() {
        byte[] ram = ram();
        KernelLoader.Loaded k = KernelLoader.load(ram, kernel().symbol("_end", 0x5000).build(), null, "x");

        assertEquals(0x5000, k.end());
        assertEquals(0x4000, k.segmentsEnd());
        assertEquals(KernelLoader.BI_MACHTYPE, ByteBuffer.wrap(ram).getShort(0x5000));
    }

    @Test
    void refusesWhatItCannotLoad() {
        byte[] good = kernel().build();
        assertThrows(IllegalArgumentException.class, () -> KernelLoader.load(ram(), new byte[100], null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(ram(), kernel().dataEncoding(1).build(), null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(ram(), kernel().machine(62).build(), null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(ram(), kernel().type(3).build(), null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(new byte[0x3800], good, null, ""), "a segment beyond RAM");
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(ram(), new TestElf().entry(0x1000).build(), null, ""), "no PT_LOAD");
        assertThrows(IllegalArgumentException.class,
                () -> KernelLoader.load(ram(), good, null, "x".repeat(KernelLoader.COMMAND_LINE_SIZE)));
        KernelLoader.load(ram(), good, null, "x".repeat(KernelLoader.COMMAND_LINE_SIZE - 1));
        // the initrd must leave TABLE_ROOM free above the page after the bootinfo (0x5000 here)
        // for head.S's page tables
        int fits = RAM - 0x5000 - KernelLoader.TABLE_ROOM;
        assertThrows(IllegalArgumentException.class, () -> KernelLoader.load(ram(), good, new byte[fits + 1], ""));
        assertEquals(0x5000 + KernelLoader.TABLE_ROOM, KernelLoader.load(ram(), good, new byte[fits], "").initrdAddress());
    }
}
