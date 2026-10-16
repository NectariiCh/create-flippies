package com.nectariich.create_flippies.content.relay;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A DIN relay mount as boxes: what it occupies and where its terminals are, in
 * the frame its generated partial is drawn in. One instance per socket:
 * {@link #FIVE_PIN} and {@link #EIGHT_PIN}.
 *
 * The exports are authored like the PSU's — plate down, {@code y} the depth off
 * the plate, {@code z} across the rail, {@code x} along it — and
 * {@code tools/gen_relay.py} centres each on the middle of the cell. Everything
 * here is in that centred frame. The script finds every pin of an export by
 * position against the boxes below and fails on a mismatch.
 *
 * <h2>Terminals</h2>
 *
 * The coil first, then each pole's common, NO and NC in turn — frozen per socket,
 * for the reason every control's are:
 *
 * <pre>
 *   5-pin:  0 A1  1 A2  2 C   3 NO   4 NC
 *   8-pin:  0 A1  1 A2  2 C1  3 NO1  4 NC1  5 C2  6 NO2  7 NC2
 * </pre>
 *
 * A1 is coil +. The coil pins stand on the −z face, the contacts on the +z face.
 * A click box is the pin grown outward from its face by {@link #REACH}, padded in
 * depth and along the rail by the socket's own pads — the 8-pin's pins sit in a
 * tight two-column grid, so its pads are smaller — and always kept
 * {@link #EDGE} inside the mount's width, so neighbouring mounts never share a
 * click.
 *
 * <h2>The body</h2>
 *
 * The bases and hooks as drawn, and the space the relay takes when seated. That
 * space is reserved whether or not a relay is fitted, so one can always be pushed
 * in.
 */
public final class RelayMountGeometry {

    public static final int TERMINAL_A1 = 0;
    public static final int TERMINAL_A2 = 1;

    /** Out from the face. */
    private static final double REACH = 0.375;
    private static final double EDGE = 0.05;

    /** What Power Grid adds to each box, per side: nothing, as for the PSU. */
    static final double CLICK_EXPAND = 0;

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".relay_mount.terminal.";

    private static final int COIL_PLUS_COLOR = IDecoratedTerminal.RED;
    private static final int COIL_MINUS_COLOR = IDecoratedTerminal.BLUE;
    /** The contacts are not a supply pair; the PLC's output orange, as the PSU's DC OK. */
    private static final int CONTACT_COLOR = 0xFFAA00;

    /**
     * Half a module wide; three stepped bases, each pin a quarter pixel square and an
     * eighth proud, centred across the body.
     */
    public static final RelayMountGeometry FIVE_PIN = new RelayMountGeometry(5, 1, 0.5,
            new double[][] {
                    { 7.875, 1.1, 5.375, 8.125, 1.35, 5.5 },
                    { 7.875, 1.9, 5.875, 8.125, 2.15, 6.0 },
                    { 7.875, 1.1, 10.5, 8.125, 1.35, 10.625 },
                    { 7.875, 1.9, 10.0, 8.125, 2.15, 10.125 },
                    { 7.875, 2.65, 9.5, 8.125, 2.9, 9.625 },
            },
            new double[][] {
                    { 7.75, 0.75, 5.5, 8.25, 1.5, 10.5 },
                    { 7.75, 1.5, 6.0, 8.25, 2.25, 10.0 },
                    { 7.75, 2.25, 6.5, 8.25, 3.0, 9.5 },
                    // The slot's two walls and the relay between them
                    { 7.75, 3.0, 6.75, 8.25, 3.75, 9.0 },
                    { 7.75, 0.25, 5.75, 8.25, 0.75, 6.65 },
                    { 7.75, 0.25, 9.35, 8.25, 0.75, 10.25 },
            },
            Double.POSITIVE_INFINITY, 0.2);

    /**
     * A module wide; one base with a raised socket, the coil pins half a pixel wide
     * and the six contacts in two columns of three, every pin a quarter pixel proud.
     */
    public static final RelayMountGeometry EIGHT_PIN = new RelayMountGeometry(8, 2, 1.0,
            new double[][] {
                    { 7.75, 1.75, 5.25, 8.25, 2.0, 5.5 },
                    { 7.75, 1.25, 5.25, 8.25, 1.5, 5.5 },
                    { 7.625, 1.5, 10.5, 7.875, 1.75, 10.75 },
                    { 7.625, 1.0, 10.5, 7.875, 1.25, 10.75 },
                    { 7.625, 2.0, 10.5, 7.875, 2.25, 10.75 },
                    { 8.125, 1.5, 10.5, 8.375, 1.75, 10.75 },
                    { 8.125, 1.0, 10.5, 8.375, 1.25, 10.75 },
                    { 8.125, 2.0, 10.5, 8.375, 2.25, 10.75 },
            },
            new double[][] {
                    { 7.5, 0.75, 5.5, 8.5, 2.5, 10.5 },
                    { 7.5, 2.5, 6.75, 8.5, 2.75, 9.25 },
                    // The relay seated flush, from the socket's top to the top of its case
                    { 7.5, 2.75, 7.0, 8.5, 4.0, 9.0 },
                    { 7.5, 0.25, 5.75, 8.5, 0.75, 6.65 },
                    { 7.5, 0.25, 9.35, 8.5, 0.75, 10.25 },
            },
            0.1, 0.1);

    private final int pins;
    private final int poles;
    private final double width;
    private final double minX;
    private final double maxX;
    private final double[][] pinBoxes;
    private final double[][] bodyBoxes;
    private final double padX;
    private final double padY;
    private final ControlFootprint footprint;

    private RelayMountGeometry(int pins, int poles, double width, double[][] pinBoxes, double[][] bodyBoxes,
            double padX, double padY) {
        if (pinBoxes.length != 2 + 3 * poles)
            throw new IllegalArgumentException(pins + "-pin mount has " + pinBoxes.length + " pin boxes");
        this.pins = pins;
        this.poles = poles;
        this.width = width;
        this.minX = 8 - width / 2;
        this.maxX = 8 + width / 2;
        this.pinBoxes = pinBoxes;
        this.bodyBoxes = bodyBoxes;
        this.padX = padX;
        this.padY = padY;
        double[][] contacts = new double[terminals()][];
        for (int i = 0; i < contacts.length; i++)
            contacts[i] = contactBox(i);
        this.footprint = ControlFootprint.of(bodyBoxes).plus(contacts);
    }

    /** How many pins the socket has, which is what a relay must match to go in. */
    public int pins() {
        return pins;
    }

    public int poles() {
        return poles;
    }

    public double width() {
        return width;
    }

    double minX() {
        return minX;
    }

    double maxX() {
        return maxX;
    }

    public int terminals() {
        return 2 + 3 * poles;
    }

    public static int common(int pole) {
        return 2 + 3 * pole;
    }

    public static int no(int pole) {
        return 3 + 3 * pole;
    }

    public static int nc(int pole) {
        return 4 + 3 * pole;
    }

    public ControlFootprint footprint() {
        return footprint;
    }

    /** The case as boxes: the outline and collision of the mount, relay space included. */
    double[][] bodyBoxes() {
        return bodyBoxes;
    }

    /** The drawn pin. */
    double[] pin(int terminal) {
        return pinBoxes[terminal].clone();
    }

    /** Coil pins stand on the −z face, contacts on the +z face. */
    private static boolean outwardPlusZ(int terminal) {
        return terminal > TERMINAL_A2;
    }

    /** The grown box a click is resolved against. */
    double[] contactBox(int terminal) {
        double[] box = pin(terminal);
        box[0] = Math.max(minX + EDGE, box[0] - padX);
        box[3] = Math.min(maxX - EDGE, box[3] + padX);
        box[1] -= padY;
        box[4] += padY;
        if (outwardPlusZ(terminal))
            box[5] += REACH;
        else
            box[2] -= REACH;
        return box;
    }

    /** Where a wire lands: the middle of the pin's outer face. */
    double[] origin(int terminal) {
        double[] pin = pinBoxes[terminal];
        double z = outwardPlusZ(terminal) ? pin[5] : pin[2];
        return new double[] { (pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, z };
    }

    public TerminalBoundingBox[] terminals(ControlMount mount) {
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[terminals()];
        for (int i = 0; i < terminals.length; i++) {
            double[] box = mount.box(contactBox(i));
            double[] o = origin(i);
            double[] tip = mount.point(o[0], o[1], o[2]);
            terminals[i] = new TerminalBoundingBox(contactName(i), box[0], box[1], box[2], box[3], box[4], box[5],
                    CLICK_EXPAND)
                    .withColor(contactColor(i))
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    /** C, NO and NC on a single pole; C1, NO2 and so on on more. */
    public Component contactName(int terminal) {
        if (terminal == TERMINAL_A1)
            return Component.translatable(TERMINAL_PREFIX + "a1");
        if (terminal == TERMINAL_A2)
            return Component.translatable(TERMINAL_PREFIX + "a2");
        int pole = (terminal - 2) / 3;
        String contact = switch ((terminal - 2) % 3) {
            case 0 -> "c";
            case 1 -> "no";
            default -> "nc";
        };
        if (poles == 1)
            return Component.translatable(TERMINAL_PREFIX + contact);
        return Component.translatable(TERMINAL_PREFIX + contact + "_n", pole + 1);
    }

    public static int contactColor(int terminal) {
        return switch (terminal) {
            case TERMINAL_A1 -> COIL_PLUS_COLOR;
            case TERMINAL_A2 -> COIL_MINUS_COLOR;
            default -> CONTACT_COLOR;
        };
    }
}
