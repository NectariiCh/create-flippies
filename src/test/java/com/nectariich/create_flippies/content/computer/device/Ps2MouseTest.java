package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Ps2Controller.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The PS/2 controller's mouse port and the 3-byte mouse (spec 3.6), at 10 MHz. */
class Ps2MouseTest {

    /** One default sample period, 10 ms. */
    private static final long SAMPLE = 100_000;

    private DeviceRig rig;
    private Ps2Controller ps2;
    private Ps2Mouse mouse;
    private final DeviceRig.Line mouseLine = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ps2 = new Ps2Controller(rig.clock, 10_000_000, new DeviceRig.Line(), mouseLine);
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        ps2.setDevice(1, Ps2DeviceKind.MOUSE);
        ps2.reset();
        mouse = ps2.mouse();
        rig.map(ps2, 0x1000);
    }

    private byte[] drain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((rig.r32(MOUSE_PORT + STATUS) & STATUS_RX_READY) != 0)
            out.write(rig.r32(MOUSE_PORT + DATA));
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            r[i] = (byte) v[i];
        return r;
    }

    private void command(int... bytes) {
        for (int x : bytes)
            rig.w32(MOUSE_PORT + DATA, x);
    }

    private void enableReporting() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE);
        drain();
        command(0xF4);
        assertArrayEquals(b(0xFA), drain());
    }

    @Test
    void powerOnAndReset() {
        assertArrayEquals(b(0xAA, 0x00), drain());
        command(0xFF);
        assertArrayEquals(b(0xFA, 0xAA, 0x00), drain());
    }

    @Test
    void noPacketsUntilReportingIsEnabled() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE);
        drain();
        mouse.move(5, 3);
        rig.at(SAMPLE * 5);
        assertArrayEquals(b(), drain());
        command(0xF4);
        assertArrayEquals(b(0xFA), drain(), "counters cleared on enable: no stale packet");
        mouse.move(5, 3);
        assertArrayEquals(b(0x08, 0x05, 0x03), drain());
    }

    @Test
    void negativeMovementSetsTheSignBits() {
        enableReporting();
        mouse.move(-5, -3);
        assertArrayEquals(b(0x38, 0xFB, 0xFD), drain());
    }

    @Test
    void buttonBits() {
        enableReporting();
        mouse.buttons(Ps2Mouse.LEFT | Ps2Mouse.MIDDLE);
        assertArrayEquals(b(0x0D, 0x00, 0x00), drain());
        rig.at(SAMPLE);
        mouse.buttons(Ps2Mouse.RIGHT);
        assertArrayEquals(b(0x0A, 0x00, 0x00), drain());
    }

    @Test
    void onePacketPerSamplePeriod() {
        enableReporting();
        rig.at(1000);
        mouse.move(1, 0);
        assertArrayEquals(b(0x08, 0x01, 0x00), drain());
        mouse.move(2, 0);
        assertArrayEquals(b(), drain(), "the next packet waits for the sample period");
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        assertEquals(1000 + SAMPLE, ps2.nextEventCycle());
        rig.at(1000 + SAMPLE - 1);
        assertArrayEquals(b(), drain());
        rig.at(1000 + SAMPLE);
        assertArrayEquals(b(0x08, 0x02, 0x00), drain());
    }

    @Test
    void clampingSetsOverflowAndCarriesTheRemainder() {
        enableReporting();
        mouse.move(300, -400);
        assertArrayEquals(b(0xE8, 0xFF, 0x00), drain(), "255 and -256 with both overflow bits");
        rig.at(SAMPLE);
        assertArrayEquals(b(0x28, 0x2D, 0x70), drain(), "45 and -144 carried");
        rig.at(SAMPLE * 3);
        assertArrayEquals(b(), drain());
    }

    @Test
    void statusBytes() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE);
        drain();
        mouse.buttons(Ps2Mouse.LEFT);
        command(0xE9);
        assertArrayEquals(b(0xFA, 0x04, 0x02, 0x64), drain(), "left is bit 2; resolution 2; 100 samples/s");
        mouse.buttons(Ps2Mouse.RIGHT);
        command(0xE9);
        assertArrayEquals(b(0xFA, 0x01, 0x02, 0x64), drain(), "right is bit 0");
        command(0xF4);
        command(0xE9);
        assertArrayEquals(b(0xFA, 0xFA, 0x21, 0x02, 0x64), drain(), "reporting is bit 5");
        command(0xE7);
        command(0xE9);
        assertArrayEquals(b(0xFA, 0xFA, 0x31, 0x02, 0x64), drain(), "2:1 scaling is bit 4");
        command(0xE8, 0x03, 0xF3, 40, 0xE9);
        assertArrayEquals(b(0xFA, 0xFA, 0xFA, 0xFA, 0xFA, 0x31, 0x03, 40), drain());
    }

    @Test
    void identifyAndUnknown() {
        drain();
        command(0xF2);
        assertArrayEquals(b(0xFA, 0x00), drain());
        command(0x99);
        assertArrayEquals(b(0xFE), drain());
    }

    @Test
    void scalingTwoToOneAppliesTheStandardTransform() {
        enableReporting();
        command(0xE7);
        drain();
        mouse.move(4, -5);
        assertArrayEquals(b(0x28, 6, -9 & 0xFF), drain());
        rig.at(SAMPLE);
        mouse.move(2, 7);
        assertArrayEquals(b(0x08, 1, 14), drain());
    }

    @Test
    void readDataReturnsOnePacket() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE);
        drain();
        mouse.move(7, 0);
        command(0xEB);
        assertArrayEquals(b(0xFA, 0x08, 0x07, 0x00), drain());
    }

    @Test
    void overflowDropsWholePackets() {
        drain();
        for (int i = 0; i < 12; i++)
            command(0xE9);
        assertEquals(48, mouse.queued());
        command(0xF2);
        assertEquals(48, mouse.queued(), "the 2-byte reply did not fit and was dropped whole");
        assertTrue((rig.r32(MOUSE_PORT + STATUS) & STATUS_OVERRUN) != 0);
        assertEquals(48, rig.r32(MOUSE_PORT + STATUS) >>> 8);
    }

    @Test
    void mouseIrqIsLineFour() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        assertTrue(mouseLine.level, "power-on bytes are waiting");
        drain();
        assertEquals(false, mouseLine.level);
    }
}
