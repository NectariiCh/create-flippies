package com.nectariich.create_flippies.content.computer.tools;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.computer.device.Ps2Keyboard;
import com.nectariich.create_flippies.content.computer.device.Ps2Mouse;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A script of PS/2 input and screenshots at emulated times, for the headless runner's
 * {@code --input}. One event per line, {@code TIME COMMAND ARGUMENTS}; blank lines and lines
 * starting with '#' are skipped.
 *
 * <pre>
 * 2.5 type echo hello\n     type the text on the keyboard (US layout; escapes \n \t \b \e \\)
 * +0.5 key ENTER            press and release a key, named as in Ps2Key (case does not matter)
 * 3 down LEFT_CTRL          press a key and keep it down
 * 3 up LEFT_CTRL            release it
 * 4 move 10 -5              move the mouse: x to the right, y up
 * 4 buttons 1               mouse buttons held from now: 1 left, 2 right, 4 middle (0 none)
 * 6 screenshot shot.png 0   save a display (default 0) as --screenshot does
 * </pre>
 *
 * TIME is in emulated seconds from power-on, or {@code +S} after the previous line's time. Lines
 * may come in any order of time; lines at the same time run in file order. An event runs at the
 * first tick boundary at or after its time (ticks are 50 ms), as the game delivers input between
 * ticks. The text after {@code type} is taken as it is, spaces included, up to the end of the line.
 *
 * The keyboard is on PS/2 port 0 and the mouse on port 1, as the runner plugs them. Key presses
 * and releases go through one queue that hands the keyboard only what fits in its 32-byte buffer
 * at each tick boundary, so a long text arrives over several ticks, in order, and nothing is lost
 * to an overrun; a key event behind it waits too. What reaches a port the guest has not enabled is
 * dropped, as a real keyboard's or mouse's input is. Mouse moves and buttons are not queued: the
 * mouse adds them up itself and sends packets at its sample rate. Screenshot file names are
 * relative to the script's folder.
 */
final class InputScript {

    enum Kind { KEYS, MOVE, BUTTONS, SCREENSHOT }

    /** One key going down or up. */
    record Stroke(Ps2Key key, boolean down) {
        byte[] bytes() {
            return down ? key.make() : key.brk();
        }
    }

    /**
     * One line of the script. {@code strokes} for KEYS; {@code dx}, {@code dy} for MOVE;
     * {@code dx} the button mask for BUTTONS; {@code file} and {@code display} for SCREENSHOT.
     */
    record Event(long atNanos, int line, Kind kind, List<Stroke> strokes, int dx, int dy, Path file,
                 int display) {}

    private final List<Event> events;

    private InputScript(List<Event> events) {
        this.events = Collections.unmodifiableList(events);
    }

    /** The events in the order they run: by time, then by line. */
    List<Event> events() {
        return events;
    }

