package com.nectariich.create_flippies.content.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.nectariich.create_flippies.foundation.ShapeUnion;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The slab of plate that one cell of a panel holds, as boxes in that cell's own
 * coordinates.
 *
 * A cardinal panel gives one box and it is exact: u, v and depth each run along a
 * world axis, so the plate is an axis-aligned slab and clipping it to the cell is
 * three interval intersections.
 *
 * A diagonal cannot be exact, because {@link VoxelShape} is a union of axis-aligned
 * boxes and there is no such thing as a rotated one. It is cut into slices instead,
 * along one of the two axes the turn is not about, each slice a box spanning what
 * the plate covers of the other over that slice, which
 * is the same staircase every diagonal block in every mod resolves to. The count is
 * a straight trade: a slice is a box in the shape, in the outline the player sees,
 * and in every collision test against it, so the shape is cut at the texture's own
 * resolution and not finer. At sixteen slices the step is a pixel, which is the
 * scale the panel is drawn at anyway.
 *
 * Everything below works in block units within the cell, so the caller never has to
 * know that the panel it belongs to is stated in absolute world coordinates.
 */
public final class PanelGeometry {

    /** Slices per block along x for a diagonal plate; see the class comment. */
    private static final int SLICES = 16;

    /**
     * Below this a slice or an interval is empty rather than merely thin. A
     * thousandth of a pixel: far under anything the plate can legitimately be, since
     * the plate is two pixels thick, and far over the arithmetic.
     */
    private static final double EPSILON = 1.0e-6;

    private static final PanelTile[] NO_TILES = new PanelTile[0];

    private PanelGeometry() {}

    /** The plate in one cell, in that cell's coordinates, or empty if it misses. */
    public static VoxelShape plateShape(PanelDefinition panel, BlockPos cell) {
        List<VoxelShape> parts = new ArrayList<>();
        for (AABB box : plateBoxes(panel, cell))
            parts.add(Shapes.create(box));
        return ShapeUnion.of(parts);
    }

    /**
     * The plate in one cell, as boxes in that cell's coordinates.
     *
     * Wanted as boxes and not only as a shape because the model has to build quads
     * from them, and a {@link VoxelShape} has already merged and split them into
     * whatever its own representation preferred.
     */
    public static AABB[] plateBoxes(PanelDefinition panel, BlockPos cell) {
        List<PanelDefinition> pieces = panel.pieces();
        if (pieces.size() == 1)
            return pieceBoxes(pieces.get(0), cell);
        List<AABB> gathered = new ArrayList<>();
        for (PanelDefinition piece : pieces)
            Collections.addAll(gathered, pieceBoxes(piece, cell));
        return gathered.toArray(new AABB[0]);
    }

    /** The same for one whole rectangle of a panel, which is what a piece is. */
    private static AABB[] pieceBoxes(PanelDefinition panel, BlockPos cell) {
        PanelPlane plane = panel.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double thickness = PanelDefinition.THICKNESS / scale;

        if (!plane.isTurned()) {
            // Straight on, v runs along a world axis and its extent is a world interval
            int vAxis = axisOf(plane.tangentV());
            double[] vSpan = overlap(panel.minV() / scale, panel.maxV() / scale, corner(cell, vAxis),
                    corner(cell, vAxis) + 1);
            return vSpan == null ? new AABB[0] : cardinalBox(panel, cell, plane, vAxis, vSpan, thickness);
        }
        return turnedSlices(panel, cell, plane, thickness);
    }

    /** A cardinal plate is one slab, and clipping it to the cell loses nothing. */
    private static AABB[] cardinalBox(PanelDefinition panel, BlockPos cell, PanelPlane plane, int vAxis, double[] vSpan,
            double thickness) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        int uAxis = axisOf(plane.tangentU());
        int nAxis = 3 - uAxis - vAxis;

        double[] uSpan = overlap(panel.minU() / scale, panel.maxU() / scale, corner(cell, uAxis),
                corner(cell, uAxis) + 1);
        if (uSpan == null)
            return new AABB[0];

