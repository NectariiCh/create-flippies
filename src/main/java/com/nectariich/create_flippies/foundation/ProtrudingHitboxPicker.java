package com.nectariich.create_flippies.foundation;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Gives {@link IProtrudingHitbox} blocks a hitbox outside their own block
 * space.
 *
 * {@code BlockGetter.clip} walks the ray cell by cell and returns the first
 * cell that yields a hit, testing only the block that lives in that cell. Both
 * halves of that are fatal to geometry authored outside the cell: looking at
 * the gauge's studs from behind the panel, the panel's own cell is reached
 * first and its shape stops the walk, and at close range the ray never reaches
 * the gauge's cell at all, so nothing tests the gauge's shape.
 *
 * So after vanilla is done, walk the same ray again — no further than whatever
 * vanilla hit — and clip the shapes of every protruding block near each cell on
 * the way. Only a hit strictly in front of vanilla's counts, which keeps studs
 * buried inside a solid neighbour unreachable, the way they look.
 *
 * "Near" is the whole three-by-three-by-three neighbourhood, not the six faces.
 * The six were enough only while a footprint was forbidden from leaving its cell
 * along more than one axis, and that rule was what stopped a control from being
 * mounted across a block boundary — a corner of a face, or a grid ruled over two
 * panel blocks, would put geometry in a cell diagonally adjacent to its own,
 * which a six-face scan never tests and which is therefore unpickable with no
 * error anywhere. A footprint may reach {@link ControlFootprint#REACH_BUDGET}
 * out of its cell along each axis, which is half a block, so its geometry can
 * never land further than one cell away on any axis and this neighbourhood is
 * exactly wide enough.
 *
 * Twenty-seven cells per step of the walk would be a great deal of repeated work,
 * because consecutive steps overlap in twenty-six of them. So the walk only
 * gathers cell coordinates, and the shapes are clipped afterwards, once each:
 * that costs about as many block lookups as the six-face scan did with its
 * duplicates, for four times the coverage.
 */
public final class ProtrudingHitboxPicker {

    /** Walk a little past vanilla's hit, so the cell it landed in is visited too. */
    private static final double OVERSHOOT = 1 / 32d;

    /** How far, in cells, a protruding block's geometry can reach; see the class comment. */
    private static final int SPREAD = 1;

    private ProtrudingHitboxPicker() {}

    /**
     * Given what {@code Entity.pick} came up with for a ray, returns the closer
     * of that hit and any protruding hitbox in front of it — {@code vanilla}
     * itself if there is none.
     */
    public static HitResult extend(Entity entity, double reach, float partialTicks, HitResult vanilla) {
        Level level = entity.level();
        if (level == null)
            return vanilla;

        Vec3 from = entity.getEyePosition(partialTicks);
        Vec3 view = entity.getViewVector(partialTicks);
        boolean hit = vanilla != null && vanilla.getType() != HitResult.Type.MISS;
        Vec3 to = hit ? vanilla.getLocation().add(view.scale(OVERSHOOT)) : from.add(view.scale(reach));
        double limit = hit ? from.distanceToSqr(vanilla.getLocation()) : Double.MAX_VALUE;

        BlockHitResult closer = nearestProtruding(level, entity, from, to, limit);
        return closer != null ? closer : vanilla;
    }

    /** The protruding hitbox nearest to {@code from}, if any is closer than {@code limit}. */
    private static BlockHitResult nearestProtruding(Level level, Entity entity, Vec3 from, Vec3 to, double limit) {
        CollisionContext collision = CollisionContext.of(entity);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Nearest nearest = new Nearest(limit);

        for (long packed : cellsNear(from, to)) {
            cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            BlockState state = level.getBlockState(cursor);
            if (!(state.getBlock() instanceof IProtrudingHitbox))
                continue;

            VoxelShape shape = state.getShape(level, cursor, collision);
            BlockHitResult candidate = shape.clip(from, to, cursor);
            if (candidate == null)
                continue;

            double distance = from.distanceToSqr(candidate.getLocation());
            if (distance >= nearest.distance)
                continue;
            nearest.distance = distance;
            // clip() hands back the mutable cursor; the loop keeps moving it
            nearest.hit = candidate.withPosition(cursor.immutable());
        }

        return nearest.hit;
    }

    /**
     * Every cell within {@link #SPREAD} of the ray, each named once. The walk
     * itself never short-circuits: any cell along it may have a protruding
     * neighbour, and stopping at the first would find whichever happened to be
     * gathered first rather than the nearest.
     */
    private static LongSet cellsNear(Vec3 from, Vec3 to) {
        LongSet cells = new LongOpenHashSet();
        BlockGetter.traverseBlocks(from, to, cells, (gathered, pos) -> {
            for (int dx = -SPREAD; dx <= SPREAD; dx++)
                for (int dy = -SPREAD; dy <= SPREAD; dy++)
                    for (int dz = -SPREAD; dz <= SPREAD; dz++)
                        gathered.add(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz));
            return null;
        }, gathered -> null);
        return cells;
    }

    private static final class Nearest {

        private BlockHitResult hit;
        private double distance;

        private Nearest(double limit) {
            distance = limit;
        }
    }
}
