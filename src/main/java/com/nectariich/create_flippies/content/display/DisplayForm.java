package com.nectariich.create_flippies.content.display;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * The body an alphanumeric display is made in: how it is framed, how big its glass
 * is, and which authored export it is drawn from.
 *
 * One constant per export. A form is what the device physically <em>is</em> — a
 * screen of a particular size, bordered or not — so it cannot be per-unit state for
 * the reason {@link com.nectariich.create_flippies.content.indicator.IndicatorSize}
 * gives about caps: the glass is the footprint, and a footprint that changed under a
 * mounted control would be a control nobody had checked against its neighbours. It
 * is therefore half of what a block is, with the rated supply as the other half —
 * see {@link DisplayKind}.
 *
 * Everything behind the panel is deliberately not here. Every display is the same
 * barrel and the same rack of pins whatever its glass, the way every indicator is
 * the same barrel whichever cap is fitted, so the contacts are
 * {@link DisplayPins}'s and are written once. A later form that really does rewire
 * itself would move them here; a form that is only a bigger window must not, or two
 * displays would have two places saying where VCC is.
 *
 * The numbers mirror the authored exports and have to be updated with them.
 * {@code python tools/gen_display.py} prints the boxes it derives from each export,
 * so a re-export that moved the glass shows up as that output disagreeing with this
 * file rather than as a screen drawn where nothing can be clicked.
 */
public enum DisplayForm {

    /**
     * Borderless, one and a half pixels square: glass edge to edge with nothing
     * round it, so there is no bezel to hide an overrun and everything that does not
     * fit has to be cut — see {@link DisplayPrint}.
     */
    BRDRL_1P5_SQ("brdrl_1p5_sq", 1.5, 1.5, 0.15, 0.1, null),
    BRDRL_1X1P5("brdrl_1x1p5", 1.5, 1, 0.15, 0.1, null),
    BRDRL_1X2("brdrl_1x2", 2, 1, 0.15, 0.1, null),
    BRDRL_2X3("brdrl_2x3", 3, 2, 0.15, 0.1, null),
    BRDRL_3X4("brdrl_3x4", 4, 3, 0.15, 0.1, null),
    BRDRL_3X5("brdrl_3x5", 5, 3, 0.15, 0.1, null),
    BRDRL_4X5("brdrl_4x5", 5, 4, 0.15, 0.1, null),
    BRDRL_4X6("brdrl_4x6", 6, 4, 0.15, 0.1, null),
    BRDRL_5X8("brdrl_5x8", 8, 5, 0.15, 0.1, null),
    BRDRL_4X10("brdrl_4x10", 10, 4, 0.15, 0.1, null),
    BRDRL_6X10("brdrl_6x10", 10, 6, 0.15, 0.1, null),
    BRDRL_8X12("brdrl_8x12", 12, 8, 0.15, 0.1, null),
    BRDRL_8X15("brdrl_8x15", 15, 8, 0.15, 0.1, null),

    /** Bordered: the same glass with a quarter-pixel bezel standing round it. */
    BRD_1P5_SQ("brd_1p5_sq", 1.5, 1.5, 0.15, 0.1, new double[] { 7, 0, 7, 9, 0.25, 9 }),
    BRD_1X1P5("brd_1x1p5", 1.5, 1, 0.15, 0.1, new double[] { 7, 0, 7.25, 9, 0.25, 8.75 }),
    BRD_1X2("brd_1x2", 2, 1, 0.15, 0.1, new double[] { 6.75, 0, 7.25, 9.25, 0.25, 8.75 }),
    BRD_2X3("brd_2x3", 3, 2, 0.15, 0.1,
            new double[] { 6.25, 0, 6.75, 9.75, 0.25, 9.25 }),
    BRD_3X4("brd_3x4", 4, 3, 0.15, 0.1,
            new double[] { 5.75, 0, 6.25, 10.25, 0.25, 9.75 }),
    BRD_3X5("brd_3x5", 5, 3, 0.15, 0.1,
            new double[] { 5.25, 0, 6.25, 10.75, 0.25, 9.75 }),
    BRD_4X5("brd_4x5", 5, 4, 0.15, 0.1,
            new double[] { 5.25, 0, 5.75, 10.75, 0.25, 10.25 }),
    BRD_4X6("brd_4x6", 6, 4, 0.15, 0.1,
            new double[] { 4.75, 0, 5.75, 11.25, 0.25, 10.25 }),
    BRD_5X8("brd_5x8", 8, 5, 0.15, 0.1,
            new double[] { 3.75, 0, 5.25, 12.25, 0.25, 10.75 }),
    BRD_4X10("brd_4x10", 10, 4, 0.15, 0.1,
            new double[] { 2.75, 0, 5.75, 13.25, 0.25, 10.25 }),
    BRD_6X10("brd_6x10", 10, 6, 0.15, 0.1,
            new double[] { 2.75, 0, 4.75, 13.25, 0.25, 11.25 }),
    BRD_8X12("brd_8x12", 12, 8, 0.15, 0.1,
            new double[] { 1.75, 0, 3.75, 14.25, 0.25, 12.25 }),
    BRD_8X15("brd_8x15", 15, 8, 0.15, 0.1,
            new double[] { 0.25, 0, 3.75, 15.75, 0.25, 12.25 }),

