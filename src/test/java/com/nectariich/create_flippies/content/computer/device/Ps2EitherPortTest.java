package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Ps2Controller.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Either device on either PS/2 port (spec 11.1.4). */
class Ps2EitherPortTest {

    private DeviceRig rig;
    private Ps2Controller ps2;
    private final DeviceRig.Line line0 = new DeviceRig.Line();
    private final DeviceRig.Line line1 = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ps2 = new Ps2Controller(rig.clock, 10_000_000, line0, line1);
        ps2.reset();
        rig.map(ps2, 0x1000);
    }

    private byte[] drain(int port) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((rig.r32(port + STATUS) & STATUS_RX_READY) != 0)
            out.write(rig.r32(port + DATA));
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            r[i] = (byte) v[i];
        return r;
    }

    @Test
    void bothPortsStartUnplugged() {
        assertEquals(Ps2DeviceKind.NONE, ps2.device(0));
        assertEquals(Ps2DeviceKind.NONE, ps2.device(1));
        assertEquals(0, rig.r32(KEYBOARD_PORT + STATUS));
        assertEquals(0, rig.r32(MOUSE_PORT + STATUS));
        rig.w32(KEYBOARD_PORT + DATA, 0xF2);
        assertEquals(0, rig.r32(KEYBOARD_PORT + STATUS), "nothing answers an empty port");
        assertThrows(IllegalArgumentException.class, () -> ps2.device(2));
    }

    @Test
    void keyboardOnPort1AnswersIdentifyOnLine4() {
        rig.w32(MOUSE_PORT + CTRL, CTRL_IRQ_ENABLE | CTRL_PORT_ENABLE);
        ps2.setDevice(1, Ps2DeviceKind.KEYBOARD);
        assertEquals(Ps2DeviceKind.KEYBOARD, ps2.device(1));
        assertTrue(line1.level, "the plugged-in keyboard's 0xAA is waiting on port 1");
        assertFalse(line0.level);
        assertArrayEquals(b(0xAA), drain(MOUSE_PORT));
        rig.w32(MOUSE_PORT + DATA, 0xF2);
        assertTrue(line1.level);
        assertArrayEquals(b(0xFA, 0xAB, 0x83), drain(MOUSE_PORT));
        ps2.keyboard(1).keyDown(Ps2Key.A);
        assertArrayEquals(b(0x1C), drain(MOUSE_PORT), "keys arrive on port 1");
        assertEquals(0, ps2.keyboard(0).queued());
    }

    @Test
    void mouseOnPort0() {
        ps2.setDevice(0, Ps2DeviceKind.MOUSE);
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE);
        assertArrayEquals(b(0xAA, 0x00), drain(KEYBOARD_PORT));
        rig.w32(KEYBOARD_PORT + DATA, 0xF2);
        assertArrayEquals(b(0xFA, 0x00), drain(KEYBOARD_PORT), "a mouse identifies itself");
    }

    @Test
    void switchingKindsEmptiesTheQueueAndQueuesPowerOnBytes() {
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE);
        ps2.keyboard(0).keyDown(Ps2Key.B);
        assertEquals(2, ps2.keyboard(0).queued(), "0xAA and the make code");
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        assertEquals(2, ps2.keyboard(0).queued(), "the same kind again changes nothing");
        ps2.setDevice(0, Ps2DeviceKind.MOUSE);
        assertEquals(0, ps2.keyboard(0).queued(), "the unplugged keyboard's queue is empty");
        assertFalse(ps2.keyboard(0).present());
        assertEquals(STATUS_PRESENT | STATUS_RX_READY | 2 << 8, rig.r32(KEYBOARD_PORT + STATUS));
        ps2.mouse(0).move(5, 5);
        assertArrayEquals(b(0xAA, 0x00), drain(KEYBOARD_PORT), "reporting is off after power-on");
        ps2.setDevice(0, Ps2DeviceKind.NONE);
        assertEquals(Ps2DeviceKind.NONE, ps2.device(0));
        assertEquals(0, rig.r32(KEYBOARD_PORT + STATUS));
        ps2.setDevice(0, Ps2DeviceKind.MOUSE);
        assertArrayEquals(b(0xAA, 0x00), drain(KEYBOARD_PORT), "replugging queues the power-on bytes");
    }

    @Test
    void resetKeepsWhatIsPluggedIn() {
        ps2.setDevice(0, Ps2DeviceKind.MOUSE);
        ps2.setDevice(1, Ps2DeviceKind.KEYBOARD);
        drain(KEYBOARD_PORT);
        drain(MOUSE_PORT);
        ps2.reset();
        assertEquals(Ps2DeviceKind.MOUSE, ps2.device(0));
        assertEquals(Ps2DeviceKind.KEYBOARD, ps2.device(1));
        assertArrayEquals(b(0xAA, 0x00), drain(KEYBOARD_PORT));
        assertArrayEquals(b(0xAA), drain(MOUSE_PORT));
    }

    @Test
    void savedStateKeepsTheKindsPerPort() throws IOException {
        ps2.setDevice(0, Ps2DeviceKind.MOUSE);
        ps2.setDevice(1, Ps2DeviceKind.KEYBOARD);
        rig.w32(MOUSE_PORT + CTRL, CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        ps2.keyboard(1).keyDown(Ps2Key.C);
        byte[] state = DeviceStateTest.save(ps2);
        Ps2Controller b = new Ps2Controller(rig.clock, 10_000_000, new DeviceRig.Line(), new DeviceRig.Line());
        DeviceStateTest.load(b, state);
        assertEquals(Ps2DeviceKind.MOUSE, b.device(0));
        assertEquals(Ps2DeviceKind.KEYBOARD, b.device(1));
        assertEquals(2, b.keyboard(1).queued());
        assertArrayEquals(state, DeviceStateTest.save(b));
    }

    @Test
    void version1PayloadLoadsAsKeyboardOnPort0AndMouseOnPort1() throws IOException {
        // what a version 1 controller wrote: CTRL and level per port, then its keyboard and mouse
        DeviceRig other = new DeviceRig();
        Ps2Keyboard kb = new Ps2Keyboard(other.clock, 10_000_000);
        Ps2Mouse mouse = new Ps2Mouse(other.clock, 10_000_000);
        kb.reset();
        mouse.reset();
        kb.setPortEnabled(true);
        kb.keyDown(Ps2Key.D);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeInt(CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        out.writeBoolean(true);
        out.writeInt(0);
        out.writeBoolean(false);
        kb.saveState(out);
        mouse.saveState(out);

        ps2.setDevice(1, Ps2DeviceKind.KEYBOARD);   // whatever was there is replaced
        DeviceStateTest.load(ps2, bytes.toByteArray());
        assertEquals(Ps2DeviceKind.KEYBOARD, ps2.device(0));
        assertEquals(Ps2DeviceKind.MOUSE, ps2.device(1));
        assertEquals(CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE, ps2.ctrl(0));
        assertArrayEquals(b(0xAA, 0x23), drain(KEYBOARD_PORT));
        assertArrayEquals(b(0xAA, 0x00), drain(MOUSE_PORT));
        assertFalse(ps2.keyboard(1).present());
        assertEquals(0, ps2.keyboard(1).queued());
        assertFalse(ps2.mouse(0).present());
    }
}
