package com.nectariich.create_flippies.content.speaker;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The pins on the back of an alarm speaker, stated once for the block and the
 * mounted control alike — see {@code DisplayPins} for why the two must share one
 * table.
 *
 * <h2>Eighteen terminals, six nodes</h2>
 *
 * Each channel has a column of four pins, and the four are one node: they are there
 * so several outputs can be wired to one channel without stacking wires on a single
 * pin, which is the same thing a terminal block's bridged clamps are for. Each pin
 * is joined to its channel's bus by {@link #CONTACT_OHMS}, the terminal blocks'
 * clamp resistance. Then {@code VCC} and {@code GND}.
 *
 * Terminal order is channel by channel, each column bottom to top, then the supply
 * pair — {@code 4 * channel + pin}, then {@link #TERMINAL_VCC} and
 * {@link #TERMINAL_GND}. A host streams terminals positionally, so this order is
 * frozen for as long as speakers exist on panels.
 *
 * <h2>Boxes</h2>
 *
 * Every pin stands off the barrel's {@code -z} face, a quarter of a pixel from its
 * neighbours both ways. Each box is reached outward along {@code -z} and padded
 * across and up by under half of that gap, so no two boxes meet: a click in an
 * overlap is a wire on whichever pin Power Grid tested first, which across
 * {@code VCC} and {@code GND} is a short.
 */
public final class SpeakerPins {

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alarm_speaker.terminal.";

    public static final int CHANNELS = 4;
    public static final int PINS_PER_CHANNEL = 4;

    public static final int TERMINAL_VCC = CHANNELS * PINS_PER_CHANNEL;
    public static final int TERMINAL_GND = TERMINAL_VCC + 1;
    public static final int TERMINALS = TERMINAL_GND + 1;

    /** One pin to its channel's bus: a milliohm, the terminal blocks' clamp. */
    public static final float CONTACT_OHMS = 1e-3f;

    // These mirror the authored export and have to be updated with it;
    // `python tools/gen_speaker.py` prints what it finds.

    /** The horn, in the speaker's own cell in front of the panel. */
    public static final double[] FRONT = { 4, 0, 4, 12, 3, 12 };

    /** The barrel through the panel. */
    public static final double[] BARREL = { 6, -10, 6, 10, 0, 10 };

    /** Each channel's column of pins, left to right as the export names them. */
    private static final double[] CHANNEL_X = { 9.25, 8.5, 7.75, 7.0 };

    /** The four rows of a channel column, bottom to top. */
    private static final double[] ROW_Y = { -9.5, -8.75, -8.0, -7.25 };

    private static final double PIN = 0.5;
    private static final double PIN_Z = 5.75;
    private static final double FACE_Z = 6.0;

    private static final double[] VCC = { 6.25, -9.5, PIN_Z, 6.75, -8.25, FACE_Z };
    private static final double[] GND = { 6.25, -8.0, PIN_Z, 6.75, -6.75, FACE_Z };

    /** How far a box reaches out past the drawn stub, in pixels: the displays' reach. */
    private static final double REACH = 0.375;

    /** Spread within the face, under half the quarter-pixel gap between pins. */
    private static final double PAD = 0.1;

    /**
     * The colours the channels are wired in. Four of their own, so a column cannot
     * be mistaken for its neighbour; the supply pair is the red and blue every other
     * pair in the mod uses.
     */
    private static final int[] CHANNEL_COLORS = { 0xFFD23B, 0x3BD26B, 0xB45CFF, 0xFF8A2B };

    private static final double[][] PINS = pins();
    private static final double[][] CONTACT_BOXES = grownBoxes();
    private static final double[][] CONTACT_ORIGINS = contactOrigins();
    private static final Component[] CONTACT_NAMES = contactNames();

    /** The whole device: horn, barrel and every grown pin. Nothing moves. */
    private static final ControlFootprint FOOTPRINT = ControlFootprint.of(FRONT, BARREL).plus(CONTACT_BOXES);

    private static final double[][] OUTLINE = outline();

    private SpeakerPins() {}

    public static int terminal(int channel, int pin) {
        return channel * PINS_PER_CHANNEL + pin;
    }

    public static ControlFootprint footprint() {
        return FOOTPRINT;
    }

    public static double[][] outlineBoxes() {
        return OUTLINE;
    }

    /** Built afresh on each call, for the reason {@code DisplayPins.terminals} gives. */
    public static TerminalBoundingBox[] terminals(ControlMount mount) {
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[TERMINALS];
        for (int i = 0; i < TERMINALS; i++) {
            double[] box = mount.box(CONTACT_BOXES[i]);
            double[] origin = CONTACT_ORIGINS[i];
            double[] tip = mount.point(origin[0], origin[1], origin[2]);
            terminals[i] = new TerminalBoundingBox(CONTACT_NAMES[i], box[0], box[1], box[2], box[3], box[4], box[5])
                    .withColor(contactColor(i))
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    public static Component contactName(int terminal) {
        return CONTACT_NAMES[terminal];
    }

    public static int contactColor(int terminal) {
        if (terminal == TERMINAL_VCC)
            return IDecoratedTerminal.RED;
        if (terminal == TERMINAL_GND)
            return IDecoratedTerminal.BLUE;
        return CHANNEL_COLORS[terminal / PINS_PER_CHANNEL];
    }

    public static double[] contactBox(int terminal) {
        return CONTACT_BOXES[terminal];
    }

    public static double[] contactOrigin(int terminal) {
        return CONTACT_ORIGINS[terminal];
    }

    private static double[][] pins() {
        double[][] pins = new double[TERMINALS][];
        for (int channel = 0; channel < CHANNELS; channel++)
            for (int pin = 0; pin < PINS_PER_CHANNEL; pin++) {
                double x = CHANNEL_X[channel];
                double y = ROW_Y[pin];
                pins[terminal(channel, pin)] = new double[] { x, y, PIN_Z, x + PIN, y + PIN, FACE_Z };
            }
        pins[TERMINAL_VCC] = VCC;
        pins[TERMINAL_GND] = GND;
        return pins;
    }

    private static double[][] grownBoxes() {
        double[][] boxes = new double[PINS.length][];
        for (int i = 0; i < PINS.length; i++) {
            double[] grown = PINS[i].clone();
            // Outward along the one axis the pins stand off, never back into the barrel
            grown[2] -= REACH;
            grown[0] -= PAD;
            grown[3] += PAD;
            grown[1] -= PAD;
            grown[4] += PAD;
            boxes[i] = grown;
        }
        return boxes;
    }

    private static double[][] contactOrigins() {
        double[][] origins = new double[PINS.length][];
        for (int i = 0; i < PINS.length; i++) {
            double[] pin = PINS[i];
            // The stub's outward face, not the grown box's middle, which is in open air
            origins[i] = new double[] { (pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, pin[2] };
        }
        return origins;
    }

    private static Component[] contactNames() {
        Component[] names = new Component[TERMINALS];
        for (int channel = 0; channel < CHANNELS; channel++)
            for (int pin = 0; pin < PINS_PER_CHANNEL; pin++)
                names[terminal(channel, pin)] = Component.translatable(TERMINAL_PREFIX + "channel", channel + 1,
                        pin + 1);
        names[TERMINAL_VCC] = Component.translatable(TERMINAL_PREFIX + "vcc");
        names[TERMINAL_GND] = Component.translatable(TERMINAL_PREFIX + "gnd");
        return names;
    }

    private static double[][] outline() {
        double[][] boxes = new double[2 + CONTACT_BOXES.length][];
        boxes[0] = FRONT;
        boxes[1] = BARREL;
        System.arraycopy(CONTACT_BOXES, 0, boxes, 2, CONTACT_BOXES.length);
        return boxes;
    }
}
