package com.nectariich.create_flippies.content.contactor;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The open contactor's module and its coil terminal as boxes, in the frame their
 * partials are drawn in.
 *
 * The exports ({@code contactor-open-<piece>.json}, a module wide, and
 * {@code contactor-open-coil-terminal-<v>v.json}, half a module) are authored from
 * x = 0, plate down, and {@code tools/gen_contactor_open.py} moves each by
 * {@code 8 - width / 2} to straddle the middle of the cell. Everything here is in
 * that frame. The script checks every pin against the tables below.
 *
 * <h2>Terminals</h2>
 *
 * Frozen, as every control's are:
 *
 * <pre>
 *   module:         0 C-NO1  1 NO1  2 C-NO2  3 NO2  4 C-NC1  5 NC1  6 C-NC2  7 NC2
 *   coil terminal:  0 A1  1 A2  (−z face)   2 A1  3 A2  (+z face, each joined to its twin)
 * </pre>
 *
 * Each module pair is its own contact. The NO pairs stand one above the other on the
 * same face; each NC pair's common is on the opposite face from its NC.
 */
public final class OpenContactorGeometry {

    public static final int MODULE_TERMINALS = 8;
    public static final int COIL_TERMINALS = 4;

    public static final double MODULE_WIDTH = 1.0;
    public static final double COIL_WIDTH = 0.5;

    private static final double MODULE_SHIFT = 8 - MODULE_WIDTH / 2;
    private static final double COIL_SHIFT = 8 - COIL_WIDTH / 2;

    /** Module pins in terminal order, export frame. */
    private static final double[][] MODULE_PINS = {
            { 0.25, 2.125, 6.0, 0.75, 2.375, 6.25 },
            { 0.25, 1.625, 6.0, 0.75, 1.875, 6.25 },
            { 0.25, 2.125, 9.75, 0.75, 2.375, 10.0 },
            { 0.25, 1.625, 9.75, 0.75, 1.875, 10.0 },
            { 0.25, 2.625, 6.0, 0.75, 2.875, 6.25 },
            { 0.25, 3.125, 9.75, 0.75, 3.375, 10.0 },
            { 0.25, 2.625, 9.75, 0.75, 2.875, 10.0 },
            { 0.25, 3.125, 6.0, 0.75, 3.375, 6.25 },
    };

    /** Coil pins in terminal order, export frame. */
    private static final double[][] COIL_PINS = {
            { 0.125, 1.375, 6.375, 0.375, 1.625, 6.5 },
            { 0.125, 1.875, 6.375, 0.375, 2.125, 6.5 },
            { 0.125, 1.375, 9.5, 0.375, 1.625, 9.625 },
            { 0.125, 1.875, 9.5, 0.375, 2.125, 9.625 },
    };

    /** Each pin's face: true for +z. */
    private static final double MIDDLE_Z = 8;

    /** The module's body as drawn: lower base, upper base to the top of the slot walls, hooks. */
    private static final double[][] MODULE_BODY = {
            { 0, 0.75, 5.5, 1, 1.5, 10.5 },
            { 0, 1.5, 6.25, 1, 3.5, 9.75 },
            { 0, 0.25, 5.75, 1, 0.75, 6.65 },
            { 0, 0.25, 9.35, 1, 0.75, 10.25 },
    };

    private static final double[][] COIL_BODY = {
            { 0, 0.75, 6, 0.5, 1.25, 10 },
            { 0, 1.25, 6.5, 0.5, 2.25, 9.5 },
            { 0, 0.25, 6, 0.5, 0.75, 6.65 },
            { 0, 0.25, 9.35, 0.5, 0.75, 10 },
    };

    /** How far the plunger ({@code button}) sinks towards the plate when pulled in, in pixels. */
    public static final double PLUNGER_TRAVEL = 0.25;

    private static final double REACH = 0.375;
    private static final double PAD_Y = 0.1;
    private static final double EDGE = 0.05;

    private static final String MODULE_TERMINAL = "gui." + CreateFlippies.MOD_ID + ".contactor_open.terminal.";
    private static final String RELAY_TERMINAL = "gui." + CreateFlippies.MOD_ID + ".relay_mount.terminal.";
    private static final String[] MODULE_KEYS = { "c_no", "no", "c_no", "no", "c_nc", "nc", "c_nc", "nc" };
    private static final int[] MODULE_PAIR = { 1, 1, 2, 2, 1, 1, 2, 2 };

