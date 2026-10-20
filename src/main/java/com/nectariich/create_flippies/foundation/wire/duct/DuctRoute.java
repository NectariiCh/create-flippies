package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * A hanging wire's way through ducts, as plain data.
 *
 * A wire routed through ducts is still one wire: one entity, one connection, one
 * stack of items. Its shape is a bent wire's (see {@link WireBends}): a path from
 * its first terminal through a list of points to its second. What a duct adds is a
 * mark on some legs of that path ({@code ductLegs}, bit {@code i} for the leg from
 * path point {@code i} to {@code i + 1}): those legs lie in a duct, so they are
 * straight, drawn as strokes on its lid and cannot shock anyone; the rest hang.
 *
 * A wire's route is built from its two ends and the passes it makes through ducts
 * ({@link #build}). It is priced and resisted by the full length of that path, so a
 * wire run through ducts costs and conducts like one that long. Each hanging leg
 * must still be within the wire type's span.
 */
public final class DuctRoute {

    /** The most path points (ends excluded) a routed wire may have; legs fit the 64-bit mark. */
    public static final int MAX_POINTS = 60;

    /** The most passes through ducts one wire may make. */
    public static final int MAX_PASSES = 8;

    /** The longest a routed wire may be, end to end along its path, blocks. */
    public static final double MAX_LENGTH = 192;

    /** Where a routed wire's path lives in its save data, apart from the plain bend points. */
    public static final String NBT_POINTS = "create_flippies:DuctPath";
    public static final String NBT_LEGS = "create_flippies:DuctLegs";

    /** What the server makes of a finished route; anything but {@link #OK} is refused. */
    public enum Verdict {
        OK("ok"),
        LEG_TOO_LONG("leg_too_long"),
        TOO_LONG("route_too_long"),
        TOO_MANY("too_many_passes");

        private final String key;

        Verdict(String key) {
            this.key = key;
        }

        public String langKey() {
            return "gui.create_flippies.wire_duct." + key;
        }
    }

    /** A routed wire's points (ends excluded) and which legs of its path lie in ducts. */
    public record Built(List<Vec3> points, long ductLegs) {

        public Built {
            points = List.copyOf(points);
        }

        public List<Vec3> path(Vec3 start, Vec3 end) {
            return WireBends.path(start, points, end);
        }
    }

    private DuctRoute() {}

    public static boolean isDucted(long ductLegs, int leg) {
        return leg >= 0 && leg < 64 && (ductLegs & (1L << leg)) != 0;
    }

    /**
     * The path of a wire from {@code start} to {@code end} through every pass in
     * order: into each duct across its side nearest where the wire comes from,
     * along its lane, and out across the side nearest where it goes next. With no
     * passes this is a plain unbent wire.
     */
    public static Built build(Vec3 start, List<DuctPass> passes, Vec3 end, double wireThickness) {
        List<List<Vec3>> lanes = new ArrayList<>(passes.size());
        for (DuctPass pass : passes)
            lanes.add(pass.lanePoints(wireThickness));
        List<Vec3> path = new ArrayList<>();
        path.add(start);
        long mask = 0;
        for (int i = 0; i < passes.size(); i++) {
            DuctPass pass = passes.get(i);
            List<Vec3> lane = lanes.get(i);
            Vec3 previous = path.get(path.size() - 1);
            Vec3 next = i + 1 < passes.size() ? lanes.get(i + 1).get(0) : end;
            // The leg from the side edge in, every leg along the lane, and the leg out to the other edge
            path.add(pass.edge(true, previous, wireThickness));
            for (Vec3 p : lane) {
                mask |= 1L << (path.size() - 1);
                path.add(p);
            }
            mask |= 1L << (path.size() - 1);
            path.add(pass.edge(false, next, wireThickness));
        }
        path.add(end);
        return new Built(path.subList(1, path.size() - 1), mask);
    }

    /** Whether a built route may become a wire whose type spans at most {@code maxSpan} in one hanging leg. */
    public static Verdict check(Built built, Vec3 start, Vec3 end, int passes, double maxSpan) {
        if (passes > MAX_PASSES || built.points().size() > MAX_POINTS)
            return Verdict.TOO_MANY;
        List<Vec3> path = built.path(start, end);
        if (WireBends.length(path) > MAX_LENGTH)
            return Verdict.TOO_LONG;
        for (int leg = 0; leg + 1 < path.size(); leg++)
            if (!isDucted(built.ductLegs(), leg) && path.get(leg).distanceTo(path.get(leg + 1)) > maxSpan + 1e-6)
                return Verdict.LEG_TOO_LONG;
        return Verdict.OK;
    }

    /** Wire items a route costs, as Power Grid prices a straight wire of the same length. */
    public static int itemCount(double pathLength, double itemsPerMeter) {
        return Math.max(1, Math.round((float) (pathLength * itemsPerMeter)));
    }

    /** The legs' marks for a path run the other way, for a wire whose ends swap over. */
    public static long reversed(long ductLegs, int legCount) {
        long out = 0;
        for (int leg = 0; leg < legCount && leg < 64; leg++)
            if (isDucted(ductLegs, leg))
                out |= 1L << (legCount - 1 - leg);
        return out;
    }

    /** The ducted legs of a path, as pairs: start, end, start, end... */
    public static List<Vec3> ductedLegs(List<Vec3> path, long ductLegs) {
        List<Vec3> legs = new ArrayList<>();
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            if (isDucted(ductLegs, leg)) {
                legs.add(path.get(leg));
                legs.add(path.get(leg + 1));
            }
        }
        return legs;
    }

    // A wire's save data

    /** Writes a routed wire's points and marks; a wire through no duct writes nothing. */
    public static void write(CompoundTag tag, List<Vec3> points, long ductLegs) {
        if (ductLegs == 0)
            return;
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            list.add(DoubleTag.valueOf(p.x));
            list.add(DoubleTag.valueOf(p.y));
            list.add(DoubleTag.valueOf(p.z));
        }
        tag.put(NBT_POINTS, list);
        tag.putLong(NBT_LEGS, ductLegs);
    }

    /** A routed wire's points and marks, or null for a wire through no duct or a damaged save. */
    public static Built read(CompoundTag tag) {
        if (!tag.contains(NBT_POINTS, Tag.TAG_LIST) || !tag.contains(NBT_LEGS, Tag.TAG_LONG))
            return null;
        long legs = tag.getLong(NBT_LEGS);
        ListTag list = tag.getList(NBT_POINTS, Tag.TAG_DOUBLE);
        if (legs == 0 || list.size() % 3 != 0 || list.size() / 3 > MAX_POINTS)
            return null;
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < list.size(); i += 3) {
            Vec3 p = new Vec3(list.getDouble(i), list.getDouble(i + 1), list.getDouble(i + 2));
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z))
                return null;
            points.add(p);
        }
        // A mark past the last leg would be a mark on nothing
        if (points.size() + 1 < 64 && (legs >>> (points.size() + 1)) != 0)
            return null;
        return new Built(points, legs);
    }

    public static void write(FriendlyByteBuf buffer, List<Vec3> points, long ductLegs) {
        buffer.writeVarInt(points.size());
        for (Vec3 p : points) {
            buffer.writeDouble(p.x);
            buffer.writeDouble(p.y);
            buffer.writeDouble(p.z);
        }
        buffer.writeLong(ductLegs);
    }

    /** Reads what {@link #write(FriendlyByteBuf, List, long)} sent, never more than the cap. */
    public static Built read(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_POINTS)
            throw new IllegalArgumentException("Bad duct route point count " + count);
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            points.add(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        return new Built(Collections.unmodifiableList(points), buffer.readLong());
    }
}
