package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;

import java.util.Arrays;

/**
 * The 68000 and 68040 boot ROM (spec 10.8.4): one 68k assembly source, assembled once per CPU
 * with the machine's own assembler at {@link M68kMap#ROM_BASE}. A few lines differ per CPU (the
 * 68040's control register prologue, the banner and how the trap handler reads a frame); Java
 * splices them in, since the assembler has no conditional assembly.
 *
 * What it does: ROM offset 0 holds the reset vectors (SSP unused, PC = the ROM's home address of
 * {@code _start}), so the CPU leaves the overlay range with its first fetch. {@code _start}
 * writes ROM_OVERLAY_OFF (RAM appears at 0), sets SP to the top of RAM, points every vector 2..255
 * of the RAM vector table at a per-vector stub, sets COM1 to 8N1 with FIFOs, clears the VGA text
 * screen and prints the banner to COM1 and VGA. Boot order floppy, then HDD (spec 11.1.5): with
 * floppy media present it reads the floppy's sector 0 to {@link #BOOT_ADDRESS} by polling; a
 * sector ending in 0x55 0xAA prints "Booting from floppy" and is entered with boot device 1,
 * anything else prints "Floppy not bootable" and carries on. Then the HDD: with neither floppy
 * media nor a disk it prints "No disk" and sleeps in STOP; otherwise it reads sector 0 the same
 * way and enters it with boot device 0, or prints "Disk not bootable" and sleeps. The boot
 * sector is entered in supervisor mode with interrupts masked, D0 = RAM size, D1 = the boot
 * device's capacity in sectors, D2 = boot device (0 HDD, 1 floppy), A1 = the ROM's print_string
 * (A0 = string; prints to COM1 and VGA, keeps every register) and SP at the top of RAM.
 *
 * The trap handler: each stub is a {@code bsr.w rom_trap}, so the handler finds the vector from
 * the return address on both CPUs (the 68000's frame has no vector number). It prints
 * "TRAP vector=XX pc=XXXXXXXX sr=XXXX", plus " addr=XXXXXXXX" for a frame with an address (the
 * 68000's bus and address error frame; the 68040's formats 2, 3 and 7), and powers off with
 * failure code {@link #TRAP_EXIT_CODE}.
 *
 * Devices are value-addressed (spec 10.3.5): 32-bit registers are read and written with
 * {@code .l}, the UART's 8-bit registers with {@code .b} at their own offsets.
 */
public final class M68kBootRom {

    private M68kBootRom() {
    }

    /** Failure code of the ROM trap handler's power off (spec 10.8.4). */
    public static final int TRAP_EXIT_CODE = 0xDEAD;
    /** Where the boot sector is loaded and entered, above the vector table (spec 10.8.4). */
    public static final int BOOT_ADDRESS = 0x1000;

