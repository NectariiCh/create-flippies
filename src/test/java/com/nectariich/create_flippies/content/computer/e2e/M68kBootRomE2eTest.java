package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.m68k.M68kAssembler;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.BootRoms;
import com.nectariich.create_flippies.content.computer.machine.M68kBootRom;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The 68k boot ROM and the machine hooks it needs (spec 10.8, 10.12.4): banner, no disk,
 * bootable and non-bootable disks, the ROM overlay through reset, power-on and a guest reset, and
 * the RESET instruction resetting devices while the program carries on.
 */
class M68kBootRomE2eTest {

    private static final long DISK_BYTES = 1024 * 1024;

    private static String banner(CpuModel model) {
        return "Create Flippies PC  " + model.displayName() + "  1024 KiB RAM\r\n";
    }

    private static Machine powerOn(CpuModel model, SparseDiskImage disk) {
        Machine m = new Machine(config(model, disk, false), StandardCpus.INSTANCE);
        m.powerOn();
        return m;
    }

    /** A boot sector: {@code body} at the boot address, padded to 510 bytes, then 0x55 0xAA. */
    private static AsmImage bootSector(CpuModel model, String body) {
        AsmResult r = M68kAssembler.assemble(PRELUDE + body + """

                        org     510
                        dc.b    $55,$AA
                """, AsmOptions.at(M68kBootRom.BOOT_ADDRESS), target(model));
        assertTrue(r.ok(), r.diagnostics().toString());
        return r.image();
    }

    private static SparseDiskImage diskWith(AsmImage sector) {
        byte[] bytes = sector.flatten(M68kBootRom.BOOT_ADDRESS, 512);
        SparseDiskImage disk = new SparseDiskImage(DISK_BYTES);
        disk.write(0, 1, bytes, 0);
        return disk;
    }

