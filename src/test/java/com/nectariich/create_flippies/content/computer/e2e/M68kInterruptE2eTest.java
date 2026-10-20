package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Interrupts on the 68k machines (spec 10.8.2, 10.12.4): controller output k on IPL k, output 0
 * also on IPL 4, autovectors 25..31, the SR mask, and the edge-triggered level 7.
 */
class M68kInterruptE2eTest {

    /**
     * The keyboard's line 3 routed to output 2 (IPL 2); the handler at autovector 26 drains the
     * PS/2 queue, writes each byte as two hex digits to VGA text and the raw byte to COM1, and
     * records the SR it ran with. The main loop sleeps in STOP #$2000.
     */
    private static final String KEYBOARD = """
            _start: move.l  #kbd_irq,26*4
                    lea     PS2,a2
                    move.l  #2,8(a2)            ; CTRL: port enabled, IRQ off
            1:      move.l  4(a2),d0            ; discard the power-on bytes
                    btst    #0,d0
                    beq.s   2f
                    move.b  (a2),d0
                    bra.s   1b
            2:      move.l  #$2000,PIC+$10      ; ROUTE0: line 3 to output 2
                    move.l  #1<<3,PIC+4         ; ENABLE line 3
                    move.l  #3,8(a2)            ; CTRL: port enabled, IRQ on
            main:   stop    #$2000
                    cmp.l   #3,count
                    bcs.s   main
                    bra     exit

            kbd_irq:
                    movem.l d0-d2/a0-a1,-(sp)
                    move.w  sr,irq_sr
            1:      move.l  PS2+4,d0
                    btst    #0,d0
                    beq.s   9f
                    moveq   #0,d0
                    move.b  PS2,d0
                    move.l  count,d1
                    addq.l  #1,count
                    mulu.w  #6,d1               ; three cells per byte
                    lea     VRAM,a0
                    add.l   d1,a0
                    lea     hexchars(pc),a1
                    move.l  d0,d2
                    lsr.b   #4,d2
                    move.b  0(a1,d2.w),(a0)
                    move.b  #$1F,1(a0)
                    move.l  d0,d2
                    and.w   #$F,d2
                    move.b  0(a1,d2.w),2(a0)
                    move.b  #$1F,3(a0)
                    bsr     putc
                    bra.s   1b
            9:      movem.l (sp)+,d0-d2/a0-a1
                    rte

            hexchars: dc.b  "0123456789ABCDEF"
                    .bss
            count:  ds.l    1
            irq_sr: ds.w    1
            """;

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void keyboardThroughIpl2ToVgaAndCom1(CpuModel model) {
        AsmImage program = assemble(model, KEYBOARD);
        Machine m = boot(model, program, null);
        runTicks(m, 1);
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(0, com1Bytes(m).length, "power-on 0xAA discarded, nothing typed yet");

        m.keyboard().keyDown(Ps2Key.A);
        m.keyboard().keyUp(Ps2Key.A);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertArrayEquals(new byte[] {0x1C, (byte) 0xF0, 0x1C}, com1Bytes(m));
        assertEquals(0x2200, ram32(m, program, "irq_sr") >>> 16, "handler ran at IPL 2");
        VgaFrame f = m.vga().snapshot();
        String[] expected = {"1C", "F0", "1C"};
        for (int i = 0; i < 3; i++) {
            int cell = i * 6;
            assertEquals(expected[i].charAt(0), f.pixelByte(cell), "cell " + i);
            assertEquals(0x1F, f.pixelByte(cell + 1));
            assertEquals(expected[i].charAt(1), f.pixelByte(cell + 2));
            assertEquals(0x1F, f.pixelByte(cell + 3));
        }
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void intervalTimerAtIpl4TenTimes(CpuModel model) {
        // reset routing: every line to output 0, which drives IPL 4 (autovector 28)
        AsmImage program = assemble(model, """
                _start: move.l  #timer_irq,28*4
                        move.l  #1<<7,PIC+4         ; ENABLE line 7
                        move.l  TIMER,start_us      ; COUNTER_LO
                        move.l  #1000,TIMER+8       ; PERIOD 1 ms
                        move.l  #3,TIMER+$0C        ; ENABLE, IRQ_ENABLE
                main:   stop    #$2000
                        cmp.l   #10,ticks
                        bcs.s   main
                        clr.l   TIMER+$0C
                        bra     exit
                timer_irq:
                        move.l  #1,TIMER+$10        ; EXPIRED cleared, line 7 drops
                        addq.l  #1,ticks
                        move.w  sr,irq_sr
                        rte
                        .bss
                ticks:  ds.l    1
                start_us: ds.l  1
                irq_sr: ds.w    1
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(10, ram32(m, program, "ticks"));
        assertEquals(0x2400, ram32(m, program, "irq_sr") >>> 16, "handler ran at IPL 4");
        long perMicro = hz(model) / 1_000_000;
        long tenth = (ram32(m, program, "start_us") + 10_000L) * perMicro;
        long total = m.clock().now();
        assertTrue(total >= tenth && total < tenth + 2000 * perMicro, "clock " + total + ", 10th expiry " + tenth);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void busyLoopIsInterruptedWithinOneInstructionOfTheExpiry(CpuModel model) {
        // the handler's first instruction powers off, so the clock at power-off is the expiry
        // plus at most one loop instruction, the interrupt entry and that store
        AsmImage program = assemble(model, """
                _start: move.l  #timer_irq,28*4
                        move.l  #1<<7,PIC+4
                        move.l  TIMER,start_us
                        move.l  #1000,TIMER+8
                        move.l  #7,TIMER+$0C        ; one shot with IRQ
                        move.w  #$2000,sr
                spin:   addq.l  #1,d0
                        bra.s   spin
                timer_irq:
                        move.l  #$5555,SYSCON
                1:      bra.s   1b
                        .bss
                start_us: ds.l  1
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        long perMicro = hz(model) / 1_000_000;
        long due = (ram32(m, program, "start_us") + 1000L) * perMicro;
        long end = m.clock().now();
        assertTrue(end >= due && end < due + 30 * perMicro + 80, "powered off at cycle " + end + ", expiry about " + due);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void maskHoldsALevel2SourceAndLevel7IsTakenOncePerEdge(CpuModel model) {
        // part 1: the timer on output 2 (IPL 2) expires while the mask is 3; nothing is taken
        // until the mask drops to 1. part 2: the timer on output 7 interrupts mask 7 once, the
        // line held high does not retrigger, and a second expiry (a new edge) is taken again
        AsmImage program = assemble(model, """
                _start: move.l  #irq2,26*4
                        move.l  #irq7,31*4
                        move.w  #$2300,sr
                        move.l  #2<<28,PIC+$10      ; ROUTE0: line 7 to output 2
                        move.l  #1<<7,PIC+4
                        move.l  #50,TIMER+8         ; 50 us, one shot
                        move.l  #7,TIMER+$0C
                        move.l  #2000,d1
                1:      dbra    d1,1b               ; well past the expiry
                        move.l  count2,before
                        move.w  #$2100,sr           ; now IPL 2 is above the mask
                        nop
                        move.l  count2,after

                        move.w  #$2700,sr
                        move.l  #7<<28,PIC+$10      ; line 7 to output 7: the non-maskable level
                        move.l  #7,TIMER+$0C
                        move.l  #2000,d1
                2:      dbra    d1,2b
                        move.l  count7,once
                        move.l  #1,TIMER+$10        ; line drops
                        move.l  #7,TIMER+$0C        ; and rises again 50 us later
                        move.l  #2000,d1
                3:      dbra    d1,3b
                        move.l  count7,d0
                        bsr     puthex
                        bra     exit

                irq2:   move.l  #1,TIMER+$10
                        addq.l  #1,count2
                        rte
                irq7:   addq.l  #1,count7           ; leaves the line high
                        move.w  sr,sr7
                        rte
                        .bss
                count2: ds.l    1
                count7: ds.l    1
                before: ds.l    1
                after:  ds.l    1
                once:   ds.l    1
                sr7:    ds.w    1
                """);
        Machine m = boot(model, program, null);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(0, ram32(m, program, "before"), "masked at level 3");
        assertEquals(1, ram32(m, program, "after"), "taken once the mask dropped");
        assertEquals(1, ram32(m, program, "once"), "level 7 taken once while held");
        assertEquals("00000002", com1(m), "a new edge is taken again");
        assertEquals(0x2700, ram32(m, program, "sr7") >>> 16);
    }
}
