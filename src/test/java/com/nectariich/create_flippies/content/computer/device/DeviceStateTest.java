package com.nectariich.create_flippies.content.computer.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Saved state of every device (spec 1.5). Each test drives a device into a busy state, saves it,
 * loads it into a fresh device whose clock is set to the same cycle (as the machine restores
 * it), and then runs the same continuation on both: every observation and the final saved bytes
 * must match. Each device also rejects an unknown format version.
 */
class DeviceStateTest {

    private static final int HZ = 10_000_000;

    /** One device on its own rig, with two recorded interrupt lines. */
    static final class Twin<D extends BusDevice> {
        final DeviceRig rig = new DeviceRig();
        final DeviceRig.Line line = new DeviceRig.Line();
        final DeviceRig.Line line2 = new DeviceRig.Line();
        final byte[] ram = new byte[64 * 1024];
        final D dev;

        Twin(Function<Twin<D>, D> make, int window) {
            rig.bus.setRam(ram, DeviceRig.RAM_BASE);
            dev = make.apply(this);
            rig.map(dev, window);
        }
    }

    static byte[] save(BusDevice d) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            d.saveState(new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    static void load(BusDevice d, byte[] state) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        d.loadState(in);
        assertEquals(0, in.available(), "loadState reads exactly what saveState wrote");
    }

    /**
     * Saves {@code a}, loads it into a fresh twin at the same cycle, checks the bytes and lines,
     * runs {@code then} on both and compares what each saw and their final states.
     */
    static <D extends BusDevice> Twin<D> roundTrip(Twin<D> a, Function<Twin<D>, D> make, int window,
                                                 Function<Twin<D>, List<Object>> then) throws IOException {
        byte[] state = save(a.dev);
        Twin<D> b = new Twin<>(make, window);
        b.rig.at(a.rig.clock.now());
        System.arraycopy(a.ram, 0, b.ram, 0, a.ram.length);
        load(b.dev, state);
        // sinks are not driven by a load: what a line feeds (the interrupt controller, the CPU's
        // pins) restores its own copy of the level, as the recording lines do here
        b.line.level = a.line.level;
        b.line2.level = a.line2.level;
        assertArrayEquals(state, save(b.dev), "a loaded device saves the same bytes");
        assertArrayEquals(state, save(a.dev), "saving has no side effect");
        List<Object> seenA = then.apply(a);
        List<Object> seenB = then.apply(b);
        assertEquals(seenA, seenB, "the restored device behaves like the original");
        assertEquals(a.line.level, b.line.level, "line level");
        assertEquals(a.line2.level, b.line2.level, "second line level");
        assertArrayEquals(save(a.dev), save(b.dev), "final states match");
        assertArrayEquals(a.ram, b.ram, "RAM written by the device matches");
        return b;
    }

    static void assertRejectsUnknownVersion(BusDevice fresh, byte[] state) {
        byte[] bad = state.clone();
        bad[3] = 99;
        StateFormatException e = assertThrows(StateFormatException.class, () -> load(fresh, bad));
        assertTrue(e.getMessage().contains("version 99"), e.getMessage());
    }

