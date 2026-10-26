package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One cell's share of a schematic arrow, raised: the top lifted to the arrow's
 * height, and a wall under every edge of its outline, as convex polygons in the
 * cell's own coordinates.
 *
 * <h2>Cutting to the cell</h2>
 *
 * Done the way paint is cut (see {@code PanelPaintQuads}): the flat shape is put at
 * the middle of the plate, cut against the cell's six walls there, and only then
 * raised. The middle of the plate is inside every cell the plate is in, so the cells
 * of a panel share the shape out between them without gaps or overlaps, and a plate
 * whose surface lies on a block boundary does not lose the arrow to the block in
 * front. Raising afterwards moves nothing in the plane, so every cut stays where it
 * was and the parts drawn by neighbouring cells meet exactly.
 *
 * Unlike paint the arrow is not cut to the plate's holes. It stands on the plate the
 * way a strip glued across a gap would, rather than being hollowed out where it
 * crosses one.
 *
 * <h2>How high</h2>
 *
 * The bottom of the walls is the plate's surface and the top is the arrow's height
 * above it, which is at least a tenth of a pixel. At that least height the top is
 * level with paint, which is drawn a tenth of a pixel up, and the two fight where
 * they overlap. Arrows that overlap on one surface are stacked a further fraction
 * apart in the order they were put on, so where a branch meets a bus at the same
 * height the two tops do not fight over the same pixels.
 *
 * Every polygon comes out wound counter-clockwise as seen from its normal, which is
 * the side it is seen from.
 */
public final class ArrowExtrusion {

    /** How much higher each arrow overlapping an earlier one on the same surface stands, in blocks. */
    public static final double STACK = 1 / 512.0;

    /** Slack in the quick test of a triangle against the cell. */
    private static final double MARGIN = 1.0e-6;

    /** The cell's own walls, as half-spaces in its own coordinates. */
    private static final double[][] CELL = {
            { 1, 0, 0, 0 }, { -1, 0, 0, -1 }, { 0, 1, 0, 0 }, { 0, -1, 0, -1 }, { 0, 0, 1, 0 }, { 0, 0, -1, -1 } };

    /** A convex polygon to draw, its outward normal and its colour. */
    public record Face(List<double[]> polygon, Vec3 normal, int colour) {}

    private ArrowExtrusion() {}

    /** How many earlier arrows on the same surface this one overlaps, which is how high it is stacked. */
    public static int stacked(List<PanelArrow> arrows, int index) {
        PanelArrow arrow = arrows.get(index);
        int[] mine = arrow.bounds();
        int below = 0;
        for (int i = 0; i < index; i++) {
            PanelArrow other = arrows.get(i);
            if (!other.plane().equals(arrow.plane()) || other.depth() != arrow.depth())
                continue;
            int[] theirs = other.bounds();
            if (theirs[0] < mine[2] && mine[0] < theirs[2] && theirs[1] < mine[3] && mine[1] < theirs[3])
                below++;
        }
        return below;
    }

    /** The faces of one arrow inside one cell. */
    public static void build(PanelArrow arrow, ArrowMesh mesh, BlockPos cell, int stack, Consumer<Face> out) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        PanelPlane plane = arrow.plane();
        double sunk = (arrow.depth() - PanelDefinition.THICKNESS / 2.0) / scale;
        double base = arrow.depth() / scale;
        double top = base + arrow.style().height() / scale + STACK * stack;
        Vec3 outward = plane.outward();
        Vec3 tu = plane.tangentU();
        Vec3 tv = plane.tangentV();

        // Measured from the cell's own place on the surface, so that nothing is the
        // small difference of two large numbers
        Vec3 origin = Vec3.atLowerCornerOf(cell);
        double u0 = plane.u(origin);
        double v0 = plane.v(origin);
        Vec3 corner = plane.at(u0, v0, sunk).subtract(origin);
        double[] c = { corner.x, corner.y, corner.z };
        double[] eu = { tu.x, tu.y, tu.z };
        double[] ev = { tv.x, tv.y, tv.z };
        double[] up = { outward.x * (top - sunk), outward.y * (top - sunk), outward.z * (top - sunk) };
        double[] down = { outward.x * (base - sunk), outward.y * (base - sunk), outward.z * (base - sunk) };

        double[] points = mesh.top();
        int[] colours = mesh.topColours();
        double[][] triangle = new double[3][];
        for (int t = 0; t < colours.length; t++) {
            for (int k = 0; k < 3; k++)
                triangle[k] = place(c, eu, ev, points[6 * t + 2 * k] - u0, points[6 * t + 2 * k + 1] - v0);
            if (!nearCell(triangle))
                continue;
            List<double[]> polygon = clip(List.of(triangle[0], triangle[1], triangle[2]));
            if (polygon.size() < 3)
                continue;
            List<double[]> lifted = new ArrayList<>(polygon.size());
            for (double[] point : polygon)
                lifted.add(plus(point, up));
            out.accept(new Face(wound(lifted, outward), outward, colours[t]));
        }

