package com.nectariich.create_flippies.foundation.wire.bend;

import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.CurveParameters;

/**
 * How far each leg of a hanging wire sags, as plain data and maths.
 *
 * Power Grid hangs a wire as the catenary whose length is {@code sqrt(dx²·h + dy²·v)},
 * with {@code h} and {@code v} the coefficients of its wire type; the slack
 * {@code h - 1} is what makes it sag. A leg's sag here is Power Plant Grid's: a
 * multiplier {@code k} on that slack, {@code h' = 1 + (h - 1)·k} and the same for
 * {@code v} ({@link #horizontal}, {@link #vertical}). {@code k = 1} is Power Grid's
 * own shape, exactly, so an untouched wire never changes. The constants and the
 * clamp are Power Plant Grid's own ({@code WireSag}), so one value means one shape in
 * both mods: an unbent wire's single leg is the same curve whichever mod drew it,
 * and its value is saved under Power Plant Grid's key ({@link #PPG_SAG_KEY}) so the
 * other mod reads it as its own.
 *
 * A wire has one value per leg of its path, {@code points + 1}, kept in step with
 * the points by {@link #apply}: a new point splits a leg into two that both keep its
 * sag, a removed point merges its two legs into one, and a moved point changes
 * nothing. Sag never touches the wire's resistance or item count, in Power Grid,
 * Power Plant Grid or here ({@link WireBends#resistanceScale} goes by straight legs).
 */
public final class LegSag {

    /** Horizontal slack at the taut end: a 20-block span sags about 5 cm. Power Plant Grid's value. */
    public static final double MIN_SLACK = 0.0003;

    /** Horizontal slack at the slack end: the midpoint drops to about a third of the span. */
    public static final double MAX_SLACK = 0.6;

    /** The most the vertical coefficient may grow to, so steep slack legs do not balloon out. */
    public static final double MAX_VERTICAL = 3.0;

    /** The slack used for a wire type whose own horizontal coefficient carries (almost) none. */
    public static final double FALLBACK_SLACK = 0.001;

    /** Where a wire's legs' sag lives in its save data, one float per leg; only on a bent wire, and only when any is not 1. */
    public static final String NBT_KEY = "create_flippies:LegSag";

    /**
     * Power Plant Grid's whole-wire sag, a float left out at 1. Flippies writes an
     * unbent wire's sag here only while Power Plant Grid is not installed; with it
     * installed this key is Power Plant Grid's alone.
     */
    public static final String PPG_SAG_KEY = "PPGSag";

    /** Power Grid's own shape. */
    public static final float DEFAULT = 1f;

    private LegSag() {}

    // The curve

    private static double baseSlack(double horizontal) {
        return Math.max(horizontal - 1, FALLBACK_SLACK);
    }

    /** The horizontal coefficient of a leg at sag {@code k}, from the wire type's {@code horizontal}; exactly it at 1. */
    public static double horizontal(double horizontal, double k) {
        if (k == 1)
            return horizontal;
        return 1 + baseSlack(horizontal) * k;
    }

    /** The vertical coefficient of a leg at sag {@code k}; exactly the wire type's at 1. */
    public static double vertical(double vertical, double k) {
        if (k == 1)
            return vertical;
        return Math.max(1, Math.min(MAX_VERTICAL, 1 + (vertical - 1) * k));
    }

    /** The tautest sag a wire type with this horizontal coefficient may take. */
    public static double minSag(double horizontal) {
        return Math.min(1, MIN_SLACK / baseSlack(horizontal));
    }

    /** The slackest. */
    public static double maxSag(double horizontal) {
        return Math.max(1, MAX_SLACK / baseSlack(horizontal));
    }

    public static double clamp(double horizontal, double k) {
        return Math.max(minSag(horizontal), Math.min(maxSag(horizontal), k));
    }

    /** The curve one leg from {@code from} to {@code to} hangs in at sag {@code k}. */
    public static CurveParameters curve(Vec3 from, Vec3 to, double horizontal, double vertical, double thickness,
            double k) {
        //? if forge {
        return new CurveParameters(from, to, horizontal(horizontal, k), vertical(vertical, k), thickness);
        //?} else {
        /*return BendGeometry.curve(from, to, horizontal(horizontal, k), vertical(vertical, k), thickness);
        *///?}
    }

