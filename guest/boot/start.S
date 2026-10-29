/*
 * flipboot stage 2 entry (spec 12.7). Stage 1 loads the image to its link address (stage2.ld)
 * and checks the magic at offset 4. Entered in supervisor mode, interrupts masked, MMU off, with
 * D0 = RAM size, D1 = the disk's capacity in sectors and A1 = the boot ROM's print_string.
 */
        .section .text.start,"ax"
        .globl  _start
_start:
        bra.w   entry                           /* 4 bytes */
        .long   0x464C4232                      /* "FLB2": stage 1 checks it */
        .long   __image_end - _start            /* image size in bytes */

entry:
        lea     __stack_top,%sp
        lea     __bss_start,%a0
        lea     __bss_end,%a2
1:      cmp.l   %a2,%a0
        bcc.s   2f
        clr.l   (%a0)+
        bra.s   1b
2:      move.l  %a1,-(%sp)                      /* print_string */
        move.l  %d1,-(%sp)                      /* capacity */
        move.l  %d0,-(%sp)                      /* RAM size */
        jsr     flipboot_main
halt:   stop    #0x2700
        bra.s   halt

/*
 * void rom_print(void (*print_string)(void), const char *s): the ROM routine takes its string in
 * A0 and keeps every register.
 */
        .text
        .globl  rom_print
rom_print:
        move.l  4(%sp),%a1
        move.l  8(%sp),%a0
        jsr     (%a1)
        rts

/*
 * void enter_kernel(unsigned long entry): interrupts stay masked, the MMU stays off (the boot
 * ROM cleared TC, VBR and CACR), SP below the kernel as the headless runner sets it (head.S points
 * its first stack at _stext at once).
 */
        .globl  enter_kernel
enter_kernel:
        move.l  4(%sp),%a0
        move.w  #0x2700,%sr
        lea     0x1000,%sp
        jmp     (%a0)

/*
 * u32 crc_words(u32 crc, const u32 *p, u32 words, const u32 tables[4][256]): the cksum CRC
 * (MSB first) over whole big-endian words, four table lookups a word. flipboot.c's crc_update
 * handles the unaligned head and tail; this loop is most of the boot's CPU time.
 */
        .globl  crc_words
crc_words:
        movem.l %d2-%d4/%a2-%a5,-(%sp)
        move.l  32(%sp),%d0
        move.l  36(%sp),%a0
        move.l  40(%sp),%d1
        move.l  44(%sp),%a2                     /* byte then 0 zero bytes */
        lea     1024(%a2),%a3                   /* 1 */
        lea     2048(%a2),%a4                   /* 2 */
        lea     3072(%a2),%a5                   /* 3 */
        moveq   #0,%d3                          /* only its low byte changes below */
        bra.s   2f
1:      move.l  (%a0)+,%d2
        eor.l   %d0,%d2
        move.b  %d2,%d3
        move.l  (%a2,%d3.l*4),%d0
        lsr.l   #8,%d2
        move.b  %d2,%d3
        move.l  (%a3,%d3.l*4),%d4
        eor.l   %d4,%d0
        lsr.l   #8,%d2
        move.b  %d2,%d3
        move.l  (%a4,%d3.l*4),%d4
        eor.l   %d4,%d0
        lsr.l   #8,%d2
        move.l  (%a5,%d2.l*4),%d4
        eor.l   %d4,%d0
2:      subq.l  #1,%d1
        bcc.s   1b
        movem.l (%sp)+,%d2-%d4/%a2-%a5
        rts
