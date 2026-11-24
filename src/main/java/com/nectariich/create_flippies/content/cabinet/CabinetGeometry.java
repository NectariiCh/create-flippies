package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.world.phys.Vec3;

/**
 * Where everything on an electrical cabinet is, worked out from its {@link CabinetSpec},
 * in model pixels as {@link DeviceShape} takes them.
 *
 * The front is +x. The back plate's outer face is the plane x = 8, the model's
 * centre, so the entity's position is on the wall the cabinet hangs on and the box
 * reaches {@code depth} px out of it. Up and down it runs from {@link #bottom} to
 * {@link #top}, and across from {@link #z0} to {@link #z1}: about the position, which
 * keeps it as near every part as it can be (the game finds entities by the sections
 * near a box, widened for large devices by {@code DeviceReach}). Both are whole pixels from the position, half the size rounded
 * down below it, so an odd size still has its edges on whole pixels; and since the
 * position is on the pixel grid and the cabinet only ever stands at a quarter turn,
 * so is every edge in the world.
 *
 * Every wall is {@link #WALL} thick. The back covers the whole back; the sides run
 * the full height from the back plate to the front; the top and bottom sit between
 * the sides; and the door fills the frame those four leave in the front, so a closed
 * cabinet shows a 1 px rim round its door. Open, the door stands straight out from the
 * hinge edge, in line with that side wall, as a door turned 90 degrees about its
 * hinge stands. Everything inside is empty: big ones can be stood in.
 *
 * Every cabinet the spec allows has all of this, however small. At the least, 3 px
 * by 3 by 2 deep, the room inside is no depth at all: the door's inner face lies on
 * the back plate's, with a 1 px door between 1 px sides.
 *
 * Gear clipped inside may stand deeper than the room ({@link #backPlane} to the
 * door's inner face) and poke out through the door or the walls. That is allowed;
 * nothing refuses a cabinet, or gear in it, for being too shallow.
 *
 * Seen from in front (looking along -x) the viewer's left is +z, so a door hung on
 * the left turns about the {@link #z1} edge.
 */
public final class CabinetGeometry {

    /** Back, sides, top, bottom and door are all this thick, px. */
    public static final int WALL = 1;
    /** The plane of the back plate's outer face, on the wall. */
    public static final double BACK = 8;

    /** What a point on the cabinet is part of. */
    public enum Part {
        /** The door, either side of it, open or closed. */
        DOOR,
        /** The inside face of the back plate, where rails will go. */
        BACK_INNER,
        /** Anything else: the outside, the walls' edges, the inside of the sides. */
        WALL
    }

    /** The inside face of the back plate, model px: the plane {@code x} and the rectangle on it. */
    public record BackPlane(double x, double y0, double y1, double z0, double z1) {

        public double width() {
            return z1 - z0;
        }

        public double height() {
            return y1 - y0;
        }
    }

    private record Key(int width, int height, int depth, CabinetSpec.Hinge hinge, boolean open) {}

    private static final Map<Key, DeviceShape> SHAPES = new ConcurrentHashMap<>();

    private CabinetGeometry() {}

    /** The underside, px: half the height below the position, rounded to a whole pixel. */
    public static double bottom(CabinetSpec spec) {
        return -(spec.heightPx() / 2);
    }

    /** The top, px. */
    public static double top(CabinetSpec spec) {
        return bottom(spec) + spec.heightPx();
    }

    /** The right edge seen from in front (low z). */
    public static double z0(CabinetSpec spec) {
        return 8 - spec.widthPx() / 2;
    }

    /** The left edge seen from in front (high z). */
    public static double z1(CabinetSpec spec) {
        return z0(spec) + spec.widthPx();
    }

    /** The plane of the front. */
    public static double front(CabinetSpec spec) {
        return BACK + spec.depthPx();
    }

    /** The box the closed cabinet fills, inside and all. */
    public static double[] outline(CabinetSpec spec) {
        return new double[] {BACK, bottom(spec), z0(spec), front(spec), top(spec), z1(spec)};
    }

    /** Everything solid: the walls and the door, open or closed. */
    public static List<double[]> boxes(CabinetSpec spec, boolean open) {
        List<double[]> boxes = new ArrayList<>();
        double x0 = BACK, x1 = front(spec), inner = BACK + WALL;
        double y0 = bottom(spec), y1 = top(spec), z0 = z0(spec), z1 = z1(spec);
        boxes.add(new double[] {x0, y0, z0, inner, y1, z1}); // back
        boxes.add(new double[] {inner, y0, z0, x1, y1, z0 + WALL}); // right side
        boxes.add(new double[] {inner, y0, z1 - WALL, x1, y1, z1}); // left side
        boxes.add(new double[] {inner, y0, z0 + WALL, x1, y0 + WALL, z1 - WALL}); // bottom
        boxes.add(new double[] {inner, y1 - WALL, z0 + WALL, x1, y1, z1 - WALL}); // top
        boxes.add(door(spec, open));
        return boxes;
    }