    /** Throws IllegalArgumentException naming the file and line on anything it cannot use. */
    static InputScript read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(text, file.toAbsolutePath().getParent());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ", " + e.getMessage(), e);
        }
    }

    /** Throws IllegalArgumentException naming the line on anything it cannot use. */
    static InputScript parse(String text, Path baseDir) {
        List<Event> events = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        long previous = 0;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
                continue;
            int lineNo = n + 1;
            try {
                String rest = line.stripLeading();
                int sp = rest.indexOf(' ');
                if (sp < 0)
                    throw new IllegalArgumentException("expected TIME COMMAND");
                long at = time(rest.substring(0, sp), previous);
                previous = at;
                rest = rest.substring(sp + 1).stripLeading();
                sp = rest.indexOf(' ');
                String command = (sp < 0 ? rest : rest.substring(0, sp)).toLowerCase(Locale.ROOT);
                String args = sp < 0 ? "" : rest.substring(sp + 1);
                String[] words = args.isBlank() ? new String[0] : args.strip().split("\\s+");
                events.add(switch (command) {
                    case "type" -> {
                        if (args.isEmpty())
                            throw new IllegalArgumentException("type needs a text");
                        yield new Event(at, lineNo, Kind.KEYS, strokes(unescape(args)), 0, 0, null, 0);
                    }
                    case "key", "down", "up" -> {
                        arguments(words, 1, 1, command + " KEY");
                        Ps2Key key = key(words[0]);
                        List<Stroke> strokes = command.equals("up") ? List.of(new Stroke(key, false))
                                : command.equals("down") ? List.of(new Stroke(key, true))
                                : List.of(new Stroke(key, true), new Stroke(key, false));
                        yield new Event(at, lineNo, Kind.KEYS, strokes, 0, 0, null, 0);
                    }
                    case "move" -> {
                        arguments(words, 2, 2, "move DX DY");
                        yield new Event(at, lineNo, Kind.MOVE, List.of(), integer(words[0]), integer(words[1]),
                                null, 0);
                    }
                    case "buttons" -> {
                        arguments(words, 1, 1, "buttons MASK");
                        int mask = integer(words[0]);
                        if (mask < 0 || mask > 7)
                            throw new IllegalArgumentException("buttons takes a mask from 0 to 7: " + words[0]);
                        yield new Event(at, lineNo, Kind.BUTTONS, List.of(), mask, 0, null, 0);
                    }
                    case "screenshot" -> {
                        arguments(words, 1, 2, "screenshot FILE [DISPLAY]");
                        int display = words.length > 1 ? integer(words[1]) : 0;
                        if (display < 0 || display >= Machine.DISPLAYS)
                            throw new IllegalArgumentException("the display must be 0, 1 or 2: " + words[1]);
                        Path file = baseDir == null ? Path.of(words[0]) : baseDir.resolve(words[0]);
                        yield new Event(at, lineNo, Kind.SCREENSHOT, List.of(), 0, 0, file, display);
                    }
                    default -> throw new IllegalArgumentException("unknown command \"" + command + "\"");
                });
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        // stable: lines at the same time keep their order
        events.sort((a, b) -> Long.compare(a.atNanos(), b.atNanos()));
        return new InputScript(events);
    }

    private static void arguments(String[] words, int min, int max, String usage) {
        if (words.length < min || words.length > max)
            throw new IllegalArgumentException("expected " + usage);
    }

    /** Seconds with up to nine decimals, or +S after {@code previous}; in nanoseconds. */
    private static long time(String word, long previous) {
        boolean relative = word.startsWith("+");
        try {
            BigDecimal s = new BigDecimal(relative ? word.substring(1) : word);
            if (s.signum() >= 0 && s.compareTo(BigDecimal.valueOf(1_000_000_000)) < 0)
                return (relative ? previous : 0)
                        + s.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("the time must be seconds, as 2.5 or +0.25: " + word);
    }

    private static int integer(String word) {
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a whole number: " + word);
        }
    }

    private static Ps2Key key(String name) {
        try {
            return Ps2Key.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no key " + name + " (names as in Ps2Key, such as ENTER or LEFT_CTRL)");
        }
    }

    /** {@code \n} Enter, {@code \t} Tab, {@code \b} Backspace, {@code \e} Escape, {@code \\} a backslash. */
    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i == text.length())
                throw new IllegalArgumentException("a backslash at the end of the text");
            out.append(switch (text.charAt(i)) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'e' -> '\u001B';
                case '\\' -> '\\';
                default -> throw new IllegalArgumentException("unknown escape \\" + text.charAt(i));
            });
        }
        return out.toString();
    }

    private static final String PLAIN = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./";
    private static final String SHIFTED = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?";
    private static final Ps2Key[] KEYS = {
            Ps2Key.BACKTICK, Ps2Key.DIGIT_1, Ps2Key.DIGIT_2, Ps2Key.DIGIT_3, Ps2Key.DIGIT_4, Ps2Key.DIGIT_5,
            Ps2Key.DIGIT_6, Ps2Key.DIGIT_7, Ps2Key.DIGIT_8, Ps2Key.DIGIT_9, Ps2Key.DIGIT_0, Ps2Key.MINUS,
            Ps2Key.EQUALS, Ps2Key.Q, Ps2Key.W, Ps2Key.E, Ps2Key.R, Ps2Key.T, Ps2Key.Y, Ps2Key.U, Ps2Key.I,
            Ps2Key.O, Ps2Key.P, Ps2Key.LEFT_BRACKET, Ps2Key.RIGHT_BRACKET, Ps2Key.BACKSLASH, Ps2Key.A, Ps2Key.S,
            Ps2Key.D, Ps2Key.F, Ps2Key.G, Ps2Key.H, Ps2Key.J, Ps2Key.K, Ps2Key.L, Ps2Key.SEMICOLON,
            Ps2Key.APOSTROPHE, Ps2Key.Z, Ps2Key.X, Ps2Key.C, Ps2Key.V, Ps2Key.B, Ps2Key.N, Ps2Key.M, Ps2Key.COMMA,
            Ps2Key.PERIOD, Ps2Key.SLASH,
    };

    /** The presses and releases that type {@code text} on a US keyboard; Shift around shifted characters. */
    static List<Stroke> strokes(String text) {
        List<Stroke> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Ps2Key key = switch (c) {
                case ' ' -> Ps2Key.SPACE;
                case '\n' -> Ps2Key.ENTER;
                case '\t' -> Ps2Key.TAB;
                case '\b' -> Ps2Key.BACKSPACE;
                case '\u001B' -> Ps2Key.ESCAPE;
                default -> null;
            };
            boolean shift = false;
            if (key == null) {
                int plain = PLAIN.indexOf(c), shifted = SHIFTED.indexOf(c);
                if (plain >= 0) {
                    key = KEYS[plain];
                } else if (shifted >= 0) {
                    key = KEYS[shifted];
                    shift = true;
                } else {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "no key types U+%04X on a US keyboard", (int) c));
                }
            }
            if (shift)
                out.add(new Stroke(Ps2Key.LEFT_SHIFT, true));
            out.add(new Stroke(key, true));
            out.add(new Stroke(key, false));
            if (shift)
                out.add(new Stroke(Ps2Key.LEFT_SHIFT, false));
        }
        return out;
    }

    /**
     * Plays a script into a machine: {@link #advance} at every tick boundary runs the events that
     * are due and feeds the keyboard from the key queue.
     */
    static final class Player {

        /** What a screenshot event asks for; the runner saves it. */
        interface Screenshots {
            void save(int display, Path file);
        }

        private final List<Event> events;
        private final Ps2Keyboard keyboard;
        private final Ps2Mouse mouse;
        private final ArrayDeque<Stroke> keys = new ArrayDeque<>();
        private int next;

        Player(InputScript script, Ps2Keyboard keyboard, Ps2Mouse mouse) {
            this.events = script.events();
            this.keyboard = keyboard;
            this.mouse = mouse;
        }

        /** Runs every event at or before {@code nowNanos} of emulated time, then feeds the keyboard. */
        void advance(long nowNanos, Screenshots screenshots) {
            while (next < events.size() && events.get(next).atNanos() <= nowNanos) {
                Event e = events.get(next++);
                switch (e.kind()) {
                    case KEYS -> keys.addAll(e.strokes());
                    case MOVE -> mouse.move(e.dx(), e.dy());
                    case BUTTONS -> mouse.buttons(e.dx());
                    case SCREENSHOT -> screenshots.save(e.display(), e.file());
                }
            }
            feedKeys();
        }

        /** Hands the keyboard the queued strokes that fit, leaving its last slot for the overrun code. */
        private void feedKeys() {
            while (!keys.isEmpty()) {
                Stroke s = keys.peek();
                if (keyboard.queued() + s.bytes().length > Ps2Keyboard.CAPACITY - 1)
                    return;
                keys.poll();
                if (s.down())
                    keyboard.keyDown(s.key());
                else
                    keyboard.keyUp(s.key());
            }
        }

        /** Whether every event has run and every key stroke has reached the keyboard. */
        boolean done() {
            return next == events.size() && keys.isEmpty();
        }
    }
}