    /** Every leg's coefficients, for {@link BendGeometry}; a leg past the end of {@code sags} hangs at 1. */
    public static BendGeometry.LegCurves curves(double horizontal, double vertical, float[] sags) {
        if (sags == null || allDefault(sags))
            return BendGeometry.LegCurves.uniform(horizontal, vertical);
        return new BendGeometry.LegCurves() {
            @Override
            public double horizontal(int leg) {
                return LegSag.horizontal(horizontal, at(sags, leg));
            }

            @Override
            public double vertical(int leg) {
                return LegSag.vertical(vertical, at(sags, leg));
            }
        };
    }

    private static float at(float[] sags, int leg) {
        return leg >= 0 && leg < sags.length ? sags[leg] : DEFAULT;
    }

    /** The horizontal length of a leg; below about 1e-3 it is vertical and has no sag to speak of. */
    public static double horizontalSpan(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Where {@code at} lies along a leg's curve, horizontally, from the leg's horizontal midpoint. */
    public static double alongWire(CurveParameters curve, Vec3 from, Vec3 to, Vec3 at) {
        Vec3 n = curve.getNormal();
        return (at.x - (from.x + to.x) * 0.5) * n.x + (at.z - (from.z + to.z) * 0.5) * n.z;
    }

    /** How far a leg hangs below its straight chord at its deepest, blocks. */
    public static double depth(CurveParameters curve, Vec3 from, Vec3 to) {
        double span = curve.getCurveSpan();
        double dy = to.y - from.y;
        double best = 0;
        for (int i = 1; i < 32; i++) {
            double x = ((double) i / 32 - 0.5) * span;
            best = Math.max(best, dy * ((double) i / 32) - curve.apply(x));
        }
        return best;
    }

    /**
     * The sag that makes a leg pass through height {@code targetY} (world) at
     * {@code x} along it ({@link #alongWire}), clamped to the wire type's range. A leg
     * drops steadily as its sag grows, so this halves the range in log-sag; Power Plant
     * Grid's own search, step for step.
     */
    public static double solve(double horizontal, double vertical, double thickness, Vec3 from, Vec3 to, double x,
            double targetY) {
        double target = targetY - from.y;
        double lo = Math.log(minSag(horizontal));
        double hi = Math.log(maxSag(horizontal));
        if (curve(from, to, horizontal, vertical, thickness, Math.exp(lo)).apply(x) <= target)
            return Math.exp(lo);
        if (curve(from, to, horizontal, vertical, thickness, Math.exp(hi)).apply(x) >= target)
            return Math.exp(hi);
        for (int i = 0; i < 32; i++) {
            double mid = (lo + hi) * 0.5;
            // Still above the target: it needs more sag
            if (curve(from, to, horizontal, vertical, thickness, Math.exp(mid)).apply(x) > target)
                lo = mid;
            else
                hi = mid;
        }
        return Math.exp((lo + hi) * 0.5);
    }

    // Keeping the legs in step with the points

    /**
     * Exactly {@code legs} values: {@code sags} itself when it already has that many,
     * otherwise every leg at its first value (or 1). That covers every way points
     * change without an edit to say how the legs moved: a duct route, a load, a sync.
     */
    public static float[] normalise(float[] sags, int legs) {
        if (sags != null && sags.length == legs)
            return sags;
        float[] out = new float[Math.max(0, legs)];
        Arrays.fill(out, sags != null && sags.length > 0 ? sags[0] : DEFAULT);
        return out;
    }

    /**
     * The legs' sag after one edit of the points, {@code path} being the whole path
     * before it (for the lengths of legs a removal merges). The input is left alone.
     * <ul>
     * <li>INSERT at {@code index} splits leg {@code index} in two, both keeping its sag;</li>
     * <li>REMOVE of point {@code index} merges legs {@code index} and {@code index + 1}
     * ({@link #merge});</li>
     * <li>MOVE changes nothing.</li>
     * </ul>
     */
    public static float[] apply(WireBends.Op op, float[] sags, int index, List<Vec3> path) {
        if (sags == null)
            return null;
        switch (op) {
            case INSERT -> {
                if (index < 0 || index >= sags.length)
                    return sags.clone();
                float[] out = new float[sags.length + 1];
                System.arraycopy(sags, 0, out, 0, index + 1);
                System.arraycopy(sags, index, out, index + 1, sags.length - index);
                return out;
            }
            case REMOVE -> {
                if (index < 0 || index + 1 >= sags.length)
                    return sags.clone();
                double a = 1, b = 1;
                if (path != null && index + 2 < path.size()) {
                    a = horizontalSpan(path.get(index), path.get(index + 1));
                    b = horizontalSpan(path.get(index + 1), path.get(index + 2));
                }
                float[] out = new float[sags.length - 1];
                System.arraycopy(sags, 0, out, 0, index);
                out[index] = merge(sags[index], a, sags[index + 1], b);
                System.arraycopy(sags, index + 2, out, index + 1, sags.length - index - 2);
                return out;
            }
            default -> {
                return sags.clone();
            }
        }
    }

    /**
     * One sag for two legs made one: the mean of their log-sags, each weighted by its
     * leg's horizontal length, so a long leg's shape counts for more and two equal
     * values stay exactly as they were.
     */
    public static float merge(float a, double lengthA, float b, double lengthB) {
        if (a == b)
            return a;
        double total = lengthA + lengthB;
        double wa = total < 1e-9 ? 0.5 : lengthA / total;
        return (float) Math.exp(wa * Math.log(a) + (1 - wa) * Math.log(b));
    }

    /** The legs in reverse, for a wire whose ends have swapped over; a catenary is the same either way round. */
    public static float[] reversed(float[] sags) {
        float[] out = new float[sags.length];
        for (int i = 0; i < sags.length; i++)
            out[i] = sags[sags.length - 1 - i];
        return out;
    }

    public static boolean allDefault(float[] sags) {
        for (float s : sags)
            if (s != DEFAULT)
                return false;
        return true;
    }

    /** Whether a value read from a save or a packet can stand as a sag. */
    public static boolean valid(float k) {
        return Float.isFinite(k) && k > 0;
    }

    // Saving

    /** Writes the legs under {@link #NBT_KEY}, unless every one hangs at 1. */
    public static void write(CompoundTag tag, float[] sags) {
        if (sags == null || allDefault(sags))
            return;
        ListTag list = new ListTag();
        for (float s : sags)
            list.add(FloatTag.valueOf(s));
        tag.put(NBT_KEY, list);
    }

    /** The legs saved under {@link #NBT_KEY}, or null when there are none; a value that cannot be a sag reads as 1. */
    public static float[] read(CompoundTag tag) {
        if (!tag.contains(NBT_KEY, Tag.TAG_LIST))
            return null;
        ListTag list = tag.getList(NBT_KEY, Tag.TAG_FLOAT);
        float[] out = new float[Math.min(list.size(), WireBends.MAX_STORED + 1)];
        for (int i = 0; i < out.length; i++) {
            float k = list.getFloat(i);
            out[i] = valid(k) ? k : DEFAULT;
        }
        return out;
    }

    /** Writes an unbent wire's sag as Power Plant Grid does: a float, left out at 1. */
    public static void writePpg(CompoundTag tag, float k) {
        if (k != DEFAULT)
            tag.putFloat(PPG_SAG_KEY, k);
    }

    /** Reads it as Power Plant Grid does: missing is 1. */
    public static float readPpg(CompoundTag tag) {
        if (!tag.contains(PPG_SAG_KEY, Tag.TAG_FLOAT))
            return DEFAULT;
        float k = tag.getFloat(PPG_SAG_KEY);
        return valid(k) ? k : DEFAULT;
    }

    // Sending

    /** Writes the legs, or none for a wire with no sag of its own ({@code null}). */
    public static void write(FriendlyByteBuf buffer, float[] sags) {
        int count = sags == null ? 0 : sags.length;
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            buffer.writeFloat(sags[i]);
    }

    /** Reads what {@link #write(FriendlyByteBuf, float[])} sent; null for none. */
    public static float[] read(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > WireBends.MAX_STORED + 1)
            throw new IllegalArgumentException("Bad wire leg count " + count);
        if (count == 0)
            return null;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            float k = buffer.readFloat();
            out[i] = valid(k) ? k : DEFAULT;
        }
        return out;
    }
}
