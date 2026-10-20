package com.nectariich.create_flippies.content.computer.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.AsmSegment;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kTarget;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Shared rig for the 68000 and 68040 end-to-end tests (spec 10.12.4): real 68k assembly through
 * {@code M68kAssembler}, loaded into RAM of a real {@link Machine} with {@code M68kCpu}, run tick
 * by tick.
 *
 * {@link #boot} skips the boot ROM's start-up but keeps its trap handler: it turns the ROM overlay
 * off, writes the reset vectors (SSP = top of RAM, PC = {@link #PROG}) and points vectors
 * 2..255 at the ROM's per-vector stubs, as the ROM would, so a stray exception prints
 * "TRAP vector=..." and powers off with 0xDEAD. A test that wants its own handler overwrites the
 * vector. Guest programs sit between {@link #PRELUDE} (device equates) and {@link #LIB} (COM1 and
 * power routines). The 68000 runs at 8 MHz, the 68040 at 25 MHz, both with 1 MiB of RAM.
 */
final class M68kE2e {

    private M68kE2e() {
    }

    static final int PROG = 0x1000;
    static final int RAM_BYTES = 1 << 20;
    static final long BIG_BUDGET = 10_000_000_000L;
    static final long FIXED_EPOCH = 1_700_000_000L;

    static int hz(CpuModel model) {
        return model == CpuModel.CPU_68040 ? 25_000_000 : 8_000_000;
    }

    static long tickCycles(CpuModel model) {
        return hz(model) / 20;
    }

    static M68kTarget target(CpuModel model) {
        return model == CpuModel.CPU_68040 ? M68kTarget.M68040 : M68kTarget.M68000;
    }

    /** Device equates; no code, so the program's first line sits at PROG. */
    static final String PRELUDE = """
            SYSCON      equ $FFF00000
            PIC         equ $FFF01000
            COM1        equ $FFF02000
            COM2        equ $FFF03000
            PS2         equ $FFF04000
            HDD         equ $FFF05000
            VGA         equ $FFF06000
            TIMER       equ $FFF07000
            RTC         equ $FFF08000
            VRAM        equ $FFE00000
            STACK_TOP   equ $100000
                    .text
            """;

    /**
     * Guest library: putc (D0.b), puts (A0 = string), puthex (D0, 8 digits), exit (waits until
     * COM1 has sent everything, then POWER 0x5555), fail (D0 = code, POWER 0x3333 | code << 16).
     * putc, puts and puthex keep every register.
     */
    static final String LIB = """

                    .text
                    even
            putc:   move.l  a0,-(sp)
                    lea     COM1,a0
            1:      btst    #5,5(a0)
                    beq.s   1b
                    move.b  d0,(a0)
                    move.l  (sp)+,a0
                    rts
            puts:   movem.l d0/a0,-(sp)
            1:      move.b  (a0)+,d0
                    beq.s   2f
                    bsr     putc
                    bra.s   1b
            2:      movem.l (sp)+,d0/a0
                    rts
            puthex: movem.l d0-d2,-(sp)
                    move.l  d0,d1
                    moveq   #7,d2
            1:      rol.l   #4,d1
                    move.l  d1,d0
                    and.w   #$F,d0
                    cmp.b   #10,d0
                    bcs.s   2f
                    addq.b  #7,d0
            2:      add.b   #$30,d0
                    bsr     putc
                    dbra    d2,1b
                    movem.l (sp)+,d0-d2
                    rts
            drain:  move.l  a0,-(sp)
                    lea     COM1,a0
            1:      btst    #6,5(a0)
                    beq.s   1b
                    move.l  (sp)+,a0
                    rts
            exit:   bsr     drain
                    move.l  #$5555,SYSCON
            1:      bra.s   1b
            fail:   bsr     drain
                    swap    d0
                    clr.w   d0
                    or.w    #$3333,d0
                    move.l  d0,SYSCON
            1:      bra.s   1b
            """;

    /** Assembles a guest program for PROG between the prelude and the library; fails the test on any error. */
    static AsmImage assemble(CpuModel model, String program) {
        AsmResult r = M68kAssembler.assemble(PRELUDE + program + LIB, AsmOptions.at(PROG), target(model));
        if (!r.ok())
            fail("guest program does not assemble for the " + model.displayName() + ": " + r.diagnostics());
        return r.image();
    }

    static MachineConfig config(CpuModel model, DiskImage disk, boolean strictFpu) {
        return new MachineConfig(model, hz(model), RAM_BYTES, disk, BootRoms.image(model), () -> FIXED_EPOCH, null,
                strictFpu);
    }

    /**
     * A powered-on machine about to run {@code program} from PROG with the ROM's trap stubs
     * installed, keyboard and mouse plugged in.
     */
    static Machine boot(CpuModel model, AsmImage program, DiskImage disk, boolean strictFpu) {
        Machine m = new Machine(config(model, disk, strictFpu), StandardCpus.INSTANCE);
        E2e.plugDefaults(m);
        m.powerOn();
        prepare(m, program);
        return m;
    }

    static Machine boot(CpuModel model, AsmImage program, DiskImage disk) {
        return boot(model, program, disk, false);
    }

    static Machine boot(CpuModel model, String program) {
        return boot(model, assemble(model, program), null);
    }

    /** What the ROM's start-up leaves behind, then the program: overlay off, vectors, code. */
    static void prepare(Machine m, AsmImage program) {
        m.bus().setRomOverlay(false);
        poke32(m, 0, RAM_BYTES);
        poke32(m, 4, PROG);
        int stubs = M68kBootRom.symbol(m.config().cpu(), "vec_stubs");
        for (int v = 2; v < 256; v++)
            poke32(m, 4 * v, stubs + 4 * v);
        for (AsmSegment s : program.segments())
            m.loadRam(s.address(), s.bytes());
    }

    static void runTicks(Machine m, int ticks) {
        for (int i = 0; i < ticks; i++)
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
    }

    /** Runs ticks until the guest powers off; fails if it has not within {@code maxTicks}. */
    static void runUntilOff(Machine m, int maxTicks) {
        for (int i = 0; i < maxTicks && m.state() == MachineState.RUNNING; i++)
            m.runTick(Machine.TICK_NANOS, BIG_BUDGET);
        if (m.state() != MachineState.OFF_BY_GUEST)
            fail("guest did not power off: state " + m.state() + ", pc 0x" + Integer.toHexString(m.cpu().pc())
                    + (m.cpu().haltReason() != null ? ", " + m.cpu().haltReason() : "")
                    + ", COM1 so far \"" + peekCom1(m) + "\"");
    }

    static void assertCleanExit(Machine m) {
        assertEquals(MachineState.OFF_BY_GUEST, m.state());
        assertEquals(0, m.exitCode(), () -> "guest exit code 0x" + Integer.toHexString(m.exitCode()));
    }

    /** Drains COM1's host output buffer as Latin-1 text. */
    static String com1(Machine m) {
        return new String(com1Bytes(m), StandardCharsets.ISO_8859_1);
    }

    static byte[] com1Bytes(Machine m) {
        byte[] buf = new byte[m.com(0).hostAvailable()];
        int n = m.com(0).hostRead(buf, 0, buf.length);
        assertEquals(buf.length, n);
        return buf;
    }

    private static String peekCom1(Machine m) {
        try {
            return com1(m);
        } catch (AssertionError e) {
            return "?";
        }
    }

    static int sym(AsmImage program, String name) {
        return (int) program.symbol(name).orElseThrow(() -> new AssertionError("no symbol " + name));
    }

    /** A big-endian long of RAM at {@code address}. */
    static int ram32(Machine m, int address) {
        assertTrue(address >= 0 && address + 4 <= RAM_BYTES, "address in RAM");
        return ByteBuffer.wrap(m.board().ram(), address, 4).getInt();
    }

    static int ram32(Machine m, AsmImage program, String symbol) {
        return ram32(m, sym(program, symbol));
    }

    static void poke32(Machine m, int address, int value) {
        ByteBuffer.wrap(m.board().ram(), address, 4).putInt(value);
    }
}
