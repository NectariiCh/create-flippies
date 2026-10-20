package com.nectariich.create_flippies.content.schematic_arrow;

import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

/**
 * Where a click on a panel puts the next point of a schematic arrow.
 *
 * Pure arithmetic on the surface's own coordinates, so the server placing the point
 * and the overlay previewing it agree to the unit.
 *
 * <h2>The lattice</h2>
 *
 * Half a pixel, counted from the panel's own corner: the pitch decals and labels are
 * lined up at, fine enough that a strip an odd number of pixels wide can still have
 * its edges on pixel lines, and counted from the panel so that a strip can be run
 * flush along an edge.
 *
 * <h2>The angle</h2>
 *
 * With angle snapping, a segment is turned to the nearest eighth of a turn from the
 * point before it and its length rounded to whole steps along that direction. On a
 * diagonal a step is one lattice step in each axis, so the end lands on the lattice
 * whenever the start was on it; the angle wins over the lattice only when the
 * previous point was placed free.
 */
public final class ArrowSnapping {

    /** The lattice, in offset units. */
    public static final int STEP = ScrewdriverActions.PAINT_STEP;

    private ArrowSnapping() {}

    /**
     * The point a click at {@code (u, v)}, in blocks, lands on, in offset units.
     *
     * @param previous the point before, in offset units, or null for the first point
     * @param originU  the panel's own corner, in blocks, which the lattice counts from
     */
    public static int[] snap(int[] previous, double u, double v, double originU, double originV, ArrowSnap snap) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        if (snap == ArrowSnap.FREE)
            return new int[] { (int) Math.round(u * scale), (int) Math.round(v * scale) };
        if (snap == ArrowSnap.GRID_ANGLE && previous != null)
            return angled(previous, u * scale, v * scale);
        return new int[] { lattice(u, originU), lattice(v, originV) };
    }

    private static int lattice(double along, double origin) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        int steps = (int) Math.round((along - origin) * scale / STEP);
        return (int) Math.round(origin * scale) + steps * STEP;
    }

    /** A point on the nearest of the eight directions from the previous point, whole steps along it. */
    static int[] angled(int[] previous, double u, double v) {
        double du = u - previous[0];
        double dv = v - previous[1];
        if (du == 0 && dv == 0)
            return previous.clone();
        int octant = Math.floorMod((int) Math.round(Math.atan2(dv, du) / (Math.PI / 4)), 8);
        int su = new int[] { 1, 1, 0, -1, -1, -1, 0, 1 }[octant];
        int sv = new int[] { 0, 1, 1, 1, 0, -1, -1, -1 }[octant];
        // The click's distance along that direction, counted in steps of the direction's
        // own: a lattice step per axis it moves along
        double along = (du * su + dv * sv) / (su * su + sv * sv);
        int steps = Math.max(0, (int) Math.round(along / STEP));
        return new int[] { previous[0] + su * steps * STEP, previous[1] + sv * steps * STEP };
    }
}
