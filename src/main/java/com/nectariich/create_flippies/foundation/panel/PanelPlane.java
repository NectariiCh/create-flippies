package com.nectariich.create_flippies.foundation.panel;

import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The flat surface a large panel lies on: one of eight horizontal facings or the
 * two vertical ones, at an exact offset, with its own in-plane coordinates.
 *
 * This is {@link PlacementGrid}'s {@code (face, plane)} pair widened to admit the
 * four diagonals, and it is deliberately the same idea — a plane owned by nobody,
 * named by integers, that many blocks may lie on and many grids may be ruled on.
 * A panel is not stored relative to any of its blocks for the reason a grid is
 * not: there would be no principled way to pick the owner, and the choice would
 * have to be revisited every time the rectangle grew.
 *
 * <h2>Why the offset is a lattice constant and not a distance</h2>
 *
 * The one thing a chamfer has to do is meet the straight panels either side of it
 * without a seam, and that is a question about where the plane is, to a precision
 * finer than a pixel. Storing the offset as a distance along the normal cannot
 * answer it: a straight panel's surface sits at {@code z = k/16}, a diagonal
 * through that corner lies an irrational distance from the origin, and the best a
 * rounded distance can do is land near the join.
 *
 * So the offset is the constant in the plane's <i>lattice</i> equation instead —
 * {@code x - z = offset/256} for one diagonal family, {@code x + z = offset/256}
 * for the other, {@code z = offset/256} and its like for the cardinals. The
 * diagonal through the end of a straight panel at {@code (x0, k/16)} then has
 * {@code offset = 256*x0 - 16*k}, an exact integer, and the join is exact rather
 * than merely close. Every plane a player can reach by snapping to the panel grid
 * is representable, which is the whole point of the unit.
 *
 * A consequence worth knowing: the lattice normal of a diagonal is
 * {@code (1, 0, +-1)} and so has length root two, which makes one unit of offset
 * {@code 1/(256*root2)} of a block of actual travel. Diagonal planes are snapped
 * about forty per cent finer than cardinal ones, for free.
 *
 * <h2>Exactness, and the one irrational number</h2>
 *
 * Everything stored here is an integer, and two planes are the same plane exactly
 * when their three fields agree — no tolerance, no comparison of doubles. Roots
 * enter only when a plane coordinate is converted to a world one, in {@link #at}
 * and {@link #u}, and they enter through {@link Math#sqrt}, which IEEE 754
 * requires to be correctly rounded and which therefore gives the same bits on
 * every machine. That is not true of {@link Math#sin}, which is allowed a ulp of
 * slack and may be swapped for a hardware intrinsic, so the diagonals are built
 * out of {@code sqrt(2)/2} rather than out of a rotation by forty-five degrees.
 * The client and the server derive terminal geometry independently and compare the
 * results at full precision; they have to agree bit for bit.
 *
 * <h2>Coordinates</h2>
 *
 * {@code face} is the outward normal, the way a control mounted here would point,
 * and {@code yaw} turns it another forty-five degrees in the order south, west,
 * north, east — so {@code NORTH} with a yaw of one faces north-east. The in-plane
 * axes u and v are derived from the plane and never from which side of it faces
 * out, so a panel and one facing the other way on the same plane name their
 * coordinates identically. For a cardinal plane they are exactly
 * {@link PlacementGrid#uAxis} and {@link PlacementGrid#vAxis}, taken from there so
 * that a yaw of zero behaves as the existing grid code already does, to the bit.
 *
 * u and v are true distances, in blocks, and not lattice units. That is the
 * opposite trade from the offset, and deliberately so: what u and v are compared
 * against is control footprints and grid cells, which have to come out their
 * authored size — a switch four pixels across must be four pixels across on a
 * chamfer too, not five and a bit. The cost is that a panel's <i>ends</i> land
 * within {@code 1/512} of a block of a lattice point rather than on one, which is
 * a fortieth of a pixel and answers to nothing anyone can see. The panel's
 * <i>surface</i>, which is the alignment that shows, stays exact.
 */
public record PanelPlane(Direction face, int yaw, int offset) {

    /**
     * Offset units in one block. The same lattice
     * {@link com.nectariich.create_flippies.foundation.control.ControlMount} anchors
     * on, so a panel's surface and a control's position are quantised alike.
     */
    public static final int UNITS_PER_BLOCK = 256;

    public static final int UNITS_PER_PIXEL = UNITS_PER_BLOCK / PlacementGrid.PIXELS_PER_BLOCK;

    /** A yaw is half a quarter turn, so there are only two before the face changes. */
    public static final int YAW_STEPS = 2;

    /**
     * Correctly rounded by IEEE 754 and therefore identical on every machine, which
     * {@code Math.sin(Math.PI / 4)} is not guaranteed to be. See the class comment.
     */
    private static final double ROOT_HALF = Math.sqrt(2.0) / 2.0;

    /** Why a plane was refused, or {@link #NONE} if it was not. */
    public enum Problem {
        NONE,
        /** Yaw outside the two steps a face admits. */
        BAD_YAW;

        public boolean ok() {
            return this == NONE;
        }
    }

    /** The cardinal plane carrying a given face of a given block, as the grid store means it. */
    public static PanelPlane ofFace(BlockPos pos, Direction face) {
        return new PanelPlane(face, 0, PlacementGrid.planeOf(pos, face) * UNITS_PER_PIXEL);
    }

    /** The plane of this facing through a world point, snapped to the offset lattice. */
    public static PanelPlane through(Direction face, int yaw, Vec3 point) {
        return new PanelPlane(face, yaw, new PanelPlane(face, yaw, 0).offsetOf(point));
    }

    public Problem check() {
        return yaw < 0 || yaw >= YAW_STEPS ? Problem.BAD_YAW : Problem.NONE;
    }

    /**
     * Whether a yaw has been applied at all — which is not the same question as
     * whether the plane is turned, and the difference is the whole reason both
     * predicates exist.
     *
     * A yaw is a turn about the vertical axis. Applied to a wall it swings the wall
     * round, so the plane itself ends up diagonal to the world. Applied to a floor it
     * cannot: turning a horizontal plane about the vertical axis leaves it exactly
     * where it was. What it turns instead is u and v <em>within</em> the plane, so a
     * flat panel comes out as a rectangle lying at forty-five degrees to the block
     * grid on a plane that is still a plain block boundary.
     *
     * Both are worth having and they behave differently almost everywhere below, so
     * they are named apart rather than told from each other by re-testing the face.
     */
    public boolean isTurned() {
        return yaw != 0;
    }

    /** Whether the plane itself lies diagonally: a turned wall, never a turned floor. */
    public boolean isDiagonal() {
        return yaw != 0 && face.getAxis().isHorizontal();
    }

    /** Whether u and v are turned inside a plane that is itself a block boundary. */
    public boolean isSpun() {
        return yaw != 0 && face.getAxis().isVertical();
    }

    /**
     * The eighth of a turn this facing occupies, counted the way
     * {@link Direction#get2DDataValue} counts: south, west, north, east. Meaningless
     * for a floor or a ceiling, which have no facing to count.
     */
    public int octant() {
        return face.get2DDataValue() * YAW_STEPS + yaw;
    }

    /**
     * The plane's normal as the shortest integer vector along it, made canonical by
     * requiring its first non-zero component to be positive.
     *
     * Canonical rather than outward so that the two facings of one physical surface
     * produce the same offset and the same in-plane axes — the property that lets a
     * panel and the controls reaching through it from behind agree about where the
     * surface is. Which side faces out is {@link #face}'s business, and
     * {@link #outwardSign} reads it back.
     */
    public int[] latticeNormal() {
        if (!isDiagonal()) {
            Axis axis = face.getAxis();
            return new int[] { axis == Axis.X ? 1 : 0, axis == Axis.Y ? 1 : 0, axis == Axis.Z ? 1 : 0 };
        }
        // Octants 1 and 5 are the north-east/south-west family, 3 and 7 the other
        return octant() % 4 == 1 ? new int[] { 1, 0, -1 } : new int[] { 1, 0, 1 };
    }

    /** Whether {@link #face} points along the canonical normal or against it. */
    public int outwardSign() {
        if (!isDiagonal())
            return face.getAxisDirection() == AxisDirection.POSITIVE ? 1 : -1;
        // Octants 5 and 7 — north-east and south-east — are the positive halves
        return octant() >= 4 ? 1 : -1;
    }

    /** The length of the lattice normal: one for a cardinal plane, root two for a diagonal. */
    public double latticeLength() {
        return isDiagonal() ? 1 / ROOT_HALF : 1;
    }

    /** The outward unit normal, which is the direction a control mounted here points. */
    public Vec3 outward() {
        int[] n = latticeNormal();
        double scale = outwardSign() / latticeLength();
        return new Vec3(n[0] * scale, n[1] * scale, n[2] * scale);
    }

    /** The unit vector u increases along. */
    public Vec3 tangentU() {
        if (isDiagonal())
            // Perpendicular to the lattice normal in the horizontal plane, first
            // non-zero component positive, which is the rule the cardinals follow too
            return octant() % 4 == 1 ? new Vec3(ROOT_HALF, 0, ROOT_HALF) : new Vec3(ROOT_HALF, 0, -ROOT_HALF);
        return spin(unit(PlacementGrid.uAxis(face.getAxis())));
    }

    /** The unit vector v increases along: up, except on a floor or a ceiling. */
    public Vec3 tangentV() {
        return isDiagonal() ? new Vec3(0, 1, 0) : spin(unit(PlacementGrid.vAxis(face.getAxis())));
    }

    /**
     * An in-plane axis of a flat panel, turned an eighth of a turn about the vertical
     * if this plane is spun, and returned untouched otherwise.
     *
     * Both axes are turned the same way and by the same amount, so the frame stays
     * right-handed and every consumer that reasoned about handedness — the winding of
     * the quads above all — carries over without knowing this happened.
     */
    private Vec3 spin(Vec3 axis) {
        if (!isSpun())
            return axis;
        return new Vec3((axis.x - axis.z) * ROOT_HALF, axis.y, (axis.x + axis.z) * ROOT_HALF);
    }

    /** The lattice normal dotted with a point, which is the quantity {@link #offset} scales. */
    public double latticeDot(double x, double y, double z) {
        int[] n = latticeNormal();
        return n[0] * x + n[1] * y + n[2] * z;
    }

    /** The offset of the plane of this facing through a point, snapped to the lattice. */
    public int offsetOf(Vec3 point) {
        return (int) Math.round(latticeDot(point.x, point.y, point.z) * UNITS_PER_BLOCK);
    }

    /** How far a point lies in front of the plane, in blocks; negative behind it. */
    public double depth(Vec3 point) {
        double fromOrigin = latticeDot(point.x, point.y, point.z) - offset / (double) UNITS_PER_BLOCK;
        return fromOrigin / latticeLength() * outwardSign();
    }

    /** A point's in-plane u, in blocks, measured from where the plane passes the world origin. */
    public double u(Vec3 point) {
        Vec3 t = tangentU();
        return t.x * point.x + t.y * point.y + t.z * point.z;
    }

    /** A point's in-plane v, in blocks. */
    public double v(Vec3 point) {
        Vec3 t = tangentV();
        return t.x * point.x + t.y * point.y + t.z * point.z;
    }

    /**
     * The world point at in-plane {@code (u, v)}, lifted {@code depth} blocks out
     * along the outward normal: the inverse of {@link #u}, {@link #v} and
     * {@link #depth} together.
     *
     * The origin of u and v is the foot of the perpendicular from the world origin,
     * which is why nothing is subtracted here — both tangents are perpendicular to
     * the normal, so that point has u and v of zero by construction.
     */
    public Vec3 at(double u, double v, double depth) {
        int[] n = latticeNormal();
        double length = latticeLength();
        double along = offset / (double) UNITS_PER_BLOCK / (length * length);
        Vec3 foot = new Vec3(n[0] * along, n[1] * along, n[2] * along);
        return foot.add(tangentU().scale(u)).add(tangentV().scale(v)).add(outward().scale(depth));
    }

    /**
     * The interval a box occupies along u, as {@code min, max} in blocks.
     *
     * Wanted by everything that has to ask whether a block and a rectangle on a
     * plane overlap. On a cardinal plane the interval is the block's own extent
     * along a world axis and the question is exact; on a turned one it is the
     * shadow the block casts on the axis, which is wider than the block's slice of
     * the plane really is, so an overlap test built on it says yes a little too
     * often and never no too often. Erring that way is what it has to do: the
     * callers are search filters, and a filter that misses is a grid that has
     * quietly stopped constraining anything.
     */
    public double[] uRange(AABB box) {
        return new double[] { extreme(box, tangentU(), false), extreme(box, tangentU(), true) };
    }

    /** The interval a box occupies along v, as {@code min, max} in blocks. */
    public double[] vRange(AABB box) {
        return new double[] { extreme(box, tangentV(), false), extreme(box, tangentV(), true) };
    }

    /**
     * How far in front of the plane a box reaches, as {@code min, max} in blocks —
     * negative behind it, so a box the plane passes through straddles zero.
     */
    public double[] depthRange(AABB box) {
        // depth is n . p plus a constant, and the constant is the depth of the origin
        double atOrigin = depth(Vec3.ZERO);
        Vec3 outward = outward();
        return new double[] { extreme(box, outward, false) + atOrigin, extreme(box, outward, true) + atOrigin };
    }

    /**
     * The furthest a box reaches along a direction, which is the corner formed by
     * taking each coordinate from whichever end of the box agrees with the sign of
     * that component.
     *
     * A coordinate the direction does not use contributes an exact zero either way,
     * so a cardinal axis reads one face of the box and nothing else — the answer a
     * world-axis test would have given, to the bit.
     */
    private static double extreme(AABB box, Vec3 along, boolean high) {
        return end(along.x, box.minX, box.maxX, high) * along.x + end(along.y, box.minY, box.maxY, high) * along.y
                + end(along.z, box.minZ, box.maxZ, high) * along.z;
    }

    private static double end(double component, double low, double high, boolean wantHigh) {
        return component >= 0 == wantHigh ? high : low;
    }

    /**
     * This plane as {@link PlacementGrid} states one, in pixels — meaningful only
     * for a cardinal plane sitting on a pixel line, which is every plane the grid
     * store can already hold. A diagonal or a sub-pixel offset has no such number,
     * and asking for one is a bug rather than a rounding, so check
     * {@link #isPixelAligned} first.
     */
    public int pixelPlane() {
        return Math.floorDiv(offset, UNITS_PER_PIXEL);
    }

    /**
     * Whether {@link #pixelPlane} says everything about this plane.
     *
     * A spun plane is on a pixel line like any other flat one, and is still excluded:
     * what the grid store means by a plane is a face with u and v on the world axes,
     * and a spun one has the plane but not the axes.
     */
    public boolean isPixelAligned() {
        return !isTurned() && Math.floorMod(offset, UNITS_PER_PIXEL) == 0;
    }

    public PanelPlane withOffset(int offset) {
        return new PanelPlane(face, yaw, offset);
    }

    /**
     * Whether two planes are the same surface held the same way round, so that a plate
     * on one occupies the very same space as a plate on the other.
     *
     * Not record equality, and the difference is the point: a flat panel and a flat
     * panel spun forty-five degrees have different yaws and the same surface, which is
     * exactly the pair a loft makes where it reaches into the strip it came from. What
     * decides it is the plane itself — the lattice normal and the offset — plus which
     * side {@link #face} hangs the plate on, because the two facings of one surface
     * carry their plates on opposite sides of it and so never share any space at all.
     */
    public boolean sameSurfaceAs(PanelPlane other) {
        if (other == null || offset != other.offset() || outwardSign() != other.outwardSign())
            return false;
        int[] mine = latticeNormal();
        int[] theirs = other.latticeNormal();
        return mine[0] == theirs[0] && mine[1] == theirs[1] && mine[2] == theirs[2];
    }

    /** The same physical surface seen from the other side. */
    public PanelPlane flipped() {
        return new PanelPlane(face.getOpposite(), yaw, offset);
    }

    public void save(CompoundTag tag) {
        tag.putByte("Face", (byte) face.get3DDataValue());
        tag.putByte("Yaw", (byte) yaw);
        tag.putInt("Offset", offset);
    }

    /** Null when the tag holds no plane, which is how a truncated save is survived. */
    public static PanelPlane load(CompoundTag tag) {
        if (!tag.contains("Offset"))
            return null;
        return new PanelPlane(Direction.from3DDataValue(tag.getByte("Face")), tag.getByte("Yaw"), tag.getInt("Offset"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(face);
        buffer.writeByte(yaw);
        buffer.writeVarInt(offset);
    }

    public static PanelPlane read(FriendlyByteBuf buffer) {
        return new PanelPlane(buffer.readEnum(Direction.class), buffer.readByte(), buffer.readVarInt());
    }

    private static Vec3 unit(Axis axis) {
        return new Vec3(axis == Axis.X ? 1 : 0, axis == Axis.Y ? 1 : 0, axis == Axis.Z ? 1 : 0);
    }
}
