package com.nectariich.create_flippies.content.computer.bus;

import java.util.Objects;

/**
 * Memory on the bus's slow path: a window of {@code length} bytes onto {@code data} starting at
 * {@code offset}. Used for views that the fast-path arrays cannot express, such as the 8086's
 * banked 64 KiB VRAM window ({@link #setOffset} moves the bank) and its text-mode alias.
 *
 * Accesses compose values in the bus's byte order and may be unaligned, like RAM. Writes set
 * the bit of each touched 4 KiB page of {@code data} (not of the window) in {@code dirtyPages},
 * so a banked view into VRAM dirties the right VRAM pages.
 */
public final class MemoryRegion {

    private final byte[] data;
    private final int length;
    private final boolean writable;
    private final long[] dirtyPages;
    private int offset;

    /** A window of length bytes onto data starting at offset; dirtyPages (nullable) gets a bit per 4 KiB page of data written. */
    public MemoryRegion(byte[] data, int offset, int length, boolean writable, long[] dirtyPages) {
        this.data = Objects.requireNonNull(data, "data");
        if (length <= 0)
            throw new IllegalArgumentException("length must be positive: " + length);
        this.length = length;
        this.writable = writable;
        this.dirtyPages = dirtyPages;
        setOffset(offset);
    }

    /** Moves the window (banking); IllegalArgumentException unless offset + length fits in the data. */
    public void setOffset(int offset) {
        if (offset < 0 || (long) offset + length > data.length)
            throw new IllegalArgumentException("window [" + offset + ", " + ((long) offset + length)
                    + ") is outside data of " + data.length + " bytes");
        this.offset = offset;
    }

    public int length() {
        return length;
    }

    public int offset() {
        return offset;
    }

    public boolean writable() {
        return writable;
    }

    byte[] data() {
        return data;
    }

    /** Reads {@code width} bytes at window index {@code index}; the bus has checked the range. */
    int load(int index, int width, boolean little) {
        return ByteArrays.load(data, offset + index, width, little);
    }

    /** Writes {@code width} bytes at window index {@code index} and marks the pages dirty. */
    void store(int index, int width, int value, boolean little) {
        ByteArrays.store(data, offset + index, width, value, little);
        ByteArrays.markPages(dirtyPages, offset + index, width);
    }

    /** Marks the pages of data bytes {@code [dataIndex, dataIndex + count)} dirty, after a DMA write. */
    void markDirty(int dataIndex, int count) {
        ByteArrays.markPages(dirtyPages, dataIndex, count);
    }
}
