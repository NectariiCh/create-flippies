package com.nectariich.create_flippies.content.screwdriver;

import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * One corner of a rectangle the player has picked but not yet completed: a point on
 * a named surface, snapped to the pixel.
 *
 * It is a point on a surface rather than a point on a block face, and the
 * difference matters twice over. A grid may run across many blocks, so the second
 * corner is routinely clicked on a different block from the first; what the two
 * clicks have to agree about is the plane and the direction it faces, not the
 * block. And a chamfer has no block face to name at all, so a corner that could
 * only be stated as one could not be picked on it.
 *
 * <h2>Why the unit is finer than the snap</h2>
 *
 * The coordinates are in {@link PanelPlane#UNITS_PER_BLOCK}ths along the surface's
 * own axes, the same lattice a panel's offset and a control's anchor use, even
 * though the ordinary snap only ever lands on whole pixels. That is not slack: a
 * corner snapped inside a grid cell is counted from the cell's own edge, and a
 * cell's edge is routinely not on a pixel line — a sixteen-pixel face ruled into
 * three has them at thirds. Holding the corner in pixels would have rounded every
 * such corner back onto the block's lattice, which is exactly the thing the player
 * was trying to get away from.
 *
 * Whole steps of the chosen lattice are still what a rectangle <em>spans</em>,
 * because both corners of one are counted from the same origin and the origin
 * cancels; see {@link #snappedWithin}.
 *
 * <h2>Which lattice</h2>
 *
 * The step is the caller's, because the two things drawn corner to corner want
 * different ones. A constraining grid is ruled in whole pixels and can hold nothing
 * finer, so it snaps to {@link PanelPlane#UNITS_PER_PIXEL}. A control drawn to size
 * is built in {@link SpannedControlType#UNITS_PER_SPAN} steps of half a pixel, which
 * is four times as many rectangles for the same two clicks and is the resolution a
 * legend is actually lined up at.
 */
public record GridCorner(PanelPlane plane, int u, int v) {

    private static final String U = "U";
    private static final String V = "V";

    /**
     * The corner a click at {@code point} on that surface picks: the nearest pixel
     * line, in both axes, counted from the surface's own zero.
     *
     * Rounding rather than flooring is what makes the tool feel like it snaps.
     * Flooring would put the corner at the low edge of whichever pixel was clicked,
     * so a rectangle drawn between the middles of two pixels would come out a pixel
     * short at the far end and the player would have to aim at lines they cannot
     * see.
     */
    public static GridCorner snapped(PanelPlane plane, Vec3 point) {
        return snapped(plane, point, PanelPlane.UNITS_PER_PIXEL);
    }

    /** The same on a lattice of the given pitch, in plane units; see the class comment. */
    public static GridCorner snapped(PanelPlane plane, Vec3 point, int step) {
        return snappedWithin(plane, point, 0, 0, step);
    }

    /**
     * The same, with the lattice of pixel lines counted from a given origin on the
     * surface rather than from the surface's zero.
     *
     * What that origin is for: a legend drawn inside a ruled cell is being sized
     * against the cell, not against the wall behind it, and the cell's edges are
     * wherever the ruling put them. Counted from the block's own lattice the label
     * could not be made flush with the cell it names, nor inset from it by a clean
     * pixel, because the lines it could reach were a fraction of a pixel off the
     * lines that mattered.
     *
     * The pitch is one step of the caller's lattice, and staying on a lattice at all
     * is a requirement rather than a choice: a spanned control's footprint is stated
     * in whole steps, so a snap finer than one could name rectangles no control could
     * be built to fill. Since both corners of a rectangle are counted from the same
     * origin, the span between them is a whole number of steps exactly, whatever
     * fraction the origin sits at.
     */
    public static GridCorner snappedWithin(PanelPlane plane, Vec3 point, double originU, double originV, int step) {
        return new GridCorner(plane, snap(plane.u(point), originU, step), snap(plane.v(point), originV, step));
    }

    private static int snap(double along, double origin, int step) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        int steps = (int) Math.round((along - origin) * scale / step);
        return (int) Math.round(origin * scale) + steps * step;
    }

    /**
     * The opposite corner of a rectangle whose first corner is already picked: a whole
     * number of pixels from it, along its own lattice.
     *
     * Measured from the corner rather than from anything on the surface because the
     * first corner is what the rectangle is being drawn from, and its phase is the one
     * that has to be kept. A legend routinely runs past the cell it was started in, and
     * the cell it ends over has a phase of its own — snapping the far corner to
     * <em>that</em> would make the two ends disagree by a fraction of a pixel, which is
     * a width no control can be built to.
     */
    public static GridCorner snappedFrom(GridCorner first, Vec3 point, int step) {
        PanelPlane plane = first.plane();
        return new GridCorner(plane, from(first.u(), plane.u(point), step), from(first.v(), plane.v(point), step));
    }

    private static int from(int origin, double along, int step) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        int steps = (int) Math.round((along - origin / scale) * scale / step);
        return origin + steps * step;
    }

    /** Whether another corner is on the same surface, so the two can span a rectangle. */
    public boolean sharesPlaneWith(GridCorner other) {
        return other.plane.equals(plane);
    }

    /**
     * This corner's u as whole pixels, which is the unit a stored grid is ruled in
     * and the unit a size is reported in.
     */
    public int pixelU() {
        return pixels(u);
    }

    public int pixelV() {
        return pixels(v);
    }

    /** A span between two corners, in whole pixels. */
    public static int pixels(int units) {
        return (int) Math.round(units / (double) PanelPlane.UNITS_PER_PIXEL);
    }

    /** A span between two corners, in the steps a control drawn to size is built in. */
    public static int spans(int units) {
        return (int) Math.round(units / (double) SpannedControlType.UNITS_PER_SPAN);
    }

    /** This corner in the world, lifted {@code proud} blocks off the surface. */
    public Vec3 toVec(double proud) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        return plane.at(u / scale, v / scale, proud);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        plane.save(tag);
        tag.putInt(U, u);
        tag.putInt(V, v);
        return tag;
    }

    /** Null when the tag holds no corner, which is also how "none picked yet" reads. */
    public static GridCorner load(CompoundTag tag) {
        PanelPlane plane = PanelPlane.load(tag);
        if (plane == null || !tag.contains(U) || !tag.contains(V))
            return null;
        return new GridCorner(plane, tag.getInt(U), tag.getInt(V));
    }
}
