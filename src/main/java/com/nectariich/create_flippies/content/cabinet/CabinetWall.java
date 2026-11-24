package com.nectariich.create_flippies.content.cabinet;

import net.minecraft.world.phys.Vec3;

/**
 * One of the six sheets an electrical cabinet is made of, as a flat thing with a
 * thickness: a point on it is {@code (u, v)} across its face, model px, and
 * {@code t} into it, 0 on one face to {@link CabinetGeometry#WALL} on the other.
 *
 * Holes ({@link CabinetCut}) are stated in those terms, so a hole in the door is in
 * the door's own terms and swings with it.
 *
 * Every wall keeps a 1 px border nothing may be cut from ({@link #allowed}): cut out
 * everything there is to cut and what is left is the cabinet's twelve edges, and the
 * door's own 1 px frame.
 */
public enum CabinetWall {

    BACK, RIGHT, LEFT, BOTTOM, TOP, DOOR;

    /** A rectangle on the wall, {@code u} across and {@code v} up it, model px. */
    public record Range(double u0, double v0, double u1, double v1) {

        public boolean contains(double u, double v) {
            return u >= u0 && u <= u1 && v >= v0 && v <= v1;
        }

        public boolean empty() {
            return u1 <= u0 || v1 <= v0;
        }
    }

    /**
     * Where the wall is in the model: a point is {@code origin + u U + v V + t N}, the
     * three axes each one of the model's, signed.
     */
    public record Frame(Vec3 origin, Vec3 u, Vec3 v, Vec3 n) {

        public Vec3 at(double u, double v, double t) {
            return origin.add(this.u.scale(u)).add(this.v.scale(v)).add(n.scale(t));
        }

        /** {@code p}, model px, as {@code (u, v, t)}. */
        public Vec3 local(Vec3 p) {
            Vec3 d = p.subtract(origin);
            return new Vec3(d.dot(u), d.dot(v), d.dot(n));
        }

        /** The model box filling {@code (u0..u1, v0..v1)} through {@code t0..t1}. */
        public double[] box(double u0, double v0, double u1, double v1, double t0, double t1) {
            Vec3 a = at(u0, v0, t0), b = at(u1, v1, t1);
            return new double[] {Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z), Math.max(a.x, b.x),
                    Math.max(a.y, b.y), Math.max(a.z, b.z)};
        }
    }

    private static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);

    /** Where the wall is, the door as it stands {@code open} or not. */
    public Frame frame(CabinetSpec spec, boolean open) {
        double front = CabinetGeometry.front(spec);
        double z0 = CabinetGeometry.z0(spec), z1 = CabinetGeometry.z1(spec);
        return switch (this) {
            case BACK -> new Frame(new Vec3(CabinetGeometry.BACK, 0, 0), Z, Y, X);
            case RIGHT -> new Frame(new Vec3(0, 0, z0), X, Y, Z);
            case LEFT -> new Frame(new Vec3(0, 0, z1), X, Y, Z.reverse());
            case BOTTOM -> new Frame(new Vec3(0, CabinetGeometry.bottom(spec), 0), X, Z, Y);
            case TOP -> new Frame(new Vec3(0, CabinetGeometry.top(spec), 0), X, Z, Y.reverse());
            case DOOR -> doorFrame(spec, open, front, z0, z1);
        };
    }

    /** As {@code CabinetGeometry.onDoor} lays the door: across from the hinge edge, into it from its outer face. */
    private static Frame doorFrame(CabinetSpec spec, boolean open, double front, double z0, double z1) {
        boolean left = spec.hinge() == CabinetSpec.Hinge.LEFT;
        int wall = CabinetGeometry.WALL;
        if (!open)
            return left ? new Frame(new Vec3(front, 0, z1 - wall), Z.reverse(), Y, X.reverse())
                    : new Frame(new Vec3(front, 0, z0 + wall), Z, Y, X.reverse());
        return left ? new Frame(new Vec3(front, 0, z1), X, Y, Z.reverse()) : new Frame(new Vec3(front, 0, z0), X, Y, Z);
    }

    /** The whole of the wall's face. */
    public Range full(CabinetSpec spec) {
        int w = CabinetGeometry.WALL;
        double inner = CabinetGeometry.BACK + w, front = CabinetGeometry.front(spec);
        double y0 = CabinetGeometry.bottom(spec), y1 = CabinetGeometry.top(spec);
        double z0 = CabinetGeometry.z0(spec), z1 = CabinetGeometry.z1(spec);
        return switch (this) {
            case BACK -> new Range(z0, y0, z1, y1);
            case RIGHT, LEFT -> new Range(inner, y0, front, y1);
            case BOTTOM, TOP -> new Range(inner, z0 + w, front, z1 - w);
            case DOOR -> new Range(0, y0 + w, CabinetGeometry.doorWidth(spec), y1 - w);
        };
    }

    /**
     * What may be cut out of it: all but a 1 px border round the cabinet's edges. The
     * top and bottom lie between the sides, whose edges are the sides' own, so only
     * their front edge is kept; the door keeps a frame all round.
     */
    public Range allowed(CabinetSpec spec) {
        int w = CabinetGeometry.WALL;
        Range f = full(spec);
        return switch (this) {
            case BACK, DOOR -> new Range(f.u0() + w, f.v0() + w, f.u1() - w, f.v1() - w);
            case RIGHT, LEFT -> new Range(f.u0(), f.v0() + w, f.u1() - w, f.v1() - w);
            case BOTTOM, TOP -> new Range(f.u0(), f.v0(), f.u1() - w, f.v1());
        };
    }

    public static CabinetWall byIndex(int index) {
        CabinetWall[] all = values();
        return index >= 0 && index < all.length ? all[index] : null;
    }
}
