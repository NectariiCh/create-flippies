package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.RAM_BASE;
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
import com.nectariich.create_flippies.content.computer.machine.BootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Freeze and resume (spec 1.5) end to end. A guest keeps every device busy at once: the CLINT
 * timer and the interval timer (through the interrupt controller) interrupt it, it streams text
 * out of COM1 at 9600 baud, polls the PS/2 keyboard, keeps an HDD READ in flight, draws in VGA
 * mode 1 and rewrites palette entries. One machine runs uninterrupted; its twin is saved and
 * loaded into a brand-new Machine after every tick. Both must agree byte for byte after every
 * tick and end with the same output. Then every rejection case.
 */
class SaveStateE2eTest {

    private static final int ITERATIONS = 220;
    private static final long DISK_BYTES = 1024 * 1024;

    private static final String PROGRAM = """
            _start:
                li   sp, STACK_TOP
                la   t0, trap
                csrw mtvec, t0
                li   t0, COM1               # 9600 baud (divisor 12), 8N1, FIFOs on
                li   t1, 0x83
                sb   t1, 3(t0)
                li   t1, 12
                sb   t1, 0(t0)
                sb   zero, 1(t0)
                li   t1, 0x03
                sb   t1, 3(t0)
                li   t1, 0x07
                sb   t1, 2(t0)
                li   t0, VGA                # 320x200 at 8 bpp
                li   t1, 1
                sw   t1, 0x00(t0)
                li   t0, PS2                # keyboard port on, IRQ off: the guest polls
                li   t1, 2
                sw   t1, 0x08(t0)
                li   t0, MTIME              # machine timer every 700 us
                lw   t1, 0(t0)
                addi t1, t1, 700
                li   t0, MTIMECMP
                sw   zero, 4(t0)
                sw   t1, 0(t0)
                li   t0, PIC                # interval timer every 1300 us on line 7
                li   t1, 1 << 7
                sw   t1, 0x04(t0)
                li   t0, TIMER
                li   t1, 1300
                sw   t1, 0x08(t0)
                li   t1, 3
                sw   t1, 0x0C(t0)
                li   t0, 0x880
                csrs mie, t0
                csrsi mstatus, 8
                li   s1, 0                  # iteration
                li   s2, 0                  # checksum
                la   s3, msg

            main:
                li   t0, HDD                # READ 32 sectors from LBA 7 * i mod 1024
                slli t1, s1, 3
                sub  t1, t1, s1
                andi t1, t1, 1023
                sw   t1, 0x08(t0)
                li   t1, 32
                sw   t1, 0x0C(t0)
                la   t1, buf
                sw   t1, 0x10(t0)
                li   t1, 1
                sw   t1, 0x00(t0)
            wait:
                lbu  a0, 0(s3)              # next message character
                bnez a0, 1f
                la   s3, msg
                lbu  a0, 0(s3)
            1:  addi s3, s3, 1
                call putc
                li   t0, PS2                # echo one keyboard byte if any
                lw   t1, 0x04(t0)
                andi t1, t1, 1
                beqz t1, 2f
                lw   a0, 0x00(t0)
                add  s2, s2, a0
                call putc
            2:  li   t0, HDD
                lw   t1, 0x04(t0)
                andi t1, t1, 1
                bnez t1, wait
                li   t1, 6
                sw   t1, 0x04(t0)
                la   t0, buf                # checksum the first 64 words read
                li   t1, 64
            3:  lw   t2, 0(t0)
                add  s2, s2, t2
                addi t0, t0, 4
                addi t1, t1, -1
                bnez t1, 3b
                li   t0, 321                # diagonal pixel i in colour i
                mul  t0, t0, s1
                li   t1, VRAM
                add  t0, t0, t1
                sb   s1, 0(t0)
                li   t0, VGA                # palette entry i = checksum
                andi t1, s1, 255
                sw   t1, 0x2C(t0)
                sw   s2, 0x30(t0)
                li   t0, SYSCON             # a debug character per iteration
                andi t1, s1, 63
                addi t1, t1, 48
                sw   t1, 0x14(t0)
                addi s1, s1, 1
                li   t0, %d
                blt  s1, t0, main

                li   a0, '|'
                call putc
                mv   a0, s2
                call puthex
                lw   a0, mti_count
                call puthex
                lw   a0, mei_count
                call puthex
                j    exit

            .align 2
            trap:
                addi sp, sp, -16
                sw   t0, 0(sp)
                sw   t1, 4(sp)
                sw   t2, 8(sp)
                csrr t0, mcause
                li   t1, 0x80000007
                bne  t0, t1, 1f
                li   t1, MTIMECMP           # re-arm on the 700 us grid
                lw   t2, 0(t1)
                addi t2, t2, 700
                sw   t2, 0(t1)
                lw   t2, mti_count
                addi t2, t2, 1
                sw   t2, mti_count, t1
                j    9f
            1:  li   t1, 0x8000000B
                bne  t0, t1, 8f
                li   t0, PIC
                lw   t1, 0x08(t0)           # CLAIM
                li   t0, TIMER
                li   t2, 1
                sw   t2, 0x10(t0)           # EXPIRED cleared, line 7 drops
                lw   t2, mei_count
                addi t2, t2, 1
                sw   t2, mei_count, t1
                j    9f
            8:  li   a0, 9
                j    fail
            9:  lw   t2, 8(sp)
                lw   t1, 4(sp)
                lw   t0, 0(sp)
                addi sp, sp, 16
                mret

            .data
            msg:       .asciz "frozen in place, back on after loading. "
            .align 2
            mti_count: .word 0
            mei_count: .word 0
            buf:       .space 32 * 512
            """.formatted(ITERATIONS);