    static final String SOURCE = """
            * Create Flippies 68000 and 68040 boot ROM (spec 10.8.4)
            SYSCON      equ $FFF00000
            COM1        equ $FFF02000
            HDD         equ $FFF05000
            VGA         equ $FFF06000
            VRAM        equ $FFE00000
            BOOT        equ $1000
            FDC         equ $FFF0A000           ; floppy controller (spec 11.1.5)
            FDC_COMMAND equ $00
            FDC_STATUS  equ $04
            FDC_LBA     equ $08
            FDC_COUNT   equ $0C
            FDC_DMA     equ $10
            FDC_CAP     equ $14

                    .text
            rom_start:
                    dc.l    0                   ; reset SSP, unused: SP comes from RAM_SIZE
                    dc.l    _start              ; reset PC: the ROM's home address
            _start:
                    move.l  d0,SYSCON+$24       ; ROM_OVERLAY_OFF: RAM appears at 0
                    move.l  SYSCON+$08,d7       ; RAM_SIZE (RAM_BASE is 0)
                    move.l  d7,sp
            @PROLOGUE@
                    lea     vec_stubs+8(pc),a0  ; vectors 2..255 to their stubs
                    lea     8,a1
                    move.w  #253,d0
            1:      move.l  a0,(a1)+
                    addq.l  #4,a0
                    dbra    d0,1b

                    move.b  #$03,COM1+3         ; LCR: 8 data bits, no parity, 1 stop
                    move.b  #$07,COM1+2         ; FCR: FIFOs on and cleared

                    lea     VRAM,a0             ; blank screen: spaces with attribute 7
                    move.w  #999,d0
            1:      move.l  #$20072007,(a0)+
                    dbra    d0,1b
                    clr.l   VGA+$0C             ; cursor to (0, 0)

                    lea     msg_banner(pc),a0
                    bsr     print_string
                    move.l  d7,d0
                    moveq   #10,d1
                    lsr.l   d1,d0
                    bsr     print_dec
                    lea     msg_kib(pc),a0
                    bsr     print_string

                    moveq   #0,d6               ; floppy media was present
                    move.l  SYSCON+$20,d0       ; PRESENT, bit 5 floppy media
                    btst    #5,d0
                    beq.s   try_hdd
                    moveq   #1,d6
                    lea     FDC,a0
                    clr.l   FDC_LBA(a0)         ; LBA 0
                    moveq   #1,d0
                    move.l  d0,FDC_COUNT(a0)    ; COUNT 1
                    move.l  #BOOT,FDC_DMA(a0)   ; DMA_ADDR
                    move.l  d0,FDC_COMMAND(a0)  ; COMMAND READ
            1:      move.l  FDC_STATUS(a0),d0   ; STATUS: wait for DONE or ERROR
                    and.l   #6,d0
                    beq.s   1b
                    moveq   #6,d1
                    move.l  d1,FDC_STATUS(a0)   ; clear DONE and ERROR
                    btst    #2,d0
                    bne.s   floppy_not_bootable
                    lea     BOOT,a2
                    cmp.b   #$55,510(a2)
                    bne.s   floppy_not_bootable
                    cmp.b   #$AA,511(a2)
                    bne.s   floppy_not_bootable
                    lea     msg_floppy_boot(pc),a0
                    bsr     print_string
                    move.l  FDC+FDC_CAP,d1      ; CAPACITY in sectors
                    moveq   #1,d2               ; boot device 1: floppy
                    move.l  d7,d0
                    lea     print_string(pc),a1
                    jmp     (a2)

            floppy_not_bootable:
                    lea     msg_floppy_not_bootable(pc),a0
                    bsr     print_string
            try_hdd:
                    move.l  SYSCON+$20,d0       ; PRESENT, bit 0 HDD
                    btst    #0,d0
                    bne.s   have_disk
                    tst.b   d6                  ; "No disk" only with neither floppy media nor an HDD
                    bne     sleep
                    lea     msg_no_disk(pc),a0
                    bsr     print_string
                    bra     sleep

            have_disk:
                    lea     HDD,a0
                    clr.l   $08(a0)             ; LBA 0
                    moveq   #1,d0
                    move.l  d0,$0C(a0)          ; COUNT 1
                    move.l  #BOOT,$10(a0)       ; DMA_ADDR
                    move.l  d0,(a0)             ; COMMAND READ
            1:      move.l  $04(a0),d0          ; STATUS: wait for DONE or ERROR
                    and.l   #6,d0
                    beq.s   1b
                    moveq   #6,d1
                    move.l  d1,$04(a0)          ; clear DONE and ERROR
                    btst    #2,d0
                    bne.s   not_bootable
                    lea     BOOT,a2
                    cmp.b   #$55,510(a2)
                    bne.s   not_bootable
                    cmp.b   #$AA,511(a2)
                    bne.s   not_bootable
                    move.l  $14(a0),d1          ; CAPACITY in sectors
                    moveq   #0,d2               ; boot device 0: HDD
                    move.l  d7,d0
                    lea     print_string(pc),a1
                    jmp     (a2)

            not_bootable:
                    lea     msg_not_bootable(pc),a0
                    bsr     print_string
            sleep:  stop    #$2700              ; nothing below level 7 can wake us
                    bra.s   sleep

            * print_string: A0 = NUL-terminated string, to COM1 and VGA. Keeps every register.
            print_string:
                    movem.l d0/a0,-(sp)
            1:      move.b  (a0)+,d0
                    beq.s   2f
                    bsr     print_char
                    bra.s   1b
            2:      movem.l (sp)+,d0/a0
                    rts

            * print_dec: D0 = unsigned value in decimal, no leading zeros. Keeps every register.
            print_dec:
                    movem.l d0-d3/a0,-(sp)
                    move.l  d0,d1
                    lea     pow10(pc),a0
                    moveq   #0,d3               ; a digit has been printed
            1:      move.l  (a0)+,d2
                    beq.s   5f
                    moveq   #$30,d0
            2:      cmp.l   d2,d1
                    bcs.s   3f
                    sub.l   d2,d1
                    addq.b  #1,d0
                    bra.s   2b
            3:      cmp.l   #1,d2               ; the units digit always prints
                    beq.s   4f
                    tst.b   d3
                    bne.s   4f
                    cmp.b   #$30,d0
                    beq.s   1b                  ; leading zero
            4:      moveq   #1,d3
                    bsr     print_char
                    bra.s   1b
            5:      movem.l (sp)+,d0-d3/a0
                    rts

            * print_hex: the low D1 (1..8) hex digits of D0, upper case. Keeps every register.
            print_hex:
                    movem.l d0-d2,-(sp)
                    move.l  d0,d2
                    moveq   #8,d0
                    sub.l   d1,d0
                    lsl.l   #2,d0
                    lsl.l   d0,d2               ; drop the digits not printed
                    subq.w  #1,d1
            1:      rol.l   #4,d2
                    move.l  d2,d0
                    and.w   #$F,d0
                    cmp.b   #10,d0
                    bcs.s   2f
                    addq.b  #7,d0
            2:      add.b   #$30,d0
                    bsr     print_char
                    dbra    d1,1b
                    movem.l (sp)+,d0-d2
                    rts

            * print_char: D0.b to COM1 (waiting for room) and to the VGA text cursor. LF moves
            * to the next row, CR to column 0; the screen scrolls at the bottom. Keeps every register.
            print_char:
                    movem.l d1-d3/a0,-(sp)
                    lea     COM1,a0
            1:      btst    #5,5(a0)            ; LSR bit 5: room in the transmitter
                    beq.s   1b
                    move.b  d0,(a0)
                    move.l  VGA+$0C,d1          ; CURSOR: column bits 7..0, row bits 15..8
                    moveq   #0,d2
                    move.b  d1,d2
                    lsr.w   #8,d1
                    and.l   #$FF,d1
                    cmp.b   #10,d0
                    beq.s   pc_newline
                    cmp.b   #13,d0
                    beq.s   pc_return
                    move.w  d1,d3               ; cell = VRAM + 2 * (row * 80 + column)
                    mulu.w  #80,d3
                    add.w   d2,d3
                    add.l   d3,d3
                    lea     VRAM,a0
                    move.b  d0,0(a0,d3.l)
                    move.b  #$07,1(a0,d3.l)
                    addq.w  #1,d2
                    cmp.w   #80,d2
                    bcs.s   pc_store
            pc_newline:
                    moveq   #0,d2
                    addq.w  #1,d1
                    cmp.w   #25,d1
                    bcs.s   pc_store
                    moveq   #24,d1              ; scroll rows 1..24 up one row, blank row 24
                    lea     VRAM,a0
                    move.w  #959,d3
            2:      move.l  160(a0),(a0)+
                    dbra    d3,2b
                    move.w  #39,d3
            3:      move.l  #$20072007,(a0)+
                    dbra    d3,3b
                    bra.s   pc_store
            pc_return:
                    moveq   #0,d2
            pc_store:
                    lsl.w   #8,d1
                    or.w    d2,d1
                    move.l  d1,VGA+$0C
                    movem.l (sp)+,d1-d3/a0
                    rts

            * Trap handler, entered from a vector's stub: report and power off with code $DEAD.
            rom_trap:
                    move.w  #$2700,sr
                    move.l  (sp)+,d6            ; the stub's address + 4
                    lea     vec_stubs(pc),a0
                    sub.l   a0,d6
                    lsr.l   #2,d6
                    subq.l  #1,d6               ; vector number
                    move.l  sp,a5               ; the exception frame
                    lea     msg_trap(pc),a0
                    bsr     print_string
                    move.l  d6,d0
                    moveq   #2,d1
                    bsr     print_hex
            @FRAME@
                    lea     msg_pc(pc),a0
                    bsr     print_string
                    move.l  d4,d0
                    moveq   #8,d1
                    bsr     print_hex
                    lea     msg_sr(pc),a0
                    bsr     print_string
                    move.l  d5,d0
                    moveq   #4,d1
                    bsr     print_hex
                    tst.b   d2
                    beq.s   1f
                    lea     msg_addr(pc),a0
                    bsr     print_string
                    move.l  d3,d0
                    moveq   #8,d1
                    bsr     print_hex
            1:      lea     msg_crlf(pc),a0
                    bsr     print_string
                    lea     COM1,a0
            2:      btst    #6,5(a0)            ; LSR bit 6: everything sent
                    beq.s   2b
                    move.l  #$DEAD3333,SYSCON
            3:      bra.s   3b

            * One stub per vector, 4 bytes each: the return address tells the vector.
            vec_stubs:
            @STUBS@
                    even
            pow10:  dc.l    1000000000,100000000,10000000,1000000,100000,10000,1000,100,10,1,0
            msg_banner:       dc.b "Create Flippies PC  @CPU@  ",0
            msg_kib:          dc.b " KiB RAM",13,10,0
            msg_no_disk:      dc.b "No disk",13,10,0
            msg_not_bootable: dc.b "Disk not bootable",13,10,0
            msg_floppy_boot:  dc.b "Booting from floppy",13,10,0
            msg_floppy_not_bootable: dc.b "Floppy not bootable",13,10,0
            msg_trap:         dc.b "TRAP vector=",0
            msg_pc:           dc.b " pc=",0
            msg_sr:           dc.b " sr=",0
            msg_addr:         dc.b " addr=",0
            msg_crlf:         dc.b 13,10,0
                    even
            """;

