package com.nectariich.create_flippies.content.terminal_block;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A DIN terminal block as boxes: what it occupies and where its terminals are, in
 * the frame its generated partial is drawn in.
 *
 * The export ({@code din_terminal_block_6p3g.json} and its copies) is authored
 * like the relay mount's — plate down, {@code y} the depth off the plate, {@code z}
 * across the rail, {@code x} along it from 0 — and is half a module wide.
 * {@code tools/gen_terminal_blocks.py} moves it by {@link #SHIFT_X} to straddle the
 * middle of the cell, and everything here is in that shifted frame. The script
 * checks every pin of every export against {@link #pin} and fails on a mismatch.
 *
 * <h2>The body</h2>
 *
 * Up to three stepped bases, each half a pixel tall and a pixel narrower across
 * the rail than the one under it, on the relay mount's two hooks.
 *
 * <h2>Terminals</h2>
 *
 * One pin on each side of each step, a quarter pixel square and an eighth proud
 * of the step's face. Which pin is which terminal is the type's table. A click
 * box is grown outward from the face by {@link #REACH} — exactly as far as the step
 * below sticks out, so a box never reaches into the base — and padded in depth by
 * {@link #PAD_Y}, which keeps it within its own step's height. Along the rail it is
 * kept a twentieth inside the half module, so blocks on neighbouring half modules
 * never share a click.
 */
public final class TerminalBlockGeometry {

    /** Half a module along the rail. */
    public static final double WIDTH = 0.5;

    /** How far the x-from-0 export is moved to straddle the middle: CircuitBreakerModel.shiftX's rule. */
    public static final double SHIFT_X = 8 - WIDTH / 2;

    static final double MIN_X = SHIFT_X;
    static final double MAX_X = SHIFT_X + WIDTH;

    /** Each step's base, in depth, and its two faces across the rail. */
    private static final double[] STEP_Y = { 0.75, 1.25, 1.75 };
    private static final double STEP_HEIGHT = 0.5;
    private static final double[] FACE_MINUS_Z = { 5.5, 6.0, 6.5 };
    private static final double[] FACE_PLUS_Z = { 10.5, 10.0, 9.5 };

    /** Bottom of the pin on each step, in depth. */
    private static final double[] PIN_Y = { 0.85, 1.4, 1.9 };

    public static final int MAX_LEVELS = STEP_Y.length;

    private static final double PIN_SIZE = 0.25;
    private static final double PIN_RISE = 0.125;

    private static final double REACH = 0.375;
    private static final double PAD_Y = 0.1;
    private static final double EDGE = 0.05;

    /** What Power Grid adds to each box, per side: nothing, as for the relay mount. */
    static final double CLICK_EXPAND = 0;

    private static final String TERMINAL_KEY = "gui." + CreateFlippies.MOD_ID + ".terminal_block.terminal";
    private static final String GROUND_TERMINAL_KEY = "gui." + CreateFlippies.MOD_ID + ".terminal_block.ground_terminal";

    /** A colour per group, so a glance at the highlight says which pins are joined. */
    private static final int[] GROUP_COLORS = { 0xFFAA00, 0x55AAFF, 0xFF55FF };
    private static final int GROUND_COLOR = 0x55FF55;

    private TerminalBlockGeometry() {}

    /** The body as boxes: the steps the type has, and the hooks. */
    static double[][] bodyBoxes(int levels) {
        if (levels < 1 || levels > MAX_LEVELS)
            throw new IllegalArgumentException("A terminal block has 1 to " + MAX_LEVELS + " steps, not " + levels);
        List<double[]> boxes = new ArrayList<>();
        for (int level = 0; level < levels; level++)
            boxes.add(new double[] { MIN_X, STEP_Y[level], FACE_MINUS_Z[level], MAX_X, STEP_Y[level] + STEP_HEIGHT,
                    FACE_PLUS_Z[level] });
        // The hooks, with the extrusions they hang from
        boxes.add(new double[] { MIN_X, 0.25, 5.75, MAX_X, 0.75, 6.65 });
        boxes.add(new double[] { MIN_X, 0.25, 9.35, MAX_X, 0.75, 10.25 });
        return boxes.toArray(double[][]::new);
    }

    static ControlFootprint footprint(TerminalBlockType type) {
        double[][] contacts = new double[type.terminalCount()][];
        for (int i = 0; i < contacts.length; i++)
            contacts[i] = contactBox(type.pins().get(i));
        return ControlFootprint.of(bodyBoxes(type.levels())).plus(contacts);
    }

    /** The drawn pin. */
    static double[] pin(TerminalBlockType.Pin pin) {
        double minX = MIN_X + (WIDTH - PIN_SIZE) / 2;
        double minY = PIN_Y[pin.level()];
        double minZ = pin.side() > 0 ? FACE_PLUS_Z[pin.level()] : FACE_MINUS_Z[pin.level()] - PIN_RISE;
        return new double[] { minX, minY, minZ, minX + PIN_SIZE, minY + PIN_SIZE, minZ + PIN_RISE };
    }

    /** The grown box a click is resolved against. */
    static double[] contactBox(TerminalBlockType.Pin pin) {
        double[] box = pin(pin);
        box[0] = MIN_X + EDGE;
        box[3] = MAX_X - EDGE;
        box[1] -= PAD_Y;
        box[4] += PAD_Y;
        if (pin.side() > 0)
            box[5] += REACH;
        else
            box[2] -= REACH;
        return box;
    }

    /** Where a wire lands: the middle of the pin's outer face. */
    static double[] origin(TerminalBlockType.Pin pin) {
        double[] box = pin(pin);
        double z = pin.side() > 0 ? box[5] : box[2];
        return new double[] { (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, z };
    }

    public static TerminalBoundingBox[] terminals(TerminalBlockType type, ControlMount mount) {
        List<TerminalBlockType.Pin> pins = type.pins();
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[pins.size()];
        for (int i = 0; i < terminals.length; i++) {
            TerminalBlockType.Pin pin = pins.get(i);
            double[] box = mount.box(contactBox(pin));
            double[] o = origin(pin);
            double[] tip = mount.point(o[0], o[1], o[2]);
            terminals[i] = new TerminalBoundingBox(contactName(type, pin), box[0], box[1], box[2], box[3], box[4],
                    box[5], CLICK_EXPAND)
                    .withColor(type.ground() ? GROUND_COLOR : GROUP_COLORS[pin.group() % GROUP_COLORS.length])
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    public static Component contactName(TerminalBlockType type, TerminalBlockType.Pin pin) {
        return Component.translatable(type.ground() ? GROUND_TERMINAL_KEY : TERMINAL_KEY, pin.label());
    }
}
