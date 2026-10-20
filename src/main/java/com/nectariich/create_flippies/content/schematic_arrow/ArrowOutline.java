package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.ArrayList;
import java.util.List;

/**
 * The flat outline of a drawn schematic arrow: a polyline thickened into a strip,
 * its bends joined, its ends finished in heads.
 *
 * Pure geometry in whatever unit the caller works in, with no game state, so the
 * server, the overlay and the mesh all get the same answer from the same points.
 *
 * <h2>One ring when it can be, pieces when it cannot</h2>
 *
 * The outline is first built as a single ring: the right side of the strip walked
 * forward with each bend joined, around the end head, the left side walked back,
 * and around the start head. That is the clean answer, and it is right whenever the
 * strip does not run into itself.
 *
 * A strip that crosses itself, folds back on itself, or bends so tightly that the
 * inside of a bend overruns the next segment does not have a single ring for an
 * outline, and working out the true union is far more machinery than the case is
 * worth. It falls back to <em>pieces</em> instead: a rectangle per segment, a wedge
 * per bend and a polygon per head, each simple, overlapping where the strip
 * overlaps. Raised to one height and drawn opaque, overlapping prisms look exactly
 * like their union: every wall that would be inside the union is inside another
 * piece and hidden under its top. So the fallback costs some triangles and nothing
 * that can be seen.
 *
 * Every ring comes out counter-clockwise in (u, v), which is what the extrusion reads
 * its outward wall normals from.
 */
public final class ArrowOutline {

    /** How long a sharp corner may grow, as a multiple of the half width, before it is bevelled. */
    public static final double MITER_LIMIT = 4;

    /** A solid head's length and half width, as multiples of the shaft width. */
    static final double TRIANGLE_LENGTH = 2;
    static final double TRIANGLE_HALF = 1.5;

    /** An open head's length, half width and stroke, as multiples of the shaft width. */
    static final double CHEVRON_LENGTH = 2.5;
    static final double CHEVRON_HALF = 2.5;
    static final double CHEVRON_STROKE = 0.8;

    /**
     * The most of the room inside a chevron its stroke may take. A stroke thicker than
     * the V has room for would close it up and leave the shaft wider than its opening.
     */
    static final double CHEVRON_STROKE_ROOM = 0.8;

    /**
     * A head's own measurements, in the outline's unit, each nought or less for the size
     * the shaft width gives it ({@link #TRIANGLE_LENGTH} and the rest).
     *
     * @param length how far the head reaches back from its tip
     * @param width  how wide it is across, never drawn narrower than the shaft (twice it for a chevron)
     * @param stroke how thick a chevron's arms are, thinned where the V has no room for it
     */
    public record HeadSize(double length, double width, double stroke) {

        public static final HeadSize AUTO = new HeadSize(0, 0, 0);

        public boolean isAuto() {
            return !(length > 0) && !(width > 0) && !(stroke > 0);
        }
    }

    /** Points on a rounded bend per half turn of it. */
    private static final int ROUND_STEPS = 8;

    /** The outline: rings to fill, and whether they are the one ring of a strip that does not overlap itself. */
    public record Shape(List<double[]> rings, boolean merged) {

        public static final Shape NONE = new Shape(List.of(), true);

        public boolean isEmpty() {
            return rings.isEmpty();
        }
    }

    private ArrowOutline() {}

    /**
     * The outline of a polyline {@code points} (u and v interleaved) drawn {@code width}
     * wide, with heads sized from the width. Empty for fewer than two distinct points,
     * or for a path too short to hold its heads.
     */
    public static Shape of(double[] points, double width, ArrowHead start, ArrowHead end, ArrowJoin join) {
        return of(points, width, start, end, join, HeadSize.AUTO);
    }

