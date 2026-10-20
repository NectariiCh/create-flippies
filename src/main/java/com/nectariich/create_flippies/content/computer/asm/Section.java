package com.nectariich.create_flippies.content.computer.asm;

/** The output sections, in layout order. */
public enum Section {
    TEXT(".text"),
    RODATA(".rodata"),
    INIT_ARRAY(".init_array"),
    FINI_ARRAY(".fini_array"),
    DATA(".data"),
    BSS(".bss");

    private final String displayName;

    Section(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * The section a {@code .section} name maps to by prefix, or null if the name is not one of
     * them (GCC and Clang add suffixes such as {@code .text.startup} or {@code .rodata.str1.4}).
     */
    public static Section forName(String name) {
        if (name.startsWith(".text"))
            return TEXT;
        if (name.startsWith(".rodata") || name.startsWith(".srodata"))
            return RODATA;
        if (name.startsWith(".data") || name.startsWith(".sdata"))
            return DATA;
        if (name.startsWith(".bss") || name.startsWith(".sbss"))
            return BSS;
        if (name.startsWith(".init_array"))
            return INIT_ARRAY;
        if (name.startsWith(".fini_array"))
            return FINI_ARRAY;
        return null;
    }
}
