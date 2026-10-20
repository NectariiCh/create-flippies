package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.PcE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.PagedDiskImage;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.M68kMap;
import com.nectariich.create_flippies.content.computer.machine.M68kPlatform;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.Platform;
import com.nectariich.create_flippies.content.computer.machine.Rv32Map;
import com.nectariich.create_flippies.content.computer.machine.Rv32Platform;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The in-game board on every machine (spec 11.1, 11.4): memory maps, SysCon additions, three
 * displays, COM3, the busy fraction, the guest error light, RAM limits and the saved-state checks.
 */
class BoardE2eTest {

    /** Bus address of a 32-bit constant as the machine maps it (24 bits on the 68000). */
    private static int at(Machine m, int address) {
        return m.config().cpu() == CpuModel.CPU_68000 ? address & M68kMap.ADDRESS_MASK_68000 : address;
    }

    private static int[] windows(CpuModel model) {
        return rv(model)
                ? new int[] { Rv32Map.SYSCON_BASE, Rv32Map.COM3_BASE, Rv32Map.FDC_BASE, Rv32Map.NIC_BASE,
                        Rv32Map.VGA_BASE, Rv32Map.VGA1_BASE, Rv32Map.VGA2_BASE }
                : new int[] { M68kMap.SYSCON_BASE, M68kMap.COM3_BASE, M68kMap.FDC_BASE, M68kMap.NIC_BASE,
                        M68kMap.VGA_BASE, M68kMap.VGA1_BASE, M68kMap.VGA2_BASE };
    }

