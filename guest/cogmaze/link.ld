/* Cog Maze: a flat image loaded at RAM_BASE. Sector 0 is the boot sector. */
ENTRY(_boot)

SECTIONS
{
    . = 0x80000000;

    .boot : {
        KEEP(*(.boot))
        . = 0x80000000 + 510;
        SHORT(0xAA55)
    }

    .text : {
        KEEP(*(.text.start))
        *(.text .text.*)
    }

    .rodata : {
        *(.rodata .rodata.* .srodata .srodata.*)
    }

    .init_array : {
        __init_array_start = .;
        KEEP(*(SORT(.init_array.*)))
        KEEP(*(.init_array))
        __init_array_end = .;
    }

    .data : {
        *(.data .data.* .sdata .sdata.*)
        . = ALIGN(4);
    }

    __load_end = .;

    .bss (NOLOAD) : ALIGN(4) {
        __bss_start = .;
        *(.sbss .sbss.* .bss .bss.* COMMON)
        . = ALIGN(4);
        __bss_end = .;
    }

    /DISCARD/ : { *(.comment) *(.riscv.attributes) *(.note*) *(.eh_frame*) }
}
