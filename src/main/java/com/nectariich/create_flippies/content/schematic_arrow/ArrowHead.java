package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Locale;

/**
 * What one end of a schematic arrow finishes in.
 *
 * The tip of a head is the point the player clicked, so an arrow drawn to the edge
 * of a symbol points exactly at that edge; the shaft is shortened to make room for
 * the head rather than the head being added past the end.
 */
public enum ArrowHead {

    /** A square end: a plain bus strip, or a branch butted into another strip. */
    NONE,

    /** A solid triangle. */
    TRIANGLE,

    /** An open V of the shaft's own thickness. */
    CHEVRON;

    public ArrowHead next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Never throws, so a damaged tag reads as a plain end. */
    public static ArrowHead byIndex(int index) {
        return index >= 0 && index < values().length ? values()[index] : NONE;
    }
}
