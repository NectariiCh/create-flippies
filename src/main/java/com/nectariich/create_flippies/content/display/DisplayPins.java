package com.nectariich.create_flippies.content.display;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The pins on the back of an alphanumeric display, stated once for every form it is
 * made in and both forms it comes in.
 *
 * A display placed as a block of its own and one screwed into a host panel are the
 * same piece of gear, so a footprint that disagreed with a terminal box by a pixel
 * would be a wire that will not attach in one form and does in the other. The
 * authored numbers are the exports', in the frame everything panel-mounted uses: the
 * glass flush with the floor of the cell and the barrel hanging through the panel
 * behind it. Only the glass differs between forms, so it is {@link DisplayForm}'s
 * and everything below is shared.
 *
 * <h2>Three of the eight pins are wired</h2>
 *
 * The rack the exports draw is a whole connector — a supply pair, a backlight feed,
 * a three-wire serial port and the two jumper posts that select between serial and
 * wired mode. Only {@code VCC}, {@code GND} and {@code BL} are terminals. The serial
 * pins and the mode jumpers are drawn and are part of the device's bulk, but nothing
 * in this mod speaks that protocol yet, and a terminal that can be wired to nothing
 * is worse than one that is not offered: it would take a wire, carry current and do
 * nothing, and it would be indistinguishable from a wiring mistake.
 *
 * Leaving them out costs nothing later either, because they would be added
 * <em>after</em> the three below rather than among them. Power Grid numbers a
 * control's terminals positionally, and a host streams them as one flat run, so
 * inserting a terminal in the middle of this list would shift every later control's
 * indices on every panel already built — see
 * {@link com.nectariich.create_flippies.foundation.control.TerminalSlots}. The three
 * that are here must therefore keep the order they have forever.
 *
 * <h2>Why the boxes are bigger than the pins</h2>
 *
 * Every wired pin stands off the same face of the barrel and sticks out an eighth of
 * a pixel, and Power Grid resolves a terminal from the precise spot clicked, so the
 * drawn pin is a target nobody can hit. Each box is therefore reached outward along
 * that one axis and padded a little within the face — outward only, and by less than
 * half the gap to its neighbour, so that no two boxes overlap. A click in an overlap
 * attaches a wire to whichever of the two Power Grid happened to test first, which
 * on a supply pair means a short.
 */
public final class DisplayPins {

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alphanum_display.terminal.";

    /** The rail that runs the display itself; nothing is shown without it. */
    public static final int TERMINAL_VCC = 0;
    /** The return for both the supply and the backlight. */
    public static final int TERMINAL_GND = 1;
    /** The backlight feed, which is a lamp and nothing else; see {@link AlphanumDisplay}. */
    public static final int TERMINAL_BL = 2;

    public static final int TERMINALS = 3;

    // These mirror the authored exports and have to be updated with them;
    // `python tools/gen_display.py` prints what it finds in each one.

    /** The barrel, standing in the cell on the far side of the panel. */
    public static final double[] BARREL = { 7.375, -4, 7.375, 8.625, 0, 8.625 };

    /**
     * The wired pins as the exports draw them, in terminal order. All three stand off
     * the barrel's {@code -z} face, which is what makes one reach direction serve them
     * all.
     */
    private static final double[][] PINS = {
            { 8.1, -3.75, 7.25, 8.5, -3.35, 7.5 },
            { 7.5, -3.75, 7.25, 7.9, -3.35, 7.5 },
            { 7.5, -3.0, 7.25, 7.9, -2.7, 7.5 } };

    /**
     * The pins that are drawn and never wired: the serial port and the two mode
     * jumpers. They are here so that the space they occupy is declared — two displays
     * back to back must not pass through each other's connectors — and for nothing
     * else. They are not grown, because nothing resolves a terminal from them.
     */
    private static final double[][] UNWIRED = {
            { 8.1, -3.0, 7.25, 8.5, -2.7, 7.5 },
            { 8.1, -2.6, 7.25, 8.5, -2.3, 7.5 },
            { 7.5, -2.6, 7.25, 7.9, -2.3, 7.5 },
            { 7.3, -3.75, 7.6, 7.4, -3.65, 8.0 },
            { 7.3, -3.2, 7.6, 7.4, -3.1, 8.0 } };

    /**
     * How far a pin's box reaches out past the drawn stub, in authored pixels. The
     * indicators' reach, because it is the same problem on a device of the same
     * order of size: enough to aim at, little enough that the device does not become
     * a footprint made of clickable air.
     */
    private static final double REACH = 0.375;

    /**
     * How far a box spreads within the face of the barrel, across and up.
     *
     * The two columns of pins stand a fifth of a pixel apart and the rows a little
     * over a third, so these are under half of each gap: the boxes come close enough
     * to be hit and still cannot meet. The vertical figure is the one to watch when a
     * later form moves a pin, because the backlight sits one row above the supply
     * pair and that is the closest two wired pins come.
     */
    private static final double PAD_ACROSS = 0.09;
    private static final double PAD_UP = 0.1;

    /** Every wired pin as it can be picked, in terminal order. */
    private static final double[][] CONTACT_BOXES = grownBoxes();

    /** Where a wire lands on each: the middle of the stub's outward face. */
    private static final double[][] CONTACT_ORIGINS = contactOrigins();

    private static final Component[] CONTACT_NAMES = {
            Component.translatable(TERMINAL_PREFIX + "vcc"),
            Component.translatable(TERMINAL_PREFIX + "gnd"),
            Component.translatable(TERMINAL_PREFIX + "bl") };

