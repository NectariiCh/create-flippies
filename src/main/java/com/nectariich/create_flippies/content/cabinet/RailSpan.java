package com.nectariich.create_flippies.content.cabinet;

import java.util.UUID;

import com.nectariich.create_flippies.content.din_rail.DinRailBlock;
import com.nectariich.create_flippies.content.din_rail.DinRailSeat;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * The part of one cabinet rail that runs through one cell, as the cell's host block
 * keeps it.
 *
 * Everything is in the frame a rail is authored in ({@link SurfaceMount}, as for a
 * rail block): {@code facing} points into the wall the cabinet hangs on and
 * {@code rotation} turns the frame so that its {@code x} runs along the rail. In
 * that frame, relative to this cell and in sixteenths of a pixel (a mount's lattice,
 * {@link ControlMount#UNITS_PER_PIXEL}):
 *
 * <ul>
 * <li>{@code from} to {@code to} is the piece in this cell, within 0 to 16 px;</li>
 * <li>{@code railFrom} to {@code railTo} is the whole rail, which runs on into the
 * cells either side, so gear seated from here snaps along all of it;</li>
 * <li>{@code across} is the rail's centre line, within this cell;</li>
 * <li>{@code plate} is how far the back plate's inside face stands off the cell's
 * face against the wall, which is where the rail and the gear on it lie.</li>
 * </ul>
 *
 * {@code cabinet} and {@code railId} say whose rail it is, which is what lets the
 * cabinet take its rails down again and a cell bond the ground terminals on one rail
 * and not on its neighbour.
 */
public record RailSpan(UUID cabinet, int railId, Direction facing, int rotation, int from, int to, int railFrom,
        int railTo, int across, int plate) {

    private static final double UNIT = ControlMount.UNITS_PER_PIXEL;

    public double fromPx() {
        return from / UNIT;
    }

    public double toPx() {
        return to / UNIT;
    }

    public double railFromPx() {
        return railFrom / UNIT;
    }

    public double railToPx() {
        return railTo / UNIT;
    }

    public double acrossPx() {
        return across / UNIT;
    }

    public double platePx() {
        return plate / UNIT;
    }

    /** Whether it is a piece of that cabinet's rail {@code railId}. */
    public boolean isOf(UUID cabinet, int railId) {
        return this.railId == railId && this.cabinet.equals(cabinet);
    }

    /** A point in the cell's pixels, in this span's authored frame. */
    public double[] authored(double x, double y, double z) {
        return SurfaceMount.localPoint(x, y, z, facing, rotation);
    }

    /**
     * The rail's bounding box in this cell, authored px {@code {x0, y0, z0, x1, y1, z1}}:
     * the piece in this cell, on the plate, the rail's full depth and width. What the
     * cell is picked and collided by, as {@code DinRailBlock.railBounds} is a block's.
     */
    public double[] bounds() {
        double half = DinRailBlock.WIDTH / 2;
        return new double[] {fromPx(), platePx(), acrossPx() - half, toPx(), platePx() + DinRailBlock.DEPTH,
                acrossPx() + half};
    }

    /** Gear seated from this span: the cell it goes in, blocks from this one, and its mount there. */
    public record CellSeat(int dx, int dy, int dz, ControlMount mount) {}

    /**
     * Where gear {@code width} modules wide goes when a click lands {@code clickedAlong}
     * authored px along this span's rail, or null if the rail is too short for it.
     *
     * It snaps along the whole rail, not only this piece, and so may be centred in a
     * neighbouring cell along the rail; that cell hosts it, which is the only way its
     * mount can keep its middle on the host's face ({@link ControlMount#isOnFace}). Only
     * the in-plane axes move: the plane is the cell's own face against the wall.
     */
    public CellSeat seat(double clickedAlong, double width) {
        if (railToPx() - railFromPx() < width)
            return null;
        double start = DinRailSeat.snapStart(clickedAlong, width, railFromPx(), railToPx());
        double[] target = DinRailSeat.target(facing, rotation, acrossPx(), start, width);
        int[] shift = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i == facing.getAxis().ordinal())
                continue;
            shift[i] = (int) Math.floor(target[i] / 16);
            target[i] -= shift[i] * 16;
        }
        return new CellSeat(shift[0], shift[1], shift[2], DinRailSeat.seat(facing, rotation, target, plate));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Cabinet", cabinet);
        tag.putInt("Rail", railId);
        tag.putByte("Facing", (byte) facing.get3DDataValue());
        tag.putByte("Rotation", (byte) rotation);
        tag.putIntArray("Span", new int[] {from, to, railFrom, railTo, across, plate});
        return tag;
    }

    /** Null for a tag that is not a whole span. */
    public static RailSpan load(CompoundTag tag) {
        int[] span = tag.getIntArray("Span");
        if (!tag.hasUUID("Cabinet") || span.length != 6)
            return null;
        return new RailSpan(tag.getUUID("Cabinet"), tag.getInt("Rail"), Direction.from3DDataValue(tag.getByte("Facing")),
                tag.getByte("Rotation") & 1, span[0], span[1], span[2], span[3], span[4], span[5]);
    }
}
