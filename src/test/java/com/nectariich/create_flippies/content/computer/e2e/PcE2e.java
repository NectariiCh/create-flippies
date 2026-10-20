package com.nectariich.create_flippies.content.computer.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.DiskImage;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Shared rig for the in-game board end-to-end tests (spec 11.4) on all three CPUs: one source per
 * instruction set (RV32I, and 68k for both the 68000 and the 68040), run on {@link E2e} or
 * {@link M68kE2e} machines with their preludes and libraries. Adds the in-game board's device
 * equates to each prelude.
 */
final class PcE2e {

    private PcE2e() {
    }

    static final String RV_EQUATES = """
            .equ COM3,        0x10007000
            .equ FDC,         0x10008000
            .equ NIC,         0x10009000
            .equ VGA1,        0x1000A000
            .equ VGA2,        0x1000B000
            """;

    static final String M68K_EQUATES = """
            COM3        equ $FFF09000
            FDC         equ $FFF0A000
            NIC         equ $FFF0B000
            VGA1        equ $FFF0C000
            VGA2        equ $FFF0D000
            """;

    static boolean rv(CpuModel model) {
        return model == CpuModel.RV32I;
    }

    /** The guest program for {@code model}: {@code rv} on RV32I, {@code m68k} on either 68k. */
    static AsmImage assemble(CpuModel model, String rv, String m68k) {
        return rv(model) ? E2e.assemble(RV_EQUATES + rv) : M68kE2e.assemble(model, M68K_EQUATES + m68k);
    }

    /** A machine about to run the program from RAM, ROM start-up skipped, keyboard and mouse plugged in. */
    static Machine boot(CpuModel model, AsmImage program, DiskImage hdd) {
        return boot(model, program, hdd, 0);
    }

    /** The same with network card MAC {@code mac}. */
    static Machine boot(CpuModel model, AsmImage program, DiskImage hdd, long mac) {
        MachineConfig c = rv(model) ? E2e.config(hdd, Rv32Map.RAM_BASE) : M68kE2e.config(model, hdd, false);
        Machine m = new Machine(withMac(c, mac), StandardCpus.INSTANCE);
        E2e.plugDefaults(m);
        m.powerOn();
        if (rv(model))
            E2e.load(m, program);
        else
            M68kE2e.prepare(m, program);
        return m;
    }

    static MachineConfig withMac(MachineConfig c, long mac) {
        return new MachineConfig(c.cpu(), c.clockHz(), c.ramBytes(), c.disk(), c.bootRom(), c.rtcEpochSeconds(),
                c.resetPc(), c.strictFpu(), mac);
    }

    static Machine boot(CpuModel model, String rv, String m68k) {
        return boot(model, assemble(model, rv, m68k), null);
    }

    /** The test config of {@code model} with the standard boot ROM and MAC {@code mac}. */
    static MachineConfig config(CpuModel model, DiskImage hdd, long mac) {
        return withMac(rv(model) ? E2e.config(hdd, null) : M68kE2e.config(model, hdd, false), mac);
    }

    /** A machine that runs the real boot ROM from power-on. */
    static Machine powerOnWithRom(CpuModel model, DiskImage hdd, DiskImage floppy) {
        Machine m = new Machine(config(model, hdd, 0), StandardCpus.INSTANCE);
        if (floppy != null)
            m.floppy().insert(floppy, false);
        m.powerOn();
        return m;
    }

    /** Where the boot ROM loads and enters a boot sector. */
    static int bootAddress(CpuModel model) {
        return rv(model) ? Rv32Map.RAM_BASE : M68kBootRom.BOOT_ADDRESS;
    }

    /** A boot sector: the body at the boot address, padded to 510 bytes, then 0x55 0xAA; 512 bytes. */
    static byte[] bootSector(CpuModel model, String rv, String m68k) {
        AsmResult r = rv(model)
                ? Rv32Assembler.assemble(E2e.PRELUDE + rv + "\n.org 510\n.byte 0x55, 0xAA\n", AsmOptions.at(Rv32Map.RAM_BASE))
                : M68kAssembler.assemble(M68kE2e.PRELUDE + m68k + "\n        org     510\n        dc.b    $55,$AA\n",
                        AsmOptions.at(M68kBootRom.BOOT_ADDRESS), M68kE2e.target(model));
        assertTrue(r.ok(), r.diagnostics().toString());
        return r.image().flatten(bootAddress(model), 512);
    }

    /** A blank 1.44 MB floppy disk held in memory (a store with nothing in it). */
    static PagedDiskImage floppy(UUID id) {
        return new PagedDiskImage(id, PagedDiskImage.FLOPPY_CAPACITY, chunk -> null, 64);
    }

    static long tickCycles(CpuModel model) {
        return rv(model) ? E2e.TICK_CYCLES : M68kE2e.tickCycles(model);
    }

    static void runTicks(Machine m, int ticks) {
        E2e.runTicks(m, ticks);
    }

    static void runUntilOff(Machine m, int maxTicks) {
        if (rv(m.config().cpu()))
            E2e.runUntilOff(m, maxTicks);
        else
            M68kE2e.runUntilOff(m, maxTicks);
    }

    static String com1(Machine m) {
        return E2e.com1(m);
    }

    static int sym(AsmImage program, String name) {
        return E2e.sym(program, name);
    }

    /** A 32-bit word of RAM at bus address {@code address}, in the machine's byte order. */
    static int ram32(Machine m, int address) {
        return ByteBuffer.wrap(m.board().ram(), address - m.bus().ramBase(), 4)
                .order(m.bus().order()).getInt();
    }

    /** {@code length} bytes of RAM at bus address {@code address}. */
    static byte[] ramBytes(Machine m, int address, int length) {
        byte[] out = new byte[length];
        System.arraycopy(m.board().ram(), address - m.bus().ramBase(), out, 0, length);
        return out;
    }

    /** Where a guest buffer at RAM offset {@code offset} lives on the bus. */
    static int ramAddress(CpuModel model, int offset) {
        return rv(model) ? Rv32Map.RAM_BASE + offset : offset;
    }

    static String banner(CpuModel model) {
        return "Create Flippies PC  " + model.displayName() + "  1024 KiB RAM\r\n";
    }
}
