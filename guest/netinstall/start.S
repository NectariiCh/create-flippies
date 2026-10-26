/*
 * netinstall (spec 12.5): the boot sector, the C runtime start and the few routines that are
 * worth writing by hand.
 *
 * The boot ROM (spec 10.8.4, 11.1.5) reads sector 0 to 0x1000 and enters it in supervisor mode
 * with interrupts masked and the MMU off, D0 = RAM size, D1 = the boot device's capacity in
 * sectors, D2 = the boot device (1 floppy, 0 HDD), A1 = the ROM's print_string (A0 = string;
 * keeps every register), SP at the top of RAM. Sector 0 loads the rest of the program (sectors
 * 1..N to 0x1200) from the same device with the controller polled, then enters _start.
 */
        .equ    HDD,        0xFFF05000
        .equ    FDC,        0xFFF0A000
        .equ    CMD,        0x00
        .equ    STATUS,     0x04
        .equ    LBA,        0x08
        .equ    COUNT,      0x0C
        .equ    DMA,        0x10
        .equ    IRQ,        0x18
        .equ    STACK_ROOM, 0x10000             /* at least this much stack above the bss */

        .section .boot,"ax"
        .globl  _boot
_boot:
        move.l  %d0,%d7                         /* RAM size */
        move.l  %d2,%d5                         /* boot device */
        move.l  %a1,%a6                         /* print_string */
        lea     msg_loading(%pc),%a0
        jsr     (%a6)
        lea     HDD,%a5
        tst.l   %d5
        beq.s   1f
        lea     FDC,%a5
1:      clr.l   IRQ(%a5)                        /* polled */
        move.l  #__load_end,%d4                 /* sectors still to load */
        sub.l   #_boot+512,%d4
        add.l   #511,%d4
        moveq   #9,%d0
        lsr.l   %d0,%d4
        moveq   #1,%d3                          /* next LBA */
        lea     _boot+512,%a4                   /* next DMA address */
load_loop:
        tst.l   %d4
        beq.s   loaded
        move.l  %d4,%d2                         /* this command: at most 128 sectors */
        cmp.l   #128,%d2
        bls.s   2f
        move.l  #128,%d2
2:      moveq   #6,%d0
        move.l  %d0,STATUS(%a5)                 /* clear DONE and ERROR */
        move.l  %d3,LBA(%a5)
        move.l  %d2,COUNT(%a5)
        move.l  %a4,DMA(%a5)
        moveq   #1,%d0
        move.l  %d0,CMD(%a5)                    /* READ */
3:      move.l  STATUS(%a5),%d0
        and.l   #6,%d0
        beq.s   3b
        moveq   #6,%d1
        move.l  %d1,STATUS(%a5)
        btst    #2,%d0
        bne.s   load_failed
        add.l   %d2,%d3
        sub.l   %d2,%d4
        moveq   #9,%d0
        lsl.l   %d0,%d2
        add.l   %d2,%a4
        bra.s   load_loop
load_failed:
        lea     msg_failed(%pc),%a0
halt_msg:
        jsr     (%a6)
4:      stop    #0x2700
        bra.s   4b
loaded:
        cmp.l   #__bss_end+STACK_ROOM,%d7
        bcc.s   5f
        lea     msg_ram(%pc),%a0
        bra.s   halt_msg
5:      jmp     _start

msg_loading:    .asciz  "Loading netinstall...\r\n"
msg_failed:     .asciz  "netinstall: read error while loading\r\n"
msg_ram:        .asciz  "netinstall: needs 2 MiB of RAM\r\n"

/* Entered from the boot sector: D7 = RAM size, D5 = boot device, A6 = print_string. */
        .section .text.start,"ax"
        .globl  _start
_start:
        move.l  %d7,%sp                         /* RAM starts at 0: the stack at its top */
        lea     __bss_start,%a0
        lea     __bss_end,%a2
1:      cmp.l   %a2,%a0
        bcc.s   2f
        clr.l   (%a0)+
        bra.s   1b
