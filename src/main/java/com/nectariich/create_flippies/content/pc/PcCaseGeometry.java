package com.nectariich.create_flippies.content.pc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.nectariich.create_flippies.content.data_cord.PortRole;
import com.nectariich.create_flippies.content.data_cord.PortType;
import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where everything on the case is, from the model `pc-case-1.json` (spec 11.2.1),
 * and what a click lands on.
 *
 * Boxes are in model space, in pixels, as authored: the model's -x end (buttons,
 * floppy slot) is the PC's front, the ports are on its +x face, and the removable
 * front panel closes the -z side. A placed case is a device ({@link PcCaseEntity}),
 * the model turned as {@link DeviceShape} turns it; {@link #toBlock} is the same
 * turn in quarters, about a block's vertical centre line. `tools/gen_pc_case.py`
 * prints the export's boxes in this order, and {@code PcCaseGeometryTest} reads the
 * export and checks them.
 *
 * Picking: every box a click can mean is one of the device's boxes, so the point
 * its trace meets is already the nearest hit; the target is the smallest box that
 * contains it.
 */
public final class PcCaseGeometry {

    /** The data ports, in the frozen order links are saved with. Never reorder. */
    public static final int PS2_1 = 0, PS2_2 = 1, ETHERNET = 2, COM1 = 3, COM2 = 4, COM3 = 5, VGA1 = 6, VGA2 = 7,
            VGA3 = 8, PORTS = 9;

    /** A port's connector type, by port index (spec 11.2.5). */
    public static PortType portType(int port) {
        return switch (port) {
            case PS2_1, PS2_2 -> PortType.PS2;
            case ETHERNET -> PortType.ETHERNET;
            case COM1, COM2, COM3 -> PortType.COM;
            default -> PortType.VGA;
        };
    }

    /** A port's role: PS/2 host, VGA source, the rest peers (spec 11.2.5). */
    public static PortRole portRole(int port) {
        return switch (portType(port)) {
            case PS2 -> PortRole.HOST;
            case VGA -> PortRole.SOURCE;
            default -> PortRole.PEER;
        };
    }

    /** How far a port's click box reaches out from its face, px; across, it stays the cube's size. */
    public static final double PORT_GROW = 0.375;

    /** The port cubes, {from, to} px, in port order. */
    static final double[][] PORT_BOXES = {
            {14, 6.75, 10, 14.25, 7.5, 10.75},
            {14, 6.75, 9, 14.25, 7.5, 9.75},
            {14, 6.75, 7, 14.25, 7.5, 8.25},
            {14, 5.5, 9.375, 14.25, 6.25, 10.75},
            {14, 5.5, 7.875, 14.25, 6.25, 9.25},
            {14, 5.5, 6.375, 14.25, 6.25, 7.75},
            {14, 4.25, 9.25, 14.25, 5, 10.75},
            {14, 4.25, 7.5, 14.25, 5, 9},
            {14, 4.25, 5.75, 14.25, 5, 7.25},
    };
    static final double[] SOCKET = {14, 1.25, 9, 14.25, 3, 10.75};
    static final double[] POWER_BUTTON = {2.25, 13, 5, 3.25, 13.25, 6};
    static final double[] RESET_BUTTON = {2.25, 13, 10, 3.25, 13.25, 11};
    static final double[] POWER_LED = {2.5, 13, 6.55, 3, 13.25, 7.05};
    static final double[] ERROR_LED = {2.5, 13, 7.35, 3, 13.25, 7.85};
    static final double[] FRONT_PANEL = {2, 0, 4, 14, 13, 5};
    static final double[] MOTHERBOARD = {5, 2.625, 10.25, 13, 11.625, 10.5};
    static final double[] FLOPPY_DISK = {1, 9.625, 5.75, 5, 9.875, 10.25};
    static final double[] RAM_A = {7, 6.625, 9.25, 7.25, 10.625, 10.5};
    static final double[] RAM_B = {7.75, 6.625, 9.25, 8, 10.625, 10.5};
    static final double[] HDD = {10.5, 3.125, 9.75, 12.25, 5.625, 10.5};
    static final double[] CPU = {9.25, 8.125, 9.75, 11.25, 9.625, 10.5};
    /** The case group's walls: top, bottom, back (+z), front (-x) and rear (+x). */
    static final double[][] SHELL = {
            {2, 12, 5, 14, 13, 12},
            {2, 0, 5, 14, 1, 11},
            {2, 0, 11, 14, 12, 12},
            {2, 1, 5, 3, 12, 11},
            {13, 1, 5, 14, 12, 11},
    };

    /**
     * The built-in drive's slot: where the disk's -x end sticks out of the front
     * wall, grown a quarter pixel up and down so it can be hit with no disk in.
     */
    static final double[] FLOPPY_SLOT = {1, 9.375, 5.75, 3, 10.125, 10.25};

    /** The open inside, reached through the -z side with the panel off; clicks on its walls install. */
    static final double[] INTERIOR = {3, 1, 5, 13, 12, 11};

    /** The plug synthesised in front of the socket (gen_pc_case.py). */
    static final double[] POWER_PLUG = {14.25, 1.5, 9.25, 15.5, 2.75, 10.5};

    /**
     * Where Power Grid is told the two socket terminals are: a speck in the middle
     * of the bottom wall, which no ray can reach. On the socket they would catch the
     * cord's click as a single-conductor "half" connection before the cord asks
     * {@code IAcceptCord}, so the cord could not plug in with one click.
     */
    public static final double[] HIDDEN_TERMINAL = {7.75, 0.375, 7.75, 8.25, 0.625, 8.25};

    /** What a click landed on. */
    public enum Kind {
        PORT,
        SOCKET,
        POWER_BUTTON,
        RESET_BUTTON,
        FLOPPY_SLOT,
        PART,
        INTERIOR,
        SHELL
    }

    /** A click's target: {@code port} for PORT, {@code slot} for PART, else unused. */
    public record Target(Kind kind, int port, PcSlot slot) {
        static Target of(Kind kind) {
            return new Target(kind, -1, null);
        }
    }

    private static final Map<Integer, DeviceShape> SHAPES = new ConcurrentHashMap<>();

    private PcCaseGeometry() {}

    // Model boxes by role

    /** A port's cube grown outward along +x, px. */
    public static double[] portBox(int port) {
        double[] b = PORT_BOXES[port].clone();
        b[3] += PORT_GROW;
        return b;
    }

    /** The socket grown outward the same way. */
    public static double[] socketBox() {
        double[] b = SOCKET.clone();
        b[3] += PORT_GROW;
        return b;
    }

    /** The box of an installed part, px. */
    public static double[] partBox(PcSlot slot) {
        return switch (slot) {
            case FRONT_PANEL -> FRONT_PANEL;
            case MOTHERBOARD -> MOTHERBOARD;
            case CPU -> CPU;
            case RAM_A -> RAM_A;
            case RAM_B -> RAM_B;
            case HDD -> HDD;
            case FLOPPY -> FLOPPY_DISK;
        };
    }

    /** The middle of a port's outer face (x = 14.25), px. */
    public static Vec3 portFaceCentre(int port) {
        double[] b = PORT_BOXES[port];
        return new Vec3(b[3], (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    /** The middle of the socket's outer face, px. */
    public static Vec3 socketFaceCentre() {
        return new Vec3(SOCKET[3], (SOCKET[1] + SOCKET[4]) / 2, (SOCKET[2] + SOCKET[5]) / 2);
    }

    // Turning with the case

    /** Quarter turns clockwise (seen from above) that take the model's -x end to {@code facing}. */
    public static int quarterTurns(Direction facing) {
        return switch (facing) {
            case NORTH -> 1;
            case EAST -> 2;
            case SOUTH -> 3;
            default -> 0;
        };
    }

    /** A model point, px, to block-local block units. */
    public static Vec3 toBlock(Vec3 modelPx, Direction facing) {
        double dx = modelPx.x - 8, dz = modelPx.z - 8;
        for (int i = 0; i < quarterTurns(facing); i++) {
            double t = dx;
            dx = -dz;
            dz = t;
        }
        return new Vec3((dx + 8) / 16, modelPx.y / 16, (dz + 8) / 16);
    }

    /** A block-local point, block units, to model space, px. */
    public static Vec3 toModel(Vec3 local, Direction facing) {
        double dx = local.x * 16 - 8, dz = local.z * 16 - 8;
        for (int i = 0; i < quarterTurns(facing); i++) {
            double t = dx;
            dx = dz;
            dz = -t;
        }
        return new Vec3(dx + 8, local.y * 16, dz + 8);
    }

    /** A model box, px, turned with the case, as a block-local box in block units. */
    public static AABB toBlock(double[] box, Direction facing) {
        Vec3 a = toBlock(new Vec3(box[0], box[1], box[2]), facing);
        Vec3 b = toBlock(new Vec3(box[3], box[4], box[5]), facing);
        return new AABB(a, b);
    }

    /** The direction the ports and the socket face: the model's +x, which is the back. */
    public static Direction back(Direction facing) {
        return facing.getOpposite();
    }

    // Picking

    /** The target under a model-space point, px, given which slots are filled. */
    public static Target targetAt(Vec3 p, Set<PcSlot> filled) {
        Target best = Target.of(Kind.SHELL);
        double bestVolume = Double.MAX_VALUE;
        for (int port = 0; port < PORTS; port++) {
            double v = volumeIfInside(portBox(port), p);
            if (v < bestVolume) {
                bestVolume = v;
                best = new Target(Kind.PORT, port, null);
            }
        }
        double v = volumeIfInside(socketBox(), p);
        if (v < bestVolume) {
            bestVolume = v;
            best = Target.of(Kind.SOCKET);
        }
        v = volumeIfInside(POWER_BUTTON, p);
        if (v < bestVolume) {
            bestVolume = v;
            best = Target.of(Kind.POWER_BUTTON);
        }
        v = volumeIfInside(RESET_BUTTON, p);
        if (v < bestVolume) {
            bestVolume = v;
            best = Target.of(Kind.RESET_BUTTON);
        }
        // The slot is a target whether or not a disk is in, and so is the disk itself
        v = Math.min(volumeIfInside(FLOPPY_SLOT, p),
                filled.contains(PcSlot.FLOPPY) ? volumeIfInside(FLOPPY_DISK, p) : Double.MAX_VALUE);
        if (v < bestVolume) {
            bestVolume = v;
            best = Target.of(Kind.FLOPPY_SLOT);
        }
        if (filled.contains(PcSlot.FRONT_PANEL)) {
            v = volumeIfInside(FRONT_PANEL, p);
            if (v < bestVolume)
                best = new Target(Kind.PART, -1, PcSlot.FRONT_PANEL);
            return best;
        }
        for (PcSlot slot : PcSlot.values()) {
            if (!slot.internal() || !filled.contains(slot))
                continue;
            v = volumeIfInside(partBox(slot), p);
            if (v < bestVolume) {
                bestVolume = v;
                best = new Target(Kind.PART, -1, slot);
            }
        }
        v = volumeIfInside(INTERIOR, p);
        if (v < bestVolume)
            best = Target.of(Kind.INTERIOR);
        return best;
    }

    /** The box's volume if it holds the point (with a hair of slack for points on a face), else MAX_VALUE. */
    private static double volumeIfInside(double[] b, Vec3 p) {
        double e = 0.02;
        if (p.x < b[0] - e || p.x > b[3] + e || p.y < b[1] - e || p.y > b[4] + e || p.z < b[2] - e || p.z > b[5] + e)
            return Double.MAX_VALUE;
        return (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
    }

    // The device's shape

    /**
     * What a placed case is hit by and collides with: the walls, the buttons and
     * lights, the floppy slot, the panel when it is on and every installed part
     * solid; every port and the socket, grown as clicked, only hit. Cached per parts
     * mask.
     */
    public static DeviceShape deviceShape(int partsMask) {
        return SHAPES.computeIfAbsent(partsMask, PcCaseGeometry::build);
    }

    private static DeviceShape build(int partsMask) {
        List<double[]> solid = new ArrayList<>(List.of(SHELL));
        solid.addAll(List.of(POWER_BUTTON, RESET_BUTTON, POWER_LED, ERROR_LED, FLOPPY_SLOT));
        for (PcSlot slot : PcSlot.values())
            if ((partsMask & slot.bit()) != 0)
                solid.add(partBox(slot));
        List<double[]> pickOnly = new ArrayList<>();
        for (int port = 0; port < PORTS; port++)
            pickOnly.add(portBox(port));
        pickOnly.add(socketBox());
        return new DeviceShape(solid.toArray(double[][]::new), pickOnly.toArray(double[][]::new), -1);
    }
}
