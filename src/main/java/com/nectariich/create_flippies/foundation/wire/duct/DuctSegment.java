package com.nectariich.create_flippies.foundation.wire.duct;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One straight stretch of wire duct: its centre line from {@code from} to
 * {@code to}, the face it lies flat on (pointing out of that face) and its size.
 *
 * The centre line runs half the duct's depth out from the face, along one of the
 * two axes that lie in the face, so the channel's back sits flush on the face and
 * its lid faces the same way the face does. A duct of any length is a chain of
 * these joined end to end ({@link DuctNetwork}).
 */
public record DuctSegment(Vec3 from, Vec3 to, Direction face, DuctSize size) {

    /** How close two ends must be to count as one joint, blocks. */
    public static final double JOINT_EPSILON = 1e-4;

    public Vec3 normal() {
        return Vec3.atLowerCornerOf(face.getNormal());
    }

    public double length() {
        return from.distanceTo(to);
    }

    /** Unit vector from {@code from} to {@code to}; the face normal's neighbour for a duct of no length. */
    public Vec3 direction() {
        Vec3 d = to.subtract(from);
        double l = d.length();
        return l < 1e-9 ? Vec3.ZERO : d.scale(1 / l);
    }

    /** The axis the duct runs along. */
    public Direction.Axis axis() {
        Vec3 d = to.subtract(from);
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ax >= ay && ax >= az)
            return Direction.Axis.X;
        return ay >= az ? Direction.Axis.Y : Direction.Axis.Z;
    }

    /** Sideways across the duct, for travel from {@code from} to {@code to}: the face normal crossed with that direction. */
    public Vec3 lateral() {
        return lateral(normal(), direction());
    }

    public static Vec3 lateral(Vec3 normal, Vec3 direction) {
        return normal.cross(direction);
    }

    /** The point of the centre line nearest {@code p}. */
    public Vec3 project(Vec3 p) {
        Vec3 d = to.subtract(from);
        double c = d.dot(d);
        if (c < 1e-12)
            return from;
        double t = Math.max(0, Math.min(1, p.subtract(from).dot(d) / c));
        return from.add(d.scale(t));
    }

    /** How far along the centre line {@code p} lies from {@code from}, unclamped, blocks. */
    public double along(Vec3 p) {
        return p.subtract(from).dot(direction());
    }

    /** Whether {@code p} is one of this segment's two ends. */
    public boolean endsAt(Vec3 p) {
        return from.distanceToSqr(p) < JOINT_EPSILON * JOINT_EPSILON
                || to.distanceToSqr(p) < JOINT_EPSILON * JOINT_EPSILON;
    }

    /** The end the two segments share, or null. Ducts of different sizes never join. */
    public Vec3 jointWith(DuctSegment other) {
        if (other.size != size)
            return null;
        for (Vec3 a : new Vec3[] {from, to})
            for (Vec3 b : new Vec3[] {other.from, other.to})
                if (a.distanceToSqr(b) < JOINT_EPSILON * JOINT_EPSILON)
                    return a;
        return null;
    }

    /**
     * The channel's box. {@code extendFrom} and {@code extendTo} lengthen (or, when
     * negative, shorten) it past each end along the duct, which is how two stretches
     * meeting at a corner tile it without overlapping.
     */
    public AABB box(double extendFrom, double extendTo) {
        Vec3 d = direction();
        Vec3 a = from.subtract(d.scale(extendFrom));
        Vec3 b = to.add(d.scale(extendTo));
        Vec3 n = normal();
        Vec3 lat = lateral();
        Vec3 half = abs(lat).scale(size.width() / 2).add(abs(n).scale(size.depth() / 2));
        AABB box = new AABB(a, b);
        return box.inflate(half.x, half.y, half.z);
    }

    public AABB box() {
        return box(0, 0);
    }

    /**
     * {@link #box(double, double)} as far out of the face as the duct holds wires
     * ({@link DuctSize#holdDepth}): the box itself, but for the shallowest ducts.
     */
    public AABB holdBox(double extendFrom, double extendTo) {
        AABB box = box(extendFrom, extendTo);
        double extra = size.holdDepth() - size.depth();
        return extra <= 0 ? box : box.expandTowards(normal().scale(extra));
    }

    public AABB holdBox() {
        return holdBox(0, 0);
    }

    private static Vec3 abs(Vec3 v) {
        return new Vec3(Math.abs(v.x), Math.abs(v.y), Math.abs(v.z));
    }

    /** Sideways offset of {@code p} from the centre line, along {@link #lateral()}. */
    public double lateralOffset(Vec3 p) {
        return p.subtract(from).dot(lateral());
    }

    /** The same segment run the other way. */
    public DuctSegment reversed() {
        return new DuctSegment(to, from, face, size);
    }
}