        // The plate hangs behind its surface, so the far face is at depth zero and
        // the near one a thickness behind it
        double faceAt = plane.at(0, 0, 0).get(axis(nAxis));
        double backAt = plane.at(0, 0, -thickness).get(axis(nAxis));
        double[] nSpan = overlap(Math.min(faceAt, backAt), Math.max(faceAt, backAt), corner(cell, nAxis),
                corner(cell, nAxis) + 1);
        if (nSpan == null)
            return new AABB[0];

        return new AABB[] { local(cell, uAxis, uSpan, vAxis, vSpan, nAxis, nSpan) };
    }

    /**
     * A turned plate, sliced along x.
     *
     * One slicer serves every way a panel can be turned, because every turn leaves
     * the axis it is about alone. A wall turned about the vertical has u and the
     * depth lying in the horizontal plane and v running straight up; a spun floor has
     * u and v in the horizontal plane and the depth running straight up; a face tipped
     * about x or z has u running along that axis. Either way exactly one of the three
     * runs along the turn's axis and the other two are affine in the remaining
     * coordinate at a fixed slice, so that one is an interval outright and the slice
     * is the intersection of the two that are left.
     *
     * The slice is sampled at its middle rather than at an edge: the plate is convex,
     * so the middle is inside it wherever any of the slice is, and sampling an edge
     * would drop the last slice of a run that ends between two sample lines.
     */
    private static AABB[] turnedSlices(PanelDefinition panel, BlockPos cell, PanelPlane plane, double thickness) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        // The turn's own axis, then the axis sliced along and the one solved along —
        // x and z about the vertical, which is how this read before the other turns
        int held = plane.turnAxis().ordinal();
        int sliced = held == 0 ? 1 : 0;
        int solved = 3 - held - sliced;

        Limit[] limits = {
                new Limit(plane::u, panel.minU() / scale, panel.maxU() / scale, plane.tangentU()),
                new Limit(plane::v, panel.minV() / scale, panel.maxV() / scale, plane.tangentV()),
                new Limit(plane::depth, -thickness, 0, plane.outward()) };

        double[] heldSpan = null;
        Limit[] across = new Limit[2];
        int crossing = 0;
        for (Limit limit : limits) {
            if (Math.abs(component(limit.along(), held)) > 0.5) {
                double[] reach = solve(t -> limit.of().applyAsDouble(point(held, t, sliced, 0)), limit.low(),
                        limit.high());
                heldSpan = reach == null ? null
                        : overlap(reach[0], reach[1], corner(cell, held), corner(cell, held) + 1);
                if (heldSpan == null)
                    return new AABB[0];
            } else {
                across[crossing++] = limit;
            }
        }
        if (heldSpan == null || crossing != 2)
            return new AABB[0];

        double step = 1.0 / SLICES;
        AABB[] found = new AABB[SLICES];
        int count = 0;
        for (int slice = 0; slice < SLICES; slice++) {
            double low = corner(cell, sliced) + slice * step;
            double middle = low + step / 2;

            double[] first = solve(t -> across[0].of().applyAsDouble(point(sliced, middle, solved, t)),
                    across[0].low(), across[0].high());
            double[] second = solve(t -> across[1].of().applyAsDouble(point(sliced, middle, solved, t)),
                    across[1].low(), across[1].high());
            if (first == null || second == null)
                continue;
            double[] solvedSpan = overlap(Math.max(first[0], second[0]), Math.min(first[1], second[1]),
                    corner(cell, solved), corner(cell, solved) + 1);
            if (solvedSpan == null)
                continue;

            found[count++] = local(cell, sliced, new double[] { low, low + step }, held, heldSpan, solved, solvedSpan);
        }

        AABB[] boxes = new AABB[count];
        System.arraycopy(found, 0, boxes, 0, count);
        return boxes;
    }

    /** One of the three intervals a plate is: a plane coordinate, its bounds, and the way it runs. */
    private record Limit(ToDoubleFunction<Vec3> of, double low, double high, Vec3 along) {}

    /** A world point with two coordinates given and the third zero. */
    private static Vec3 point(int axisA, double a, int axisB, double b) {
        double[] p = new double[3];
        p[axisA] = a;
        p[axisB] = b;
        return new Vec3(p[0], p[1], p[2]);
    }

    /**
     * How far along a spun plate's normal it occupies a cell, or null if it misses
     * it. The normal is the axis of the spin, so this is a world interval.
     */
    private static double[] depthSpan(PanelPlane plane, BlockPos cell, double thickness) {
        int axis = plane.turnAxis().ordinal();
        double faceAt = component(plane.at(0, 0, 0), axis);
        double backAt = component(plane.at(0, 0, -thickness), axis);
        return overlap(Math.min(faceAt, backAt), Math.max(faceAt, backAt), corner(cell, axis), corner(cell, axis) + 1);
    }

    /**
     * The plate in one cell as tiles to draw, which is the same slab
     * {@link #plateBoxes} approximates but stated exactly.
     *
     * There is one box, not a staircase, and that is not an approximation either.
     * The plate is a slab in the plane's own frame; v runs along a world axis, so the
     * cell's bounds on that axis cut it to a v interval outright, and each of the
     * other two cuts it at a single u — a plane crossing a slab crosses it once. So
     * the intersection of plate and cell is a box in {@code (u, v, depth)}, and the
     * only thing standing between that and geometry is a rotation, which quads can
     * express and {@link net.minecraft.world.phys.shapes.VoxelShape} cannot.
     *
     * It is then split at whole u and whole v so the material repeats once per block
     * along the panel; see {@link PanelTile}. A cell yields one tile, or two where a
     * diagonal crosses a repeat inside it, and never more.
     */
    public static PanelTile[] plateTiles(PanelDefinition panel, BlockPos cell) {
        List<PanelDefinition> pieces = panel.pieces();
        if (pieces.size() == 1)
            return pieceTiles(pieces.get(0), cell);
        List<PanelTile> gathered = new ArrayList<>();
        for (PanelDefinition piece : pieces)
            Collections.addAll(gathered, pieceTiles(piece, cell));
        return gathered.toArray(NO_TILES);
    }

    /** The same for one whole rectangle of a panel, which is what a piece is. */
    private static PanelTile[] pieceTiles(PanelDefinition panel, BlockPos cell) {
        PanelPlane plane = panel.plane();
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double thickness = PanelDefinition.THICKNESS / scale;

        if (plane.isSpun())
            return spunTiles(panel, cell, plane, thickness);

        // One in-plane axis runs along a world axis: v on a cardinal plane or a wall
        // turned about the vertical, u on a face tipped about x or z. That one is cut
        // to the cell outright and the other is the run across it
        boolean alongU = !isAxial(plane.tangentV());
        int heldAxis = axisOf(alongU ? plane.tangentU() : plane.tangentV());
        double[] heldSpan = alongU
                ? overlap(panel.minU() / scale, panel.maxU() / scale, corner(cell, heldAxis), corner(cell, heldAxis) + 1)
                : overlap(panel.minV() / scale, panel.maxV() / scale, corner(cell, heldAxis),
                        corner(cell, heldAxis) + 1);
        if (heldSpan == null)
            return NO_TILES;

        double[] runSpan = runWithin(panel, plane, cell, heldAxis, -thickness / 2, alongU);
        if (runSpan == null)
            return NO_TILES;
        double[] uSpan = alongU ? heldSpan : runSpan;
        double[] vSpan = alongU ? runSpan : heldSpan;

        List<PanelTile> tiles = new ArrayList<>(2);
        for (double[] uPart : repeats(uSpan))
            for (double[] vPart : repeats(vSpan))
                tiles.add(tile(plane, cell, uPart, vPart, thickness));
        return tiles.toArray(new PanelTile[0]);
    }

    /**
     * A flat panel lying at forty-five degrees, whose cells hold polygons rather than
     * boxes.
     *
     * Both in-plane axes are turned against the block grid here, so a wall of the cell
     * cuts the rectangle cornerwise and there is no interval of u that answers for the
     * whole cell the way there is for a turned wall. What is used instead is the
     * smallest box of u and v that could reach into the cell at all — the four corners
     * of the cell read as plane coordinates — and the overhang that leaves is cut off
     * afterwards by clipping the placed geometry to the cell itself.
     *
     * Neighbouring cells therefore claim overlapping boxes and are trimmed back to
     * exactly their own share, so the panel is covered once and only once, with a
     * clean diagonal edge instead of a staircase of whole blocks.
     */
    private static PanelTile[] spunTiles(PanelDefinition panel, BlockPos cell, PanelPlane plane, double thickness) {
        if (depthSpan(plane, cell, thickness) == null)
            return NO_TILES;

        double scale = PanelPlane.UNITS_PER_BLOCK;
        // The cell's square across the spin's axis, which is the plate's normal
        int held = plane.turnAxis().ordinal();
        int first = held == 0 ? 1 : 0;
        int second = 3 - held - first;
        double[] uReach = null;
        double[] vReach = null;
        for (int corner = 0; corner < 4; corner++) {
            Vec3 point = point(first, corner(cell, first) + (corner & 1), second,
                    corner(cell, second) + ((corner >> 1) & 1));
            uReach = stretch(uReach, plane.u(point));
            vReach = stretch(vReach, plane.v(point));
        }
        double[] uSpan = overlap(uReach[0], uReach[1], panel.minU() / scale, panel.maxU() / scale);
        double[] vSpan = overlap(vReach[0], vReach[1], panel.minV() / scale, panel.maxV() / scale);
        if (uSpan == null || vSpan == null)
            return NO_TILES;

        AABB cube = new AABB(0, 0, 0, 1, 1, 1);
        List<PanelTile> tiles = new ArrayList<>(4);
        for (double[] uPart : repeats(uSpan))
            for (double[] vPart : repeats(vSpan))
                tiles.add(tile(plane, cell, uPart, vPart, thickness).clipped(cube));
        return tiles.toArray(new PanelTile[0]);
    }

    /** An interval grown to take in one more value, started from nothing by null. */
    private static double[] stretch(double[] span, double value) {
        if (span == null)
            return new double[] { value, value };
        return new double[] { Math.min(span[0], value), Math.max(span[1], value) };
    }

    /**
     * How far along the panel the cell reaches, in u — or in v when {@code alongV}, which
     * is the run on a face tipped about x or z — or null if it does not.
     *
     * Taken on the plate's midsurface, which is the same surface
     * {@link PanelDefinition#forEachCell} walks to decide which cells there are. That
     * agreement is the point: consecutive cells then hand back consecutive u
     * intervals that meet exactly, so the tiles they draw tile the panel with no gap
     * and no overlap.
     *
     * The two axes that are not the held one are the ones the run varies along, and
     * the held axis contributes nothing to either of them because the frame is
     * orthogonal — which is why a single interval can answer for the whole cell rather
     * than one per height.
     */
    private static double[] runWithin(PanelDefinition panel, PanelPlane plane, BlockPos cell, int heldAxis,
            double depth, boolean alongV) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double[] span = alongV ? new double[] { panel.minV() / scale, panel.maxV() / scale }
                : new double[] { panel.minU() / scale, panel.maxU() / scale };
        for (int index = 0; index < 3; index++) {
            if (index == heldAxis)
                continue;
            int axis = index;
            double[] reach = solve(t -> component(alongV ? plane.at(0, t, depth) : plane.at(t, 0, depth), axis),
                    corner(cell, index), corner(cell, index) + 1);
            if (reach == null)
                return null;
            span = overlap(span[0], span[1], reach[0], reach[1]);
            if (span == null)
                return null;
        }
        return span;
    }

    /** An interval cut at every whole number it crosses, so no piece spans a repeat. */
    private static List<double[]> repeats(double[] span) {
        List<double[]> parts = new ArrayList<>(2);
        double low = span[0];
        while (low < span[1] - EPSILON) {
            double high = Math.min(Math.floor(low + EPSILON) + 1, span[1]);
            parts.add(new double[] { low, high });
            low = high;
        }
        return parts;
    }

    /** One repeat's worth of plate, as the material box and the frame that places it. */
    private static PanelTile tile(PanelPlane plane, BlockPos cell, double[] uPart, double[] vPart, double thickness) {
        double uBase = Math.floor(uPart[0] + EPSILON);
        double vBase = Math.floor(vPart[0] + EPSILON);
        AABB source = new AABB(uPart[0] - uBase, vPart[0] - vBase, 0, uPart[1] - uBase, vPart[1] - vBase, thickness);
        Vec3 origin = plane.at(uBase, vBase, 0).subtract(cell.getX(), cell.getY(), cell.getZ());
        return new PanelTile(source, origin, plane.tangentU(), plane.tangentV(), plane.outward().scale(-1), null);
    }

    private static double component(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    /**
     * The free variable where an affine function of it lies within
     * {@code [low, high]}, or null where it never does. A function with no slope is
     * either everywhere in range or nowhere, and saying so is what lets the caller
     * treat a cardinal-looking constraint on a diagonal plane without a special case.
     */
    private static double[] solve(java.util.function.DoubleUnaryOperator f, double low, double high) {
        double at0 = f.applyAsDouble(0);
        double slope = f.applyAsDouble(1) - at0;
        if (Math.abs(slope) < EPSILON)
            return at0 >= low - EPSILON && at0 <= high + EPSILON
                    ? new double[] { Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY }
                    : null;
        double a = (low - at0) / slope;
        double b = (high - at0) / slope;
        return new double[] { Math.min(a, b), Math.max(a, b) };
    }

    /** Two world intervals intersected, or null if they do not meet. */
    private static double[] overlap(double lowA, double highA, double lowB, double highB) {
        double low = Math.max(lowA, lowB);
        double high = Math.min(highA, highB);
        return high - low > EPSILON ? new double[] { low, high } : null;
    }

    /** Three world intervals, one per axis, gathered into a box local to the cell. */
    private static AABB local(BlockPos cell, int axisA, double[] spanA, int axisB, double[] spanB, int axisC,
            double[] spanC) {
        double[] low = new double[3];
        double[] high = new double[3];
        low[axisA] = spanA[0];
        high[axisA] = spanA[1];
        low[axisB] = spanB[0];
        high[axisB] = spanB[1];
        low[axisC] = spanC[0];
        high[axisC] = spanC[1];
        return new AABB(low[0] - cell.getX(), low[1] - cell.getY(), low[2] - cell.getZ(), high[0] - cell.getX(),
                high[1] - cell.getY(), high[2] - cell.getZ());
    }

    /** Whether a unit vector points along a world axis rather than across two. */
    private static boolean isAxial(Vec3 unit) {
        return Math.max(Math.abs(unit.x), Math.max(Math.abs(unit.y), Math.abs(unit.z))) > 0.99;
    }

    /** Which world axis a unit vector points along; only ever asked of one that does. */
    private static int axisOf(Vec3 unit) {
        if (Math.abs(unit.x) > 0.5)
            return 0;
        return Math.abs(unit.y) > 0.5 ? 1 : 2;
    }

    private static net.minecraft.core.Direction.Axis axis(int index) {
        return net.minecraft.core.Direction.Axis.values()[index];
    }

    private static double corner(BlockPos cell, int axis) {
        return axis == 0 ? cell.getX() : axis == 1 ? cell.getY() : cell.getZ();
    }
}
