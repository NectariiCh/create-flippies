package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The geometry of Power Grid's placeable wires in wire ducts, as plain data.
 *
 * A placeable wire is one entity: an anchor and a chain of axis-aligned steps that
 * hug the faces it was laid along, priced and resisted by the chain's total length.
 * A wire run through a duct stays exactly that. The part inside the duct is more of
 * the same chain: the wire turns in through the duct's side, runs along the duct's
 * floor some way across it ({@link DuctPass#wirePoints}), and turns out through
 * the side again. Nothing about the wire records the duct; whether a stretch of it is in one
 * is read off the geometry ({@link #holding}), which is why cutting, splitting,
 * flipping or merging the wire with Power Grid's own tools never leaves a stale mark
 * behind.
 *
 * A wire counts as in a duct when it turns or ends inside it: a vertex of its chain
 * lies inside the duct's box. Its parts inside that box are hidden and drawn as
 * strokes on the lid instead ({@link #hidden}, {@link #strokes}).
 *
 * A duct holds a width of wires, not a number of them ({@link #capacity}): each wire
 * running along a stretch of it takes up its own gauge's share there
 * ({@link #occupancy}, {@link #fit}). Where a wire runs across the duct is only an
 * order, kept from where it went in; its stroke on the lid is laid out with the
 * others present, side by side, each as wide as its gauge ({@link #layout}).
 */
public final class DuctWires {

    /** How far off a face a placeable wire's centre runs, blocks; the same in a duct as on a block. */
    public static final double WIRE_DEPTH = 1 / 32.0;

    /** How far past a duct's side wall a wire turns in or out, blocks; puts the turn on a pixel's centre. */
    public static final double SIDE_GAP = 1.5 / 16;

    /** How far above the lid a stroke's surface floats, blocks. */
    public static final double STROKE_CLEARANCE = 0.004;

    /** Length of one stroke drawn on a lid, and of the gap after it, blocks. */
    public static final double STROKE = 0.1875, GAP = 0.0625;

    /** Power Grid's own wires, and any wire whose gauge is not known, count as this gauge. */
    public static final int DEFAULT_AWG = 12;

    /** Kept clear along each side of a lid, blocks, or an eighth of a narrower lid; the strokes share what is left. */
    public static final double STROKE_MARGIN = 0.5 / 16;

    /** The narrowest a stroke is drawn when there is room, blocks. */
    public static final double MIN_STROKE = 0.4 / 16;

    /** Of each wire's share of the lid's width, how much its stroke fills; the rest is the gap beside it. */
    public static final double STROKE_FILL = 0.8;

    /** The finest step a wire's chain takes, blocks: Power Grid's segment unit. */
    public static final double UNIT = 1.0 / BlockWireGeometry.UNITS_PER_BLOCK;

    private static final double EPS = 1e-5;

    private DuctWires() {}

    /** A duct as a wire sees it: its centre line and the box it holds wires in (with its ends cut back or extended). */
    public record Duct(DuctSegment segment, AABB box) {

        public static Duct of(DuctSegment segment) {
            return new Duct(segment, segment.holdBox());
        }
    }

    /** One axis-aligned step of a wire's chain, in Power Grid's segment unit ({@link BlockWireGeometry}). */
    public record Step(Direction direction, int units) {

        public double length() {
            return units / (double) BlockWireGeometry.UNITS_PER_BLOCK;
        }

        public Vec3 vector() {
            return Vec3.atLowerCornerOf(direction.getNormal()).scale(length());
        }
    }

    /**
     * A wire running along a stretch of a duct, from {@code from} to {@code to} blocks
     * along it (equal for a wire that only turns in or out there), {@code offset}
     * across it along {@link DuctSegment#lateral}, travelling with the duct
     * ({@code travel} 1) or against it (-1). {@code awg} is its gauge, and {@code tie}
     * names the wire, to tell it from others and to order wires running exactly
     * side by side the same way all along.
     */
    public record Occupant(DuctSegment segment, double from, double to, double offset, int travel, int awg, long tie) {

        /** How much of the duct's width this wire takes: its thickness, blocks. */
        public double span() {
            return DuctWires.diameter(awg);
        }

        public double diameter() {
            return awgDiameter(awg);
        }

        public boolean covers(double along) {
            return from - EPS <= along && along <= to + EPS;
        }

        /** Whether it runs some way along the duct, rather than only turning in or out. */
        public boolean runs() {
            return to - from > 1e-4;
        }
    }

    /** Whether wires fit, and if not why: one is thicker than the duct alone holds, or the duct is full. */
    public enum Fit {
        OK, TOO_THICK, FULL
    }

    /** Where a wire's stroke goes across a stretch of duct: {@code offset} along its lateral, {@code width} wide. */
    public record Slot(Occupant occupant, double offset, double width) {}

    /** The slot a wire takes in a stretch of duct around a point of it, or null when it has none there. */
    @FunctionalInterface
    public interface Slots {
        Slot of(DuctSegment segment, Vec3 at);
    }

    /** One dash of a stroke, lying flat on a lid that faces {@code normal}, {@code width} across. */
    public record Dash(Vec3 from, Vec3 to, Vec3 normal, double width) {}

    /** The part of leg {@code leg} of a wire's path between parameters {@code t0} and {@code t1} that lies in a duct. */
    public record Piece(int leg, double t0, double t1, Duct duct) {

        public Vec3 from(List<Vec3> path) {
            return lerp(path.get(leg), path.get(leg + 1), t0);
        }

        public Vec3 to(List<Vec3> path) {
            return lerp(path.get(leg), path.get(leg + 1), t1);
        }
    }

    /** A straight stretch of a wire's path left showing. */
    public record Span(int leg, Vec3 from, Vec3 to) {}

    // Where a wire goes in a duct

    /**
     * The point of a duct's centre line nearest {@code p}, moved along the duct onto
     * the centre of a pixel, where a placeable wire's own turns lie.
     */
    public static Vec3 snapOnCentre(DuctSegment s, Vec3 p) {
        Vec3 q = s.project(p);
        Direction.Axis axis = s.axis();
        double a = DuctPlacement.coord(s.from(), axis), b = DuctPlacement.coord(s.to(), axis);
        double lo = Math.min(a, b), hi = Math.max(a, b);
        double c = DuctPlacement.coord(q, axis);
        double snapped = (Math.floor(c * 16) + 0.5) / 16;
        if (snapped < lo - EPS)
            snapped += 1 / 16.0;
        if (snapped > hi + EPS)
            snapped -= 1 / 16.0;
        if (snapped < lo - EPS || snapped > hi + EPS)
            return q;
        return DuctPlacement.with(q, axis, snapped);
    }

    /**
     * The point of a duct's centre line nearest the line of sight from {@code eye}
     * along {@code look}: where the end of a wire held to the duct goes as the player
     * looks along it.
     */
    public static Vec3 nearestToSight(DuctSegment s, Vec3 eye, Vec3 look) {
        Vec3 u = s.to().subtract(s.from());
        double a = u.dot(u), b = u.dot(look), c = look.dot(look);
        if (a < 1e-12)
            return s.from();
        Vec3 w = s.from().subtract(eye);
        double d = u.dot(w), e = look.dot(w);
        double denom = a * c - b * b;
        double t = denom < 1e-12 * a * c ? -d / a : (b * e - c * d) / denom;
        return s.from().add(u.scale(Math.max(0, Math.min(1, t))));
    }

    /** Where a wire in the duct lies at the centre-line point {@code center}, {@code lateral} across from it. */
    public static Vec3 wirePoint(DuctSegment s, Vec3 center, double lateral) {
        return center.add(s.normal().scale(WIRE_DEPTH - s.size().depth() / 2)).add(s.lateral().scale(lateral));
    }

    /** The side of the duct (+1 along {@link DuctSegment#lateral}, or -1) that faces {@code toward} from {@code at}. */
    public static int sideToward(DuctSegment s, Vec3 at, Vec3 toward) {
        return toward.subtract(at).dot(s.lateral()) >= 0 ? 1 : -1;
    }

    /** Where a wire turns in or out beside the duct at the centre-line point {@code center}, on {@code side}. */
    public static Vec3 beside(DuctSegment s, Vec3 center, int side) {
        return wirePoint(s, center, side * (s.size().width() / 2 + SIDE_GAP));
    }

    /** The two turns of a wire going in at {@code center} from {@code side}: beside the duct, then {@code offset} across it. */
    public static List<Vec3> entry(DuctSegment s, Vec3 center, int side, double offset) {
        return List.of(beside(s, center, side), wirePoint(s, center, offset));
    }

    /**
     * How far across the duct a wire going in at {@code along} from {@code side}
     * runs: just outside every wire already in that stretch on that side, so it
     * crosses none of them going in; on the centre line in an empty one. Always a
     * whole number of Power Grid's units, and inside the duct's walls.
     */
    public static double entryOffset(DuctSegment s, double along, int side, Collection<Occupant> present) {
        double outer = Double.NEGATIVE_INFINITY;
        for (Occupant o : present)
            if (o.segment().equals(s))
                outer = Math.max(outer, side * o.offset());
        if (outer == Double.NEGATIVE_INFINITY)
            return 0;
        double limit = Math.floor((s.size().width() / 2 - UNIT) / UNIT + 1e-9) * UNIT;
        double at = Math.round((outer + UNIT) / UNIT) * UNIT;
        return side * Math.max(-limit, Math.min(limit, at));
    }

    /** How far across the duct a wire ending at {@code from} runs, in the frame of a slide from there: it keeps that place. */
    public static double slideOffset(DuctPass pass, Vec3 from) {
        double offset = from.subtract(pass.centers().get(0)).dot(pass.lateral(0));
        return Math.round(offset / UNIT) * UNIT;
    }

    // Gauges and capacity

    /** A wire's conductor diameter by its American Wire Gauge, millimetres; 0 is 1/0. */
    public static double awgDiameter(int awg) {
        return 0.127 * Math.pow(92, (36 - awg) / 39.0);
    }

    /**
     * Blocks per millimetre of conductor: the scale the gauged wires are drawn at
     * (their {@code wireThickness}, 0.19925781 blocks for 1/0 AWG), and so the one
     * a wire is measured against a duct at.
     */
    public static final double BLOCKS_PER_MM = 0.19925781 / awgDiameter(0);

    /** How thick a wire of gauge {@code awg} is, blocks. */
    public static double diameter(int awg) {
        return awgDiameter(awg) * BLOCKS_PER_MM;
    }

    /**
     * How thick the wires along one stretch of a duct may add up to, blocks:
     * {@link DuctSize#capacity}. The gauge data has no outer (insulated) diameter, so
     * an insulated wire counts by its conductor like a bare one of the same gauge.
     */
    public static double capacity(DuctSize size) {
        return size.capacity();
    }

    /** Whether a wire of gauge {@code awg} is thin enough to go into a duct of {@code size} at all. */
    public static boolean thinEnough(DuctSize size, int awg) {
        return diameter(awg) <= size.maxWireDiameter() + 1e-12;
    }

    /**
     * How many wires of gauge {@code awg}, and nothing else, one stretch of a duct of
     * {@code size} holds: none if one is too thick, else as many as fit side by side.
     */
    public static int howMany(DuctSize size, int awg) {
        if (!thinEnough(size, awg))
            return 0;
        return (int) Math.floor(capacity(size) / diameter(awg) * (1 + 1e-9));
    }

    /**
     * A wire's gauge by its item id: the gauged wires by their table, Power Grid's own
     * four (and anything not known) as {@link #DEFAULT_AWG}.
     */
    public static int awgOf(String itemId) {
        if (itemId == null)
            return DEFAULT_AWG;
        var gauge = WireGauges.byFlippiesId(itemId).or(() -> WireGauges.byPpgId(itemId));
        return gauge.map(WireGauges.Gauge::awg).orElse(DEFAULT_AWG);
    }

    /**
     * Whether wires of gauge {@code awg} laid along {@code adding} fit among the wires
     * {@code present}: the wire must be thin enough for the duct at all, and at every
     * point of every stretch added, the thicknesses of the other wires there and of
     * the one added must not add up past the duct's capacity. A wire is counted once
     * at a point however many times it passes it.
     */
    public static Fit fit(DuctSize size, int awg, Collection<Occupant> adding, Collection<Occupant> present) {
        double capacity = capacity(size);
        if (!thinEnough(size, awg))
            return Fit.TOO_THICK;
        for (Occupant a : adding) {
            List<Double> points = new ArrayList<>(List.of(a.from(), a.to()));
            for (Occupant o : present) {
                if (!o.segment().equals(a.segment()))
                    continue;
                if (a.covers(o.from()))
                    points.add(o.from());
                if (a.covers(o.to()))
                    points.add(o.to());
            }
            for (double at : points) {
                double used = diameter(awg);
                Set<Long> counted = new HashSet<>();
                counted.add(a.tie());
                for (Occupant o : present)
                    if (o.segment().equals(a.segment()) && o.covers(at) && counted.add(o.tie()))
                        used += o.span();
                if (used > capacity * (1 + 1e-9))
                    return Fit.FULL;
            }
        }
        return Fit.OK;
    }

    // Strokes side by side

    /**
     * Where the strokes of the wires running along {@code s} lie across its lid: in
     * the order the wires run in (their offsets, the side they went in by), side by
     * side and centred, each one's share of the width in proportion to its diameter.
     * At full size wires filling the duct ({@link #capacity}) span its whole lid; when more are
     * present than fit at that scale, all shrink alike so they still fit.
     */
    public static List<Slot> layout(DuctSegment s, Collection<Occupant> present) {
        List<Occupant> along = new ArrayList<>();
        for (Occupant o : present)
            if (o.segment().equals(s) && o.runs())
                along.add(o);
        along.sort(Comparator.comparingDouble((Occupant o) -> Math.round(o.offset() / 1e-6) * 1e-6)
                .thenComparingLong(o -> o.travel() * o.tie()));
        double usable = s.size().width() - 2 * strokeMargin(s.size());
        double total = 0;
        for (Occupant o : along)
            total += o.diameter();
        double scale = usable / (capacity(s.size()) / BLOCKS_PER_MM);
        if (total * scale > usable)
            scale = usable / total;
        double[] shares = new double[along.size()];
        double sum = 0;
        for (int i = 0; i < shares.length; i++) {
            shares[i] = Math.max(MIN_STROKE / STROKE_FILL, along.get(i).diameter() * scale);
            sum += shares[i];
        }
        double fitTo = sum > usable ? usable / sum : 1;
        List<Slot> slots = new ArrayList<>(shares.length);
        double at = -sum * fitTo / 2;
        for (int i = 0; i < shares.length; i++) {
            double share = shares[i] * fitTo;
            slots.add(new Slot(along.get(i), at + share / 2, share * STROKE_FILL));
            at += share;
        }
        return slots;
    }

    /** What is kept clear along each side of a lid of this size, blocks: {@link #STROKE_MARGIN}, or an eighth of a narrow one. */
    public static double strokeMargin(DuctSize size) {
        return Math.min(STROKE_MARGIN, size.width() / 8);
    }

    // A wire's chain

    /**
     * The steps from {@code from} through every point of {@code targets} in turn.
     * Where a hop differs on more than one axis (only ever the first, which absorbs
     * how far short of its target a traced path stopped), {@code last} goes last.
     */
    public static List<Step> steps(Vec3 from, List<Vec3> targets, Direction.Axis last) {
        List<Step> out = new ArrayList<>();
        Vec3 at = from;
        for (Vec3 target : targets) {
            for (Direction.Axis axis : order(last)) {
                int units = BlockWireGeometry.toUnits(DuctPlacement.coord(target, axis) - DuctPlacement.coord(at, axis));
                if (units == 0)
                    continue;
                Step step = new Step(Direction.fromAxisAndDirection(axis,
                        units > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE),
                        Math.abs(units));
                out.add(step);
                at = at.add(step.vector());
            }
        }
        return out;
    }

    private static Direction.Axis[] order(Direction.Axis last) {
        if (last == null)
            return Direction.Axis.values();
        Direction.Axis[] axes = new Direction.Axis[3];
        int i = 0;
        for (Direction.Axis axis : Direction.Axis.values())
            if (axis != last)
                axes[i++] = axis;
        axes[2] = last;
        return axes;
    }

    /** Where a chain of steps from {@code from} ends. */
    public static Vec3 end(Vec3 from, List<Step> steps) {
        Vec3 at = from;
        for (Step step : steps)
            at = at.add(step.vector());
        return at;
    }

    /** The path a chain of steps from {@code from} makes, both ends included. */
    public static List<Vec3> path(Vec3 from, List<Step> steps) {
        List<Vec3> path = new ArrayList<>(steps.size() + 1);
        Vec3 at = from;
        path.add(at);
        for (Step step : steps) {
            at = at.add(step.vector());
            path.add(at);
        }
        return path;
    }

    public static double length(List<Step> steps) {
        double total = 0;
        for (Step step : steps)
            total += step.length();
        return total;
    }

    /**
     * The wire items a placement adds, as Power Grid prices one: a new wire pays for
     * its length, and a wire made longer pays for its whole new length less the
     * items it already holds. Either way a wire ends up holding what its total
     * length costs, so its resistance (Power Grid's per-item resistance times its
     * items) follows the whole length, inside ducts and out.
     */
    public static int itemsToAdd(boolean newWire, double totalLength, double addedLength, double itemsPerMeter,
            int wireCount) {
        double owed = newWire ? addedLength * itemsPerMeter : (totalLength + addedLength) * itemsPerMeter - wireCount;
        return Math.max(0, (int) Math.ceil(owed - 1e-9));
    }

    // A wire's path against the ducts around it

    /** Whether {@code v} lies inside a duct's box. */
    public static boolean holds(Duct d, Vec3 v) {
        AABB b = d.box();
        return v.x > b.minX + EPS && v.x < b.maxX - EPS && v.y > b.minY + EPS && v.y < b.maxY - EPS
                && v.z > b.minZ + EPS && v.z < b.maxZ - EPS;
    }

    /** The ducts a wire with this path runs in: those it turns or ends inside. */
    public static List<Duct> holding(List<Vec3> path, Collection<Duct> ducts) {
        List<Duct> in = new ArrayList<>();
        for (Duct d : ducts) {
            for (Vec3 v : path) {
                if (holds(d, v)) {
                    in.add(d);
                    break;
                }
            }
        }
        return in;
    }

    /** The parts of a wire's path inside any of {@code ducts}. */
    public static List<Piece> hidden(List<Vec3> path, Collection<Duct> ducts) {
        List<Piece> pieces = new ArrayList<>();
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 p = path.get(leg), q = path.get(leg + 1);
            double length = p.distanceTo(q);
            if (length < 1e-9)
                continue;
            for (Duct d : ducts) {
                double[] t = clip(d.box(), p, q);
                if (t != null && (t[1] - t[0]) * length > 1e-6)
                    pieces.add(new Piece(leg, t[0], t[1], d));
            }
        }
        return pieces;
    }

    /** What of a wire's path is left showing once {@code hidden} is taken out. */
    public static List<Span> visible(List<Vec3> path, List<Piece> hidden) {
        List<Span> spans = new ArrayList<>();
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 p = path.get(leg), q = path.get(leg + 1);
            double length = p.distanceTo(q);
            if (length < 1e-9)
                continue;
            List<double[]> cuts = new ArrayList<>();
            for (Piece piece : hidden)
                if (piece.leg() == leg)
                    cuts.add(new double[] {piece.t0(), piece.t1()});
            cuts.sort((a, b) -> Double.compare(a[0], b[0]));
            double at = 0;
            for (double[] cut : cuts) {
                if (cut[0] > at)
                    addSpan(spans, leg, p, q, at, cut[0], length);
                at = Math.max(at, cut[1]);
            }
            if (at < 1)
                addSpan(spans, leg, p, q, at, 1, length);
        }
        return spans;
    }

    private static void addSpan(List<Span> spans, int leg, Vec3 p, Vec3 q, double t0, double t1, double length) {
        if ((t1 - t0) * length > 1e-6)
            spans.add(new Span(leg, lerp(p, q, t0), lerp(p, q, t1)));
    }

    /**
     * Of a layout, the slot the wire named {@code tie} takes around the point
     * {@code at} of its stretch: the one whose stretch is nearest, then whose place
     * across is nearest. Null if the wire has none there.
     */
    public static Slot slotIn(List<Slot> layout, long tie, DuctSegment s, Vec3 at) {
        double along = s.along(at), side = s.lateralOffset(at);
        Slot best = null;
        double bestScore = Double.MAX_VALUE;
        for (Slot slot : layout) {
            Occupant o = slot.occupant();
            if (o.tie() != tie)
                continue;
            double gap = Math.max(0, Math.max(o.from() - along, along - o.to()));
            double score = gap + Math.abs(o.offset() - side);
            if (score < bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    /** The slot of a layout whose stroke lies nearest a point of the lid, among those whose stretch takes it in. */
    public static Slot slotAt(List<Slot> layout, DuctSegment s, Vec3 at) {
        double along = s.along(at), side = s.lateralOffset(at);
        Slot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Slot slot : layout) {
            if (!slot.occupant().covers(along))
                continue;
            double d = Math.abs(slot.offset() - side);
            if (d < bestDistance) {
                bestDistance = d;
                best = slot;
            }
        }
        return best;
    }

    /**
     * The strokes the hidden parts of a wire are drawn as: each part lifted out of its
     * duct onto the lid and moved across into the wire's slot there ({@code slots};
     * where it has none, it stays above where it lies), then cut into dashes.
     *
     * Where two parts meet at a turn the strokes meet exactly: each leg runs on to
     * where the other leg's stroke lies, and on a turn within one lid each also runs
     * on by half the other's width, so the corner is filled square, with no gap and
     * nothing sticking out past it. A turn from one lid onto another (a wall onto
     * the floor) meets at the edge between the two lids. The dash pattern carries on
     * through every turn.
     */
    public static List<Dash> strokes(List<Vec3> path, List<Piece> hidden, Slots slots, double defaultWidth) {
        return dashed(lines(path, hidden, slots, defaultWidth));
    }

    /**
     * {@link #strokes} before it is cut into dashes: one solid line per hidden part,
     * in order, each starting where the one before it ends when the wire runs on
     * from one to the next.
     */
    public static List<Dash> lines(List<Vec3> path, List<Piece> hidden, Slots slots, double defaultWidth) {
        List<Piece> sorted = new ArrayList<>(hidden);
        sorted.sort(Comparator.comparingInt(Piece::leg).thenComparingDouble(Piece::t0));
        // One leg running on through the boxes of two ducts (a corner square, a joint) along the same duct is one line
        List<Piece> pieces = new ArrayList<>();
        DuctSegment lastHome = null;
        for (int i = 0; i < sorted.size(); i++) {
            Piece piece = sorted.get(i);
            Vec3 d = path.get(piece.leg() + 1).subtract(path.get(piece.leg())).normalize();
            DuctSegment home = home(sorted, i, d);
            if (!pieces.isEmpty()) {
                Piece prev = pieces.get(pieces.size() - 1);
                if (prev.leg() == piece.leg() && Math.abs(prev.t1() - piece.t0()) < 1e-9 && home != null
                        && home.equals(lastHome)) {
                    Duct along = parallel(prev.duct().segment(), d) ? prev.duct() : piece.duct();
                    pieces.set(pieces.size() - 1, new Piece(prev.leg(), prev.t0(), piece.t1(), along));
                    continue;
                }
            }
            pieces.add(piece);
            lastHome = home;
        }
        int count = pieces.size();
        Vec3[] a = new Vec3[count], b = new Vec3[count], dir = new Vec3[count], n = new Vec3[count],
                o = new Vec3[count];
        double[] w = new double[count];
        boolean[] own = new boolean[count];
        for (int i = 0; i < count; i++) {
            Piece piece = pieces.get(i);
            a[i] = piece.from(path);
            b[i] = piece.to(path);
            dir[i] = path.get(piece.leg() + 1).subtract(path.get(piece.leg())).normalize();
            DuctSegment home = home(pieces, i, dir[i]);
            DuctSegment ref = home != null ? home : piece.duct().segment();
            n[i] = ref.normal();
            Vec3 mid = a[i].add(b[i]).scale(0.5);
            double up = mid.subtract(ref.from()).dot(n[i]);
            Vec3 offset = n[i].scale(ref.size().depth() / 2 + STROKE_CLEARANCE - up);
            w[i] = defaultWidth;
            Slot slot = home == null ? null : slots.of(home, mid);
            if (slot != null) {
                offset = offset.add(home.lateral().scale(slot.offset() - home.lateralOffset(mid)));
                w[i] = slot.width();
                own[i] = true;
            }
            o[i] = offset;
        }
        // A leg across a duct (going in or out) is as wide as the stroke it joins
        for (int i = 0; i < count; i++) {
            if (own[i])
                continue;
            if (i + 1 < count && joined(b[i], a[i + 1]) && own[i + 1])
                w[i] = w[i + 1];
            else if (i > 0 && joined(b[i - 1], a[i]) && own[i - 1])
                w[i] = w[i - 1];
        }
        Vec3[] start = new Vec3[count], end = new Vec3[count];
        for (int i = 0; i < count; i++) {
            start[i] = a[i].add(o[i]);
            end[i] = b[i].add(o[i]);
        }
        for (int i = 0; i + 1 < count; i++) {
            if (!joined(b[i], a[i + 1]) || Math.abs(dir[i].dot(dir[i + 1])) > 0.99)
                continue;
            Vec3 v = b[i];
            end[i] = v.add(o[i]).add(dir[i].scale(o[i + 1].dot(dir[i])));
            start[i + 1] = v.add(o[i + 1]).add(dir[i + 1].scale(o[i].dot(dir[i + 1])));
            if (n[i].distanceToSqr(n[i + 1]) < 1e-6) {
                end[i] = end[i].add(dir[i].scale(w[i + 1] / 2));
                start[i + 1] = start[i + 1].subtract(dir[i + 1].scale(w[i] / 2));
            }
        }
        List<Dash> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            lines.add(new Dash(start[i], end[i], n[i], w[i]));
        return lines;
    }

    /**
     * Solid lines cut into dashes, the pattern carrying on from each line to the next
     * where they meet (within a corner's overlap), and starting afresh where they do not.
     */
    public static List<Dash> dashed(List<Dash> lines) {
        List<Dash> dashes = new ArrayList<>();
        double phase = 0;
        Vec3 last = null;
        double period = STROKE + GAP;
        for (Dash line : lines) {
            Vec3 p = line.from(), q = line.to();
            if (last == null || last.distanceTo(p) > line.width() + 1e-6)
                phase = 0;
            last = q;
            double length = p.distanceTo(q);
            if (length < 1e-6)
                continue;
            Vec3 d = q.subtract(p).scale(1 / length);
            // Where in the pattern this line starts, and the dashes that fall on it
            for (double at = -(phase % period); at < length - 1e-6; at += period) {
                double from = Math.max(0, at), to = Math.min(length, at + STROKE);
                if (to - from > 1e-6)
                    dashes.add(new Dash(p.add(d.scale(from)), p.add(d.scale(to)), line.normal(), line.width()));
            }
            phase += length;
        }
        return dashes;
    }

    /** The duct a piece of a wire's path runs along: its own, or one the same leg runs along next to it. */
    private static DuctSegment home(List<Piece> pieces, int i, Vec3 dir) {
        Piece piece = pieces.get(i);
        if (parallel(piece.duct().segment(), dir))
            return piece.duct().segment();
        DuctSegment best = null;
        double bestGap = Double.MAX_VALUE;
        for (Piece other : pieces) {
            if (other.leg() != piece.leg() || !parallel(other.duct().segment(), dir))
                continue;
            double gap = Math.max(0, Math.max(other.t0() - piece.t1(), piece.t0() - other.t1()));
            if (gap < bestGap) {
                bestGap = gap;
                best = other.duct().segment();
            }
        }
        return best;
    }

    private static boolean parallel(DuctSegment s, Vec3 dir) {
        return Math.abs(s.direction().dot(dir)) > 0.99;
    }

    private static boolean joined(Vec3 p, Vec3 q) {
        return p.distanceToSqr(q) < 1e-10;
    }

    /**
     * How a wire with this path occupies {@code s}: every leg running along it inside,
     * over the stretch it covers, and every turn in or out through its side, as a
     * point. A leg merely crossing under a duct, turning nowhere inside it, takes up
     * nothing there.
     */
    public static List<Occupant> occupancy(DuctSegment s, List<Vec3> path, int awg, long tie) {
        List<Occupant> out = new ArrayList<>();
        Vec3 dir = s.direction();
        double length = s.length();
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 p = path.get(leg), q = path.get(leg + 1);
            Vec3 d = q.subtract(p);
            double l = d.length();
            if (l < 1e-6)
                continue;
            double along = d.scale(1 / l).dot(dir);
            if (Math.abs(along) > 0.99) {
                Vec3 mid = p.add(q).scale(0.5);
                if (!inside(s, mid))
                    continue;
                double a = s.along(p), b = s.along(q);
                double lo = Math.max(0, Math.min(a, b)), hi = Math.min(length, Math.max(a, b));
                if (hi - lo > 1e-4)
                    out.add(new Occupant(s, lo, hi, s.lateralOffset(mid), along > 0 ? 1 : -1, awg, tie));
                continue;
            }
            for (Vec3 end : new Vec3[] {p, q}) {
                double at = s.along(end);
                if (inside(s, end) && at > -EPS && at < length + EPS) {
                    out.add(new Occupant(s, at, at, s.lateralOffset(end), 1, awg, tie));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean inside(DuctSegment s, Vec3 p) {
        double side = s.lateralOffset(p);
        double up = p.subtract(s.from()).dot(s.normal());
        // Measured from the face up to as far as the duct holds wires; see DuctSize#holdDepth
        double out = up + s.size().depth() / 2;
        return Math.abs(side) < s.size().width() / 2 - EPS && out > EPS && out < s.size().holdDepth() - EPS;
    }

    /** How far {@code p} is from the nearest point of a path. */
    public static double distanceToPath(List<Vec3> path, Vec3 p) {
        double best = Double.MAX_VALUE;
        for (int leg = 0; leg + 1 < path.size(); leg++) {
            Vec3 a = path.get(leg), b = path.get(leg + 1);
            Vec3 d = b.subtract(a);
            double c = d.lengthSqr();
            double t = c < 1e-12 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(d) / c));
            best = Math.min(best, a.add(d.scale(t)).distanceTo(p));
        }
        if (path.size() == 1)
            best = path.get(0).distanceTo(p);
        return best;
    }

    // Helpers

    static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.add(b.subtract(a).scale(t));
    }

    /** The parameters, from 0 at {@code p} to 1 at {@code q}, between which the segment is inside the box; null if never. */
    static double[] clip(AABB box, Vec3 p, Vec3 q) {
        double t0 = 0, t1 = 1;
        double[] from = {p.x, p.y, p.z}, to = {q.x, q.y, q.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        for (int i = 0; i < 3; i++) {
            double d = to[i] - from[i];
            if (Math.abs(d) < 1e-12) {
                if (from[i] <= min[i] + EPS || from[i] >= max[i] - EPS)
                    return null;
                continue;
            }
            double a = (min[i] - from[i]) / d, b = (max[i] - from[i]) / d;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
            if (t0 >= t1)
                return null;
        }
        return new double[] {t0, t1};
    }
}
