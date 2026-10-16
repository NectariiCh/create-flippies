package com.nectariich.create_flippies.foundation.grid;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;

/**
 * A rectangle on one flat surface, ruled into cells, in block units and owned by
 * nobody.
 *
 * {@link PlacementGrid} is the persisted, integer-pixel, world-wide article; this
 * is the transient one the screwdriver reasons about while the player is still
 * aiming. Four things it has to describe are not grids in the store and must not
 * become entries in it: the whole face of an ordinary block that mode 1
 * subdivides, one block's worth of a chamfer that mode 1 subdivides instead when
 * there is no face to use, one cell of a stored grid that mode 3 subdivides again,
 * and the rectangle between two half-chosen corners in mode 2. Giving them a
 * common shape is what lets one aim resolver and one overlay serve all the modes,
 * so the preview and the placement cannot disagree about which cell the crosshair
 * is in.
 *
 * The unit is the block rather than the pixel because a stored grid's cell edges
 * are fractions of a pixel — a sixteen-pixel face ruled into three columns has
 * them at thirds — so integers would not represent the mode 3 case at all. That
 * costs the exactness {@link PlacementGrid} goes to some trouble for, which is
 * acceptable here and nowhere else: nothing in this class is persisted, sent, or
 * compared against a neighbour's idea of a shared edge. The one number that
 * escapes is a cell centre, and it is rounded to the mount lattice, which is four
 * times coarser than the finest ruling the store accepts.
 *
 * The surface is a {@link PanelPlane}, so u and v are its axes and not the world's
 * — which on a cardinal plane is the same thing, and on a chamfer is the only
 * thing that could be meant.
 */
public record FaceRegion(PanelPlane plane, double minU, double minV, double maxU, double maxV, int columns, int rows) {

    /** Returned by {@link #cellAt} for a point outside the rectangle. */
    public static final int OUTSIDE = PlacementGrid.OUTSIDE;

    private static final double PIXEL_SCALE = PlacementGrid.PIXELS_PER_BLOCK;

    /**
     * One block's worth of a surface whose axes are the world's, ruled into
     * {@code columns} by {@code rows} and squared to the block grid.
     *
     * The plane is passed in rather than taken from the block, because the two are
     * not the same thing. A panel's surface is snapped to the pixel, not to the block
     * boundary, so it may lie anywhere inside {@code cell} — but its u and v are
     * still world axes, and a ruling that did not line up with the blocks behind it
     * would be a poor way to lay out a wall. So the squares come from the block and
     * the depth comes from the plane.
     */
    public static FaceRegion wholeFace(PanelPlane plane, BlockPos cell, int columns, int rows) {
        Axis axis = plane.face().getAxis();
        double u = PlacementGrid.uAxis(axis).choose(cell.getX(), cell.getY(), cell.getZ());
        double v = PlacementGrid.vAxis(axis).choose(cell.getX(), cell.getY(), cell.getZ());
        return new FaceRegion(plane, u, v, u + 1, v + 1, columns, rows);
    }

    /**
     * One block's worth of a surface that has no block faces: the square of the
     * plane's own grid containing {@code u, v}, counted from {@code originU} and
     * {@code originV}.
     *
     * The origin is a chamfer's own corner rather than the world origin, and that
     * choice is the whole of what makes this usable. A diagonal plane's u runs from
     * the foot of a perpendicular dropped from the world origin, which is nowhere in
     * particular, so squares counted from there would cut the chamfer at an arbitrary
     * phase and the first one would be a sliver. Counted from the panel's own edge
     * they line up with it, and a player ruling the first square of a chamfer four
     * ways gets four equal cells starting where the chamfer starts.
     */
    public static FaceRegion square(PanelPlane plane, double originU, double originV, double u, double v, int columns,
            int rows) {
        double lowU = originU + Math.floor(u - originU);
        double lowV = originV + Math.floor(v - originV);
        return new FaceRegion(plane, lowU, lowV, lowU + 1, lowV + 1, columns, rows);
    }

    /** A stored grid, as its own cells. */
    public static FaceRegion of(PlacementGrid grid) {
        double[] rect = grid.rect();
        return new FaceRegion(grid.plane(), rect[0], rect[1], rect[2], rect[3], grid.columns(), grid.rows());
    }