    /**
     * A bezel with a light shade over the top edge, which is what makes the front
     * deeper than the glass on one side only. Short and long are how far it reaches.
     */
    LIGHT_SHADE_SHORT_1P5_SQ("light_shade_short_1p5_sq", 1.5, 1.5, 0.15, 0.1, new double[] { 7, 0, 7, 9, 0.5, 9 }),
    LIGHT_SHADE_SHORT_1X1P5("light_shade_short_1x1p5", 1.5, 1, 0.15, 0.1,
            new double[] { 7, 0, 7.25, 9, 0.5, 8.75 }),
    LIGHT_SHADE_SHORT_1X2("light_shade_short_1x2", 2, 1, 0.15, 0.1,
            new double[] { 6.75, 0, 7.25, 9.25, 0.5, 8.75 }),
    LIGHT_SHADE_SHORT_2X3("light_shade_short_2x3", 3, 2, 0.15, 0.1,
            new double[] { 6.25, 0, 6.75, 9.75, 0.5, 9.25 }),
    LIGHT_SHADE_SHORT_3X4("light_shade_short_3x4", 4, 3, 0.15, 0.1,
            new double[] { 5.75, 0, 6.25, 10.25, 0.75, 9.75 }),
    LIGHT_SHADE_SHORT_3X5("light_shade_short_3x5", 5, 3, 0.15, 0.1,
            new double[] { 5.25, 0, 6.25, 10.75, 0.75, 9.75 }),
    LIGHT_SHADE_SHORT_4X5("light_shade_short_4x5", 5, 4, 0.15, 0.1,
            new double[] { 5.25, 0, 5.75, 10.75, 1, 10.25 }),
    LIGHT_SHADE_SHORT_4X6("light_shade_short_4x6", 6, 4, 0.15, 0.1,
            new double[] { 4.75, 0, 5.75, 11.25, 1, 10.25 }),
    LIGHT_SHADE_SHORT_5X8("light_shade_short_5x8", 8, 5, 0.15, 0.1,
            new double[] { 3.75, 0, 5.25, 12.25, 1.25, 10.75 }),
    LIGHT_SHADE_SHORT_4X10("light_shade_short_4x10", 10, 4, 0.15, 0.1,
            new double[] { 2.75, 0, 5.75, 13.25, 1, 10.25 }),
    LIGHT_SHADE_SHORT_6X10("light_shade_short_6x10", 10, 6, 0.15, 0.1,
            new double[] { 2.75, 0, 4.75, 13.25, 1.5, 11.25 }),
    LIGHT_SHADE_SHORT_8X12("light_shade_short_8x12", 12, 8, 0.15, 0.1,
            new double[] { 1.75, 0, 3.75, 14.25, 2, 12.25 }),
    LIGHT_SHADE_SHORT_8X15("light_shade_short_8x15", 15, 8, 0.15, 0.1,
            new double[] { 0.25, 0, 3.75, 15.75, 2, 12.25 }),
    LIGHT_SHADE_LONG_1P5_SQ("light_shade_long_1p5_sq", 1.5, 1.5, 0.15, 0.1, new double[] { 7, 0, 7, 9, 0.75, 9 }),
    LIGHT_SHADE_LONG_1X1P5("light_shade_long_1x1p5", 1.5, 1, 0.15, 0.1,
            new double[] { 7, 0, 7.25, 9, 0.75, 8.75 }),
    LIGHT_SHADE_LONG_1X2("light_shade_long_1x2", 2, 1, 0.15, 0.1,
            new double[] { 6.75, 0, 7.25, 9.25, 0.75, 8.75 }),
    LIGHT_SHADE_LONG_2X3("light_shade_long_2x3", 3, 2, 0.15, 0.1,
            new double[] { 6.25, 0, 6.75, 9.75, 1, 9.25 }),
    LIGHT_SHADE_LONG_3X4("light_shade_long_3x4", 4, 3, 0.15, 0.1,
            new double[] { 5.75, 0, 6.25, 10.25, 1.5, 9.75 }),
    LIGHT_SHADE_LONG_3X5("light_shade_long_3x5", 5, 3, 0.15, 0.1,
            new double[] { 5.25, 0, 6.25, 10.75, 1.5, 9.75 }),
    LIGHT_SHADE_LONG_4X5("light_shade_long_4x5", 5, 4, 0.15, 0.1,
            new double[] { 5.25, 0, 5.75, 10.75, 2, 10.25 }),
    LIGHT_SHADE_LONG_4X6("light_shade_long_4x6", 6, 4, 0.15, 0.1,
            new double[] { 4.75, 0, 5.75, 11.25, 2, 10.25 }),
    LIGHT_SHADE_LONG_5X8("light_shade_long_5x8", 8, 5, 0.15, 0.1,
            new double[] { 3.75, 0, 5.25, 12.25, 2.5, 10.75 }),
    LIGHT_SHADE_LONG_4X10("light_shade_long_4x10", 10, 4, 0.15, 0.1,
            new double[] { 2.75, 0, 5.75, 13.25, 2, 10.25 }),
    LIGHT_SHADE_LONG_6X10("light_shade_long_6x10", 10, 6, 0.15, 0.1,
            new double[] { 2.75, 0, 4.75, 13.25, 3, 11.25 }),
    LIGHT_SHADE_LONG_8X12("light_shade_long_8x12", 12, 8, 0.15, 0.1,
            new double[] { 1.75, 0, 3.75, 14.25, 4, 12.25 }),
    LIGHT_SHADE_LONG_8X15("light_shade_long_8x15", 15, 8, 0.15, 0.1,
            new double[] { 0.25, 0, 3.75, 15.75, 4, 12.25 });

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alphanum_display.form.";

