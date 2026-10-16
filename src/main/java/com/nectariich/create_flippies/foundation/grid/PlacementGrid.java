package com.nectariich.create_flippies.foundation.grid;

import java.util.UUID;
import java.util.function.LongConsumer;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A rectangle ruled into cells, lying on one flat surface, held in absolute world
 * coordinates.
 *
 * A grid is neither a block nor an entity: it constrains where controls may be
 * placed, and what it constrains is a geometrical surface, not a block. So one
 * grid may run across many blocks and one block may carry several, which rules out
 * storing it relative to any particular block — there would be no principled way
 * to pick the owner, and the choice would have to be revisited every time the
 * rectangle grew.
 *
 * The surface is a {@link PanelPlane}, which is to say it may be a block face or
 * it may be one of the four diagonals a chamfer stands on. Everything below is
 * written in that plane's own u and v rather than in world axes, which is what
 * lets one implementation serve both: on a cardinal plane the plane's axes
 * <em>are</em> world axes and every expression here reduces to the one it replaced,
 * to the bit.
 *
 * The unit for u and v is the pixel, a sixteenth of a block, and every stored
 * coordinate is an {@code int} in that unit. That settles precision outright. A
 * pixel coordinate at the world border is about 4.8e8, well inside an int,
 * whereas a float there has a spacing of tens of blocks and a double, though
 * adequate, only ever gets two grids that share an edge to agree about it very
 * nearly. Exact integers make them agree exactly, which is what a placement aid
 * needs: a control snapped to the right-hand edge of one grid and one snapped to
 * the left-hand edge of its neighbour must land on the same line.
 *
 * On a diagonal plane u and v are still true distances and still exact integers,
 * but they are measured along the surface rather than along the world, so a grid
 * edge does not fall on a block boundary. That is the same trade
 * {@link com.nectariich.create_flippies.foundation.panel.PanelDefinition} makes and
 * for the same reason: what these coordinates are compared against is control
 * footprints and cell edges, which have to come out their authored size.
 *
 * Cells are not required to be a whole number of pixels — a rectangle sixteen
 * pixels wide ruled into three columns has edges at thirds of a pixel — so cell
 * edges are the one unavoidable fraction. They are formed by a single division of
 * an exact long numerator, never by accumulating a step, so a cell and its
 * neighbour always report the same edge between them, and containment tests
 * compare the numerators and never divide at all.
 *
 * Grids nest by naming a parent and one of its cells, and a child must fit inside
 * that cell. Identity is a UUID rather than an index because grids are created
 * and destroyed freely, and an index would either be reused — silently
 * reparenting orphaned children — or need a counter kept in step with the store
 * across every save.
 */