    /** One cell of a stored grid, ruled again into {@code columns} by {@code rows}. */
    public static FaceRegion cellOf(PlacementGrid grid, int cell, int columns, int rows) {
        double[] rect = grid.cellRect(cell);
        return new FaceRegion(grid.plane(), rect[0], rect[1], rect[2], rect[3], columns, rows);
    }

    /** An undivided pixel rectangle, which is the shape a stored grid is ruled from. */
    public static FaceRegion undivided(PanelPlane plane, int minU, int minV, int maxU, int maxV) {
        return new FaceRegion(plane, minU / PIXEL_SCALE, minV / PIXEL_SCALE, maxU / PIXEL_SCALE, maxV / PIXEL_SCALE, 1,
                1);
    }

    /**
     * An undivided rectangle in the offset lattice, which is what a pair of picked
     * corners spans — finer than a pixel, because a corner snapped inside a ruled cell
     * carries that cell's phase; see {@code GridCorner}.
     */
    public static FaceRegion spanned(PanelPlane plane, int minU, int minV, int maxU, int maxV) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        return new FaceRegion(plane, minU / scale, minV / scale, maxU / scale, maxV / scale, 1, 1);
    }

    /** The way a control placed in this region would point. */
    public Direction face() {
        return plane.face();
    }

    public int cellCount() {
        return columns * rows;
    }

    public int columnOf(int cell) {
        return cell % columns;
    }

    public int rowOf(int cell) {
        return cell / columns;
    }

    /** The u of a column boundary, {@code index} running 0 to {@link #columns} inclusive. */
    public double columnEdge(int index) {
        return minU + (maxU - minU) * index / (double) columns;
    }

    public double rowEdge(int index) {
        return minV + (maxV - minV) * index / (double) rows;
    }

    /**
     * The cell a point falls in, or {@link #OUTSIDE}. The point is in world block
     * units and is read through the plane's own axes.
     *
     * The far edge counts as belonging to the last cell rather than to nothing,
     * which {@link PlacementGrid#cellAt(double, double)} deliberately does not do.
     * The difference is the difference in what the two are for: a stored grid is
     * asked whether it owns a point and must not claim one merely touching it,
     * whereas a region is the surface the player is aiming at, and a ray that lands
     * exactly on the far edge of a block face has to resolve to a cell or the click
     * does nothing for no visible reason.
     */
    public int cellAt(Vec3 point) {
        return cellAt(plane.u(point), plane.v(point));
    }

    public int cellAt(double u, double v) {
        int column = trackAt(u, minU, maxU, columns);
        int row = trackAt(v, minV, maxV, rows);
        if (column == OUTSIDE || row == OUTSIDE)
            return OUTSIDE;
        return row * columns + column;
    }

    public double cellCentreU(int cell) {
        return (columnEdge(columnOf(cell)) + columnEdge(columnOf(cell) + 1)) / 2;
    }

    public double cellCentreV(int cell) {
        return (rowEdge(rowOf(cell)) + rowEdge(rowOf(cell) + 1)) / 2;
    }

    /** An in-plane point, lifted {@code proud} blocks off the surface so it draws clear of it. */
    public Vec3 point(double u, double v, double proud) {
        return plane.at(u, v, proud);
    }

    /**
     * The four corners of one cell, anticlockwise from the low one, lifted proud of
     * the surface.
     *
     * Corners rather than a box because a rectangle at forty-five degrees is not
     * one: its {@code AABB} is a square of twice the area that says nothing about
     * where the rectangle is. Four points draw the same outline for both.
     */
    public Vec3[] cellCorners(int cell, double proud) {
        return corners(columnEdge(columnOf(cell)), rowEdge(rowOf(cell)), columnEdge(columnOf(cell) + 1),
                rowEdge(rowOf(cell) + 1), proud);
    }

    /** The four corners of the whole rectangle. */
    public Vec3[] corners(double proud) {
        return corners(minU, minV, maxU, maxV, proud);
    }

    private Vec3[] corners(double lowU, double lowV, double highU, double highV, double proud) {
        return new Vec3[] { point(lowU, lowV, proud), point(highU, lowV, proud), point(highU, highV, proud),
                point(lowU, highV, proud) };
    }

    private static int trackAt(double coordinate, double min, double max, int count) {
        double extent = max - min;
        if (extent <= 0 || coordinate < min || coordinate > max)
            return OUTSIDE;
        return Math.min((int) Math.floor((coordinate - min) * count / extent), count - 1);
    }
}
