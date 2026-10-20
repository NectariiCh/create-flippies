package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Ducts joined end to end, and the way a wire takes through them.
 *
 * Two stretches are joined when an end of one is an end of the other and they are
 * the same size; nothing else connects them, so ducts that merely cross or touch
 * stay apart. A wire entering at one point and leaving at another follows the
 * fewest stretches between the two, turning at each joint on the way.
 */
public final class DuctNetwork {

    /** The most stretches one pass through ducts may cross. */
    public static final int MAX_SEGMENTS = 32;

    private DuctNetwork() {}

    /**
     * The centre line a wire follows from {@code fromPoint} on stretch
     * {@code fromIndex} to {@code toPoint} on stretch {@code toIndex}, as a pass in
     * lane {@code lane}; null if the two are not joined or are too far apart.
     * Both points are moved onto their stretch's centre line first.
     */
    public static DuctPass route(List<DuctSegment> segments, int fromIndex, Vec3 fromPoint, int toIndex,
            Vec3 toPoint, int lane) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= segments.size() || toIndex >= segments.size())
            return null;
        List<Integer> chain = chain(segments, fromIndex, toIndex);
        if (chain == null)
            return null;
        DuctSegment first = segments.get(fromIndex);
        List<Vec3> points = new ArrayList<>();
        List<Direction> faces = new ArrayList<>();
        points.add(first.project(fromPoint));
        for (int i = 0; i + 1 < chain.size(); i++) {
            DuctSegment a = segments.get(chain.get(i));
            Vec3 joint = a.jointWith(segments.get(chain.get(i + 1)));
            addLeg(points, faces, joint, a.face());
        }
        DuctSegment last = segments.get(toIndex);
        addLeg(points, faces, last.project(toPoint), last.face());
        if (faces.isEmpty())
            return null;
        return new DuctPass(points, faces, first.size(), lane);
    }

    private static void addLeg(List<Vec3> points, List<Direction> faces, Vec3 next, Direction face) {
        if (points.get(points.size() - 1).distanceToSqr(next) < 1e-10)
            return;
        points.add(next);
        faces.add(face);
    }

    /** The stretches from one to the other, both included, fewest first; null if not joined. */
    static List<Integer> chain(List<DuctSegment> segments, int from, int to) {
        int n = segments.size();
        int[] previous = new int[n];
        Arrays.fill(previous, -2);
        previous[from] = -1;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            if (at == to)
                break;
            for (int next = 0; next < n; next++) {
                if (previous[next] != -2 || segments.get(at).jointWith(segments.get(next)) == null)
                    continue;
                previous[next] = at;
                queue.add(next);
            }
        }
        if (previous[to] == -2)
            return null;
        List<Integer> chain = new ArrayList<>();
        for (int at = to; at != -1; at = previous[at])
            chain.add(at);
        Collections.reverse(chain);
        return chain.size() > MAX_SEGMENTS ? null : chain;
    }

    /**
     * How far each of two joined stretches' boxes reach past their shared end, as
     * {existing, added}: for a turn within one face the existing one stops short by
     * half its width and the added one runs on by as much, so the corner square is
     * drawn once rather than twice or not at all. Anything else meets end to end.
     */
    public static double[] jointExtensions(DuctSegment existing, DuctSegment added) {
        if (existing.face() != added.face() || existing.axis() == added.axis())
            return new double[] {0, 0};
        double half = existing.size().width() / 2;
        return new double[] {-half, half};
    }

    /**
     * The lowest lane free along every stretch of a pass, given the legs of wires
     * already in ducts nearby ({@code legs} as pairs: start, end, start, end...);
     * -1 when every lane is taken somewhere.
     */
    public static int freeLane(DuctSize size, List<DuctSegment> along, List<Vec3> legs) {
        boolean[] taken = new boolean[size.lanes()];
        for (DuctSegment segment : along) {
            for (int i = 0; i + 1 < legs.size(); i += 2) {
                Vec3 p = legs.get(i), q = legs.get(i + 1);
                if (segment.holdsLeg(p, q))
                    taken[size.nearestLane(segment.lateralOffset(p.add(q).scale(0.5)))] = true;
            }
        }
        for (int lane = 0; lane < taken.length; lane++)
            if (!taken[lane])
                return lane;
        return -1;
    }

    /** The stretches a pass runs along, in order, looked up among {@code segments}. */
    public static List<DuctSegment> segmentsOf(DuctPass pass, List<DuctSegment> segments) {
        List<DuctSegment> along = new ArrayList<>();
        List<Vec3> c = pass.centers();
        for (int i = 0; i + 1 < c.size(); i++) {
            Vec3 mid = c.get(i).add(c.get(i + 1)).scale(0.5);
            for (DuctSegment s : segments) {
                if (s.size() == pass.size() && s.face() == pass.faces().get(i)
                        && s.project(mid).distanceToSqr(mid) < 1e-6 && !along.contains(s)) {
                    along.add(s);
                }
            }
        }
        return along;
    }
}
