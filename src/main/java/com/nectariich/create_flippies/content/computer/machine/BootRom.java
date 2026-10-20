package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;

import java.util.Arrays;

/**
 * The RV32I boot ROM (spec 4.7), kept as assembly and assembled once with the machine's own
 * assembler at {@link Rv32Map#ROM_BASE}.
 *
 * What it does: sets sp to the top of RAM and mtvec to its trap handler, sets COM1 to 8N1 with
 * FIFOs, clears the VGA text screen and prints the banner to COM1 and VGA. Boot order floppy,
 * then HDD (spec 11.1.5): with floppy media present it reads the floppy's sector 0 to the start
 * of RAM by polling; a sector ending in 0x55 0xAA prints "Booting from floppy" and is entered
 * with boot device 1, anything else prints "Floppy not bootable" and carries on. Then the HDD:
 * with neither floppy media nor a disk it prints "No disk" and sleeps; otherwise it reads sector
 * 0 the same way and enters it with boot device 0, or prints "Disk not bootable" and sleeps.
 * The entry registers: a0 = RAM size, a1 = the boot device's capacity in sectors, a2 = the
 * ROM's print_string routine (a0 = string; prints to COM1 and VGA), a3 = boot device (0 HDD,
 * 1 floppy). The trap handler prints mcause, mepc and mtval and powers off with failure code
 * 0xDEAD.
 *
 * The VGA cursor register is the ROM's only text state, so print_string works the same when a
 * boot sector calls it later. The ROM uses no M instructions.
 */
public final class BootRom {

    private BootRom() {
    }

    /** Failure code of the ROM trap handler's power off (spec 4.7). */
    public static final int TRAP_EXIT_CODE = 0xDEAD;

