package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a cabinet's rails are in the world, worked out exactly: which cells each one
 * runs through and what piece of it each of those cells carries ({@link RailSpan}).
 *
 * A cabinet stands at a quarter turn with its position on the pixel grid, so its
 * model maps to the world by whole pixels along world axes and nothing here needs a
 * sine. {@code origin} is the cabinet's position in world pixels; {@code out} is the
 * way its front looks, out of the wall. The model's x runs along {@code out}, its y
 * up, and its z along {@code out}'s clockwise neighbour, which is what
 * {@code DeviceShape} does at the turn a wall gives ({@code CabinetRailFrameTest}
 * holds the two together).
 *
 * The cells a rail runs through are those just in front of the back plate. The
 * plate is never a block deep and the cabinet hangs on a wall, so that is one layer
 * of cells, and the rails' and gear's host blocks all live in it.
 */
public record CabinetRailFrame(int originX, int originY, int originZ, Direction out) {

    private static final int UNIT = ControlMount.UNITS_PER_PIXEL;

    /** The frame of a cabinet standing at {@code position} looking {@code out}. */
    public static CabinetRailFrame of(Vec3 position, Direction out) {
        return new CabinetRailFrame((int) Math.round(position.x * 16), (int) Math.round(position.y * 16),
                (int) Math.round(position.z * 16), out);
    }

    /** The world direction the model's z runs along. */
    public Direction across() {
        return out.getClockWise();
    }

    /** A model point, px, in world pixels. */
    public double[] worldPx(double mx, double my, double mz) {
        Direction z = across();
        return new double[] {
                originX + (mx - 8) * out.getStepX() + (mz - 8) * z.getStepX(),
                originY + my,
                originZ + (mx - 8) * out.getStepZ() + (mz - 8) * z.getStepZ()};
    }

    /** The way gear and rails on the back plate face: into the wall. */
    public Direction facing() {
        return out.getOpposite();
    }

    /**
     * The quarter turn of the authored frame that runs its x along a rail: level or
     * upright. Found by asking the orientation rather than tabled.
     */
    public int rotationFor(boolean vertical) {
        Direction.Axis along = vertical ? Direction.Axis.Y : across().getAxis();
        for (int rotation = 0; rotation < 2; rotation++)
            if (axisOf(step(facing(), rotation, 0)) == along)
                return rotation;
        throw new IllegalStateException("No turn runs a rail along " + along + " facing " + facing());
    }

    /**
     * The world step, as a whole block, that the authored axis {@code axis} (0 x, 2 z)
     * takes in a cell of this orientation.
     */
    static int[] step(Direction facing, int rotation, int axis) {
        double[] o = SurfaceMount.point(8, 0, 8, facing, rotation);
        double[] p = SurfaceMount.point(axis == 0 ? 9 : 8, 0, axis == 2 ? 9 : 8, facing, rotation);
        return new int[] {(int) Math.round(p[0] - o[0]), (int) Math.round(p[1] - o[1]), (int) Math.round(p[2] - o[2])};
    }

    private static Direction.Axis axisOf(int[] step) {
        return step[0] != 0 ? Direction.Axis.X : step[1] != 0 ? Direction.Axis.Y : Direction.Axis.Z;
    }

    /** One cell a rail runs through and the piece of it there. */
    public record Cell(BlockPos pos, RailSpan span) {}

    /**
     * Every cell {@code rail} of the cabinet {@code cabinet} runs through, with the
     * piece each carries, in order along the rail's authored x.
     *
     * The first cell is the one the rail's start is in, chosen so that the start's
     * coordinates along and across the rail are within 0 to 16 px there — a rail on a
     * block boundary belongs to the cell on the positive side of the authored frame,
     * which is the cell gear centred on it will be seated in too. The rest follow
     * along the rail a block at a time ({@link #pieces}).
     */
    public List<Cell> cells(UUID cabinet, CabinetRail rail, CabinetSpec spec) {
        Direction facing = facing();
        int rotation = rotationFor(rail.vertical());
        double plateX = CabinetGeometry.backPlane(spec).x();
        double[] a = rail.pointAlong(rail.start());
        double[] b = rail.pointAlong(rail.end());
        double[] start = worldPx(plateX, a[0], a[1]);
        double[] end = worldPx(plateX, b[0], b[1]);

        // The cell just in front of the plate, then put where the start is within it
        int[] cell = new int[3];
        for (int i = 0; i < 3; i++)
            cell[i] = Math.floorDiv((int) Math.floor(start[i] + 0.5 * stepOf(out, i)), 16);
        double[] s = local(start, cell, facing, rotation);
        int[] alongStep = step(facing, rotation, 0), acrossStep = step(facing, rotation, 2);
        int shiftAlong = (int) Math.floor(s[0] / 16), shiftAcross = (int) Math.floor(s[2] / 16);
        for (int i = 0; i < 3; i++)
            cell[i] += shiftAlong * alongStep[i] + shiftAcross * acrossStep[i];
        s = local(start, cell, facing, rotation);
        double[] e = local(end, cell, facing, rotation);

        int railFrom = units(Math.min(s[0], e[0])), railTo = units(Math.max(s[0], e[0]));
        int across = units(s[2]), plate = units(s[1]);
        List<Cell> cells = new ArrayList<>();
        for (int[] piece : pieces(railFrom, railTo)) {
            int k = piece[0];
            BlockPos pos = new BlockPos(cell[0] + k * alongStep[0], cell[1] + k * alongStep[1],
                    cell[2] + k * alongStep[2]);
            int shift = k * 16 * UNIT;
            cells.add(new Cell(pos, new RailSpan(cabinet, rail.id(), facing, rotation, piece[1], piece[2],
                    railFrom - shift, railTo - shift, across, plate)));
        }
        return cells;
    }

    /**
     * The pieces a rail running {@code railFrom} to {@code railTo} along authored x,
     * in sixteenths of a pixel from the first cell's origin, falls into a block at a
     * time: {@code {k, from, to}}, {@code k} blocks along from the first cell and
     * {@code from} to {@code to} within that cell. A rail ending exactly on a cell
     * boundary leaves no empty piece in the next cell.
     */
    public static List<int[]> pieces(int railFrom, int railTo) {
        int block = 16 * UNIT;
        List<int[]> pieces = new ArrayList<>();
        if (railTo <= railFrom)
            return pieces;
        for (int k = Math.floorDiv(railFrom, block); k * block < railTo; k++) {
            int from = Math.max(railFrom - k * block, 0), to = Math.min(railTo - k * block, block);
            if (to > from)
                pieces.add(new int[] {k, from, to});
        }
        return pieces;
    }

    private static int stepOf(Direction d, int i) {
        return i == 0 ? d.getStepX() : i == 1 ? d.getStepY() : d.getStepZ();
    }

    private static double[] local(double[] world, int[] cell, Direction facing, int rotation) {
        return SurfaceMount.localPoint(world[0] - cell[0] * 16, world[1] - cell[1] * 16, world[2] - cell[2] * 16, facing,
                rotation);
    }

    private static int units(double px) {
        return (int) Math.round(px * UNIT);
    }
}
