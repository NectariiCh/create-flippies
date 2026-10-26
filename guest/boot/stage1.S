/*
 * flipboot stage 1 (spec 12.7): the code part of the MBR, at most 440 bytes.
 *
 * The boot ROM (spec 10.8.4) reads sector 0 to 0x1000 and enters it in supervisor mode with
 * interrupts masked and the MMU off, D0 = RAM size, D1 = the disk's capacity in sectors,
 * A1 = the ROM's print_string (A0 = string; keeps every register), SP at the top of RAM.
 *
 * Stage 1 prints "flipboot", reads stage 2 from sectors 1..127 to STAGE2 with the HDD polled,
 * checks its magic and enters it with D0, D1 and A1 as it got them. Stage 2 runs from 15 MiB, above
 * every byte the kernel loads (see stage2.ld), so the machine needs at least 16 MiB of RAM.
 * Errors print one line and stop.
 */
        .equ    HDD,        0xFFF05000
        .equ    HDD_CMD,    0x00
        .equ    HDD_STATUS, 0x04
        .equ    HDD_LBA,    0x08
        .equ    HDD_COUNT,  0x0C
        .equ    HDD_DMA,    0x10
        .equ    HDD_IRQ,    0x18
        .equ    STAGE2,     0x00F00000
        .equ    STAGE2_SECTORS, 127
        .equ    MIN_RAM,    0x01000000
        .equ    MAGIC2,     0x464C4232          /* "FLB2" at stage 2 + 4 */

        .text
        .globl  _start
_start:
        move.l  %d0,%d7                         /* RAM size */
        move.l  %d1,%d6                         /* capacity */
        move.l  %a1,%a6                         /* print_string */
        lea     msg_hello(%pc),%a0
        jsr     (%a6)
        cmp.l   #MIN_RAM,%d7
        bcs.s   no_ram

        lea     HDD,%a0
        clr.l   HDD_IRQ(%a0)                    /* polled */
        moveq   #6,%d0
        move.l  %d0,HDD_STATUS(%a0)             /* clear DONE and ERROR */
        moveq   #1,%d0
        move.l  %d0,HDD_LBA(%a0)
        move.l  #STAGE2_SECTORS,HDD_COUNT(%a0)
        move.l  #STAGE2,HDD_DMA(%a0)
        move.l  %d0,HDD_CMD(%a0)                /* READ */
1:      move.l  HDD_STATUS(%a0),%d0
        and.l   #6,%d0
        beq.s   1b
        moveq   #6,%d1
        move.l  %d1,HDD_STATUS(%a0)
        btst    #2,%d0
        bne.s   disk_error
        cmp.l   #MAGIC2,STAGE2+4
        bne.s   no_stage2

        move.l  %d7,%d0
        move.l  %d6,%d1
        move.l  %a6,%a1
        jmp     STAGE2

no_ram:
        lea     msg_ram(%pc),%a0
        bra.s   fail
disk_error:
        lea     msg_disk(%pc),%a0
        bra.s   fail
no_stage2:
        lea     msg_stage2(%pc),%a0
fail:
        jsr     (%a6)
2:      stop    #0x2700
        bra.s   2b

msg_hello:  .asciz  "flipboot\r\n"
msg_ram:    .asciz  "flipboot: needs 16 MiB of RAM\r\n"
msg_disk:   .asciz  "flipboot: disk error reading stage 2\r\n"
msg_stage2: .asciz  "flipboot: no stage 2 on this disk\r\n"
