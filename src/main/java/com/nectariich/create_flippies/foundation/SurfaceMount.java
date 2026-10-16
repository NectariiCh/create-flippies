package com.nectariich.create_flippies.foundation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The authoring convention this mod's panel-mounted devices share, and the
 * rotations that follow from it.
 *
 * Models are authored pointing at the ground: the mounting plate flush with the
 * floor of the block's own cell, whatever the operator reaches for sitting above
 * it, and the part that passes through the panel — barrel, contact rack,
 * terminals — hanging below y=0 into the cell underneath. So the authored pose
 * is {@code FACING = DOWN, ROTATION = 0}, FACING being where the plate points,
 * and placement is {@code clickedFace.getOpposite()}: click a panel's face and
 * the device lands against it with its contacts through it, reachable from the
 * far side.
 *
 * ROTATION spins the device about its own mounting axis in quarter turns, and
 * rotation 0 is the authored pose exactly — which is the pose that reads upright
 * on a wall. Turning it anywhere else from there stands a wall-mounted front on
 * its side or on its head.
 *
 * The orientation is {@code Ry(yaw) · Rx(tilt) · Ry(spin)}: spin the device
 * about its own axis, tilt that axis off vertical, then swing it round to the
 * facing. Every angle is a quarter turn, so the rotations below are exact
 * coordinate swaps rather than trigonometry — a terminal box has to land on the
 * pixel the renderer draws its stud at, and a hitbox 1e-16 out of place is a
 * wire that will not connect.
 *
 * <h2>The eighth turn</h2>
 *
 * A device screwed to a chamfer faces one of the four diagonals, which is half a
 * quarter turn off every facing there is. So the outer yaw admits an eighth-turn
 * step as well, applied after it and about the same axis, and named the way
 * {@link com.nectariich.create_flippies.foundation.panel.PanelPlane} names it: one
 * step from a facing is that facing turned an eighth towards the next, counted
 * south, west, north, east.
 *
 * It is the one rotation here that is not a coordinate swap, and the exactness
 * argument above survives it for the reason the panels' does. The turn is built
 * out of {@code sqrt(2)/2}, which IEEE 754 requires {@link Math#sqrt} to round
 * correctly and which is therefore the same bits everywhere — unlike
 * {@link Math#sin}, which is allowed a ulp of slack. So the client and the server
 * still derive the identical terminal box from the identical mount, which is the
 * property the wires actually need; what is given up is only that a point put
 * through {@link #point} and back through {@link #localPoint} returns within a
 * rounding rather than bit for bit, and nothing reads a local point that finely.
 */
public final class SurfaceMount {

    /** An eighth turn, exactly and reproducibly; see the class comment. */
    private static final double ROOT_HALF = Math.sqrt(2.0) / 2.0;

    /** The middle of an authored model, in pixels, and the point every turn holds still. */
    private static final double MIDDLE = 8;

    private SurfaceMount() {}

    /** Quarter turns about Y, applied last, swinging the mounting axis to {@code facing}. */
    public static int yawQuarters(Direction facing) {
        if (facing.getAxis().isVertical())
            return 0;
        // 180° - facing.toYRot(), in quarter turns
        return Math.floorMod(2 - (int) facing.toYRot() / 90, 4);
    }

    /** Quarter turns about X, tilting the authored downward mounting axis to {@code facing}'s axis. */
    public static int tiltQuarters(Direction facing) {
        return switch (facing) {
            case DOWN -> 0;
            case UP -> 2;
            default -> 1;
        };
    }

    /** Quarter turns about the device's own mounting axis. */
    public static int spinQuarters(int rotation) {
        return Math.floorMod(rotation, 4);
    }

    public static float yawDegrees(Direction facing) {
        return 90f * yawQuarters(facing);
    }

    /**
     * The same, an eighth of a turn further round per step of {@code yaw}.
     *
     * One number rather than two rotations because both are about the same axis and
     * therefore add: a renderer that already turns by {@link #yawDegrees} needs this
     * in its place and nothing else in the chain moves.
     */
    public static float yawDegrees(Direction facing, int yaw) {
        return yawDegrees(facing) - 45f * yaw;
    }

    public static float tiltDegrees(Direction facing) {
        return 90f * tiltQuarters(facing);
    }

    public static float spinDegrees(int rotation) {
        return 90f * spinQuarters(rotation);
    }

    /**
     * The quarter turn that puts the top of the front face towards whoever placed
     * the device. Walls need none — rotation 0 already reads upright there — so
     * this only has anything to say about a device lying flat on a floor or
     * ceiling, where there is no up to line it up with.
     */
    public static int rotationForPlacement(Direction facing, Direction placerFacing) {
        if (facing.getAxis().isHorizontal())
            return 0;
        int towardsPlacer = placerFacing.getOpposite().get2DDataValue();
        return facing == Direction.DOWN
                ? Math.floorMod(-towardsPlacer, 4)
                : Math.floorMod(towardsPlacer + 2, 4);
    }

    /** An authored model-space point, in pixels, moved to where this orientation puts it. */
    public static double[] point(double x, double y, double z, Direction facing, int rotation) {
        return point(x, y, z, facing, 0, rotation);
    }

    /** The same, for a facing turned {@code yaw} eighths past a cardinal one. */
    public static double[] point(double x, double y, double z, Direction facing, int yaw, int rotation) {
        double[] p = { x - MIDDLE, y - MIDDLE, z - MIDDLE };
        rotateY(p, spinQuarters(rotation));
        rotateX(p, tiltQuarters(facing));
        rotateY(p, yawQuarters(facing));
        rotateYEighths(p, yaw);
        p[0] += MIDDLE;
        p[1] += MIDDLE;
        p[2] += MIDDLE;
        return p;
    }

    /**
     * The authored point that this orientation puts at {@code (x, y, z)}: the
     * inverse of {@link #point}, and the way back from somewhere in the block to
     * somewhere on the authored model.
     *
     * Wanted whenever a click has to be read as a position on a device rather than
     * merely tested against its boxes — where along a run of DIN rail a breaker
     * should clip on, for one. Undoing quarter turns is exact, so a point put
     * through {@code point} and back through here returns bit for bit.
     */
    public static double[] localPoint(double x, double y, double z, Direction facing, int rotation) {
        return localPoint(x, y, z, facing, 0, rotation);
    }

    /** The same, for a facing turned {@code yaw} eighths past a cardinal one. */
    public static double[] localPoint(double x, double y, double z, Direction facing, int yaw, int rotation) {
        double[] p = { x - MIDDLE, y - MIDDLE, z - MIDDLE };
        // Each rotation is undone by its complement, and a complement of 4 lands on
        // the no-op branch, which is what undoing a turn of none has to do
        rotateYEighths(p, -yaw);
        rotateY(p, 4 - yawQuarters(facing));
        rotateX(p, 4 - tiltQuarters(facing));
        rotateY(p, 4 - spinQuarters(rotation));
        p[0] += MIDDLE;
        p[1] += MIDDLE;
        p[2] += MIDDLE;
        return p;
    }

    /**
     * An authored pixel box {@code {minX, minY, minZ, maxX, maxY, maxZ}} moved to
     * this orientation. Quarter turns take a box to a box, so rotating the two
     * corners and sorting the results is exact.
     */
    public static double[] box(double[] box, Direction facing, int rotation) {
        double[] a = point(box[0], box[1], box[2], facing, rotation);
        double[] b = point(box[3], box[4], box[5], facing, rotation);
        return new double[] {
                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]) };
    }

    /**
     * Right-handed rotation about +Y, in eighths of a turn, in the same sense as
     * {@link #rotateY} — so one step is that method's quarter turn halved.
     *
     * The sense is fixed by what a yaw has to mean: one step past a facing is that
     * facing turned an eighth towards the next one counted south, west, north, east,
     * and the quarter turns above run the other way round, so a step forward in yaw
     * is a step back of forty-five degrees here. Getting this backwards puts every
     * chamfered control on the wrong diagonal, which is why it is derived in one
     * place and read from there.
     */
    public static void rotateYEighths(double[] p, int eighths) {
        for (int step = 0; step < Math.abs(eighths); step++) {
            double x = p[0];
            double z = p[2];
            if (eighths > 0) {
                p[0] = (x - z) * ROOT_HALF;
                p[2] = (x + z) * ROOT_HALF;
            } else {
                p[0] = (x + z) * ROOT_HALF;
                p[2] = (z - x) * ROOT_HALF;
            }
        }
    }

    /** The union of authored pixel boxes, moved to this orientation. */
    public static VoxelShape shape(double[][] boxes, Direction facing, int rotation) {
        List<VoxelShape> parts = new ArrayList<>(boxes.length);
        for (double[] authored : boxes) {
            double[] b = box(authored, facing, rotation);
            parts.add(Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return ShapeUnion.of(parts);
    }

    /** Right-handed rotation about +Y, in quarter turns. */
    private static void rotateY(double[] p, int quarters) {
        double x = p[0];
        double z = p[2];
        switch (quarters) {
            case 1 -> { p[0] = z;  p[2] = -x; }
            case 2 -> { p[0] = -x; p[2] = -z; }
            case 3 -> { p[0] = -z; p[2] = x;  }
            default -> { }
        }
    }

    /** Right-handed rotation about +X, in quarter turns. */
    private static void rotateX(double[] p, int quarters) {
        double y = p[1];
        double z = p[2];
        switch (quarters) {
            case 1 -> { p[1] = -z; p[2] = y;  }
            case 2 -> { p[1] = -y; p[2] = -z; }
            case 3 -> { p[1] = z;  p[2] = -y; }
            default -> { }
        }
    }
}