    /** The 68040's control registers to their reset meaning: vectors at 0, caches and paging off, FPU defaults. */
    private static final String PROLOGUE_68040 = """
                    moveq   #0,d0
                    movec   d0,vbr
                    movec   d0,cacr
                    movec   d0,tc
                    fmove.l d0,fpcr
            """;

    /**
     * 68000 frame: SR then PC, or for the bus and address error (vectors 2, 3) the status word,
     * access address and instruction register first. Leaves PC in D4, SR in D5, the address in
     * D3 with D2 = 1 when there is one.
     */
    private static final String FRAME_68000 = """
                    moveq   #0,d2
                    cmp.l   #3,d6
                    bhi.s   1f
                    move.l  2(a5),d3            ; group 0: access address
                    moveq   #1,d2
                    lea     8(a5),a5
            1:      move.w  (a5),d5
                    move.l  2(a5),d4
            """;

    /** 68040 frame: SR, PC, format and vector word; formats 2, 3 and 7 carry an address at offset 8. */
    private static final String FRAME_68040 = """
                    move.w  (a5),d5
                    move.l  2(a5),d4
                    move.w  6(a5),d0
                    rol.w   #4,d0
                    and.w   #$F,d0              ; frame format
                    moveq   #0,d2
                    cmp.w   #2,d0
                    beq.s   1f
                    cmp.w   #3,d0
                    beq.s   1f
                    cmp.w   #7,d0
                    bne.s   2f
            1:      move.l  8(a5),d3
                    moveq   #1,d2
            2:
            """;

