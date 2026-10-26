/*
 * flipboot stage 2: linked to run at 15 MiB (0x00F00000), with its data, bss and stack below 16 MiB.
 *
 * Why there: the kernel loads at physical 0x1000 up to _end (5 MiB for the current series, under
 * 12.6's 6 MiB target), the bootinfo follows at _end, and head.S builds its early page tables on
 * the pages after that. Stage 2 loads every segment straight to its final address, so it must
 * sit above all of it; the kernel's head.S tables are written only after the jump, when stage 2
 * is no longer needed. 15 MiB leaves the kernel 15 MiB of room and asks for 16 MiB of RAM, far
 * below what Linux needs anyway. Loading the kernel high and copying it down would instead need
 * the copy loop itself kept out of the way, and twice the RAM traffic.
 */
OUTPUT_FORMAT("elf32-m68k")
OUTPUT_ARCH(m68k)
ENTRY(_start)

STAGE2_BASE = 0x00F00000;
STAGE2_MAX_IMAGE = 127 * 512;

SECTIONS
{
    . = STAGE2_BASE;
    .text : { KEEP(*(.text.start)) *(.text .text.*) }
    .rodata : { *(.rodata .rodata.*) }
    .data : { *(.data .data.*) }
    . = ALIGN(4);
    __image_end = .;
    .bss (NOLOAD) : { __bss_start = .; *(.bss .bss.*) *(COMMON) . = ALIGN(4); __bss_end = .; }
    __stack_top = 0x01000000;
    /DISCARD/ : { *(.comment) *(.note .note.*) *(.eh_frame .eh_frame_hdr) }
}

ASSERT(__image_end - STAGE2_BASE <= STAGE2_MAX_IMAGE, "stage 2 is larger than sectors 1..127")
ASSERT(__bss_end <= 0x00FF0000, "stage 2 leaves less than 64 KiB of stack below 16 MiB")
