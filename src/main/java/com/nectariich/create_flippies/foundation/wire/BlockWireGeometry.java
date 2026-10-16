package com.nectariich.create_flippies.foundation.wire;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntity.Point;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;

/**
 * The arithmetic behind landing Power Grid's surface-hugging block wire on a
 * quarter-pixel terminal, shared by the handful of mixins that have to agree
 * about it.
 *
 * It lives here rather than beside those mixins, and must stay out of
 * {@code com.nectariich.create_flippies.mixin.*}, because Mixin owns that package
 * once the config declares it: a class inside it cannot be referenced directly,
 * and the attempt throws {@code IllegalClassLoadError}. Nothing catches that at
 * compile time. It surfaces the first time a block wire is deserialised, as an
 * entity that fails to load — so a wire the player had already placed simply
 * vanishes.
 *
 * A block wire is not a polyline. It is one anchor — the entity's own position —
 * followed by a chain of axis-aligned offsets, each stored as an integer count of
 * grid units in {@code BlockWireEntity$Point.gridLength} and turned back into a
 * distance by {@code Point.length()}. Only the anchor and the sum of the chain
 * decide where the two ends sit; every corner in between is free. Power Grid
 * anchors on {@code BlockTrace.alignPosition}, which per axis computes
 * {@code round(v * 16) / 16 - 1/32} and so can only ever produce odd multiples of
 * half a pixel, and its unit is a whole pixel arrived at by a truncating cast. A
 * terminal whose origin is a quarter pixel off that lattice — which is what this
 * mod's protruding studs are, {@code (8.75 + 9.75) / 2 = 9.25px} — is
 * unreachable at both ends at once: the near contact keeps a half-pixel gap and
 * the whole route runs half a pixel off the panel with it.
 *
 * So the anchor moves to the terminal origin itself, snapped to a 1/64 lattice,
 * and the unit becomes 1/64 of a block. Quarter-pixel origins are exactly
 * representable there, which is the resolution this mod authors terminals at, and
 * a lattice shared by both ends is what makes the far end reachable at all: the
 * chain can only sum to a multiple of its own unit, so either both ends sit on
 * that lattice or neither of them is exact.
 *
 * What deliberately did NOT change is the A* routing grid.
 * {@code BlockTrace$TraceState.GRID_SIZE} stays 16 and the values handed to
 * {@code BlockTrace.findPath} stay exactly what Power Grid computed, so cell
 * selection, support tests, ray casts and the three cell-denominated budgets — an
 * unsupported run of 16, an origin distance of 160, 150 visited states — all
 * behave as before. Re-gridding the search would mean overwriting some fifteen
 * methods that inline the grid constants as compile-time literals, re-deriving
 * those budgets, and it would still leave a shortfall from a
 * {@code distManhattan <= 1} terminator, all to move corners that are invisible
 * at one pixel. Instead the routed chain is corrected once, at placement, by
 * absorbing the leftover sub-pixel residual into the last segment on each axis.
 * Intermediate corners shift by well under a pixel as a result, which is why
 * nothing about the route needs re-checking.
 *
 * Persistence stays readable by an unmodified Power Grid. The legacy integer
 * keeps its key and its 1/16 meaning, rounded; the exact 1/64 count goes in a
 * sibling key of ours, and its absence is what marks a value as legacy. Power
 * Grid's own {@code Version} field on the wire entity is deliberately left where
 * it is — it describes Power Grid's schema, and moving it would send its migration
 * down a path written for a different change.
 */
public final class BlockWireGeometry {

    /** The unit every wire segment length is now counted in: a quarter of a pixel. */
    public static final int UNITS_PER_BLOCK = 64;

    /** Power Grid's own unit, still the meaning of everything under a legacy NBT key. */
    private static final int LEGACY_UNITS_PER_BLOCK = 16;

    private static final int LEGACY_SCALE = UNITS_PER_BLOCK / LEGACY_UNITS_PER_BLOCK;

    /**
     * Prefixed onto the legacy key to name our sibling. Derived from the key being
     * intercepted rather than written out in full, so a reader and a writer that
     * intercept the same field cannot disagree about where the fine value lives.
     */
    private static final String FINE_KEY_PREFIX = "create_flippies:Fine";

    /**
     * The largest end correction worth trusting, in units. A route stops within one
     * cell of the target cell's centre, so an honest residual is at most a pixel
     * and a half plus the two-thirds of a pixel the anchor moved — call it nine
     * units. Anything approaching a quarter of a block means an endpoint resolved
     * to {@code BlockPos.getCenter()} because its block was not loaded, and
     * correcting towards that would wreck the wire rather than seat it.
     */
    private static final int MAX_CORRECTION = 16;