    private static SparseDiskImage disk() {
        SparseDiskImage d = new SparseDiskImage(DISK_BYTES);
        byte[] sector = new byte[512];
        for (int s = 0; s < 1100; s++) {
            for (int i = 0; i < 512; i++)
                sector[i] = (byte) (s * 31 + i * 7);
            d.write(s, 1, sector, 0);
        }
        return d;
    }

    /** A brand-new machine for the same parts, with {@code state} loaded, as the game does on chunk load. */
    private static Machine reload(byte[] state, DiskImage disk) throws StateFormatException {
        Machine m = new Machine(config(disk, RAM_BASE), StandardCpus.INSTANCE);
        m.loadState(state);
        return m;
    }

    /** Host input for tick {@code t}, applied after the tick so bytes sit queued at the save. */
    private static void hostInput(Machine m, int t) {
        if (t == 2 || t == 5)
            m.keyboard().inject((byte) ('a' + t));
        if (t == 1)
            m.keyboard().keyDown(Ps2Key.Q);        // held for 19 ticks: typematic repeats after 10
        if (t == 20)
            m.keyboard().keyUp(Ps2Key.Q);
    }

    /** Whether, in {@code state}, COM1 is mid-transmit (read on a throwaway copy: LSR reads have side effects). */
    private static boolean transmitting(byte[] state, DiskImage disk) throws StateFormatException {
        SparseDiskImage scratch = new SparseDiskImage(DISK_BYTES);
        Machine probe = reload(state, disk == null ? null : scratch);
        return (probe.bus().read8(Rv32Map.COM1_BASE + 5) & 0x40) == 0;
    }

