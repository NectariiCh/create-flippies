package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.PcE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Two machines on one ethernet cord (spec 11.1.7, 11.4), with a harness that moves frames once
 * per tick as the game will: A broadcasts a frame with its MAC as the source, B echoes it back
 * to the sender with its own MAC as the source, A checks the payload.
 */
class NetworkE2eTest {

    private static final long MAC_A = 0x02_00_00_00_0A_0AL;
    private static final long MAC_B = 0x06_12_34_56_78_9AL;
    /** Where each guest keeps its frames, as offsets into RAM. */
    private static final int TXF = 0x10000, RXF = 0x11000;

    private static final String RV_SENDER = """
            .equ TXF, RAM + 0x10000
            .equ RXF, RAM + 0x11000
            _start:
                li   sp, STACK_TOP
                li   s1, NIC
                li   t0, 1
                sw   t0, 0x00(s1)           # CTRL: RX_ENABLE
                li   s0, TXF
                li   t1, 0                  # destination broadcast, source our MAC
                li   t4, 6
            1:  li   t3, 0xFF
                add  t2, s0, t1
                sb   t3, 0(t2)
                add  t5, s1, t1
                lbu  t3, 8(t5)              # MAC byte t1 at offset 8 + t1
                sb   t3, 6(t2)
                addi t1, t1, 1
                blt  t1, t4, 1b
                li   t3, 0x88
                sb   t3, 12(s0)
                li   t3, 0xB5
                sb   t3, 13(s0)
                li   t1, 0                  # payload[i] = 3 + 5 * i
                li   t3, 3
                li   t4, 50
            2:  add  t2, s0, t1
                sb   t3, 14(t2)
                addi t3, t3, 5
                addi t1, t1, 1
                blt  t1, t4, 2b
                sw   s0, 0x10(s1)           # TX_ADDR
                li   t0, 64
                sw   t0, 0x14(s1)           # TX_LEN
                li   t0, 1
                sw   t0, 0x18(s1)           # TX_CMD send
                lw   a0, 0x30(s1)           # ERROR_CODE
                bnez a0, txerr
            3:  lw   t0, 0x2C(s1)           # RX_COUNT
                beqz t0, 3b
                lw   s2, 0x20(s1)           # RX_LEN
                li   t0, RXF
                sw   t0, 0x24(s1)
                li   t0, 1
                sw   t0, 0x28(s1)           # RX_CMD copy
                li   t0, 64
                bne  s2, t0, bad
                li   t1, 14
            4:  add  t2, s0, t1
                lbu  t3, 0(t2)
                li   t2, RXF
                add  t2, t2, t1
                lbu  t5, 0(t2)
                bne  t3, t5, bad
                addi t1, t1, 1
                blt  t1, s2, 4b
                la   a0, ok
                call puts
                call exit
            txerr:
                addi a0, a0, 0x20
                j    fail
            bad:
                li   a0, 2
                j    fail
            ok: .asciz "echo ok\\r\\n"
            """;

    private static final String M68K_SENDER = """
            TXF     equ $10000
            RXF     equ $11000
            _start: lea     NIC,a1
                    move.l  #1,(a1)             ; CTRL: RX_ENABLE
                    lea     TXF,a0
                    moveq   #5,d1
                    moveq   #0,d3
            1:      move.b  #$FF,0(a0,d3.w)     ; destination broadcast
                    move.b  8(a1,d3.w),6(a0,d3.w) ; source: MAC byte d3 at offset 8 + d3
                    addq.w  #1,d3
                    dbra    d1,1b
                    move.w  #$88B5,12(a0)
                    moveq   #0,d3
                    moveq   #3,d0
            2:      move.b  d0,14(a0,d3.w)      ; payload[i] = 3 + 5 * i
                    addq.b  #5,d0
                    addq.w  #1,d3
                    cmp.w   #50,d3
                    bne.s   2b
                    move.l  a0,$10(a1)          ; TX_ADDR
                    move.l  #64,$14(a1)         ; TX_LEN
                    move.l  #1,$18(a1)          ; TX_CMD send
                    move.l  $30(a1),d0          ; ERROR_CODE
                    bne.s   txerr
            3:      tst.l   $2C(a1)             ; RX_COUNT
                    beq.s   3b
                    move.l  $20(a1),d2          ; RX_LEN
                    move.l  #RXF,$24(a1)
                    move.l  #1,$28(a1)          ; RX_CMD copy
                    cmp.l   #64,d2
                    bne.s   bad
                    lea     TXF+14,a0
                    lea     RXF+14,a2
                    move.w  #49,d1
            4:      cmpm.b  (a0)+,(a2)+
                    bne.s   bad
                    dbra    d1,4b
                    lea     ok(pc),a0
                    bsr     puts
                    bra     exit
            txerr:  add.l   #$20,d0
                    bra     fail
            bad:    moveq   #2,d0
                    bra     fail
            ok:     dc.b    "echo ok",13,10,0
                    even
            """;

