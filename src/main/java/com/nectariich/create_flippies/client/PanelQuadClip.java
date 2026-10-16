package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cuts a quad down to a box, keeping its texture.
 *
 * Needed by exactly one case, and worth naming for it: a flat panel lying at
 * forty-five degrees. Everywhere else a cell holds a piece of panel that is a box in
 * the plane's own frame, so it can be described rather than cut. There, both in-plane
 * axes run diagonally to the block grid, so the cell walls cross the rectangle
 * cornerwise and the piece is a polygon of up to eight sides — which no box can state
 * and no {@code cropAndMove} can produce, since cropping clamps to an axis-aligned box
 * in the material's own space and the shape wanted here is not one.
 *
 * So the tile is allowed to overhang its cell and is trimmed afterwards. Trimming is
 * Sutherland and Hodgman: walk the polygon, keep what is inside the plane, and where an
 * edge crosses, put a vertex on the crossing. Six planes, one per wall of the cell.
 * The input is convex and a half-space is convex, so every intermediate is convex too
 * and the vertices stay in order, which is what lets the result be fanned back into
 * quads at the end without triangulating anything properly.
 *
 * <h2>What is interpolated and what is carried</h2>
 *
 * Position and texture coordinate, because those are what vary across a face and
 * getting them wrong would slide the texture. Colour, light and normal are copied from
 * the vertex the new one was cut from: a quad here is one flat piece of one material,
 * so all four of its corners already agree on them, and interpolating between equal
 * values would only be a slower way to copy.
 */
final class PanelQuadClip {

    /** The four corners a baked quad always has. */
    private static final int CORNERS = 4;

    /** A vertex nearer a wall than this is treated as being on it. */
    private static final double EPSILON = 1.0e-7;

    private PanelQuadClip() {}

    /** The part of a quad inside the box, as however many quads that takes. */
    static void clip(BakedQuad quad, AABB box, List<BakedQuad> into) {
        List<int[]> polygon = corners(quad);
        for (int axis = 0; axis < 3; axis++) {
            polygon = inside(polygon, axis, low(box, axis), true);
            polygon = inside(polygon, axis, high(box, axis), false);
            if (polygon.size() < 3)
                return;
        }
        fan(polygon, quad, into);
    }

    private static List<int[]> corners(BakedQuad quad) {
        int stride = BakedQuadHelper.VERTEX_STRIDE;
        int[] vertices = quad.getVertices();
        List<int[]> polygon = new ArrayList<>(CORNERS + 2);
        for (int corner = 0; corner < CORNERS; corner++) {
            int[] vertex = new int[stride];
            System.arraycopy(vertices, corner * stride, vertex, 0, stride);
            polygon.add(vertex);
        }
        return polygon;
    }

    /**
     * The part of a polygon on the inner side of one wall.
     *
     * A vertex exactly on the wall is kept rather than cut, which is what stops a face
     * that lies flat against it from being thrown away or from growing a duplicate
     * vertex at every corner.
     */
    private static List<int[]> inside(List<int[]> polygon, int axis, double wall, boolean above) {
        List<int[]> kept = new ArrayList<>(polygon.size() + 2);
        for (int index = 0; index < polygon.size(); index++) {
            int[] from = polygon.get(index);
            int[] to = polygon.get((index + 1) % polygon.size());
            double here = depth(from, axis, wall, above);
            double there = depth(to, axis, wall, above);

            if (here >= -EPSILON)
                kept.add(from);
            if (here > EPSILON && there < -EPSILON || here < -EPSILON && there > EPSILON)
                kept.add(cut(from, to, here / (here - there)));
        }
        return kept;
    }

    /** How far inside the wall a vertex is; negative is outside. */
    private static double depth(int[] vertex, int axis, double wall, boolean above) {
        Vec3 position = BakedQuadHelper.getXYZ(vertex, 0);
        double along = axis == 0 ? position.x : axis == 1 ? position.y : position.z;
        return above ? along - wall : wall - along;
    }

    /** A vertex a fraction of the way along an edge, carrying everything but its place. */
    private static int[] cut(int[] from, int[] to, double fraction) {
        int[] vertex = from.clone();
        Vec3 start = BakedQuadHelper.getXYZ(from, 0);
        Vec3 end = BakedQuadHelper.getXYZ(to, 0);
        BakedQuadHelper.setXYZ(vertex, 0, start.add(end.subtract(start).scale(fraction)));
        BakedQuadHelper.setU(vertex, 0, lerp(BakedQuadHelper.getU(from, 0), BakedQuadHelper.getU(to, 0), fraction));
        BakedQuadHelper.setV(vertex, 0, lerp(BakedQuadHelper.getV(from, 0), BakedQuadHelper.getV(to, 0), fraction));
        return vertex;
    }

    private static float lerp(float from, float to, double fraction) {
        return (float) (from + (to - from) * fraction);
    }

    /**
     * A convex polygon as quads, taken two edges at a time from the first vertex.
     *
     * Four vertices come out as the one quad they already were, so the common case
     * costs nothing. An odd count ends on a triangle, which is sent as a quad with its
     * last vertex repeated — the shape Minecraft has always used for one.
     */
    private static void fan(List<int[]> polygon, BakedQuad quad, List<BakedQuad> into) {
        int stride = BakedQuadHelper.VERTEX_STRIDE;
        int count = polygon.size();
        for (int index = 1; index + 1 < count; index += 2) {
            int[] vertices = new int[CORNERS * stride];
            put(vertices, 0, polygon.get(0));
            put(vertices, 1, polygon.get(index));
            put(vertices, 2, polygon.get(index + 1));
            put(vertices, 3, polygon.get(Math.min(index + 2, count - 1)));
            into.add(new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                    quad.isShade()));
        }
    }

    private static void put(int[] vertices, int corner, int[] vertex) {
        System.arraycopy(vertex, 0, vertices, corner * BakedQuadHelper.VERTEX_STRIDE, vertex.length);
    }

    private static double low(AABB box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double high(AABB box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }
}
