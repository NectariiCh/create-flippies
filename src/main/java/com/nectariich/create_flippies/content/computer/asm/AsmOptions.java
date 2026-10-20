package com.nectariich.create_flippies.content.computer.asm;

/**
 * Where the assembler places a program.
 *
 * @param textBase      run (and load) address of the text section; rodata, init_array,
 *                      fini_array and, without a data base, data and bss follow it
 * @param dataBase      run address of data and bss for a ROM program, or null; data's initial
 *                      bytes are then also emitted at a load address right after fini_array
 *                      (symbol {@code __data_load_start}) for {@code crt0} to copy
 * @param maxImageBytes cap on the bytes the image (and any one section) may hold; the assembler
 *                      reports an error instead of allocating past it
 */
public record AsmOptions(int textBase, Integer dataBase, int maxImageBytes) {

    public static final int DEFAULT_MAX_IMAGE_BYTES = 64 * 1024 * 1024;

    public AsmOptions {
        if (maxImageBytes <= 0)
            throw new IllegalArgumentException("maxImageBytes must be positive: " + maxImageBytes);
    }

    /** Text at {@code textBase}, no separate data base, the default size cap. */
    public static AsmOptions at(int textBase) {
        return new AsmOptions(textBase, null, DEFAULT_MAX_IMAGE_BYTES);
    }
}
