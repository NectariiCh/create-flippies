package com.nectariich.create_flippies.content.computer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.Ps2Keyboard;
import com.nectariich.create_flippies.content.computer.device.Ps2Mouse;
import com.nectariich.create_flippies.content.computer.device.SparseDiskImage;

import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * Bugs found by the adversarial review of the bus, devices and machine against docs/PC_SPEC.md.
 * Each test states the spec rule it checks and fails while the bug is present.
 */
class ReviewSystemTest {

    private static final int HZ = 10_000_000;

    private static Ps2Controller ps2(CycleClock clock) {
        Ps2Controller ps2 = new Ps2Controller(clock, HZ, IrqSink.NONE, IrqSink.NONE);
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        ps2.setDevice(1, Ps2DeviceKind.MOUSE);
        ps2.reset();
        return ps2;
    }

    private static int keyboardStatus(Ps2Controller ps2) {
        return ps2.read(Ps2Controller.KEYBOARD_PORT + Ps2Controller.STATUS, 4);
    }

    /**
     * Spec 3.6: when the keyboard queue fills, 0x00 goes in the last slot and further bytes are
     * dropped "until it is read". Unplugging the keyboard clears the queue, so the overrun code is
     * gone; a replugged keyboard must queue key presses again. The overflow marker is only cleared
     * when a pop empties the queue, so after an unplug with a full queue the keyboard stays mute
     * until the next device reset.
     */
    @Test
    void keyboardStaysMuteAfterUnplugWhileItsQueueOverflowed() {
        CycleClock clock = new CycleClock();
        Ps2Controller ps2 = ps2(clock);
        Ps2Keyboard kb = ps2.keyboard();
        ps2.write(Ps2Controller.KEYBOARD_PORT + Ps2Controller.CTRL, 4, Ps2Controller.CTRL_PORT_ENABLE);
        byte[] flood = new byte[Ps2Keyboard.CAPACITY + 8];
        kb.inject(flood);
        assertEquals(Ps2Keyboard.CAPACITY, kb.queued(), "precondition: queue full with the overrun code");

        kb.setPresent(false);
        kb.setPresent(true);
        assertEquals(0, kb.queued(), "precondition: unplug cleared the queue");

        kb.keyDown(Ps2Key.A);
        assertEquals(1, kb.queued(), "a replugged keyboard must queue the make code of A");
    }

    /**
     * Spec 3.6 and Ps2Device.setPresent: an absent device queues nothing. A reset of the
     * controller (every machine reset and power-on) runs the keyboard's power-on sequence, which
     * queues 0xAA even with no keyboard plugged in, so STATUS reports RX ready for a missing device.
     */
    @Test
    void absentKeyboardQueuesSelfTestByteOnReset() {
        CycleClock clock = new CycleClock();
        Ps2Controller ps2 = ps2(clock);
        ps2.keyboard().setPresent(false);
        ps2.reset();
        assertEquals(0, ps2.keyboard().queued(), "an absent keyboard must not queue 0xAA");
        assertEquals(0, keyboardStatus(ps2) & Ps2Controller.STATUS_RX_READY);
    }

    /** The same for the mouse: its power-on 0xAA 0x00 is queued while it is unplugged. */
    @Test
    void absentMouseQueuesPowerOnBytesOnReset() {
        CycleClock clock = new CycleClock();
        Ps2Controller ps2 = ps2(clock);
        Ps2Mouse mouse = ps2.mouse();
        mouse.setPresent(false);
        ps2.reset();
        assertEquals(0, mouse.queued(), "an absent mouse must not queue 0xAA 0x00");
    }

    /**
     * Spec 3.8 and HddController.setDisk (the host may swap or remove a drive at any time): the LBA
     * range is checked only when the command is accepted. If the host swaps in a smaller disk
     * while a READ near the end of the old disk is in flight, completion calls DiskImage.read out
     * of range and the IndexOutOfBoundsException escapes from advanceTo, that is from the
     * machine's catch-up, crashing the server tick. A removed disk is handled (ERROR, no media);
     * a smaller one is not.
     */
    @Test
    void hddCompletionAfterSwapToSmallerDiskThrowsOutOfTheDevice() {
        CycleClock clock = new CycleClock();
        SystemBus bus = new SystemBus(ByteOrder.LITTLE_ENDIAN);
        int ramBase = 0x8000_0000;
        bus.setRam(new byte[64 * 1024], ramBase);
        HddController hdd = new HddController(clock, HZ, bus, IrqSink.NONE);
        SparseDiskImage big = new SparseDiskImage(1024 * 1024);
        hdd.setDisk(big);

        int lastSector = (int) big.sectorCount() - 1;
        hdd.write(HddController.LBA, 4, lastSector);
        hdd.write(HddController.COUNT, 4, 1);
        hdd.write(HddController.DMA_ADDR, 4, ramBase);
        hdd.write(HddController.COMMAND, 4, HddController.CMD_READ);
        assertTrue(hdd.busy(), "precondition: the READ was accepted");

        hdd.setDisk(new SparseDiskImage(64 * 1024));
        long completion = hdd.nextEventCycle();
        clock.advanceTo(completion);
        assertDoesNotThrow(() -> hdd.advanceTo(completion), "completion against the new disk must fail cleanly");
        int status = hdd.read(HddController.STATUS, 4);
        assertEquals(HddController.STATUS_ERROR, status & HddController.STATUS_ERROR, "the command must end in ERROR");
    }
}
