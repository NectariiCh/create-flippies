package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a cabinet's walls are once its {@link CabinetCut}s have been made: each wall
 * a grid of quarter-pixel cells, solid or cut, and the rectangles those cells come to.
 *
 * A wall with nothing cut out of it stays the one box it always was. One that has
 * had holes made in it is drawn and clicked as the rectangles its solid cells merge
 * into ({@link Built#boxes} at quarter pixels, so a round hole looks round), and
 * collided with at whole pixels, a pixel solid where at least half of it is, so a
 * wall full of round holes does not cost the game a collision shape of thousands of
 * faces.
 *
 * The strokes are kept in order, and each change is checked against what it would
 * actually do ({@link #add}): one that changes nothing is turned down, earlier strokes
 * it covers entirely are forgotten, and a wall made whole again forgets all of its
 * strokes, so the list stays as short as what can be seen.
 */
public final class CabinetCuts {

    /** Cells per pixel. */
    public static final int RES = 4;
    /** The most strokes one cabinet keeps. */
    public static final int MAX = 256;
    /** Slack on the edges of what may be cut, px: a stroke off by a float's rounding still fits. */
    private static final double EPS = 1e-3;

    private CabinetCuts() {}

    /** A rectangle on a wall, model px. */
    public record Rect(double u0, double v0, double u1, double v1) {}

    /** Why a stroke was turned down, or {@link #OK}; {@link #key} under {@code message.create_flippies.cabinet_cut.}. */
    public enum Problem {
        OK, NOTHING_TO_CUT, NOTHING_TO_FILL, OFF_WALL, TOO_SMALL, TOO_MANY;

        public boolean ok() {
            return this == OK;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What {@link #add} came to: the new list, or the old one and why not. */
    public record Change(List<CabinetCut> cuts, Problem problem) {}

    /** A wall's cells, solid or cut. */
    private static final class Mask {
        final CabinetWall.Range full;
        final int w, h;
        final boolean[] solid;

        Mask(CabinetWall.Range full) {
            this.full = full;
            this.w = Math.max(0, (int) Math.round((full.u1() - full.u0()) * RES));
            this.h = Math.max(0, (int) Math.round((full.v1() - full.v0()) * RES));
            this.solid = new boolean[w * h];
            java.util.Arrays.fill(solid, true);
        }

        double u(int i) {
            return full.u0() + (i + 0.5) / RES;
        }

        double v(int j) {
            return full.v0() + (j + 0.5) / RES;
        }

        void apply(CabinetCut cut, CabinetWall.Range allowed) {
            int i0 = Math.max(0, (int) Math.floor((cut.u0() - full.u0()) * RES));
            int i1 = Math.min(w, (int) Math.ceil((cut.u1() - full.u0()) * RES));
            int j0 = Math.max(0, (int) Math.floor((cut.v0() - full.v0()) * RES));
            int j1 = Math.min(h, (int) Math.ceil((cut.v1() - full.v0()) * RES));
            for (int j = j0; j < j1; j++)
                for (int i = i0; i < i1; i++) {
                    double u = u(i), v = v(j);
                    if (allowed.contains(u, v) && cut.covers(u, v))
                        solid[j * w + i] = cut.fill();
                }
        }

        boolean whole() {
            for (boolean s : solid)
                if (!s)
                    return false;
            return true;
        }

        boolean at(double u, double v) {
            int i = (int) Math.floor((u - full.u0()) * RES), j = (int) Math.floor((v - full.v0()) * RES);
            return i < 0 || j < 0 || i >= w || j >= h || solid[j * w + i];
        }
    }

    private static Mask mask(CabinetSpec spec, CabinetWall wall, List<CabinetCut> cuts) {
        Mask mask = new Mask(wall.full(spec));
        CabinetWall.Range allowed = wall.allowed(spec);
        for (CabinetCut cut : cuts)
            if (cut.wall() == wall)
                mask.apply(cut, allowed);
        return mask;
    }

    /**
     * {@code cuts} with {@code cut} made after them, or why it cannot be. A
     * rectangle that runs past what may be cut is cut as far as it may; an ellipse,
     * which is a drilled hole of a size the player chose, has to fit.
     */
    public static Change add(CabinetSpec spec, List<CabinetCut> cuts, CabinetCut cut) {
        CabinetWall.Range allowed = cut.wall().allowed(spec);
        if (cut.shape() == CabinetCut.Shape.RECT) {
            cut = new CabinetCut(cut.wall(), cut.shape(), Math.max(cut.u0(), allowed.u0()),
                    Math.max(cut.v0(), allowed.v0()), Math.min(cut.u1(), allowed.u1()),
                    Math.min(cut.v1(), allowed.v1()), cut.fill());
            if (cut.u1() <= cut.u0() || cut.v1() <= cut.v0())
                return new Change(cuts, Problem.OFF_WALL);
        } else if (!cut.fill() && !fits(allowed, cut))
            return new Change(cuts, Problem.OFF_WALL);
        if (cut.u1() - cut.u0() < 1.0 / RES || cut.v1() - cut.v0() < 1.0 / RES)
            return new Change(cuts, Problem.TOO_SMALL);

        Mask before = mask(spec, cut.wall(), cuts);
        Mask after = mask(spec, cut.wall(), cuts);
        after.apply(cut, allowed);
        if (java.util.Arrays.equals(before.solid, after.solid))
            return new Change(cuts, cut.fill() ? Problem.NOTHING_TO_FILL : Problem.NOTHING_TO_CUT);

        List<CabinetCut> next = new ArrayList<>(cuts.size() + 1);
        CabinetCut made = cut;
        boolean whole = after.whole();
        for (CabinetCut old : cuts)
            if (old.wall() != made.wall() || !whole && !made.contains(old))
                next.add(old);
        if (!whole)
            next.add(made);
        if (next.size() > MAX)
            return new Change(cuts, Problem.TOO_MANY);
        return new Change(Collections.unmodifiableList(next), Problem.OK);
    }

    /** Whether {@code cut} lies wholly where cutting is allowed. */
    public static boolean fits(CabinetWall.Range allowed, CabinetCut cut) {
        return cut.u0() >= allowed.u0() - EPS && cut.v0() >= allowed.v0() - EPS && cut.u1() <= allowed.u1() + EPS
                && cut.v1() <= allowed.v1() + EPS;
    }

    /**
     * The rectangles of quarter-pixel cells {@code cut} would change on its wall,
     * clipped to what may be cut: what a preview of it should show.
     */
    public static List<Rect> footprint(CabinetSpec spec, CabinetCut cut) {
        CabinetWall.Range full = cut.wall().full(spec), allowed = cut.wall().allowed(spec);
        // Only the cells under the stroke, on the wall's own grid
        CabinetWall.Range box = new CabinetWall.Range(Math.max(full.u0(), Math.floor(cut.u0() * RES) / RES),
                Math.max(full.v0(), Math.floor(cut.v0() * RES) / RES),
                Math.min(full.u1(), Math.ceil(cut.u1() * RES) / RES),
                Math.min(full.v1(), Math.ceil(cut.v1() * RES) / RES));
        if (box.empty())
            return List.of();
        Mask mask = new Mask(box);
        java.util.Arrays.fill(mask.solid, false);
        mask.apply(cut.withFill(true), allowed);
        return merge(mask.solid, mask.w, mask.h, full.u0(), full.v0(), 1.0 / RES);
    }

    /** Every wall of a cabinet of {@code spec} with {@code cuts} made in it. */
    public static Built build(CabinetSpec spec, List<CabinetCut> cuts) {
        return new Built(spec, cuts);
    }

    /** The walls of one cabinet, its cuts made; build once per change, ask as often as needed. */
    public static final class Built {

        private final CabinetSpec spec;
        private final Map<CabinetWall, Mask> masks = new EnumMap<>(CabinetWall.class);
        private final Map<CabinetWall, List<Rect>> fine = new EnumMap<>(CabinetWall.class);
        private final Map<CabinetWall, List<Rect>> coarse = new EnumMap<>(CabinetWall.class);

        private Built(CabinetSpec spec, List<CabinetCut> cuts) {
            this.spec = spec;
            for (CabinetWall wall : CabinetWall.values()) {
                boolean any = false;
                for (CabinetCut cut : cuts)
                    any |= cut.wall() == wall;
                if (!any)
                    continue;
                Mask mask = mask(spec, wall, cuts);
                if (mask.whole())
                    continue;
                masks.put(wall, mask);
                fine.put(wall, merge(mask.solid, mask.w, mask.h, mask.full.u0(), mask.full.v0(), 1.0 / RES));
                coarse.put(wall, merge(coarsen(mask), mask.w / RES, mask.h / RES, mask.full.u0(), mask.full.v0(), 1));
            }
        }

        /** Whether nothing has been cut out of any wall. */
        public boolean whole() {
            return masks.isEmpty();
        }

        /** Whether the point {@code (u, v)} of {@code wall} is still there. */
        public boolean solidAt(CabinetWall wall, double u, double v) {
            Mask mask = masks.get(wall);
            return mask == null || mask.at(u, v);
        }

        /** The boxes {@code wall} is, the door as it stands; at whole pixels for {@code collision}. */
        public List<double[]> boxes(CabinetWall wall, boolean open, boolean collision) {
            CabinetWall.Frame frame = wall.frame(spec, open);
            List<Rect> rects = (collision ? coarse : fine).get(wall);
            List<double[]> boxes = new ArrayList<>();
            if (rects == null) {
                CabinetWall.Range f = wall.full(spec);
                if (!f.empty())
                    boxes.add(frame.box(f.u0(), f.v0(), f.u1(), f.v1(), 0, CabinetGeometry.WALL));
                return boxes;
            }
            for (Rect r : rects)
                boxes.add(frame.box(r.u0(), r.v0(), r.u1(), r.v1(), 0, CabinetGeometry.WALL));
            return boxes;
        }

        /** Every wall's boxes. */
        public List<double[]> boxes(boolean open, boolean collision) {
            List<double[]> boxes = new ArrayList<>();
            for (CabinetWall wall : CabinetWall.values())
                boxes.addAll(boxes(wall, open, collision));
            return boxes;
        }
    }

    /** Whole pixels, each solid where at least half its cells are. */
    private static boolean[] coarsen(Mask mask) {
        int w = mask.w / RES, h = mask.h / RES;
        boolean[] out = new boolean[w * h];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int count = 0;
                for (int b = 0; b < RES; b++)
                    for (int a = 0; a < RES; a++)
                        if (mask.solid[(j * RES + b) * mask.w + i * RES + a])
                            count++;
                out[j * w + i] = count * 2 >= RES * RES;
            }
        return out;
    }

    /**
     * The set cells of a {@code w} by {@code h} grid as rectangles: runs along each
     * row, a run carried on up while the row above has exactly the same one.
     */
    private static List<Rect> merge(boolean[] cells, int w, int h, double u0, double v0, double size) {
        List<Rect> out = new ArrayList<>();
        // Open rectangles by the run they cover: start, end, first row
        List<int[]> open = new ArrayList<>();
        for (int j = 0; j <= h; j++) {
            List<int[]> runs = new ArrayList<>();
            if (j < h)
                for (int i = 0; i < w; i++) {
                    if (!cells[j * w + i])
                        continue;
                    int s = i;
                    while (i < w && cells[j * w + i])
                        i++;
                    runs.add(new int[] {s, i});
                }
            List<int[]> next = new ArrayList<>();
            for (int[] run : runs) {
                int[] carried = null;
                for (int[] o : open)
                    if (o[0] == run[0] && o[1] == run[1]) {
                        carried = o;
                        break;
                    }
                if (carried != null) {
                    open.remove(carried);
                    next.add(carried);
                } else
                    next.add(new int[] {run[0], run[1], j});
            }
            for (int[] o : open)
                out.add(new Rect(u0 + o[0] * size, v0 + o[2] * size, u0 + o[1] * size, v0 + j * size));
            open = next;
        }
        return out;
    }
}
