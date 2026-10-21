package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a duct goes when a player clicks two points on a face: the pure geometry
 * behind {@code WireDuctItem}.
 *
 * Both clicks snap to a one-pixel grid on the face, and the centre line runs half
 * the duct's depth out from it. Two points in line give one straight run; two
 * points that differ along both of the face's axes give an L, turning once, the
 * longer leg first. A run may be any length. It is cut where it crosses a section
 * border, so each piece lives and is saved wholly in one chunk and a duct never
 * half vanishes when only one of two chunks it spans is loaded; the cuts are
 * invisible, and the pieces join end to end, which is all a wire run through them
 * needs ({@link DuctNetwork}).
 *
 * A click near the end of a duct already there snaps onto that end, which is how
 * a new run continues an old one, turns a corner from it in the face, or turns
 * from a wall onto the floor (the inside corner, where the two centre lines meet).
 */
public final class DuctPlacement {

    /** The grid points snap to, blocks. */
    public static final double GRID = 1 / 16.0;

    /** How near an existing duct end a click must be to snap onto it, blocks. */
    public static final double END_SNAP = 0.3;

    /** The shortest stretch worth placing, blocks. */
    public static final double MIN_LENGTH = GRID;

    private static final double EPS = 1e-6;

    /** Why a placement is refused; anything but {@link #OK}. */
    public enum Verdict {
        OK("ok"),
        NOT_FLAT("not_flat"),
        TOO_SHORT("too_short"),
        OUT_OF_REACH("out_of_reach"),
        NOT_LOADED("not_loaded"),
        MISSING_ITEMS("missing_items");

        private final String key;

        Verdict(String key) {
            this.key = key;
        }

        public String langKey() {
            return "gui.create_flippies.wire_duct." + key;
        }
    }

    private DuctPlacement() {}

    /** A clicked point on {@code face} as a point on a duct's centre line: snapped, and lifted off the face. */
    public static Vec3 snap(Vec3 hit, Direction face, DuctSize size) {
        double x = round(hit.x), y = round(hit.y), z = round(hit.z);
        Vec3 onFace = new Vec3(x, y, z);
        return onFace.add(Vec3.atLowerCornerOf(face.getNormal()).scale(size.depth() / 2));
    }

    private static double round(double v) {
        return Math.round(v / GRID) * GRID;
    }

    /** {@code p}, or the nearest of {@code ends} within {@link #END_SNAP} of it. */
    public static Vec3 snapToEnds(Vec3 p, List<Vec3> ends) {
        Vec3 best = p;
        double bestDistance = END_SNAP * END_SNAP;
        for (Vec3 end : ends) {
            double d = end.distanceToSqr(p);
            if (d <= bestDistance) {
                bestDistance = d;
                best = end;
            }
        }
        return best;
    }

    /**
     * The pieces a duct from {@code start} to {@code end} (both centre-line points)
     * on {@code face} is made of, in order from the start. Empty if the two do not
     * lie in one plane parallel to the face, or coincide.
     */
    public static List<DuctSegment> plan(Vec3 start, Vec3 end, Direction face, DuctSize size) {
        Direction.Axis normal = face.getAxis();
        if (Math.abs(coord(start, normal) - coord(end, normal)) > EPS)
            return List.of();
        Direction.Axis[] inPlane = inPlane(normal);
        double d0 = coord(end, inPlane[0]) - coord(start, inPlane[0]);
        double d1 = coord(end, inPlane[1]) - coord(start, inPlane[1]);
        List<DuctSegment> runs = new ArrayList<>();
        if (Math.abs(d0) < EPS && Math.abs(d1) < EPS)
            return List.of();
        if (Math.abs(d0) < EPS || Math.abs(d1) < EPS) {
            runs.add(new DuctSegment(start, end, face, size));
        } else {
            Direction.Axis first = Math.abs(d0) >= Math.abs(d1) ? inPlane[0] : inPlane[1];
            Vec3 corner = with(start, first, coord(end, first));
            runs.add(new DuctSegment(start, corner, face, size));
            runs.add(new DuctSegment(corner, end, face, size));
        }
        List<DuctSegment> pieces = new ArrayList<>();
        for (DuctSegment run : runs)
            pieces.addAll(splitAtChunks(run));
        return pieces;
    }

    /**
     * Whether a planned duct may be placed, before the items are counted. There is no
     * longest duct: the first point may be anywhere, and only the second, the one
     * being clicked, has to be within the player's reach.
     */
    public static Verdict check(List<DuctSegment> pieces, Vec3 eye, double reach) {
        if (pieces.isEmpty())
            return Verdict.NOT_FLAT;
        double total = totalLength(pieces);
        if (total < MIN_LENGTH - EPS)
            return Verdict.TOO_SHORT;
        double range = reach + 2;
        Vec3 last = pieces.get(pieces.size() - 1).to();
        if (eye.distanceToSqr(last) > range * range)
            return Verdict.OUT_OF_REACH;
        return Verdict.OK;
    }

    public static double totalLength(List<DuctSegment> pieces) {
        double total = 0;
        for (DuctSegment piece : pieces)
            total += piece.length();
        return total;
    }

    /** The items a stretch of duct costs and gives back: one per block begun. */
    public static int itemCost(double length) {
        return Math.max(1, (int) Math.ceil(length - 1e-6));
    }

    /**
     * A run cut at every chunk section border (a multiple of 16) it crosses. On X
     * and Z that keeps each piece in one chunk; on Y it keeps every piece at most 16
     * long, like every other.
     */
    public static List<DuctSegment> splitAtChunks(DuctSegment run) {
        Direction.Axis axis = run.axis();
        double a = coord(run.from(), axis), b = coord(run.to(), axis);
        double lo = Math.min(a, b), hi = Math.max(a, b);
        List<Double> cuts = new ArrayList<>();
        for (double c = Math.floor(lo / 16) * 16 + 16; c < hi - EPS; c += 16)
            if (c > lo + EPS)
                cuts.add(c);
        if (cuts.isEmpty())
            return List.of(run);
        if (b < a)
            java.util.Collections.reverse(cuts);
        List<DuctSegment> pieces = new ArrayList<>();
        Vec3 at = run.from();
        for (double c : cuts) {
            Vec3 next = with(run.from(), axis, c);
            pieces.add(new DuctSegment(at, next, run.face(), run.size()));
            at = next;
        }
        pieces.add(new DuctSegment(at, run.to(), run.face(), run.size()));
        return pieces;
    }

    static double coord(Vec3 v, Direction.Axis axis) {
        return axis.choose(v.x, v.y, v.z);
    }

    static Vec3 with(Vec3 v, Direction.Axis axis, double value) {
        return switch (axis) {
            case X -> new Vec3(value, v.y, v.z);
            case Y -> new Vec3(v.x, value, v.z);
            case Z -> new Vec3(v.x, v.y, value);
        };
    }

    /** The two axes that lie in a face whose normal runs along {@code normal}. */
    static Direction.Axis[] inPlane(Direction.Axis normal) {
        return switch (normal) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
    }
}