    /** The door. */
    public static double[] door(CabinetSpec spec, boolean open) {
        return onDoor(spec, open, 0, doorWidth(spec), 0, WALL, bottom(spec) + WALL, top(spec) - WALL);
    }

    /**
     * The handle, a 1 px bar standing proud of the door's outer face a pixel in from
     * its free edge, or null for a door too narrow or short for one. Drawn only: it
     * is neither hit nor collided with.
     */
    public static double[] handle(CabinetSpec spec, boolean open) {
        if (doorWidth(spec) < 3)
            return null;
        int doorHeight = spec.heightPx() - 2 * WALL;
        int length = Math.min(6, Math.max(1, doorHeight - 4));
        double y0 = bottom(spec) + WALL + (doorHeight - length) / 2;
        double s = doorWidth(spec) - 2;
        return onDoor(spec, open, s, s + 1, -1, 0, y0, y0 + length);
    }

    /** The door's width, between the sides. */
    public static int doorWidth(CabinetSpec spec) {
        return spec.widthPx() - 2 * WALL;
    }

    /**
     * A box given in the door's own terms as the door is: {@code s} across it from the
     * hinge edge, {@code t} into it from its outer face (negative stands proud of it),
     * {@code y} up. Closed, the outer face is the front plane; open, the door has turned
     * 90 degrees outwards about the hinge, so across it is straight out from the
     * front and its outer face looks away from the opening.
     */
    private static double[] onDoor(CabinetSpec spec, boolean open, double s0, double s1, double t0, double t1,
            double y0, double y1) {
        double front = front(spec);
        boolean left = spec.hinge() == CabinetSpec.Hinge.LEFT;
        if (!open) {
            double x0 = front - t1, x1 = front - t0;
            // Across runs from the hinge edge: the left side's inner face, or the right's
            double hinge = left ? z1(spec) - WALL : z0(spec) + WALL;
            double za = left ? hinge - s1 : hinge + s0, zb = left ? hinge - s0 : hinge + s1;
            return new double[] {x0, y0, za, x1, y1, zb};
        }
        double x0 = front + s0, x1 = front + s1;
        // In line with the hinge side's wall, its outer face that wall's outer face
        double outer = left ? z1(spec) : z0(spec);
        double za = left ? outer - t1 : outer + t0, zb = left ? outer - t0 : outer + t1;
        return new double[] {x0, y0, za, x1, y1, zb};
    }

    /**
     * The inside face of the back plate. The room in front of it is {@link #roomDepth}
     * deep, which may be none.
     */
    public static BackPlane backPlane(CabinetSpec spec) {
        return new BackPlane(BACK + WALL, bottom(spec) + WALL, top(spec) - WALL, z0(spec) + WALL, z1(spec) - WALL);
    }

    /** From the back plate's inside face to the closed door's, px: none for a cabinet 2 px deep. */
    public static double roomDepth(CabinetSpec spec) {
        return spec.depthPx() - 2 * WALL;
    }

    /** The middle of the room inside (on the back plate when it has no depth). */
    public static Vec3 interiorCentre(CabinetSpec spec) {
        double[] b = outline(spec);
        return new Vec3((BACK + WALL + front(spec) - WALL) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    /** What the point {@code p}, model px, on the cabinet's surface is part of. */
    public static Part partAt(CabinetSpec spec, boolean open, Vec3 p) {
        double e = 1e-3;
        if (inside(door(spec, open), p, e))
            return Part.DOOR;
        BackPlane back = backPlane(spec);
        if (Math.abs(p.x - back.x()) < e && p.y > back.y0() - e && p.y < back.y1() + e && p.z > back.z0() - e
                && p.z < back.z1() + e)
            return Part.BACK_INNER;
        return Part.WALL;
    }

    private static boolean inside(double[] b, Vec3 p, double e) {
        return p.x >= b[0] - e && p.x <= b[3] + e && p.y >= b[1] - e && p.y <= b[4] + e && p.z >= b[2] - e
                && p.z <= b[5] + e;
    }

    /** The shape of a cabinet of {@code spec}, door as given; one per size, hinge and door, shared. */
    public static DeviceShape shape(CabinetSpec spec, boolean open) {
        Key key = new Key(spec.widthPx(), spec.heightPx(), spec.depthPx(), spec.hinge(), open);
        return SHAPES.computeIfAbsent(key,
                k -> new DeviceShape(boxes(spec, open).toArray(double[][]::new), new double[][] {}, 1));
    }
}
