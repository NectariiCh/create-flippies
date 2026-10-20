package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/**
 * One stretch of a wire's way that runs inside ducts: the duct centre line it
 * follows from where it goes in to where it comes out ({@code centers}, with the
 * face each leg of it lies on in {@code faces}), the duct size, and the lane it
 * takes across the duct.
 *
 * The wire itself is drawn on the duct's lid, in its lane, as strokes: see
 * {@link #lanePoints}. It goes in and comes out through the duct's side, so its
 * way also crosses from the lane to the side edge at each end ({@link #edge}).
 */
public record DuctPass(List<Vec3> centers, List<Direction> faces, DuctSize size, int lane) {

    /** The most centre-line points one pass may have. */
    public static final int MAX_POINTS = DuctNetwork.MAX_SEGMENTS + 1;

    /** How far above the lid a stroke's surface floats, beyond the wire's own radius, blocks. */
    public static final double STROKE_CLEARANCE = 0.004;

    public DuctPass {
        centers = List.copyOf(centers);
        faces = List.copyOf(faces);
        if (centers.size() < 2 || faces.size() != centers.size() - 1)
            throw new IllegalArgumentException("A pass needs one face per leg");
    }

    public DuctPass withLane(int newLane) {
        return new DuctPass(centers, faces, size, newLane);
    }

    public double length() {
        double total = 0;
        for (int i = 1; i < centers.size(); i++)
            total += centers.get(i - 1).distanceTo(centers.get(i));
        return total;
    }

    private Vec3 normal(int leg) {
        return Vec3.atLowerCornerOf(faces.get(leg).getNormal());
    }

    private Vec3 lateral(int leg) {
        Vec3 d = centers.get(leg + 1).subtract(centers.get(leg)).normalize();
        return DuctSegment.lateral(normal(leg), d);
    }

    /** How far out from the centre line a stroke runs, for a wire this thick. */
    public double lift(double wireThickness) {
        return size.depth() / 2 + wireThickness / 2 + STROKE_CLEARANCE;
    }

    /**
     * The wire's way along the lid, in its lane: each centre point moved out of the
     * face onto the lid and sideways into the lane. At a joint the offsets of both
     * legs add up when they differ (a turn within one face moves the corner both
     * ways; a turn from a wall onto the floor lifts it off both), which keeps every
     * leg parallel to its duct.
     */
    public List<Vec3> lanePoints(double wireThickness) {
        double up = lift(wireThickness);
        double side = size.laneOffset(lane);
        int legs = faces.size();
        List<Vec3> points = new ArrayList<>(centers.size());
        for (int i = 0; i < centers.size(); i++) {
            int before = Math.max(0, i - 1), after = Math.min(legs - 1, i);
            Vec3 n = union(normal(before), normal(after));
            Vec3 l = union(lateral(before), lateral(after));
            points.add(centers.get(i).add(n.scale(up)).add(l.scale(side)));
        }
        return points;
    }

    private static Vec3 union(Vec3 a, Vec3 b) {
        return a.distanceToSqr(b) < 1e-6 ? a : a.add(b);
    }

    /**
     * Where the wire crosses the duct's side next to one end of the pass: from the
     * lane point at that end, sideways to whichever side edge faces
     * {@code toward} (the wire's way before the entry, or after the exit).
     */
    public Vec3 edge(boolean entry, Vec3 toward, double wireThickness) {
        List<Vec3> lanes = lanePoints(wireThickness);
        Vec3 at = entry ? lanes.get(0) : lanes.get(lanes.size() - 1);
        Vec3 lat = lateral(entry ? 0 : faces.size() - 1);
        double sign = toward == null || toward.subtract(at).dot(lat) >= 0 ? 1 : -1;
        return at.add(lat.scale(sign * size.width() / 2 - size.laneOffset(lane)));
    }

    // Saving

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        ListTag points = new ListTag();
        for (Vec3 p : centers) {
            points.add(DoubleTag.valueOf(p.x));
            points.add(DoubleTag.valueOf(p.y));
            points.add(DoubleTag.valueOf(p.z));
        }
        tag.put("C", points);
        int[] f = new int[faces.size()];
        for (int i = 0; i < f.length; i++)
            f[i] = faces.get(i).get3DDataValue();
        tag.putIntArray("F", f);
        tag.putInt("S", size.ordinal());
        tag.putInt("L", lane);
        return tag;
    }

    /** A pass read back, or null if the save is damaged or out of bounds. */
    public static DuctPass read(CompoundTag tag) {
        ListTag list = tag.getList("C", Tag.TAG_DOUBLE);
        int[] f = tag.getIntArray("F");
        int count = list.size() / 3;
        if (count < 2 || count > MAX_POINTS || f.length != count - 1)
            return null;
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Vec3 p = new Vec3(list.getDouble(3 * i), list.getDouble(3 * i + 1), list.getDouble(3 * i + 2));
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z))
                return null;
            points.add(p);
        }
        List<Direction> faces = new ArrayList<>(f.length);
        for (int v : f) {
            if (v < 0 || v > 5)
                return null;
            faces.add(Direction.from3DDataValue(v));
        }
        DuctSize size = DuctSize.byOrdinal(tag.getInt("S"));
        int lane = tag.getInt("L");
        if (lane < 0 || lane >= size.lanes())
            return null;
        return new DuctPass(points, faces, size, lane);
    }
}
