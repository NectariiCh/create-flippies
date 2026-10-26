package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Ducts joined end to end, and the way a wire slides through them.
 *
 * Two stretches are joined when an end of one is an end of the other and they are
 * the same size; nothing else connects them, so ducts that merely cross or touch
 * stay apart. A wire sliding from one point to another follows the fewest
 * stretches between the two, turning at each joint on the way.
 *
 * There is no limit on how far apart the two may be. The search walks the joints
 * it is handed (a level looks them up by position, not by searching a radius), and
 * stops only after {@link #MAX_VISITED} stretches, which no honest network reaches
 * and which keeps one click from walking a pathological one for ever.
 */
public final class DuctNetwork {

    /** A safety cap on the stretches one search may visit, not a limit on distance. */
    public static final int MAX_VISITED = 1 << 16;

    private DuctNetwork() {}

    /**
     * The stretches from {@code from} to {@code to}, both included, fewest first;
     * null if they are not joined. {@code neighbours} answers the stretches joined
     * to one, and {@code segmentOf} the geometry of each.
     */
    public static <T> List<T> chain(T from, T to, Function<T, DuctSegment> segmentOf,
            Function<T, Collection<T>> neighbours) {
        Map<T, T> previous = new HashMap<>();
        previous.put(from, null);
        Deque<T> queue = new ArrayDeque<>();
        queue.add(from);
        boolean found = from.equals(to);
        while (!queue.isEmpty() && !found) {
            T at = queue.poll();
            DuctSegment a = segmentOf.apply(at);
            for (T next : neighbours.apply(at)) {
                if (previous.containsKey(next) || a.jointWith(segmentOf.apply(next)) == null)
                    continue;
                previous.put(next, at);
                if (next.equals(to)) {
                    found = true;
                    break;
                }
                if (previous.size() >= MAX_VISITED)
                    return null;
                queue.add(next);
            }
        }
        if (!found)
            return null;
        List<T> chain = new ArrayList<>();
        for (T at = to; at != null; at = previous.get(at))
            chain.add(at);
        Collections.reverse(chain);
        return chain;
    }

    /** {@link #chain} over a plain list, every pair tested for a joint. */
    public static List<Integer> chain(List<DuctSegment> segments, int from, int to) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++)
            all.add(i);
        return chain(from, to, segments::get, at -> all);
    }

    /**
     * The centre line a wire follows along joined stretches ({@code chain}, in
     * order) from {@code fromPoint} to {@code toPoint}, as a pass {@code offset}
     * across the duct; null if it has no length. Both points are moved onto the centre
     * line of their stretch first.
     */
    public static DuctPass route(List<DuctSegment> chain, Vec3 fromPoint, Vec3 toPoint, double offset) {
        if (chain.isEmpty())
            return null;
        DuctSegment first = chain.get(0);
        List<Vec3> points = new ArrayList<>();
        List<Direction> faces = new ArrayList<>();
        points.add(first.project(fromPoint));
        for (int i = 0; i + 1 < chain.size(); i++) {
            DuctSegment a = chain.get(i);
            Vec3 joint = a.jointWith(chain.get(i + 1));
            if (joint == null)
                return null;
            addLeg(points, faces, joint, a.face());
        }
        DuctSegment last = chain.get(chain.size() - 1);
        addLeg(points, faces, last.project(toPoint), last.face());
        if (faces.isEmpty())
            return null;
        return new DuctPass(points, faces, first.size(), offset);
    }

    /** {@link #route(List, Vec3, Vec3, double)} between two stretches of a list; null if they are not joined. */
    public static DuctPass route(List<DuctSegment> segments, int fromIndex, Vec3 fromPoint, int toIndex,
            Vec3 toPoint, double offset) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex >= segments.size() || toIndex >= segments.size())
            return null;
        List<Integer> chain = chain(segments, fromIndex, toIndex);
        if (chain == null)
            return null;
        List<DuctSegment> along = new ArrayList<>(chain.size());
        for (int i : chain)
            along.add(segments.get(i));
        return route(along, fromPoint, toPoint, offset);
    }

    private static void addLeg(List<Vec3> points, List<Direction> faces, Vec3 next, Direction face) {
        if (points.get(points.size() - 1).distanceToSqr(next) < 1e-10)
            return;
        points.add(next);
        faces.add(face);
    }

    /**
     * How far each of two joined stretches' boxes reach past their shared end, as
     * {existing, added}, so that together they tile the corner exactly: one solid
     * shape with no gap, no overlap and so no doubled faces.
     * <ul>
     * <li>A turn within one face: the existing one stops short by half its width and
     * the added one runs on by as much, so the corner square is drawn once.</li>
     * <li>A turn from one face onto another, over an inside corner (a wall down onto
     * the floor) or an outside one (round a block's edge): the centre lines meet half
     * a depth off both faces, so the existing one runs on by half a depth, to the
     * other face or to the other's lid, and the added one stops short by as much.
     * A stretch that runs to the turn from the other face itself, filling an inside
     * corner, runs on over the whole corner the same way, and one that runs from the
     * turn to the other face lies wholly inside the existing one's corner and shrinks
     * to nothing.</li>
     * <li>Straight on: end to end.</li>
     * </ul>
     */
    public static double[] jointExtensions(DuctSegment existing, DuctSegment added) {
        if (existing.axis() == added.axis())
            return new double[] {0, 0};
        if (existing.face() == added.face()) {
            double half = existing.size().width() / 2;
            return new double[] {-half, half};
        }
        double half = existing.size().depth() / 2;
        return new double[] {half, -half};
    }
}
