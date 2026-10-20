package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Freeze and resume (spec 1.5) on the 68k machines. The guest keeps every device busy: the
 * interval timer interrupts it at IPL 4, it streams text out of COM1 at 9600 baud, polls the
 * keyboard, keeps HDD READs in flight, draws in VGA mode 1 and rewrites the palette; on the 68040
 * it also accumulates in an FPU register. One machine runs uninterrupted, its twin is saved and
 * loaded into a brand-new Machine after every tick, and both must agree byte for byte.
 */
class M68kSaveStateE2eTest {

    private static final int ITERATIONS = 40;
    private static final long DISK_BYTES = 1024 * 1024;

    private static String program(CpuModel model) {
        boolean fpu = model == CpuModel.CPU_68040;
        return """
                _start: move.l  #timer_irq,28*4
                        lea     COM1,a0             ; 9600 baud (divisor 12), 8N1, FIFOs on
                        move.b  #$83,3(a0)
                        move.b  #12,(a0)
                        clr.b   1(a0)
                        move.b  #$03,3(a0)
                        move.b  #$07,2(a0)
                        move.l  #1,VGA              ; 320x200 at 8 bpp
                        move.l  #2,PS2+8            ; keyboard on, IRQ off: polled
                        move.l  #1<<7,PIC+4         ; timer line 7, reset routing: IPL 4
                        move.l  #1300,TIMER+8
                        move.l  #3,TIMER+$0C
                        move.w  #$2000,sr
                @INIT@
                        moveq   #0,d6
                loop:   lea     msg(pc),a0
                        bsr     puts
                1:      move.l  PS2+4,d0            ; echo queued key bytes
                        btst    #0,d0
                        beq.s   2f
                        move.b  PS2,d0
                        bsr     putc
                        bra.s   1b
                2:      move.l  HDD+4,d0            ; a 32-sector READ unless one is in flight
                        btst    #0,d0
                        bne.s   3f
                        move.l  #6,HDD+4
                        move.l  d6,d0
                        mulu.w  #3,d0
                        move.l  d0,HDD+8
                        move.l  #32,HDD+$0C
                        move.l  #buf,HDD+$10
                        move.l  #1,HDD
                3:      move.l  d6,d1               ; row d6 mod 200 in colour d6
                        divu.w  #200,d1
                        clr.w   d1
                        swap    d1
                        mulu.w  #320,d1
                        lea     VRAM,a1
                        add.l   d1,a1
                        move.w  #319,d2
                4:      move.b  d6,(a1)+
                        dbra    d2,4b
                        move.l  d6,d0
                        and.l   #$FF,d0
                        move.l  d0,VGA+$2C          ; PAL_INDEX
                        move.l  d6,d1
                        mulu.w  #$0203,d1
                        move.l  d1,VGA+$30          ; PAL_DATA
                @STEP@
                        addq.l  #1,d6
                        cmp.l   #%d,d6
                        bcs     loop
                        move.l  ticks,d0
                        bsr     puthex
                        bra     exit

                timer_irq:
                        move.l  #1,TIMER+$10
                        addq.l  #1,ticks
                        rte

                msg:    dc.b    "frozen in place, back on after loading. ",0
                        .bss
                ticks:  ds.l    1
                fpacc:  ds.d    1
                buf:    ds.b    32*512
                """.formatted(ITERATIONS)
                .replace("@INIT@", fpu ? "        fmove.l #0,fp2\n        fmove.l #$10,fpcr" : "")
                .replace("@STEP@", fpu ? "        fadd.l  d6,fp2\n        fdiv.l  #3,fp2\n        fmove.d fp2,fpacc" : "");
    }

    private static SparseDiskImage disk() {
        SparseDiskImage d = new SparseDiskImage(DISK_BYTES);
        byte[] sector = new byte[512];
        for (int s = 0; s < 200; s++) {
            for (int i = 0; i < 512; i++)
                sector[i] = (byte) (s * 31 + i * 7);
            d.write(s, 1, sector, 0);
        }
        return d;
    }

    private static Machine reload(CpuModel model, byte[] state, DiskImage disk) throws StateFormatException {
        Machine m = new Machine(config(model, disk, false), StandardCpus.INSTANCE);
        m.loadState(state);
        return m;
    }

    private static void hostInput(Machine m, int t) {
        if (t == 2 || t == 5)
            m.keyboard().inject((byte) ('a' + t));
        if (t == 1)
            m.keyboard().keyDown(Ps2Key.Q);
        if (t == 20)
            m.keyboard().keyUp(Ps2Key.Q);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void saveAndLoadEveryTickRunsInLockstep(CpuModel model) throws StateFormatException {
        AsmImage program = assemble(model, program(model));
        Machine a = boot(model, program, disk());
        SparseDiskImage diskB = disk();
        Machine b = boot(model, program, diskB);
        StringBuilder outA = new StringBuilder(), outB = new StringBuilder();
        boolean busyAtASave = false;
        int t = 0;
        for (; t < 120 && a.state() == MachineState.RUNNING; t++) {
            a.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            hostInput(a, t);
            hostInput(b, t);
            outA.append(com1(a));
            outB.append(com1(b));
            if (a.hdd().busy() && a.keyboard().queued() > 0 && a.board().timer().nextEventCycle() != Long.MAX_VALUE)
                busyAtASave = true;
            b = reload(model, b.saveState(), diskB);
            assertArrayEquals(a.saveState(), b.saveState(), "state after tick " + t);
        }
        assertTrue(busyAtASave, "some save caught the timer armed, keys queued and the HDD busy");
        assertTrue(t > 20, "the run spans many saves: " + t + " ticks");
        assertCleanExit(a);
        assertCleanExit(b);
        outA.append(com1(a));
        outB.append(com1(b));
        assertEquals(outA.toString(), outB.toString());
        assertArrayEquals(a.saveState(), b.saveState());
        assertTrue(outA.toString().contains("frozen in place, back on after loading."), outA.toString());
        assertTrue(ram32(a, program, "ticks") > 20, "timer interrupts crossed saves");
        if (model == CpuModel.CPU_68040)
            assertTrue(ram32(a, program, "fpacc") != 0, "the FPU accumulated");
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void oneSaveMidRunIntoAFreshMachineMatchesAnUninterruptedRun(CpuModel model) throws StateFormatException {
        AsmImage program = assemble(model, program(model));
        Machine a = boot(model, program, disk());
        runTicks(a, 4);
        hostInput(a, 1);
        byte[] saved = a.saveState();
        Machine b = reload(model, saved, disk());
        assertEquals(a.clock().now(), b.clock().now(), "emulated time is frozen: no catch-up on load");
        assertEquals(a.cpu().pc(), b.cpu().pc());
        runUntilOff(a, 200);
        runUntilOff(b, 200);
        assertCleanExit(b);
        assertEquals(com1(a), com1(b));
        assertArrayEquals(a.saveState(), b.saveState());
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void aStateDoesNotLoadIntoTheOtherModel(CpuModel model) {
        Machine a = boot(model, assemble(model, program(model)), null);
        runTicks(a, 1);
        CpuModel other = model == CpuModel.CPU_68000 ? CpuModel.CPU_68040 : CpuModel.CPU_68000;
        Machine b = new Machine(config(other, null, false), StandardCpus.INSTANCE);
        StateFormatException e = assertThrows(StateFormatException.class, () -> b.loadState(a.saveState()));
        assertTrue(e.getMessage().contains(model.name()), e.getMessage());
    }
}
