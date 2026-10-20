package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The HDD from 68k code (spec 10.12.4): write a sector, read it back, check the image, then move
 * the dirty chunks to a fresh image and read the sector on a new machine with the HDD interrupt.
 */
class M68kHddE2eTest {

    private static final long DISK_BYTES = 1024 * 1024;
    private static final int LBA = 5;

    /** Byte i of the test sector, as the guest writes it: 3 + 7 * i. */
    private static byte[] patternSector() {
        byte[] s = new byte[512];
        for (int i = 0; i < 512; i++)
            s[i] = (byte) (3 + 7 * i);
        return s;
    }

    /** Checks dst against the pattern; fails with code 3 on a mismatch. */
    private static final String VERIFY = """
            verify: lea     dst,a0
                    move.w  #511,d1
                    moveq   #3,d2
            1:      cmp.b   (a0)+,d2
                    bne.s   2f
                    addq.b  #7,d2
                    dbra    d1,1b
                    rts
            2:      moveq   #3,d0
                    bra     fail
            """;

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void writeSectorReadItBackAndKeepTheImage(CpuModel model) {
        SparseDiskImage disk = new SparseDiskImage(DISK_BYTES);
        AsmImage program = assemble(model, """
                _start: lea     src,a0              ; src[i] = 3 + 7 * i
                        move.w  #511,d1
                        moveq   #3,d2
                1:      move.b  d2,(a0)+
                        addq.b  #7,d2
                        dbra    d1,1b

                        lea     HDD,a2
                        move.l  #%d,8(a2)           ; LBA
                        move.l  #1,$0C(a2)          ; COUNT
                        move.l  #src,$10(a2)        ; DMA_ADDR
                        move.l  #2,(a2)             ; WRITE
                        bsr     hdd_wait
                        move.l  #dst,$10(a2)
                        move.l  #1,(a2)             ; READ
                        bsr     hdd_wait
                        bsr     verify
                        lea     ok(pc),a0
                        bsr     puts
                        bra     exit

                hdd_wait:                           ; DONE or ERROR; an error fails with code 2
                        move.l  4(a2),d0
                        and.l   #6,d0
                        beq.s   hdd_wait
                        move.l  #6,4(a2)
                        btst    #2,d0
                        bne.s   1f
                        rts
                1:      moveq   #2,d0
                        bra     fail
                %s
                ok:     dc.b    "OK",10,0
                        .bss
                src:    ds.b    512
                dst:    ds.b    512
                """.formatted(LBA, VERIFY));
        Machine m = boot(model, program, disk);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("OK\n", com1(m));

        byte[] sectors = new byte[3 * 512];
        disk.read(LBA - 1, 3, sectors, 0);
        assertArrayEquals(patternSector(), Arrays.copyOfRange(sectors, 512, 1024));
        for (int i = 0; i < 512; i++) {
            assertEquals(0, sectors[i]);
            assertEquals(0, sectors[1024 + i]);
        }

        // persistence: the dirty chunks, saved and loaded into a fresh image, read on a new PC
        Map<Integer, byte[]> saved = disk.takeDirtyChunks();
        assertEquals(1, saved.size());
        SparseDiskImage moved = new SparseDiskImage(DISK_BYTES);
        saved.forEach(moved::loadChunk);
        readBackWithInterrupt(model, moved);
    }

    /** A second PC reads the sector with the HDD interrupt (line 5, output 0, IPL 4) while in STOP. */
    private static void readBackWithInterrupt(CpuModel model, SparseDiskImage disk) {
        AsmImage program = assemble(model, """
                _start: move.l  #hdd_irq,28*4
                        move.l  #1<<5,PIC+4         ; ENABLE line 5
                        lea     HDD,a2
                        move.l  #1,$18(a2)          ; IRQ_ENABLE
                        move.l  #%d,8(a2)
                        move.l  #1,$0C(a2)
                        move.l  #dst,$10(a2)
                        move.l  #1,(a2)             ; READ
                wait:   stop    #$2000
                        move.l  status,d0
                        beq.s   wait
                        btst    #2,d0
                        bne.s   1f
                        bsr     verify
                        lea     ok(pc),a0
                        bsr     puts
                        bra     exit
                1:      moveq   #2,d0
                        bra     fail
                hdd_irq:
                        move.l  HDD+4,status
                        move.l  #6,HDD+4            ; DONE and ERROR cleared: the line drops
                        rte
                %s
                ok:     dc.b    "OK",10,0
                        .bss
                status: ds.l    1
                dst:    ds.b    512
                """.formatted(LBA, VERIFY));
        Machine m = boot(model, program, disk);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("OK\n", com1(m));
        assertFalse(disk.dirtyChunks().length > 0, "reading does not dirty the image");
    }
}
