package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.CurveParameters;

/**
 * The shape of a bent hanging wire: every leg between two consecutive path points
 * hangs as its own catenary, built by Power Grid's {@link CurveParameters} exactly as
 * an unbent wire between those two points would be. From that come the pieces the
 * renderer draws, the box the entity occupies, and what a line of sight hits.
 *
 * Power Grid lays a curve out relative to a local origin: horizontally halfway
 * between its ends, vertically at its first end ({@link #curveOrigin}). Everything
 * here hands back world coordinates.
 */
public final class BendGeometry {

    /** Length of the straight pieces a curve is walked in for hit tests and boxes, blocks. */
    public static final double SAMPLE_STEP = 0.25;

    /** How near a line of sight must pass a bend point to take hold of it, blocks. */
    public static final double POINT_PICK_RADIUS = 0.15;

    /** The least distance a line of sight must pass a wire within to hit it, blocks. */
    public static final double MIN_WIRE_PICK_RADIUS = 0.1;

    private BendGeometry() {}

    /** One straight piece of one leg, in world coordinates. */
    public interface PieceVisitor {
        /**
         * @param leg which leg of the path, from 0
         * @param offset how far along the whole path the piece starts, blocks
         */
        void visit(int leg, Vec3 from, Vec3 to, double offset, double length);
    }

    /** Where Power Grid measures a curve from {@code from} to {@code to} from. */
    public static Vec3 curveOrigin(Vec3 from, Vec3 to) {
        return new Vec3((from.x + to.x) * 0.5, from.y, (from.z + to.z) * 0.5);
    }

    /** The curve one leg hangs in. */
    public static CurveParameters curve(Vec3 from, Vec3 to, double horizontal, double vertical, double thickness) {
        return new CurveParameters(from, to, horizontal, vertical, thickness);
    }

    /**
     * Walks every leg of a path as straight pieces about {@code step} long, in order.
     * A leg of no length is skipped, so two points dropped on one another draw
     * nothing between them rather than a degenerate curve.
     */
    public static void forEachPiece(List<Vec3> path, double horizontal, double vertical, double thickness,
            double step, PieceVisitor visitor) {
        forEachPiece(path, 0, horizontal, vertical, thickness, step, visitor);
    }

    /**
     * As {@link #forEachPiece(List, double, double, double, double, PieceVisitor)},
     * for a wire some of whose legs run inside wire ducts ({@code ductLegs}, bit
     * {@code i} for leg {@code i}): those legs do not hang but run straight, and are
     * walked as straight pieces.
     */
    public static void forEachPiece(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness, double step, PieceVisitor visitor) {
        forEachPiece(path, ductLegs, LegCurves.uniform(horizontal, vertical), thickness, step, visitor);
    }

    /**
     * WIRE SAG: the curve coefficients of each leg of a path. A wire whose legs sag
     * by their own amount ({@link LegSag}) hangs each with its own pair; everything
     * else hangs every leg as its type does ({@link #uniform}).
     */
    public interface LegCurves {

        double horizontal(int leg);

        double vertical(int leg);

        static LegCurves uniform(double horizontal, double vertical) {
            return new LegCurves() {
                @Override
                public double horizontal(int leg) {
                    return horizontal;
                }

                @Override
                public double vertical(int leg) {
                    return vertical;
                }
            };
        }
    }

