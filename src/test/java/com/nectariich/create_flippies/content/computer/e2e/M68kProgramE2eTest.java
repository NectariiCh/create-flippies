package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Assembled 68k programs on both machines (spec 10.12.4): COM1 output and echo, recursion,
 * string routines, MOVEM, LINK and UNLK calling conventions, VGA text and graphics, and the boot
 * ROM's trap handler reporting illegal instructions, privilege violations and address errors.
 */
class M68kProgramE2eTest {

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void helloOverCom1(CpuModel model) {
        Machine m = boot(model, """
                _start: lea     msg(pc),a0
                        bsr     puts
                        bra     exit
                msg:    dc.b    "Hello, PC!",10,0
                """);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("Hello, PC!\n", com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void failureCodeReachesTheHost(CpuModel model) {
        Machine m = boot(model, """
                _start: move.l  #$1234,d0
                        bra     fail
                """);
        runUntilOff(m, 5);
        assertEquals(MachineState.OFF_BY_GUEST, m.state());
        assertEquals(0x1234, m.exitCode());
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void echoesHostBytesUpperCased(CpuModel model) {
        // polls LSR.DR, reads RBR, upper-cases letters, writes THR; a '.' ends the program
        Machine m = boot(model, """
                _start: lea     COM1,a2
                loop:   btst    #0,5(a2)
                        beq.s   loop
                        move.b  (a2),d0
                        cmp.b   #'a',d0
                        bcs.s   1f
                        cmp.b   #'z',d0
                        bhi.s   1f
                        sub.b   #32,d0
                1:      bsr     putc
                        cmp.b   #'.',d0
                        bne.s   loop
                        bra     exit
                """);
        byte[] in = "hello, world 42.".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(in.length, m.com(0).hostWrite(in, 0, in.length));
        runUntilOff(m, 10);
        assertCleanExit(m);
        assertEquals("HELLO, WORLD 42.", com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void recursiveFibonacciAndFactorial(CpuModel model) {
        // fib keeps n on the stack across its two calls; fact keeps n in a LINK frame local and
        // multiplies 32 by 16 bits with two MULU.W (the 68000 has no 32-bit multiply)
        AsmImage program = assemble(model, """
                _start: moveq   #20,d0
                        bsr     fib
                        move.l  d0,fib20
                        bsr     puthex
                        moveq   #10,d0
                        bsr     putc
                        moveq   #12,d0
                        bsr     fact
                        move.l  d0,fact12
                        bsr     puthex
                        moveq   #10,d0
                        bsr     putc
                        bra     exit

                fib:    cmp.l   #2,d0
                        bcs.s   1f                  ; fib(0) = 0, fib(1) = 1
                        move.l  d0,-(sp)
                        subq.l  #1,d0
                        bsr     fib
                        move.l  (sp),d1
                        move.l  d0,(sp)             ; keep fib(n - 1)
                        move.l  d1,d0
                        subq.l  #2,d0
                        bsr     fib
                        add.l   (sp)+,d0
                1:      rts

                fact:   link    a6,#-4
                        move.l  d0,-4(a6)
                        cmp.l   #1,d0
                        bls.s   1f
                        subq.l  #1,d0
                        bsr     fact
                        move.l  -4(a6),d1
                        bsr     mul32
                        bra.s   2f
                1:      moveq   #1,d0
                2:      unlk    a6
                        rts

                mul32:  movem.l d1-d2,-(sp)         ; d0 = d0 * d1.w
                        move.l  d0,d2
                        swap    d2
                        mulu.w  d1,d2
                        swap    d2
                        clr.w   d2
                        mulu.w  d1,d0
                        add.l   d2,d0
                        movem.l (sp)+,d1-d2
                        rts

                        .bss
                fib20:  ds.l    1
                fact12: ds.l    1
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 20);
        assertCleanExit(m);
        assertEquals("00001A6D\n1C8CFC00\n", com1(m));
        assertEquals(6765, ram32(m, program, "fib20"));
        assertEquals(479001600, ram32(m, program, "fact12"));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void memcpyAndStrlen(CpuModel model) {
        AsmImage program = assemble(model, """
                _start: lea     buf,a0
                        lea     msg(pc),a1
                        move.l  #msg_end-msg,d0
                        bsr     memcpy
                        lea     buf,a0
                        bsr     strlen
                        move.l  d0,len
                        bsr     puts
                        bsr     puthex
                        bra     exit

                strlen: move.l  a0,a1               ; d0 = length of the string at a0
                1:      tst.b   (a1)+
                        bne.s   1b
                        move.l  a1,d0
                        sub.l   a0,d0
                        subq.l  #1,d0
                        rts

                memcpy: move.l  a0,-(sp)            ; d0 bytes from a1 to a0
                        subq.l  #1,d0
                        bcs.s   2f
                1:      move.b  (a1)+,(a0)+
                        subq.l  #1,d0
                        bcc.s   1b
                2:      move.l  (sp)+,a0
                        rts

                msg:    dc.b    "copied through memcpy: ",0
                msg_end:
                        .bss
                len:    ds.l    1
                buf:    ds.b    64
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("copied through memcpy: 00000017", com1(m));
        assertEquals(23, ram32(m, program, "len"));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void movemLinkAndUnlkCallingConvention(CpuModel model) {
        // sum3(a, b, c) takes its arguments on the stack through a LINK frame, saves d2-d7/a2-a5
        // with MOVEM, clobbers all of them and restores them; the caller checks every register
        // and the stack pointer after it pops the arguments
        AsmImage program = assemble(model, """
                _start: movem.l init(pc),d2-d7/a2-a5
                        move.l  sp,a1
                        move.l  #300,-(sp)
                        move.l  #20,-(sp)
                        move.l  #1,-(sp)
                        bsr     sum3
                        lea     12(sp),sp
                        cmp.l   a1,sp
                        bne     bad
                        cmp.l   #321,d0
                        bne     bad
                        movem.l d2-d7/a2-a5,saved
                        lea     init(pc),a0
                        lea     saved,a2
                        moveq   #9,d1
                1:      cmpm.l  (a0)+,(a2)+
                        bne     bad
                        dbra    d1,1b
                        bsr     puthex
                        bra     exit
                bad:    moveq   #1,d0
                        bra     fail

                sum3:   link    a6,#-8
                        movem.l d2-d7/a2-a5,-(sp)
                        move.l  8(a6),d0
                        add.l   12(a6),d0
                        add.l   16(a6),d0
                        move.l  d0,-4(a6)
                        moveq   #-1,d2
                        move.l  d2,d3
                        move.l  d2,d4
                        move.l  d2,d5
                        move.l  d2,d6
                        move.l  d2,d7
                        move.l  d2,a2
                        move.l  d2,a3
                        move.l  d2,a4
                        move.l  d2,a5
                        movem.l (sp)+,d2-d7/a2-a5
                        move.l  -4(a6),d0
                        unlk    a6
                        rts

                init:   dc.l    2,3,4,5,6,7,$A2,$A3,$A4,$A5
                        .bss
                saved:  ds.l    10
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("00000141", com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void vgaTextWithBigEndianCells(CpuModel model) {
        // a word write puts the character in the cell's first byte and the attribute in the second
        Machine m = boot(model, """
                _start: move.w  #$481F,VRAM
                        move.w  #$491F,VRAM+2
                        move.l  #2,VGA+$0C          ; cursor column 2, row 0
                1:      stop    #$2700
                        bra.s   1b
                """);
        long before = m.vga().generation();
        runTicks(m, 1);
        VgaFrame f = m.vga().snapshot();
        assertEquals(before + 1, f.generation());
        assertEquals(VgaDisplay.MODE_TEXT, f.mode());
        assertEquals('H', f.pixelByte(0));
        assertEquals(0x1F, f.pixelByte(1));
        assertEquals('I', f.pixelByte(2));
        assertEquals(0x1F, f.pixelByte(3));
        assertEquals(2, f.cursorColumn());
        assertEquals(0, f.cursorRow());
        assertTrue(m.cpu().pc() > PROG, "sleeping in STOP");
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void vgaGraphicsDiagonalAndPalette(CpuModel model) {
        Machine m = boot(model, """
                _start: lea     VGA,a1
                        move.l  #1,(a1)             ; MODE 1: 320x200 at 8 bpp
                        move.l  #$FF0000,$404(a1)   ; PALETTE[1] red
                        move.l  #2,$2C(a1)          ; PAL_INDEX
                        move.l  #$00FF00,$30(a1)    ; PAL_DATA: entry 2 green
                        lea     VRAM,a0
                        move.w  #199,d1
                1:      move.b  #1,(a0)             ; pixel (i, i)
                        lea     321(a0),a0
                        dbra    d1,1b
                        move.b  #2,VRAM+319         ; pixel (319, 0)
                2:      stop    #$2700
                        bra.s   2b
                """);
        runTicks(m, 1);
        VgaFrame f = m.vga().snapshot();
        assertEquals(VgaDisplay.MODE_320X200X8, f.mode());
        assertEquals(0xFF0000, f.paletteEntry(1));
        assertEquals(0x00FF00, f.paletteEntry(2));
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int expected = x == y ? 1 : (x == 319 && y == 0 ? 2 : 0);
                assertEquals(expected, f.pixelByte(y * 320 + x), "pixel " + x + "," + y);
            }
        assertEquals(200, m.vga().dirtyRowsSince(0).cardinality());
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void illegalInstructionReachesTheRomTrapHandler(CpuModel model) {
        Machine m = boot(model, """
                _start: nop
                        illegal
                """);
        runUntilOff(m, 5);
        assertEquals(M68kBootRom.TRAP_EXIT_CODE, m.exitCode());
        assertEquals("TRAP vector=04 pc=00001002 sr=2700\r\n", com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void privilegeViolationFromUserMode(CpuModel model) {
        Machine m = boot(model, """
                _start: move.w  #$0000,sr           ; to user mode
                        move.w  #$2700,sr           ; privileged
                """);
        runUntilOff(m, 5);
        assertEquals(M68kBootRom.TRAP_EXIT_CODE, m.exitCode());
        assertEquals("TRAP vector=08 pc=00001004 sr=0000\r\n", com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void oddJumpIsAnAddressErrorWithTheAddress(CpuModel model) {
        Machine m = boot(model, """
                _start: jmp     $2001
                """);
        runUntilOff(m, 5);
        assertEquals(M68kBootRom.TRAP_EXIT_CODE, m.exitCode());
        // 68000: group 0 frame, PC = instruction + 2 (no prefetch model); 68040: format 2, PC =
        // the jump, address bit 0 cleared
        assertEquals(model == CpuModel.CPU_68000
                        ? "TRAP vector=03 pc=00001002 sr=2700 addr=00002001\r\n"
                        : "TRAP vector=03 pc=00001000 sr=2700 addr=00002000\r\n",
                com1(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void oddWordReadFaultsOnlyOnThe68000(CpuModel model) {
        Machine m = boot(model, """
                _start: move.l  #$11223344,$2000
                        move.l  #$55667788,$2004
                        move.w  $2001,d0
                        bsr     puthex
                        bra     exit
                """);
        runUntilOff(m, 5);
        if (model == CpuModel.CPU_68000) {
            assertEquals(M68kBootRom.TRAP_EXIT_CODE, m.exitCode());
            assertEquals("TRAP vector=03 pc=00001012 sr=2700 addr=00002001\r\n", com1(m));
        } else {
            assertCleanExit(m);
            assertEquals("00002233", com1(m));
        }
    }
}