    /** The mounting axis, which every display is centred on. */
    private static final double MIDDLE = 8.0;

    private final String id;
    private final double width;
    private final double height;
    private final double proud;
    private final double padding;
    private final double[] glass;
    private final double[] front;

    /** {@code front} is null when nothing but the glass stands in front of the panel. */
    DisplayForm(String id, double width, double height, double proud, double padding, double[] front) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.proud = proud;
        this.padding = padding;
        this.glass = new double[] {
                MIDDLE - width / 2, 0, MIDDLE - height / 2,
                MIDDLE + width / 2, proud, MIDDLE + height / 2 };
        this.front = front == null ? glass : front;
    }

    /** This form's half of the block ids and of the model names, and what a save holds. */
    public String id() {
        return id;
    }

    /** The partial the glass is drawn from, written by {@code tools/gen_display.py}. */
    public String screenModel() {
        return "block/oled_display_" + id + "_screen";
    }

    /** The partial the barrel and the pins are drawn from. */
    public String bodyModel() {
        return "block/oled_display_" + id + "_body";
    }

    /** The glass as an authored pixel box in the {@code SurfaceMount} frame. */
    public double[] glass() {
        return glass;
    }

    /**
     * Everything inside the device's own cell, bezel and shade included, as one box:
     * the footprint, and the part a click lands on. The glass itself on a borderless
     * form.
     */
    public double[] front() {
        return front;
    }

    /** How far across the glass is along x, the way an upright line runs, in authored pixels. */
    public double width() {
        return width;
    }

    /** How far the glass is along z, across an upright line, in authored pixels. */
    public double height() {
        return height;
    }

    /** How far the glass stands proud of the panel, in authored pixels. */
    public double proud() {
        return proud;
    }

    /**
     * Kept clear round the edge of the glass, in authored pixels.
     *
     * On a borderless display this is the whole of what stops a legend running off
     * the front of the device, since there is no moulded frame for it to run under.
     * Proportionally what {@link com.nectariich.create_flippies.content.label.PanelLabel#TEXT_INSET}
     * is on a label and what {@code IndicatorLegend}'s inset is on a cap, which is
     * where the number comes from.
     */
    public double padding() {
        return padding;
    }

    /**
     * How much of the glass a line may run along, in authored pixels, at that many
     * quarter turns of the lettering. A turn exchanges the two spans.
     */
    public double usableAlong(int quarters) {
        return Math.max(0, (quarters % 2 == 0 ? width : height) - 2 * padding);
    }

    /** How much of the glass a line may stand across, the cap on its height. */
    public double usableAcross(int quarters) {
        return usableAlong(quarters + 1);
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + id);
    }

    /** The form saved under this id, or {@code fallback} if it is unknown. */
    public static DisplayForm of(String id, DisplayForm fallback) {
        for (DisplayForm form : values())
            if (form.id.equals(id))
                return form;
        return fallback;
    }
}