2:      move.l  %a6,-(%sp)
        move.l  %d5,-(%sp)
        move.l  %d7,-(%sp)
        jsr     netinstall_main
3:      stop    #0x2700
        bra.s   3b

/*
 * The interrupt handler for every autovector: it raises the interrupted SR's mask to 7 and
 * returns. The program sleeps with "stop #0x2000" and does all its work at task level, so an
 * interrupt only ends the STOP; the device lines are levels, and the main loop clears them.
 */
        .text
        .globl  irq_entry
irq_entry:
        ori.w   #0x0700,(%sp)
        rte

/* void sleep_until_interrupt(void): STOP with every level enabled; returns with the mask at 7. */
        .globl  sleep_until_interrupt
sleep_until_interrupt:
        stop    #0x2000
        rts

/*
 * u32 crc32_words(u32 c, const u32 *p, u32 words, const u32 *ta, const u32 *tb): zlib's CRC32
 * over whole big-endian words, the state kept byte-swapped (netinstall.c, crc32). Two lookups
 * of 64 Ki-entry tables per word: c = ta[x >> 16] ^ tb[x & 0xFFFF] with x = c ^ word. Unrolled
 * four times. This loop is most of the install's CPU time.
 */
        .globl  crc32_words
crc32_words:
        movem.l %d2-%d5/%a2-%a3,-(%sp)
        move.l  28(%sp),%d0
        move.l  32(%sp),%a0
        move.l  36(%sp),%d1
        move.l  40(%sp),%a2
        move.l  44(%sp),%a3
        moveq   #0,%d2
        moveq   #0,%d4
        move.l  %d1,%d5
        and.l   #3,%d5                          /* words left after the unrolled loop */
        lsr.l   #2,%d1
        bra.s   2f
1:
        .rept   4
        move.l  (%a0)+,%d3
        eor.l   %d0,%d3
        move.w  %d3,%d2
        move.l  (%a3,%d2.l*4),%d0
        swap    %d3
        move.w  %d3,%d4
        move.l  (%a2,%d4.l*4),%d3
        eor.l   %d3,%d0
        .endr
2:      subq.l  #1,%d1
        bcc.s   1b
        bra.s   4f
3:      move.l  (%a0)+,%d3
        eor.l   %d0,%d3
        move.w  %d3,%d2
        move.l  (%a3,%d2.l*4),%d0
        swap    %d3
        move.w  %d3,%d4
        move.l  (%a2,%d4.l*4),%d3
        eor.l   %d3,%d0
4:      subq.l  #1,%d5
        bcc.s   3b
        movem.l (%sp)+,%d2-%d5/%a2-%a3
        rts

/*
 * void copy_fast(void *dst, const void *src, u32 n): 32 bytes per MOVEM pair, then longs, then
 * bytes. The 68040 takes misaligned long accesses, so any alignment works.
 */
        .globl  copy_fast
copy_fast:
        movem.l %d2-%d7/%a2-%a3,-(%sp)
        move.l  36(%sp),%a1
        move.l  40(%sp),%a0
        move.l  44(%sp),%d0
        move.l  %d0,%d1
        lsr.l   #5,%d1
        bra.s   2f
1:      movem.l (%a0)+,%d2-%d7/%a2-%a3
        movem.l %d2-%d7/%a2-%a3,(%a1)
        lea     32(%a1),%a1
2:      subq.l  #1,%d1
        bcc.s   1b
        moveq   #31,%d1
        and.l   %d1,%d0
        move.l  %d0,%d1
        lsr.l   #2,%d1
        bra.s   4f
3:      move.l  (%a0)+,(%a1)+
4:      subq.l  #1,%d1
        bcc.s   3b
        and.l   #3,%d0
        bra.s   6f
5:      move.b  (%a0)+,(%a1)+
6:      subq.l  #1,%d0
        bcc.s   5b
        movem.l (%sp)+,%d2-%d7/%a2-%a3
        rts
