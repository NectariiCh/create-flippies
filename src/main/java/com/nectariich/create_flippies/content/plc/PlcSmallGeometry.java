package com.nectariich.create_flippies.content.plc;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The small PLC in its two mountings as boxes: what each occupies, in the
 * {@link SurfaceMount} frame their models are authored in, and the twenty
 * terminals along its ledges.
 *
 * There is one PLC in two mountings. The body is the same device either way — a
 * base with a raised cover on it and two rows of ten terminals along the ledges
 * the cover leaves either side — and the mounting is the whole of the difference:
 * the DIN one clips to a rail and so starts at the rail's lips, the panel one is
 * screwed to the plate and so starts at the plate, {@link #PANEL_DROP} lower, with
 * two flanges to put the screws through. Both are hosted controls of the same
 * {@link PlcSmallControl}; {@link PlcSmallControlType} has a kind for each.
 *
 * Both are eight pixels along the mounting axis, which is eight rail modules, and
 * both are authored centred on the cell — {@code CircuitBreakerModel.shiftX(8)} is
 * 4, so the DIN one is already drawn where an eight module control sits before the
 * mount displaces it along the rail.
 *
 * <h2>Terminals</h2>
 *
 * Both mountings have the same twenty pins in the same order; the panel one's are
 * the DIN one's moved down by {@link #PANEL_DROP}, which is all that separates the
 * two drawings above the rail clips. They are numbered in the order the export lists them,
 * which is the order {@code python tools/gen_plc.py} prints, and that order is
 * frozen: Power Grid numbers a control's terminals positionally and a host streams
 * them as one flat run, so swapping two of them would move every wire already
 * landed on a PLC to the neighbouring pin.
 *
 * <pre>
 *   0..7    pin_a_00..07   DI0..DI7   inputs, PA0..PA7
 *   8       VCC            +24 V supply
 *   9       GND            0 V, the reference for every input and output
 *   10..17  pin_b_00..07   DO0..DO7   high-side outputs, PB0..PB7
 *   18      STLED          the STATUS output, PA11 (LED_BUILTIN)
 *   19      RESET          24 V input holding the MCU in reset
 * </pre>
 *
 * The {@code pin_a} row is the inputs and the {@code pin_b} row the outputs, as the
 * user confirmed against the design the model was drawn from.
 *
 * The pins are half a pixel square and stand a quarter pixel proud, a quarter pixel
 * apart, and Power Grid resolves a terminal from the exact spot clicked, so the drawn
 * pin is not a target anyone can hit. Each box is grown outward — up, away from the
 * ledge — by {@link #REACH}, and padded across by {@link #PAD}, which is under half
 * the gap between neighbours so that no two boxes overlap: a click in an overlap
 * lands on whichever of the two Power Grid tested first, and on VCC beside GND that
 * would be a short.
 *
 * That holds only because the boxes are handed to Power Grid with an explicit
 * {@link #CLICK_EXPAND} of nothing. Its six-coordinate {@code TerminalBoundingBox}
 * constructor quietly grows every box by a further tenth of a pixel on every side
 * (it delegates to the seven-coordinate one with {@code expand = 0.1}, and
 * {@code check()} tests the grown bounds), which on top of {@link #PAD} made each
 * neighbouring pair overlap by 0.15 px — VCC and GND included. {@link #clickBox}
 * is what is actually tested, and {@code PlcSmallGeometryTest} holds it apart.
 */
public final class PlcSmallGeometry {

    /** Rail modules one of these occupies; also its width in pixels. */
    public static final int MODULES = 8;

    private static final double MIN_X = 4;
    private static final double MAX_X = 12;

    /** The lengthwise span of the terminal rows, and how far they stand proud. */
    private static final double PINS_MIN_X = 4.375;
    private static final double PINS_MAX_X = 11.625;
    private static final double PIN_RISE = 0.25;

    // -------------------------------------------------------------- DIN ---
    /** The rail's lips, which is what a DIN device stands on. */
    public static final double DIN_FLOOR = 0.75;
    private static final double DIN_BASE_TOP = 2.5;
    private static final double DIN_TOP = 3.5;

    // ------------------------------------------------------- terminals ---
    // These mirror the authored export and have to be updated with it;
    // `python tools/gen_plc.py` prints what it finds.

    public static final int TERMINAL_DI0 = 0;
    public static final int TERMINAL_VCC = 8;
    public static final int TERMINAL_GND = 9;
    public static final int TERMINAL_DO0 = 10;
    public static final int TERMINAL_STLED = 18;
    public static final int TERMINAL_RESET = 19;
    public static final int TERMINALS = 20;

    /** Pins per row, and the distance from one pin to the next along it. */
    private static final int PINS_PER_ROW = 10;
    private static final double PIN_PITCH = 0.75;
    private static final double PIN_SIZE = 0.5;

    /**
     * The two rows across the rail: the inputs' near the high-z ledge, the outputs'
     * near the low. The export draws them the other way round, but it is drawn upside
     * down, and {@code tools/gen_plc.py} turns it half a turn about the vertical; so
     * the rows swap ledges and each runs from high x to low, as {@link #pin} counts.
     */
    private static final double ROW_A_MIN_Z = 10.25;
    private static final double ROW_B_MIN_Z = 5.25;

    /** How far a pin's box reaches above the pin: the displays' and indicators' reach. */
    private static final double REACH = 0.375;

    /**
     * How far a box spreads past its pin across the row, each side. The pins are a
     * quarter pixel apart, so this leaves a twentieth between neighbouring boxes.
     * Along the ledge it is the same figure, which keeps the box clear of the raised
     * cover a quarter pixel inboard.
     */
    private static final double PAD = 0.1;

    /**
     * What Power Grid adds to each box, per side, on top of {@link #PAD}: nothing.
     * See the class comment for what its default of 0.1 px did here.
     */
    static final double CLICK_EXPAND = 0;

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".plc.terminal.";

    /**
     * Colours for the two kinds of signal pin. Power Grid only names red, blue, grey
     * and green, and draws whatever RGB it is handed; inputs take its green, and the
     * outputs and the reset line take colours of their own so a player can tell the
     * three apart along a row of identical copper.
     */
    private static final int OUTPUT_COLOR = 0xFFAA00;
    private static final int RESET_COLOR = 0xFFFFFF;

    /** Every pin as it can be picked, in terminal order. */
    private static final double[][] CONTACT_BOXES = contactBoxes();

    /** Where a wire lands on each: the middle of the pin's top face, as drawn. */
    private static final double[][] CONTACT_ORIGINS = contactOrigins();

    /**
     * The two hooks under the base that grip the rail's lips. They stand below the
     * lips, outside them, and are part of the device's bulk like everything else.
     */
    private static final double[][] DIN_CLIPS = {
            { MIN_X, 0, 5, MAX_X, DIN_FLOOR, 6.5 },
            { MIN_X, 0, 9.5, MAX_X, DIN_FLOOR, 11 },
    };

    /**
     * What an eight module PLC occupies on a rail, following the step of the case
     * rather than hulling it, with the grown terminal boxes added so that a wire
     * target can never be built into.
     *
     * The host builds both its outline and its overlap test from these, so the two
     * cannot disagree: the notch either side of the raised cover is free, and it is
     * free to look free and to be built in. A single hull would have been cheaper
     * by three boxes and wrong by the same three.
     */
    public static final double[][] DIN_BOXES = {
            { MIN_X, DIN_FLOOR, 5, MAX_X, DIN_BASE_TOP, 11 },
            { MIN_X, DIN_BASE_TOP, 6, MAX_X, DIN_TOP, 10 },
            // The onboard button and indicator LEDs on the cover, as drawn after
            // gen_plc.py's half turn; the button stands 0.2 px proud of it
            { 4.25, DIN_TOP, 6.25, 5.5, DIN_TOP + 0.2, 7.75 },
            { PINS_MIN_X, DIN_BASE_TOP, 5.25, PINS_MAX_X, DIN_BASE_TOP + PIN_RISE, 5.75 },
            { PINS_MIN_X, DIN_BASE_TOP, 10.25, PINS_MAX_X, DIN_BASE_TOP + PIN_RISE, 10.75 },
    };

    public static final ControlFootprint DIN_FOOTPRINT = ControlFootprint.of(DIN_BOXES)
            .plus(DIN_CLIPS)
            .plus(CONTACT_BOXES);

    // ------------------------------------------------------------ panel ---
    /**
     * How much lower the panel PLC stands than the DIN one: the height of the
     * rail's lips, which is what the DIN one stands on and the panel one does not.
     * {@code tools/gen_plc.py} lowers the drawing by the same figure.
     */
    public static final double PANEL_DROP = DIN_FLOOR;

    private static final double PANEL_BASE_TOP = DIN_BASE_TOP - PANEL_DROP;
    private static final double PANEL_TOP = DIN_TOP - PANEL_DROP;
    private static final double EAR_TOP = 0.25;

    /**
     * The panel PLC's body: the DIN body {@link #PANEL_DROP} lower, without the
     * rail clips, with the two screw ears either end. It stands entirely inside
     * its own cell: nothing passes through the plate, so unlike the lamps and the
     * switches there is no protruding hitbox to give it.
     */
    public static final double[][] PANEL_BOXES = {
            { 3, 0, 6.5, 4, EAR_TOP, 9.5 },
            { 12, 0, 6.5, 13, EAR_TOP, 9.5 },
            { MIN_X, 0, 5, MAX_X, PANEL_BASE_TOP, 11 },
            { MIN_X, PANEL_BASE_TOP, 6, MAX_X, PANEL_TOP, 10 },
            { 4.25, PANEL_TOP, 6.25, 5.5, PANEL_TOP + 0.2, 7.75 },
            { PINS_MIN_X, PANEL_BASE_TOP, 5.25, PINS_MAX_X, PANEL_BASE_TOP + PIN_RISE, 5.75 },
            { PINS_MIN_X, PANEL_BASE_TOP, 10.25, PINS_MAX_X, PANEL_BASE_TOP + PIN_RISE, 10.75 },
    };

    /** The DIN contacts {@link #PANEL_DROP} lower, in the same terminal order. */
    private static final double[][] PANEL_CONTACT_BOXES = lowered(CONTACT_BOXES, PANEL_DROP);

    /**
     * What a panel PLC occupies on a plate, built the way {@link #DIN_FOOTPRINT}
     * is: the stepped body, with the grown terminal boxes added so that a wire
     * target can never be built into.
     */
    public static final ControlFootprint PANEL_FOOTPRINT = ControlFootprint.of(PANEL_BOXES)
            .plus(PANEL_CONTACT_BOXES);

    private PlcSmallGeometry() {}

    /**
     * The contacts as a mounted PLC presents them, in terminal order.
     *
     * Built afresh on each call, never cached, for the reason
     * {@link com.nectariich.create_flippies.foundation.control.ControlType#terminals}
     * gives: a TerminalBoundingBox mutates itself when it is given an origin or a
     * colour, so a shared instance would be rewritten by the next PLC mounted the
     * other way round.
     */
    public static TerminalBoundingBox[] terminals(ControlMount mount) {
        return terminals(mount, 0);
    }

    /**
     * The contacts of a PLC standing {@code drop} pixels lower than a DIN one
     * ({@link #PANEL_DROP} for the panel mounting), in the same frozen order. Only
     * the height moves; which pin is which cannot, for the reason the class
     * comment gives.
     */
    public static TerminalBoundingBox[] terminals(ControlMount mount, double drop) {
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[TERMINALS];
        for (int i = 0; i < TERMINALS; i++) {
            double[] contact = CONTACT_BOXES[i].clone();
            contact[1] -= drop;
            contact[4] -= drop;
            double[] box = mount.box(contact);
            double[] origin = CONTACT_ORIGINS[i];
            double[] tip = mount.point(origin[0], origin[1] - drop, origin[2]);
            terminals[i] = new TerminalBoundingBox(contactName(i), box[0], box[1], box[2], box[3], box[4], box[5],
                    CLICK_EXPAND)
                    .withColor(contactColor(i))
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    /**
     * What a terminal is called where a wire is being aimed at it: the board signal
     * and, for the pins a sketch can address, the MCU pin behind it, so the name on
     * the copper is the name in the code.
     */
    public static Component contactName(int terminal) {
        if (terminal < TERMINAL_VCC)
            return Component.translatable(TERMINAL_PREFIX + "di", terminal, "PA" + terminal);
        if (terminal == TERMINAL_VCC)
            return Component.translatable(TERMINAL_PREFIX + "vcc");
        if (terminal == TERMINAL_GND)
            return Component.translatable(TERMINAL_PREFIX + "gnd");
        if (terminal < TERMINAL_STLED)
            return Component.translatable(TERMINAL_PREFIX + "do", terminal - TERMINAL_DO0,
                    "PB" + (terminal - TERMINAL_DO0));
        if (terminal == TERMINAL_STLED)
            return Component.translatable(TERMINAL_PREFIX + "stled");
        return Component.translatable(TERMINAL_PREFIX + "reset");
    }

    /** Supply red and blue as every polarised pair in the mod; see {@link #OUTPUT_COLOR}. */
    public static int contactColor(int terminal) {
        if (terminal < TERMINAL_VCC)
            return IDecoratedTerminal.GREEN;
        if (terminal == TERMINAL_VCC)
            return IDecoratedTerminal.RED;
        if (terminal == TERMINAL_GND)
            return IDecoratedTerminal.BLUE;
        if (terminal == TERMINAL_RESET)
            return RESET_COLOR;
        return OUTPUT_COLOR;
    }

    /** The grown box a click is resolved against, in authored pixels. */
    public static double[] contactBox(int terminal) {
        return CONTACT_BOXES[terminal].clone();
    }

    /**
     * The box a click on a terminal is actually tested against, in authored pixels:
     * {@link #contactBox} as Power Grid widens it by {@link #CLICK_EXPAND}.
     */
    static double[] clickBox(int terminal) {
        double[] box = contactBox(terminal);
        for (int axis = 0; axis < 3; axis++) {
            box[axis] -= CLICK_EXPAND;
            box[axis + 3] += CLICK_EXPAND;
        }
        return box;
    }

    /**
     * The drawn pin, in pixels as the partial is drawn: row A is terminals 0..9,
     * row B 10..19, each counting from high x down, since the drawn model is the
     * export turned half a turn (see {@link #ROW_A_MIN_Z}).
     */
    static double[] pin(int terminal) {
        int column = terminal % PINS_PER_ROW;
        double minZ = terminal < PINS_PER_ROW ? ROW_A_MIN_Z : ROW_B_MIN_Z;
        double minX = PINS_MAX_X - PIN_SIZE - column * PIN_PITCH;
        return new double[] { minX, DIN_BASE_TOP, minZ, minX + PIN_SIZE, DIN_BASE_TOP + PIN_RISE, minZ + PIN_SIZE };
    }

    private static double[][] contactBoxes() {
        double[][] boxes = new double[TERMINALS][];
        for (int i = 0; i < TERMINALS; i++) {
            double[] grown = pin(i);
            // Up only — down is the ledge the pin stands on — and a little either side
            grown[4] += REACH;
            grown[0] -= PAD;
            grown[3] += PAD;
            grown[2] -= PAD;
            grown[5] += PAD;
            boxes[i] = grown;
        }
        return boxes;
    }

    private static double[][] lowered(double[][] boxes, double drop) {
        double[][] out = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) {
            double[] box = boxes[i].clone();
            box[1] -= drop;
            box[4] -= drop;
            out[i] = box;
        }
        return out;
    }

    private static double[][] contactOrigins() {
        double[][] origins = new double[TERMINALS][];
        for (int i = 0; i < TERMINALS; i++) {
            double[] pin = pin(i);
            // The pin's own top face, not the grown box's, which would hang the wire
            // in the air REACH above the copper
            origins[i] = new double[] { (pin[0] + pin[3]) / 2, pin[4], (pin[2] + pin[5]) / 2 };
        }
        return origins;
    }
}
