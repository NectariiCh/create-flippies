package com.nectariich.create_flippies.content.computer.bus;

import java.nio.ByteOrder;

/**
 * A machine's physical address space (or, for the 8086 and 8088, its port space) as a CPU sees
 * it. Addresses are 32-bit unsigned values carried in an int; a CPU with a narrower address bus
 * masks before calling. Reads are zero-extended; writes use the low 8, 16 or 32 bits of the value.
 *
 * Memory regions (RAM, ROM, VRAM) compose 16- and 32-bit accesses in {@link #order()} and allow
 * unaligned accesses. Device registers are value-addressed: a full-width access gives the
 * register's value whatever the CPU's byte order, and device accesses must be naturally aligned.
 * An access that nothing answers, a write to ROM, a misaligned device access, or a multi-byte
 * access that crosses a region edge throws {@link BusFault}. A CPU issues one 32-bit call for a
 * 32-bit device access and never splits it (only 8086/8088 port I/O splits, low half first).
 *
 * RAM, ROM and VRAM are also exposed as raw arrays so a CPU can serve its own fetches, loads and
 * stores from them without region dispatch; the read and write methods give the same results for
 * those ranges. An absent array is empty (length 0) with size 0, never null (the port bus has
 * none). Test the ROM range first: under the reset-time ROM overlay the ROM range overlaps RAM
 * and the ROM wins. A store into VRAM through the array must also set bit
 * {@code (index >>> 12) & 63} of {@code vramDirtyPages()[index >>> 18]}, where
 * {@code index = address - vramBase()}; the write methods do this themselves.
 *
 * The arrays, bases and sizes change only between {@code Cpu.run} calls; a CPU reloads them at
 * the start of every run.
 */
public interface Bus {

    int read8(int address);

    int read16(int address);

    int read32(int address);

    void write8(int address, int value);

    void write16(int address, int value);

    void write32(int address, int value);

    ByteOrder order();

    /** Main RAM's backing array; index = address - {@link #ramBase()}; length == {@link #ramSize()}. */
    byte[] ramArray();

    int ramBase();

    int ramSize();

    /**
     * The boot ROM's backing array (read-only to the CPU); index = address - {@link #romBase()};
     * length == {@link #romSize()}, zero-padded to the platform's whole ROM window. While the ROM
     * overlay is on, {@link #romBase()} is 0.
     */
    byte[] romArray();

    int romBase();

    int romSize();

    /** VRAM's backing array; index = address - {@link #vramBase()}; length == {@link #vramSize()}. */
    byte[] vramArray();

    int vramBase();

    int vramSize();

    /** One bit per 4 KiB VRAM page, set on every VRAM write; owned and cleared by the VGA device. Empty when there is no VRAM. */
    long[] vramDirtyPages();
}
