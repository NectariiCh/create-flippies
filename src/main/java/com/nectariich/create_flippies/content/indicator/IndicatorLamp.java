package com.nectariich.create_flippies.content.indicator;

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
 * The shape of an indicator and of the eight contacts on the back of it, stated
 * once for both forms it comes in and both sizes it is made in.
 *
 * A unit placed as a block of its own and one screwed into a host panel are the same
 * piece of gear, so a footprint that disagreed with a terminal box by a pixel would
 * be a wire that will not attach in one form and does in the other. The authored
 * numbers are the models', in the frame everything panel-mounted uses: the cap flush
 * with the floor of the cell and the barrel hanging through the panel behind it. The
 * cap is the one thing the two sizes disagree about, so it is {@link IndicatorSize}'s
 * and everything below is shared.
 *
 * <h2>The lamp</h2>
 *
 * The lamp is bipolar. Its two contacts are the same size, sit opposite each other
 * on the barrel, and are named A and B rather than positive and negative, because
 * nothing about the lamp cares which way round the line is wired — the brightness is
 * taken from the size of the potential difference and not its sign. Giving them a red
 * and a blue would be telling the player a polarity that does not exist.
 *
 * <h2>The button</h2>
 *
 * Three more pairs stand behind them, up the other two sides of the barrel and in
 * three tiers: P1 and P2 are the first pole, P3 and P4 the second, P5 and P6 the
 * third. A pole is a plain pair — a contact that is open or closed between its own
 * two terminals — rather than a common riding over studs the way a rotary switch's
 * section is, because a button has one thing to say and two states to say it in, and
 * a transfer contact would be three terminals to wire where two will do.
 *
 * The three do the same thing at the same instant, because they are one plunger with
 * a stack of contact blocks under it. That is what the stack is for: one press to
 * break a hold circuit and make two others, which is the whole of a motor start
 * station. Whether pressing does anything at all is {@link IndicatorAction}'s, and
 * the contacts are on the unit either way — a lamp that is not a button simply never
 * closes them, and they are still there to be wired through later.
 *
 * Each pole is given a colour of its own, shared by its two ends, so that a rack of
 * six contacts a pixel and a half across can still be read: the pair that lights up
 * the same colour is the pair that makes together.
 */
public final class IndicatorLamp {

    private static final String TERMINAL_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator.terminal.";

    /** Terminal indices of the lamp, and the order its contacts are listed in. */
    public static final int TERMINAL_A = 0;
    public static final int TERMINAL_B = 1;

    /** How many pairs of button contacts stand behind the lamp. */
    public static final int POLES = 3;

    /** The first button terminal; the poles run on from here, two apiece. */
    public static final int FIRST_POLE_TERMINAL = 2;

    public static final int TERMINALS = FIRST_POLE_TERMINAL + 2 * POLES;

    /**
     * Resistance of a closed pole, in ohms. The same as a rotary switch's made
     * contact, because it is the same thing: two pieces of metal touching.
     */
    public static final float CONTACT_RESISTANCE = 0.01f;

    // These mirror the authored models and have to be updated with them.

    /** The barrel, standing in the cell on the far side of the panel. */
    public static final double[] BARREL = { 7.625, -4, 7.625, 8.375, 0, 8.375 };

    /**
     * How far a pressed cap sinks into the panel, in authored pixels. Deep enough to
     * read as down at arm's length, shallow enough that the cap does not disappear
     * into a panel it is only half a pixel proud of.
     */
    public static final double PRESS_DEPTH = 0.25;

    /** Which way a contact stands off the barrel, and so which way its box grows. */
    private static final int X = 0;
    private static final int Z = 2;

    /**
     * One stud on the rack: the box the model draws it as, the axis it stands off
     * the barrel along, and which way along that axis.
     *
     * The axis is carried rather than worked out, because both of the things derived
     * from it — the grown box a click is resolved against and the point a wire lands
     * on — have to agree about which face of the stub is its outward one, and a rule
     * that guessed would guess differently for the lamp's contacts than for the
     * button's.
     */
    private record Stud(double[] box, int axis, int sign) {

