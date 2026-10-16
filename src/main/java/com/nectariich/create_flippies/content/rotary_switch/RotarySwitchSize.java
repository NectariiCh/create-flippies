package com.nectariich.create_flippies.content.rotary_switch;

import java.util.List;
import java.util.Locale;

/**
 * How big a rotary switch's front is, and so which set of authored parts it is
 * built from.
 *
 * Only the front differs. Every switch hangs the same neck and the same contact
 * rack through the panel, with its studs in the same places — the medium and large
 * models draw them from identical boxes — so size changes what the switch looks
 * like from the front of the panel and what room it needs there, and nothing about
 * how it is wired from behind.
 *
 * Size is part of the {@link RotarySwitchKind} rather than a state a wrench can
 * set, for the same reason the detent count is: the front is the footprint a
 * mounted switch was fitted into, and a switch that grew a bezel in place could
 * find itself overlapping the neighbour it was screwed in beside.
 *
 * The styles are the paints and handles authored at this size. They are stated
 * here rather than taken from {@link RotarySwitchStyle} wholesale because the
 * wrench menu cycles them: offering a paint that has no model behind it would put
 * the missing-texture check on the panel and leave no way back except cycling past
 * it.
 */
public enum RotarySwitchSize {

    /**
     * The four-pixel switch: a bare plate and a pointer handle whose arm swings a
     * quarter pixel past the plate's own edge.
     */
    MED(new double[] { 6, 0, 6, 10, 1.75, 10 }, 8.25, 10.25, List.of(RotarySwitchStyle.values())),

    /**
     * The four-and-a-half-pixel switch: a bezel around the plate and a bar handle
     * standing proud of it. The bar is symmetric about the axis and stays well
     * inside the bezel, so here it is the bezel that sets the room needed.
     */
    LARGE(new double[] { 5.75, 0, 5.75, 10.25, 2.5, 10.25 }, 8.25, 9.25, List.of(RotarySwitchStyle.A));

    /** The mounting axis every switch is drawn and turned about. */
    private static final double AXIS = 8;

    private final double[] front;
    private final double sweepRadius;
    private final List<RotarySwitchStyle> styles;

    RotarySwitchSize(double[] front, double armCornerX, double armCornerZ, List<RotarySwitchStyle> styles) {
        this.front = front;
        this.sweepRadius = Math.max(Math.hypot(armCornerX - AXIS, armCornerZ - AXIS),
                (front[3] - front[0]) / 2);
        this.styles = styles;
    }

    /** This size's half of the model and texture names, and of the block ids. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Everything in front of the panel: plate, bezel and handle, as authored. */
    public double[] front() {
        return front;
    }

    /**
     * Half the width of the square the front needs, about the mounting axis.
     *
     * The handle turns 45 degrees at every detent, so what a neighbour has to stay
     * clear of is the disc the handle sweeps and not the arm's resting pose. This is
     * the larger of that disc's radius — taken from the arm's furthest corner — and
     * the front's own half-width, and a square of it about the axis is exactly
     * invariant under the quarter turns a mount may apply.
     */
    public double sweepRadius() {
        return sweepRadius;
    }

    /** Top of the front, and so how far into the room before the panel it reaches. */
    public double frontHeight() {
        return front[4];
    }

    /** The paints and handles authored at this size, in the order a wrench cycles them. */
    public List<RotarySwitchStyle> styles() {
        return styles;
    }

    /** Stem of every model path at this size: bodies, handles and the item merges. */
    public String modelStem() {
        return "block/rotary-switch-" + id() + "-";
    }

    /** The style this is placed with, and what an unauthored one falls back to. */
    public RotarySwitchStyle defaultStyle() {
        return styles.get(0);
    }

    /** The given style if this size has it authored, otherwise the default. */
    public RotarySwitchStyle clamp(RotarySwitchStyle style) {
        return styles.contains(style) ? style : defaultStyle();
    }

    /** The next style this size has, wrapping — one step of a wrench menu button. */
    public RotarySwitchStyle next(RotarySwitchStyle style) {
        int index = styles.indexOf(style);
        return index < 0 ? defaultStyle() : styles.get((index + 1) % styles.size());
    }
}
