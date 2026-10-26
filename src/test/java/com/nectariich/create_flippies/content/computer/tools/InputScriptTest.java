package com.nectariich.create_flippies.content.computer.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.Ps2Keyboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The headless runner's input scripts ({@link InputScript}): parsing, typing and the key queue. */
class InputScriptTest {

    private static final long S = 1_000_000_000L;

    @Test
    void parsesEveryCommandWithAbsoluteAndRelativeTimes() {
        InputScript script = InputScript.parse("""
                # a comment, then a blank line

                2.5 type ls -l\\n
                +0.25 key enter
                3 down LEFT_CTRL
                3 up left_ctrl
                1 move 10 -5
                1 buttons 5
                4 screenshot shots/a.png 2
                4 screenshot b.png
                """, Path.of("/base"));
        List<InputScript.Event> e = script.events();
        assertEquals(8, e.size());
        // by time, then by line: move and buttons at 1 s come first
        assertEquals(InputScript.Kind.MOVE, e.get(0).kind());
        assertEquals(S, e.get(0).atNanos());
        assertEquals(10, e.get(0).dx());
        assertEquals(-5, e.get(0).dy());
        assertEquals(InputScript.Kind.BUTTONS, e.get(1).kind());
        assertEquals(5, e.get(1).dx());
        assertEquals(InputScript.Kind.KEYS, e.get(2).kind());
        assertEquals(2_500_000_000L, e.get(2).atNanos());
        assertEquals(3, e.get(2).line());
        assertEquals(InputScript.strokes("ls -l\n"), e.get(2).strokes());
        assertEquals(2_750_000_000L, e.get(3).atNanos());
        assertEquals(List.of(new InputScript.Stroke(Ps2Key.ENTER, true), new InputScript.Stroke(Ps2Key.ENTER, false)),
                e.get(3).strokes());
        assertEquals(List.of(new InputScript.Stroke(Ps2Key.LEFT_CTRL, true)), e.get(4).strokes());
        assertEquals(List.of(new InputScript.Stroke(Ps2Key.LEFT_CTRL, false)), e.get(5).strokes());
        assertEquals(Path.of("/base/shots/a.png"), e.get(6).file());
        assertEquals(2, e.get(6).display());
        assertEquals(Path.of("/base/b.png"), e.get(7).file());
        assertEquals(0, e.get(7).display());
    }

    @Test
    void typesUsLayoutWithShiftAndEscapes() {
        List<InputScript.Stroke> s = InputScript.strokes("aA!\t");
        assertEquals(List.of(
                new InputScript.Stroke(Ps2Key.A, true), new InputScript.Stroke(Ps2Key.A, false),
                new InputScript.Stroke(Ps2Key.LEFT_SHIFT, true), new InputScript.Stroke(Ps2Key.A, true),
                new InputScript.Stroke(Ps2Key.A, false), new InputScript.Stroke(Ps2Key.LEFT_SHIFT, false),
                new InputScript.Stroke(Ps2Key.LEFT_SHIFT, true), new InputScript.Stroke(Ps2Key.DIGIT_1, true),
                new InputScript.Stroke(Ps2Key.DIGIT_1, false), new InputScript.Stroke(Ps2Key.LEFT_SHIFT, false),
                new InputScript.Stroke(Ps2Key.TAB, true), new InputScript.Stroke(Ps2Key.TAB, false)), s);
        assertEquals("a\nb\\c\u001B\b\t", InputScript.unescape("a\\nb\\\\c\\e\\b\\t"));
        // every printable ASCII character has a key
        for (char c = ' '; c < 0x7F; c++)
            assertTrue(InputScript.strokes(String.valueOf(c)).size() >= 2, "no key for " + c);
        // the text after "type " is taken as it is, spaces included
        InputScript script = InputScript.parse("0 type  two  spaces ", null);
        assertEquals(InputScript.strokes(" two  spaces "), script.events().get(0).strokes());
    }

    @Test
    void errorsNameTheLine() {
        assertError("line 1: unknown command \"jump\"", "1 jump");
        assertError("line 2: the time must be seconds", "\nsoon key A");
        assertError("line 1: no key FOO", "1 key FOO");
        assertError("line 1: expected move DX DY", "1 move 3");
        assertError("line 1: buttons takes a mask from 0 to 7", "1 buttons 8");
        assertError("line 1: the display must be 0, 1 or 2", "1 screenshot a.png 3");
        assertError("line 1: no key types U+00E9", "1 type café");
        assertError("line 1: unknown escape \\q", "1 type \\q");
        assertError("line 1: type needs a text", "1 type");
        assertError("line 1: expected TIME COMMAND", "1");
        assertError("line 1: the time must be seconds", "-1 key A");
    }

    private static void assertError(String expected, String script) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InputScript.parse(script, null));
        assertTrue(e.getMessage().startsWith(expected), e.getMessage());
    }

    /**
     * A long text reaches the keyboard over several tick boundaries, in order and with no overrun:
     * at each boundary the player hands the keyboard only what fits in its 32-byte queue, which
     * the "guest" here drains between boundaries.
     */
    @Test
    void theKeyQueueFeedsOnlyWhatFitsAndKeepsTheOrder() {
        CycleClock clock = new CycleClock();
        Ps2Controller ps2 = new Ps2Controller(clock, 25_000_000, IrqSink.NONE, IrqSink.NONE);
        ps2.setDevice(0, Ps2DeviceKind.KEYBOARD);
        ps2.setDevice(1, Ps2DeviceKind.MOUSE);
        ps2.write(Ps2Controller.CTRL, 4, Ps2Controller.CTRL_PORT_ENABLE);
        drain(ps2, 0);   // the power-on 0xAA
        String text = "Hello, World! 0123456789 abcdefghijklmnopqrstuvwxyz\n";
        InputScript script = InputScript.parse("0.5 type " + text.replace("\n", "\\n") + "\n0.5 key ESCAPE", null);
        InputScript.Player player = new InputScript.Player(script, ps2.keyboard(0), ps2.mouse(1));

        List<Integer> received = new ArrayList<>();
        player.advance(0, (d, f) -> { });
        assertEquals(0, ps2.keyboard(0).queued(), "nothing before its time");
        int boundaries = 0;
        for (long t = S / 2; !player.done(); t += S / 20, boundaries++) {
            clock.advanceTo(t / 40);   // 25 MHz
            player.advance(t, (d, f) -> { });
            assertTrue(ps2.keyboard(0).queued() < Ps2Keyboard.CAPACITY, "the queue never fills");
            received.addAll(drain(ps2, 0));
        }
        assertTrue(boundaries > 5, "a long text takes several boundaries: " + boundaries);

        List<Integer> expected = new ArrayList<>();
        List<InputScript.Stroke> strokes = new ArrayList<>(InputScript.strokes(text));
        strokes.add(new InputScript.Stroke(Ps2Key.ESCAPE, true));
        strokes.add(new InputScript.Stroke(Ps2Key.ESCAPE, false));
        for (InputScript.Stroke s : strokes)
            for (byte b : s.bytes())
                expected.add(b & 0xFF);
        assertEquals(expected, received);
        assertEquals(0, ps2.read(Ps2Controller.STATUS, 4) & Ps2Controller.STATUS_OVERRUN);
    }

    private static List<Integer> drain(Ps2Controller ps2, int port) {
        List<Integer> out = new ArrayList<>();
        while ((ps2.read(port * 0x10 + Ps2Controller.STATUS, 4) & Ps2Controller.STATUS_RX_READY) != 0)
            out.add(ps2.read(port * 0x10 + Ps2Controller.DATA, 4));
        return out;
    }
}