        /**
         * The box as it can be picked: the drawn stub, reached further out along its
         * own axis and padded a little up and down.
         *
         * Grown for the reason the rotary switch's contacts are: an authored contact
         * stands an eighth of a pixel proud of the barrel, and Power Grid resolves a
         * terminal from the precise spot clicked, so the drawn stub is a target
         * nobody can hit.
         *
         * It grows outwards only, and only along its own axis. Eight contacts share a
         * barrel three quarters of a pixel across — two on each side, in three tiers
         * up the back — so a box padded all round the way the two lamp contacts once
         * were would overlap the button contact beside it and the tier above it, and
         * a click in the overlap would attach a wire to whichever of the two Power
         * Grid happened to test first.
         */
        double[] grown() {
            double[] grown = box.clone();
            grown[axis + (sign < 0 ? 0 : 3)] += sign * REACH;
            grown[1] -= PAD_Y;
            grown[4] += PAD_Y;
            return grown;
        }

        /**
         * Where a wire should visually land: the centre of the stub's outward face,
         * as the model draws it.
         *
         * Stated rather than left to Power Grid because a TerminalBoundingBox takes
         * its origin from the midpoint of the box it was given, and {@link #grown}
         * reaches out on one side only — which would drag the attachment point half
         * of {@link #REACH} off the contact and hang the wire in open air beside it.
         */
        double[] origin() {
            double[] origin = { mid(0), mid(1), mid(2) };
            origin[axis] = box[axis + (sign < 0 ? 0 : 3)];
            return origin;
        }

        private double mid(int axis) {
            return (box[axis] + box[axis + 3]) / 2;
        }
    }

    /**
     * How far a contact box reaches out past the drawn stub, in authored pixels.
     *
     * Smaller than the rotary switch's because the whole unit is one pixel across.
     * Half a pixel each side would put two units two pixels apart on a panel where
     * the drawn ones are one, which would be a footprint made of clickable air rather
     * than of indicator.
     */
    private static final double REACH = 0.375;

    /**
     * How far a contact box reaches above and below the drawn stub.
     *
     * A twenty-fifth of a pixel, which is what there is: the three tiers of button
     * contacts stand a tenth of a pixel apart, so anything from a twentieth up would
     * have the first pole's box and the second's meeting somewhere behind the panel.
     */
    private static final double PAD_Y = 0.04;

    /** Every contact in terminal order: the lamp's two, then the three poles. */
    private static final Stud[] STUDS = {
            new Stud(new double[] { 7.5, -3.75, 7.75, 7.75, -3.25, 8.25 }, X, -1),
            new Stud(new double[] { 8.25, -3.75, 7.75, 8.5, -3.25, 8.25 }, X, +1),

            new Stud(new double[] { 7.75, -3.75, 7.5, 8.25, -3.25, 7.75 }, Z, -1),
            new Stud(new double[] { 7.75, -3.75, 8.25, 8.25, -3.25, 8.5 }, Z, +1),

            new Stud(new double[] { 7.75, -3.15, 7.5, 8.25, -2.65, 7.75 }, Z, -1),
            new Stud(new double[] { 7.75, -3.15, 8.25, 8.25, -2.65, 8.5 }, Z, +1),

            new Stud(new double[] { 7.75, -2.55, 7.5, 8.25, -2.05, 7.75 }, Z, -1),
            new Stud(new double[] { 7.75, -2.55, 8.25, 8.25, -2.05, 8.5 }, Z, +1) };

    /** Every contact as it can be picked, in terminal order. */
    public static final double[][] CONTACT_BOXES = grownBoxes();

    /** Where a wire lands on each, in the same order. */
    public static final double[][] CONTACT_ORIGINS = contactOrigins();

    public static final Component[] CONTACT_NAMES = contactNames();

    /**
     * What each terminal is wired in. The lamp's pair is grey because it has no
     * polarity to state; each pole takes a colour of its own, both ends alike, so
     * that the pair which makes together is the pair which looks alike.
     */
    private static final int[] CONTACT_COLORS = {
            IDecoratedTerminal.GRAY, IDecoratedTerminal.GRAY,
            IDecoratedTerminal.GREEN, IDecoratedTerminal.GREEN,
            IDecoratedTerminal.BLUE, IDecoratedTerminal.BLUE,
            IDecoratedTerminal.RED, IDecoratedTerminal.RED };

