package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a duct goes when a player clicks two points on a face: the pure geometry
 * behind {@code WireDuctItem}.
 *
 * Both clicks snap to a one-pixel grid on the face, and the centre line runs half
 * the duct's depth out from it. Two points in line give one straight run; two
 * points that differ along both of the face's axes give an L, turning once, the
 * longer leg first. Two points on faces at right angles give a duct that turns
 * from one face onto the other over the edge between them, inside or outside
 * ({@link #corner}). A run may be any length. It is cut where it crosses a section
 * border, so each piece lives and is saved wholly in one chunk and a duct never
 * half vanishes when only one of two chunks it spans is loaded; the cuts are
 * invisible, and the pieces join end to end, which is all a wire run through them
 * needs ({@link DuctNetwork}).
 *
 * A click on a duct already there, near its end, snaps onto that end (a click on
 * the face beside it does not; see {@code WireDuctService.snapPoint}), which is how
 * a new run continues an old one, turns a corner from it in the face, or turns
 * from it onto another face.
 */
public final class DuctPlacement {

    /** The grid points snap to, blocks. */
    public static final double GRID = 1 / 16.0;

    /** How near an existing duct end a click must be to snap onto it, blocks. */
    public static final double END_SNAP = 0.3;

    /** The shortest stretch worth placing, blocks. */
    public static final double MIN_LENGTH = GRID;

    private static final double EPS = 1e-6;

    /** How far past an edge to look for the block that tells an inside corner from an outside one, blocks. */
    private static final double PROBE = 1e-3;

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
     * The pieces a duct from {@code start} on {@code startFace} to {@code end} on
     * {@code endFace} is made of. On faces at right angles it turns from one onto the
     * other ({@link #corner}); otherwise both must lie in one face's plane
     * ({@link #plan(Vec3, Vec3, Direction, DuctSize)}). {@code solid} says whether a
     * point is inside a block, which tells an inside edge from an outside one when
     * both clicks were on the edge itself.
     */
    public static List<DuctSegment> plan(Vec3 start, Direction startFace, Vec3 end, Direction endFace, DuctSize size,
            Predicate<Vec3> solid) {
        if (startFace.getAxis() != endFace.getAxis()) {
            List<DuctSegment> turned = corner(start, startFace, end, endFace, size, solid);
            if (!turned.isEmpty())
                return turned;
        }
        List<DuctSegment> pieces = plan(start, end, endFace, size);
        return pieces.isEmpty() && startFace != endFace ? plan(start, end, startFace, size) : pieces;
    }

    /**
     * A duct turning from one face onto another at right angles to it, over the edge
     * where they meet: an inside corner (from the floor up a wall) or an outside one
     * (round the edge of a block). The centre lines turn where they cross, half a
     * depth off both faces. A shift along the edge is made on the face whose point
     * lies further from it, so the duct crosses the edge square.
     *
     * At an inside corner a point no further from the other face than that crossing
     * is moved onto the other face, so the short stretch from it to the turn fills
     * the corner right down to that face ({@link DuctNetwork#jointExtensions}). Empty
     * if one point lies in front of the other face and the other behind it, which no
     * edge joins.
     */
    static List<DuctSegment> corner(Vec3 start, Direction startFace, Vec3 end, Direction endFace, DuctSize size,
            Predicate<Vec3> solid) {
        Direction.Axis a = startFace.getAxis(), b = endFace.getAxis();
        if (a == b)
            return List.of();
        Direction.Axis e = edgeAxis(a, b);
        Vec3 nA = Vec3.atLowerCornerOf(startFace.getNormal()), nB = Vec3.atLowerCornerOf(endFace.getNormal());
        double half = size.depth() / 2;
        // How far each point lies in front of the other face: positive at an inside corner, negative outside
        double startFront = start.subtract(end).dot(nB) + half;
        double endFront = end.subtract(start).dot(nA) + half;
        boolean startOn = Math.abs(startFront) < EPS, endOn = Math.abs(endFront) < EPS;
        boolean inside;
        if (startOn && endOn) {
            // Both on the edge: inside if the block beside the first face is behind the second
            Vec3 edge = start.subtract(nA.scale(half));
            inside = solid.test(edge.add(nA.scale(PROBE)).subtract(nB.scale(PROBE)));
        } else if (!startOn && !endOn && (startFront > 0) != (endFront > 0)) {
            return List.of();
        } else {
            inside = (startOn ? endFront : startFront) > 0;
        }
        boolean shiftFirst = Math.abs(startFront) >= Math.abs(endFront);
        if (inside) {
            if (startFront < half + EPS)
                start = start.subtract(nB.scale(startFront));
            if (endFront < half + EPS)
                end = end.subtract(nA.scale(endFront));
        }
        List<Vec3> points = new ArrayList<>();
        List<Direction> faces = new ArrayList<>();
        points.add(start);
        if (shiftFirst) {
            Vec3 along = with(start, e, coord(end, e));
            Vec3 turn = with(along, b, coord(end, b));
            points.add(along);
            faces.add(startFace);
            points.add(turn);
            faces.add(startFace);
        } else {
            Vec3 turn = with(start, b, coord(end, b));
            points.add(turn);
            faces.add(startFace);
            points.add(with(end, e, coord(start, e)));
            faces.add(endFace);
        }
        points.add(end);
        faces.add(endFace);
        List<DuctSegment> pieces = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++)
            if (points.get(i).distanceToSqr(points.get(i + 1)) > EPS * EPS)
                pieces.addAll(splitAtChunks(new DuctSegment(points.get(i), points.get(i + 1), faces.get(i), size)));
        return pieces;
    }

    /** The axis along the edge between faces whose normals run along {@code a} and {@code b}. */
    static Direction.Axis edgeAxis(Direction.Axis a, Direction.Axis b) {
        for (Direction.Axis axis : Direction.Axis.values())
            if (axis != a && axis != b)
                return axis;
        throw new IllegalArgumentException("No edge between faces along one axis");
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
