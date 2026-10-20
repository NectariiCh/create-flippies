package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.PcE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The floppy drive end to end (spec 11.1.5, 11.4): boot order floppy then HDD, and guest reads and writes. */
class FloppyE2eTest {

    private static final long HDD_BYTES = 1024 * 1024;

    /** Stores the entry registers (RAM size, capacity, boot device) and prints "booted". */
    private static byte[] bootable(CpuModel model) {
        return bootSector(model, """
                _start:
                    li   t0, RAM + 0x800
                    sw   a0, 0(t0)              # RAM size
                    sw   a1, 4(t0)              # the boot device's sectors
                    sw   a3, 8(t0)              # boot device
                    la   a0, msg
                    jalr a2
                    li   t0, COM1
                1:  lbu  t1, 5(t0)
                    andi t1, t1, 0x40
                    beqz t1, 1b
                    li   t0, SYSCON
                    li   t1, 0x5555
                    sw   t1, 0(t0)
                2:  j    2b
                msg: .asciz "booted\\r\\n"
                """, """
                _start: movem.l d0-d2,$800      ; RAM size, the boot device's sectors, boot device
                        lea     msg(pc),a0
                        jsr     (a1)
                1:      btst    #6,COM1+5
                        beq.s   1b
                        move.l  #$5555,SYSCON
                2:      bra.s   2b
                msg:    dc.b    "booted",13,10,0
                """);
    }

    /** The entry registers the bootable sector stored at RAM offset 0x800. */
    private static int[] entry(Machine m) {
        int at = ramAddress(m.config().cpu(), 0x800);
        return new int[] { ram32(m, at), ram32(m, at + 4), ram32(m, at + 8) };
    }

    private static PagedDiskImage floppyWith(byte[] sector0) {
        PagedDiskImage f = floppy(UUID.randomUUID());
        f.write(0, 1, sector0, 0);
        return f;
    }

