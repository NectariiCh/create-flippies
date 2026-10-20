package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Locale;

/** How the outside of a bend in a schematic arrow is finished. */
public enum ArrowJoin {

    /** A sharp corner, which is what mimic strips on a real panel have. Very sharp bends fall back to a bevel. */
    MITER,

    /** The corner cut off square across the bend. */
    BEVEL,

    /** The corner rounded to the strip's half width. */
    ROUND;

    public ArrowJoin next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ArrowJoin byIndex(int index) {
        return index >= 0 && index < values().length ? values()[index] : MITER;
    }
}
