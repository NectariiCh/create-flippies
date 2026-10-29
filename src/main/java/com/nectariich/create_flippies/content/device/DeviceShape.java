package com.nectariich.create_flippies.content.device;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The geometry of one kind of device: its model's boxes in model pixels as authored
 * (a 16 px cell), and how a placed one is turned.
 *
 * A device stands with the bottom centre of its model's cell, (8, 0, 8) px, at the
 * entity's position, turned about the vertical through it in {@link #STEPS} steps of
 * {@link #STEP_DEGREES}. Step k faces the front of the model towards Minecraft yaw
 * k * 11.25 degrees (0 south, 90 west), so steps 0, 8, 16 and 24 are a block's four
 * facings.
 *
 * Two shapes come from the boxes. Clicks are traced against the boxes themselves,
 * turned exactly ({@link #clip}). Collision is a {@link VoxelShape}, which can only
 * be axis aligned: at a quarter turn the turned boxes exactly, at any other step the
 * boxes filled in whole pixel columns, each column whose middle the turned box covers
 * ({@link #localShape}). The entity's position is on the pixel grid, so those columns
 * are the world's pixel columns.
 */
public final class DeviceShape {

    public static final int STEPS = 32;
    public static final double STEP_DEGREES = 360.0 / STEPS;

    /** Boxes that are hit and collided with, px. */
    private final double[][] solid;
    /** Boxes that are only hit (ports too small to stand on), px. */
    private final double[][] pickOnly;
    /** The yaw of the model's front at turn 0 plus this is the turn applied to the model, degrees. */
    private final double frontOffset;

    private final VoxelShape[] localShapes = new VoxelShape[STEPS];
    private final AABB[] localBounds = new AABB[STEPS];

    /**
     * @param front the model's front: +1 if it is the +x side, -1 if it is the -x side
     */
    public DeviceShape(double[][] solid, double[][] pickOnly, int front) {
        this.solid = solid;
        this.pickOnly = pickOnly;
        this.frontOffset = front > 0 ? -90 : 90;
    }

    /** The yaw a device turned {@code step} faces, degrees, Minecraft's convention. */
    public static float yaw(int step) {
        return (float) (Math.floorMod(step, STEPS) * STEP_DEGREES);
    }

    /** The turn nearest to facing yaw {@code yaw}, degrees. */
    public static int stepFor(double yaw) {
        return Math.floorMod((int) Math.round(yaw / STEP_DEGREES), STEPS);
    }

    /** The turn applied to the model for {@code step}, degrees, about +y (x towards -z for a positive turn). */
    public double turnDegrees(int step) {
        return -yaw(step) + frontOffset;
    }

    /** {@code v} turned about +y by {@code degrees}: x' = x cos + z sin, z' = z cos - x sin, as JOML's rotateY. */
    public static Vec3 rotate(Vec3 v, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3(v.x * c + v.z * s, v.y, v.z * c - v.x * s);
    }

    /** A model point, px, as a point relative to the entity's position, blocks. */
    public Vec3 toLocal(Vec3 modelPx, int step) {
        return rotate(new Vec3((modelPx.x - 8) / 16, modelPx.y / 16, (modelPx.z - 8) / 16), turnDegrees(step));
    }

    /** A point relative to the entity's position, blocks, as a model point, px. */
    public Vec3 toModel(Vec3 local, int step) {
        Vec3 m = rotate(local, -turnDegrees(step));
        return new Vec3(m.x * 16 + 8, m.y * 16, m.z * 16 + 8);
    }

    /** A direction in the model as one in the world. */
    public Vec3 directionToLocal(Vec3 modelDirection, int step) {
        return rotate(modelDirection, turnDegrees(step));
    }

    /** The collision shape relative to the entity's position. */
    public VoxelShape localShape(int step) {
        int s = Math.floorMod(step, STEPS);
        VoxelShape shape = localShapes[s];
        if (shape == null)
            localShapes[s] = shape = buildShape(s);
        return shape;
    }

    /** The box round everything that is hit or collided with, relative to the entity's position. */
    public AABB localBounds(int step) {
        int s = Math.floorMod(step, STEPS);
        AABB bounds = localBounds[s];
        if (bounds == null) {
            for (double[][] boxes : new double[][][] {solid, pickOnly})
                for (double[] b : boxes)
                    for (Vec3 corner : corners(b)) {
                        Vec3 p = toLocal(corner, s);
                        bounds = bounds == null ? new AABB(p, p) : bounds.minmax(new AABB(p, p));
                    }
            localBounds[s] = bounds;
        }
        return bounds;
    }

    private static Vec3[] corners(double[] b) {
        return new Vec3[] {new Vec3(b[0], b[1], b[2]), new Vec3(b[3], b[1], b[2]), new Vec3(b[0], b[1], b[5]),
                new Vec3(b[3], b[1], b[5]), new Vec3(b[0], b[4], b[2]), new Vec3(b[3], b[4], b[2]),
                new Vec3(b[0], b[4], b[5]), new Vec3(b[3], b[4], b[5])};
    }

    private VoxelShape buildShape(int step) {
        List<VoxelShape> parts = new ArrayList<>();
        for (double[] b : solid) {
            if (step % (STEPS / 4) == 0) {
                Vec3 p = toLocal(new Vec3(b[0], b[1], b[2]), step), q = toLocal(new Vec3(b[3], b[4], b[5]), step);
                parts.add(Shapes.create(new AABB(p, q)));
                continue;
            }
            columns(b, step, parts);
        }
        VoxelShape shape = Shapes.empty();
        for (VoxelShape part : parts)
            shape = Shapes.joinUnoptimized(shape, part, BooleanOp.OR);
        return shape.optimize();
    }

    /** {@code b} turned and filled in pixel columns, each run of columns along z one box. */
    private void columns(double[] b, int step, List<VoxelShape> out) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 corner : corners(b)) {
            Vec3 p = toLocal(corner, step).scale(16);
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        double y0 = b[1] / 16, y1 = b[4] / 16;
        for (int i = (int) Math.floor(minX); i < (int) Math.ceil(maxX); i++) {
            int runStart = Integer.MIN_VALUE;
            for (int k = (int) Math.floor(minZ); k <= (int) Math.ceil(maxZ); k++) {
                boolean in = k < (int) Math.ceil(maxZ) && covers(b, step, i + 0.5, k + 0.5);
                if (in && runStart == Integer.MIN_VALUE)
                    runStart = k;
                if (!in && runStart != Integer.MIN_VALUE) {
                    out.add(Shapes.create(i / 16.0, y0, runStart / 16.0, (i + 1) / 16.0, y1, k / 16.0));
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
    }

    /** Whether the local point (x, z) px lies inside the turned box's footprint. */
    private boolean covers(double[] b, int step, double xPx, double zPx) {
        Vec3 m = toModel(new Vec3(xPx / 16, 0, zPx / 16), step);
        return m.x >= b[0] && m.x <= b[3] && m.z >= b[2] && m.z <= b[5];
    }

    /** Where a segment first meets a box, in model px, and how far along it, 0 to 1. */
    public record Hit(Vec3 modelPoint, double fraction) {}

    /**
     * Where the segment from {@code from} to {@code to}, relative to the entity's
     * position, first meets a box, solid or pick-only; null if it misses.
     */
    public Hit clip(Vec3 from, Vec3 to, int step) {
        Vec3 a = toModel(from, step), b = toModel(to, step);
        double length = a.distanceTo(b);
        if (length < 1e-9)
            return null;
        Hit best = null;
        for (double[][] boxes : new double[][][] {solid, pickOnly})
            for (double[] box : boxes) {
                var hit = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]).clip(a, b);
                if (hit.isEmpty())
                    continue;
                double fraction = a.distanceTo(hit.get()) / length;
                if (best == null || fraction < best.fraction())
                    best = new Hit(hit.get(), fraction);
            }
        return best;
    }
}
