package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Ps2Controller.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The PS/2 controller's keyboard port and the set 2 keyboard (spec 3.6), at 10 MHz. */
class Ps2KeyboardTest {

    private DeviceRig rig;
    private Ps2Controller ps2;
    private Ps2Keyboard kb;
    private final DeviceRig.Line kbLine = new DeviceRig.Line();
    private final DeviceRig.Line mouseLine = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        ps2 = new Ps2Controller(rig.clock, 10_000_000, kbLine, mouseLine);
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        ps2.setDevice(1, Ps2DeviceKind.MOUSE);
        ps2.reset();
        kb = ps2.keyboard();
        rig.map(ps2, 0x1000);
    }

    /** Pops every queued keyboard byte through DATA. */
    private byte[] drain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((rig.r32(KEYBOARD_PORT + STATUS) & STATUS_RX_READY) != 0)
            out.write(rig.r32(KEYBOARD_PORT + DATA));
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++)
            r[i] = (byte) v[i];
        return r;
    }

    private void enablePort() {
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE);
        drain();
    }

    private void command(int... bytes) {
        for (int x : bytes)
            rig.w32(KEYBOARD_PORT + DATA, x);
    }

    @Test
    void powerOnQueuesSelfTestAlone() {
        assertEquals(STATUS_RX_READY | STATUS_PRESENT | 1 << 8, rig.r32(KEYBOARD_PORT + STATUS));
        assertArrayEquals(b(0xAA), drain());
        assertEquals(STATUS_PRESENT, rig.r32(KEYBOARD_PORT + STATUS));
        assertEquals(0, rig.r32(KEYBOARD_PORT + DATA), "empty reads 0");
    }

    @Test
    void resetCommandRepliesAckThenSelfTest() {
        drain();
        command(0xFF);
        assertArrayEquals(b(0xFA, 0xAA), drain());
    }

    @Test
    void hostInputIsDiscardedWhileThePortIsDisabled() {
        drain();
        kb.keyDown(Ps2Key.A);
        assertEquals(0, kb.queued());
        enablePort();
        kb.keyDown(Ps2Key.A);
        assertArrayEquals(b(0x1C), drain());
    }

    @Test
    void makeAndBreakSequences() {
        enablePort();
        assertKey(Ps2Key.A, b(0x1C), b(0xF0, 0x1C));
        assertKey(Ps2Key.ENTER, b(0x5A), b(0xF0, 0x5A));
        assertKey(Ps2Key.ESCAPE, b(0x76), b(0xF0, 0x76));
        assertKey(Ps2Key.SPACE, b(0x29), b(0xF0, 0x29));
        assertKey(Ps2Key.LEFT_SHIFT, b(0x12), b(0xF0, 0x12));
        assertKey(Ps2Key.RIGHT_CTRL, b(0xE0, 0x14), b(0xE0, 0xF0, 0x14));
        assertKey(Ps2Key.RIGHT, b(0xE0, 0x74), b(0xE0, 0xF0, 0x74));
        assertKey(Ps2Key.PRINT_SCREEN, b(0xE0, 0x12, 0xE0, 0x7C), b(0xE0, 0xF0, 0x7C, 0xE0, 0xF0, 0x12));
        assertKey(Ps2Key.PAUSE, b(0xE1, 0x14, 0x77, 0xE1, 0xF0, 0x14, 0xF0, 0x77), b());
    }

    private void assertKey(Ps2Key key, byte[] make, byte[] brk) {
        kb.keyDown(key);
        assertArrayEquals(make, drain(), key + " make");
        kb.keyUp(key);
        assertArrayEquals(brk, drain(), key + " break");
    }

    @Test
    void theLayoutHas104DistinctKeys() {
        assertEquals(104, Ps2Key.values().length);
        Set<String> sequences = new HashSet<>();
        for (Ps2Key k : EnumSet.allOf(Ps2Key.class))
            assertTrue(sequences.add(java.util.Arrays.toString(k.make())), "duplicate make code for " + k);
    }

    @Test
    void commandReplies() {
        drain();
        command(0xED);
        assertArrayEquals(b(0xFA), drain());
        command(0x05);
        assertArrayEquals(b(0xFA), drain());
        assertEquals(Ps2Keyboard.LED_SCROLL | Ps2Keyboard.LED_CAPS, kb.leds());

        command(0xEE);
        assertArrayEquals(b(0xEE), drain());
        command(0xF0, 0x00);
        assertArrayEquals(b(0xFA, 0xFA, 0x02), drain(), "scancode set query");
        command(0xF0, 0x02);
        assertArrayEquals(b(0xFA, 0xFA), drain());
        command(0xF0, 0x01);
        assertArrayEquals(b(0xFA, 0xFE), drain(), "set 1 is not supported");
        command(0xF2);
        assertArrayEquals(b(0xFA, 0xAB, 0x83), drain());
        command(0xF4);
        assertArrayEquals(b(0xFA), drain());
        command(0x42);
        assertArrayEquals(b(0xFE), drain(), "unknown command");
        command(0xEE, 0xFE);
        assertArrayEquals(b(0xEE, 0xEE), drain(), "resend repeats the last byte");
    }

    @Test
    void disableStopsScanningUntilEnable() {
        enablePort();
        command(0xF5);
        drain();
        kb.keyDown(Ps2Key.A);
        assertEquals(0, kb.queued());
        command(0xF4);
        drain();
        kb.keyDown(Ps2Key.A);
        assertArrayEquals(b(0x1C), drain());
    }

    @Test
    void fullQueueEndsWithTheOverrunCode() {
        enablePort();
        byte[] many = new byte[40];
        for (int i = 0; i < many.length; i++)
            many[i] = (byte) (0x10 + i);
        kb.inject(many);
        int status = rig.r32(KEYBOARD_PORT + STATUS);
        assertEquals(32, status >>> 8);
        assertTrue((status & STATUS_OVERRUN) != 0);
        byte[] got = drain();
        assertEquals(32, got.length);
        for (int i = 0; i < 31; i++)
            assertEquals(0x10 + i, got[i]);
        assertEquals(0x00, got[31], "set 2 overrun code in the last slot");
        rig.w32(KEYBOARD_PORT + STATUS, STATUS_OVERRUN);
        assertEquals(0, rig.r32(KEYBOARD_PORT + STATUS) & STATUS_OVERRUN, "W1C");
        kb.inject((byte) 0x55);
        assertArrayEquals(b(0x55), drain(), "accepted again once the code was read");
    }

    @Test
    void typematicRepeatAfterTheDelay() {
        enablePort();
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        kb.keyDown(Ps2Key.A);
        assertEquals(5_000_000, ps2.nextEventCycle(), "500 ms at 10 MHz");
        assertArrayEquals(b(0x1C), drain());
        rig.at(4_999_999);
        assertArrayEquals(b(), drain());
        rig.at(5_000_000);
        assertArrayEquals(b(0x1C), drain());
        long period = 917_400;
        assertEquals(5_000_000 + period, ps2.nextEventCycle(), "10.9 per second");
        rig.at(5_000_000 + period - 1);
        assertArrayEquals(b(), drain());
        rig.at(5_000_000 + period);
        assertArrayEquals(b(0x1C), drain());
        kb.keyUp(Ps2Key.A);
        assertArrayEquals(b(0xF0, 0x1C), drain());
        rig.at(20_000_000);
        assertArrayEquals(b(), drain(), "released: no more repeats");
        assertEquals(Long.MAX_VALUE, ps2.nextEventCycle());
    }

    @Test
    void typematicRateCommand() {
        enablePort();
        command(0xF3, 0x00);
        assertArrayEquals(b(0xFA, 0xFA), drain());
        assertEquals(250_000, kb.repeatDelayMicros());
        assertEquals(33_360, kb.repeatPeriodMicros(), "30 per second");
        command(0xF3, 0x7F);
        drain();
        assertEquals(1_000_000, kb.repeatDelayMicros());
        assertEquals(15 * 8 * 4_170, kb.repeatPeriodMicros(), "2 per second");
    }

    @Test
    void irqLineFollowsRxReadyAndCtrl() {
        assertFalse(kbLine.level, "power-on byte queued but IRQ disabled");
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE | CTRL_IRQ_ENABLE);
        assertTrue(kbLine.level);
        assertFalse(mouseLine.level);
        drain();
        assertFalse(kbLine.level);
        kb.keyDown(Ps2Key.Q);
        assertTrue(kbLine.level, "a host key press raises it between runs");
        rig.w32(KEYBOARD_PORT + CTRL, CTRL_PORT_ENABLE);
        assertFalse(kbLine.level);
        assertEquals(CTRL_PORT_ENABLE, rig.r32(KEYBOARD_PORT + CTRL));
    }

    @Test
    void absentKeyboard() {
        kb.setPresent(false);
        assertEquals(0, rig.r32(KEYBOARD_PORT + STATUS));
        command(0xFF);
        assertEquals(0, kb.queued(), "commands to an absent device are lost");
    }
}