    /**
     * Everything the unit really occupies: the cap in its own cell, the barrel
     * through the panel, and all eight contacts as grown above so that two units side
     * by side cannot have overlapping places to click.
     *
     * Nothing outside the cap moves, so unlike the rotary switch there is almost no
     * swept volume to declare — a pressed cap travels a quarter of a pixel further
     * into the panel, which is inside the barrel's own box. The whole of it fits in a
     * square one and three quarter pixels across about the mounting axis, on both
     * sizes: the large cap is wider than the medium one but still narrower than the
     * grown contacts behind it, which is what keeps these the one thing in the mod
     * that can be packed several to a face.
     */
    private static final Map<IndicatorSize, ControlFootprint> FOOTPRINTS = footprints();

    /** Everything the crosshair may pick, in the order the outline is built. */
    private static final Map<IndicatorSize, double[][]> OUTLINES = outlines();

    private IndicatorLamp() {}

    public static ControlFootprint footprint(IndicatorSize size) {
        return FOOTPRINTS.get(size);
    }

    public static double[][] outlineBoxes(IndicatorSize size) {
        return OUTLINES.get(size);
    }

    /** Terminal index of one end of one pole; {@code end} is 0 or 1. */
    public static int poleTerminal(int pole, int end) {
        return FIRST_POLE_TERMINAL + 2 * pole + end;
    }

    /**
     * The contacts as a mounted unit presents them.
     *
     * Built afresh on each call, never cached, for the reason
     * {@link com.nectariich.create_flippies.foundation.control.ControlType#terminals}
     * gives: a TerminalBoundingBox mutates itself when it is given an origin or a
     * colour, so a shared instance would be rewritten by the next unit mounted the
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

    /** What colour a terminal is wired in, for the block form's own collection. */
    public static int contactColor(int terminal) {
        return CONTACT_COLORS[terminal];
    }

    private static double[][] grownBoxes() {
        double[][] boxes = new double[STUDS.length][];
        for (int i = 0; i < STUDS.length; i++)
            boxes[i] = STUDS[i].grown();
        return boxes;
    }

    private static double[][] contactOrigins() {
        double[][] origins = new double[STUDS.length][];
        for (int i = 0; i < STUDS.length; i++)
            origins[i] = STUDS[i].origin();
        return origins;
    }

    /** {@code a}, {@code b}, then {@code p1} through {@code p6}, in terminal order. */
    private static Component[] contactNames() {
        Component[] names = new Component[TERMINALS];
        names[TERMINAL_A] = Component.translatable(TERMINAL_PREFIX + "a");
        names[TERMINAL_B] = Component.translatable(TERMINAL_PREFIX + "b");
        for (int pole = 0; pole < POLES; pole++)
            for (int end = 0; end < 2; end++) {
                int terminal = poleTerminal(pole, end);
                names[terminal] = Component.translatable(TERMINAL_PREFIX + "p" + (terminal - FIRST_POLE_TERMINAL + 1),
                        pole + 1);
            }
        return names;
    }

    private static Map<IndicatorSize, ControlFootprint> footprints() {
        Map<IndicatorSize, ControlFootprint> footprints = new EnumMap<>(IndicatorSize.class);
        for (IndicatorSize size : IndicatorSize.values())
            footprints.put(size, ControlFootprint.of(size.lens(), BARREL).plus(CONTACT_BOXES));
        return Collections.unmodifiableMap(footprints);
    }

    private static Map<IndicatorSize, double[][]> outlines() {
        Map<IndicatorSize, double[][]> outlines = new EnumMap<>(IndicatorSize.class);
        for (IndicatorSize size : IndicatorSize.values()) {
            double[][] boxes = new double[2 + CONTACT_BOXES.length][];
            boxes[0] = size.lens();
            boxes[1] = BARREL;
            System.arraycopy(CONTACT_BOXES, 0, boxes, 2, CONTACT_BOXES.length);
            outlines.put(size, boxes);
        }
        return Collections.unmodifiableMap(outlines);
    }
}