    /** The same with heads of the given size. */
    public static Shape of(double[] points, double width, ArrowHead start, ArrowHead end, ArrowJoin join,
            HeadSize size) {
        double[] p = clean(points, width * 1e-6);
        int n = p.length / 2;
        if (n < 2 || !(width > 0) || !fitsCleaned(p, width, start, end, size))
            return Shape.NONE;
        double h = width / 2;
        Cap first = cap(start, width, size);
        Cap last = cap(end, width, size);

        // The shaft, shortened at either end to make room for the heads
        double[] q = p.clone();
        double[] d0 = direction(p, 0);
        double[] dn = direction(p, n - 2);
        q[0] += d0[0] * first.setback;
        q[1] += d0[1] * first.setback;
        q[2 * n - 2] -= dn[0] * last.setback;
        q[2 * n - 1] -= dn[1] * last.setback;

        List<double[]> ring = new ArrayList<>();
        boolean merged = true;
        // Right side forward
        ring.add(offset(q, 0, d0, -h));
        for (int i = 1; i < n - 1; i++)
            merged &= joinPoints(p, i, -1, h, join, ring, false);
        ring.add(offset(q, n - 1, dn, -h));
        // Round the end
        capPoints(last, p[2 * n - 2], p[2 * n - 1], dn[0], dn[1], ring);
        // Left side back
        ring.add(offset(q, n - 1, dn, h));
        for (int i = n - 2; i >= 1; i--)
            merged &= joinPoints(p, i, 1, h, join, ring, true);
        ring.add(offset(q, 0, d0, h));
        // Round the start
        capPoints(first, p[0], p[1], -d0[0], -d0[1], ring);

        double[] flat = deduplicated(ring, width * 1e-6);
        if (merged && simple(flat) && signedArea(flat) > 0)
            return new Shape(List.of(flat), true);
        return new Shape(pieces(p, q, width, first, last, join), false);
    }

    /** The same, from a stored path in offset units, in offset units. */
    public static Shape of(int[] points, ArrowStyle style) {
        double[] p = new double[points.length];
        for (int i = 0; i < points.length; i++)
            p[i] = points[i];
        return of(p, style.width(), style.start(), style.end(), style.join(), style.headSize());
    }

    /** Whether a path is long enough to hold both its heads, sized from the width. */
    public static boolean fits(double[] points, double width, ArrowHead start, ArrowHead end) {
        return fits(points, width, start, end, HeadSize.AUTO);
    }

    /** Whether a path is long enough to hold both its heads at the given size. */
    public static boolean fits(double[] points, double width, ArrowHead start, ArrowHead end, HeadSize size) {
        double[] p = clean(points, width * 1e-6);
        return p.length >= 4 && fitsCleaned(p, width, start, end, size);
    }

    private static boolean fitsCleaned(double[] p, double width, ArrowHead start, ArrowHead end, HeadSize size) {
        int n = p.length / 2;
        double first = cap(start, width, size).setback;
        double last = cap(end, width, size).setback;
        double margin = width * 1e-3;
        if (n == 2)
            return first + last + margin < length(p, 0);
        return first + margin < length(p, 0) && last + margin < length(p, n - 2);
    }

    /**
     * As far from the polyline as any part of the outline can reach, in the same unit
     * as the width: the widest head, a long miter, and a little to spare. What a bounding
     * box is grown by so that it is never short.
     */
    public static double reach(double width) {
        return reach(width, HeadSize.AUTO);
    }

    /**
     * The same with heads of the given size. A chevron's arms reach back past where the
     * shaft meets them, so a long one can reach past the point before the last; sized
     * heads count that, and heads sized from the width keep the reach they always had,
     * so an arrow already on a panel keeps the cells it is in.
     */
    public static double reach(double width, HeadSize size) {
        Cap triangle = cap(ArrowHead.TRIANGLE, width, size);
        Cap chevron = cap(ArrowHead.CHEVRON, width, size);
        double reach = Math.max(MITER_LIMIT * width / 2, Math.max(chevron.half, triangle.half));
        if (!size.isAuto())
            reach = Math.max(reach, Math.hypot(chevron.half, Math.max(0, chevron.length - chevron.setback)));
        return reach + 1;
    }

    // Heads

    /** One end's head: how far the shaft stops short of the tip, and the head's own measurements. */
    private record Cap(ArrowHead head, double setback, double length, double half, double inner, double junction,
            double halfWidth) {}

    private static Cap cap(ArrowHead head, double width, HeadSize size) {
        double h = width / 2;
        switch (head) {
            case TRIANGLE -> {
                double length = size.length() > 0 ? size.length() : TRIANGLE_LENGTH * width;
                double half = size.width() > 0 ? Math.max(size.width() / 2, h) : TRIANGLE_HALF * width;
                return new Cap(head, length, length, half, 0, length, h);
            }
            case CHEVRON -> {
                double length = size.length() > 0 ? size.length() : CHEVRON_LENGTH * width;
                // At least twice the shaft across, so the V has an inside for the shaft to meet
                double half = size.width() > 0 ? Math.max(size.width() / 2, width) : CHEVRON_HALF * width;
                double stroke = size.stroke() > 0 ? size.stroke() : CHEVRON_STROKE * width;
                // The shaft meets the inside of the V short of its open end only while the
                // stroke is under length (half - h) / hypot(length, half); keep it well under
                stroke = Math.min(stroke, CHEVRON_STROKE_ROOM * length * (half - h) / Math.hypot(length, half));
                // The inside of the V is the outside moved in by the stroke: its tip
                // is behind the outer tip by the stroke over the sine of the half angle
                double tip = stroke * Math.hypot(length, half) / half;
                double inner = half / length * (length - tip);
                // Where the shaft's sides meet the inside of the V
                double junction = tip + h * length / half;
                return new Cap(head, junction, length, half, inner, junction, h);
            }
            default -> {
                return new Cap(head, 0, 0, 0, 0, 0, h);
            }
        }
    }

