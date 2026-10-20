package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.PcE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Freeze and resume with the in-game board busy (spec 11.4): a guest keeps a floppy READ in
 * flight, sends a frame on every loop while it receives what the host delivers, streams bytes out
 * of COM3 and draws on all three displays. One machine runs uninterrupted; its twin is saved and
 * loaded into a brand-new Machine (with its floppy inserted first, as the game does) after every
 * tick. Both get the same host input and must agree byte for byte after every tick.
 */
class BoardSaveStateE2eTest {

    private static final long MAC = 0x02_00_00_00_00_77L;
    private static final UUID FLOPPY_ID = UUID.fromString("0f10b000-0000-0000-0000-000000000001");

    private static final String RV_PROGRAM = """
            .equ FBUF, RAM + 0x10000
            .equ TXF,  RAM + 0x20000
            .equ RXF,  RAM + 0x21000
            _start:
                li   sp, STACK_TOP
                li   s1, NIC
                li   t0, 1
                sw   t0, 0x00(s1)           # CTRL: RX_ENABLE
                li   s2, FDC
                li   s3, COM3
                li   t1, 0x03
                sb   t1, 3(s3)
                li   t1, 0x07
                sb   t1, 2(s3)
                li   s4, 0                  # floppy reads done
                li   s5, 0                  # loop count
                li   s6, SYSCON
                li   t0, TXF                # a broadcast frame
                li   t1, -1
                sw   t1, 0(t0)
                sh   t1, 4(t0)
                li   t1, 0
                call fread
            loop:
                addi s5, s5, 1
                li   t0, TXF                # send; a full queue is only an error code
                sw   s5, 20(t0)
                sw   t0, 0x10(s1)
                li   t1, 64
                sw   t1, 0x14(s1)
                li   t1, 1
                sw   t1, 0x18(s1)
                li   t1, 6
                sw   t1, 0x04(s1)           # clear TX_DONE and TX_ERROR
                lw   t1, 0x2C(s1)           # take a received frame
                beqz t1, 1f
                li   t1, RXF
                sw   t1, 0x24(s1)
                li   t1, 1
                sw   t1, 0x28(s1)
            1:  lbu  t1, 5(s3)              # COM3: one byte when there is room
                andi t1, t1, 0x20
                beqz t1, 2f
                andi t1, s5, 0x3F
                addi t1, t1, 0x30
                sb   t1, 0(s3)
            2:  andi t2, s5, 0x7FE          # draw on all three displays
                lw   t3, 0x18(s6)
                add  t3, t3, t2
                sb   s5, 0(t3)
                lw   t3, 0x2C(s6)
                add  t3, t3, t2
                sb   s5, 0(t3)
                lw   t3, 0x30(s6)
                add  t3, t3, t2
                sb   s5, 0(t3)
                lw   t1, 0x04(s2)           # floppy: the next read once this one is done
                andi t2, t1, 6
                beqz t2, loop
                li   t2, 6
                sw   t2, 0x04(s2)
                andi t2, t1, 4
                bnez t2, ferr
                addi s4, s4, 1
                li   t2, 6
                beq  s4, t2, done
                slli t1, s4, 4
                call fread
                j    loop
            fread:                          # t1 = LBA; 8 sectors to FBUF
                sw   t1, 0x08(s2)
                li   t0, 8
                sw   t0, 0x0C(s2)
                li   t0, FBUF
                sw   t0, 0x10(s2)
                li   t0, 1
                sw   t0, 0x00(s2)
                ret
            ferr:
                lw   a0, 0x1C(s2)
                addi a0, a0, 0x40
                j    fail
            done:
                li   t0, FBUF
                lw   a0, 0(t0)
                call puthex
                li   a0, 10
                call putc
                call exit
            """;

    private static final String M68K_PROGRAM = """
            FBUF    equ $10000
            TXF     equ $20000
            RXF     equ $21000
            _start: lea     NIC,a1
                    move.l  #1,(a1)             ; CTRL: RX_ENABLE
                    lea     FDC,a2
                    lea     COM3,a3
                    move.b  #$03,3(a3)
                    move.b  #$07,2(a3)
                    moveq   #0,d4               ; floppy reads done
                    moveq   #0,d5               ; loop count
                    lea     TXF,a0              ; a broadcast frame
                    move.l  #-1,(a0)
                    move.w  #-1,4(a0)
                    moveq   #0,d1
                    bsr     fread
            loop:   addq.l  #1,d5
                    lea     TXF,a0              ; send; a full queue is only an error code
                    move.l  d5,20(a0)
                    move.l  a0,$10(a1)
                    move.l  #64,$14(a1)
                    move.l  #1,$18(a1)
                    move.l  #6,4(a1)            ; clear TX_DONE and TX_ERROR
                    tst.l   $2C(a1)             ; take a received frame
                    beq.s   1f
                    move.l  #RXF,$24(a1)
                    move.l  #1,$28(a1)
            1:      btst    #5,5(a3)            ; COM3: one byte when there is room
                    beq.s   2f
                    move.b  d5,d0
                    and.b   #$3F,d0
                    add.b   #$30,d0
                    move.b  d0,(a3)
            2:      move.l  d5,d2               ; draw on all three displays
                    and.l   #$7FE,d2
                    move.l  SYSCON+$18,a4
                    move.b  d5,0(a4,d2.l)
                    move.l  SYSCON+$2C,a4
                    move.b  d5,0(a4,d2.l)
                    move.l  SYSCON+$30,a4
                    move.b  d5,0(a4,d2.l)
                    move.l  4(a2),d0            ; floppy: the next read once this one is done
                    and.l   #6,d0
                    beq     loop
                    move.l  #6,4(a2)
                    btst    #2,d0
                    bne     ferr
                    addq.l  #1,d4
                    cmp.l   #6,d4
                    beq     done
                    move.l  d4,d1
                    lsl.l   #4,d1
                    bsr     fread
                    bra     loop
            fread:  move.l  d1,8(a2)            ; d1 = LBA; 8 sectors to FBUF
                    move.l  #8,$0C(a2)
                    move.l  #FBUF,$10(a2)
                    move.l  #1,(a2)
                    rts
            ferr:   move.l  $1C(a2),d0
                    add.l   #$40,d0
                    bra     fail
            done:   move.l  FBUF,d0
                    bsr     puthex
                    moveq   #10,d0
                    bsr     putc
                    bra     exit
            """;

