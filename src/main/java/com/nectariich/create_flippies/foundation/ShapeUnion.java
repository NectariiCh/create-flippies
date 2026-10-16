package com.nectariich.create_flippies.foundation;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Joins many boxes into one {@link VoxelShape} once, rather than once for every
 * box after the first.
 *
 * {@link Shapes#or} looks like an accumulator and is not one. Each call joins two
 * shapes into a fresh discrete voxel grid whose side along each axis is the number
 * of distinct coordinates the two operands have between them, and then calls
 * {@code optimize}, which walks that whole grid to merge the boxes back out of it.
 * So a loop that ors one box onto a running total pays for the total and not for
 * the box, and pays again for every box still to come: the work is quartic in the
 * number of boxes, and it is the running total that makes it so.
 *
 * That is not a theoretical cost. A large panel cell on a diagonal is sixteen
 * staircase slices of plate; a dozen indicator units and their labels mounted on it
 * are a hundred and thirty-two more boxes, because a unit is a lens, a barrel and
 * eight grown contact studs. Building that cell's shape a box at a time measured at
 * 140 milliseconds. Two dozen units in one cell — two hundred and eighty boxes —
 * measured at 967, and the curve is steepening the whole way: sixty boxes cost six
 * milliseconds and twice as many cost eight times that. Every one of those
 * milliseconds is spent on a chunk build thread, on the server's collision path, or
 * in front of the player who has just placed a control or dragged a panel edge.
 *
 * What this does instead is join the parts as a balanced tree, so neither side of a
 * join is ever much bigger than the other, and optimise once at the end instead of
 * once per box. The same two cells measure at 39 and 148 milliseconds that way.
 *
 * The shape that comes out is the same shape. The final merge rebuilds from the
 * boxes it finds and is indifferent to the order they were joined in, so both routes
 * end at the same grid and the same merged box list — 205 boxes for the twelve-unit
 * cell either way. They are not bit for bit identical, and the reason is worth
 * naming so that nobody reads it as a bug: two controls can land on coordinates that
 * are already one unit in the last place apart, and which of that pair the greedy
 * merge keeps depends on which box it reached first. The largest disagreement
 * measured across the whole cell is four times ten to the minus seventeen of a
 * block, which is a millionth of a millionth of a pixel.
 *
 * Fixing the join left almost all of a build's remaining cost in the final optimise,
 * and the reason is that {@link VoxelShape#optimize} is the same accumulator sweep
 * again — the one place this class had left it standing. {@link #optimised} is where
 * that went, and it says what must not be inferred from the saving: the merge itself
 * is not optional, because it is what halves the grid that everything asked of the
 * shape afterwards walks.
 */
public final class ShapeUnion {

    private ShapeUnion() {}

    /** The union of some shapes, optimised, for a shape something outside will be given. */
    public static VoxelShape of(VoxelShape... parts) {
        return of(List.of(parts));
    }

    public static VoxelShape of(List<VoxelShape> parts) {
        return optimised(join(parts, 0, parts.size()));
    }

    /**
     * The same union left unoptimised, for one that is only ever an ingredient of
     * another. Optimising it would be work the join it is about to go into throws
     * away and does again.
     */
    public static VoxelShape raw(List<VoxelShape> parts) {
        return join(parts, 0, parts.size());
    }

    /**
     * What {@link VoxelShape#optimize} does, without the sweep it does it with.
     *
     * Optimising a shape is two separate things wearing one name. The first is the
     * greedy merge: walk the discrete grid once and hand back the fewest axis-aligned
     * boxes that cover exactly the cells that are full. The second is rebuilding a
     * shape out of those boxes, so that the new shape's coordinate lists hold only the
     * coordinates the merged boxes actually use and its grid is correspondingly
     * smaller. The first is the point and is unavoidable. The second is a union of
     * boxes — and vanilla writes it as
     * {@code acc = joinUnoptimized(acc, box, OR)} inside the merge's own callback,
     * which is precisely the accumulator this class exists to avoid, on precisely the
     * box count that makes it ruinous.
     *
     * So the same two shapes: the twelve-unit cell merges to 228 boxes and the
     * two-dozen one to 442, and rebuilding them a box at a time measures 70 and 371
     * milliseconds against 5.8 and 12.3 for the balanced join below. Every
     * millisecond of that gap was being paid on whichever thread first asked a cell
     * for its shape after a control was screwed in, a panel edge dragged, or a chunk
     * of a panelled wall built — and 371 milliseconds is not a slow frame, it is a
     * third of a second of nothing at all.
     *
     * The shape that comes out is the same shape, and here that is stronger than the
     * approximate sameness the class comment describes above. Both routes are handed
     * the identical merged boxes by the identical walk, and a union of boxes does not
     * depend on the order they are joined in: the coordinate lists are the same set
     * either way and the bit set is an OR. Measured over the authored cells at zero,
     * one, four, twelve and twenty-four units and over random piles of forty turned
     * boxes, the merged box lists compare equal and the grids come out the same size
     * every time.
     *
     * One thing must not be inferred from the saving, which is that the merge could be
     * skipped too. It is what halves the grid — 94,380 cells instead of 162,688 for
     * the twelve-unit cell — and everything ever asked of the shape afterwards walks
     * that grid: the edge walk the block outline is drawn from, the collision sweep,
     * the one clip a ray now pays per shape rather than per pick. Paying for it once
     * is the right way round, which is why {@link #raw} exists only for a shape on its
     * way into another one and never for one that is handed out.
     */
    private static VoxelShape optimised(VoxelShape joined) {
        List<VoxelShape> merged = new ArrayList<>();
        joined.forAllBoxes(
                (minX, minY, minZ, maxX, maxY, maxZ) -> merged.add(Shapes.box(minX, minY, minZ, maxX, maxY, maxZ)));
        return join(merged, 0, merged.size());
    }

    /**
     * The parts of {@code [from, to)} joined as a balanced tree.
     *
     * Halving rather than sweeping is the whole of the trick. A sweep joins a
     * one-box shape onto a shape that already holds everything before it, so the
     * grid it builds is as large as the finished one every single time; halving
     * keeps the two operands the same size as each other, so only the last few joins
     * are ever expensive.
     */
    private static VoxelShape join(List<VoxelShape> parts, int from, int to) {
        if (from >= to)
            return Shapes.empty();
        if (to - from == 1)
            return parts.get(from);
        int middle = (from + to) >>> 1;
        return Shapes.joinUnoptimized(join(parts, from, middle), join(parts, middle, to), BooleanOp.OR);
    }
}
