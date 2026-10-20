package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.RAM_BASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.AsmSegment;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.machine.BootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Shared rig for the end-to-end tests (spec 8.4): real assembly through {@code Rv32Assembler},
 * loaded into RAM of a real {@link Machine} with {@code Rv32Cpu}, run tick by tick.
 *
 * A guest program is placed between {@link #PRELUDE} (device equates, no code) and {@link #LIB}
 * (small COM1 and power routines), so its first instruction is at RAM_BASE, the reset PC these
 * machines use. The machine runs at 10 MHz with 1 MiB of RAM: 500 000 cycles per tick.
 */
final class E2e {

    private E2e() {
    }

    static final int HZ = 10_000_000;
    static final int RAM_BYTES = 1 << 20;
    static final long TICK_CYCLES = HZ / 20;
    /** A host budget no test reaches: the tick always runs to its end. */
    static final long BIG_BUDGET = 10_000_000_000L;
    static final long FIXED_EPOCH = 1_700_000_000L;

    /** Device equates for guest programs; no code, so the program's first line sits at RAM_BASE. */
    static final String PRELUDE = """
            .equ SYSCON,      0x00100000
            .equ CLINT,       0x02000000
            .equ MTIMECMP,    0x02004000
            .equ MTIME,       0x0200BFF8
            .equ PIC,         0x0C000000
            .equ COM1,        0x10000000
            .equ COM2,        0x10001000
            .equ PS2,         0x10002000
            .equ HDD,         0x10003000
            .equ VGA,         0x10004000
            .equ TIMER,       0x10005000
            .equ RTC,         0x10006000
            .equ VRAM,        0x20000000
            .equ RAM,         0x80000000
            .equ STACK_TOP,   0x80100000
            .text
            """;

    /**
     * Guest library: putc (a0), puts (a0 = string), puthex (a0, 8 digits), exit (waits until COM1
     * has sent everything, then POWER 0x5555), fail (a0 = code, POWER 0x3333 | code << 16).
     * putc uses t0 and t1 only; puts and puthex keep s registers.
     */
    static final String LIB = """

            .text
            .align 2
            putc:
                li   t0, COM1
            1:  lbu  t1, 5(t0)
                andi t1, t1, 0x20
                beqz t1, 1b
                sb   a0, 0(t0)
                ret
            puts:
                addi sp, sp, -8
                sw   ra, 4(sp)
                sw   s0, 0(sp)
                mv   s0, a0
            1:  lbu  a0, 0(s0)
                beqz a0, 2f
                call putc
                addi s0, s0, 1
                j    1b
            2:  lw   s0, 0(sp)
                lw   ra, 4(sp)
                addi sp, sp, 8
                ret
            puthex:
                addi sp, sp, -12
                sw   ra, 8(sp)
                sw   s0, 4(sp)
                sw   s1, 0(sp)
                mv   s0, a0
                li   s1, 8
            1:  srli a0, s0, 28
                slli s0, s0, 4
                li   t0, 10
                blt  a0, t0, 2f
                addi a0, a0, 'A' - 10 - '0'
            2:  addi a0, a0, '0'
                call putc
                addi s1, s1, -1
                bnez s1, 1b
                lw   s1, 0(sp)
                lw   s0, 4(sp)
                lw   ra, 8(sp)
                addi sp, sp, 12
                ret
            drain:
                li   t0, COM1
            1:  lbu  t1, 5(t0)
                andi t1, t1, 0x40
                beqz t1, 1b
                ret
            exit:
                call drain
                li   t0, SYSCON
                li   t1, 0x5555
                sw   t1, 0(t0)
            1:  j    1b
            fail:
                mv   s0, a0
                call drain
                slli t1, s0, 16
                li   t2, 0x3333
                or   t1, t1, t2
                li   t0, SYSCON
                sw   t1, 0(t0)
            1:  j    1b
            """;

    /** Assembles a guest program for RAM_BASE between the prelude and the library; fails the test on any diagnostic error. */
    static AsmImage assemble(String program) {
        AsmResult r = Rv32Assembler.assemble(PRELUDE + program + LIB, AsmOptions.at(RAM_BASE));
        if (!r.ok())
            fail("guest program does not assemble: " + r.diagnostics());
        return r.image();
    }

    static MachineConfig config(DiskImage disk, Integer resetPc) {
        return new MachineConfig(CpuModel.RV32I, HZ, RAM_BYTES, disk, BootRom.image(), () -> FIXED_EPOCH, resetPc);
    }

    /** Plugs a keyboard into PS/2 port 0 and a mouse into port 1, as the game does for its cords. */
    static void plugDefaults(Machine m) {
        m.ps2().setDevice(0, Ps2DeviceKind.KEYBOARD);
        m.ps2().setDevice(1, Ps2DeviceKind.MOUSE);
    }

    /** A powered-on machine running {@code program} from RAM_BASE (the boot ROM is skipped), keyboard and mouse plugged in. */
    static Machine boot(AsmImage program, DiskImage disk) {
        Machine m = new Machine(config(disk, RAM_BASE), StandardCpus.INSTANCE);
        plugDefaults(m);
        m.powerOn();
        load(m, program);
        return m;
    }

    static Machine boot(String program) {
        return boot(assemble(program), null);
    }

    static void load(Machine m, AsmImage program) {
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
        assertEquals(0, m.exitCode(), "guest exit code");
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

    /** Address of a program symbol. */
    static int sym(AsmImage program, String name) {
        return (int) program.symbol(name).orElseThrow(() -> new AssertionError("no symbol " + name));
    }

    /** A little-endian word of RAM at bus address {@code address}. */
    static int ramWord(Machine m, int address) {
        int off = address - RAM_BASE;
        assertTrue(off >= 0 && off + 4 <= RAM_BYTES, "address in RAM");
        return ByteBuffer.wrap(m.board().ram(), off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static int ramWord(Machine m, AsmImage program, String symbol) {
        return ramWord(m, sym(program, symbol));
    }
}