    private static int[] displayBases(CpuModel model) {
        return rv(model)
                ? new int[] { 0x2000_0000, 0x2008_0000, 0x2010_0000 }
                : new int[] { 0xFFE0_0000, 0xFFE8_0000, 0xFFD8_0000 };
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void everyNewWindowAndTheThreeVramBases(CpuModel model) {
        Machine m = boot(model, "1: j 1b\n", "1: bra.s 1b\n");
        SystemBus bus = m.bus();
        int[] w = windows(model);
        int sys = at(m, w[0]);
        assertEquals(0x60, bus.read8(at(m, w[1]) + Uart16550.LSR), "COM3");
        assertEquals(FloppyController.GEOMETRY_VALUE, bus.read32(at(m, w[2]) + FloppyController.GEOMETRY), "floppy");
        assertEquals(0, bus.read32(at(m, w[3]) + Nic.MAC_HI), "network card, MAC 0");
        for (int n = 0; n < 3; n++)
            assertEquals(80, bus.read32(at(m, w[4 + n]) + VgaDisplay.WIDTH), "display " + n);
        int[] bases = displayBases(model);
        assertEquals(bases[0], bus.read32(sys + SysCon.VRAM_BASE));
        assertEquals(bases[1], bus.read32(sys + SysCon.VRAM1_BASE));
        assertEquals(bases[2], bus.read32(sys + SysCon.VRAM2_BASE));
        assertEquals(512 * 1024, bus.read32(sys + SysCon.VRAM_SIZE), "per display");
        assertEquals(0x18_0000, bus.vramSize(), "one 1.5 MiB VRAM range on the fast path");
        for (int n = 0; n < 3; n++) {
            bus.write8(at(m, bases[n]) + 2, 'A' + n);
            assertEquals('A' + n, m.vga(n).snapshot().pixelByte(2), "display " + n + "'s framebuffer");
        }
        assertEquals('A', m.vga().snapshot().pixelByte(2), "vga() is display 0");
        assertEquals(0, bus.read32(sys + SysCon.LEDS));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void presentBits(CpuModel model) {
        Machine m = new Machine(config(model, null, 0), StandardCpus.INSTANCE);
        m.powerOn();
        int present = at(m, windows(model)[0]) + SysCon.PRESENT;
        assertEquals(0, m.bus().read32(present), "nothing plugged in, no media");
        m.ps2().setDevice(1, Ps2DeviceKind.KEYBOARD);
        assertEquals(SysCon.PRESENT_MOUSE, m.bus().read32(present), "bit 2: a device in PS/2 port 1");
        m.floppy().insert(floppy(null), false);
        m.com(2).setHostConnected(true);
        m.nic().setLink(true);
        assertEquals(SysCon.PRESENT_MOUSE | SysCon.PRESENT_FLOPPY | SysCon.PRESENT_COM3 | SysCon.PRESENT_NIC_LINK,
                m.bus().read32(present));
    }

    @Test
    void the68000ReachesDisplay2ByDmaAt0xFFD80000() {
        Machine m = boot(CpuModel.CPU_68000, "1: bra.s 1b\n", "1: bra.s 1b\n");
        PagedDiskImage f = floppy(null);
        byte[] sector = new byte[512];
        sector[0] = 'Z';
        f.write(0, 1, sector, 0);
        m.floppy().insert(f, false);
        FloppyController fdc = m.floppy();
        fdc.write(FloppyController.COUNT, 4, 1);
        fdc.write(FloppyController.DMA_ADDR, 4, M68kMap.vramDisplayBase(2));
        fdc.write(FloppyController.COMMAND, 4, FloppyController.CMD_READ);
        assertEquals(FloppyController.ERR_NONE, fdc.read(FloppyController.ERROR_CODE, 4),
                "the 32-bit display 2 base is masked to 0xD8_0000 and lands in VRAM");
        runTicks(m, 10);
        assertEquals(FloppyController.STATUS_DONE, fdc.read(FloppyController.STATUS, 4) & 7);
        assertEquals('Z', m.vga(2).snapshot().pixelByte(0));
    }

    @Test
    void ramLimitsPerPlatform() {
        assertEquals(128 << 20, Rv32Platform.INSTANCE.maxRamBytes());
        assertEquals(0xD8_0000, M68kPlatform.M68000.maxRamBytes());
        assertEquals(128 << 20, M68kPlatform.M68040.maxRamBytes());
        assertEquals(Platform.forModel(CpuModel.CPU_68000).maxRamBytes(), M68kMap.RAM_MAX_68000);
        M68kPlatform.M68000.validate(MachineConfig.of(CpuModel.CPU_68000, 8_000_000, 0xD8_0000, null));
        assertThrows(IllegalArgumentException.class,
                () -> M68kPlatform.M68000.validate(MachineConfig.of(CpuModel.CPU_68000, 8_000_000, 14 << 20, null)));
        assertThrows(IllegalArgumentException.class,
                () -> M68kPlatform.M68040.validate(MachineConfig.of(CpuModel.CPU_68040, 25_000_000, (128 << 20) + 4096, null)));
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68040"})
    void a128MiBMachineUsesItsTopOfRam(CpuModel model) throws StateFormatException {
        int hz = rv(model) ? 10_000_000 : 25_000_000;
        Machine m = new Machine(MachineConfig.of(model, hz, 128 << 20, null), StandardCpus.INSTANCE);
        m.powerOn();
        runTicks(m, 2);
        int top = m.bus().ramBase() + (128 << 20) - 4;
        m.bus().write32(top, 0x1234_5678);
        assertEquals(0x1234_5678, m.bus().read32(top));
        assertEquals(128 << 20, m.bus().read32(at(m, windows(model)[0]) + SysCon.RAM_SIZE));
        byte[] state = m.saveState();
        assertTrue(state.length < 64 * 1024, "idle RAM costs little in a saved state: " + state.length);
        Machine n = new Machine(MachineConfig.of(model, hz, 128 << 20, null), StandardCpus.INSTANCE);
        n.loadState(state);
        assertEquals(0x1234_5678, n.bus().read32(top));
    }

    @Test
    void a13AndAHalfMiB68000RunsTheRom() {
        Machine m = new Machine(MachineConfig.of(CpuModel.CPU_68000, 8_000_000, 0xD8_0000, null), StandardCpus.INSTANCE);
        m.powerOn();
        runTicks(m, 3);
        assertEquals("Create Flippies PC  68000  13824 KiB RAM\r\nNo disk\r\n", com1(m));
    }

    // ---- three displays ----

    private static final String RV_DISPLAYS = """
            _start:
                li   sp, STACK_TOP
                li   s1, SYSCON
                lw   a0, 0x18(s1)           # VRAM_BASE: display 0
                la   a1, one
                call text
                lw   a0, 0x2C(s1)           # VRAM1_BASE
                la   a1, two
                call text
                lw   a0, 0x30(s1)           # VRAM2_BASE
                la   a1, three
                call text
                li   t0, VGA1
                li   t1, 0x0105
                sw   t1, 0x0C(t0)           # display 1's cursor
            9:  j    9b                     # keep running so frames end
            text:                           # a0 = framebuffer, a1 = string; attribute 0x1F
                lbu  t0, 0(a1)
                beqz t0, 1f
                sb   t0, 0(a0)
                li   t1, 0x1F
                sb   t1, 1(a0)
                addi a0, a0, 2
                addi a1, a1, 1
                j    text
            1:  ret
            one:   .asciz "DISPLAY ONE"
            two:   .asciz "DISPLAY TWO"
            three: .asciz "DISPLAY THREE"
            """;

    private static final String M68K_DISPLAYS = """
            _start: move.l  SYSCON+$18,a0       ; VRAM_BASE: display 0
                    lea     one(pc),a1
                    bsr.s   text
                    move.l  SYSCON+$2C,a0       ; VRAM1_BASE
                    lea     two(pc),a1
                    bsr.s   text
                    move.l  SYSCON+$30,a0       ; VRAM2_BASE
                    lea     three(pc),a1
                    bsr.s   text
                    move.l  #$0105,VGA1+$0C     ; display 1's cursor
            9:      bra.s   9b                  ; keep running so frames end
            text:   move.b  (a1)+,d0            ; a0 = framebuffer, a1 = string; attribute $1F
                    beq.s   1f
                    move.b  d0,(a0)+
                    move.b  #$1F,(a0)+
                    bra.s   text
            1:      rts
            one:    dc.b    "DISPLAY ONE",0
            two:    dc.b    "DISPLAY TWO",0
            three:  dc.b    "DISPLAY THREE",0
                    even
            """;

    private static String row0(VgaFrame f) {
        byte[] chars = new byte[80];
        for (int c = 0; c < 80; c++)
            chars[c] = (byte) f.pixelByte(c * 2);
        return new String(chars, StandardCharsets.ISO_8859_1).replace('\0', ' ').stripTrailing();
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void aProgramWritesDifferentTextToEachDisplay(CpuModel model) {
        Machine m = boot(model, RV_DISPLAYS, M68K_DISPLAYS);
        long[] gen = { m.vga(0).generation(), m.vga(1).generation(), m.vga(2).generation() };
        runTicks(m, 2);
        VgaFrame[] f = { m.vga(0).snapshot(), m.vga(1).snapshot(), m.vga(2).snapshot() };
        assertEquals("DISPLAY ONE", row0(f[0]));
        assertEquals("DISPLAY TWO", row0(f[1]));
        assertEquals("DISPLAY THREE", row0(f[2]));
        assertEquals(0x1F, f[2].pixelByte(1));
        assertEquals(5, f[1].cursorColumn());
        assertEquals(1, f[1].cursorRow());
        assertEquals(0, f[0].cursorColumn() | f[2].cursorColumn(), "the cursor is display 1's alone");
        for (int n = 0; n < 3; n++)
            assertTrue(m.vga(n).generation() > gen[n], "display " + n + " has a new generation");
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void everyDisplayEndsAFrameEachTickOnItsOwnLine(CpuModel model) {
        Machine m = boot(model, "1: j 1b\n", "1: bra.s 1b\n");
        int[] lines = rv(model) ? new int[] { Rv32Map.LINE_VGA, Rv32Map.LINE_VGA1, Rv32Map.LINE_VGA2 }
                : new int[] { M68kMap.LINE_VGA, M68kMap.LINE_VGA1, M68kMap.LINE_VGA2 };
        assertArrayEquals(new int[] { 6, 11, 12 }, lines);
        int frame = m.vga(2).frame();
        runTicks(m, 1);
        for (int n = 0; n < 3; n++)
            assertEquals(frame + 1, m.vga(n).frame(), "display " + n);
        assertEquals(0, m.board().pic().read(0, 4) & (1 << 6 | 1 << 11 | 1 << 12), "VBLANK interrupts are off");
        m.vga(1).write(VgaDisplay.CTRL, 4, VgaDisplay.CTRL_RESET | VgaDisplay.CTRL_VBLANK_IRQ);
        assertEquals(1 << 11, m.board().pic().read(0, 4) & (1 << 6 | 1 << 11 | 1 << 12), "only display 1's line");
        m.vga(0).write(VgaDisplay.CTRL, 4, VgaDisplay.CTRL_RESET | VgaDisplay.CTRL_VBLANK_IRQ);
        m.vga(2).write(VgaDisplay.CTRL, 4, VgaDisplay.CTRL_RESET | VgaDisplay.CTRL_VBLANK_IRQ);
        assertEquals(1 << 6 | 1 << 11 | 1 << 12, m.board().pic().read(0, 4) & (1 << 6 | 1 << 11 | 1 << 12));
    }

    // ---- COM3 ----

    private static final String RV_COM3_ECHO = """
            _start:
                li   s1, COM3
                li   t1, 0x03
                sb   t1, 3(s1)              # 8N1
                li   t1, 0x07
                sb   t1, 2(s1)              # FIFOs
            1:  lbu  t1, 5(s1)
                andi t1, t1, 1
                beqz t1, 1b
                lbu  a0, 0(s1)
            2:  lbu  t1, 5(s1)
                andi t1, t1, 0x20
                beqz t1, 2b
                sb   a0, 0(s1)
                li   t1, 10
                bne  a0, t1, 1b
            3:  lbu  t1, 5(s1)              # everything sent
                andi t1, t1, 0x40
                beqz t1, 3b
                call exit
            """;

    private static final String M68K_COM3_ECHO = """
            _start: lea     COM3,a1
                    move.b  #$03,3(a1)          ; 8N1
                    move.b  #$07,2(a1)          ; FIFOs
            1:      btst    #0,5(a1)
                    beq.s   1b
                    move.b  (a1),d0
            2:      btst    #5,5(a1)
                    beq.s   2b
                    move.b  d0,(a1)
                    cmp.b   #10,d0
                    bne.s   1b
            3:      btst    #6,5(a1)            ; everything sent
                    beq.s   3b
                    bra     exit
            """;

    private static final String RV_COM3_PING = """
            _start:
                li   sp, STACK_TOP
                li   s1, COM3
                li   t1, 0x03
                sb   t1, 3(s1)
                li   t1, 0x07
                sb   t1, 2(s1)
                la   s0, msg
            1:  lbu  a0, 0(s0)
                beqz a0, 3f
            2:  lbu  t1, 5(s1)
                andi t1, t1, 0x20
                beqz t1, 2b
                sb   a0, 0(s1)
                addi s0, s0, 1
                j    1b
            3:  li   s2, 5                  # the echo back, to COM1
            4:  lbu  t1, 5(s1)
                andi t1, t1, 1
                beqz t1, 4b
                lbu  a0, 0(s1)
                call putc
                addi s2, s2, -1
                bnez s2, 4b
                call exit
            msg: .asciz "ping\\n"
            """;

    private static final String M68K_COM3_PING = """
            _start: lea     COM3,a1
                    move.b  #$03,3(a1)
                    move.b  #$07,2(a1)
                    lea     msg(pc),a0
            1:      move.b  (a0)+,d0
                    beq.s   3f
            2:      btst    #5,5(a1)
                    beq.s   2b
                    move.b  d0,(a1)
                    bra.s   1b
            3:      moveq   #4,d1               ; the echo back, to COM1
            4:      btst    #0,5(a1)
                    beq.s   4b
                    move.b  (a1),d0
                    bsr     putc
                    dbra    d1,4b
                    bra     exit
            msg:    dc.b    "ping",10,0
                    even
            """;

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void com3EchoesThroughTheHost(CpuModel model) {
        Machine m = boot(model, RV_COM3_ECHO, M68K_COM3_ECHO);
        m.com(2).setHostConnected(true);
        byte[] in = "hello\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(in.length, m.com(2).hostWrite(in, 0, in.length));
        runUntilOff(m, 10);
        assertEquals(0, m.exitCode());
        byte[] out = new byte[64];
        int n = m.com(2).hostRead(out, 0, out.length);
        assertEquals("hello\n", new String(out, 0, n, StandardCharsets.US_ASCII));
        assertEquals(0, m.com(0).hostAvailable(), "nothing on COM1");
        assertEquals(0, m.com(1).hostAvailable());
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void aCom3LinkBetweenTwoMachinesPumpedEachTick(CpuModel model) {
        CpuModel other = model == CpuModel.RV32I ? CpuModel.CPU_68040 : CpuModel.RV32I;
        Machine a = boot(model, RV_COM3_PING, M68K_COM3_PING);
        Machine b = boot(other, RV_COM3_ECHO, M68K_COM3_ECHO);
        a.com(2).setHostConnected(true);
        b.com(2).setHostConnected(true);
        byte[] buf = new byte[4096];
        for (int t = 0; t < 20 && a.state() == MachineState.RUNNING; t++) {
            a.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, E2e.BIG_BUDGET);
            int n = a.com(2).hostRead(buf, 0, buf.length);
            assertEquals(n, b.com(2).hostWrite(buf, 0, n));
            n = b.com(2).hostRead(buf, 0, buf.length);
            assertEquals(n, a.com(2).hostWrite(buf, 0, n));
        }
        runUntilOff(a, 1);
        assertEquals(0, a.exitCode());
        assertEquals("ping\n", com1(a));
        assertEquals(MachineState.OFF_BY_GUEST, b.state(), "the echo stopped after the newline");
    }

    // ---- busy fraction, error light, halt reason ----

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void busyFractionOfABusyAndAnIdleGuest(CpuModel model) {
        Machine busy = boot(model, "1: j 1b\n", "1: bra.s 1b\n");
        assertEquals(0.0, busy.busyFraction(), "no tick yet");
        runTicks(busy, 2);
        assertEquals(1.0, busy.busyFraction(), 0.001);
        Machine idle = boot(model, """
                    csrw mie, zero
                1:  wfi
                    j    1b
                """, """
                1:      stop    #$2700
                        bra.s   1b
                """);
        runTicks(idle, 2);
        assertTrue(idle.busyFraction() < 0.001, "asleep: " + idle.busyFraction());
        idle.powerOff();
        runTicks(idle, 1);
        assertEquals(0.0, idle.busyFraction(), "off");
    }

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68000", "CPU_68040"})
    void guestErrorLed(CpuModel model) {
        Machine m = boot(model, """
                    li   t0, SYSCON
                    li   t1, 1
                    sw   t1, 0x34(t0)           # LEDS bit 0
                1:  j    1b
                """, """
                        move.l  #1,SYSCON+$34   ; LEDS bit 0
                1:      bra.s   1b
                """);
        assertFalse(m.guestErrorLed());
        runTicks(m, 1);
        assertTrue(m.guestErrorLed());
        assertEquals(1, m.bus().read32(at(m, windows(model)[0]) + SysCon.LEDS));
        assertNull(m.haltReason(), "not halted");
        m.reset();
        assertFalse(m.guestErrorLed(), "reset clears LEDS");
    }

    // ---- saved-state checks ----

    @ParameterizedTest
    @EnumSource(value = CpuModel.class, names = {"RV32I", "CPU_68040"})
    void theMacIsPartOfTheConfigCheck(CpuModel model) throws StateFormatException {
        Machine a = new Machine(config(model, null, 0x02_00_00_00_00_01L), StandardCpus.INSTANCE);
        a.powerOn();
        runTicks(a, 1);
        byte[] state = a.saveState();
        Machine same = new Machine(config(model, null, 0x02_00_00_00_00_01L), StandardCpus.INSTANCE);
        same.loadState(state);
        assertEquals(0x02_00_00_00_00_01L, same.nic().macAddress());
        Machine other = new Machine(config(model, null, 0x02_00_00_00_00_02L), StandardCpus.INSTANCE);
        StateFormatException e = assertThrows(StateFormatException.class, () -> other.loadState(state));
        assertTrue(e.getMessage().contains("MAC"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> config(model, null, 1L << 48));
        assertEquals(0x02_00_00_00_00_01L, MachineConfig.of(model, a.config().clockHz(), 1 << 20, null, false,
                0x02_00_00_00_00_01L).macAddress());
    }

    @Test
    void aDifferentHddIdIsRejected() throws StateFormatException {
        UUID id = UUID.randomUUID();
        PagedDiskImage disk = new PagedDiskImage(id, 1 << 20, chunk -> null, 4);
        Machine a = new Machine(config(CpuModel.RV32I, disk, 0), StandardCpus.INSTANCE);
        a.powerOn();
        runTicks(a, 1);
        byte[] state = a.saveState();
        Machine same = new Machine(config(CpuModel.RV32I, new PagedDiskImage(id, 1 << 20, chunk -> null, 4), 0),
                StandardCpus.INSTANCE);
        same.loadState(state);
        Machine swapped = new Machine(config(CpuModel.RV32I,
                new PagedDiskImage(UUID.randomUUID(), 1 << 20, chunk -> null, 4), 0), StandardCpus.INSTANCE);
        StateFormatException e = assertThrows(StateFormatException.class, () -> swapped.loadState(state));
        assertTrue(e.getMessage().contains(id.toString()), e.getMessage());
        assertEquals(MachineState.OFF, swapped.state(), "left as it was");
    }
}
