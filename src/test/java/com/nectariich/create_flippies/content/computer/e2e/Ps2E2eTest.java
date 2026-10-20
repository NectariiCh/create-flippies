package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import org.junit.jupiter.api.Test;

/** PS/2 end to end (spec 3.6, 8.4): host key and mouse events reach guest code. */
class Ps2E2eTest {

    /**
     * Discards the keyboard's power-on 0xAA by polling, then sleeps in WFI with the keyboard IRQ
     * routed through the interrupt controller (line 3, MEI). The handler claims the line, drains
     * the keyboard, writes each scancode in hex to VGA text cells 0, 3, 6, ... (attribute 0x1F)
     * and the raw byte to COM1. Three scancodes end the program.
     */
    private static final String KEYBOARD_PROGRAM = """
            _start:
                li   sp, STACK_TOP
                la   t0, handler
                csrw mtvec, t0
                li   s0, PS2
                li   t0, 2
                sw   t0, 0x08(s0)           # CTRL: port enabled, IRQ off
            1:  lw   t0, 0x04(s0)           # discard the power-on bytes
                andi t0, t0, 1
                beqz t0, 2f
                lbu  t0, 0(s0)
                j    1b
            2:  li   t0, PIC
                li   t1, 1 << 3
                sw   t1, 0x04(t0)           # ENABLE line 3
                li   t0, 3
                sw   t0, 0x08(s0)           # CTRL: port enabled, IRQ on
                li   t0, 0x800
                csrs mie, t0                # MEIE
                csrsi mstatus, 8            # MIE
            main:
                wfi
                lw   t0, count
                li   t1, 3
                blt  t0, t1, main
                j    exit

            .align 2
            handler:
                addi sp, sp, -24
                sw   ra, 0(sp)
                sw   t0, 4(sp)
                sw   t1, 8(sp)
                sw   t2, 12(sp)
                sw   t3, 16(sp)
                sw   a0, 20(sp)
                li   t0, PIC
                lw   t1, 0x08(t0)           # CLAIM
                li   t2, 3
                bne  t1, t2, 9f
            2:  li   t0, PS2
                lw   t1, 0x04(t0)
                andi t1, t1, 1
                beqz t1, 9f
                lbu  a0, 0(t0)
                la   t2, count
                lw   t3, 0(t2)
                addi t1, t3, 1
                sw   t1, 0(t2)
                slli t1, t3, 2              # cell offset = count * 3 cells * 2 bytes
                slli t3, t3, 1
                add  t3, t3, t1
                li   t1, VRAM
                add  t3, t3, t1
                la   t2, hexchars
                srli t1, a0, 4
                add  t1, t1, t2
                lbu  t1, 0(t1)
                sb   t1, 0(t3)
                andi t1, a0, 15
                add  t1, t1, t2
                lbu  t1, 0(t1)
                sb   t1, 2(t3)
                li   t1, 0x1F
                sb   t1, 1(t3)
                sb   t1, 3(t3)
                call putc
                j    2b
            9:  lw   a0, 20(sp)
                lw   t3, 16(sp)
                lw   t2, 12(sp)
                lw   t1, 8(sp)
                lw   t0, 4(sp)
                lw   ra, 0(sp)
                addi sp, sp, 24
                mret

            .data
            count:    .word 0
            hexchars: .ascii "0123456789ABCDEF"
            """;

    @Test
    void keyboardScancodesThroughInterruptHandlerToVgaAndCom1() {
        Machine m = boot(KEYBOARD_PROGRAM);
        runTicks(m, 1);
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(0, com1Bytes(m).length, "power-on 0xAA discarded, nothing typed yet");
        long idleCycles = m.clock().now();

        m.keyboard().keyDown(Ps2Key.A);
        m.keyboard().keyUp(Ps2Key.A);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertArrayEquals(new byte[] { 0x1C, (byte) 0xF0, 0x1C }, com1Bytes(m));

        VgaFrame f = m.vga().snapshot();
        String[] expected = { "1C", "F0", "1C" };
        for (int i = 0; i < 3; i++) {
            int cell = i * 3 * 2;
            assertEquals(expected[i].charAt(0), f.pixelByte(cell), "cell " + i);
            assertEquals(0x1F, f.pixelByte(cell + 1));
            assertEquals(expected[i].charAt(1), f.pixelByte(cell + 2));
            assertEquals(0x1F, f.pixelByte(cell + 3));
        }
        assertTrue(idleCycles >= TICK_CYCLES, "the sleeping guest still ran its whole first tick");
    }

    @Test
    void extendedKeyMakeAndBreak() {
        Machine m = boot(KEYBOARD_PROGRAM);
        runTicks(m, 1);
        m.keyboard().keyDown(Ps2Key.RIGHT_CTRL);
        m.keyboard().keyUp(Ps2Key.RIGHT_CTRL);
        runUntilOff(m, 5);
        // the handler drains every queued byte before the main loop sees the count
        assertArrayEquals(new byte[] { (byte) 0xE0, 0x14, (byte) 0xE0, (byte) 0xF0, 0x14 }, com1Bytes(m));
    }

    @Test
    void mousePacketAfterEnablingReporting() {
        // discard 0xAA 0x00, send 0xF4 (enable reporting), expect 0xFA, then print the next
        // six bytes (two packets) to COM1 and power off
        Machine m = boot("""
                _start:
                    li   sp, STACK_TOP
                    li   s0, PS2 + 0x10
                    li   t0, 2
                    sw   t0, 0x08(s0)           # mouse port enabled
                1:  lw   t0, 0x04(s0)
                    andi t0, t0, 1
                    beqz t0, 2f
                    lbu  t0, 0(s0)
                    j    1b
                2:  li   t0, 0xF4
                    sb   t0, 0(s0)
                    call getb
                    li   t0, 0xFA
                    bne  a0, t0, bad
                    li   s1, 6
                3:  call getb
                    call putc
                    addi s1, s1, -1
                    bnez s1, 3b
                    j    exit
                bad:
                    li   a0, 1
                    j    fail
                getb:
                    lw   t0, 0x04(s0)
                    andi t0, t0, 1
                    beqz t0, getb
                    lbu  a0, 0(s0)
                    ret
                """);
        runTicks(m, 1);
        m.mouse().buttons(1);
        m.mouse().move(5, -3);
        runUntilOff(m, 5);
        assertCleanExit(m);
        // the button change is reported at once (left | always-1 bit 3), the movement one sample
        // period (10 ms) later: left | bit 3 | y sign (bit 5), dx = 5, dy = -3
        assertArrayEquals(new byte[] { 0x09, 0x00, 0x00, 0x29, 0x05, (byte) 0xFD }, com1Bytes(m));
    }
}