    @Test
    void sysCon() throws IOException {
        Function<Twin<SysCon>, SysCon> make = t -> new SysCon(t.rig.clock, DeviceRig.RAM_BASE, 65536, HZ, 0, 0);
        Twin<SysCon> a = new Twin<>(make, 0x1000);
        for (char c : "helloé".toCharArray())
            a.rig.w8(SysCon.DEBUG_OUT, c);
        a.rig.w16(SysCon.POWER, SysCon.POWER_FAIL);      // low half latched, no request yet
        a.rig.w32(SysCon.WAIT, 1);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.dev.debugLog(), t.dev.waitRequested()));
            t.rig.w16(SysCon.POWER + 2, 0x0042);         // completes 0x3333 | 0x42 << 16
            seen.add(t.dev.powerRequest());
            seen.add(t.dev.exitCode());
            return seen;
        });
        assertRejectsUnknownVersion(make.apply(new Twin<>(make, 0x1000)), save(a.dev));
    }

    @Test
    void interruptController() throws IOException {
        Function<Twin<InterruptController>, InterruptController> make = t -> {
            InterruptController pic = new InterruptController();
            pic.setOutput(0, t.line);
            pic.setOutput(3, t.line2);
            return pic;
        };
        Twin<InterruptController> a = new Twin<>(make, 0x1000);
        a.rig.w32(InterruptController.ENABLE, 0xFE);
        a.rig.w32(InterruptController.ROUTE0, 0x3000);   // line 3 to output 3
        a.rig.w32(InterruptController.VECTOR_BASE, 0x40);
        a.dev.setLine(3, true);
        a.dev.setLine(5, true);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>();
            for (int off = 0; off < InterruptController.SIZE; off += 4)
                seen.add(t.rig.r32(off));
            t.dev.setLine(5, false);
            seen.add(t.dev.output(0));
            seen.add(t.dev.output(3));
            seen.add(t.dev.acknowledgeVector());
            return seen;
        });
        assertRejectsUnknownVersion(new InterruptController(), save(a.dev));
    }

    @Test
    void clint() throws IOException {
        Function<Twin<Clint>, Clint> make = t -> {
            Clint c = new Clint(t.rig.clock, HZ);
            c.setOutputs(t.line, t.line2);
            return c;
        };
        Twin<Clint> a = new Twin<>(make, Clint.SIZE);
        a.rig.at(12_345);
        a.rig.w32(Clint.MSIP, 1);
        a.rig.w32(Clint.MTIMECMP_HI, 0);
        a.rig.w32(Clint.MTIMECMP_LO, 2_000);
        roundTrip(a, make, Clint.SIZE, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.dev.nextEventCycle(), t.dev.mtip()));
            t.rig.at(20_000);
            t.dev.advanceTo(20_000);
            seen.add(t.dev.mtip());
            seen.add(t.rig.r32(Clint.MTIME_LO));
            return seen;
        });
        assertRejectsUnknownVersion(new Clint(a.rig.clock, HZ), save(a.dev));
    }

    @Test
    void intervalTimer() throws IOException {
        Function<Twin<IntervalTimer>, IntervalTimer> make = t -> new IntervalTimer(t.rig.clock, HZ, t.line);
        Twin<IntervalTimer> a = new Twin<>(make, 0x1000);
        a.rig.at(1_000);
        a.rig.w32(IntervalTimer.PERIOD, 300);
        a.rig.w32(IntervalTimer.CTRL, IntervalTimer.CTRL_ENABLE | IntervalTimer.CTRL_IRQ_ENABLE);
        a.rig.at(5_000);
        a.rig.r32(IntervalTimer.COUNTER_LO);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.dev.nextEventCycle(), t.rig.r32(IntervalTimer.COUNTER_HI)));
            for (long c = 6_000; c < 40_000; c += 2_500) {
                t.rig.at(c);
                t.dev.advanceTo(c);
                seen.add(t.line.level);
                if (t.line.level)
                    t.rig.w32(IntervalTimer.STATUS, IntervalTimer.STATUS_EXPIRED);
                seen.add(t.dev.nextEventCycle());
            }
            return seen;
        });
        assertRejectsUnknownVersion(new IntervalTimer(a.rig.clock, HZ, a.line), save(a.dev));
    }

    @Test
    void rtc() throws IOException {
        long[] host = { 1_700_000_000L };
        Function<Twin<Rtc>, Rtc> make = t -> new Rtc(() -> host[0]);
        Twin<Rtc> a = new Twin<>(make, 0x1000);
        a.rig.w32(Rtc.SET_LO, 1_000);
        a.rig.w32(Rtc.SET_HI, 0);
        a.rig.r32(Rtc.SECONDS_LO);
        a.rig.w32(Rtc.SET_LO, 77);                       // half of a pending SET
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.rig.r32(Rtc.SECONDS_LO), t.dev.seconds()));
            t.rig.w32(Rtc.SET_HI, 0);
            seen.add(t.dev.seconds());
            return seen;
        });
        assertRejectsUnknownVersion(new Rtc(() -> 0), save(a.dev));
    }

    @Test
    void uartMidTransmitWithHostInputQueued() throws IOException {
        Function<Twin<Uart16550>, Uart16550> make = t -> new Uart16550(t.rig.clock, HZ, t.line);
        Twin<Uart16550> a = new Twin<>(make, 0x1000);
        a.dev.setHostConnected(true);
        a.rig.w8(Uart16550.LCR, 0x83);                   // DLAB, 8N1
        a.rig.w8(Uart16550.DLL, 12);                     // 9600 baud
        a.rig.w8(Uart16550.LCR, 0x03);
        a.rig.w8(Uart16550.FCR, 0x87);                   // FIFOs on, trigger 8
        a.rig.w8(Uart16550.IER, Uart16550.IER_RDA | Uart16550.IER_THRE);
        for (char c : "transmitting".toCharArray())
            a.rig.w8(Uart16550.THR, c);
        a.dev.hostWrite("typed by the host".getBytes(), 0, 17);
        a.rig.at(25_000);                                // two characters out, two in
        a.dev.advanceTo(25_000);
        a.dev.hostRead(new byte[1], 0, 1);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.dev.nextEventCycle(), t.dev.hostPending()));
            StringBuilder rx = new StringBuilder();
            for (long c = 30_000; c < 400_000; c += 7_000) {
                t.rig.at(c);
                seen.add(t.rig.r8(Uart16550.LSR));
                seen.add(t.rig.r8(Uart16550.IIR));
                while ((t.rig.r8(Uart16550.LSR) & Uart16550.LSR_DR) != 0)
                    rx.append((char) t.rig.r8(Uart16550.RBR));
            }
            byte[] out = new byte[64];
            int n = t.dev.hostRead(out, 0, out.length);
            seen.add(new String(out, 0, n));
            seen.add(rx.toString());
            return seen;
        });
        assertRejectsUnknownVersion(new Uart16550(a.rig.clock, HZ, a.line), save(a.dev));
    }

    @Test
    void ps2KeyboardRepeatingAndMouseReporting() throws IOException {
        Function<Twin<Ps2Controller>, Ps2Controller> make = t -> {
            Ps2Controller p = new Ps2Controller(t.rig.clock, HZ, t.line, t.line2);
            p.reset();
            return p;
        };
        Twin<Ps2Controller> a = new Twin<>(make, 0x1000);
        int en = Ps2Controller.CTRL_IRQ_ENABLE | Ps2Controller.CTRL_PORT_ENABLE;
        a.rig.w32(Ps2Controller.KEYBOARD_PORT + Ps2Controller.CTRL, en);
        a.rig.w32(Ps2Controller.MOUSE_PORT + Ps2Controller.CTRL, en);
        a.rig.w32(Ps2Controller.KEYBOARD_PORT + Ps2Controller.DATA, 0xF3);   // typematic, data pending
        a.rig.w32(Ps2Controller.MOUSE_PORT + Ps2Controller.DATA, 0xF4);      // reporting on
        a.rig.at(10_000);
        a.dev.keyboard().keyDown(Ps2Key.A);
        a.dev.mouse().buttons(Ps2Mouse.LEFT);
        a.dev.mouse().move(300, -5);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>();
            t.rig.w32(Ps2Controller.KEYBOARD_PORT + Ps2Controller.DATA, 0x20);  // typematic byte
            for (long c = 20_000; c < 8_000_000; c += 400_000) {
                t.rig.at(c);
                for (int port : new int[] { Ps2Controller.KEYBOARD_PORT, Ps2Controller.MOUSE_PORT }) {
                    seen.add(t.rig.r32(port + Ps2Controller.STATUS));
                    seen.add(t.rig.r32(port + Ps2Controller.DATA));
                }
            }
            t.dev.keyboard().keyUp(Ps2Key.A);
            seen.add(t.dev.keyboard().queued());
            return seen;
        });
        assertRejectsUnknownVersion(make.apply(new Twin<>(make, 0x1000)), save(a.dev));
    }

    @Test
    void vgaGraphicsModeWithPaletteAndPendingFrame() throws IOException {
        Function<Twin<VgaDisplay>, VgaDisplay> make = t -> new VgaDisplay(t.line);
        Twin<VgaDisplay> a = new Twin<>(make, 0x1000);
        a.rig.w32(VgaDisplay.MODE, VgaDisplay.MODE_320X200X8);
        a.rig.w32(VgaDisplay.CTRL, VgaDisplay.CTRL_DISPLAY | VgaDisplay.CTRL_VBLANK_IRQ);
        a.rig.w32(VgaDisplay.PAL_INDEX, 7);
        a.rig.w16(VgaDisplay.PAL_DATA, 0x3456);           // half a palette entry latched
        a.rig.w32(VgaDisplay.CURSOR, 0x0A05);
        for (int i = 0; i < 200; i++)
            a.dev.vram()[i * 321] = (byte) i;
        a.dev.endFrame();
        a.dev.vram()[100_000] = 1;
        a.dev.dirtyPages()[0] |= 1L << (100_000 >>> 12);
        roundTrip(a, make, 0x1000, t -> {
            List<Object> seen = new ArrayList<>();
            VgaFrame f = t.dev.snapshot();
            seen.add(List.of(f.mode(), f.cursorColumn(), f.cursorRow(), f.generation(),
                    java.util.Arrays.hashCode(f.pixels()), java.util.Arrays.hashCode(f.palette())));
            seen.add(t.dev.dirtyRowsSince(0));
            t.rig.w16(VgaDisplay.PAL_DATA + 2, 0x0012);
            t.dev.endFrame();
            seen.add(t.dev.generation());
            seen.add(t.dev.dirtyRowsSince(1));
            seen.add(t.dev.palette(7));
            seen.add(t.rig.r32(VgaDisplay.FRAME));
            return seen;
        });
        assertRejectsUnknownVersion(new VgaDisplay(a.line), save(a.dev));
    }

    @Test
    void hddCommandInFlightCompletesAfterLoad() throws IOException {
        SparseDiskImage diskA = new SparseDiskImage(1024 * 1024);
        SparseDiskImage diskB = new SparseDiskImage(1024 * 1024);
        byte[] data = new byte[4 * 512];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte) (i * 13 + 1);
        diskA.write(10, 4, data, 0);
        diskB.write(10, 4, data, 0);
        Function<Twin<HddController>, HddController> makeA = t -> {
            HddController h = new HddController(t.rig.clock, HZ, t.rig.bus, t.line);
            h.setDisk(diskA);
            return h;
        };
        Function<Twin<HddController>, HddController> makeB = t -> {
            HddController h = new HddController(t.rig.clock, HZ, t.rig.bus, t.line);
            h.setDisk(diskB);
            return h;
        };
        Twin<HddController> a = new Twin<>(makeA, 0x1000);
        a.rig.w32(HddController.IRQ_ENABLE, 1);
        a.rig.w32(HddController.LBA, 10);
        a.rig.w32(HddController.COUNT, 4);
        a.rig.w32(HddController.DMA_ADDR, DeviceRig.RAM_BASE + 0x100);
        a.rig.w32(HddController.COMMAND, HddController.CMD_READ);
        a.rig.at(3_000);
        assertTrue(a.dev.busy());
        roundTrip(a, makeB, 0x1000, t -> {
            List<Object> seen = new ArrayList<>(List.of(t.dev.busy(), t.dev.nextEventCycle()));
            t.rig.at(9_000);
            seen.add(t.rig.r32(HddController.STATUS));
            t.rig.at(9_000 + 1_000_000);
            seen.add(t.rig.r32(HddController.STATUS));
            seen.add(t.line.level);
            return seen;
        });
        assertArrayEquals(data, java.util.Arrays.copyOfRange(a.ram, 0x100, 0x100 + data.length));
    }

    @Test
    void hddRejectsADifferentOrMissingDisk() {
        DeviceRig rig = new DeviceRig();
        HddController withDisk = new HddController(rig.clock, HZ, rig.bus, IrqSink.NONE);
        withDisk.setDisk(new SparseDiskImage(1024 * 1024));
        byte[] state = save(withDisk);

        HddController noDisk = new HddController(rig.clock, HZ, rig.bus, IrqSink.NONE);
        StateFormatException e = assertThrows(StateFormatException.class, () -> load(noDisk, state));
        assertTrue(e.getMessage().contains("no disk"), e.getMessage());

        HddController bigger = new HddController(rig.clock, HZ, rig.bus, IrqSink.NONE);
        bigger.setDisk(new SparseDiskImage(2 * 1024 * 1024));
        e = assertThrows(StateFormatException.class, () -> load(bigger, state));
        assertTrue(e.getMessage().contains("2048 sectors") && e.getMessage().contains("4096 sectors"), e.getMessage());

        HddController same = new HddController(rig.clock, HZ, rig.bus, IrqSink.NONE);
        same.setDisk(new SparseDiskImage(1024 * 1024));
        assertRejectsUnknownVersion(same, state);
    }
}
