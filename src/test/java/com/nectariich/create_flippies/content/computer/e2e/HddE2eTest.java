package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The HDD end to end (spec 3.8, 8.4): DMA both ways, the image holds the data and survives a move to a new PC. */
class HddE2eTest {

    private static final long DISK_BYTES = 4L * 1024 * 1024;
    private static final int LBA = 5;

    /** Byte i of the test sector. */
    private static int pattern(int i) {
        return (3 + 7 * i) & 0xFF;
    }

    private static byte[] patternSector() {
        byte[] s = new byte[512];
        for (int i = 0; i < 512; i++)
            s[i] = (byte) pattern(i);
        return s;
    }

    /** Checks dst against the pattern; fails with code 3 on a mismatch. Uses t0..t4. */
    private static final String VERIFY = """
            verify:
                la   t0, dst
                li   t1, 512
                li   t2, 3
            1:  lbu  t3, 0(t0)
                andi t4, t2, 0xFF
                bne  t3, t4, 2f
                addi t0, t0, 1
                addi t2, t2, 7
                addi t1, t1, -1
                bnez t1, 1b
                ret
            2:  li   a0, 3
                j    fail
            """;

    @Test
    void writeSectorReadItBackAndKeepTheImage() {
        SparseDiskImage disk = new SparseDiskImage(DISK_BYTES);
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, src                # src[i] = 3 + 7 * i
                    li   t1, 512
                    li   t2, 3
                1:  sb   t2, 0(t0)
                    addi t0, t0, 1
                    addi t2, t2, 7
                    addi t1, t1, -1
                    bnez t1, 1b

                    li   s0, HDD
                    li   t0, %d
                    sw   t0, 0x08(s0)           # LBA
                    li   t0, 1
                    sw   t0, 0x0C(s0)           # COUNT
                    la   t0, src
                    sw   t0, 0x10(s0)           # DMA_ADDR
                    li   t0, 2
                    sw   t0, 0x00(s0)           # WRITE
                    call hdd_wait

                    la   t0, dst
                    sw   t0, 0x10(s0)
                    li   t0, 1
                    sw   t0, 0x00(s0)           # READ
                    call hdd_wait
                    call verify
                    la   a0, ok
                    call puts
                    j    exit

                hdd_wait:                       # poll for DONE or ERROR; error fails with code 2
                    lw   t0, 0x04(s0)
                    andi t1, t0, 6
                    beqz t1, hdd_wait
                    li   t1, 6
                    sw   t1, 0x04(s0)
                    andi t1, t0, 4
                    bnez t1, 1f
                    ret
                1:  li   a0, 2
                    j    fail
                %s
                ok: .asciz "OK\\n"
                .bss
                .align 4
                src: .space 512
                dst: .space 512
                """.formatted(LBA, VERIFY));
        Machine m = boot(program, disk);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("OK\n", com1(m));

        // the image holds the pattern at byte LBA * 512, and nothing around it
        byte[] sectors = new byte[3 * 512];
        disk.read(LBA - 1, 3, sectors, 0);
        assertArrayEquals(patternSector(), java.util.Arrays.copyOfRange(sectors, 512, 1024));
        for (int i = 0; i < 512; i++) {
            assertEquals(0, sectors[i]);
            assertEquals(0, sectors[1024 + i]);
        }
        assertEquals(1, disk.allocatedChunks(), "sparse: only the written chunk exists");

        // persistence: the dirty chunks, saved and loaded into a fresh image, boot on a new PC
        Map<Integer, byte[]> saved = disk.takeDirtyChunks();
        assertEquals(1, saved.size());
        assertEquals(0, disk.dirtyChunks().length);
        SparseDiskImage moved = new SparseDiskImage(DISK_BYTES);
        saved.forEach(moved::loadChunk);
        readBackWithInterrupt(moved);
    }

    /** A second PC reads the sector with the HDD interrupt (line 5, MEI) while sleeping in WFI. */
    private void readBackWithInterrupt(SparseDiskImage disk) {
        AsmImage program = assemble("""
                _start:
                    li   sp, STACK_TOP
                    la   t0, handler
                    csrw mtvec, t0
                    li   t0, PIC
                    li   t1, 1 << 5
                    sw   t1, 0x04(t0)           # ENABLE line 5
                    li   s0, HDD
                    li   t0, 1
                    sw   t0, 0x18(s0)           # IRQ_ENABLE
                    li   t0, 0x800
                    csrs mie, t0
                    csrsi mstatus, 8
                    li   t0, %d
                    sw   t0, 0x08(s0)
                    li   t0, 1
                    sw   t0, 0x0C(s0)
                    la   t0, dst
                    sw   t0, 0x10(s0)
                    rdcycle s1
                    li   t0, 1
                    sw   t0, 0x00(s0)           # READ
                wait:
                    wfi
                    lw   s3, status
                    beqz s3, wait
                    rdcycle s2
                    sub  s2, s2, s1
                    sw   s2, elapsed, t0
                    andi t0, s3, 4
                    bnez t0, 1f
                    call verify
                    la   a0, ok
                    call puts
                    j    exit
                1:  li   a0, 2
                    j    fail

                .align 2
                handler:
                    addi sp, sp, -8
                    sw   t0, 0(sp)
                    sw   t1, 4(sp)
                    li   t0, HDD
                    lw   t1, 0x04(t0)
                    sw   t1, status, t0
                    li   t0, HDD
                    li   t1, 6
                    sw   t1, 0x04(t0)           # W1C DONE and ERROR drops the line
                    lw   t1, 4(sp)
                    lw   t0, 0(sp)
                    addi sp, sp, 8
                    mret
                %s
                ok: .asciz "OK\\n"
                .data
                .align 2
                status:  .word 0
                elapsed: .word 0
                .bss
                .align 4
                dst: .space 512
                """.formatted(LBA, VERIFY));
        Machine m = boot(program, disk);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("OK\n", com1(m));
        // completion is 500 us + 100 us per sector after the command (6000 cycles at 10 MHz)
        int elapsed = ramWord(m, program, "elapsed");
        assertTrue(elapsed >= 6000 && elapsed < 6100, "read took " + elapsed + " cycles");
        assertFalse(disk.dirtyChunks().length > 0, "reading does not dirty the image");
    }
}
