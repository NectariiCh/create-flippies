package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.RAM_BASE;
import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.ROM_BASE;
import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.ROM_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.asm.AsmOptions;
import com.nectariich.create_flippies.content.computer.asm.AsmResult;
import com.nectariich.create_flippies.content.computer.asm.rv32.Rv32Assembler;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.BootRom;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The boot ROM (spec 4.7, 8.4): banner, no disk, bootable and non-bootable disks, its trap handler. */
class BootRomE2eTest {

    private static final long DISK_BYTES = 1024 * 1024;
    private static final String BANNER = "Create Flippies PC  RV32I  1024 KiB RAM\r\n";

    private static Machine powerOn(SparseDiskImage disk) {
        Machine m = new Machine(config(disk, null), StandardCpus.INSTANCE);
        m.powerOn();
        return m;
    }

    /** A boot sector: {@code body} at RAM_BASE, padded to 510 bytes, then 0x55 0xAA. */
    private static AsmImage bootSector(String body) {
        AsmResult r = Rv32Assembler.assemble(PRELUDE + body + """

                .org 510
                .byte 0x55, 0xAA
                """, AsmOptions.at(RAM_BASE));
        assertTrue(r.ok(), r.diagnostics().toString());
        return r.image();
    }

    private static SparseDiskImage diskWith(AsmImage sector) {
        byte[] bytes = sector.flatten(RAM_BASE, 512);
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

    @Test
    void romImageFitsAndIsTrimmed() {
        byte[] rom = BootRom.image();
        assertTrue(rom.length > 64 && rom.length <= ROM_SIZE, "ROM is " + rom.length + " bytes");
        assertTrue(rom[rom.length - 1] != 0);
        assertEquals(ROM_BASE, BootRom.symbol("_start"));
        assertTrue(BootRom.symbol("print_string") > ROM_BASE);
    }

    @Test
    void noDiskPrintsBannerAndSleeps() {
        Machine m = powerOn(null);
        runTicks(m, 2);
        assertEquals(BANNER + "No disk\r\n", com1(m));
        assertEquals("Create Flippies PC  RV32I  1024 KiB RAM", vgaRow(m, 0));
        assertEquals("No disk", vgaRow(m, 1));
        VgaFrame f = m.vga().snapshot();
        assertEquals(0x07, f.pixelByte(1), "attribute 0x07");
        assertEquals(0, f.cursorColumn());
        assertEquals(2, f.cursorRow());
        assertEquals(MachineState.RUNNING, m.state());
        assertEquals(2 * TICK_CYCLES, m.clock().now(), "sleeping ticks still pass emulated time");
        // asleep in WFI with nothing enabled: the CPU says WAITING at once
        assertEquals(Cpu.Exit.WAITING, m.cpu().run(m.clock().now() + 1000, Long.MAX_VALUE));
    }

    @Test
    void bootableDiskRunsTheBootSector() {
        AsmImage sector = bootSector("""
                _start:
                    la   t0, args
                    sw   a0, 0(t0)              # RAM size
                    sw   a1, 4(t0)              # disk sectors
                    sw   a2, 8(t0)              # print_string
                    la   a0, msg
                    jalr a2
                    li   t0, COM1
                1:  lbu  t1, 5(t0)              # wait until everything is sent
                    andi t1, t1, 0x40
                    beqz t1, 1b
                    li   t0, SYSCON
                    li   t1, 0x5555
                    sw   t1, 0(t0)
                2:  j    2b
                msg: .asciz "Booted from disk\\r\\n"
                .align 2
                args: .word 0, 0, 0
                """);
        Machine m = powerOn(diskWith(sector));
        runUntilOff(m, 5);
        assertCleanExit(m);
        assertEquals(BANNER + "Booted from disk\r\n", com1(m));
        assertEquals("Booted from disk", vgaRow(m, 1));
        int args = sym(sector, "args");
        assertEquals(RAM_BYTES, ramWord(m, args));
        assertEquals((int) (DISK_BYTES / 512), ramWord(m, args + 4));
        assertEquals(BootRom.symbol("print_string"), ramWord(m, args + 8));
    }

    @Test
    void diskWithoutSignatureIsNotBootable() {
        SparseDiskImage disk = new SparseDiskImage(DISK_BYTES);
        disk.write(0, 1, new byte[512], 0);
        Machine m = powerOn(disk);
        runTicks(m, 2);
        assertEquals(BANNER + "Disk not bootable\r\n", com1(m));
        assertEquals(MachineState.RUNNING, m.state());
    }

    @Test
    void romTrapHandlerReportsAndPowersOff() {
        AsmImage sector = bootSector("""
                _start:
                    nop
                bad:
                    .word 0
                """);
        Machine m = powerOn(diskWith(sector));
        runUntilOff(m, 5);
        assertEquals(MachineState.OFF_BY_GUEST, m.state());
        assertEquals(BootRom.TRAP_EXIT_CODE, m.exitCode());
        assertEquals(BANNER + String.format("TRAP mcause=00000002 mepc=%08X mtval=00000000\r\n", sym(sector, "bad")),
                com1(m));
    }

    @Test
    void manyLinesScrollTheScreen() {
        // 30 lines through the ROM's print_string: the screen keeps the last 24 plus the cursor row
        StringBuilder body = new StringBuilder("""
                _start:
                    mv   s0, a2
                    li   s1, 0
                1:  la   a0, line
                    jalr s0
                    la   t0, line + 5
                    lbu  t1, 0(t0)
                    addi t1, t1, 1
                    sb   t1, 0(t0)
                    addi s1, s1, 1
                    li   t0, 30
                    blt  s1, t0, 1b
                2:  wfi
                    j    2b
                line: .asciz "line A\\r\\n"
                """);
        Machine m = powerOn(diskWith(bootSector(body.toString())));
        runTicks(m, 5);
        // the banner and the first 6 lines scrolled away; the last line, 'line ^' (A + 29), is on row 23
        assertEquals("line " + (char) ('A' + 29), vgaRow(m, 23));
        assertEquals("line " + (char) ('A' + 7), vgaRow(m, 1));
        assertEquals("", vgaRow(m, 24));
        assertEquals(24, m.vga().snapshot().cursorRow());
    }
}