    private static final int CONTACT_COLOR = 0xFFAA00;

    private static final ControlFootprint MODULE_FOOTPRINT = footprint(true);
    private static final ControlFootprint COIL_FOOTPRINT = footprint(false);

    private OpenContactorGeometry() {}

    private static double[] shifted(double[] box, double shift) {
        double[] out = box.clone();
        out[0] += shift;
        out[3] += shift;
        return out;
    }

    static double[] modulePin(int terminal) {
        return shifted(MODULE_PINS[terminal], MODULE_SHIFT);
    }

    static double[] coilPin(int terminal) {
        return shifted(COIL_PINS[terminal], COIL_SHIFT);
    }

    private static boolean plusZ(double[] pin) {
        return pin[2] > MIDDLE_Z;
    }

    private static double[] contactBox(double[] pin, double shift, double width) {
        double[] box = pin.clone();
        box[0] = shift + EDGE;
        box[3] = shift + width - EDGE;
        box[1] -= PAD_Y;
        box[4] += PAD_Y;
        if (plusZ(pin))
            box[5] += REACH;
        else
            box[2] -= REACH;
        return box;
    }

    static double[] moduleBox(int terminal) {
        return contactBox(modulePin(terminal), MODULE_SHIFT, MODULE_WIDTH);
    }

    static double[] coilBox(int terminal) {
        return contactBox(coilPin(terminal), COIL_SHIFT, COIL_WIDTH);
    }

    /** The module's body, which is also where a hand pushes the plunger. */
    static double[][] moduleBody() {
        return body(MODULE_BODY, MODULE_SHIFT);
    }

    static double[][] coilBody() {
        return body(COIL_BODY, COIL_SHIFT);
    }

    private static double[][] body(double[][] boxes, double shift) {
        double[][] out = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++)
            out[i] = shifted(boxes[i], shift);
        return out;
    }

    private static ControlFootprint footprint(boolean module) {
        int terminals = module ? MODULE_TERMINALS : COIL_TERMINALS;
        double[][] contacts = new double[terminals][];
        for (int i = 0; i < terminals; i++)
            contacts[i] = module ? moduleBox(i) : coilBox(i);
        return ControlFootprint.of(module ? moduleBody() : coilBody()).plus(contacts);
    }

    static ControlFootprint moduleFootprint() {
        return MODULE_FOOTPRINT;
    }

    static ControlFootprint coilFootprint() {
        return COIL_FOOTPRINT;
    }

    static TerminalBoundingBox[] moduleTerminals(ControlMount mount) {
        TerminalBoundingBox[] out = new TerminalBoundingBox[MODULE_TERMINALS];
        for (int i = 0; i < out.length; i++)
            out[i] = terminal(mount, modulePin(i), moduleBox(i), moduleName(i), CONTACT_COLOR);
        return out;
    }

    static TerminalBoundingBox[] coilTerminals(ControlMount mount) {
        TerminalBoundingBox[] out = new TerminalBoundingBox[COIL_TERMINALS];
        for (int i = 0; i < out.length; i++)
            out[i] = terminal(mount, coilPin(i), coilBox(i),
                    Component.translatable(RELAY_TERMINAL + (i % 2 == 0 ? "a1" : "a2")),
                    i % 2 == 0 ? IDecoratedTerminal.RED : IDecoratedTerminal.BLUE);
        return out;
    }

    private static TerminalBoundingBox terminal(ControlMount mount, double[] pin, double[] click, Component name,
            int color) {
        double[] box = mount.box(click);
        double z = plusZ(pin) ? pin[5] : pin[2];
        double[] tip = mount.point((pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, z);
        return new TerminalBoundingBox(name, box[0], box[1], box[2], box[3], box[4], box[5],
                ContactorGeometry.CLICK_EXPAND)
                .withColor(color)
                .withOrigin(tip[0], tip[1], tip[2]);
    }

    /** C-NO1, NO1, C-NO2, NO2, C-NC1, NC1, C-NC2, NC2, as the export names them. */
    static Component moduleName(int terminal) {
        return Component.translatable(MODULE_TERMINAL + MODULE_KEYS[terminal], MODULE_PAIR[terminal]);
    }

    /** Which pairs close when the plunger is in: the first two. */
    static boolean normallyOpen(int pair) {
        return pair < 2;
    }
}
