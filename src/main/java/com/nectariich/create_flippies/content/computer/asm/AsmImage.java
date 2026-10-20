package com.nectariich.create_flippies.content.computer.asm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * An assembled program: its initialised bytes as address-sorted segments (bss is never in a
 * segment), the entry point, the bss range, the symbol table and a line map for debuggers.
 */
public final class AsmImage {

    private final List<AsmSegment> segments;
    private final int entry;
    private final int bssStart;
    private final int bssEnd;
    private final Map<String, Long> symbols;
    /** Start address, size and source of every instruction, sorted by address. */
    private final int[] lineStarts;
    private final int[] lineSizes;
    private final AsmLine[] lines;

    AsmImage(List<AsmSegment> segments, int entry, int bssStart, int bssEnd, Map<String, Long> symbols,
             int[] lineStarts, int[] lineSizes, AsmLine[] lines) {
        List<AsmSegment> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> Integer.compareUnsigned(a.address(), b.address()));
        this.segments = sorted;
        this.entry = entry;
        this.bssStart = bssStart;
        this.bssEnd = bssEnd;
        this.symbols = Collections.unmodifiableMap(symbols);
        this.lineStarts = lineStarts;
        this.lineSizes = lineSizes;
        this.lines = lines;
    }

    /** Sorted by address; every call returns fresh copies of the bytes. */
    public List<AsmSegment> segments() {
        List<AsmSegment> out = new ArrayList<>(segments.size());
        for (AsmSegment s : segments)
            out.add(new AsmSegment(s.address(), s.bytes().clone()));
        return out;
    }

    /**
     * The image's bytes in [base, base + length) with gaps zero. A segment wholly outside the
     * range is left out (with a data base, the run copy of data sits outside a ROM range);
     * a segment that only partly overlaps the range is an IllegalArgumentException, because
     * the range would cut it.
     */
    public byte[] flatten(int base, int length) {
        if (length < 0)
            throw new IllegalArgumentException("negative length " + length);
        long lo = Integer.toUnsignedLong(base);
        long hi = lo + length;
        byte[] out = new byte[length];
        for (AsmSegment s : segments) {
            long start = Integer.toUnsignedLong(s.address());
            long end = s.end();
            if (end <= lo || start >= hi)
                continue;
            if (start < lo || end > hi)
                throw new IllegalArgumentException(String.format(
                        "segment 0x%08x..0x%08x does not fit in 0x%08x..0x%08x", start, end, lo, hi));
            System.arraycopy(s.bytes(), 0, out, (int) (start - lo), s.bytes().length);
        }
        return out;
    }

    /** {@code _start} if defined, else the text base. */
    public int entry() {
        return entry;
    }

    public int bssStart() {
        return bssStart;
    }

    public int bssEnd() {
        return bssEnd;
    }

    /**
     * Global and file-local labels and equates, zero-extended to 32 bits ({@code .L*} and numeric
     * local labels left out). With several files a file-local name is keyed {@code fileName:name}.
     */
    public Map<String, Long> symbols() {
        return symbols;
    }

    public OptionalLong symbol(String name) {
        Long v = symbols.get(name);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    /** The source line of the instruction covering {@code address}, or null. */
    public AsmLine lineAt(int address) {
        long a = Integer.toUnsignedLong(address);
        int lo = 0, hi = lineStarts.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Integer.toUnsignedLong(lineStarts[mid]) <= a) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0 || a >= Integer.toUnsignedLong(lineStarts[found]) + lineSizes[found])
            return null;
        return lines[found];
    }

    @Override
    public String toString() {
        return "AsmImage" + Arrays.toString(segments.stream()
                .map(s -> String.format("0x%08x+%d", s.address(), s.bytes().length)).toArray());
    }
}
