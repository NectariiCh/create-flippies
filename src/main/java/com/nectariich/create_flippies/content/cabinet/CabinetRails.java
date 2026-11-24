package com.nectariich.create_flippies.content.cabinet;

import java.util.List;
import java.util.Locale;

/**
 * The rules for laying DIN rails on a cabinet's back plate, in the cabinet's model
 * pixels, with nothing of the world in them so that both sides and the tests can
 * ask them alike.
 *
 * <h2>Room</h2>
 *
 * There are no margins. A rail takes the strip it covers on the plate, its length
 * along and {@link #WIDTH} across, and that strip must lie on the back plate and
 * overlap no other rail's. Strips that only meet along an edge are fine: rails laid
 * side by side, end to end on one line, or a level rail butting into an upright one.
 * Gear clipped on may then overlap the gear on the next rail; that is left to the
 * player.
 */
public final class CabinetRails {

    /** A rail's width across, px: {@code DinRailBlock.WIDTH}, a 35 mm rail. */
    public static final double WIDTH = 3.5;
    /** Its strip either side of its centre line, px. */
    public static final double HALF = WIDTH / 2;
    /** The shortest rail, one module. */
    public static final int MIN_LENGTH = 1;
    /**
     * Most rails one cabinet carries. More than anyone lays by hand; it only bounds
     * what a forged packet can make a cabinet save and sync.
     */
    public static final int MAX_RAILS = 64;

    /** Why a rail cannot go where it was drawn. */
    public enum Problem {
        NONE, TOO_SHORT, OFF_PLATE, CROWDED, TOO_MANY;

        public boolean ok() {
            return this == NONE;
        }

        /** Its lang key under {@code message.create_flippies.cabinet_rail.}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private CabinetRails() {}

    /**
     * The strip a rail covers on the plate, model px, as {@code {y0, z0, y1, z1}}: its
     * length along, {@link #HALF} either side across.
     */
    public static double[] band(CabinetRail rail) {
        if (rail.vertical())
            return new double[] {rail.start(), rail.centre() - HALF, rail.end(), rail.centre() + HALF};
        return new double[] {rail.centre() - HALF, rail.start(), rail.centre() + HALF, rail.end()};
    }

    /** Whether {@code candidate} may go on a cabinet of {@code spec} carrying {@code rails}, and if not, why. */
    public static Problem validate(CabinetSpec spec, List<CabinetRail> rails, CabinetRail candidate) {
        if (rails.size() >= MAX_RAILS)
            return Problem.TOO_MANY;
        if (candidate.length() < MIN_LENGTH)
            return Problem.TOO_SHORT;
        CabinetGeometry.BackPlane plate = CabinetGeometry.backPlane(spec);
        double[] band = band(candidate);
        if (band[0] < plate.y0() || band[2] > plate.y1() || band[1] < plate.z0() || band[3] > plate.z1())
            return Problem.OFF_PLATE;
        for (CabinetRail other : rails) {
            double[] b = band(other);
            // Overlap with any area at all; meeting along an edge is allowed
            if (band[0] < b[2] && b[0] < band[2] && band[1] < b[3] && b[1] < band[3])
                return Problem.CROWDED;
        }
        return Problem.NONE;
    }

    /**
     * The rail drawn from one point on the back plate to another, model px
     * {@code (y, z)}, given {@code id}.
     *
     * The way the drag went furthest decides which way the rail runs: further up
     * or down than across makes it upright. The first point sets the line it runs
     * on, to the nearest whole pixel, so the rail starts where it was started rather
     * than wherever the drag ends up across. Along that line it covers every module
     * from the one the first point was in to the one the second was in, both
     * included — so a rail is never shorter than a module — cut to the back plate,
     * so dragging past a wall runs the rail up to that wall.
     *
     * Nothing else is checked; {@link #validate} says whether it can go.
     */
    public static CabinetRail fromTwoPoints(CabinetSpec spec, int id, double ay, double az, double by, double bz) {
        boolean vertical = Math.abs(by - ay) > Math.abs(bz - az);
        CabinetGeometry.BackPlane plate = CabinetGeometry.backPlane(spec);
        double a = vertical ? ay : az, b = vertical ? by : bz;
        double low = vertical ? plate.y0() : plate.z0(), high = vertical ? plate.y1() : plate.z1();
        int start = (int) Math.max(low, Math.floor(Math.min(a, b)));
        int end = (int) Math.min(high, Math.floor(Math.max(a, b)) + 1);
        int centre = (int) Math.round(vertical ? az : ay);
        return new CabinetRail(id, vertical, start, centre, Math.max(0, end - start));
    }

    /** The rail of {@code rails} with {@code id}, or null. */
    public static CabinetRail byId(List<CabinetRail> rails, int id) {
        for (CabinetRail rail : rails)
            if (rail.id() == id)
                return rail;
        return null;
    }
}
