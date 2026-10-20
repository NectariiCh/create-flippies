package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** COM1 end to end (spec 3.5, 8.4): guest output reaches the host and host input reaches the guest. */
class UartE2eTest {

    @Test
    void helloWorldOverCom1() {
        Machine m = boot("""
                _start:
                    li   sp, STACK_TOP
                    la   a0, msg
                    call puts
                    j    exit
                msg: .asciz "Hello, PC!\\n"
                """);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("Hello, PC!\n", com1(m));
    }

    @Test
    void failureCodeReachesTheHost() {
        Machine m = boot("""
                _start:
                    li   sp, STACK_TOP
                    li   a0, 0x1234
                    j    fail
                """);
        runUntilOff(m, 5);
        assertEquals(MachineState.OFF_BY_GUEST, m.state());
        assertEquals(0x1234, m.exitCode());
    }

    @Test
    void echoesHostBytesUpperCased() {
        // polls LSR.DR, reads RBR, upper-cases letters, writes THR; a '.' ends the program
        Machine m = boot("""
                _start:
                    li   sp, STACK_TOP
                    li   s0, COM1
                loop:
                    lbu  t1, 5(s0)
                    andi t1, t1, 1
                    beqz t1, loop
                    lbu  a0, 0(s0)
                    li   t1, 'a'
                    blt  a0, t1, 1f
                    li   t1, 'z'
                    bgt  a0, t1, 1f
                    addi a0, a0, -32
                1:  mv   s1, a0
                    call putc
                    li   t1, '.'
                    bne  s1, t1, loop
                    j    exit
                """);
        byte[] in = "hello, world 42.".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(in.length, m.com(0).hostWrite(in, 0, in.length));
        runUntilOff(m, 10);
        assertCleanExit(m);
        assertEquals("HELLO, WORLD 42.", com1(m));
    }

    @Test
    void echoesInputArrivingAcrossTicks() {
        // a sleeping-free echo that keeps running: the host types one byte per tick
        Machine m = boot("""
                _start:
                    li   s0, COM1
                loop:
                    lbu  t1, 5(s0)
                    andi t1, t1, 1
                    beqz t1, loop
                    lbu  t2, 0(s0)
                    sb   t2, 0(s0)
                    j    loop
                """);
        StringBuilder sent = new StringBuilder();
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            byte b = (byte) ('A' + i);
            m.com(0).hostWrite(new byte[] { b }, 0, 1);
            sent.append((char) b);
            runTicks(m, 1);
            got.append(com1(m));
        }
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(sent.toString(), got.toString());
        assertTrue(m.clock().now() >= 20 * TICK_CYCLES);
    }
}
