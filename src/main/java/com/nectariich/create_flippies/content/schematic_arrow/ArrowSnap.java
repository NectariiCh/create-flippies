package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Locale;

/** How a clicked point of a schematic arrow is snapped. */
public enum ArrowSnap {

    /** To the half-pixel lattice of the panel, and each segment to a multiple of forty-five degrees. */
    GRID_ANGLE,

    /** To the half-pixel lattice of the panel only. */
    GRID,

    /** Nowhere, beyond the panel's own offset lattice. */
    FREE;

    public ArrowSnap next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ArrowSnap byIndex(int index) {
        return index >= 0 && index < values().length ? values()[index] : GRID_ANGLE;
    }
}