    private BlockWireGeometry() {}

    /** A distance in blocks as a count of wire units, to the nearest unit. */
    public static int toUnits(double blocks) {
        return (int) Math.round(blocks * UNITS_PER_BLOCK);
    }

    /** A world position moved onto the lattice a wire chain can express. */
    public static Vec3 snap(Vec3 position) {
        return new Vec3(snapAxis(position.x), snapAxis(position.y), snapAxis(position.z));
    }

    private static double snapAxis(double blocks) {
        return toUnits(blocks) / (double) UNITS_PER_BLOCK;
    }

    /**
     * Writes a unit count so that both this mod and an unmodified Power Grid can
     * read it: the legacy key carries the same measurement rounded to Power Grid's
     * coarser unit, never rounding a real segment away to nothing.
     */
    public static void writeUnits(CompoundTag tag, String legacyKey, int units) {
        tag.putInt(legacyKey, toLegacyUnits(units));
        tag.putInt(FINE_KEY_PREFIX + legacyKey, units);
    }

    /**
     * Reads a unit count written by either version. A wire saved before this mod
     * has no sibling key and its integer means sixteenths of a block; taking that
     * at face value would draw every block wire in an existing world at a quarter
     * of its length.
     */
    public static int readUnits(CompoundTag tag, String legacyKey) {
        String fineKey = FINE_KEY_PREFIX + legacyKey;
        if (tag.contains(fineKey, Tag.TAG_INT))
            return tag.getInt(fineKey);
        return tag.getInt(legacyKey) * LEGACY_SCALE;
    }

    private static int toLegacyUnits(int units) {
        if (units <= 0)
            return units;
        return Math.max(1, Math.round(units / (float) LEGACY_SCALE));
    }

    /**
     * Lengthens the routed chain so that it ends on the far endpoint rather than on
     * the routing lattice near it. Called once per placement, on the server, while
     * both endpoints' blocks are certainly loaded — never on load, where a missing
     * chunk answers with a block centre and this would then bend the wire towards
     * it.
     */
    public static void snapFarEnd(BlockWireEntity wire) {
        IWireEndpoint far = wire.getEndpoint2();
        if (far == null)
            return;

        List<Point> segments = wire.segments;
        Vec3 end = wire.position();
        for (Point segment : segments)
            end = end.add(segment.vector());

        Vec3 target = snap(far.getExactPosition(wire.level()));
        int x = toUnits(target.x - end.x);
        int y = toUnits(target.y - end.y);
        int z = toUnits(target.z - end.z);
        if (x == 0 && y == 0 && z == 0)
            return;
        if (Math.abs(x) > MAX_CORRECTION || Math.abs(y) > MAX_CORRECTION || Math.abs(z) > MAX_CORRECTION)
            return;

        absorb(segments, Direction.Axis.X, x);
        absorb(segments, Direction.Axis.Y, y);
        absorb(segments, Direction.Axis.Z, z);
        wire.bakeBoundingBoxes();
    }

    /**
     * Adds a residual to the last segment that already runs along its axis, which
     * shifts every corner after that segment by a sub-pixel amount and leaves the
     * shape of the route alone. A run shorter than the residual would be cancelled
     * outright, so an earlier run takes it instead; when the axis is unused — the
     * two terminals sat level on it, to within the old unit — the correction
     * becomes a short segment of its own, placed one before the end so that the
     * wire still finishes on the direction it approached the terminal from. That
     * direction is what an extension of this wire is later routed away from.
     */
    private static void absorb(List<Point> segments, Direction.Axis axis, int units) {
        if (units == 0)
            return;
        for (int i = segments.size() - 1; i >= 0; i--) {
            Point segment = segments.get(i);
            if (segment.direction.getAxis() != axis)
                continue;
            int along = signedLength(segment) + units;
            if (along == 0)
                continue;
            segments.set(i, new Point(along(axis, along > 0), Math.abs(along)));
            return;
        }
        Point stub = new Point(along(axis, units > 0), Math.abs(units));
        segments.add(Math.max(0, segments.size() - 1), stub);
    }

    /** A segment's length counted towards the positive end of its own axis. */
    private static int signedLength(Point segment) {
        return segment.direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? segment.gridLength
                : -segment.gridLength;
    }

    private static Direction along(Direction.Axis axis, boolean positive) {
        return switch (axis) {
            case X -> positive ? Direction.EAST : Direction.WEST;
            case Y -> positive ? Direction.UP : Direction.DOWN;
            case Z -> positive ? Direction.SOUTH : Direction.NORTH;
        };
    }
}