    @Test
    void saveAndLoadEveryTickRunsInLockstepWithAnUninterruptedMachine() throws StateFormatException {
        AsmImage program = assemble(PROGRAM);
        Machine a = boot(program, disk());
        SparseDiskImage diskB = disk();
        Machine b = boot(program, diskB);
        StringBuilder outA = new StringBuilder(), outB = new StringBuilder();
        boolean everythingInFlight = false;
        int t = 0;
        for (; t < 60 && a.state() == MachineState.RUNNING; t++) {
            a.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            hostInput(a, t);
            hostInput(b, t);
            outA.append(com1(a));
            outB.append(com1(b));

            byte[] saved = b.saveState();
            if (!everythingInFlight && a.hdd().busy() && a.keyboard().queued() > 0 && a.vga().mode() == 1
                    && a.board().clint().nextEventCycle() != Long.MAX_VALUE
                    && a.board().timer().nextEventCycle() != Long.MAX_VALUE && transmitting(saved, diskB))
                everythingInFlight = true;
            b = reload(saved, diskB);
            assertArrayEquals(a.saveState(), b.saveState(), "state after tick " + t);
        }
        assertTrue(everythingInFlight, "some save caught timers armed, COM1 sending, keys queued, the HDD busy and VGA in mode 1");
        assertTrue(t > 8, "the run spans many saves: " + t + " ticks");
        assertCleanExit(a);
        assertCleanExit(b);
        outA.append(com1(a));
        outB.append(com1(b));
        assertEquals(outA.toString(), outB.toString());
        assertArrayEquals(a.saveState(), b.saveState());
        assertEquals(a.debugLog(), b.debugLog());
        assertTrue(outA.toString().contains("frozen in place, back on after loading."), outA.toString());
        long qMakes = outA.chars().filter(c -> c == 0x15).count();
        assertTrue(qMakes >= 3, "typematic repeats of Q (make code 0x15) crossed saves: " + qMakes + " in " + t + " ticks");
        assertTrue(a.vga().palette(ITERATIONS - 1) != 0, "the guest drew and wrote the palette");
    }

    @Test
    void oneSaveMidRunIntoAFreshMachineMatchesAnUninterruptedRun() throws StateFormatException {
        AsmImage program = assemble(PROGRAM);
        Machine a = boot(program, disk());
        runTicks(a, 4);
        hostInput(a, 1);
        byte[] saved = a.saveState();
        long clockAtSave = a.clock().now();
        SparseDiskImage diskB = disk();
        Machine b = reload(saved, diskB);
        assertEquals(clockAtSave, b.clock().now(), "emulated time is frozen: no catch-up on load");
        assertEquals(a.cpu().pc(), b.cpu().pc());
        runUntilOff(a, 80);
        runUntilOff(b, 80);
        assertCleanExit(b);
        assertEquals(com1(a), com1(b));
        assertArrayEquals(a.saveState(), b.saveState());
        assertEquals(a.vga().snapshot().generation(), b.vga().snapshot().generation());
    }

    @Test
    void anOffMachineAndAHaltedOneRoundTrip() throws StateFormatException {
        Machine off = new Machine(config(null, RAM_BASE), StandardCpus.INSTANCE);
        Machine loaded = reload(off.saveState(), null);
        assertEquals(MachineState.OFF, loaded.state());
        assertArrayEquals(off.saveState(), loaded.saveState());

        Machine halted = boot(assemble("""
                _start:
                    li   t0, 0x100
                    csrw mtvec, t0          # a handler that cannot be fetched
                    ecall
                """), null);
        runTicks(halted, 1);
        assertEquals(MachineState.HALTED, halted.state());
        Machine back = reload(halted.saveState(), null);
        assertEquals(MachineState.HALTED, back.state());
        assertEquals(halted.cpu().haltReason(), back.cpu().haltReason());
    }

    // ---- rejection ----

    /** A machine that has run a while, and its state. */
    private static Machine running(DiskImage disk) {
        Machine m = boot(assemble(PROGRAM), disk);
        runTicks(m, 3);
        return m;
    }

