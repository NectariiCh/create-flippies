package com.nectariich.create_flippies.content.crt;

import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.content.pc.PcCaseGeometry;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where everything on the CRT is, from the model `crt-1.json` (spec 11.2.14), in
 * model pixels as authored.
 *
 * The screen is on the model's +x side and the two ports on its -x side, so the
 * front is +x: a CRT whose {@code facing} is F has its screen towards F, which is
 * the case's turning ({@link PcCaseGeometry}, model -x towards a direction) applied
 * with the opposite of F.
 */
public final class CrtGeometry {

    /** The data port and its index: one VGA input. */
    public static final int VGA_IN = 0, PORTS = 1;

    /** The cabinet and the frame round the screen, as one box. */
    static final double[] BODY = {0.5, 0, 1, 12, 12, 15};
    /** The `screen` cube; its +x face (x = 11.75) shows the picture. */
    public static final double[] SCREEN = {11, 1.5, 2.5, 11.75, 10.5, 13.5};
    /** The `vga in` and `power in` cubes, on the back (their -x faces at x = 0, the block's edge). */
    static final double[] VGA_PORT = {0, 0.5, 1.5, 0.5, 1.5, 3.25};
    static final double[] SOCKET = {0, 0.5, 4, 0.5, 1.5, 5.75};
    /** The plug synthesised behind the socket (tools/gen_crt.py), drawn while a cord is in. */
    static final double[] POWER_PLUG = {-1.25, 0.75, 4.25, 0, 1.25, 5.5};
    /** Where Power Grid is told the socket terminals are: inside the cabinet, out of every ray's reach (as the case's). */
    public static final double[] HIDDEN_TERMINAL = {5.75, 5.75, 7.75, 6.25, 6.25, 8.25};

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL)
            SHAPES.put(facing, Shapes.or(box(BODY, facing), box(VGA_PORT, facing), box(SOCKET, facing)).optimize());
    }

    private CrtGeometry() {}

    /** The direction the model's -x end faces: the back. */
    public static Direction back(Direction facing) {
        return facing.getOpposite();
    }

    /** A model box, px, as a block-local box in block units for a CRT facing {@code facing}. */
    public static AABB toBlock(double[] box, Direction facing) {
        return PcCaseGeometry.toBlock(box, back(facing));
    }

    /** A model point, px, as a block-local point in block units. */
    public static Vec3 toBlock(Vec3 modelPx, Direction facing) {
        return PcCaseGeometry.toBlock(modelPx, back(facing));
    }

    /** A block-local point, block units, in model space, px. */
    public static Vec3 toModel(Vec3 local, Direction facing) {
        return PcCaseGeometry.toModel(local, back(facing));
    }

    /** The y rotation the renderer turns a part model by, degrees: the block state's. */
    public static float yRotationDegrees(Direction facing) {
        return PcCaseGeometry.yRotationDegrees(back(facing));
    }

    public static VoxelShape shape(Direction facing) {
        return SHAPES.get(facing);
    }

    /** The middle of a back cube's outer (-x) face, px. */
    static Vec3 backFaceCentre(double[] b) {
        return new Vec3(b[0], (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    /** Whether a model point, px, is on the socket (with a hair's slack for a point on its face). */
    static boolean onSocket(Vec3 p) {
        double e = 1e-3;
        return p.x >= SOCKET[0] - e && p.x <= SOCKET[3] + e && p.y >= SOCKET[1] - e && p.y <= SOCKET[4] + e
                && p.z >= SOCKET[2] - e && p.z <= SOCKET[5] + e;
    }

    private static VoxelShape box(double[] b, Direction facing) {
        AABB a = toBlock(b, facing);
        return Block.box(a.minX * 16, a.minY * 16, a.minZ * 16, a.maxX * 16, a.maxY * 16, a.maxZ * 16);
    }
}