    /**
     * As {@link #forEachPiece(List, long, double, double, double, double, PieceVisitor)},
     * each leg hanging with its own coefficients. A ducted leg runs straight whatever
     * its sag.
     */
    public static void forEachPiece(List<Vec3> path, long ductLegs, LegCurves curves, double thickness,
            double step, PieceVisitor visitor) {
        double[] along = {0};
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 from = path.get(leg);
            Vec3 to = path.get(leg + 1);
            if (from.distanceToSqr(to) < 1e-10)
                continue;
            if (isDucted(ductLegs, leg)) {
                double length = from.distanceTo(to);
                int pieces = Math.max(1, (int) Math.ceil(length / step));
                for (int i = 0; i < pieces; i++) {
                    double a = (double) i / pieces, b = (double) (i + 1) / pieces;
                    visitor.visit(leg, from.lerp(to, a), from.lerp(to, b), along[0] + a * length,
                            (b - a) * length);
                }
                along[0] += length;
                continue;
            }
            List<Vec3> vertices = legVertices(from, to, curves.horizontal(leg), curves.vertical(leg), thickness, step);
            for (int i = 1; i < vertices.size(); i++) {
                Vec3 a = vertices.get(i - 1), b = vertices.get(i);
                double length = a.distanceTo(b);
                visitor.visit(leg, a, b, along[0], length);
                along[0] += length;
            }
        }
    }

    /**
     * The corners of one hanging leg, in world coordinates, as Power Grid's
     * {@link CurveParameters#runForSegments} walks it, from {@code from} to {@code to}
     * inclusive. The first and last are exactly the leg's ends: Power Grid's walk
     * misses them by a hair (it rounds the span to a float), and the residue is
     * spread along the leg so that two legs meeting at a bend point share that very
     * point. A curve that cannot be solved falls back to a straight line.
     */
    public static List<Vec3> legVertices(Vec3 from, Vec3 to, double horizontal, double vertical, double thickness,
            double step) {
        Vec3 origin = curveOrigin(from, to);
        List<Vec3> raw = new ArrayList<>();
        curve(from, to, horizontal, vertical, thickness).runForSegments((x1, y1, z1, x2, y2, z2, offset, length) -> {
            if (raw.isEmpty())
                raw.add(origin.add(x1, y1, z1));
            raw.add(origin.add(x2, y2, z2));
        }, step);
        boolean solved = raw.size() >= 2;
        for (Vec3 p : raw)
            solved &= Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z);
        if (!solved) {
            raw.clear();
            int pieces = Math.max(1, (int) Math.ceil(from.distanceTo(to) / step));
            for (int i = 0; i <= pieces; i++)
                raw.add(from.lerp(to, (double) i / pieces));
            return raw;
        }
        int last = raw.size() - 1;
        Vec3 startError = from.subtract(raw.get(0));
        Vec3 endError = to.subtract(raw.get(last));
        List<Vec3> vertices = new ArrayList<>(raw.size());
        for (int i = 0; i <= last; i++) {
            double t = (double) i / last;
            vertices.add(raw.get(i).add(startError.lerp(endError, t)));
        }
        vertices.set(0, from);
        vertices.set(last, to);
        return vertices;
    }

    /** Whether leg {@code leg} is marked as running inside a duct. */
    public static boolean isDucted(long ductLegs, int leg) {
        return leg >= 0 && leg < 64 && (ductLegs & (1L << leg)) != 0;
    }

    /** The box a bent wire occupies: every piece, its sag included, with room for its thickness. */
    public static AABB bounds(List<Vec3> path, double horizontal, double vertical, double thickness) {
        return bounds(path, 0, horizontal, vertical, thickness);
    }

    public static AABB bounds(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness) {
        return bounds(path, ductLegs, LegCurves.uniform(horizontal, vertical), thickness);
    }

    public static AABB bounds(List<Vec3> path, long ductLegs, LegCurves curves, double thickness) {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Vec3 p : path)
            grow(box, p);
        forEachPiece(path, ductLegs, curves, thickness, SAMPLE_STEP * 2,
                (leg, from, to, offset, length) -> {
            grow(box, from);
            grow(box, to);
        });
        return new AABB(box[0], box[1], box[2], box[3], box[4], box[5]).inflate(0.1 + thickness);
    }

    private static void grow(double[] box, Vec3 p) {
        box[0] = Math.min(box[0], p.x);
        box[1] = Math.min(box[1], p.y);
        box[2] = Math.min(box[2], p.z);
        box[3] = Math.max(box[3], p.x);
        box[4] = Math.max(box[4], p.y);
        box[5] = Math.max(box[5], p.z);
    }

    /** Whether any piece of a bent wire passes through a box. */
    public static boolean touches(List<Vec3> path, double horizontal, double vertical, double thickness, AABB box) {
        return touches(path, 0, horizontal, vertical, thickness, box);
    }

    /** As above; a leg inside a duct is shut in by it, so it touches nothing. */
    public static boolean touches(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness, AABB box) {
        return touches(path, ductLegs, LegCurves.uniform(horizontal, vertical), thickness, box);
    }

    public static boolean touches(List<Vec3> path, long ductLegs, LegCurves curves, double thickness, AABB box) {
        boolean[] hit = {false};
        forEachPiece(path, ductLegs, curves, thickness, SAMPLE_STEP, (leg, from, to, offset, length) -> {
            if (!hit[0] && !isDucted(ductLegs, leg) && (box.contains(from) || box.clip(from, to).isPresent()))
                hit[0] = true;
        });
        return hit[0];
    }

    /** What a line of sight hit on a wire: a bend point, or a leg at a place on it. */
    public record Hit(int point, int leg, Vec3 onWire, double distance) {

        public boolean isPoint() {
            return point >= 0;
        }
    }

    /**
     * The nearest thing a ray hits on a bent or unbent wire, or null. A bend point
     * wins over the wire it sits on, since a point is always on its wire and is the
     * smaller target. {@code points} are the bend points alone; {@code path} is the
     * whole path built from them.
     */
    public static Hit pick(Vec3 eye, Vec3 look, double maxDistance, List<Vec3> points, List<Vec3> path,
            double horizontal, double vertical, double thickness) {
        return pick(eye, look, maxDistance, points, path, 0, horizontal, vertical, thickness);
    }

    public static Hit pick(Vec3 eye, Vec3 look, double maxDistance, List<Vec3> points, List<Vec3> path,
            long ductLegs, double horizontal, double vertical, double thickness) {
        return pick(eye, look, maxDistance, points, path, ductLegs, LegCurves.uniform(horizontal, vertical),
                thickness);
    }

    public static Hit pick(Vec3 eye, Vec3 look, double maxDistance, List<Vec3> points, List<Vec3> path,
            long ductLegs, LegCurves curves, double thickness) {
        Vec3 unit = look.normalize();
        Hit best = null;
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            double t = p.subtract(eye).dot(unit);
            if (t < 0 || t > maxDistance)
                continue;
            if (eye.add(unit.scale(t)).distanceToSqr(p) > POINT_PICK_RADIUS * POINT_PICK_RADIUS)
                continue;
            if (best == null || t < best.distance())
                best = new Hit(i, -1, p, t);
        }
        if (best != null)
            return best;
        Hit leg = pickWire(eye, unit, maxDistance, path, ductLegs, curves, thickness);
        return leg;
    }

    /** The nearest place a ray passes within a wire's pick radius of one of its legs, or null. */
    public static Hit pickWire(Vec3 eye, Vec3 unit, double maxDistance, List<Vec3> path, double horizontal,
            double vertical, double thickness) {
        return pickWire(eye, unit, maxDistance, path, 0, horizontal, vertical, thickness);
    }

    public static Hit pickWire(Vec3 eye, Vec3 unit, double maxDistance, List<Vec3> path, long ductLegs,
            double horizontal, double vertical, double thickness) {
        return pickWire(eye, unit, maxDistance, path, ductLegs, LegCurves.uniform(horizontal, vertical), thickness);
    }

    public static Hit pickWire(Vec3 eye, Vec3 unit, double maxDistance, List<Vec3> path, long ductLegs,
            LegCurves curves, double thickness) {
        double radius = Math.max(MIN_WIRE_PICK_RADIUS, thickness);
        Hit[] best = {null};
        forEachPiece(path, ductLegs, curves, thickness, SAMPLE_STEP, (leg, from, to, offset, length) -> {
            double[] t = closest(eye, unit, from, to);
            if (t == null || t[0] < 0 || t[0] > maxDistance)
                return;
            Vec3 onRay = eye.add(unit.scale(t[0]));
            Vec3 onWire = from.add(to.subtract(from).scale(t[1]));
            if (onRay.distanceToSqr(onWire) > radius * radius)
                return;
            if (best[0] == null || t[0] < best[0].distance())
                best[0] = new Hit(-1, leg, onWire, t[0]);
        });
        return best[0];
    }

    /** Where a ray from {@code from} towards {@code to} first comes within reach of a wire, or null. */
    public static Vec3 raycast(Vec3 from, Vec3 to, List<Vec3> path, double horizontal, double vertical,
            double thickness) {
        return raycast(from, to, path, 0, horizontal, vertical, thickness);
    }

    public static Vec3 raycast(Vec3 from, Vec3 to, List<Vec3> path, long ductLegs, double horizontal,
            double vertical, double thickness) {
        return raycast(from, to, path, ductLegs, LegCurves.uniform(horizontal, vertical), thickness);
    }

    public static Vec3 raycast(Vec3 from, Vec3 to, List<Vec3> path, long ductLegs, LegCurves curves,
            double thickness) {
        Vec3 dir = to.subtract(from);
        double length = dir.length();
        if (length < 1e-6)
            return null;
        Vec3 unit = dir.scale(1 / length);
        Hit hit = pickWire(from, unit, length, path, ductLegs, curves, thickness);
        return hit == null ? null : from.add(unit.scale(hit.distance()));
    }

    /**
     * Closest approach of a ray (origin, unit direction) and a segment, as
     * {ray distance, fraction along the segment}; null for a segment of no length.
     */
    public static double[] closest(Vec3 origin, Vec3 unit, Vec3 p, Vec3 q) {
        Vec3 seg = q.subtract(p);
        double c = seg.dot(seg);
        if (c < 1e-12)
            return null;
        Vec3 w = origin.subtract(p);
        double b = unit.dot(seg), d = unit.dot(w), e = seg.dot(w);
        double denom = c - b * b;
        double s = denom < 1e-9 ? 0 : Math.max(0, Math.min(1, (e - b * d) / denom));
        // The ray distance that is closest to the clamped point on the segment
        double t = p.add(seg.scale(s)).subtract(origin).dot(unit);
        return new double[] {t, s};
    }

    /**
     * Where a dragged point goes: along the current line of sight, as far from the
     * eye as it was when the drag began. Holding the distance fixed is what turns a
     * two-dimensional mouse into a three-dimensional drag that never runs away
     * towards or from the camera.
     */
    public static Vec3 dragTarget(Vec3 eye, Vec3 look, double distance) {
        return eye.add(look.normalize().scale(distance));
    }

    /** The distance a drag holds a point at: how far it was from the eye when taken hold of. */
    public static double grabDistance(Vec3 eye, Vec3 point) {
        return eye.distanceTo(point);
    }
}
