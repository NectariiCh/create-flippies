package com.nectariich.create_flippies.content.computer.device;

import java.util.UUID;

/**
 * A disk's contents at sector level (spec 3.8), independent of any PC so a drive can move
 * between machines. Callers keep transfers inside the disk; implementations throw
 * IndexOutOfBoundsException otherwise.
 */
public interface DiskImage {

    int SECTOR_SIZE = 512;

    /** Capacity in sectors. */
    long sectorCount();

    /** Copies {@code count} sectors starting at {@code sector} into {@code dst} at {@code offset}. */
    void read(long sector, int count, byte[] dst, int offset);

    /** Copies {@code count} sectors from {@code src} at {@code offset} onto the disk starting at {@code sector}. */
    void write(long sector, int count, byte[] src, int offset);

    /**
     * The disk's identity, checked by saved state so a swapped disk is detected (spec 11.1.6);
     * null for an anonymous disk (tests, tools).
     */
    default UUID id() {
        return null;
    }
}
