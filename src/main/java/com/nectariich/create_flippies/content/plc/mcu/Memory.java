package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The bus as a sketch sees it: flash at {@link Stm32f103cb#FLASH_BASE}, SRAM at
 * {@link Stm32f103cb#SRAM_BASE}, little-endian. Every read and write a program does
 * goes through here, so pointers, arrays, structs and overruns behave as on the chip.
 *
 * Addresses are unsigned 32-bit values carried in a long. Any access outside the
 * two regions throws {@link HardFault} (BUS_FAULT); a store into flash throws
 * {@link HardFault} (MEM_MANAGE). Unaligned 16/32-bit accesses are legal, as the M3
 * allows them for LDR/STR/LDRH/STRH; 64-bit accesses go as two 32-bit halves.
 */
public interface Memory {

    int read8(long address);

    int read16(long address);

    int read32(long address);

    long read64(long address);

    void write8(long address, int value);

    void write16(long address, int value);

    void write32(long address, int value);

    void write64(long address, long value);

    /** A NUL-terminated string, at most {@code max} bytes, read as Latin-1. */
    String readCString(long address, int max);
}
