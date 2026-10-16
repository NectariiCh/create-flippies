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
    BRDRL_1P5_SQ("brdrl_1p5_sq", 1.5, 0.15, 0.1);

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alphanum_display.form.";

    /** The mounting axis, which every display is centred on. */
    private static final double MIDDLE = 8.0;

    private final String id;
    private final double width;
    private final double proud;
    private final double padding;
    private final double[] glass;

    DisplayForm(String id, double width, double proud, double padding) {
        this.id = id;
        this.width = width;
        this.proud = proud;
        this.padding = padding;
        this.glass = new double[] {
                MIDDLE - width / 2, 0, MIDDLE - width / 2,
                MIDDLE + width / 2, proud, MIDDLE + width / 2 };
    }

    /** This form's half of the block ids and of the model names, and what a save holds. */
    public String id() {
        return id;
    }

    /** The partial the glass is drawn from, written by {@code tools/gen_display.py}. */
    public String screenModel() {
        return "block/alphanum_display_" + id + "_screen";
    }

    /** The partial the barrel and the pins are drawn from. */
    public String bodyModel() {
        return "block/alphanum_display_" + id + "_body";
    }

    /**
     * The glass as an authored pixel box in the {@code SurfaceMount} frame: the only
     * part of the device inside its own cell, and the part a click lands on.
     */
    public double[] glass() {
        return glass;
    }

    /**
     * How far across the glass is, in authored pixels.
     *
     * One number because every form so far is square, which is also what makes a
     * quarter turn of the lettering cost nothing here — unlike on a label, where the
     * two spans differ and a turn exchanges them. A form that is not square needs a
     * second span and {@link DisplayPrint} needs to be told which of the two caps the
     * height; until one exists, a second number would be a guess at what it should be.
     */
    public double width() {
        return width;
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

    /** How much of the glass may be printed on, in authored pixels. */
    public double usable() {
        return Math.max(0, width - 2 * padding);
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