    /**
     * The points around a head from the shaft's right side to its left, tip at
     * {@code (tx, ty)}, pointing along {@code (ax, ay)}. The shaft's own two end points
     * are not included; a square end adds nothing between them.
     */
    private static void capPoints(Cap cap, double tx, double ty, double ax, double ay, List<double[]> into) {
        double lx = -ay;
        double ly = ax;
        switch (cap.head) {
            case TRIANGLE -> {
                double bx = tx - ax * cap.length;
                double by = ty - ay * cap.length;
                into.add(new double[] { bx - lx * cap.half, by - ly * cap.half });
                into.add(new double[] { tx, ty });
                into.add(new double[] { bx + lx * cap.half, by + ly * cap.half });
            }
            case CHEVRON -> {
                double wx = tx - ax * cap.length;
                double wy = ty - ay * cap.length;
                into.add(new double[] { wx - lx * cap.inner, wy - ly * cap.inner });
                into.add(new double[] { wx - lx * cap.half, wy - ly * cap.half });
                into.add(new double[] { tx, ty });
                into.add(new double[] { wx + lx * cap.half, wy + ly * cap.half });
                into.add(new double[] { wx + lx * cap.inner, wy + ly * cap.inner });
            }
            default -> { }
        }
    }

    /** A head on its own, as a ring, for the pieces fallback: the head closed across where the shaft meets it. */
    private static double[] capPiece(Cap cap, double tx, double ty, double ax, double ay) {
        if (cap.head == ArrowHead.NONE)
            return null;
        List<double[]> ring = new ArrayList<>();
        double lx = -ay;
        double ly = ax;
        double jx = tx - ax * cap.junction;
        double jy = ty - ay * cap.junction;
        ring.add(new double[] { jx - lx * cap.halfWidth, jy - ly * cap.halfWidth });
        capPoints(cap, tx, ty, ax, ay, ring);
        ring.add(new double[] { jx + lx * cap.halfWidth, jy + ly * cap.halfWidth });
        return counterClockwise(deduplicated(ring, 1e-9));
    }

    // Bends

    /**
     * The points one side of the strip takes around the bend at point {@code i}, added
     * in the order that side is walked. {@code side} is minus one for the right and one
     * for the left. Whether the strip can still be one ring past this bend, which it
     * cannot where it folds straight back on itself.
     */
    private static boolean joinPoints(double[] p, int i, int side, double h, ArrowJoin join, List<double[]> into,
            boolean backward) {
        double[] da = direction(p, i - 1);
        double[] db = direction(p, i);
        double px = p[2 * i];
        double py = p[2 * i + 1];
        double cross = da[0] * db[1] - da[1] * db[0];
        double dot = da[0] * db[0] + da[1] * db[1];
        List<double[]> points = new ArrayList<>();
        boolean ok = true;
        double ax = px - da[1] * side * h;
        double ay = py + da[0] * side * h;
        double bx = px - db[1] * side * h;
        double by = py + db[0] * side * h;
        if (Math.abs(cross) < 1e-9) {
            points.add(new double[] { ax, ay });
            if (dot < 0) {
                points.add(new double[] { bx, by });
                ok = false;
            }
        } else {
            boolean outer = side * cross < 0;
            double mx = (-da[1] - db[1]) / (1 + dot);
            double my = (da[0] + db[0]) / (1 + dot);
            double[] miter = { px + mx * side * h, py + my * side * h };
            if (!outer || join == ArrowJoin.MITER && Math.hypot(mx, my) <= MITER_LIMIT) {
                points.add(miter);
            } else if (join == ArrowJoin.ROUND) {
                arc(px, py, ax, ay, bx, by, h, points);
            } else {
                points.add(new double[] { ax, ay });
                points.add(new double[] { bx, by });
            }
        }
        if (backward)
            for (int k = points.size() - 1; k >= 0; k--)
                into.add(points.get(k));
        else
            into.addAll(points);
        return ok;
    }