    private static final String RV_ECHO = """
            .equ BUF, RAM + 0x10000
            _start:
                li   s1, NIC
                li   t0, 1
                sw   t0, 0x00(s1)           # CTRL: RX_ENABLE
                li   s0, BUF
            loop:
                lw   t0, 0x2C(s1)           # RX_COUNT
                beqz t0, loop
                lw   s2, 0x20(s1)           # RX_LEN
                sw   s0, 0x24(s1)
                li   t0, 1
                sw   t0, 0x28(s1)           # RX_CMD copy
                li   t1, 0
                li   t4, 6
            1:  add  t2, s0, t1
                lbu  t3, 6(t2)              # the source becomes the destination
                sb   t3, 0(t2)
                add  t5, s1, t1
                lbu  t3, 8(t5)              # our MAC becomes the source
                sb   t3, 6(t2)
                addi t1, t1, 1
                blt  t1, t4, 1b
                sw   s0, 0x10(s1)
                sw   s2, 0x14(s1)
                li   t0, 1
                sw   t0, 0x18(s1)           # send it back
                j    loop
            """;

    private static final String M68K_ECHO = """
            BUF     equ $10000
            _start: lea     NIC,a1
                    move.l  #1,(a1)             ; CTRL: RX_ENABLE
                    lea     BUF,a0
            loop:   tst.l   $2C(a1)             ; RX_COUNT
                    beq.s   loop
                    move.l  $20(a1),d2          ; RX_LEN
                    move.l  a0,$24(a1)
                    move.l  #1,$28(a1)          ; RX_CMD copy
                    moveq   #5,d1
                    moveq   #0,d3
            1:      move.b  6(a0,d3.w),0(a0,d3.w) ; the source becomes the destination
                    move.b  8(a1,d3.w),6(a0,d3.w) ; our MAC becomes the source
                    addq.w  #1,d3
                    dbra    d1,1b
                    move.l  a0,$10(a1)
                    move.l  d2,$14(a1)
                    move.l  #1,$18(a1)          ; send it back
                    bra.s   loop
            """;

    /** Both machines on one cord: links up, frames moved after every tick in a fixed order. */
    private static List<byte[]> runPair(Machine a, Machine b, int maxTicks) {
        a.nic().setLink(true);
        b.nic().setLink(true);
        List<byte[]> wire = new ArrayList<>();
        for (int t = 0; t < maxTicks && a.state() == MachineState.RUNNING; t++) {
            a.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            exchange(a, b, wire);
            exchange(b, a, wire);
        }
        return wire;
    }

    private static void exchange(Machine from, Machine to, List<byte[]> wire) {
        byte[] f;
        while ((f = from.nic().takeOutgoing()) != null) {
            wire.add(f);
            to.nic().deliver(f, 0, f.length);
        }
    }

    private static byte[] mac(long mac) {
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++)
            b[i] = (byte) (mac >>> (40 - 8 * i));
        return b;
    }

    private static void echo(CpuModel modelA, CpuModel modelB) {
        Machine a = boot(modelA, assemble(modelA, RV_SENDER, M68K_SENDER), null, MAC_A);
        Machine b = boot(modelB, assemble(modelB, RV_ECHO, M68K_ECHO), null, MAC_B);
        List<byte[]> wire = runPair(a, b, 20);
        runUntilOff(a, 1);
        assertEquals(0, a.exitCode(), () -> "exit code 0x" + Integer.toHexString(a.exitCode()));
        assertEquals("echo ok\r\n", com1(a));
        assertEquals(2, wire.size(), "one frame each way");
        byte[] sent = wire.get(0), back = wire.get(1);
        assertArrayEquals(new byte[] { -1, -1, -1, -1, -1, -1 }, Arrays.copyOfRange(sent, 0, 6));
        assertArrayEquals(mac(MAC_A), Arrays.copyOfRange(sent, 6, 12), "A's source is its MAC");
        assertArrayEquals(mac(MAC_A), Arrays.copyOfRange(back, 0, 6), "B answers to A's MAC");
        assertArrayEquals(mac(MAC_B), Arrays.copyOfRange(back, 6, 12), "B's source is its MAC");
        assertArrayEquals(Arrays.copyOfRange(sent, 12, 64), Arrays.copyOfRange(back, 12, 64));
        byte[] received = ramBytes(a, ramAddress(modelA, RXF), 64);
        assertArrayEquals(back, received, "A's guest holds the echoed frame");
        assertEquals((byte) 3, sent[14]);
        assertEquals((byte) 0x88, sent[12]);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void aSendsBEchoesAChecksThePayloadAndMacs(CpuModel model) {
        echo(model, model);
    }

    @Test
    void mixedPairRv32iAnd68040() {
        echo(CpuModel.RV32I, CpuModel.CPU_68040);
        echo(CpuModel.CPU_68040, CpuModel.RV32I);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void linkDownGivesCode5(CpuModel model) {
        Machine a = boot(model, assemble(model, RV_SENDER, M68K_SENDER), null, MAC_A);
        runUntilOff(a, 5);
        assertEquals(0x20 + Nic.ERR_NO_LINK, a.exitCode());
        assertEquals(0, a.nic().outgoingCount());
        assertTrue((a.nic().read(Nic.STATUS, 4) & Nic.STATUS_TX_ERROR) != 0);
    }
}