    /** The same floppy for each machine: sector s holds bytes s * 11 + i * 3. */
    private static PagedDiskImage disk() {
        PagedDiskImage f = floppy(FLOPPY_ID);
        byte[] sector = new byte[512];
        for (int s = 0; s < 160; s++) {
            for (int i = 0; i < 512; i++)
                sector[i] = (byte) (s * 11 + i * 3);
            f.write(s, 1, sector, 0);
        }
        f.takeDirtyChunks();
        return f;
    }

    private static Machine start(CpuModel model, AsmImage program, PagedDiskImage floppy) {
        Machine m = boot(model, program, null, MAC);
        m.floppy().insert(floppy, false);
        m.nic().setLink(true);
        m.com(2).setHostConnected(true);
        return m;
    }

    /** A brand-new machine for the same parts with its floppy inserted, then {@code state} loaded. */
    private static Machine reload(Machine like, byte[] state, PagedDiskImage floppy) throws StateFormatException {
        Machine m = new Machine(like.config(), StandardCpus.INSTANCE);
        m.floppy().insert(floppy, false);
        m.loadState(state);
        return m;
    }

    /**
     * Host side after tick {@code t}: at most 5 frames taken, 2 delivered, COM3 drained and fed.
     * Adds the COM3 bytes drained to {@code com3[0]}.
     */
    private static String host(Machine m, int t, int[] com3) {
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            byte[] f = m.nic().takeOutgoing();
            if (f == null)
                break;
            seen.append(f.length).append(':').append(f[20] & 0xFF).append(f[23] & 0xFF).append(' ');
        }
        for (int i = 0; i < 2; i++) {
            byte[] f = new byte[60];
            Arrays.fill(f, 0, 6, (byte) 0xFF);
            f[14] = (byte) t;
            f[15] = (byte) i;
            m.nic().deliver(f, 0, f.length);
        }
        byte[] buf = new byte[4096];
        int n = m.com(2).hostRead(buf, 0, buf.length);
        com3[0] += n;
        seen.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
        byte[] in = { (byte) ('a' + t % 26), 'x', '\n' };
        m.com(2).hostWrite(in, 0, in.length);
        return seen.toString();
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void saveAndLoadEveryTickRunsInLockstep(CpuModel model) throws StateFormatException {
        AsmImage program = assemble(model, RV_PROGRAM, M68K_PROGRAM);
        Machine a = start(model, program, disk());
        PagedDiskImage diskB = disk();
        Machine b = start(model, program, diskB);
        StringBuilder outA = new StringBuilder(), outB = new StringBuilder();
        boolean everythingInFlight = false;
        int[] com3A = new int[1], com3B = new int[1];
        int t = 0;
        for (; t < 120 && a.state() == MachineState.RUNNING; t++) {
            long[] gen = { a.vga(0).generation(), a.vga(1).generation(), a.vga(2).generation() };
            a.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            int before = com3A[0];
            outA.append(host(a, t, com3A));
            outB.append(host(b, t, com3B));
            boolean drawing = a.vga(0).generation() > gen[0] && a.vga(1).generation() > gen[1]
                    && a.vga(2).generation() > gen[2];
            if (a.floppy().busy() && a.nic().outgoingCount() > 0 && a.nic().incomingCount() > 0 && drawing
                    && com3A[0] > before)
                everythingInFlight = true;
            b = reload(b, b.saveState(), diskB);
            assertArrayEquals(a.saveState(), b.saveState(), "state after tick " + t);
        }
        assertTrue(everythingInFlight, "some save caught a floppy read, frames both ways, COM3 and three displays");
        assertTrue(t > 10, "the run spans many saves: " + t + " ticks");
        assertEquals(MachineState.OFF_BY_GUEST, a.state());
        assertEquals(0, a.exitCode(), () -> "exit code 0x" + Integer.toHexString(a.exitCode()));
        assertEquals(MachineState.OFF_BY_GUEST, b.state());
        assertEquals(outA.toString(), outB.toString());
        assertEquals(com1(a), com1(b));
        assertArrayEquals(a.saveState(), b.saveState());
        assertTrue(outA.toString().contains("64:"), "frames went out");
        assertEquals(com3A[0], com3B[0]);
        assertTrue(com3A[0] > 1000, "COM3 streamed " + com3A[0] + " bytes");
    }
}