    /** The source for one CPU. */
    static String source(CpuModel model) {
        boolean m040 = model == CpuModel.CPU_68040;
        if (!m040 && model != CpuModel.CPU_68000)
            throw new IllegalArgumentException("no 68k boot ROM for a " + model.displayName());
        return SOURCE.replace("@PROLOGUE@\n", m040 ? PROLOGUE_68040 : "")
                .replace("@FRAME@\n", m040 ? FRAME_68040 : FRAME_68000)
                .replace("@STUBS@\n", "        bsr.w   rom_trap\n".repeat(256))
                .replace("@CPU@", model.displayName());
    }

    /** The assembled ROMs, built on first use. */
    private static final class Holder {
        static final AsmImage M68000 = assemble(CpuModel.CPU_68000);
        static final AsmImage M68040 = assemble(CpuModel.CPU_68040);
    }

    private static AsmImage assemble(CpuModel model) {
        M68kTarget target = model == CpuModel.CPU_68040 ? M68kTarget.M68040 : M68kTarget.M68000;
        AsmResult result = M68kAssembler.assemble(source(model), AsmOptions.at(M68kMap.ROM_BASE), target);
        if (!result.ok())
            throw new IllegalStateException(model.displayName() + " boot ROM does not assemble: " + result.diagnostics());
        return result.image();
    }

    private static AsmImage assembled(CpuModel model) {
        return switch (model) {
            case CPU_68000 -> Holder.M68000;
            case CPU_68040 -> Holder.M68040;
            default -> throw new IllegalArgumentException("no 68k boot ROM for a " + model.displayName());
        };
    }

    /** The ROM bytes from {@link M68kMap#ROM_BASE}, trimmed after the last non-zero byte; a fresh copy. */
    public static byte[] image(CpuModel model) {
        byte[] full = assembled(model).flatten(M68kMap.ROM_BASE, M68kMap.ROM_SIZE);
        int end = full.length;
        while (end > 0 && full[end - 1] == 0)
            end--;
        return Arrays.copyOf(full, Math.max(end, 1));
    }

    /** Address of a ROM symbol such as {@code print_string} (as the 68040 sees it; the 68000 masks it). */
    public static int symbol(CpuModel model, String name) {
        return (int) assembled(model).symbol(name)
                .orElseThrow(() -> new IllegalArgumentException("no boot ROM symbol " + name));
    }
}
