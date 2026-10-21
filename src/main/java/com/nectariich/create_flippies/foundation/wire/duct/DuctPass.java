package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * One slide of a wire along joined ducts: the duct centre line it follows from one
 * point to another ({@code centers}, with the face each leg of it lies on in
 * {@code faces}), the duct size, and how far across the duct the wire runs,
 * {@code offset} blocks along {@link #lateral} in the frame of travel.
 *
 * Keeping the offset in the frame of travel is what keeps wires in order through a
 * turn: a wire on the inside of a corner stays on the inside of the next leg, on a
 * face or from a wall onto the floor ({@link #points}).
 *
 * The wire itself lies on the duct's floor like a placeable wire on a face
 * ({@link #wirePoints}); what the player sees of it are strokes on the lid above.
 */
public record DuctPass(List<Vec3> centers, List<Direction> faces, DuctSize size, double offset) {

    public DuctPass {
        centers = List.copyOf(centers);
        faces = List.copyOf(faces);
        if (centers.size() < 2 || faces.size() != centers.size() - 1)
            throw new IllegalArgumentException("A pass needs one face per leg");
    }

    public DuctPass withOffset(double newOffset) {
        return new DuctPass(centers, faces, size, newOffset);
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

    /** Sideways across leg {@code leg}, for travel along the pass. */
    public Vec3 lateral(int leg) {
        Vec3 d = centers.get(leg + 1).subtract(centers.get(leg)).normalize();
        return DuctSegment.lateral(normal(leg), d);
    }

    /**
     * The wire's way along the pass, in its lane, lying on the duct's floor at the
     * depth a placeable wire keeps off a face ({@link DuctWires#WIRE_DEPTH}).
     */
    public List<Vec3> wirePoints() {
        return points(DuctWires.WIRE_DEPTH - size.depth() / 2);
    }

    /**
     * Each centre point moved {@code up} out of its face and {@link #offset} sideways.
     * At a joint the offsets of both legs add up when they differ (a turn within one
     * face moves the corner both ways; a turn from a wall onto the floor moves it off
     * both), which keeps every leg parallel to its duct.
     */
    public List<Vec3> points(double up) {
        double side = offset;
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
}
