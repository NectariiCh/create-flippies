package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.machine.Rv32Map.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Board;
import com.nectariich.create_flippies.content.computer.machine.MachineConfig;
import com.nectariich.create_flippies.content.computer.machine.Rv32Platform;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Random register traffic on every device of a full RV32I board, with random host input and
 * random time steps (spec 8.3). Nothing may throw, and after every step each interrupt line seen
 * at the controller must match what the device's own registers say.
 */
class DeviceFuzzTest {

    private static final int[] BASES = { SYSCON_BASE, CLINT_BASE, PIC_BASE, COM1_BASE, COM2_BASE,
            PS2_BASE, HDD_BASE, VGA_BASE, TIMER_BASE, RTC_BASE };

    @Test
    void randomTrafficNeverThrowsAndLinesMatchRegisters() {
        for (long seed = 1; seed <= 6; seed++)
            run(seed, 20_000);
    }

    private void run(long seed, int steps) {
        Random rnd = new Random(seed);
        CycleClock clock = new CycleClock();
        MachineConfig config = new MachineConfig(CpuModel.RV32I, 10_000_000, 64 * 1024,
                new SparseDiskImage(1024 * 1024), new byte[] { 0x13 }, () -> 1_700_000_000L, null);
        Board board = Rv32Platform.INSTANCE.build(config, clock);
        for (BusDevice d : board.devices())
            d.reset();
        SystemBus bus = board.memory();
        Ps2Key[] keys = Ps2Key.values();

        for (int step = 0; step < steps; step++) {
            String what = "seed " + seed + " step " + step;
            int op = rnd.nextInt(100);
            if (op < 70) {
                int base = BASES[rnd.nextInt(BASES.length)];
                int window = base == CLINT_BASE ? CLINT_SIZE : DEVICE_WINDOW;
                int width = 1 << rnd.nextInt(3);
                int offset = rnd.nextInt(4) == 0 ? rnd.nextInt(window) : rnd.nextInt(0x40);
                if (base == CLINT_BASE && rnd.nextBoolean())
                    offset = new int[] { 0, 0x4000, 0x4004, 0xBFF8, 0xBFFC }[rnd.nextInt(5)] + rnd.nextInt(4);
                if (base == VGA_BASE && rnd.nextInt(4) == 0)
                    offset = 0x400 + rnd.nextInt(0x400);
                offset &= -width;
                int value = interesting(rnd);
                if (base == HDD_BASE && rnd.nextBoolean() && (offset & ~3) == HddController.DMA_ADDR)
                    value = RAM_BASE + rnd.nextInt(64 * 1024);
                int address = base + offset;
                if (rnd.nextBoolean()) {
                    switch (width) {
                        case 1 -> bus.read8(address);
                        case 2 -> bus.read16(address);
                        default -> bus.read32(address);
                    }
                } else {
                    switch (width) {
                        case 1 -> bus.write8(address, value);
                        case 2 -> bus.write16(address, value);
                        default -> bus.write32(address, value);
                    }
                }
            } else if (op < 80) {
                clock.advanceTo(clock.now() + (rnd.nextBoolean() ? rnd.nextInt(2000) : rnd.nextInt(5_000_000)));
                for (BusDevice d : board.devices())
                    d.advanceTo(clock.now());
            } else if (op < 85) {
                Ps2Key k = keys[rnd.nextInt(keys.length)];
                if (rnd.nextBoolean())
                    board.ps2().keyboard().keyDown(k);
                else
                    board.ps2().keyboard().keyUp(k);
            } else if (op < 90) {
                if (rnd.nextBoolean())
                    board.ps2().mouse().move(rnd.nextInt(1000) - 500, rnd.nextInt(1000) - 500);
                else
                    board.ps2().mouse().buttons(rnd.nextInt(8));
            } else if (op < 95) {
                Uart16550 com = board.com(rnd.nextInt(2));
                byte[] b = new byte[rnd.nextInt(40)];
                rnd.nextBytes(b);
                com.hostWrite(b, 0, b.length);
                com.hostRead(new byte[64], 0, 64);
            } else if (op < 97) {
                board.com(rnd.nextInt(2)).setHostConnected(rnd.nextBoolean());
            } else if (op < 98) {
                board.vga().endFrame();
            } else {
                board.vga().copyVisibleInto(new byte[640 * 480]);
                board.vga().snapshot();
                board.vga().dirtyRowsSince(rnd.nextInt(5));
            }
            checkLines(board, clock, what);
        }
    }

    private static int interesting(Random rnd) {
        return switch (rnd.nextInt(6)) {
            case 0 -> 0;
            case 1 -> -1;
            case 2 -> rnd.nextInt(16);
            case 3 -> 1 << rnd.nextInt(32);
            case 4 -> rnd.nextInt(0x300);
            default -> rnd.nextInt();
        };
    }

    /** Catches devices up, then compares register state (no read side effects) with the controller's PENDING. */
    private static void checkLines(Board board, CycleClock clock, String what) {
        // the machine catches every device up at each segment end; lines are compared there
        for (BusDevice d : board.devices())
            d.advanceTo(clock.now());
        InterruptController pic = board.pic();
        int pending = pic.read(InterruptController.PENDING, 4);

        for (int i = 0; i < 2; i++) {
            Uart16550 u = board.com(i);
            boolean expected = (u.iirValue() & 1) == 0;
            assertEquals(expected, bit(pending, LINE_COM1 + i), what + ": COM" + (i + 1));
        }
        Ps2Controller ps2 = board.ps2();
        assertEquals((ps2.ctrl(0) & 1) != 0 && ps2.keyboard().queued() > 0, bit(pending, LINE_KEYBOARD), what + ": keyboard");
        assertEquals((ps2.ctrl(1) & 1) != 0 && ps2.mouse().queued() > 0, bit(pending, LINE_MOUSE), what + ": mouse");

        HddController hdd = board.hdd();
        int hddStatus = hdd.read(HddController.STATUS, 4);
        boolean hddExpected = hdd.read(HddController.IRQ_ENABLE, 4) != 0
                && (hddStatus & (HddController.STATUS_DONE | HddController.STATUS_ERROR)) != 0;
        assertEquals(hddExpected, bit(pending, LINE_HDD), what + ": HDD");

        VgaDisplay vga = board.vga();
        boolean vgaExpected = (vga.read(VgaDisplay.STATUS, 4) & 1) != 0 && (vga.ctrl() & VgaDisplay.CTRL_VBLANK_IRQ) != 0;
        assertEquals(vgaExpected, bit(pending, LINE_VGA), what + ": VGA");

        IntervalTimer timer = board.timer();
        boolean timerExpected = (timer.read(IntervalTimer.STATUS, 4) & 1) != 0
                && (timer.read(IntervalTimer.CTRL, 4) & IntervalTimer.CTRL_IRQ_ENABLE) != 0;
        assertEquals(timerExpected, bit(pending, LINE_TIMER), what + ": timer");

        Clint clint = board.clint();
        assertEquals(Long.compareUnsigned(clint.mtime(), clint.mtimecmp()) >= 0, clint.mtip(), what + ": MTIP");

        int enable = pic.read(InterruptController.ENABLE, 4);
        int route0 = 0;
        for (int n = 1; n < 32; n++) {
            int r = pic.read(InterruptController.ROUTE0 + 4 * (n >>> 3), 4) >>> (4 * (n & 7)) & 7;
            if (r == 0)
                route0 |= 1 << n;
        }
        assertEquals((pending & enable & route0) != 0, pic.output(0), what + ": controller output 0");
    }

    private static boolean bit(int v, int n) {
        return (v & (1 << n)) != 0;
    }
}
