/* flipboot stage 1: entered by the boot ROM at 0x1000, the code part of the MBR (440 bytes). */
OUTPUT_FORMAT("elf32-m68k")
OUTPUT_ARCH(m68k)
ENTRY(_start)

SECTIONS
{
    . = 0x1000;
    .text : { *(.text .text.*) *(.rodata .rodata.*) *(.data .data.*) }
    __stage1_end = .;
    /DISCARD/ : { *(.comment) *(.note .note.*) *(.bss) }
}

ASSERT(__stage1_end - 0x1000 <= 440, "stage 1 is larger than the 440 bytes before the disk signature")