    /**
     * What each terminal is wired in. The supply pair takes the red and blue every
     * other polarised pair in the mod uses, and the backlight takes a colour of its
     * own because it is a separate feed that happens to share the return — a player
     * who wired it as though it were part of the supply would get a dark screen and
     * no clue why.
     */
    private static final int[] CONTACT_COLORS = {
            IDecoratedTerminal.RED, IDecoratedTerminal.BLUE, IDecoratedTerminal.GREEN };

    /**
     * Everything the device really occupies, per form: the glass in its own cell, the
     * barrel through the panel, the grown pin boxes and the pins nothing is wired to.
     *
     * Nothing about a display moves, so unlike the rotary switch there is no swept
     * volume to declare. The borderless 1.5 px glass is narrower than the rack behind
     * it, so what sets the spacing is the connector: a display claims a pixel and a
     * half across and a little under two front to back, which lets them sit in a row
     * shoulder to shoulder on a ruled grid. The barrel spends four pixels of the depth
     * budget, so two displays mounted back to back through a panel one block thick
     * clear each other by eight.
     */
    private static final Map<DisplayForm, ControlFootprint> FOOTPRINTS = footprints();

    /** Everything the crosshair may pick, in the order the outline is built. */
    private static final Map<DisplayForm, double[][]> OUTLINES = outlines();

    private DisplayPins() {}

    public static ControlFootprint footprint(DisplayForm form) {
        return FOOTPRINTS.get(form);
    }

    public static double[][] outlineBoxes(DisplayForm form) {
        return OUTLINES.get(form);
    }

    /**
     * The contacts as a mounted display presents them.
     *
     * Built afresh on each call, never cached, for the reason
     * {@link com.nectariich.create_flippies.foundation.control.ControlType#terminals}
     * gives: a TerminalBoundingBox mutates itself when it is given an origin or a
     * colour, so a shared instance would be rewritten by the next display mounted the
     * other way up.
     */
    public static TerminalBoundingBox[] terminals(ControlMount mount) {
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[TERMINALS];
        for (int i = 0; i < TERMINALS; i++) {
            double[] box = mount.box(CONTACT_BOXES[i]);
            double[] origin = CONTACT_ORIGINS[i];
            double[] tip = mount.point(origin[0], origin[1], origin[2]);
            terminals[i] = new TerminalBoundingBox(CONTACT_NAMES[i], box[0], box[1], box[2], box[3], box[4], box[5])
                    .withColor(CONTACT_COLORS[i])
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    public static Component contactName(int terminal) {
        return CONTACT_NAMES[terminal];
    }

    /** What colour a terminal is wired in, for the block form's own collection. */
    public static int contactColor(int terminal) {
        return CONTACT_COLORS[terminal];
    }

    /** The grown box a click is resolved against, for the block form's own collection. */
    public static double[] contactBox(int terminal) {
        return CONTACT_BOXES[terminal];
    }

    /** Where a wire lands on one, which {@link #terminals} states for the mounted form. */
    public static double[] contactOrigin(int terminal) {
        return CONTACT_ORIGINS[terminal];
    }

    private static double[][] grownBoxes() {
        double[][] boxes = new double[PINS.length][];
        for (int i = 0; i < PINS.length; i++) {
            double[] grown = PINS[i].clone();
            // Outward along the one axis every wired pin stands off, and spread within
            // the face it stands on — never outward on the barrel side, which is where
            // the neighbouring pin is
            grown[2] -= REACH;
            grown[0] -= PAD_ACROSS;
            grown[3] += PAD_ACROSS;
            grown[1] -= PAD_UP;
            grown[4] += PAD_UP;
            boxes[i] = grown;
        }
        return boxes;
    }

    private static double[][] contactOrigins() {
        double[][] origins = new double[PINS.length][];
        for (int i = 0; i < PINS.length; i++) {
            double[] pin = PINS[i];
            // The outward face of the stub as drawn, not of the grown box: the box
            // reaches out on one side only, so its midpoint would hang the wire half
            // of REACH behind the pin in open air
            origins[i] = new double[] { (pin[0] + pin[3]) / 2, (pin[1] + pin[4]) / 2, pin[2] };
        }
        return origins;
    }

    private static Map<DisplayForm, ControlFootprint> footprints() {
        Map<DisplayForm, ControlFootprint> footprints = new EnumMap<>(DisplayForm.class);
        for (DisplayForm form : DisplayForm.values())
            footprints.put(form, ControlFootprint.of(form.glass(), BARREL).plus(CONTACT_BOXES).plus(UNWIRED));
        return Collections.unmodifiableMap(footprints);
    }

    private static Map<DisplayForm, double[][]> outlines() {
        Map<DisplayForm, double[][]> outlines = new EnumMap<>(DisplayForm.class);
        for (DisplayForm form : DisplayForm.values()) {
            double[][] boxes = new double[2 + CONTACT_BOXES.length + UNWIRED.length][];
            boxes[0] = form.glass();
            boxes[1] = BARREL;
            System.arraycopy(CONTACT_BOXES, 0, boxes, 2, CONTACT_BOXES.length);
            System.arraycopy(UNWIRED, 0, boxes, 2 + CONTACT_BOXES.length, UNWIRED.length);
            outlines.put(form, boxes);
        }
        return Collections.unmodifiableMap(outlines);
    }
}
