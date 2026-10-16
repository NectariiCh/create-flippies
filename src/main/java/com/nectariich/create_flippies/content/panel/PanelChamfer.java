package com.nectariich.create_flippies.content.panel;

import java.util.Locale;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The forty-five degree panel that fills the corner between two straight ones.
 *
 * The corner of an L-shaped room is the reason large panels can be turned at all, and
 * drawing one by hand is the one case where the two-corner gesture is bad at its job:
 * the whole point of a chamfer is that it meets the panel it springs from exactly, and
 * "exactly" is a property of a number the player cannot see, let alone aim at. So it
 * is derived instead, from the two panels it is to join.
 *
 * <h2>What is taken from which panel</h2>
 *
 * The first panel decides everything about where the chamfer <em>is</em>: its plane
 * passes through that panel's end edge, so the two front surfaces continue into one
 * another, and its height is that panel's height, so the corner is not a step. The
 * second panel decides only where the chamfer <em>stops</em> — it runs until it
 * reaches the plane standing perpendicular to that panel at its nearest end.
 *
 * That is deliberately not symmetrical, and it is not an approximation of something
 * symmetrical either. A forty-five degree line from a fixed edge will not in general
 * arrive at another fixed edge; asking for both is asking for a chamfer that is flush
 * at both ends, which usually has no solution. Naming which end is flush is what makes
 * the answer exist, and the player picks it by which panel they click first.
 *
 * <h2>Why the join is exact rather than close</h2>
 *
 * Because of how {@link PanelPlane} states an offset. A straight panel's surface sits
 * at a whole number of offset units on a world axis, its end edge sits at a whole
 * number along the panel, and the diagonal through that edge therefore has an offset
 * of their sum or difference — an integer, arrived at without rounding anything. The
 * seam between a chamfer and the panel it springs from is closed by arithmetic, not by
 * tolerance, which is what the whole lattice scheme was for.
 *
 * <h2>The flat case, which is a loft rather than a chamfer</h2>
 *
 * Two floor panels have the same problem and not the same answer. A walkway running
 * east and another running north want a diagonal piece between them exactly as two
 * walls do, but there is no corner between their <em>planes</em> to cut: they are the
 * same plane. What is at forty-five degrees is the piece itself, lying on that plane
 * with both of its axes turned — which is what {@link PanelPlane#isSpun} is for, and a
 * shape the panel walk and the model already draw, because a player can rule one
 * freehand with the yaw button held.
 *
 * So for two flat panels the roles change. Which way each one <em>runs</em> takes the
 * place of which way it faces: a strip runs along its longer side, the two must run
 * square to each other, and the piece between them heads off along the direction
 * bisecting them. The first panel still decides where the loft is — its width is the
 * shadow the first panel's end edge casts on the diagonal, so the loft's long sides
 * pass exactly through that edge's two corners — and the second still decides where it
 * stops.
 *
 * What cannot carry over is flushness at the ends. A vertical chamfer meets its
 * neighbour along a vertical line, and vertical is an axis of every plane in play, so
 * the two edges coincide. Here the neighbour ends on a straight cut and the loft ends
 * on a forty-five degree one, and two such lines cross at a point: whichever way the
 * end is placed, a triangle is left over. Placed one way that triangle is a hole in
 * the floor; placed the other it is plate over ground the neighbour already covers.
 *
 * The loft runs on, so the hole is the one that does not happen. A player can always
 * add a panel to fill a gap and can never subtract part of one, so a corner that comes
 * out whole and overlapping is recoverable where a corner that comes out with two
 * triangles missing is a rebuild. What the overlap would otherwise cost — two
 * identical faces in one block, which flickers rather than reading as a seam — is
 * settled where it arises, by the cell turning away a plate it has no room to show;
 * see {@code LargePanelBlockEntity.smotheredBy}.
 */
public final class PanelChamfer {

    /** Facings a panel can have, counting the diagonals; see {@link PanelPlane#octant}. */
    private static final int OCTANTS = 4 * PanelPlane.YAW_STEPS;

    /** A slope this shallow is a chamfer that never reaches the plane it aims at. */
    private static final double EPSILON = 1.0e-9;

    /**
     * How far a dot product of two unit vectors may stray from nought and still count
     * as a right angle. The quantities compared are exact right angles built out of
     * {@code sqrt(2)/2}, so what this admits is a few ulps and nothing else.
     */
    private static final double TOLERANCE = 1.0e-6;

    /** Why two panels cannot be joined, or {@link #NONE} if they can. */
    public enum Problem {
        NONE,
        /** Both clicks landed on one panel. */
        SAME,
        /** One of them is already turned, so the corner between them is not a right angle. */
        TURNED,
        /** One lies flat and the other does not, so there is nothing between them to draw. */
        FLAT,
        /** Both lie flat, but not on the same surface. */
        APART,
        /** They face the same way or opposite ways rather than meeting squarely. */
        SQUARE_ONLY,
        /** They already meet, so there is nothing between them to fill. */
        DEGENERATE;

        public boolean ok() {
            return this == NONE;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A chamfer, or the reason there is none. Exactly one of the two is set. */
    public record Joint(PanelDefinition panel, Problem problem) {}

    private PanelChamfer() {}

    /**
     * The chamfer springing from {@code from} and stopping at {@code to}, or the
     * reason there is not one.
     */
    public static Joint between(PanelDefinition from, PanelDefinition to) {
        if (from == null || to == null || from.equals(to))
            return refused(Problem.SAME);

        PanelPlane springs = from.plane();
        PanelPlane stops = to.plane();
        if (springs.isTurned() || stops.isTurned())
            return refused(Problem.TURNED);

        boolean flat = springs.face().getAxis().isVertical();
        if (flat != stops.face().getAxis().isVertical())
            return refused(Problem.FLAT);
        return flat ? lofted(from, to) : upright(from, to);
    }

    /**
     * The chamfer between two panels that stand up: a panel on the diagonal plane
     * through the first one's end edge, as tall as it is.
     *
     * <h2>Why the direction is not read off the facings</h2>
     *
     * It was, and it was wrong half the time. Two perpendicular walls name a corner
     * between their fronts, and taking the facing halfway between them looks like the
     * whole answer — until one of the walls was built from the other side. Which side
     * a panel calls its front is decided by where the player stood when they drew it,
     * a plate looks the same from both, and nothing about a finished wall says which
     * way it was drawn. Flip one and its octant moves by four, the bisector moves by
     * two, and the chamfer comes out on the perpendicular diagonal: right length,
     * right end, square across the corner it was meant to cut.
     *
     * So the direction comes from where the panels <em>are</em>. The chamfer leaves the
     * first panel at the end nearer the second, heading out of it, and arrives at the
     * second heading into it; the direction bisecting those two is the one diagonal
     * that gets from one to the other, and no facing takes part in choosing it. That
     * is the same rule {@link #lofted} follows, for the same reason.
     *
     * The facing is then settled separately and only by the first panel: of the two
     * sides of that diagonal, the chamfer fronts the one the panel it springs from
     * fronts, so the two surfaces continue into each other. If the second panel
     * disagrees the player has two walls that genuinely face apart, and a chamfer in
     * the right place meeting the back of one is a far better answer than a chamfer in
     * the wrong place.
     */
    private static Joint upright(PanelDefinition from, PanelDefinition to) {
        PanelPlane springs = from.plane();
        PanelPlane stops = to.plane();
        // Two walls on one axis are parallel however they face, and parallel walls
        // have no corner between them
        if (springs.face().getAxis() == stops.face().getAxis())
            return refused(Problem.SQUARE_ONLY);

        // Any height will do: every plane in play here is vertical, so nothing below
        // depends on which one is picked
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double height = from.minV() / scale;

        int leaves = nearestEnd(springs, from, height, centre(stops, to, height));
        Vec3 edge = springs.at(leaves / scale, height, 0);
        int arrives = nearestEnd(stops, to, height, edge);

        Vec3 heading = springs.tangentU().scale(leaves == from.maxU() ? 1 : -1)
                .add(stops.tangentU().scale(arrives == to.minU() ? 1 : -1)).normalize();
        PanelPlane facing = diagonalAlong(heading, springs.outward());
        if (facing == null)
            return refused(Problem.SQUARE_ONLY);
        PanelPlane plane = PanelPlane.through(facing.face(), facing.yaw(), edge);

        int reach = reach(plane, stops, arrives, height);
        int start = (int) Math.round(plane.u(edge) * scale);
        // Backwards means the second panel's end is behind the first one's rather than
        // beyond it, so there is no corner left between them to cut
        int advance = heading.dot(plane.tangentU()) > 0 ? 1 : -1;
        if ((reach - start) * advance <= 0)
            return refused(Problem.DEGENERATE);
        return new Joint(PanelDefinition.between(plane, start, from.minV(), reach, from.maxV()), Problem.NONE);
    }

    /**
     * The diagonal plane a heading runs along, fronted on the side a given outward
     * normal is on.
     *
     * Two of the four diagonals run along any given heading — the same plane seen from
     * either side — and the front picks between them. The other two stand square to it
     * and are skipped by the first test.
     */
    private static PanelPlane diagonalAlong(Vec3 heading, Vec3 front) {
        for (int octant = 1; octant < OCTANTS; octant += 2) {
            PanelPlane bare = ofOctant(octant);
            if (Math.abs(bare.outward().dot(heading)) > TOLERANCE)
                continue;
            if (bare.outward().dot(front) > 0)
                return bare;
        }
        return null;
    }

    /**
     * How far along the chamfer the second panel's end plane is, in offset units.
     *
     * That plane stands perpendicular to the second panel through its nearest end, so
     * a point is on it exactly when the second panel's own u coordinate reads the end
     * value. Read along the chamfer, that coordinate is affine — the chamfer is a
     * straight line and u is a dot product — so it is inverted rather than searched.
     * The two are forty-five degrees apart by construction, so the slope is never
     * zero and the check below is only ever a guard.
     */
    private static int reach(PanelPlane chamfer, PanelPlane stops, int end, double height) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double at0 = stops.u(chamfer.at(0, height, 0));
        double slope = stops.u(chamfer.at(1, height, 0)) - at0;
        if (Math.abs(slope) < EPSILON)
            return (int) Math.round(chamfer.u(chamfer.at(0, height, 0)) * scale);
        return (int) Math.round((end / scale - at0) / slope * scale);
    }

    /** Whichever end of a panel is nearer a point, as its u in offset units. */
    private static int nearestEnd(PanelPlane plane, PanelDefinition panel, double height, Vec3 target) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        Vec3 low = plane.at(panel.minU() / scale, height, 0);
        Vec3 high = plane.at(panel.maxU() / scale, height, 0);
        return low.distanceToSqr(target) <= high.distanceToSqr(target) ? panel.minU() : panel.maxU();
    }

    /** The middle of a panel, which is what "the end nearer the other one" is measured from. */
    private static Vec3 centre(PanelPlane plane, PanelDefinition panel, double height) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        return plane.at((panel.minU() + panel.maxU()) / 2.0 / scale, height, 0);
    }

    /** The bare plane of a facing named by its eighth of a turn. */
    private static PanelPlane ofOctant(int octant) {
        return new PanelPlane(Direction.from2DDataValue(octant / PanelPlane.YAW_STEPS),
                octant % PanelPlane.YAW_STEPS, 0);
    }

    // Two panels lying flat

    /**
     * The diagonal piece between two flat strips running square to each other: a spun
     * panel on the surface they share, as wide as the first one's end edge and reaching
     * to the second one's.
     */
    private static Joint lofted(PanelDefinition from, PanelDefinition to) {
        PanelPlane springs = from.plane();
        PanelPlane stops = to.plane();
        // The same plane held the same way up. Two floors at different heights, or a
        // floor and the ceiling below it, have nothing a single panel can bridge
        if (springs.face() != stops.face() || springs.offset() != stops.offset())
            return refused(Problem.APART);

        // A strip runs along its longer side, which here stands in for which way a
        // wall faces; two strips running the same way have no corner between them
        boolean fromAlongU = from.spanU() >= from.spanV();
        boolean toAlongU = to.spanU() >= to.spanV();
        if (fromAlongU == toAlongU)
            return refused(Problem.SQUARE_ONLY);

        double scale = PanelPlane.UNITS_PER_BLOCK;
        int leaves = nearerEnd(from, fromAlongU, middleAlong(to, fromAlongU));
        int arrives = nearerEnd(to, toAlongU, middleAlong(from, toAlongU));

        // Out of the first strip at its near end, and on into the second at its own:
        // the loft heads off along the direction bisecting the two
        int away = leaves == high(from, fromAlongU) ? 1 : -1;
        int into = arrives == low(to, toAlongU) ? 1 : -1;
        Vec3 fromAxis = fromAlongU ? springs.tangentU() : springs.tangentV();
        Vec3 toAxis = toAlongU ? stops.tangentU() : stops.tangentV();
        Vec3 heading = fromAxis.scale(away).add(toAxis.scale(into)).normalize();

        // Both axes of a spun plane are diagonals, so whichever of the two the loft
        // runs along, the other measures across it
        PanelPlane chamfer = new PanelPlane(springs.face(), 1, springs.offset());
        boolean runsU = Math.abs(heading.dot(chamfer.tangentU())) > Math.abs(heading.dot(chamfer.tangentV()));
        int advance = heading.dot(runsU ? chamfer.tangentU() : chamfer.tangentV()) > 0 ? 1 : -1;

        // The first strip's end edge, whose two corners the loft's long sides pass
        // through and whose nearer corner it starts at, so that no part of that edge is
        // left with nothing beyond it
        Vec3[] mouth = endCorners(springs, from, fromAlongU, leaves);
        int acrossA = units(across(chamfer, runsU, mouth[0]));
        int acrossB = units(across(chamfer, runsU, mouth[1]));
        int acrossLow = Math.min(acrossA, acrossB);
        int acrossHigh = Math.max(acrossA, acrossB);
        int runA = units(along(chamfer, runsU, mouth[0]));
        int runB = units(along(chamfer, runsU, mouth[1]));
        int start = advance > 0 ? Math.min(runA, runB) : Math.max(runA, runB);

        // The second strip's end plane crosses the loft's two long sides at different
        // places, and the further of them is where the loft stops, for the same reason:
        // stopping at the nearer would leave part of that end edge with nothing to meet
        double first = meets(chamfer, stops, runsU, toAlongU, acrossLow / scale, arrives / scale);
        double second = meets(chamfer, stops, runsU, toAlongU, acrossHigh / scale, arrives / scale);
        if (Double.isNaN(first) || Double.isNaN(second))
            return refused(Problem.SQUARE_ONLY);
        int reach = units(advance > 0 ? Math.max(first, second) : Math.min(first, second));

        // Nothing between them, or the second strip already reaching back past the
        // first one's end, which is the flat way of two panels already meeting
        if ((reach - start) * advance <= 0)
            return refused(Problem.DEGENERATE);
        return new Joint(runsU ? PanelDefinition.between(chamfer, start, acrossLow, reach, acrossHigh)
                : PanelDefinition.between(chamfer, acrossLow, start, acrossHigh, reach), Problem.NONE);
    }

    /**
     * How far along the loft the second strip's end plane lies, at one given distance
     * across it, in blocks.
     *
     * The same inversion {@link #reach} performs, and it has to be done twice because
     * the answer moves: the end plane is square to the second strip and the loft is at
     * forty-five degrees to it, so the plane crosses the loft's two long sides a
     * strip's width apart rather than at one place.
     *
     * @return NaN if the loft never reaches that plane, which the construction rules
     *         out and this only guards against
     */
    private static double meets(PanelPlane chamfer, PanelPlane stops, boolean runsU, boolean toAlongU,
            double across, double end) {
        double at0 = reading(stops, toAlongU, point(chamfer, runsU, 0, across));
        double slope = reading(stops, toAlongU, point(chamfer, runsU, 1, across)) - at0;
        if (Math.abs(slope) < EPSILON)
            return Double.NaN;
        return (end - at0) / slope;
    }

    /** A point on the loft, named by how far along and across it is. */
    private static Vec3 point(PanelPlane chamfer, boolean runsU, double along, double across) {
        return runsU ? chamfer.at(along, across, 0) : chamfer.at(across, along, 0);
    }

    /** The second strip's own coordinate along its run, which is what its end names. */
    private static double reading(PanelPlane stops, boolean toAlongU, Vec3 point) {
        return toAlongU ? stops.u(point) : stops.v(point);
    }

    /** How far along the loft a point is, in blocks. */
    private static double along(PanelPlane chamfer, boolean runsU, Vec3 point) {
        return runsU ? chamfer.u(point) : chamfer.v(point);
    }

    /** How far across the loft a point is, in blocks. */
    private static double across(PanelPlane chamfer, boolean runsU, Vec3 point) {
        return runsU ? chamfer.v(point) : chamfer.u(point);
    }

    /** The two corners of the end edge a strip presents at one end of its run. */
    private static Vec3[] endCorners(PanelPlane plane, PanelDefinition panel, boolean alongU, int end) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        if (alongU)
            return new Vec3[] { plane.at(end / scale, panel.minV() / scale, 0),
                    plane.at(end / scale, panel.maxV() / scale, 0) };
        return new Vec3[] { plane.at(panel.minU() / scale, end / scale, 0),
                plane.at(panel.maxU() / scale, end / scale, 0) };
    }

    /** Whichever end of a strip's run is nearer a place along that same axis. */
    private static int nearerEnd(PanelDefinition panel, boolean alongU, double target) {
        int low = low(panel, alongU);
        int high = high(panel, alongU);
        return Math.abs(low - target) <= Math.abs(high - target) ? low : high;
    }

    /** The middle of a panel along one of the plane's axes, in offset units. */
    private static double middleAlong(PanelDefinition panel, boolean alongU) {
        return (low(panel, alongU) + high(panel, alongU)) / 2.0;
    }

    private static int low(PanelDefinition panel, boolean alongU) {
        return alongU ? panel.minU() : panel.minV();
    }

    private static int high(PanelDefinition panel, boolean alongU) {
        return alongU ? panel.maxU() : panel.maxV();
    }

    private static int units(double blocks) {
        return (int) Math.round(blocks * PanelPlane.UNITS_PER_BLOCK);
    }

    private static Joint refused(Problem problem) {
        return new Joint(null, problem);
    }
}
