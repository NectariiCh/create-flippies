package com.nectariich.create_flippies.content.panel;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.chat.Component;

/**
 * What an edit does to the panel under the crosshair.
 *
 * The three are one mode of the screwdriver rather than three, for the reason the
 * dynamic block gestures are: everything around them is shared — finding which panel
 * is meant, projecting the crosshair onto it, previewing the rectangle, and rebuilding
 * the cells afterwards — and what differs is only which rectangle a click names.
 *
 * They live here rather than beside the tool's other settings because what they
 * describe is a panel operation and not a tool one. The screwdriver picks one the way
 * it picks a cell ruling; {@link PanelEdit} is what actually knows what each means.
 */
public enum PanelEditKind {

    /**
     * One tile: the unit square of the panel's own lattice under the crosshair, which
     * on a straight panel is exactly one block of it and on a diagonal is one repeat
     * of the material — the same square {@link PanelTile} draws.
     *
     * A block rather than a pixel because this is the gesture for making a doorway or
     * a service opening, where what is wanted is a hole the size of the thing going
     * through it and edges that line up with the wall's own joints.
     */
    TILE,

    /**
     * A square hole of the tool's own size, in pixels, centred on the pixel under the
     * crosshair.
     *
     * Pixels rather than tiles because this is the gesture for a cable or a spindle
     * passing through a panel, which wants a hole a few pixels across wherever the
     * cable happens to run rather than a hole on the block grid.
     */
    HOLE,

    /**
     * The nearest edge of the panel, moved by the tool's size: out on a right click
     * and in on a left one.
     *
     * One edge at a time rather than a corner, because a panel is nearly always being
     * matched to something along one axis — a wall that has to reach the next pillar,
     * a run of desk a block too long. Which edge is decided by the crosshair, so the
     * same two buttons grow and shrink the panel in any of the four directions.
     */
    EDGE;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".screwdriver.edit.";

    /** Whether this kind is sized by the tool rather than by the panel's own lattice. */
    public boolean sized() {
        return this != TILE;
    }

    public PanelEditKind next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component label() {
        return Component.translatable(LANG_PREFIX + name().toLowerCase(Locale.ROOT));
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * What to say once a click of this kind has gone through, which is six messages
     * rather than three: each kind's two buttons do opposite things, and "tile
     * removed" and "tile put back" are the sort of thing worth being told apart when
     * the panel is behind you.
     */
    public String message(boolean primary) {
        return switch (this) {
            case TILE -> primary ? "tile_removed" : "tile_restored";
            case HOLE -> primary ? "hole_drilled" : "hole_filled";
            case EDGE -> primary ? "edge_extended" : "edge_shortened";
        };
    }

    /**
     * Whether the right button of this kind takes material off the panel, which is
     * the only thing left that still has to tell the two apart: it decides whether a
     * click sounds like something being put on or taken off.
     */
    public boolean subtracts(boolean primary) {
        return this == EDGE ? !primary : primary;
    }

    /** Guards a value off the wire, where the ordinal is whatever was sent. */
    public static PanelEditKind byOrdinal(int ordinal) {
        PanelEditKind[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : TILE;
    }
}