    /** The outside of a bend on its own, as a ring from the bend point, for the pieces fallback. */
    private static double[] joinPiece(double[] p, int i, double h, ArrowJoin join) {
        double[] da = direction(p, i - 1);
        double[] db = direction(p, i);
        double cross = da[0] * db[1] - da[1] * db[0];
        if (Math.abs(cross) < 1e-9)
            return null;
        int side = cross > 0 ? -1 : 1;
        double px = p[2 * i];
        double py = p[2 * i + 1];
        double ax = px - da[1] * side * h;
        double ay = py + da[0] * side * h;
        double bx = px - db[1] * side * h;
        double by = py + db[0] * side * h;
        List<double[]> ring = new ArrayList<>();
        ring.add(new double[] { px, py });
        ring.add(new double[] { ax, ay });
        double dot = da[0] * db[0] + da[1] * db[1];
        double mx = (-da[1] - db[1]) / (1 + dot);
        double my = (da[0] + db[0]) / (1 + dot);
        if (join == ArrowJoin.MITER && Math.hypot(mx, my) <= MITER_LIMIT)
            ring.add(new double[] { px + mx * side * h, py + my * side * h });
        else if (join == ArrowJoin.ROUND) {
            List<double[]> arc = new ArrayList<>();
            arc(px, py, ax, ay, bx, by, h, arc);
            ring.addAll(arc.subList(1, arc.size() - 1));
        }
        ring.add(new double[] { bx, by });
        return counterClockwise(deduplicated(ring, 1e-9));
    }

