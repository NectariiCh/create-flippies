package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * The bend points of a hanging wire, as plain data: where they are, what path they
 * make with the wire's two ends, how long that path is, how they are saved and sent,
 * and which edits a server accepts.
 *
 * A bent wire is still one wire. The points only change its shape: the path runs
 * from the first end through every point in order to the second end, and each leg
 * of it hangs as its own catenary (see {@link BendGeometry}). Electrically it stays
 * the single connection Power Grid made, with its resistance scaled by how much
 * longer the bent path is than the straight one ({@link #resistanceScale}).
 *
 * Points are kept in world coordinates. A save also carries Power Grid's own
 * {@code LastKnownPos}, which is what lets a wire moved with its structure shift its
 * points by the same offset its endpoints get.
 */
public final class WireBends {

    /** The most bend points one wire may carry. */
    public static final int MAX_POINTS = 16;

    /**
     * How much longer than the wire type's maximum span a bent path may be. A wire
     * at its full span can still be bent, and no point can be dragged off into the
     * distance: the path through it would be too long.
     */
    public static final double MAX_PATH_FACTOR = 1.5;

    /** Extra room past the player's reach a point may be placed at, for a drag a tick behind. */
    public static final double REACH_MARGIN = 1.5;

    /** Where the points live in a wire's save data, beside Power Grid's own keys. */
    public static final String NBT_KEY = "create_flippies:Bends";

    /** An edit a player can make to a wire's points. */
    public enum Op {
        INSERT, MOVE, REMOVE
    }

    /** What the server makes of an edit; anything but {@link #OK} is refused. */
    public enum Verdict {
        OK("ok"),
        TOO_MANY("too_many"),
        BAD_INDEX("bad_index"),
        NOT_FINITE("bad_index"),
        OUT_OF_REACH("out_of_reach"),
        TOO_LONG("too_long");

        private final String key;

        Verdict(String key) {
            this.key = key;
        }

        public String langKey() {
            return "gui.create_flippies.wire_bend." + key;
        }
    }

    private WireBends() {}

    /** The whole path: the first end, every point in order, the second end. */
    public static List<Vec3> path(Vec3 start, List<Vec3> points, Vec3 end) {
        List<Vec3> path = new ArrayList<>(points.size() + 2);
        path.add(start);
        path.addAll(points);
        path.add(end);
        return path;
    }

    /** The length of a path measured end to end along its straight legs, blocks. */
    public static double length(List<Vec3> path) {
        double total = 0;
        for (int i = 1; i < path.size(); i++)
            total += path.get(i - 1).distanceTo(path.get(i));
        return total;
    }

    /**
     * How much the bends multiply the wire's resistance: the bent path's length over
     * the straight distance Power Grid priced the wire by. Exactly 1 with no points,
     * so an unbent wire is untouched.
     */
    public static double resistanceScale(Vec3 start, List<Vec3> points, Vec3 end) {
        if (points.isEmpty() || start == null || end == null)
            return 1;
        double straight = start.distanceTo(end);
        if (straight < 1e-6)
            return 1;
        return Math.max(1, length(path(start, points, end)) / straight);
    }

    /** The longest a bent path may be, given the straight span and the wire type's maximum. */
    public static double maxPathLength(double straight, double maxSpan) {
        return Math.max(straight, maxSpan) * MAX_PATH_FACTOR;
    }

    /** The points with one edit made; the input is left alone. */
    public static List<Vec3> apply(Op op, List<Vec3> points, int index, Vec3 position) {
        List<Vec3> next = new ArrayList<>(points);
        switch (op) {
            case INSERT -> next.add(index, position);
            case MOVE -> next.set(index, position);
            case REMOVE -> next.remove(index);
        }
        return next;
    }

    /**
     * Whether a server should make an edit. {@code index} is where an insert goes
     * (0 to the count) or which point is moved or removed; {@code eye} and
     * {@code reach} are the editing player's.
     */
    public static Verdict check(Op op, List<Vec3> points, int index, Vec3 position, Vec3 start, Vec3 end,
            double maxSpan, Vec3 eye, double reach) {
        int limit = op == Op.INSERT ? points.size() : points.size() - 1;
        if (index < 0 || index > limit)
            return Verdict.BAD_INDEX;
        if (op == Op.INSERT && points.size() >= MAX_POINTS)
            return Verdict.TOO_MANY;
        if (op == Op.REMOVE)
            return Verdict.OK;
        if (position == null || !finite(position))
            return Verdict.NOT_FINITE;
        double range = reach + REACH_MARGIN;
        if (eye.distanceToSqr(position) > range * range)
            return Verdict.OUT_OF_REACH;
        List<Vec3> next = apply(op, points, index, position);
        double straight = start.distanceTo(end);
        if (length(path(start, next, end)) > maxPathLength(straight, maxSpan))
            return Verdict.TOO_LONG;
        return Verdict.OK;
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** The points moved by an offset, as when a wire is moved with its structure. */
    public static List<Vec3> offset(List<Vec3> points, Vec3 by) {
        if (points.isEmpty() || by.equals(Vec3.ZERO))
            return points;
        List<Vec3> moved = new ArrayList<>(points.size());
        for (Vec3 p : points)
            moved.add(p.add(by));
        return Collections.unmodifiableList(moved);
    }

    /** The points in reverse, for a wire whose two ends have swapped over. */
    public static List<Vec3> reversed(List<Vec3> points) {
        List<Vec3> flipped = new ArrayList<>(points);
        Collections.reverse(flipped);
        return Collections.unmodifiableList(flipped);
    }

    // Saving

    /** Writes the points under {@link #NBT_KEY}; an unbent wire writes nothing at all. */
    public static void write(CompoundTag tag, List<Vec3> points) {
        if (points.isEmpty())
            return;
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            list.add(DoubleTag.valueOf(p.x));
            list.add(DoubleTag.valueOf(p.y));
            list.add(DoubleTag.valueOf(p.z));
        }
        tag.put(NBT_KEY, list);
    }

    /**
     * Reads the points back. A wire saved without any, or by a game without this
     * mod, reads as unbent; a damaged list keeps the points it can make whole, up
     * to the cap.
     */
    public static List<Vec3> read(CompoundTag tag) {
        if (!tag.contains(NBT_KEY, Tag.TAG_LIST))
            return List.of();
        ListTag list = tag.getList(NBT_KEY, Tag.TAG_DOUBLE);
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i + 2 < list.size() && points.size() < MAX_POINTS; i += 3) {
            Vec3 p = new Vec3(list.getDouble(i), list.getDouble(i + 1), list.getDouble(i + 2));
            if (finite(p))
                points.add(p);
        }
        return Collections.unmodifiableList(points);
    }

    // Sending

    public static void write(FriendlyByteBuf buffer, List<Vec3> points) {
        buffer.writeVarInt(points.size());
        for (Vec3 p : points) {
            buffer.writeDouble(p.x);
            buffer.writeDouble(p.y);
            buffer.writeDouble(p.z);
        }
    }

    /** Reads a list sent by {@link #write(FriendlyByteBuf, List)}, never more than the cap. */
    public static List<Vec3> read(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_POINTS)
            throw new IllegalArgumentException("Bad bend point count " + count);
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            points.add(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        return Collections.unmodifiableList(points);
    }
}