    static final String SOURCE = """
            # Create Flippies RV32I boot ROM (spec 4.7)
            .equ SYSCON,     0x00100000
            .equ COM1,       0x10000000
            .equ HDD,        0x10003000
            .equ VGA,        0x10004000
            .equ VRAM,       0x20000000
            .equ FDC,        0x10008000     # floppy controller (spec 11.1.5)
            .equ FDC_COMMAND,  0x00
            .equ FDC_STATUS,   0x04
            .equ FDC_LBA,      0x08
            .equ FDC_COUNT,    0x0C
            .equ FDC_DMA_ADDR, 0x10
            .equ FDC_CAPACITY, 0x14

            .text
            .globl _start
            _start:
                li   t0, SYSCON
                lw   s0, 0x04(t0)           # RAM_BASE
                lw   s1, 0x08(t0)           # RAM_SIZE
                add  sp, s0, s1
                la   t0, rom_trap
                csrw mtvec, t0

                li   t0, COM1               # 8 data bits, no parity, 1 stop; FIFOs on and cleared
                li   t1, 0x03
                sb   t1, 3(t0)
                li   t1, 0x07
                sb   t1, 2(t0)

                li   t0, VRAM               # blank screen: spaces with attribute 0x07
                li   t1, 1000
                li   t2, 0x07200720
            1:  sw   t2, 0(t0)
                addi t0, t0, 4
                addi t1, t1, -1
                bnez t1, 1b
                li   t0, VGA
                sw   zero, 0x0C(t0)         # cursor to (0, 0)

                la   a0, msg_banner
                call print_string
                srli a0, s1, 10
                call print_dec
                la   a0, msg_kib
                call print_string

                li   s2, 0                  # floppy media was present
                li   t0, SYSCON
                lw   t1, 0x20(t0)           # PRESENT, bit 5 floppy media
                andi t1, t1, 0x20
                beqz t1, try_hdd
                li   s2, 1
                li   t0, FDC
                sw   zero, FDC_LBA(t0)      # LBA 0
                li   t1, 1
                sw   t1, FDC_COUNT(t0)      # COUNT 1
                sw   s0, FDC_DMA_ADDR(t0)   # DMA_ADDR = RAM_BASE
                sw   t1, FDC_COMMAND(t0)    # COMMAND READ
            1:  lw   t1, FDC_STATUS(t0)     # STATUS: wait for DONE or ERROR
                andi t2, t1, 6
                beqz t2, 1b
                li   t2, 6
                sw   t2, FDC_STATUS(t0)     # clear DONE and ERROR
                andi t2, t1, 4
                bnez t2, floppy_not_bootable
                lbu  t1, 510(s0)
                li   t2, 0x55
                bne  t1, t2, floppy_not_bootable
                lbu  t1, 511(s0)
                li   t2, 0xAA
                bne  t1, t2, floppy_not_bootable
                la   a0, msg_floppy_boot
                call print_string
                li   t0, FDC
                lw   a1, FDC_CAPACITY(t0)   # CAPACITY in sectors
                li   a3, 1                  # boot device 1: floppy
                mv   a0, s1
                la   a2, print_string
                jr   s0

            floppy_not_bootable:
                la   a0, msg_floppy_not_bootable
                call print_string
            try_hdd:
                li   t0, SYSCON
                lw   t1, 0x20(t0)           # PRESENT, bit 0 HDD
                andi t1, t1, 1
                bnez t1, have_disk
                bnez s2, sleep              # "No disk" only with neither floppy media nor an HDD
                la   a0, msg_no_disk
                call print_string
                j    sleep

            have_disk:
                li   t0, HDD
                sw   zero, 0x08(t0)         # LBA 0
                li   t1, 1
                sw   t1, 0x0C(t0)           # COUNT 1
                sw   s0, 0x10(t0)           # DMA_ADDR = RAM_BASE
                sw   t1, 0x00(t0)           # COMMAND READ
            1:  lw   t1, 0x04(t0)           # STATUS: wait for DONE or ERROR
                andi t2, t1, 6
                beqz t2, 1b
                li   t2, 6
                sw   t2, 0x04(t0)           # clear DONE and ERROR
                andi t2, t1, 4
                bnez t2, not_bootable
                lbu  t1, 510(s0)
                li   t2, 0x55
                bne  t1, t2, not_bootable
                lbu  t1, 511(s0)
                li   t2, 0xAA
                bne  t1, t2, not_bootable
                lw   a1, 0x14(t0)           # CAPACITY in sectors
                li   a3, 0                  # boot device 0: HDD
                mv   a0, s1
                la   a2, print_string
                jr   s0

            not_bootable:
                la   a0, msg_not_bootable
                call print_string
            sleep:
                csrw mie, zero              # nothing can wake us: the machine idles for free
                wfi
                j    sleep

            # print_string: a0 = NUL-terminated string, to COM1 and VGA. Keeps s registers.
            .globl print_string
            print_string:
                addi sp, sp, -8
                sw   ra, 4(sp)
                sw   s0, 0(sp)
                mv   s0, a0
            1:  lbu  a0, 0(s0)
                beqz a0, 2f
                call print_char
                addi s0, s0, 1
                j    1b
            2:  lw   s0, 0(sp)
                lw   ra, 4(sp)
                addi sp, sp, 8
                ret

            # print_dec: a0 = unsigned value in decimal, no leading zeros. Keeps s registers.
            print_dec:
                addi sp, sp, -16
                sw   ra, 12(sp)
                sw   s0, 8(sp)
                sw   s1, 4(sp)
                sw   s2, 0(sp)
                mv   s0, a0
                la   s1, pow10
                li   s2, 0                  # a digit has been printed
            1:  lw   t0, 0(s1)
                beqz t0, 5f
                li   a0, '0'
            2:  bltu s0, t0, 3f
                sub  s0, s0, t0
                addi a0, a0, 1
                j    2b
            3:  addi s1, s1, 4
                li   t1, 1
                beq  t0, t1, 4f             # the units digit always prints
                bnez s2, 4f
                li   t1, '0'
                beq  a0, t1, 1b             # leading zero
            4:  li   s2, 1
                call print_char
                j    1b
            5:  lw   s2, 0(sp)
                lw   s1, 4(sp)
                lw   s0, 8(sp)
                lw   ra, 12(sp)
                addi sp, sp, 16
                ret

            # print_hex: a0 as 8 upper-case hex digits. Keeps s registers.
            print_hex:
                addi sp, sp, -12
                sw   ra, 8(sp)
                sw   s0, 4(sp)
                sw   s1, 0(sp)
                mv   s0, a0
                li   s1, 8
            1:  srli a0, s0, 28
                slli s0, s0, 4
                li   t0, 10
                blt  a0, t0, 2f
                addi a0, a0, 'A' - 10 - '0'
            2:  addi a0, a0, '0'
                call print_char
                addi s1, s1, -1
                bnez s1, 1b
                lw   s1, 0(sp)
                lw   s0, 4(sp)
                lw   ra, 8(sp)
                addi sp, sp, 12
                ret

            # print_char: a0 = byte, to COM1 (waiting for room) and to the VGA text cursor.
            # '\\n' moves to the next row, '\\r' to column 0; the screen scrolls at the bottom.
            # Uses only t0..t4.
            print_char:
                li   t0, COM1
            1:  lbu  t1, 5(t0)              # LSR bit 5: room in the transmitter
                andi t1, t1, 0x20
                beqz t1, 1b
                sb   a0, 0(t0)
                li   t0, VGA
                lw   t1, 0x0C(t0)           # CURSOR: column bits 7..0, row bits 15..8
                andi t2, t1, 0xFF
                srli t3, t1, 8
                andi t3, t3, 0xFF
                li   t4, 10
                beq  a0, t4, 3f
                li   t4, 13
                beq  a0, t4, 4f
                slli t4, t3, 6              # cell = VRAM + 2 * (row * 80 + column)
                slli t1, t3, 4
                add  t4, t4, t1
                add  t4, t4, t2
                slli t4, t4, 1
                li   t1, VRAM
                add  t4, t4, t1
                sb   a0, 0(t4)
                li   t1, 0x07
                sb   t1, 1(t4)
                addi t2, t2, 1
                li   t1, 80
                blt  t2, t1, 5f
            3:  li   t2, 0                  # next row
                addi t3, t3, 1
                li   t1, 25
                blt  t3, t1, 5f
                li   t3, 24                 # scroll rows 1..24 up one row, blank row 24
                li   t0, VRAM
                li   t1, 960
            6:  lw   t4, 160(t0)
                sw   t4, 0(t0)
                addi t0, t0, 4
                addi t1, t1, -1
                bnez t1, 6b
                li   t1, 0x07200720
                li   t4, 40
            7:  sw   t1, 0(t0)
                addi t0, t0, 4
                addi t4, t4, -1
                bnez t4, 7b
                j    5f
            4:  li   t2, 0                  # carriage return
            5:  slli t3, t3, 8
                or   t2, t2, t3
                li   t0, VGA
                sw   t2, 0x0C(t0)
                ret

            # Trap handler: report and power off with failure code 0xDEAD.
            .align 2
            rom_trap:
                li   t0, SYSCON
                lw   t1, 0x04(t0)
                lw   t2, 0x08(t0)
                add  sp, t1, t2
                la   a0, msg_trap_cause
                call print_string
                csrr a0, mcause
                call print_hex
                la   a0, msg_trap_epc
                call print_string
                csrr a0, mepc
                call print_hex
                la   a0, msg_trap_val
                call print_string
                csrr a0, mtval
                call print_hex
                la   a0, msg_crlf
                call print_string
                li   t0, COM1
            1:  lbu  t1, 5(t0)              # LSR bit 6: everything sent
                andi t1, t1, 0x40
                beqz t1, 1b
                li   t0, SYSCON
                li   t1, 0xDEAD3333
                sw   t1, 0x00(t0)
            2:  j    2b

            .section .rodata
            .align 2
            pow10:
                .word 1000000000, 100000000, 10000000, 1000000, 100000, 10000, 1000, 100, 10, 1, 0
            msg_banner:       .asciz "Create Flippies PC  RV32I  "
            msg_kib:          .asciz " KiB RAM\\r\\n"
            msg_no_disk:      .asciz "No disk\\r\\n"
            msg_not_bootable: .asciz "Disk not bootable\\r\\n"
            msg_floppy_boot:  .asciz "Booting from floppy\\r\\n"
            msg_floppy_not_bootable: .asciz "Floppy not bootable\\r\\n"
            msg_trap_cause:   .asciz "TRAP mcause="
            msg_trap_epc:     .asciz " mepc="
            msg_trap_val:     .asciz " mtval="
            msg_crlf:         .asciz "\\r\\n"
            """;

    /** The assembled ROM, built on first use. */
    private static final class Holder {
        static final AsmImage IMAGE = assemble();
    }

    private static AsmImage assemble() {
        AsmResult result = Rv32Assembler.assemble(SOURCE, AsmOptions.at(Rv32Map.ROM_BASE));
        if (!result.ok())
            throw new IllegalStateException("boot ROM does not assemble: " + result.diagnostics());
        return result.image();
    }

    /** The ROM bytes from {@link Rv32Map#ROM_BASE}, trimmed after the last non-zero byte; a fresh copy. */
    public static byte[] image() {
        byte[] full = Holder.IMAGE.flatten(Rv32Map.ROM_BASE, Rv32Map.ROM_SIZE);
        int end = full.length;
        while (end > 0 && full[end - 1] == 0)
            end--;
        return Arrays.copyOf(full, Math.max(end, 1));
    }

    /** Address of a ROM symbol such as {@code print_string}, for tests and the future debugger. */
    public static int symbol(String name) {
        return (int) Holder.IMAGE.symbol(name)
                .orElseThrow(() -> new IllegalArgumentException("no boot ROM symbol " + name));
    }
}
