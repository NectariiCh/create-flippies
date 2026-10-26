/*
 * netinstall: a flat image the boot ROM loads at 0x1000, sector 0 first (spec 12.5). Sector 0 is
 * the boot sector, which loads the rest to 0x1200. The bss (receive ring, CRC tables, the image's
 * chunk table) follows the image and is not on the floppy; the stack is at the top of RAM.
 */
OUTPUT_FORMAT("elf32-m68k")
OUTPUT_ARCH(m68k)
ENTRY(_boot)

SECTIONS
{
    . = 0x1000;
    .boot : {
        KEEP(*(.boot))
        . = 510;
        SHORT(0x55AA)
    }
    .text : { KEEP(*(.text.start)) *(.text .text.*) }
    .rodata : { *(.rodata .rodata.*) }
    .data : { *(.data .data.*) }
    . = ALIGN(4);
    __load_end = .;
    .bss (NOLOAD) : ALIGN(16) { __bss_start = .; *(.bss .bss.*) *(COMMON) . = ALIGN(4); __bss_end = .; }
    /DISCARD/ : { *(.comment) *(.note .note.*) *(.eh_frame .eh_frame_hdr) }
}

ASSERT(__load_end - 0x1000 <= 96 * 1024, "netinstall is larger than 96 KiB")
