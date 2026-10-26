package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.PcE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * to the sender with its own MAC as the source, A checks the payload. Then the faster card of
 * 12.3: a burst of 256 full frames with checksum offload, and RX_INFO read by the receiver.
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

    // ---- faster ethernet (spec 12.3) ----

    /** Where the receiver keeps each frame's RX_INFO, one byte per frame, as an offset into RAM. */
    private static final int RES = 0x12000;
    private static final int BURST = 256, FULL = 1514;

    /**
     * A sends 256 full IPv4/UDP frames (10.88.0.2 to 10.88.0.1, port 50000 to 80) in one go with
     * TX_CSUM on and both checksums left 0 in memory, frame n carrying n in the IPv4
     * identification's low byte and the first payload byte. A 257th must fail with TX_FULL.
     */
    private static final String RV_BURST = """
            .equ TXF, RAM + 0x10000
            _start:
                li   sp, STACK_TOP
                li   s1, NIC
                li   t0, 0x21
                sw   t0, 0x00(s1)           # CTRL: RX_ENABLE, TX_CSUM
                li   s0, TXF
                la   t1, hdr                # the 42-byte header template
                li   t2, 0
                li   t4, 42
            1:  add  t3, t1, t2
                lbu  t3, 0(t3)
                add  t5, s0, t2
                sb   t3, 0(t5)
                addi t2, t2, 1
                blt  t2, t4, 1b
                li   t2, 0                  # source: our MAC
                li   t4, 6
            2:  add  t5, s1, t2
                lbu  t3, 8(t5)
                add  t5, s0, t2
                sb   t3, 6(t5)
                addi t2, t2, 1
                blt  t2, t4, 2b
                li   t2, 42                 # payload byte at offset j = 7 * (j - 42)
                li   t3, 0
                li   t4, 1514
            3:  add  t5, s0, t2
                sb   t3, 0(t5)
                addi t3, t3, 7
                addi t2, t2, 1
                blt  t2, t4, 3b
                sw   s0, 0x10(s1)           # TX_ADDR
                sw   t4, 0x14(s1)           # TX_LEN 1514
                li   s2, 0
                li   s3, 256
            4:  sb   s2, 19(s0)             # IPv4 identification, low byte
                sb   s2, 42(s0)             # first payload byte
                li   t0, 1
                sw   t0, 0x18(s1)           # TX_CMD send
                lw   a0, 0x30(s1)           # ERROR_CODE
                bnez a0, txerr
                addi s2, s2, 1
                blt  s2, s3, 4b
                li   t0, 1
                sw   t0, 0x18(s1)           # a 257th
                lw   t0, 0x30(s1)
                li   t1, 3                  # ERR_TX_FULL
                bne  t0, t1, bad
                la   a0, ok
                call puts
                call exit
            txerr:
                addi a0, a0, 0x20
                j    fail
            bad:
                li   a0, 2
                j    fail
            ok: .asciz "sent 256\\r\\n"
            hdr:
                .byte 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0, 0x08, 0x00
                .byte 0x45, 0x00, 0x05, 0xDC, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00
                .byte 0x0A, 0x58, 0x00, 0x02, 0x0A, 0x58, 0x00, 0x01
                .byte 0xC3, 0x50, 0x00, 0x50, 0x05, 0xC8, 0x00, 0x00
            """;

    private static final String M68K_BURST = """
            TXF     equ $10000
            _start: lea     NIC,a1
                    move.l  #$21,(a1)           ; CTRL: RX_ENABLE, TX_CSUM
                    lea     TXF,a0
                    lea     hdr(pc),a2
                    moveq   #41,d1
            1:      move.b  (a2)+,(a0)+         ; the 42-byte header template
                    dbra    d1,1b
                    lea     TXF,a0
                    moveq   #5,d1
                    moveq   #0,d3
            2:      move.b  8(a1,d3.w),6(a0,d3.w) ; source: our MAC
                    addq.w  #1,d3
                    dbra    d1,2b
                    moveq   #42,d3
                    moveq   #0,d0
            3:      move.b  d0,0(a0,d3.w)       ; payload byte at offset j = 7 * (j - 42)
                    addq.b  #7,d0
                    addq.w  #1,d3
                    cmp.w   #1514,d3
                    bne.s   3b
                    move.l  a0,$10(a1)          ; TX_ADDR
                    move.l  #1514,$14(a1)       ; TX_LEN
                    moveq   #0,d2
            4:      move.b  d2,19(a0)           ; IPv4 identification, low byte
                    move.b  d2,42(a0)           ; first payload byte
                    move.l  #1,$18(a1)          ; TX_CMD send
                    move.l  $30(a1),d0          ; ERROR_CODE
                    bne.s   txerr
                    addq.w  #1,d2
                    cmp.w   #256,d2
                    bne.s   4b
                    move.l  #1,$18(a1)          ; a 257th
                    move.l  $30(a1),d0
                    cmp.l   #3,d0               ; ERR_TX_FULL
                    bne.s   bad
                    lea     ok(pc),a0
                    bsr     puts
                    bra     exit
            txerr:  add.l   #$20,d0
                    bra     fail
            bad:    moveq   #2,d0
                    bra     fail
            ok:     dc.b    "sent 256",13,10,0
            hdr:    dc.b    $FF,$FF,$FF,$FF,$FF,$FF,0,0,0,0,0,0,$08,$00
                    dc.b    $45,$00,$05,$DC,$00,$00,$40,$00,$40,$11,$00,$00
                    dc.b    $0A,$58,$00,$02,$0A,$58,$00,$01
                    dc.b    $C3,$50,$00,$50,$05,$C8,$00,$00
                    even
            """;

    /**
     * B waits for 256 frames, then for each one stores RX_INFO in RES[n], checks RX_LEN and copies
     * the frame to RXF; the queue must end empty.
     */
    private static final String RV_INFO = """
            .equ RXF, RAM + 0x11000
            .equ RES, RAM + 0x12000
            _start:
                li   sp, STACK_TOP
                li   s1, NIC
                li   t0, 1
                sw   t0, 0x00(s1)           # CTRL: RX_ENABLE
                li   t1, 256
            1:  lw   t0, 0x2C(s1)           # RX_COUNT
                bne  t0, t1, 1b
                li   s0, RES
                li   t0, RXF
                sw   t0, 0x24(s1)           # RX_ADDR
                li   s2, 0
                li   s3, 256
                li   s4, 1514
            2:  lw   t0, 0x38(s1)           # RX_INFO
                add  t2, s0, s2
                sb   t0, 0(t2)
                lw   t0, 0x20(s1)           # RX_LEN
                bne  t0, s4, bad
                li   t0, 1
                sw   t0, 0x28(s1)           # RX_CMD copy
                lw   a0, 0x30(s1)           # ERROR_CODE
                bnez a0, rxerr
                addi s2, s2, 1
                blt  s2, s3, 2b
                lw   t0, 0x2C(s1)
                bnez t0, bad
                la   a0, ok
                call puts
                call exit
            rxerr:
                addi a0, a0, 0x20
                j    fail
            bad:
                li   a0, 2
                j    fail
            ok: .asciz "got 256\\r\\n"
            """;

    private static final String M68K_INFO = """
            RXF     equ $11000
            RES     equ $12000
            _start: lea     NIC,a1
                    move.l  #1,(a1)             ; CTRL: RX_ENABLE
            1:      move.l  $2C(a1),d0          ; RX_COUNT
                    cmp.l   #256,d0
                    bne.s   1b
                    lea     RES,a2
                    move.l  #RXF,$24(a1)        ; RX_ADDR
                    move.w  #255,d1
            2:      move.l  $38(a1),d0          ; RX_INFO
                    move.b  d0,(a2)+
                    move.l  $20(a1),d0          ; RX_LEN
                    cmp.l   #1514,d0
                    bne.s   bad
                    move.l  #1,$28(a1)          ; RX_CMD copy
                    move.l  $30(a1),d0          ; ERROR_CODE
                    bne.s   rxerr
                    dbra    d1,2b
                    tst.l   $2C(a1)
                    bne.s   bad
                    lea     ok(pc),a0
                    bsr     puts
                    bra     exit
            rxerr:  add.l   #$20,d0
                    bra     fail
            bad:    moveq   #2,d0
                    bra     fail
            ok:     dc.b    "got 256",13,10,0
                    even
            """;

    private static int field(byte[] f, int at) {
        return (f[at] & 0xFF) << 8 | f[at + 1] & 0xFF;
    }

    /** RFC 1071 by the book over {@code length} bytes of {@code data} from {@code from}, with the 16-bit field at {@code skip} taken as 0. */
    private static int referenceChecksum(byte[] data, int from, int length, int skip, long seed) {
        long sum = seed;
        for (int i = from; i < from + length; i += 2)
            if (i != skip)
                sum += (data[i] & 0xFF) << 8 | (i + 1 < from + length ? data[i + 1] & 0xFF : 0);
        while (sum > 0xFFFF)
            sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) ~sum & 0xFFFF;
    }

    /** The reference UDP checksum of a frame with a 20-byte IPv4 header: pseudo-header, then the datagram. */
    private static int referenceUdp(byte[] f) {
        int length = field(f, 38);
        long pseudo = field(f, 26) + field(f, 28) + field(f, 30) + field(f, 32) + 17 + length;
        int c = referenceChecksum(f, 34, length, 40, pseudo);
        return c == 0 ? 0xFFFF : c;
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void burstOf256FullFramesWithChecksumOffloadAndRxInfo(CpuModel model) {
        Machine a = boot(model, assemble(model, RV_BURST, M68K_BURST), null, MAC_A);
        Machine b = boot(model, assemble(model, RV_INFO, M68K_INFO), null, MAC_B);
        a.nic().setLink(true);
        b.nic().setLink(true);
        // Nothing moves until A is done: all 256 frames wait in its transmit queue at once
        runUntilOff(a, 20);
        assertEquals(0, a.exitCode(), () -> "exit code 0x" + Integer.toHexString(a.exitCode()));
        assertEquals("sent 256\r\n", com1(a));
        assertEquals(BURST, a.nic().outgoingCount());
        runTicks(b, 1);

        byte[] last = null;
        for (int n = 0; n < BURST; n++) {
            byte[] f = a.nic().takeOutgoing();
            assertEquals(FULL, f.length);
            assertEquals((byte) n, f[19], "frames in send order");
            assertEquals((byte) n, f[42]);
            assertArrayEquals(mac(MAC_A), Arrays.copyOfRange(f, 6, 12));
            assertEquals(referenceChecksum(f, 14, 20, 24, 0), field(f, 24), "IPv4 header checksum of frame " + n);
            assertEquals(referenceUdp(f), field(f, 40), "UDP checksum of frame " + n);
            if (n == 100)
                f[1000] ^= 0x10;    // payload: the UDP checksum no longer holds
            if (n == 200)
                f[22]--;            // TTL: the IPv4 header checksum no longer holds
            assertTrue(b.nic().deliver(f, 0, f.length), "frame " + n + " fits the receive queue");
            last = f;
        }
        assertNull(a.nic().takeOutgoing());
        runUntilOff(b, 20);
        assertEquals(0, b.exitCode(), () -> "exit code 0x" + Integer.toHexString(b.exitCode()));
        assertEquals("got 256\r\n", com1(b));
        byte[] info = ramBytes(b, ramAddress(model, RES), BURST);
        for (int n = 0; n < BURST; n++) {
            int expect = n == 100 ? Nic.RX_INFO_IPV4 | Nic.RX_INFO_IP_OK : n == 200 ? Nic.RX_INFO_IPV4
                    : Nic.RX_INFO_IPV4 | Nic.RX_INFO_IP_OK | Nic.RX_INFO_L4_OK;
            assertEquals(expect, info[n], "RX_INFO of frame " + n);
        }
        assertArrayEquals(last, ramBytes(b, ramAddress(model, RXF), FULL), "B's guest holds the last frame");
    }
}
