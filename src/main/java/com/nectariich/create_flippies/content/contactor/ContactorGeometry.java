package com.nectariich.create_flippies.content.contactor;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerModel;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A DIN contactor as boxes: what it occupies and where its terminals are, in the
 * frame its generated partial is drawn in.
 *
 * The exports ({@code contactor-<n>m-<poles>-<v>v.json}) are authored like the
 * breakers' — plate down, {@code y} the depth off the plate, {@code z} across the
 * rail, {@code x} along it from 0, a module a pixel — and
 * {@code tools/gen_contactors.py} moves each by {@link CircuitBreakerModel#shiftX}
 * of its module count to straddle the middle of the cell. Everything here is in
 * that shifted frame, worked out per module from the one-module figures below.
 * The script checks every pin of every export against them and fails on a
 * mismatch.
 *
 * <h2>Terminals</h2>
 *
 * The coil, on the first module only, then each pole's common, NO and NC in turn —
 * the relay mounts' order, and frozen for the same reason:
 *
 * <pre>
 *   0 A1  1 A2  2 C1  3 NO1  4 NC1  5 C2  6 NO2  7 NC2 ...
 * </pre>
 *
 * The coil stands on the −z face, A1 the one nearer the plate; each pole's
 * contacts stand on the +z face of its module, NO nearest the plate, then C, then
 * NC. A click box is the pin grown outward by {@link #REACH}, padded by
 * {@link #PAD_Y} in depth — which keeps the three contacts, a quarter pixel apart,
 * from sharing a click — and across most of its module.
 */
public final class ContactorGeometry {

    public static final int TERMINAL_A1 = 0;
    public static final int TERMINAL_A2 = 1;

    /** One module's pins, x from 0, as (minX, minY, minZ, maxX, maxY, maxZ). */
    private static final double[] A1 = { 0.25, 1.0, 5.25, 0.75, 1.5, 5.5 };
    private static final double[] A2 = { 0.25, 1.75, 5.25, 0.75, 2.25, 5.5 };
    private static final double[] C = { 0.25, 1.5, 10.5, 0.75, 1.75, 10.75 };
    private static final double[] NO = { 0.25, 1.0, 10.5, 0.75, 1.25, 10.75 };
    private static final double[] NC = { 0.25, 2.0, 10.5, 0.75, 2.25, 10.75 };

    /** One module's body as drawn: the two bases and the two hooks. */
    private static final double[][] BODY = {
            { 0, 0.75, 5.5, 1, 2.5, 10.5 },
            { 0, 2.5, 6.75, 1, 3.5, 9.25 },
            { 0, 0.25, 5.75, 1, 0.75, 6.65 },
            { 0, 0.25, 9.35, 1, 0.75, 10.25 },
    };

    private static final double REACH = 0.375;
    private static final double PAD_Y = 0.1;
    /** How far inside its module a click box stops, as the breakers' does. */
    private static final double EDGE = 0.1;

    /** What Power Grid adds to each box, per side, as for the PLC; see {@code PlcSmallGeometry}. */
    static final double CLICK_EXPAND = 0.01;

    /** The relay mount's names: a contactor's pins are called just what a relay's are. */
    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".relay_mount.terminal.";

    private static final int COIL_PLUS_COLOR = IDecoratedTerminal.RED;
    private static final int COIL_MINUS_COLOR = IDecoratedTerminal.BLUE;
    private static final int CONTACT_COLOR = 0xFFAA00;

    private ContactorGeometry() {}

    public static int terminals(int poles) {
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

    /** The drawn pin of a terminal, in the shifted frame. */
    static double[] pin(int modules, int terminal) {
        double[] base;
        int module;
        if (terminal == TERMINAL_A1 || terminal == TERMINAL_A2) {
            base = terminal == TERMINAL_A1 ? A1 : A2;
            module = 0;
        } else {
            module = (terminal - 2) / 3;
            base = switch ((terminal - 2) % 3) {
                case 0 -> C;
                case 1 -> NO;
                default -> NC;
            };
        }
        return moved(base, CircuitBreakerModel.shiftX(modules) + module);
    }

    private static double[] moved(double[] box, double dx) {
        double[] out = box.clone();
        out[0] += dx;
        out[3] += dx;
        return out;
    }

    private static boolean outwardPlusZ(int terminal) {
        return terminal > TERMINAL_A2;
    }

    /** The grown box a click is resolved against. */
    static double[] contactBox(int modules, int terminal) {
        double[] box = pin(modules, terminal);
        int module = terminal <= TERMINAL_A2 ? 0 : (terminal - 2) / 3;
        double moduleMin = CircuitBreakerModel.shiftX(modules) + module;
        box[0] = moduleMin + EDGE;
        box[3] = moduleMin + 1 - EDGE;
        box[1] -= PAD_Y;
        box[4] += PAD_Y;
        if (outwardPlusZ(terminal))
            box[5] += REACH;
        else
            box[2] -= REACH;
        return box;
    }

    /** Where a wire lands: the middle of the pin's outer face. */
    static double[] origin(int modules, int terminal) {
        double[] pin = pin(modules, terminal);
        double z = outwardPlusZ(terminal) ? pin[5] : pin[2];
        return new double[] { (pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, z };
    }

    /** The body of every module and the click box of every pin. */
    static ControlFootprint footprint(int modules) {
        double shift = CircuitBreakerModel.shiftX(modules);
        double[][] body = new double[BODY.length][];
        for (int i = 0; i < BODY.length; i++) {
            body[i] = BODY[i].clone();
            body[i][0] = shift;
            body[i][3] = shift + modules;
        }
        double[][] contacts = new double[terminals(modules)][];
        for (int i = 0; i < contacts.length; i++)
            contacts[i] = contactBox(modules, i);
        return ControlFootprint.of(body).plus(contacts);
    }

    static TerminalBoundingBox[] terminals(int modules, ControlMount mount) {
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[terminals(modules)];
        for (int i = 0; i < terminals.length; i++) {
            double[] box = mount.box(contactBox(modules, i));
            double[] o = origin(modules, i);
            double[] tip = mount.point(o[0], o[1], o[2]);
            terminals[i] = new TerminalBoundingBox(contactName(modules, i), box[0], box[1], box[2], box[3], box[4],
                    box[5], CLICK_EXPAND)
                    .withColor(contactColor(i))
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    /** C, NO and NC on a single pole; C1, NO2 and so on on more. */
    public static Component contactName(int modules, int terminal) {
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
        if (modules == 1)
            return Component.translatable(TERMINAL_PREFIX + contact);
        return Component.translatable(TERMINAL_PREFIX + contact + "_n", pole + 1);
    }

    static int contactColor(int terminal) {
        return switch (terminal) {
            case TERMINAL_A1 -> COIL_PLUS_COLOR;
            case TERMINAL_A2 -> COIL_MINUS_COLOR;
            default -> CONTACT_COLOR;
        };
    }
}