    /** Points on the short arc of radius {@code r} about a centre from one point to another, both included. */
    private static void arc(double cx, double cy, double ax, double ay, double bx, double by, double r,
            List<double[]> into) {
        double from = Math.atan2(ay - cy, ax - cx);
        double ux = ax - cx;
        double uy = ay - cy;
        double vx = bx - cx;
        double vy = by - cy;
        double sweep = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        int steps = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / ROUND_STEPS)));
        for (int k = 0; k <= steps; k++) {
            double angle = from + sweep * k / steps;
            into.add(new double[] { cx + r * Math.cos(angle), cy + r * Math.sin(angle) });
        }
    }

    // The fallback

    private static List<double[]> pieces(double[] p, double[] q, double width, Cap first, Cap last, ArrowJoin join) {
        int n = p.length / 2;
        double h = width / 2;
        List<double[]> rings = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            double[] d = direction(q, i);
            if (d[0] == 0 && d[1] == 0)
                continue;
            double nx = -d[1] * h;
            double ny = d[0] * h;
            double ax = q[2 * i];
            double ay = q[2 * i + 1];
            double bx = q[2 * i + 2];
            double by = q[2 * i + 3];
            rings.add(new double[] { ax - nx, ay - ny, bx - nx, by - ny, bx + nx, by + ny, ax + nx, ay + ny });
        }
        for (int i = 1; i < n - 1; i++) {
            double[] wedge = joinPiece(p, i, h, join);
            if (wedge != null && wedge.length >= 6)
                rings.add(wedge);
        }
        double[] d0 = direction(p, 0);
        double[] dn = direction(p, n - 2);
        double[] head = capPiece(last, p[2 * n - 2], p[2 * n - 1], dn[0], dn[1]);
        if (head != null)
            rings.add(head);
        double[] tail = capPiece(first, p[0], p[1], -d0[0], -d0[1]);
        if (tail != null)
            rings.add(tail);
        return rings;
    }

    // Rings

    /** Whether a ring has no two edges that touch other than neighbours at their shared corner. */
    public static boolean simple(double[] ring) {
        int m = ring.length / 2;
        if (m < 3)
            return false;
        for (int i = 0; i < m; i++) {
            int i2 = (i + 1) % m;
            for (int j = i + 1; j < m; j++) {
                int j2 = (j + 1) % m;
                if (j == i2 || j2 == i)
                    continue;
                if (touch(ring[2 * i], ring[2 * i + 1], ring[2 * i2], ring[2 * i2 + 1], ring[2 * j], ring[2 * j + 1],
                        ring[2 * j2], ring[2 * j2 + 1]))
                    return false;
            }
        }
        return true;
    }

    /** Whether two segments share any point, ends included. */
    static boolean touch(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double scale = Math.max(Math.max(Math.abs(bx - ax), Math.abs(by - ay)),
                Math.max(Math.abs(dx - cx), Math.abs(dy - cy)));
        double eps = 1e-9 * Math.max(1, scale * scale);
        double o1 = orient(ax, ay, bx, by, cx, cy);
        double o2 = orient(ax, ay, bx, by, dx, dy);
        double o3 = orient(cx, cy, dx, dy, ax, ay);
        double o4 = orient(cx, cy, dx, dy, bx, by);
        if ((o1 > eps && o2 < -eps || o1 < -eps && o2 > eps) && (o3 > eps && o4 < -eps || o3 < -eps && o4 > eps))
            return true;
        return Math.abs(o1) <= eps && within(ax, ay, bx, by, cx, cy) || Math.abs(o2) <= eps && within(ax, ay, bx, by, dx, dy)
                || Math.abs(o3) <= eps && within(cx, cy, dx, dy, ax, ay)
                || Math.abs(o4) <= eps && within(cx, cy, dx, dy, bx, by);
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean within(double ax, double ay, double bx, double by, double px, double py) {
        return px >= Math.min(ax, bx) - 1e-9 && px <= Math.max(ax, bx) + 1e-9 && py >= Math.min(ay, by) - 1e-9
                && py <= Math.max(ay, by) + 1e-9;
    }

    /** The area of a ring, positive when it runs counter-clockwise. */
    public static double signedArea(double[] ring) {
        double sum = 0;
        int m = ring.length / 2;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            sum += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1];
        }
        return sum / 2;
    }

    /** Whether a point is inside any of the rings. */
    public static boolean contains(List<double[]> rings, double x, double y) {
        for (double[] ring : rings)
            if (inside(ring, x, y))
                return true;
        return false;
    }

    private static boolean inside(double[] ring, double x, double y) {
        boolean in = false;
        int m = ring.length / 2;
        for (int i = 0, j = m - 1; i < m; j = i++) {
            double xi = ring[2 * i];
            double yi = ring[2 * i + 1];
            double xj = ring[2 * j];
            double yj = ring[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    private static double[] counterClockwise(double[] ring) {
        if (signedArea(ring) >= 0)
            return ring;
        double[] reversed = new double[ring.length];
        int m = ring.length / 2;
        for (int i = 0; i < m; i++) {
            reversed[2 * i] = ring[2 * (m - 1 - i)];
            reversed[2 * i + 1] = ring[2 * (m - 1 - i) + 1];
        }
        return reversed;
    }

    // Small things

    /** The points with any that repeat the one before dropped. */
    static double[] clean(double[] points, double within) {
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i + 1 < points.length; i += 2) {
            double[] point = { points[i], points[i + 1] };
            if (!kept.isEmpty() && near(kept.get(kept.size() - 1), point, within))
                continue;
            kept.add(point);
        }
        double[] flat = new double[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[2 * i] = kept.get(i)[0];
            flat[2 * i + 1] = kept.get(i)[1];
        }
        return flat;
    }

    private static double[] deduplicated(List<double[]> ring, double within) {
        List<double[]> kept = new ArrayList<>();
        for (double[] point : ring)
            if (kept.isEmpty() || !near(kept.get(kept.size() - 1), point, within))
                kept.add(point);
        while (kept.size() > 1 && near(kept.get(0), kept.get(kept.size() - 1), within))
            kept.remove(kept.size() - 1);
        double[] flat = new double[kept.size() * 2];
        for (int i = 0; i < kept.size(); i++) {
            flat[2 * i] = kept.get(i)[0];
            flat[2 * i + 1] = kept.get(i)[1];
        }
        return flat;
    }

    private static boolean near(double[] a, double[] b, double within) {
        return Math.abs(a[0] - b[0]) <= within && Math.abs(a[1] - b[1]) <= within;
    }

    private static double[] direction(double[] p, int segment) {
        double dx = p[2 * segment + 2] - p[2 * segment];
        double dy = p[2 * segment + 3] - p[2 * segment + 1];
        double length = Math.hypot(dx, dy);
        return length == 0 ? new double[] { 0, 0 } : new double[] { dx / length, dy / length };
    }

    private static double length(double[] p, int segment) {
        return Math.hypot(p[2 * segment + 2] - p[2 * segment], p[2 * segment + 3] - p[2 * segment + 1]);
    }

    private static double[] offset(double[] q, int i, double[] d, double by) {
        return new double[] { q[2 * i] - d[1] * by, q[2 * i + 1] + d[0] * by };
    }
}