    /** Recomputes the trailing CRC after a deliberate edit. */
    private static byte[] withCrc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        int v = (int) crc.getValue();
        for (int i = 0; i < 4; i++)
            data[data.length - 4 + i] = (byte) (v >>> (24 - 8 * i));
        return data;
    }

    /** Asserts that loading {@code state} into {@code target} fails naming {@code expected} and changes nothing. */
    private static void assertRejected(Machine target, byte[] state, String expected) {
        byte[] before = target.saveState();
        StateFormatException e = assertThrows(StateFormatException.class, () -> target.loadState(state));
        assertTrue(e.getMessage().contains(expected), "message \"" + e.getMessage() + "\" names " + expected);
        assertArrayEquals(before, target.saveState(), "a rejected load leaves the machine as it was");
    }

    @Test
    void anUnknownMachineVersionIsRejected() {
        byte[] state = running(disk()).saveState();
        state[7] = (byte) (Machine.STATE_VERSION + 1);
        assertRejected(new Machine(config(null, RAM_BASE), StandardCpus.INSTANCE), withCrc(state),
                "version " + (Machine.STATE_VERSION + 1));
    }

    @Test
    void anUnknownComponentVersionIsRejectedAfterRamWasLoadedAndRolledBack() {
        SparseDiskImage d = disk();
        byte[] state = running(d).saveState();
        // the RTC section: UTF "rtc", length, then the RTC's own version
        byte[] tag = { 0, 3, 'r', 't', 'c' };
        int at = indexOf(state, tag);
        assertTrue(at > 0);
        state[at + tag.length + 4 + 3] = 9;
        Machine target = running(disk());
        assertRejected(target, withCrc(state), "RTC state version 9");
        runTicks(target, 2);                          // still a working machine
        assertEquals(MachineState.RUNNING, target.state());
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++)
                if (data[i + j] != pattern[j])
                    continue outer;
            return i;
        }
        return -1;
    }

    @Test
    void aWrongCpuModelIsRejected() throws IOException {
        byte[] state = running(disk()).saveState();
        // header: magic, version, then the model name as UTF ("RV32I", 2 + 5 bytes)
        ByteArrayOutputStream edited = new ByteArrayOutputStream();
        edited.write(state, 0, 8);
        new DataOutputStream(edited).writeUTF(CpuModel.CPU_68000.name());
        edited.write(state, 15, state.length - 15);
        assertRejected(new Machine(config(disk(), RAM_BASE), StandardCpus.INSTANCE), withCrc(edited.toByteArray()),
                "CPU_68000");
    }

    @Test
    void changedRamSizeClockOrBootRomIsRejected() {
        byte[] state = running(null).saveState();
        MachineConfig bigger = new MachineConfig(CpuModel.RV32I, HZ, 2 * RAM_BYTES, null, BootRom.image(), () -> FIXED_EPOCH, RAM_BASE);
        assertRejected(new Machine(bigger, StandardCpus.INSTANCE), state, "RAM");
        MachineConfig faster = new MachineConfig(CpuModel.RV32I, 2 * HZ, RAM_BYTES, null, BootRom.image(), () -> FIXED_EPOCH, RAM_BASE);
        assertRejected(new Machine(faster, StandardCpus.INSTANCE), state, "Hz");
        byte[] rom = BootRom.image();
        rom = Arrays.copyOf(rom, rom.length + 4);
        MachineConfig otherRom = new MachineConfig(CpuModel.RV32I, HZ, RAM_BYTES, null, rom, () -> FIXED_EPOCH, RAM_BASE);
        assertRejected(new Machine(otherRom, StandardCpus.INSTANCE), state, "boot ROM");
        MachineConfig otherPc = new MachineConfig(CpuModel.RV32I, HZ, RAM_BYTES, null, BootRom.image(), () -> FIXED_EPOCH, null);
        assertRejected(new Machine(otherPc, StandardCpus.INSTANCE), state, "reset PC");
    }

    @Test
    void aMissingOrDifferentDiskIsRejected() {
        byte[] state = running(disk()).saveState();
        Machine noDisk = new Machine(config(null, RAM_BASE), StandardCpus.INSTANCE);
        noDisk.powerOn();
        assertRejected(noDisk, state, "no disk");
        Machine otherDisk = new Machine(config(new SparseDiskImage(2 * DISK_BYTES), RAM_BASE), StandardCpus.INSTANCE);
        assertRejected(otherDisk, state, "4096 sectors");
    }

    @Test
    void corruptOrTruncatedDataIsRejected() {
        byte[] state = running(disk()).saveState();
        Machine target = new Machine(config(disk(), RAM_BASE), StandardCpus.INSTANCE);
        byte[] flipped = state.clone();
        flipped[state.length / 2] ^= 1;
        assertRejected(target, flipped, "checksum");
        assertRejected(target, Arrays.copyOf(state, state.length - 100), "checksum");
        assertRejected(target, new byte[3], "only 3 bytes");
        byte[] notOurs = withCrc(new byte[64]);
        assertRejected(target, notOurs, "not a saved machine state");
        // a valid checksum over a truncated body: the reader runs out of data
        assertRejected(target, withCrc(Arrays.copyOf(state, 200)), "corrupt");
    }
}