    private static SparseDiskImage hddWith(byte[] sector0) {
        SparseDiskImage d = new SparseDiskImage(HDD_BYTES);
        d.write(0, 1, sector0, 0);
        return d;
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void aBootableFloppyBootsFirstWithBootDevice1(CpuModel model) {
        Machine m = powerOnWithRom(model, hddWith(bootable(model)), floppyWith(bootable(model)));
        runUntilOff(m, 40);
        assertEquals(0, m.exitCode());
        assertEquals(banner(model) + "Booting from floppy\r\nbooted\r\n", com1(m));
        assertArrayEquals(new int[] { 1 << 20, FloppyController.SECTORS, 1 }, entry(m));
        assertTrue(m.clock().now() > tickCycles(model) * 8, "the floppy takes about 0.4 s from cold");
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void aNonBootableFloppyFallsBackToTheHdd(CpuModel model) {
        Machine m = powerOnWithRom(model, hddWith(bootable(model)), floppy(UUID.randomUUID()));
        runUntilOff(m, 40);
        assertEquals(0, m.exitCode());
        assertEquals(banner(model) + "Floppy not bootable\r\nbooted\r\n", com1(m));
        assertArrayEquals(new int[] { 1 << 20, (int) (HDD_BYTES / 512), 0 }, entry(m));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void withoutABootableFloppyTheOldMessagesStay(CpuModel model) {
        Machine none = powerOnWithRom(model, null, null);
        runTicks(none, 3);
        assertEquals(banner(model) + "No disk\r\n", com1(none));

        Machine hddOnly = powerOnWithRom(model, new SparseDiskImage(HDD_BYTES), null);
        runTicks(hddOnly, 3);
        assertEquals(banner(model) + "Disk not bootable\r\n", com1(hddOnly));

        Machine floppyOnly = powerOnWithRom(model, null, floppy(UUID.randomUUID()));
        runTicks(floppyOnly, 20);
        assertEquals(banner(model) + "Floppy not bootable\r\n", com1(floppyOnly),
                "\"No disk\" only with neither floppy media nor an HDD");
        assertEquals(MachineState.RUNNING, floppyOnly.state());
    }

    private static final String RV_WRITE_READ = """
            .equ WBUF, 0x80010000
            .equ RBUF, 0x80020000
            _start:
                li   sp, STACK_TOP
                li   s0, WBUF
                li   t1, 0
                li   t2, 1024
            1:  xori t3, t1, 0x5A
                add  t4, s0, t1
                sb   t3, 0(t4)
                addi t1, t1, 1
                blt  t1, t2, 1b
                li   s1, FDC
                li   t0, 1000
                sw   t0, 0x08(s1)           # LBA 1000
                li   t0, 2
                sw   t0, 0x0C(s1)           # COUNT 2
                sw   s0, 0x10(s1)
                sw   t0, 0x00(s1)           # WRITE
                call fwait
                li   s2, RBUF
                sw   s2, 0x10(s1)
                li   t0, 1
                sw   t0, 0x00(s1)           # READ
                call fwait
                li   t1, 0
                li   t2, 1024
            2:  add  t4, s0, t1
                lbu  t3, 0(t4)
                add  t4, s2, t1
                lbu  t5, 0(t4)
                bne  t3, t5, bad
                addi t1, t1, 1
                blt  t1, t2, 2b
                la   a0, ok
                call puts
                call exit
            bad:
                li   a0, 2
                j    fail
            fwait:
                lw   t1, 0x04(s1)
                andi t2, t1, 6
                beqz t2, fwait
                li   t2, 6
                sw   t2, 0x04(s1)
                andi t2, t1, 4
                beqz t2, 1f
                lw   a0, 0x1C(s1)
                addi a0, a0, 0x10
                j    fail
            1:  ret
            ok: .asciz "floppy ok\\r\\n"
            """;

    private static final String M68K_WRITE_READ = """
            WBUF    equ $10000
            RBUF    equ $20000
            _start: lea     WBUF,a0
                    moveq   #0,d1
            1:      move.b  d1,d0
                    eor.b   #$5A,d0
                    move.b  d0,(a0)+
                    addq.w  #1,d1
                    cmp.w   #1024,d1
                    bne.s   1b
                    lea     FDC,a1
                    move.l  #1000,8(a1)         ; LBA 1000
                    moveq   #2,d0
                    move.l  d0,$0C(a1)          ; COUNT 2
                    move.l  #WBUF,$10(a1)
                    move.l  d0,(a1)             ; WRITE
                    bsr     fwait
                    move.l  #RBUF,$10(a1)
                    moveq   #1,d0
                    move.l  d0,(a1)             ; READ
                    bsr     fwait
                    lea     WBUF,a0
                    lea     RBUF,a2
                    move.w  #1023,d1
            2:      cmpm.b  (a0)+,(a2)+
                    bne.s   bad
                    dbra    d1,2b
                    lea     ok(pc),a0
                    bsr     puts
                    bra     exit
            bad:    moveq   #2,d0
                    bra     fail
            fwait:  move.l  4(a1),d0
                    and.l   #6,d0
                    beq.s   fwait
                    moveq   #6,d1
                    move.l  d1,4(a1)
                    btst    #2,d0
                    beq.s   1f
                    move.l  $1C(a1),d0
                    add.l   #$10,d0
                    bra     fail
            1:      rts
            ok:     dc.b    "floppy ok",13,10,0
                    even
            """;

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void writeThenReadBackAndTheImagesDirtyChunks(CpuModel model) {
        AsmImage program = assemble(model, RV_WRITE_READ, M68K_WRITE_READ);
        Machine m = boot(model, program, null);
        PagedDiskImage f = floppy(UUID.randomUUID());
        m.floppy().insert(f, false);
        runUntilOff(m, 60);
        assertEquals(0, m.exitCode(), () -> "exit code 0x" + Integer.toHexString(m.exitCode()));
        assertEquals("floppy ok\r\n", com1(m));
        assertTrue(m.floppy().takeActivity());
        assertTrue(f.hasDirtyChunks());
        Map<Integer, byte[]> dirty = f.takeDirtyChunks();
        assertEquals(1000 * 512 / PagedDiskImage.CHUNK_SIZE, dirty.keySet().iterator().next());
        assertEquals(1, dirty.size());
        byte[] chunk = dirty.get(7);
        int at = 1000 * 512 - 7 * PagedDiskImage.CHUNK_SIZE;
        for (int i = 0; i < 1024; i++)
            assertEquals((byte) (i & 0xFF ^ 0x5A), chunk[at + i], "byte " + i);
    }
}
