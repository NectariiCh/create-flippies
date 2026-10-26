package com.nectariich.create_flippies.content.modem;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nectariich.create_flippies.content.pc.PcCaseGeometry;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where everything on the network modem is (spec 12.4.5), from its placeholder model
 * {@code models/block/network_modem/shell.json} and the two light partials beside it. The boxes
 * are in model space, in pixels, as authored, and follow the PC case's convention: the -x end is
 * the front (turned towards the player who placed it), the port is on the +x face, and a placed
 * modem turns the same way as a case ({@link PcCaseGeometry#toBlock}).
 *
 * The model is a stand-in until the real one is made: a new model only needs these boxes
 * updated to its {@code body}, {@code ethernet}, {@code power led} and {@code link led} cubes.
 */
public final class ModemGeometry {

    /** The one data port: ethernet, saved in links as port 0. */
    public static final int ETHERNET = 0, PORTS = 1;

    /** The case's box. */
    static final double[] BODY = {3, 0, 4, 13, 3, 12};
    /** The ethernet port cube, on the +x face. */
    static final double[] PORT = {13, 1, 7.25, 13.25, 2, 8.75};
    /** The lights on top, near the front: power (PoE present) and link/activity. */
    static final double[] POWER_LED = {4, 3, 5, 4.75, 3.25, 5.75};
    static final double[] LINK_LED = {4, 3, 6.25, 4.75, 3.25, 7};

    private static final Map<Direction, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    private ModemGeometry() {}

    /** The port cube grown outward along +x as the case's ports are, px. */
    public static double[] portBox() {
        double[] b = PORT.clone();
        b[3] += PcCaseGeometry.PORT_GROW;
        return b;
    }

    /** The middle of the port's outer face, px. */
    public static Vec3 portFaceCentre() {
        return new Vec3(PORT[3], (PORT[1] + PORT[4]) / 2, (PORT[2] + PORT[5]) / 2);
    }

    /** The outline and collision shape: the body, the grown port and the lights. */
    public static VoxelShape shape(Direction facing) {
        return SHAPES.computeIfAbsent(facing, f -> Shapes.or(box(BODY, f), box(portBox(), f), box(POWER_LED, f),
                box(LINK_LED, f)).optimize());
    }

    private static VoxelShape box(double[] b, Direction facing) {
        AABB a = PcCaseGeometry.toBlock(b, facing);
        return Block.box(a.minX * 16, a.minY * 16, a.minZ * 16, a.maxX * 16, a.maxY * 16, a.maxZ * 16);
    }
}
