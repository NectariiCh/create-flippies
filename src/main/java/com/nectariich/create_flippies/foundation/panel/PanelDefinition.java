package com.nectariich.create_flippies.foundation.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One whole large panel: a rectangle on a {@link PanelPlane}, and the blocks it
 * turns into.
 *
 * Every cell of a panel stores this same record, identically. That is what makes
 * breaking one cell break the panel: the block that was hit already knows the
 * rectangle it belongs to, so the break walks {@link #forEachCell} and takes down
 * every cell whose own definition is {@code equals} to this one. There is no
 * panel store, no controller block, no shared id to keep alive, and nothing to go
 * stale — a cell either agrees about the rectangle or is not part of it. The cost
 * is a few dozen bytes repeated per cell, which is nothing beside the bookkeeping
 * a multiblock controller would need to survive chunk unloads, world edits and a
 * half-loaded panel straddling a chunk border.
 *
 * {@code minU} and the rest are in {@link PanelPlane#UNITS_PER_BLOCK}ths of a
 * block along the plane's own axes, the same unit the plane offset uses, so a
 * panel can start and end on a sub-pixel line — which is what lets a chamfer meet
 * its neighbours squarely.
 *
 * <h2>Which blocks a panel occupies</h2>
 *
 * For a cardinal panel this is a plain rectangle of blocks. For a diagonal it is
 * not, and the shape depends on the offset in a way worth stating: a diagonal
 * whose plane runs exactly through the block corners passes through one cell per
 * root two of travel, each holding an identical corner-to-corner slice, whereas
 * one offset off the corners crosses an x boundary and a z boundary alternately
 * and so passes through <i>two</i> cells per root two, each holding a smaller
 * slice. Both are legal and both look right; the corner-aligned one simply uses
 * half the blocks. So the walk below makes no assumption about alignment — it
 * follows the plate and reports whatever cells it actually crosses.
 *
 * <h2>Holes</h2>
 *
 * A panel is a rectangle less whatever has been taken out of it: {@link PanelCut}s,
 * for a tile unscrewed out of a wall or a hole drilled through it for a cable. They
 * are held on the definition rather than on the cells, which is the same decision
 * the rectangle itself is: the definition is what every cell agrees about, so a hole
 * has to be part of it or the cells either side of one would disagree and the panel
 * would break in pieces.
 *
 * Everything else is spared knowing about them by {@link #pieces}, which states the
 * panel as the uncut rectangles it is left as. Both the walk below and the geometry
 * in {@code PanelGeometry} simply run over those, so a hole costs a few more
 * rectangles and not one branch anywhere that draws, collides or breaks.
 */
public record PanelDefinition(PanelPlane plane, int minU, int minV, int maxU, int maxV, List<PanelCut> cuts) {

    /** How thick the plate is, matching Create's copycat panel at two pixels. */
    public static final int THICKNESS = 2 * PanelPlane.UNITS_PER_PIXEL;

    /** The longest a panel may be along either in-plane axis. */
    public static final int MAX_SPAN = 32 * PanelPlane.UNITS_PER_BLOCK;

    /**
     * The most blocks one panel may turn into. A diagonal costs about root two
     * cells per block of length, so this is reached well before {@link #MAX_SPAN}
     * is on a large panel, and it is the bound that actually protects the placement
     * loop and the break.
     */
    public static final int MAX_CELLS = 4096;

    /**
     * How far along the plate, in blocks, the walk steps past a boundary before
     * asking which cell it is in. Half a thousandth of a block is far enough clear
     * of a crossing to floor to the right side of it and far shorter than the
     * shortest slice a diagonal can cut, which is bounded below by the plate
     * thickness.
     */
    private static final double NUDGE = 1.0e-4;

    /**
     * The shortest run of plate, in blocks, that is worth a block of its own.
     *
     * A diagonal crosses an x boundary and a z boundary at two separate points, and
     * as the plate approaches running corner to corner those two points converge:
     * the wall goes from a full staircase of two cells per root two of travel to a
     * single file of one, and in between it passes through a regime where the second
     * cell of each pair holds a sliver of plate a fraction of a pixel wide. Placing a
     * block for that sliver would double the block count of a wall that looks like a
     * clean diagonal, and the block would be very nearly empty.
     *
     * Corner alignment cannot be hit exactly — the midsurface sits half a thickness
     * behind the plane, so an integral plane offset puts it an irrational distance
     * from the corner — which is precisely why a threshold is needed rather than an
     * equality. A quarter of a pixel is far above the residue alignment leaves
     * behind, which the offset lattice bounds at about a three hundred and sixtieth
     * of a block, and far below any sliver a player could have meant.
     */
    private static final double MIN_SLICE = 1.0 / 64;

    /**
     * Overlap below this is a shape touching a boundary rather than crossing it. A
     * thousandth of a pixel, which is far under the thinnest sliver of plate that is
     * worth a block and far over the arithmetic that produced it.
     */
    private static final double BOUNDARY = 1.0e-6;

    /**
     * How many holes one panel will hold.
     *
     * Generous — a wall with sixty-four separate openings in it is a wall somebody has
     * built out of holes — and bounded because {@link #pieces} is quadratic in it. Cuts
     * that can be joined are joined as they are added, so a doorway taken out a tile at
     * a time counts as one whatever order the clicks came in.
     */
    public static final int MAX_CUTS = 64;

    private static final String CUTS = "Cuts";

    /** Why a panel was refused, or {@link #NONE} if it was not. */
    public enum Problem {
        NONE,
        /** The plane itself is not one this models. */
        BAD_PLANE,
        /** Zero or negative extent, which is a rectangle with nothing in it. */
        DEGENERATE,
        TOO_LARGE,
        /** Within the span limits but still more blocks than {@link #MAX_CELLS}. */
        TOO_MANY_CELLS,
        /** Every last piece of it has been cut away, so there is no panel left. */
        NOTHING_LEFT;

        public boolean ok() {
            return this == NONE;
        }
    }

    /**
     * Holes are held sorted and immutable, so that two definitions describing the same
     * panel are {@code equals} however their cuts were arrived at.
     *
     * That is not tidiness. A cell belongs to a panel exactly when its definition
     * equals the panel's, and cuts are made one click at a time on cells that are
     * loaded in whatever order the world had them — so a list that remembered the
     * order it was built in would leave two cells of one panel disagreeing about a
     * panel they both hold.
     */
    public PanelDefinition {
        cuts = normalise(cuts);
    }

    private static List<PanelCut> normalise(List<PanelCut> cuts) {
        if (cuts == null || cuts.isEmpty())
            return List.of();
        List<PanelCut> sorted = new ArrayList<>(cuts.size());
        for (PanelCut cut : cuts)
            if (cut != null && !cut.isEmpty())
                sorted.add(cut);
        sorted.sort(Comparator.comparingInt(PanelCut::minU).thenComparingInt(PanelCut::minV)
                .thenComparingInt(PanelCut::maxU).thenComparingInt(PanelCut::maxV));
        return List.copyOf(sorted);
    }

    /** A whole rectangle, with nothing taken out of it — which is every panel as built. */
    public PanelDefinition(PanelPlane plane, int minU, int minV, int maxU, int maxV) {
        this(plane, minU, minV, maxU, maxV, List.of());
    }

    /** The rectangle between two corners on one plane, in either order. */
    public static PanelDefinition between(PanelPlane plane, int uA, int vA, int uB, int vB) {
        return new PanelDefinition(plane, Math.min(uA, uB), Math.min(vA, vB), Math.max(uA, uB), Math.max(vA, vB));
    }

    public int spanU() {
        return maxU - minU;
    }

    public int spanV() {
        return maxV - minV;
    }

    public Problem check() {
        if (!plane.check().ok())
            return Problem.BAD_PLANE;
        if (spanU() <= 0 || spanV() <= 0)
            return Problem.DEGENERATE;
        if (spanU() > MAX_SPAN || spanV() > MAX_SPAN)
            return Problem.TOO_LARGE;
        if (pieces().isEmpty())
            return Problem.NOTHING_LEFT;
        return cellCount() > MAX_CELLS ? Problem.TOO_MANY_CELLS : Problem.NONE;
    }

    // Holes

    /**
     * This panel with a rectangle taken out of it, or null if that would change
     * nothing — which is what a click on a tile that is already gone does.
     *
     * The rectangle is clipped to the panel first, so a hole drilled at the very edge
     * takes out the part of it that is panel and nothing beyond. Cuts that can be
     * joined to the new one are joined rather than listed beside it; see
     * {@link PanelCut#union}.
     */
    public PanelDefinition cut(PanelCut cut) {
        PanelCut within = cut == null ? null : cut.clippedTo(this);
        if (within == null)
            return null;
        for (PanelCut held : cuts)
            if (held.covers(within))
                return null;

        List<PanelCut> kept = new ArrayList<>(cuts.size() + 1);
        PanelCut growing = within;
        for (PanelCut held : cuts) {
            if (growing.covers(held))
                continue;
            PanelCut joined = growing.union(held);
            if (joined == null)
                kept.add(held);
            else
                growing = joined;
        }
        if (kept.size() >= MAX_CUTS)
            return null;
        kept.add(growing);
        return withCuts(kept);
    }

    /**
     * This panel with a rectangle put back, or null if there was nothing there to put
     * back.
     *
     * Every cut is asked to give up its share, which is why one tile can be restored
     * out of the middle of a hole that was drilled in one piece: the cut becomes the
     * ring of rectangles around the tile. A restore that would need more rectangles
     * than a panel may hold is refused rather than truncated, since a truncated cut
     * list is a hole quietly filling itself in somewhere else.
     */
    public PanelDefinition uncut(PanelCut cut) {
        PanelCut within = cut == null ? null : cut.clippedTo(this);
        if (within == null)
            return null;
        List<PanelCut> kept = new ArrayList<>(cuts.size() + 3);
        boolean changed = false;
        for (PanelCut held : cuts) {
            List<PanelCut> left = held.minus(within);
            changed |= left.size() != 1 || !left.get(0).equals(held);
            kept.addAll(left);
        }
        if (!changed || kept.size() > MAX_CUTS)
            return null;
        return withCuts(kept);
    }

    /**
     * This panel with whichever holes contain a point filled in entirely, or null if
     * none does. What a click meant to fill a hole in does, as against restoring one
     * tile of one.
     */
    public PanelDefinition fillAt(double u, double v) {
        List<PanelCut> kept = new ArrayList<>(cuts.size());
        for (PanelCut held : cuts)
            if (!held.holds(u, v))
                kept.add(held);
        return kept.size() == cuts.size() ? null : withCuts(kept);
    }

    /** The hole a point is in, or null if it is on plate. */
    public PanelCut cutAt(double u, double v) {
        for (PanelCut held : cuts)
            if (held.holds(u, v))
                return held;
        return null;
    }

    public PanelDefinition withCuts(List<PanelCut> cuts) {
        return new PanelDefinition(plane, minU, minV, maxU, maxV, cuts);
    }

    /**
     * The same panel between different edges, keeping whatever holes still fall inside
     * them. A hole the shortening ran past is dropped rather than clamped: it was a
     * hole in plate that is no longer there, and clamping would leave a notch in the
     * new edge that nobody drilled.
     */
    public PanelDefinition withBounds(int minU, int minV, int maxU, int maxV) {
        List<PanelCut> kept = new ArrayList<>(cuts.size());
        for (PanelCut held : cuts) {
            PanelCut clipped = held.clippedTo(minU, minV, maxU, maxV);
            if (clipped != null)
                kept.add(clipped);
        }
        return new PanelDefinition(plane, minU, minV, maxU, maxV, kept);
    }

    /**
     * This panel as the whole rectangles it is left as once its holes are taken out,
     * each of them a panel in its own right with no holes of its own.
     *
     * This is what spares the rest of the code any knowledge of holes at all. The walk
     * below and every routine in {@code PanelGeometry} run over the pieces and union
     * what comes back, so a hole is a few more rectangles rather than a special case
     * in the cell walk, the collision shape, the outline and the model.
     *
     * The decomposition is a plain sweep: the cut edges divide the rectangle into
     * bands, the uncovered runs of each band are merged along u, and bands with
     * identical runs are merged along v. That last step is what keeps a wall with one
     * window at four pieces rather than at one per row of pixels, and it is exact —
     * two bands with the same runs are one rectangle by construction.
     */
    public List<PanelDefinition> pieces() {
        if (cuts.isEmpty())
            return List.of(this);

        int[] us = edges(minU, maxU, true);
        int[] vs = edges(minV, maxV, false);
        List<PanelDefinition> pieces = new ArrayList<>();
        List<int[]> pending = List.of();
        int from = 0;
        int to = 0;

        for (int row = 0; row + 1 < vs.length; row++) {
            List<int[]> runs = runs(us, midpoint(vs[row], vs[row + 1]));
            if (!runs.isEmpty() && sameRuns(runs, pending)) {
                to = vs[row + 1];
                continue;
            }
            flush(pieces, pending, from, to);
            pending = runs;
            from = vs[row];
            to = vs[row + 1];
        }
        flush(pieces, pending, from, to);
        return pieces;
    }

    /** The runs of one band that no cut covers, merged along u. */
    private List<int[]> runs(int[] us, double v) {
        List<int[]> runs = new ArrayList<>();
        for (int column = 0; column + 1 < us.length; column++) {
            if (covered(midpoint(us[column], us[column + 1]), v))
                continue;
            int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[1] == us[column])
                last[1] = us[column + 1];
            else
                runs.add(new int[] { us[column], us[column + 1] });
        }
        return runs;
    }

    private void flush(List<PanelDefinition> pieces, List<int[]> runs, int from, int to) {
        if (to <= from)
            return;
        for (int[] run : runs)
            pieces.add(new PanelDefinition(plane, run[0], from, run[1], to));
    }

    private static boolean sameRuns(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size())
            return false;
        for (int i = 0; i < a.size(); i++)
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1])
                return false;
        return true;
    }

    private boolean covered(double u, double v) {
        for (PanelCut cut : cuts)
            if (cut.holds(u, v))
                return true;
        return false;
    }

    /** The rectangle's own edges along one axis, plus every cut edge strictly inside them. */
    private int[] edges(int low, int high, boolean alongU) {
        TreeSet<Integer> found = new TreeSet<>();
        found.add(low);
        found.add(high);
        for (PanelCut cut : cuts) {
            add(found, alongU ? cut.minU() : cut.minV(), low, high);
            add(found, alongU ? cut.maxU() : cut.maxV(), low, high);
        }
        int[] edges = new int[found.size()];
        int index = 0;
        for (int edge : found)
            edges[index++] = edge;
        return edges;
    }

    private static void add(TreeSet<Integer> found, int edge, int low, int high) {
        if (edge > low && edge < high)
            found.add(edge);
    }

    /** How many blocks this panel would become, without building the list. */
    public int cellCount() {
        int[] count = { 0 };
        forEachCell(pos -> count[0]++);
        return count[0];
    }

    public List<BlockPos> cells() {
        List<BlockPos> found = new ArrayList<>();
        forEachCell(found::add);
        return found;
    }

    /**
     * Every block this panel's plate passes through, each once, in no promised
     * order.
     *
     * The plate is followed rather than the rectangle, and it is followed along its
     * middle: a point half the thickness behind the surface, which is inside the
     * plate wherever the plate is. Both in-plane axes are walked the same way, by
     * cutting the run wherever any world axis crosses an integer and taking the
     * midpoint of each piece, so the cardinal case and the diagonal case are the
     * same code and neither needs to know which axes it varies along.
     */
    public void forEachCell(Consumer<BlockPos> cells) {
        // A panel with holes in it is the union of the whole rectangles it is left as,
        // and so are its cells. Deduplicating is what a union needs and not a
        // precaution: two pieces either side of a hole routinely share the cell the
        // hole sits in, since a hole need not be a whole block
        if (!cuts.isEmpty()) {
            Set<BlockPos> emitted = new LinkedHashSet<>();
            for (PanelDefinition piece : pieces())
                piece.forEachCell(cell -> {
                    if (emitted.add(cell))
                        cells.accept(cell);
                });
            return;
        }

        // A spun panel breaks the assumption the walk below rests on; see there
        if (plane.isSpun()) {
            forEachSpunCell(cells);
            return;
        }

        double scale = PanelPlane.UNITS_PER_BLOCK;
        double depth = -THICKNESS / scale / 2;
        double uLow = minU / scale;
        double uHigh = maxU / scale;
        double vLow = minV / scale;
        double vHigh = maxV / scale;

        // The v run is cut once and reused: the plate is flat, so where it crosses a
        // boundary along v does not depend on how far along u it has got
        List<Double> vCuts = crossings(vLow, vHigh, plane.at(0, 0, depth), plane.tangentV());
        Set<BlockPos> emitted = new LinkedHashSet<>();

        for (int row = 0; row + 1 < vCuts.size(); row++) {
            double v = midpoint(vCuts.get(row), vCuts.get(row + 1));
            List<Double> uCuts = crossings(uLow, uHigh, plane.at(0, v, depth), plane.tangentU());
            for (int column = 0; column + 1 < uCuts.size(); column++) {
                double from = uCuts.get(column);
                double to = uCuts.get(column + 1);
                // A sliver at a near-aligned corner is plate, but not enough of it to
                // be worth a block; see MIN_SLICE. The whole run is never skipped,
                // however short the panel itself is
                if (to - from < MIN_SLICE && uCuts.size() > 2)
                    continue;
                double u = midpoint(from, to);
                BlockPos cell = BlockPos.containing(plane.at(u, v, depth));
                // A plate thinner than a block can enter the same cell twice along one
                // run when it grazes a corner, and a panel may not place two blocks in
                // one cell
                if (emitted.add(cell))
                    cells.accept(cell);
            }
        }
    }

    /**
     * Every block a flat panel lying at forty-five degrees passes through.
     *
     * The walk above cuts the v run once and reuses it for every column, which is
     * sound exactly when where the plate crosses a boundary along v does not depend on
     * how far along u it has got. A spun panel is the one case where it does: both
     * in-plane axes run diagonally to the block grid, so the boundaries cut the
     * rectangle into diamonds rather than into rows, and a walk that samples a grid of
     * rows and columns steps over some of them and lands twice in others.
     *
     * So the world is asked instead of the rectangle. The plate is flat and lies level,
     * so its blocks are a footprint repeated over the few layers it is thick, and a
     * block is in that footprint exactly when its square meets the rectangle. Two
     * convex shapes in a plane miss each other only if some axis separates them, and
     * for a rectangle and a square the only axes that can are their own four, which
     * makes the test four interval comparisons and exact.
     */
    private void forEachSpunCell(Consumer<BlockPos> cells) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        double uLow = minU / scale;
        double uHigh = maxU / scale;
        double vLow = minV / scale;
        double vHigh = maxV / scale;

        double faceAt = plane.at(0, 0, 0).y;
        double backAt = plane.at(0, 0, -THICKNESS / scale).y;
        int lowY = Mth.floor(Math.min(faceAt, backAt) + BOUNDARY);
        int highY = Mth.floor(Math.max(faceAt, backAt) - BOUNDARY);

        // The corners of the plate, which bound the blocks worth asking about
        double lowX = Double.MAX_VALUE, highX = -Double.MAX_VALUE;
        double lowZ = Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            Vec3 point = plane.at((corner & 1) == 0 ? uLow : uHigh, (corner & 2) == 0 ? vLow : vHigh, 0);
            lowX = Math.min(lowX, point.x);
            highX = Math.max(highX, point.x);
            lowZ = Math.min(lowZ, point.z);
            highZ = Math.max(highZ, point.z);
        }

        for (int x = Mth.floor(lowX); x <= Mth.floor(highX); x++)
            for (int z = Mth.floor(lowZ); z <= Mth.floor(highZ); z++) {
                if (!meetsSquare(x, z, uLow, uHigh, vLow, vHigh))
                    continue;
                for (int y = lowY; y <= highY; y++)
                    cells.accept(new BlockPos(x, y, z));
            }
    }

    /**
     * Whether the panel rectangle meets one block square of the world, seen from
     * above.
     *
     * The four axes that can separate them are u and v, on which the rectangle is its
     * own bounds, and x and z, on which the square is. Each side is projected onto the
     * other two and the intervals compared; touching along an edge counts as missing,
     * which is what stops a panel ending exactly on a boundary from claiming the block
     * beyond it.
     */
    private boolean meetsSquare(int x, int z, double uLow, double uHigh, double vLow, double vHigh) {
        double squareLowU = Double.MAX_VALUE, squareHighU = -Double.MAX_VALUE;
        double squareLowV = Double.MAX_VALUE, squareHighV = -Double.MAX_VALUE;
        double panelLowX = Double.MAX_VALUE, panelHighX = -Double.MAX_VALUE;
        double panelLowZ = Double.MAX_VALUE, panelHighZ = -Double.MAX_VALUE;

        for (int corner = 0; corner < 4; corner++) {
            Vec3 stone = new Vec3(x + (corner & 1), 0, z + ((corner >> 1) & 1));
            squareLowU = Math.min(squareLowU, plane.u(stone));
            squareHighU = Math.max(squareHighU, plane.u(stone));
            squareLowV = Math.min(squareLowV, plane.v(stone));
            squareHighV = Math.max(squareHighV, plane.v(stone));

            Vec3 point = plane.at((corner & 1) == 0 ? uLow : uHigh, (corner & 2) == 0 ? vLow : vHigh, 0);
            panelLowX = Math.min(panelLowX, point.x);
            panelHighX = Math.max(panelHighX, point.x);
            panelLowZ = Math.min(panelLowZ, point.z);
            panelHighZ = Math.max(panelHighZ, point.z);
        }

        return meets(uLow, uHigh, squareLowU, squareHighU) && meets(vLow, vHigh, squareLowV, squareHighV)
                && meets(panelLowX, panelHighX, x, x + 1) && meets(panelLowZ, panelHighZ, z, z + 1);
    }

    private static boolean meets(double lowA, double highA, double lowB, double highB) {
        return Math.min(highA, highB) - Math.max(lowA, lowB) > BOUNDARY;
    }

    /**
     * Whether this panel's plate fills a cell, leaving nothing of it for another panel
     * on the same surface to show.
     *
     * Asked by a cell before it takes on a second plate, and answered from the shadow
     * the whole block casts on the plane rather than from the slice the plane actually
     * cuts through it. That shadow is the wider of the two on a turned plane, so the
     * answer errs towards no — and no is the safe way to err here, because it only ever
     * means a plate is kept that could have been dropped.
     */
    public boolean coversCell(BlockPos cell) {
        double scale = PanelPlane.UNITS_PER_BLOCK;
        AABB box = new AABB(cell);
        double[] u = plane.uRange(box);
        double[] v = plane.vRange(box);
        if (u[0] < minU / scale - BOUNDARY || u[1] > maxU / scale + BOUNDARY
                || v[0] < minV / scale - BOUNDARY || v[1] > maxV / scale + BOUNDARY)
            return false;
        // A hole anywhere in the cell leaves something of it for another plate to
        // show, and the shadow is asked rather than the slice for the reason above:
        // this errs towards no, and no only ever keeps a plate that could have gone
        for (PanelCut cut : cuts)
            if (Math.min(u[1], cut.maxU() / scale) - Math.max(u[0], cut.minU() / scale) > BOUNDARY
                    && Math.min(v[1], cut.maxV() / scale) - Math.max(v[0], cut.minV() / scale) > BOUNDARY)
                return false;
        return true;
    }

    /** Whether a block is one of this panel's own cells. */
    public boolean covers(BlockPos pos) {
        boolean[] found = { false };
        forEachCell(cell -> found[0] |= cell.equals(pos));
        return found[0];
    }

    /**
     * The parameter values that cut {@code [from, to]} wherever the line
     * {@code base + direction * t} crosses a world block boundary, with the two ends
     * included and duplicates removed.
     *
     * A crossing is solved for directly rather than stepped towards, so a long run
     * cannot drift, and each cut is the one value both of the pieces either side of
     * it agree on.
     */
    private static List<Double> crossings(double from, double to, Vec3 base, Vec3 direction) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(from);
        cuts.add(to);
        for (int axis = 0; axis < 3; axis++) {
            double step = component(direction, axis);
            if (Math.abs(step) < NUDGE)
                continue;
            double origin = component(base, axis);
            double atFrom = origin + step * from;
            double atTo = origin + step * to;
            int low = (int) Math.ceil(Math.min(atFrom, atTo));
            int high = (int) Math.floor(Math.max(atFrom, atTo));
            for (int boundary = low; boundary <= high; boundary++) {
                double t = (boundary - origin) / step;
                if (t > from + NUDGE && t < to - NUDGE)
                    cuts.add(t);
            }
        }
        Collections.sort(cuts);
        return cuts;
    }

    private static double midpoint(double low, double high) {
        return low + (high - low) / 2;
    }

    private static double component(Vec3 vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        plane.save(tag);
        tag.putInt("MinU", minU);
        tag.putInt("MinV", minV);
        tag.putInt("MaxU", maxU);
        tag.putInt("MaxV", maxV);
        if (cuts.isEmpty())
            return tag;
        ListTag holes = new ListTag();
        for (PanelCut cut : cuts)
            holes.add(cut.save());
        tag.put(CUTS, holes);
        return tag;
    }

    /** Null when the tag holds no panel, which is how a truncated save is survived. */
    public static PanelDefinition load(CompoundTag tag) {
        PanelPlane plane = PanelPlane.load(tag);
        if (plane == null || !tag.contains("MinU"))
            return null;
        List<PanelCut> cuts = new ArrayList<>();
        for (Tag element : tag.getList(CUTS, Tag.TAG_COMPOUND)) {
            PanelCut cut = PanelCut.load((CompoundTag) element);
            if (cut != null && cuts.size() < MAX_CUTS)
                cuts.add(cut);
        }
        return new PanelDefinition(plane, tag.getInt("MinU"), tag.getInt("MinV"), tag.getInt("MaxU"),
                tag.getInt("MaxV"), cuts);
    }

    public void write(FriendlyByteBuf buffer) {
        plane.write(buffer);
        buffer.writeVarInt(minU);
        buffer.writeVarInt(minV);
        buffer.writeVarInt(maxU);
        buffer.writeVarInt(maxV);
        buffer.writeVarInt(cuts.size());
        for (PanelCut cut : cuts)
            cut.write(buffer);
    }

    public static PanelDefinition read(FriendlyByteBuf buffer) {
        PanelPlane plane = PanelPlane.read(buffer);
        int minU = buffer.readVarInt();
        int minV = buffer.readVarInt();
        int maxU = buffer.readVarInt();
        int maxV = buffer.readVarInt();
        int count = Math.min(buffer.readVarInt(), MAX_CUTS);
        List<PanelCut> cuts = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            cuts.add(PanelCut.read(buffer));
        return new PanelDefinition(plane, minU, minV, maxU, maxV, cuts);
    }
}
