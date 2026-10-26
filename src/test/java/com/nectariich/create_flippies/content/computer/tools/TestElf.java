package com.nectariich.create_flippies.content.computer.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds small 68k ELF executables by hand for the loader tests: a 32-bit big-endian header,
 * program headers right after it, file contents put at chosen offsets, and optionally a symbol
 * table (.symtab and .strtab with their section headers) after everything else.
 */
final class TestElf {

    private final ByteBuffer file = ByteBuffer.allocate(256 * 1024).order(ByteOrder.BIG_ENDIAN);
    private final List<int[]> segments = new ArrayList<>();
    private final Map<String, Integer> symbols = new LinkedHashMap<>();
    private int entry;
    private int end = 52;
    private int machine = 4;
    private int type = 2;
    private int data = 2;

    TestElf entry(int address) {
        entry = address;
        return this;
    }

    /** Puts {@code bytes} into the file at {@code offset}. */
    TestElf put(int offset, byte[] bytes) {
        file.put(offset, bytes);
        end = Math.max(end, offset + bytes.length);
        return this;
    }

    /** A PT_LOAD program header. */
    TestElf segment(int offset, int paddr, int filesz, int memsz) {
        segments.add(new int[] { offset, paddr, filesz, memsz });
        end = Math.max(end, offset + filesz);
        return this;
    }

    TestElf symbol(String name, int value) {
        symbols.put(name, value);
        return this;
    }

    TestElf machine(int value) {
        machine = value;
        return this;
    }

    TestElf type(int value) {
        type = value;
        return this;
    }

    /** 1 little-endian, 2 big-endian (the byte in e_ident). */
    TestElf dataEncoding(int value) {
        data = value;
        return this;
    }

    byte[] build() {
        int phoff = 52;
        int at = Math.max(end, phoff + 32 * segments.size());
        int shoff = 0, shnum = 0;
        if (!symbols.isEmpty()) {
            // .strtab: a leading NUL, then the names
            int strtab = align(at);
            file.position(strtab);
            file.put((byte) 0);
            Map<String, Integer> names = new LinkedHashMap<>();
            for (String name : symbols.keySet()) {
                names.put(name, file.position() - strtab);
                file.put(name.getBytes(StandardCharsets.ISO_8859_1)).put((byte) 0);
            }
            int strsize = file.position() - strtab;
            // .symtab: the null symbol, then each symbol, absolute and global
            int symtab = align(file.position());
            file.position(symtab);
            file.put(new byte[16]);
            for (Map.Entry<String, Integer> s : symbols.entrySet())
                file.putInt(names.get(s.getKey())).putInt(s.getValue()).putInt(0)
                        .put((byte) 0x10).put((byte) 0).putShort((short) 0xFFF1);
            int symsize = file.position() - symtab;
            // section headers: null, .symtab (linked to 2), .strtab
            shoff = align(file.position());
            file.position(shoff);
            file.put(new byte[40]);
            section(2, symtab, symsize, 2, 16);
            section(3, strtab, strsize, 0, 0);
            shnum = 3;
            at = file.position();
        }
        file.position(0);
        file.put(new byte[] { 0x7F, 'E', 'L', 'F', 1, (byte) data, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        file.putShort((short) type).putShort((short) machine).putInt(1).putInt(entry).putInt(phoff).putInt(shoff)
                .putInt(0).putShort((short) 52).putShort((short) 32).putShort((short) segments.size())
                .putShort((short) 40).putShort((short) shnum).putShort((short) 0);
        for (int[] s : segments)
            file.putInt(1).putInt(s[0]).putInt(s[1]).putInt(s[1]).putInt(s[2]).putInt(s[3]).putInt(7).putInt(0x2000);
        return Arrays.copyOf(file.array(), Math.max(at, end));
    }

    private void section(int type, int offset, int size, int link, int entsize) {
        file.putInt(0).putInt(type).putInt(0).putInt(0).putInt(offset).putInt(size).putInt(link).putInt(0)
                .putInt(4).putInt(entsize);
    }

    private static int align(int offset) {
        return (offset + 3) & -4;
    }
}
