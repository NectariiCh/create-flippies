package com.nectariich.create_flippies.content.psu;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerModel;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The DIN-rail power supplies as boxes: what one occupies and where its five
 * terminals are, in the frame its generated partial is drawn in.
 *
 * The exports are authored like the breakers': plate-down, {@code y} the depth
 * off the plate, {@code z} across the rail, {@code x} along it from 0, one pixel
 * per module. {@code tools/gen_psu.py} moves them by
 * {@link CircuitBreakerModel#shiftX} so they straddle the middle of the cell, and
 * everything here is in that shifted frame. The script also checks every pin of
 * an export against {@link #pin} and fails on a mismatch, so the copper that is
 * drawn and the boxes a click lands on cannot drift apart unnoticed.
 *
 * <h2>The body</h2>
 *
 * A lower base the full width, 0.75..3.25 px off the plate and 5.5..10.5 across;
 * a raised upper base on it, inset across; and the two hooks under it that grip
 * the rail's lips. Only the module count changes between variants.
 *
 * <h2>Terminals</h2>
 *
 * Numbered in this order, which is frozen once shipped: Power Grid numbers a
 * control's terminals positionally and saved wires depend on it.
 *
 * <pre>
 *   0  VCC     input +       +z side, module 1
 *   1  GND     input −       +z side, module 2
 *   2  V+      output +      −z side, module 1
 *   3  V−      output −      −z side, module 2
 *   4  DC OK   status out    −z side, module 3
 * </pre>
 *
 * Unlike the PLC's, these pins stand on the <em>sides</em> of the lower base, a
 * quarter pixel proud of its ±z faces, so each click box is grown outward across
 * the rail — away from the face — by {@link #REACH}, never up, and padded along
 * the rail and in depth by {@link #PAD}. The pins are half a pixel apart, so the
 * padded boxes keep 0.3 px between neighbours. As in
 * {@code PlcSmallGeometry}, the boxes go to Power Grid with an explicit
 * {@link #CLICK_EXPAND} of nothing: its six-coordinate constructor would add a
 * further tenth of a pixel a side on top of {@link #PAD}.
 *
 * <h2>Colours</h2>
 *
 * Each polarised pair is red and blue, as every supply pair in the mod is. The
 * input and output pairs are told apart by being on opposite faces and by name,
 * not by colour; DC OK takes the PLC's output orange, since it is the same kind
 * of thing — a sourcing status output — as the PLC's STLED.
 */
public final class PsuGeometry {

    public static final int TERMINAL_VCC = 0;
    public static final int TERMINAL_GND = 1;
    public static final int TERMINAL_V_PLUS = 2;
    public static final int TERMINAL_V_MINUS = 3;
    public static final int TERMINAL_DC_OK = 4;
    public static final int TERMINALS = 5;

    /**
     * The fewest modules the pin layout below fits: DC OK is in the third. A
     * narrower variant needs its pins placed again, here and in gen_psu.py.
     */
    public static final int MIN_MODULES = 3;

    // -------------------------------------------------------------- body ---
    private static final double HOOK_BOTTOM = 0.25;
    private static final double FLOOR = 0.75;
    private static final double BASE_TOP = 3.25;
    private static final double TOP = 3.5;
    private static final double BASE_MIN_Z = 5.5;
    private static final double BASE_MAX_Z = 10.5;
    private static final double UPPER_MIN_Z = 6.25;
    private static final double UPPER_MAX_Z = 9.75;

    // -------------------------------------------------------------- pins ---
    /** Which module each terminal's pin is in, counting from the low-x end. */
    private static final int[] COLUMN = { 0, 1, 0, 1, 2 };

    /** Which face each pin stands on: +1 the high-z (input) face, −1 the low-z (output) face. */
    private static final int[] SIDE = { +1, +1, -1, -1, -1 };

    private static final double PIN_INSET = 0.25;
    private static final double PIN_SIZE = 0.5;
    private static final double PIN_RISE = 0.25;
    private static final double PIN_MIN_Y = 1.25;

    /** How far a pin's box reaches out from the pin, away from the face: the PLC's reach. */
    private static final double REACH = 0.375;

    /** How far a box spreads past its pin along the rail and in depth, each side. */
    private static final double PAD = 0.1;

    /** What Power Grid adds to each box, per side: nothing. See the class comment. */
    static final double CLICK_EXPAND = 0;

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".psu.terminal.";

    /** The PLC's output orange; see the class comment. */
    private static final int DC_OK_COLOR = 0xFFAA00;

    private PsuGeometry() {}

    private static void checkModules(int modules) {
        if (modules < MIN_MODULES)
            throw new IllegalArgumentException("A " + modules + " module PSU has no room for the pin layout "
                    + "PsuGeometry knows; place its pins first");
    }

    /** The body as boxes, the outline and collision of the case itself. */
    static double[][] bodyBoxes(int modules) {
        checkModules(modules);
        double lo = CircuitBreakerModel.minX(modules);
        double hi = CircuitBreakerModel.maxX(modules);
        return new double[][] {
                { lo, FLOOR, BASE_MIN_Z, hi, BASE_TOP, BASE_MAX_Z },
                { lo, BASE_TOP, UPPER_MIN_Z, hi, TOP, UPPER_MAX_Z },
                // The hooks, with the extrusions they hang from
                { lo, HOOK_BOTTOM, 5.75, hi, FLOOR, 6.65 },
                { lo, HOOK_BOTTOM, 9.35, hi, FLOOR, 10.25 },
        };
    }

    /**
     * What a supply of this width occupies on a rail: its body, following the
     * step of the case rather than hulling it, and the grown terminal boxes, so a
     * wire target can never be built into.
     */
    public static ControlFootprint footprint(int modules) {
        double[][] contacts = new double[TERMINALS][];
        for (int i = 0; i < TERMINALS; i++)
            contacts[i] = contactBox(modules, i);
        return ControlFootprint.of(bodyBoxes(modules)).plus(contacts);
    }

    /** The drawn pin, in pixels as the partial is drawn. */
    static double[] pin(int modules, int terminal) {
        checkModules(modules);
        double minX = CircuitBreakerModel.minX(modules) + COLUMN[terminal] + PIN_INSET;
        double minZ = SIDE[terminal] > 0 ? BASE_MAX_Z : BASE_MIN_Z - PIN_RISE;
        return new double[] { minX, PIN_MIN_Y, minZ, minX + PIN_SIZE, PIN_MIN_Y + PIN_SIZE, minZ + PIN_RISE };
    }

    /** The grown box a click is resolved against, in authored pixels. */
    static double[] contactBox(int modules, int terminal) {
        double[] box = pin(modules, terminal);
        box[0] -= PAD;
        box[3] += PAD;
        box[1] -= PAD;
        box[4] += PAD;
        // Outward only: the other way is the base the pin stands on
        if (SIDE[terminal] > 0)
            box[5] += REACH;
        else
            box[2] -= REACH;
        return box;
    }

    /** {@link #contactBox} as Power Grid widens it by {@link #CLICK_EXPAND}. */
    static double[] clickBox(int modules, int terminal) {
        double[] box = contactBox(modules, terminal);
        for (int axis = 0; axis < 3; axis++) {
            box[axis] -= CLICK_EXPAND;
            box[axis + 3] += CLICK_EXPAND;
        }
        return box;
    }

    /**
     * Where a wire lands: the middle of the pin's outer face, not of the grown box,
     * which would hang the wire {@link #REACH} off the copper.
     */
    static double[] origin(int modules, int terminal) {
        double[] pin = pin(modules, terminal);
        double z = SIDE[terminal] > 0 ? pin[5] : pin[2];
        return new double[] { (pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, z };
    }

    /**
     * The contacts as a mounted supply presents them, in terminal order. Built
     * afresh on each call, for the reason
     * {@link com.nectariich.create_flippies.foundation.control.ControlType#terminals}
     * gives.
     */
    public static TerminalBoundingBox[] terminals(PsuType type, ControlMount mount) {
        int modules = type.modules();
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[TERMINALS];
        for (int i = 0; i < TERMINALS; i++) {
            double[] box = mount.box(contactBox(modules, i));
            double[] o = origin(modules, i);
            double[] tip = mount.point(o[0], o[1], o[2]);
            terminals[i] = new TerminalBoundingBox(contactName(type, i), box[0], box[1], box[2], box[3], box[4],
                    box[5], CLICK_EXPAND)
                    .withColor(contactColor(i))
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    /** What a terminal is called where a wire is being aimed at it, with the rating it carries. */
    public static Component contactName(PsuType type, int terminal) {
        return switch (terminal) {
            case TERMINAL_VCC -> Component.translatable(TERMINAL_PREFIX + "vcc", type.inputVolts());
            case TERMINAL_GND -> Component.translatable(TERMINAL_PREFIX + "gnd", type.inputVolts());
            case TERMINAL_V_PLUS -> Component.translatable(TERMINAL_PREFIX + "v_plus", type.outputVolts());
            case TERMINAL_V_MINUS -> Component.translatable(TERMINAL_PREFIX + "v_minus", type.outputVolts());
            default -> Component.translatable(TERMINAL_PREFIX + "dc_ok");
        };
    }

    public static int contactColor(int terminal) {
        return switch (terminal) {
            case TERMINAL_VCC, TERMINAL_V_PLUS -> IDecoratedTerminal.RED;
            case TERMINAL_GND, TERMINAL_V_MINUS -> IDecoratedTerminal.BLUE;
            default -> DC_OK_COLOR;
        };
    }
}