        double[] walls = mesh.walls();
        int[] wallColours = mesh.wallColours();
        for (int w = 0; w < wallColours.length; w++) {
            double[] a = place(c, eu, ev, walls[6 * w] - u0, walls[6 * w + 1] - v0);
            double[] b = place(c, eu, ev, walls[6 * w + 2] - u0, walls[6 * w + 3] - v0);
            double[] span = clipSegment(a, b);
            if (span == null)
                continue;
            double[] from = lerp(a, b, span[0]);
            double[] to = lerp(a, b, span[1]);
            double nu = walls[6 * w + 4];
            double nv = walls[6 * w + 5];
            Vec3 normal = new Vec3(tu.x * nu + tv.x * nv, tu.y * nu + tv.y * nv, tu.z * nu + tv.z * nv);
            List<double[]> quad = new ArrayList<>(4);
            quad.add(plus(from, down));
            quad.add(plus(to, down));
            quad.add(plus(to, up));
            quad.add(plus(from, up));
            out.accept(new Face(wound(quad, normal), normal, wallColours[w]));
        }
    }

    private static double[] place(double[] c, double[] eu, double[] ev, double u, double v) {
        return new double[] { c[0] + eu[0] * u + ev[0] * v, c[1] + eu[1] * u + ev[1] * v,
                c[2] + eu[2] * u + ev[2] * v };
    }

    private static double[] plus(double[] a, double[] b) {
        return new double[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    private static double[] lerp(double[] a, double[] b, double f) {
        return new double[] { a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f };
    }

    private static boolean nearCell(double[][] triangle) {
        for (int axis = 0; axis < 3; axis++) {
            double low = Math.min(triangle[0][axis], Math.min(triangle[1][axis], triangle[2][axis]));
            double high = Math.max(triangle[0][axis], Math.max(triangle[1][axis], triangle[2][axis]));
            if (high < -MARGIN || low > 1 + MARGIN)
                return false;
        }
        return true;
    }

    /** A convex polygon cut down to the inside of the cell. */
    private static List<double[]> clip(List<double[]> polygon) {
        for (double[] plane : CELL) {
            if (polygon.size() < 3)
                return polygon;
            List<double[]> kept = new ArrayList<>(polygon.size() + 2);
            int count = polygon.size();
            for (int i = 0; i < count; i++) {
                double[] from = polygon.get(i);
                double[] to = polygon.get((i + 1) % count);
                double a = side(plane, from);
                double b = side(plane, to);
                if (a >= 0)
                    kept.add(from);
                if ((a >= 0) != (b >= 0))
                    kept.add(lerp(from, to, a / (a - b)));
            }
            polygon = kept;
        }
        return polygon;
    }

    /** The part of a segment inside the cell, as fractions along it, or null for none. */
    private static double[] clipSegment(double[] a, double[] b) {
        double low = 0;
        double high = 1;
        for (double[] plane : CELL) {
            double sa = side(plane, a);
            double sb = side(plane, b);
            if (sa < 0 && sb < 0)
                return null;
            if (sa < 0)
                low = Math.max(low, sa / (sa - sb));
            else if (sb < 0)
                high = Math.min(high, sa / (sa - sb));
        }
        return high - low > 1e-9 ? new double[] { low, high } : null;
    }

    private static double side(double[] plane, double[] point) {
        return plane[0] * point[0] + plane[1] * point[1] + plane[2] * point[2] - plane[3];
    }

    /** The polygon turned, if it has to be, to wind counter-clockwise seen from along the normal. */
    static List<double[]> wound(List<double[]> polygon, Vec3 normal) {
        if (facing(polygon, normal) >= 0)
            return polygon;
        List<double[]> reversed = new ArrayList<>(polygon);
        Collections.reverse(reversed);
        return reversed;
    }

    /** Newell's normal of a polygon, dotted with a direction. */
    public static double facing(List<double[]> points, Vec3 normal) {
        double nx = 0;
        double ny = 0;
        double nz = 0;
        int count = points.size();
        for (int i = 0; i < count; i++) {
            double[] a = points.get(i);
            double[] b = points.get((i + 1) % count);
            nx += (a[1] - b[1]) * (a[2] + b[2]);
            ny += (a[2] - b[2]) * (a[0] + b[0]);
            nz += (a[0] - b[0]) * (a[1] + b[1]);
        }
        return nx * normal.x + ny * normal.y + nz * normal.z;
    }
}
