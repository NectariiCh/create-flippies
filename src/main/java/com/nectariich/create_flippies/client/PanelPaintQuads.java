package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.content.decal.CellPaint;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

/**
 * The paint on one panel cell, as quads for the chunk mesh.
 *
 * Paint is baked with the plate rather than drawn every frame, so a wall covered in
 * pictures costs nothing once its chunk is built: the same bargain the plate
 * itself makes, and the reason paint is part of the panel's model at all.
 *
 * <h2>Clipping to the plate</h2>
 *
 * Each cell draws only its own share of a picture, and only where there is plate
 * on the picture's surface: every triangle is cut against the cell's walls and
 * against each rectangle the panels of that surface are left as once their holes
 * are taken out. Both are convex, so cutting is plain Sutherland–Hodgman against a
 * handful of planes and the pieces come out convex.
 *
 * The cutting is done with the paint sunk to the middle of the plate and the result
 * lifted back out afterwards. A plate whose surface lies on a block boundary has its
 * surface exactly on the cell's wall, and paint lifted off it before the cut would
 * be on the wrong side of the wall and cut away entirely; the middle of the plate is
 * inside every cell the plate is in, by the same rule that decides which cells a
 * panel takes up. Moving along the normal changes nothing in the plane, so the lift
 * afterwards leaves every cut exactly where it was.
 *
 * <h2>How far off the plate</h2>
 *
 * A tenth of a pixel, which is as thin as paint can be drawn without the plate
 * showing through it at a distance, and pictures that overlap on one surface are
 * stacked a further fraction apart in the order they were painted. Within one
 * picture nothing overlaps, which is what the importer flattened it for.
 *
 * The quads are lit by {@link PanelQuadLight} exactly as the plate under them is, so
 * paint darkens with the panel it is on rather than glowing over it.
 */
final class PanelPaintQuads {

    /** A white square in the block atlas, which every quad samples the middle of; the colour is in the vertices. */
    static final ResourceLocation PAINT = CreateFlippies.asResource("block/paint");

    private static final double PROUD = 1 / 160.0;

    /** How much further each picture overlapping an earlier one on the same surface stands off. */
    private static final double STACK = 1 / 512.0;

    private static final int VERTICES = 4;

    /** Slack in the quick test of a triangle against the cell, so one lying along a wall is not dropped. */
    private static final double MARGIN = 1.0e-6;

    /** The cell's own walls, as half-spaces in its own coordinates. */
    private static final double[][] CELL = {
            { 1, 0, 0, 0 }, { -1, 0, 0, -1 }, { 0, 1, 0, 0 }, { 0, -1, 0, -1 }, { 0, 0, 1, 0 }, { 0, 0, -1, -1 } };

    private PanelPaintQuads() {}