    /** The text of VGA text row {@code row}, trailing blanks removed. */
    private static String vgaRow(Machine m, int row) {
        VgaFrame f = m.vga().snapshot();
        byte[] chars = new byte[80];
        for (int c = 0; c < 80; c++)
            chars[c] = (byte) f.pixelByte(row * 160 + c * 2);
        return new String(chars, StandardCharsets.ISO_8859_1).stripTrailing();
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void romImageFitsAndStartsWithItsResetVectors(CpuModel model) {
        byte[] rom = BootRoms.image(model);
        assertTrue(rom.length > 1024 && rom.length <= M68kMap.ROM_SIZE, "ROM is " + rom.length + " bytes");
        assertEquals(0, rom[0] | rom[1] | rom[2] | rom[3], "reset SSP unused");
        int pc = (rom[4] & 0xFF) << 24 | (rom[5] & 0xFF) << 16 | (rom[6] & 0xFF) << 8 | rom[7] & 0xFF;
        assertEquals(M68kBootRom.symbol(model, "_start"), pc);
        assertEquals(M68kMap.ROM_BASE + 8, pc, "the reset PC is the ROM's home address");
        assertEquals(MachineConfig.of(model, hz(model), RAM_BYTES, null).bootRom().length, rom.length);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void noDiskPrintsBannerAndSleeps(CpuModel model) {
        Machine m = powerOn(model, null);
        assertTrue(m.bus().romOverlay(), "power-on mirrors the ROM at 0");
        runTicks(m, 2);
        assertFalse(m.bus().romOverlay(), "the ROM's first write unmapped the mirror");
        assertEquals(banner(model) + "No disk\r\n", com1(m));
        assertEquals(banner(model).strip(), vgaRow(m, 0));
        assertEquals("No disk", vgaRow(m, 1));
        VgaFrame f = m.vga().snapshot();
        assertEquals(0x07, f.pixelByte(1), "attribute 0x07");
        assertEquals(0, f.cursorColumn());
        assertEquals(2, f.cursorRow());
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(2 * tickCycles(model), m.clock().now(), "sleeping ticks still pass emulated time");
        assertEquals(Cpu.Exit.WAITING, m.cpu().run(m.clock().now() + 1000, Long.MAX_VALUE), "asleep in STOP");
        // the vector table points every vector at its stub
        int stubs = M68kBootRom.symbol(model, "vec_stubs");
        for (int v = 2; v < 256; v++)
            assertEquals(stubs + 4 * v, ram32(m, 4 * v), "vector " + v);
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void bootableDiskRunsTheBootSectorWithItsArguments(CpuModel model) {
        AsmImage sector = bootSector(model, """
                _start: movem.l d0-d1/a1,$800       ; RAM size, disk sectors, print_string
                        move.w  sr,d2
                        move.w  d2,$80C
                        move.l  sp,$810
                        lea     msg(pc),a0
                        jsr     (a1)
                1:      btst    #6,COM1+5
                        beq.s   1b
                        move.l  #$5555,SYSCON
                2:      bra.s   2b
                msg:    dc.b    "booted from disk",13,10,0
                """);
        Machine m = powerOn(model, diskWith(sector));
        runUntilOff(m, 10);
        assertEquals(0, m.exitCode());
        assertEquals(banner(model) + "booted from disk\r\n", com1(m));
        assertEquals(RAM_BYTES, ram32(m, 0x800));
        assertEquals((int) (DISK_BYTES / 512), ram32(m, 0x804));
        assertEquals(M68kBootRom.symbol(model, "print_string"), ram32(m, 0x808));
        assertEquals(0x2700, ram32(m, 0x80C) >>> 16, "supervisor, interrupts masked");
        assertEquals(RAM_BYTES, ram32(m, 0x810), "SP at the top of RAM");
        assertEquals("booted from disk", vgaRow(m, 1));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void diskWithoutTheSignatureIsNotBootable(CpuModel model) {
        SparseDiskImage disk = new SparseDiskImage(DISK_BYTES);
        disk.write(0, 1, new byte[512], 0);
        Machine m = powerOn(model, disk);
        runTicks(m, 3);
        assertEquals(banner(model) + "Disk not bootable\r\n", com1(m));
        assertEquals(MachineState.RUNNING, m.state());
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void guestResetTurnsTheOverlayBackOnAndTheRomBootsAgain(CpuModel model) {
        // the boot sector counts its runs in RAM (kept across a reset): the first run asks
        // SysCon for a reset, the second powers off
        AsmImage sector = bootSector(model, """
                _start: addq.l  #1,$800
                        cmp.l   #2,$800
                        beq.s   2f
                        move.l  #$7777,SYSCON
                1:      bra.s   1b
                2:      btst    #6,COM1+5
                        beq.s   2b
                        move.l  #$5555,SYSCON
                3:      bra.s   3b
                """);
        Machine m = powerOn(model, diskWith(sector));
        runUntilOff(m, 10);
        assertEquals(0, m.exitCode());
        assertEquals(2, ram32(m, 0x800), "RAM survived the reset");
        assertEquals(banner(model) + banner(model), com1(m), "the ROM booted a second time");
        assertFalse(m.bus().romOverlay());

        // the host reset button turns the overlay back on, power-on too
        Machine n = powerOn(model, null);
        runTicks(n, 1);
        assertFalse(n.bus().romOverlay());
        assertEquals(banner(model) + "No disk\r\n", com1(n));
        n.reset();
        assertTrue(n.bus().romOverlay(), "reset turns the overlay back on");
        assertEquals(0, n.bus().read32(0), "address 0 reads the ROM's first long");
        assertEquals(M68kMap.ROM_BASE + 8, n.bus().read32(4));
        runTicks(n, 1);
        assertEquals(banner(model) + "No disk\r\n", com1(n));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"CPU_68000", "CPU_68040"})
    void resetInstructionResetsDevicesAndTheProgramCarriesOn(CpuModel model) {
        // set the UART's LCR, the interrupt controller's ENABLE and VGA mode 1, RESET, then read
        // them back at their reset values; RAM (the vector at 0) is kept and the overlay stays off
        Machine m = boot(model, """
                _start: move.b  #$83,COM1+3
                        move.l  #$FE,PIC+4
                        move.l  #1,VGA
                        reset
                        moveq   #0,d0
                        move.b  COM1+3,d0
                        bsr     puthex
                        move.l  PIC+4,d0
                        bsr     puthex
                        move.l  VGA,d0
                        bsr     puthex
                        move.l  0,d0
                        bsr     puthex
                        bra     exit
                """);
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals("00000000" + "00000000" + "00000000" + "00100000", com1(m));
        assertFalse(m.bus().romOverlay());
    }
}
