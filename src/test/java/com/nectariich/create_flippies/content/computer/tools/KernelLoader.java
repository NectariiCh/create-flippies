package com.nectariich.create_flippies.content.computer.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Puts a Linux kernel into a 68040 PC's RAM the way the boot chain's second stage will (spec
 * 12.6, 12.7), so the headless runner can boot a kernel without a disk. The rules, checked in the
 * pinned kernel (guest/linux/NOTES.md):
 * <ul>
 * <li>The kernel is an ELF file: 32-bit, big-endian, machine 68k, an executable. Each PT_LOAD
 * segment is copied to its physical address, but only from max(p_paddr, 0x1000) on, with the file
 * offset advanced by the same amount: page 0 holds the vector table, and the first segment's
 * bytes below 0x1000 are only the ELF header and padding.</li>
 * <li>Each segment is zeroed from p_filesz to p_memsz: head.S does not clear BSS.</li>
 * <li>The bootinfo records start exactly at {@code _end}, where head.S looks for them: the
 * symbol when the ELF has a symbol table, else the end of the last segment rounded up to a page
 * (the two agree for the kernels built so far). Records, each a big-endian tag, the record's whole
 * size and its data, padded to 4 bytes: BI_MACHTYPE (MACH_FLIPPIES), BI_CPUTYPE, BI_FPUTYPE and
 * BI_MMUTYPE (the 68040 bit, 4, in each), one BI_MEMCHUNK (0, the RAM size), BI_RAMDISK when there
 * is an initrd, BI_COMMAND_LINE, then BI_LAST.</li>
 * <li>head.S puts its early page tables on the pages right after the records, so an initrd goes at
 * the top of RAM, page aligned, and must leave at least {@link #TABLE_ROOM} free below it.</li>
 * </ul>
 * The entry point is the ELF entry ({@code _start}); the caller starts the CPU there in supervisor
 * mode with the MMU off.
 */
final class KernelLoader {

    private KernelLoader() {
    }

    /** Bootinfo tags (arch/m68k/include/uapi/asm/bootinfo.h). */
    static final int BI_LAST = 0x0000, BI_MACHTYPE = 0x0001, BI_CPUTYPE = 0x0002, BI_FPUTYPE = 0x0003,
            BI_MMUTYPE = 0x0004, BI_MEMCHUNK = 0x0005, BI_RAMDISK = 0x0006, BI_COMMAND_LINE = 0x0007;
    /** Our machine type, after upstream's last (MACH_VIRT, 14). */
    static final int MACH_FLIPPIES = 15;
    /** CPU_68040, FPU_68040 and MMU_68040: bit 2 of each mask. */
    static final int TYPE_68040 = 4;
    /** COMMAND_LINE_SIZE on m68k, terminating NUL included. */
    static final int COMMAND_LINE_SIZE = 256;
    /** Nothing is loaded below this: page 0 is the vector table. */
    static final int LOAD_FLOOR = 0x1000;
    static final int PAGE = 4096;
    /** Room left between the bootinfo and an initrd for head.S's page tables. */
    static final int TABLE_ROOM = 1 << 20;

    private static final int PT_LOAD = 1, SHT_SYMTAB = 2, EM_68K = 4, ET_EXEC = 2;

    /**
     * Where things went: the entry point, {@code _end} (where the bootinfo starts) and the first
     * byte after the bootinfo, the initrd's address and size (0 and 0 without one), and the end
     * of the last segment rounded up to a page, for a check against the symbol.
     */
    record Loaded(int entry, int end, int bootinfoEnd, int initrdAddress, int initrdSize, int segmentsEnd) {
    }

    /**
     * Loads {@code elf} into {@code ram} (the PC's RAM, from physical address 0), with an optional
     * {@code initrd} and the kernel command line. Throws IllegalArgumentException, with a message
     * for the user, for anything that is not a loadable 68k kernel or does not fit.
     */
    static Loaded load(byte[] ram, byte[] elf, byte[] initrd, String commandLine) {
        ByteBuffer f = ByteBuffer.wrap(elf).order(ByteOrder.BIG_ENDIAN);
        check(elf.length >= 52 && f.getInt(0) == 0x7F454C46, "not an ELF file");
        check(elf[4] == 1 && elf[5] == 2, "not a 32-bit big-endian ELF file");
        check(f.getShort(18) == EM_68K, "not a 68k ELF file (machine " + f.getShort(18) + ")");
        check(f.getShort(16) == ET_EXEC, "not an executable ELF file");
        int entry = f.getInt(24);
        int phoff = f.getInt(28), phentsize = f.getShort(42) & 0xFFFF, phnum = f.getShort(44) & 0xFFFF;
        check(phentsize >= 32 && fits(elf, phoff, (long) phentsize * phnum), "bad program header table");

        long lastEnd = 0;
        int loaded = 0;
        for (int i = 0; i < phnum; i++) {
            int ph = phoff + i * phentsize;
            if (f.getInt(ph) != PT_LOAD)
                continue;
            long offset = Integer.toUnsignedLong(f.getInt(ph + 4));
            long paddr = Integer.toUnsignedLong(f.getInt(ph + 12));
            long filesz = Integer.toUnsignedLong(f.getInt(ph + 16));
            long memsz = Integer.toUnsignedLong(f.getInt(ph + 20));
            check(filesz <= memsz, "segment " + i + " is larger in the file than in memory");
            check(offset + filesz <= elf.length, "segment " + i + " runs past the end of the file");
            check(paddr + memsz <= ram.length, String.format(
                    "segment %d (0x%x to 0x%x) does not fit in %d KiB of RAM", i, paddr, paddr + memsz, ram.length >> 10));
            long start = Math.max(paddr, LOAD_FLOOR);
            long skip = start - paddr;
            long end = paddr + memsz;
            if (start < end) {
                long copy = Math.max(0, filesz - skip);
                System.arraycopy(elf, (int) (offset + skip), ram, (int) start, (int) copy);
                Arrays.fill(ram, (int) (start + copy), (int) end, (byte) 0);
            }
            lastEnd = Math.max(lastEnd, end);
            loaded++;
        }
        check(loaded > 0, "the ELF file has no loadable segment");
        int segmentsEnd = (int) roundUp(lastEnd);
        Integer symbol = symbol(f, elf, "_end");
        int end = symbol != null ? symbol : segmentsEnd;
        check(end >= lastEnd && end % 4 == 0, String.format("_end 0x%x is not after the segments", end));

        byte[] cmd = (commandLine == null ? "" : commandLine).getBytes(StandardCharsets.ISO_8859_1);
        check(cmd.length < COMMAND_LINE_SIZE, "the command line is longer than " + (COMMAND_LINE_SIZE - 1) + " bytes");
        int initrdSize = initrd == null ? 0 : initrd.length;
        int initrdAddress = 0;

        ByteBuffer bi = ByteBuffer.allocate(64 + cmd.length + 8).order(ByteOrder.BIG_ENDIAN);
        record(bi, BI_MACHTYPE, MACH_FLIPPIES);
        record(bi, BI_CPUTYPE, TYPE_68040);
        record(bi, BI_FPUTYPE, TYPE_68040);
        record(bi, BI_MMUTYPE, TYPE_68040);
        record(bi, BI_MEMCHUNK, 0, ram.length);
        int bootinfoSize = bi.position() + (initrd != null ? 12 : 0) + recordSize(cmd.length + 1) + 4;
        int bootinfoEnd = end + bootinfoSize;
        if (initrd != null) {
            check(initrdSize > 0, "the initrd is empty");
            initrdAddress = (ram.length - initrdSize) & -PAGE;
            check(initrdAddress >= roundUp(bootinfoEnd) + TABLE_ROOM, String.format(
                    "the initrd (%d KiB) does not fit above the kernel in %d KiB of RAM", initrdSize >> 10, ram.length >> 10));
            record(bi, BI_RAMDISK, initrdAddress, initrdSize);
        }
        int at = bi.position();
        int size = recordSize(cmd.length + 1);
        bi.putShort((short) BI_COMMAND_LINE).putShort((short) size).put(cmd);
        bi.position(at + size);
        bi.putShort((short) BI_LAST).putShort((short) 4);
        check(end + bi.position() <= ram.length, "the bootinfo does not fit in RAM");
        if (bi.position() != bootinfoSize)
            throw new IllegalStateException("bootinfo size " + bi.position() + " is not " + bootinfoSize);
        System.arraycopy(bi.array(), 0, ram, end, bi.position());
        if (initrd != null)
            System.arraycopy(initrd, 0, ram, initrdAddress, initrdSize);
        return new Loaded(entry, end, bootinfoEnd, initrdAddress, initrdSize, segmentsEnd);
    }

    /** A record of 32-bit values. */
    private static void record(ByteBuffer bi, int tag, int... values) {
        bi.putShort((short) tag).putShort((short) (4 + 4 * values.length));
        for (int v : values)
            bi.putInt(v);
    }

    /** A record's size for {@code data} bytes: the 4-byte header plus the data, padded to 4. */
    private static int recordSize(int data) {
        return (4 + data + 3) & -4;
    }

    /** The value of a global symbol in the ELF's symbol table, or null without one. */
    private static Integer symbol(ByteBuffer f, byte[] elf, String name) {
        int shoff = f.getInt(32), shentsize = f.getShort(46) & 0xFFFF, shnum = f.getShort(48) & 0xFFFF;
        if (shoff == 0 || shnum == 0 || shentsize < 40 || !fits(elf, shoff, (long) shentsize * shnum))
            return null;
        byte[] want = name.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < shnum; i++) {
            int sh = shoff + i * shentsize;
            if (f.getInt(sh + 4) != SHT_SYMTAB)
                continue;
            int symoff = f.getInt(sh + 16), symsize = f.getInt(sh + 20), link = f.getInt(sh + 24);
            if (link < 0 || link >= shnum || !fits(elf, symoff, symsize))
                return null;
            int strsh = shoff + link * shentsize;
            int stroff = f.getInt(strsh + 16), strsize = f.getInt(strsh + 20);
            if (!fits(elf, stroff, strsize))
                return null;
            for (int s = symoff; s + 16 <= symoff + symsize; s += 16) {
                int nameAt = f.getInt(s);
                if (nameAt <= 0 || nameAt + want.length >= strsize)
                    continue;
                int p = stroff + nameAt;
                if (elf[p + want.length] == 0 && Arrays.equals(elf, p, p + want.length, want, 0, want.length))
                    return f.getInt(s + 4);
            }
        }
        return null;
    }

    private static boolean fits(byte[] data, long offset, long length) {
        return offset >= 0 && length >= 0 && offset + length <= data.length;
    }

    private static long roundUp(long address) {
        return (address + PAGE - 1) & -PAGE;
    }

    private static void check(boolean ok, String message) {
        if (!ok)
            throw new IllegalArgumentException(message);
    }
}