public record PlacementGrid(
        UUID id,
        UUID parent,
        int parentCell,
        PanelPlane plane,
        int minU,
        int minV,
        int maxU,
        int maxV,
        int columns,
        int rows) {

    public static final int PIXELS_PER_BLOCK = 16;

    /** Returned by every cell lookup for a point that misses the grid. */
    public static final int OUTSIDE = -1;

    /** {@link #parentCell} of a grid that has no parent. */
    public static final int NO_CELL = -1;

    /**
     * A grid may be ruled no finer than this many tracks per axis. The cap bounds
     * the size of one grid on the wire and in a save, and it is already finer than
     * anyone can aim at: sixty-four tracks across a block is a quarter of a pixel
     * a cell.
     */
    public static final int MAX_TRACKS = 64;

    /** Sixteen blocks per axis, which is also what bounds the chunk enumeration. */
    public static final int MAX_SPAN = 16 * PIXELS_PER_BLOCK;

    /**
     * How far off the plane a hit point may be and still count as on it. A ray
     * cast against a block face returns a point on that face, so the slack only
     * has to absorb the arithmetic, not any real thickness.
     */
    private static final double PLANE_TOLERANCE = 1.0e-4;

    /**
     * How far behind the surface a point is taken to decide which block owns it, in
     * blocks. A grid belongs to the block behind it, and a point exactly on a
     * cardinal plane belongs to neither of the two it separates until it is pushed
     * off; a millionth of a block settles that and crosses nothing else.
     */
    private static final double BEHIND = 1.0e-6;

    /**
     * How far inside its far edge a rectangle is measured to, in blocks. An edge
     * lying exactly on a block boundary belongs to the block before it, not the one
     * after, and this is what says so.
     */
    private static final double BOUNDARY = 1.0e-9;

    /** Why a grid was refused, or {@link #NONE} if it was not. */
    public enum Problem {
        NONE,
        /** The plane is not one a grid can be ruled on. */
        OFF_FACE,
        /** Zero or negative extent, which leaves no cell to place anything in. */
        DEGENERATE,
        TOO_LARGE,
        BAD_TRACK_COUNT,
        /** A parent was named but no such grid is in the store. */
        PARENT_MISSING,
        /** The named parent is on a different surface. */
        PARENT_ELSEWHERE,
        /** The named parent cell does not exist. */
        NO_SUCH_CELL,
        /** The rectangle is not contained by the parent cell it claims. */
        OUTSIDE_PARENT_CELL,
        /** Nested deeper than the store allows. */
        TOO_DEEP,
        /** Another grid already has this id. */
        DUPLICATE;

        public boolean ok() {
            return this == NONE;
        }
    }

    public static PlacementGrid top(UUID id, PanelPlane plane, int minU, int minV, int maxU, int maxV, int columns,
            int rows) {
        return new PlacementGrid(id, null, NO_CELL, plane, minU, minV, maxU, maxV, columns, rows);
    }

    /** A child of {@code parent}, inheriting its surface by construction. */
    public static PlacementGrid nested(UUID id, PlacementGrid parent, int parentCell, int minU, int minV, int maxU,
            int maxV, int columns, int rows) {
        return new PlacementGrid(id, parent.id(), parentCell, parent.plane(), minU, minV, maxU, maxV, columns, rows);
    }

    /** The pixel coordinate of the plane carrying a given face of a given block. */
    public static int planeOf(BlockPos pos, Direction face) {
        int layer = face.getAxis().choose(pos.getX(), pos.getY(), pos.getZ());
        if (face.getAxisDirection() == AxisDirection.POSITIVE)
            layer++;
        return layer * PIXELS_PER_BLOCK;
    }

    /**
     * The in-plane axis called u: whichever of x, y, z comes first once the face
     * axis is struck out. Derived from the axis and not the direction, so both
     * faces of a plane agree about it.
     */
    public static Axis uAxis(Axis faceAxis) {
        return faceAxis == Axis.X ? Axis.Z : Axis.X;
    }

    /** The in-plane axis called v, the one left over after u. */
    public static Axis vAxis(Axis faceAxis) {
        return faceAxis == Axis.Y ? Axis.Z : Axis.Y;
    }

    /** The way a control mounted on this grid would point. */
    public Direction face() {
        return plane.face();
    }

    public int widthU() {
        return maxU - minU;
    }

    public int heightV() {
        return maxV - minV;
    }

    public int cellCount() {
        return columns * rows;
    }

    public boolean isNested() {
        return parent != null;
    }

    /**
     * Whether this grid is ruled on that surface and covers part of that block.
     *
     * Two conditions, and the second is the one that is easy to leave out. The
     * block's shadow on u and v has to meet the rectangle, and the block has to be
     * the one <em>behind</em> the surface — otherwise every block in the column
     * above a floor grid would answer yes, since they all cast the same shadow. A
     * block is behind the surface when it reaches to the plane and some of it lies
     * further back, which on a cardinal plane picks out exactly the one block the
     * grid is a face of, and on a diagonal picks out the blocks the plane cuts
     * through.
     */
    public boolean touches(BlockPos pos, PanelPlane surface) {
        return plane.equals(surface) && covers(pos);
    }

    /** Whether the block behind this grid's surface at that position is this one. */
    public boolean covers(BlockPos pos) {
        AABB block = new AABB(pos);
        double[] depth = plane.depthRange(block);
        if (depth[0] >= 0 || depth[1] < 0)
            return false;
        double[] u = plane.uRange(block);
        double[] v = plane.vRange(block);
        return overlaps(u[0] * PIXELS_PER_BLOCK, u[1] * PIXELS_PER_BLOCK, minU, maxU)
                && overlaps(v[0] * PIXELS_PER_BLOCK, v[1] * PIXELS_PER_BLOCK, minV, maxV);
    }

    /** Every chunk this grid overlaps, as {@link ChunkPos#toLong()} keys, each once. */
    public void forEachChunk(LongConsumer keys) {
        AABB bounds = worldBounds();
        int minChunkX = Mth.floor(bounds.minX) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ) >> 4;
        // Never fewer than the chunk the low corner is in. A plane is flat, so along
        // its own normal the box has no thickness at all, and trimming the far edge
        // of an interval that has no width would run the loop backwards and file the
        // grid under nothing — which is not a grid that misbehaves but a grid that
        // is never found again
        int maxChunkX = Math.max(minChunkX, Mth.floor(bounds.maxX - BOUNDARY) >> 4);
        int maxChunkZ = Math.max(minChunkZ, Mth.floor(bounds.maxZ - BOUNDARY) >> 4);
        for (int x = minChunkX; x <= maxChunkX; x++)
            for (int z = minChunkZ; z <= maxChunkZ; z++)
                keys.accept(ChunkPos.asLong(x, z));
    }

    /**
     * The smallest world box holding the rectangle, which on a diagonal is
     * noticeably larger than the rectangle — so a grid is filed under a few chunks
     * it only reaches into at a corner. That costs a handful of extra candidates in
     * a search that already filters by {@link #touches}, which is the right way for
     * this to be wrong.
     */
    private AABB worldBounds() {
        double scale = PIXELS_PER_BLOCK;
        AABB bounds = null;
        for (int corner = 0; corner < 4; corner++) {
            // Taken from behind the surface, because that is the side the grid's
            // blocks are on and a corner exactly on a block boundary would otherwise
            // name the block in front
            Vec3 point = plane.at((corner & 1) == 0 ? minU / scale : maxU / scale,
                    (corner & 2) == 0 ? minV / scale : maxV / scale, -BEHIND);
            AABB at = new AABB(point, point);
            bounds = bounds == null ? at : bounds.minmax(at);
        }
        return bounds;
    }

    /**
     * The block behind the surface at a point on it, that point given in pixels.
     *
     * Read by stepping a hair back from the surface rather than by inverting the
     * plane's arithmetic, because on a diagonal there is nothing to invert: the
     * block a point belongs to is simply the block the point is in, and a point
     * exactly on a cardinal plane is on the boundary between two of them until it
     * is pushed off it.
     */
    public BlockPos hostBlock(double uPixel, double vPixel) {
        return hostBlock(plane, uPixel, vPixel);
    }

    /** The same, for a surface no grid has been ruled on. */
    public static BlockPos hostBlock(PanelPlane plane, double uPixel, double vPixel) {
        double scale = PIXELS_PER_BLOCK;
        return BlockPos.containing(plane.at(uPixel / scale, vPixel / scale, -BEHIND));
    }

    /**
     * The cell a point falls in, or {@link #OUTSIDE}. The point is in world block
     * units, as a ray cast gives it. Its distance off the plane is checked first,
     * because a hit on the far side of a one-pixel panel would otherwise be
     * attributed to a cell of the grid on this side.
     */
    public int cellAt(Vec3 point) {
        if (Math.abs(plane.depth(point)) > PLANE_TOLERANCE)
            return OUTSIDE;
        return cellAt(plane.u(point), plane.v(point));
    }

    /** The cell containing an in-plane point given in block units, or {@link #OUTSIDE}. */
    public int cellAt(double u, double v) {
        int column = trackAt(u, minU, widthU(), columns);
        int row = trackAt(v, minV, heightV(), rows);
        if (column == OUTSIDE || row == OUTSIDE)
            return OUTSIDE;
        return row * columns + column;
    }

    public int columnOf(int cell) {
        return cell % columns;
    }

    public int rowOf(int cell) {
        return cell / columns;
    }

    /**
     * A cell's rectangle in block units, as {@code minU, minV, maxU, maxV}. These
     * are the only fractions in the model, and each comes out of a single division
     * so that a cell and its neighbour report the shared edge identically.
     */
    public double[] cellRect(int cell) {
        int column = columnOf(cell);
        int row = rowOf(cell);
        return new double[] {
                trackEdge(minU, widthU(), columns, column),
                trackEdge(minV, heightV(), rows, row),
                trackEdge(minU, widthU(), columns, column + 1),
                trackEdge(minV, heightV(), rows, row + 1) };
    }

    /** The whole grid's rectangle in block units, as {@code minU, minV, maxU, maxV}. */
    public double[] rect() {
        double scale = PIXELS_PER_BLOCK;
        return new double[] { minU / scale, minV / scale, maxU / scale, maxV / scale };
    }

    /**
     * Whether the geometry is self-consistent and, when nested, fits the parent
     * cell it claims. A grid describes exactly one surface by construction, so the
     * requirement that it lie on one reduces to two checks: that the surface is one
     * a grid may be ruled on, and that a child shares its parent's.
     *
     * A cardinal surface must sit on a pixel line, because a control snapped into
     * it is anchored in whole sixteenths of a pixel measured from a block face and
     * a plane between two pixels would put every one of them off the lattice. A
     * turned surface is under no such rule: it is a chamfer's own plane, it lands
     * where the chamfer landed, and its offset is exact by construction.
     *
     * {@code actualParent} is the grid the store holds under {@link #parent}, or
     * null if it holds none; passing it in rather than looking it up keeps this
     * method usable before the grid is anywhere near a store.
     */
    public Problem check(PlacementGrid actualParent) {
        if (!plane.check().ok() || (!plane.isTurned() && !plane.isPixelAligned()))
            return Problem.OFF_FACE;
        if (widthU() <= 0 || heightV() <= 0)
            return Problem.DEGENERATE;
        if (widthU() > MAX_SPAN || heightV() > MAX_SPAN)
            return Problem.TOO_LARGE;
        if (columns < 1 || rows < 1 || columns > MAX_TRACKS || rows > MAX_TRACKS)
            return Problem.BAD_TRACK_COUNT;

        if (parent == null)
            return parentCell == NO_CELL ? Problem.NONE : Problem.NO_SUCH_CELL;
        if (actualParent == null || !actualParent.id().equals(parent))
            return Problem.PARENT_MISSING;
        if (!actualParent.plane().equals(plane))
            return Problem.PARENT_ELSEWHERE;
        if (parentCell < 0 || parentCell >= actualParent.cellCount())
            return Problem.NO_SUCH_CELL;
        return actualParent.containsInCell(parentCell, this) ? Problem.NONE : Problem.OUTSIDE_PARENT_CELL;
    }

    /**
     * Whether a child's rectangle fits inside one of this grid's cells. A cell
     * edge can be a fraction of a pixel, so the comparison runs on the exact
     * numerators rather than on divided values: a child laid flush against a cell
     * edge has to be accepted, and a rounded edge would refuse it about half the
     * time.
     */
    private boolean containsInCell(int cell, PlacementGrid child) {
        return withinTrack(child.minU, child.maxU, minU, widthU(), columns, columnOf(cell))
                && withinTrack(child.minV, child.maxV, minV, heightV(), rows, rowOf(cell));
    }

    private static boolean withinTrack(int childMin, int childMax, int min, int extent, int count, int index) {
        return (long) childMin * count >= scaledEdge(min, extent, count, index)
                && (long) childMax * count <= scaledEdge(min, extent, count, index + 1);
    }

    /**
     * A track boundary in pixels multiplied by the track count, which makes it an
     * exact integer. Both the fractional edge and every containment test derive
     * from this, so the two cannot disagree.
     */
    private static long scaledEdge(int min, int extent, int count, int index) {
        return min * (long) count + (long) index * extent;
    }

    private static double trackEdge(int min, int extent, int count, int index) {
        return scaledEdge(min, extent, count, index) / (double) (PIXELS_PER_BLOCK * (long) count);
    }

    /**
     * Which track along one axis an in-plane block coordinate falls in. The
     * absolute edge is subtracted before anything is scaled, so by the time the
     * coordinate is multiplied by the track count it is a small local value and
     * the slack of a double taken from a ray cast near the world border — a
     * fraction of a millionth of a pixel — is never amplified.
     */
    private static int trackAt(double coordinate, int min, int extent, int count) {
        double local = coordinate * PIXELS_PER_BLOCK - min;
        if (local < 0)
            return OUTSIDE;
        int track = (int) Math.floor(local * count / extent);
        return track < count ? track : OUTSIDE;
    }

    private static boolean overlaps(double minA, double maxA, int minB, int maxB) {
        return minA < maxB && minB < maxA;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (parent != null) {
            tag.putUUID("Parent", parent);
            tag.putInt("ParentCell", parentCell);
        }
        plane.save(tag);
        tag.putIntArray("Rect", new int[] { minU, minV, maxU, maxV });
        tag.putInt("Columns", columns);
        tag.putInt("Rows", rows);
        return tag;
    }

    /** Null when the tag is not a grid, which is how a truncated save is survived. */
    public static PlacementGrid load(CompoundTag tag) {
        int[] rect = tag.getIntArray("Rect");
        PanelPlane plane = PanelPlane.load(tag);
        if (!tag.hasUUID("Id") || plane == null || rect.length != 4)
            return null;
        UUID parent = tag.hasUUID("Parent") ? tag.getUUID("Parent") : null;
        return new PlacementGrid(tag.getUUID("Id"), parent, parent == null ? NO_CELL : tag.getInt("ParentCell"), plane,
                rect[0], rect[1], rect[2], rect[3], tag.getInt("Columns"), tag.getInt("Rows"));
    }

    /**
     * Coordinates go out as plain ints rather than varints: they are absolute and
     * routinely negative, which is the one case a varint makes longer.
     */
    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
        buffer.writeNullable(parent, FriendlyByteBuf::writeUUID);
        if (parent != null)
            buffer.writeVarInt(parentCell);
        plane.write(buffer);
        buffer.writeInt(minU);
        buffer.writeInt(minV);
        buffer.writeInt(maxU);
        buffer.writeInt(maxV);
        buffer.writeVarInt(columns);
        buffer.writeVarInt(rows);
    }

    public static PlacementGrid read(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        UUID parent = buffer.readNullable(FriendlyByteBuf::readUUID);
        int parentCell = parent == null ? NO_CELL : buffer.readVarInt();
        return new PlacementGrid(id, parent, parentCell, PanelPlane.read(buffer), buffer.readInt(), buffer.readInt(),
                buffer.readInt(), buffer.readInt(), buffer.readVarInt(), buffer.readVarInt());
    }
}
