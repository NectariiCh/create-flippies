package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * The surface of a bent wire, as one square tube swept along its whole path.
 *
 * Power Grid draws a wire as straight pieces sharing one cross-section, square to
 * the line between the wire's two ends. That is seamless along one curve, but two
 * legs of a bent wire each have their own line and so their own cross-section:
 * where they met at a bend point the two square ends were twisted and tilted
 * against each other, leaving notches on the outside of the bend and overlaps on
 * the inside. Here the cross-section is carried along the path from piece to piece
 * by the smallest turn that keeps it square to each piece, and wherever two pieces
 * meet (a bend point included) both end on one shared, mitred ring: the corners lie
 * on the plane halfway between the two directions, so the tube neither opens nor
 * overlaps from any side, and its texture runs on through the joint.
 *
 * A turn too sharp to mitre (folding back by more than {@link #SHARP_TURN_COS})
 * ends one tube and starts the next, each closed by a cap, which reads as a knot
 * the width of the wire. Legs inside a wire duct are drawn as dashes
 * ({@link #STROKE}, {@link #GAP}), each its own short tube; a dash that starts
 * right at a joint continues the hanging leg's tube without a seam.
 */
public final class WireSweep {

    /** Length of one dash of a wire drawn in a duct, and of the gap after it, blocks. */
    public static final double STROKE = 0.1875, GAP = 0.0625;

    /** The least cosine between two pieces that are still mitred together; sharper turns get a knot. */
    public static final double SHARP_TURN_COS = -0.5;

    private WireSweep() {}

    /**
     * One cross-section of the tube: its centre, the two vectors from the centre to
     * its corners (the other two corners are their negatives, as with Power Grid's
     * {@code cross1} and {@code cross2}), and how far along the path it is.
     */
    public record Ring(Vec3 at, Vec3 cross1, Vec3 cross2, double offset) {}

    /** One unbroken tube: a piece runs between each two consecutive rings. Open at both ends. */
    public record Strip(List<Ring> rings) {

        public Ring first() {
            return rings.get(0);
        }

        public Ring last() {
            return rings.get(rings.size() - 1);
        }
    }

    /**
     * The corners of the whole path in order, legs hanging as {@link BendGeometry}
     * hangs them and legs in ducts straight, each bend point appearing once.
     */
    public static List<Vec3> polyline(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness, double step) {
        List<Vec3> points = new ArrayList<>();
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 from = path.get(leg), to = path.get(leg + 1);
            if (from.distanceToSqr(to) < 1e-10)
                continue;
            List<Vec3> vertices;
            if (BendGeometry.isDucted(ductLegs, leg)) {
                int pieces = Math.max(1, (int) Math.ceil(from.distanceTo(to) / step));
                vertices = new ArrayList<>(pieces + 1);
                for (int i = 0; i <= pieces; i++)
                    vertices.add(from.lerp(to, (double) i / pieces));
            } else {
                vertices = BendGeometry.legVertices(from, to, horizontal, vertical, thickness, step);
            }
            // A leg starts where the last one ended
            points.addAll(points.isEmpty() ? vertices : vertices.subList(1, vertices.size()));
        }
        return points;
    }

    /**
     * {@code count + 1} places spread evenly by length along a polyline, from its
     * start to its end; Power Grid hangs string lights at such places.
     */
    public static List<Vec3> divide(List<Vec3> polyline, int count) {
        List<Vec3> out = new ArrayList<>(count + 1);
        if (polyline.isEmpty() || count < 1)
            return out;
        double total = WireBends.length(polyline);
        out.add(polyline.get(0));
        int piece = 1;
        double walked = 0;
        for (int k = 1; k < count; k++) {
            double target = total * k / count;
            while (piece < polyline.size() - 1
                    && walked + polyline.get(piece - 1).distanceTo(polyline.get(piece)) < target) {
                walked += polyline.get(piece - 1).distanceTo(polyline.get(piece));
                piece++;
            }
            Vec3 a = polyline.get(piece - 1), b = polyline.get(piece);
            double length = a.distanceTo(b);
            out.add(length < 1e-12 ? a : a.lerp(b, Math.min(1, (target - walked) / length)));
        }
        out.add(polyline.get(polyline.size() - 1));
        return out;
    }

    /**
     * Places no more than {@code spacing} apart along a polyline, not counting its
     * start and ending at its end: Power Grid's spacing of a string light cord's light
     * blocks, laid along the cord's own shape.
     */
    public static List<Vec3> spaced(List<Vec3> polyline, double spacing) {
        int count = (int) (WireBends.length(polyline) / spacing) + 1;
        List<Vec3> places = divide(polyline, count);
        return places.isEmpty() ? places : places.subList(1, places.size());
    }

    /**
     * The tube of a whole path. {@code reference} sets which way the cross-section
     * faces where the path starts (its part square to the first piece is used), so
     * that a bent wire starts out turned the way Power Grid would have drawn it; null
     * picks a side.
     */
    public static List<Strip> sweep(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness, double step, Vec3 reference) {
        List<Piece> pieces = pieces(path, ductLegs, horizontal, vertical, thickness, step);
        List<Strip> strips = new ArrayList<>();
        if (pieces.isEmpty())
            return strips;
        double radius = thickness * Math.sqrt(0.5);
        Vec3 tangent = pieces.get(0).direction();
        Vec3 c1 = square(reference, tangent);
        if (c1 == null)
            c1 = square(new Vec3(0, 1, 0), tangent);
        if (c1 == null)
            c1 = square(new Vec3(1, 0, 0), tangent);
        c1 = c1.scale(radius);
        Vec3 c2 = c1.cross(tangent).normalize().scale(radius);

        List<Ring> open = null;
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            Vec3 b = piece.direction();
            if (i > 0) {
                Piece before = pieces.get(i - 1);
                Vec3 a = before.direction();
                boolean joined = before.visible() && piece.visible() && a.dot(b) >= SHARP_TURN_COS;
                if (joined) {
                    Vec3 n = a.add(b).normalize();
                    open.add(new Ring(piece.from(), mitre(c1, a, n), mitre(c2, a, n), piece.offset()));
                } else if (open != null) {
                    open.add(new Ring(piece.from(), c1, c2, piece.offset()));
                    strips.add(new Strip(Collections.unmodifiableList(open)));
                    open = null;
                }
                Vec3[] turned = turn(c1, c2, a, b, radius);
                c1 = turned[0];
                c2 = turned[1];
                if (!joined && piece.visible())
                    open = new ArrayList<>(List.of(new Ring(piece.from(), c1, c2, piece.offset())));
            } else if (piece.visible()) {
                open = new ArrayList<>(List.of(new Ring(piece.from(), c1, c2, piece.offset())));
            }
        }
        if (open != null) {
            Piece end = pieces.get(pieces.size() - 1);
            open.add(new Ring(end.to(), c1, c2, end.offset() + end.length()));
            strips.add(new Strip(Collections.unmodifiableList(open)));
        }
        return strips;
    }

    /** The tube with its first piece left out, as where a split cord's two leads take over. */
    public static List<Strip> withoutFirstPiece(List<Strip> strips) {
        if (strips.isEmpty())
            return strips;
        List<Strip> out = new ArrayList<>(strips);
        List<Ring> rings = strips.get(0).rings();
        if (rings.size() > 2)
            out.set(0, new Strip(rings.subList(1, rings.size())));
        else
            out.remove(0);
        return out;
    }

    /** The tube with its last piece left out. */
    public static List<Strip> withoutLastPiece(List<Strip> strips) {
        if (strips.isEmpty())
            return strips;
        List<Strip> out = new ArrayList<>(strips);
        int last = strips.size() - 1;
        List<Ring> rings = strips.get(last).rings();
        if (rings.size() > 2)
            out.set(last, new Strip(rings.subList(0, rings.size() - 1)));
        else
            out.remove(last);
        return out;
    }

    // Building

    private record Piece(Vec3 from, Vec3 to, double offset, double length, boolean visible) {

        Vec3 direction() {
            return to.subtract(from).scale(1 / length);
        }
    }

    /** The path as straight pieces, a duct's legs cut into dashes and gaps. */
    private static List<Piece> pieces(List<Vec3> path, long ductLegs, double horizontal, double vertical,
            double thickness, double step) {
        List<Piece> out = new ArrayList<>();
        double along = 0;
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 from = path.get(leg), to = path.get(leg + 1);
            if (from.distanceToSqr(to) < 1e-10)
                continue;
            if (BendGeometry.isDucted(ductLegs, leg)) {
                along = dashes(out, from, to, along);
                continue;
            }
            List<Vec3> vertices = BendGeometry.legVertices(from, to, horizontal, vertical, thickness, step);
            for (int i = 1; i < vertices.size(); i++) {
                Vec3 a = vertices.get(i - 1), b = vertices.get(i);
                double length = a.distanceTo(b);
                if (length < 1e-9)
                    continue;
                out.add(new Piece(a, b, along, length, true));
                along += length;
            }
        }
        return out;
    }

    /** One straight duct leg as dashes and gaps, the pattern measured along the whole path. */
    private static double dashes(List<Piece> out, Vec3 from, Vec3 to, double along) {
        double length = from.distanceTo(to);
        Vec3 dir = to.subtract(from).scale(1 / length);
        double period = STROKE + GAP;
        double s = 0;
        while (s < length) {
            double phase = (along + s) % period;
            boolean dash = phase < STROKE;
            double end = Math.min(length, s + (dash ? STROKE - phase : period - phase));
            if (end - s > 1e-9) {
                Vec3 a = s == 0 ? from : from.add(dir.scale(s));
                Vec3 b = end >= length ? to : from.add(dir.scale(end));
                out.add(new Piece(a, b, along + s, end - s, dash));
            }
            s = Math.max(end, s + 1e-4);
        }
        return along + length;
    }

    /** The unit part of {@code v} square to a unit {@code direction}, or null if there is none to speak of. */
    private static Vec3 square(Vec3 v, Vec3 direction) {
        if (v == null)
            return null;
        Vec3 off = v.subtract(direction.scale(v.dot(direction)));
        return off.lengthSqr() < 1e-12 ? null : off.normalize();
    }

    /**
     * A corner of a cross-section square to {@code a}, slid along {@code a} onto the
     * plane through the joint with normal {@code n}, halfway between both pieces.
     */
    private static Vec3 mitre(Vec3 corner, Vec3 a, Vec3 n) {
        return corner.subtract(a.scale(corner.dot(n) / a.dot(n)));
    }

    /**
     * The cross-section turned from square to {@code a} to square to {@code b} by the
     * smallest rotation that does it, then squared up again against rounding.
     */
    private static Vec3[] turn(Vec3 c1, Vec3 c2, Vec3 a, Vec3 b, double radius) {
        Vec3 axis = a.cross(b);
        double sin = axis.length(), cos = a.dot(b);
        Vec3 r1;
        if (sin < 1e-9) {
            // Straight on, or straight back: then half a turn about the first corner, which keeps it
            r1 = c1;
        } else {
            Vec3 k = axis.scale(1 / sin);
            r1 = c1.scale(cos).add(k.cross(c1).scale(sin)).add(k.scale(k.dot(c1) * (1 - cos)));
        }
        Vec3 n1 = square(r1, b);
        if (n1 == null)
            n1 = square(c2, b);
        n1 = n1.scale(radius);
        return new Vec3[] {n1, n1.cross(b).normalize().scale(radius)};
    }
}