    static void build(CellPaint paint, PanelQuadLight light, List<BakedQuad> into) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(PAINT);
        List<PanelDecal> decals = paint.decals();
        for (int index = 0; index < decals.size(); index++) {
            PanelDecal decal = decals.get(index);
            DecalArt art = ClientDecals.art(decal.art());
            if (art == null)
                continue;
            List<double[][]> plates = plates(paint.panels(), decal.cellPlane(), paint.cell());
            if (plates.isEmpty())
                continue;
            paint(decal, art, paint.cell(), stacked(decals, index), plates, sprite, light, into);
        }
    }

    /** How many earlier pictures on the same surface this one overlaps, which is how high it is stacked. */
    private static int stacked(List<PanelDecal> decals, int index) {
        PanelDecal decal = decals.get(index);
        int below = 0;
        for (int i = 0; i < index; i++) {
            PanelDecal other = decals.get(i);
            if (other.plane().equals(decal.plane()) && other.depth() == decal.depth() && other.minU() < decal.maxU()
                    && decal.minU() < other.maxU() && other.minV() < decal.maxV() && decal.minV() < other.maxV())
                below++;
        }
        return below;
    }

    /**
     * Every rectangle of plate on the surface, as the four half-spaces that bound it
     * in the cell's coordinates.
     */
    private static List<double[][]> plates(List<PanelDefinition> panels, PanelPlane surface, BlockPos cell) {
        Vec3 origin = Vec3.atLowerCornerOf(cell);
        double scale = PanelPlane.UNITS_PER_BLOCK;
        List<double[][]> plates = new ArrayList<>();
        for (PanelDefinition panel : panels) {
            if (!panel.plane().sameSurfaceAs(surface))
                continue;
            Vec3 u = panel.plane().tangentU();
            Vec3 v = panel.plane().tangentV();
            double uAt = u.dot(origin);
            double vAt = v.dot(origin);
            for (PanelDefinition piece : panel.pieces())
                plates.add(new double[][] {
                        { u.x, u.y, u.z, piece.minU() / scale - uAt },
                        { -u.x, -u.y, -u.z, uAt - piece.maxU() / scale },
                        { v.x, v.y, v.z, piece.minV() / scale - vAt },
                        { -v.x, -v.y, -v.z, vAt - piece.maxV() / scale } });
        }
        return plates;
    }

    private static void paint(PanelDecal decal, DecalArt art, BlockPos cell, int stack, List<double[][]> plates,
            TextureAtlasSprite sprite, PanelQuadLight light, List<BakedQuad> into) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double sunk = (decal.depth() - PanelDefinition.THICKNESS / 2.0) / scale;
        double drawn = decal.depth() / scale + PROUD + STACK * stack;
        Vec3 outward = decal.plane().outward();
        double[] lift = { outward.x * (drawn - sunk), outward.y * (drawn - sunk), outward.z * (drawn - sunk) };
        Direction named = Direction.getNearest((float) outward.x, (float) outward.y, (float) outward.z);

        // The picture's box lands on the plate as a parallelogram (a rectangle, in
        // fact), so where any point of it goes is a corner plus two edge vectors
        Vec3 origin = Vec3.atLowerCornerOf(cell);
        double[] corner = local(decal, 0, 0, sunk, origin);
        double[] across = minus(local(decal, 1, 0, sunk, origin), corner);
        double[] down = minus(local(decal, 0, 1, sunk, origin), corner);

        Quads quads = new Quads(sprite, light, outward, named, lift, into);
        double[][] triangle = new double[3][3];
        List<DecalArt.Layer> layers = new ArrayList<>(art.layers());
        layers.addAll(art.pixels());
        for (DecalArt.Layer layer : layers) {
            quads.colour = abgr(decal.tinted() ? decal.tint() : layer.colour());
            float[] points = layer.triangles();
            for (int t = 0; t < points.length; t += 6) {
                for (int k = 0; k < 3; k++) {
                    double x = points[t + 2 * k];
                    double y = points[t + 2 * k + 1];
                    for (int axis = 0; axis < 3; axis++)
                        triangle[k][axis] = corner[axis] + across[axis] * x + down[axis] * y;
                }
                if (!nearCell(triangle))
                    continue;
                List<double[]> inCell = clip(List.of(triangle[0].clone(), triangle[1].clone(),
                        triangle[2].clone()), CELL);
                if (inCell.size() < 3)
                    continue;
                for (double[][] plate : plates) {
                    List<double[]> polygon = clip(inCell, plate);
                    if (polygon.size() >= 3)
                        quads.emit(polygon);
                }
            }
        }
    }

    /** A point of the picture's box, sunk to the given depth, in the cell's coordinates. */
    private static double[] local(PanelDecal decal, double x, double y, double depth, Vec3 origin) {
        double[] uv = new double[2];
        decal.map(x, y, uv);
        Vec3 at = decal.plane().at(uv[0], uv[1], depth).subtract(origin);
        return new double[] { at.x, at.y, at.z };
    }

    private static double[] minus(double[] a, double[] b) {
        return new double[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
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

    /** A convex polygon cut down to the side of each plane its normal points into. */
    private static List<double[]> clip(List<double[]> polygon, double[][] planes) {
        for (double[] plane : planes) {
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
                if ((a >= 0) != (b >= 0)) {
                    double f = a / (a - b);
                    kept.add(new double[] { from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f,
                            from[2] + (to[2] - from[2]) * f });
                }
            }
            polygon = kept;
        }
        return polygon;
    }

    private static double side(double[] plane, double[] point) {
        return plane[0] * point[0] + plane[1] * point[1] + plane[2] * point[2] - plane[3];
    }

    /** ARGB as a baked vertex holds it, which is red in the lowest byte. */
    private static int abgr(int argb) {
        return (argb & 0xFF000000) | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
    }

    /** Turns convex polygons into lit quads facing out of the plate. */
    private static final class Quads {

        private final TextureAtlasSprite sprite;
        private final PanelQuadLight light;
        private final Vec3 outward;
        private final Direction named;
        private final double[] lift;
        private final List<BakedQuad> into;
        private final float spriteU;
        private final float spriteV;
        int colour;

        Quads(TextureAtlasSprite sprite, PanelQuadLight light, Vec3 outward, Direction named, double[] lift,
                List<BakedQuad> into) {
            this.sprite = sprite;
            this.light = light;
            this.outward = outward;
            this.named = named;
            this.lift = lift;
            this.into = into;
            // The sprite centre: 1.21 takes the position as 0..1 instead of 0..16
            //? if forge {
            this.spriteU = sprite.getU(8);
            this.spriteV = sprite.getV(8);
            //?} else {
            /*this.spriteU = sprite.getU(0.5f);
            this.spriteV = sprite.getV(0.5f);
            *///?}
        }

        /**
         * A convex polygon as a fan of quads, two triangles to a quad where there are two
         * to spare and one doubled vertex where there is one. Wound to face out, which
         * is the only side paint is seen from.
         */
        void emit(List<double[]> polygon) {
            List<double[]> points = new ArrayList<>(polygon);
            if (facing(points) < 0)
                java.util.Collections.reverse(points);
            int count = points.size();
            for (int i = 1; i + 1 < count; i += 2) {
                double[] third = i + 2 < count ? points.get(i + 2) : points.get(i + 1);
                quad(points.get(0), points.get(i), points.get(i + 1), third);
            }
        }

        /** Newell's normal of the polygon, dotted with the way the paint faces. */
        private double facing(List<double[]> points) {
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
            return nx * outward.x + ny * outward.y + nz * outward.z;
        }

        private void quad(double[]... corners) {
            int stride = BakedQuadHelper.VERTEX_STRIDE;
            int[] vertices = new int[VERTICES * stride];
            for (int k = 0; k < VERTICES; k++) {
                double[] p = corners[k];
                BakedQuadHelper.setXYZ(vertices, k, new Vec3(p[0] + lift[0], p[1] + lift[1], p[2] + lift[2]));
                vertices[k * stride + BakedQuadHelper.COLOR_OFFSET] = colour;
                BakedQuadHelper.setU(vertices, k, spriteU);
                BakedQuadHelper.setV(vertices, k, spriteV);
                BakedQuadHelper.setNormalXYZ(vertices, k, outward);
            }
            BakedQuad quad = new BakedQuad(vertices, -1, named, sprite, true);
            into.add(light == null ? quad : light.apply(quad, outward));
        }
    }
}
