package com.nectariich.create_flippies.content.indicator;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * How big the cap on the front of an indicator is.
 *
 * The only thing that differs between the two is the lens: the barrel, the two
 * lamp contacts and the three pairs of button contacts behind the panel are the
 * same piece of gear on both, because they are what the unit is rather than what it
 * looks like. So this states the lens box and nothing else, and everything else
 * about an indicator is written once.
 *
 * It cannot be per-unit state the way the colour and the legend are, for the reason
 * {@link com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSize}
 * gives about switch fronts: the lens is the footprint, and a footprint that
 * changed under a mounted control would be a control that had never been checked
 * against its neighbours at the size it is now. So it is half of what a block is,
 * with the rating as the other half — see {@link IndicatorKind}.
 *
 * The numbers mirror the authored models and have to be updated with them. Both are
 * centred on the mounting axis, which is what lets one set of contacts serve both.
 */
public enum IndicatorSize {

    /** The one-pixel cap, and the only size there was. */
    MED("med", new double[] { 7.5, 0, 7.5, 8.5, 0.5, 8.5 }),

    /** Half again across and half again proud of the panel. */
    LARGE("large", new double[] { 7.25, 0, 7.25, 8.75, 0.75, 8.75 });

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".indicator.size.";

    private final String id;
    private final double[] lens;

    IndicatorSize(String id, double[] lens) {
        this.id = id;
        this.lens = lens;
    }

    /** This size's half of the model names and of the block ids, and what a save holds. */
    public String id() {
        return id;
    }

    /**
     * The cap as an authored pixel box in the {@code SurfaceMount} frame: the only
     * part of the unit inside its own cell, and the only part a click can operate.
     */
    public double[] lens() {
        return lens;
    }

    /** How far the cap stands proud of the panel, in authored pixels. */
    public double capHeight() {
        return lens[4];
    }

    /** How far across the cap is, in authored pixels; square, so one number does. */
    public double capWidth() {
        return lens[3] - lens[0];
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + id);
    }

    /** The size saved under this id, or {@code fallback} if it is unknown. */
    public static IndicatorSize of(String id, IndicatorSize fallback) {
        for (IndicatorSize size : values())
            if (size.id.equals(id))
                return size;
        return fallback;
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
