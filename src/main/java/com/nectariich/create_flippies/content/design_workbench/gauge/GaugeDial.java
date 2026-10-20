package com.nectariich.create_flippies.content.design_workbench.gauge;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeScale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * What makes a design on an imported model a gauge: what it measures, the dial
 * face printed for it by one of the two face generators, which submesh of the model
 * the printed face is drawn on, and how the model's needle follows the reading.
 *
 * The model, its placement, its needle parts and their pivot are the design's own,
 * exactly as for a switch: the needle is the design's moving parts and turns about
 * the design's pivot. This only adds what a gauge needs on top.
 *
 * The face is a rotary dial ({@link GaugeFaceDefinition}, the round face ported from
 * DHE's GaugeDefinition) or an edgewise scale ({@link EdgewiseFaceDefinition}, ported
 * from DHE's BarGaugeDefinition), whichever the design's kind is. Either is baked
 * into a texture on the client, which becomes the material of the chosen submesh,
 * {@link #face}: the texture is stretched over the rectangle {@link #uv} of the
 * submesh's own UV space, by default the bounds of the submesh's UVs, so a face
 * whose UVs fill any part of the texture square still shows the whole dial. Its top
 * is the top of that rectangle as an image is read (the smallest V once the OBJ's V
 * is flipped, which Hypergraphical does on import).
 *
 * The needle:
 * <ul>
 * <li>Rotary: it turns about the vertical through the pivot, as the dial's own
 * needle would, from the dial's start angle at the minimum to its end angle at the
 * maximum. {@link #needleZero} is the dial angle the needle points at as modelled
 * (0 for straight up the face), and {@link #reverse} turns it the other way, for a
 * face whose UVs are mirrored. Its poses are never stored; they follow from the
 * dial ({@link #rotaryPoses}).</li>
 * <li>Edgewise: by default it slides in a straight line between the design's two
 * poses (at the minimum, then at the maximum), as the DHE pointer behind a curved
 * face looks to; with {@link #slide} off it swings between them about the pivot,
 * as that pointer actually does. Both poses are set in the workbench, and can be
 * fitted to the printed scale's ends ({@link GaugeNeedleFit}).</li>
 * </ul>
 *
 * Immutable and safe on both sides; {@link #read} sanitises anything a client sends.
 */
public final class GaugeDial {

    public static final String TAG = "GaugeDial";

    /** The tag the gauges with a generated case were saved under; read only, to carry their scale over. */
    public static final String LEGACY_TAG = "Gauge";

    /** A reading this far past either end still moves the needle, so it is seen to peg. */
    public static final float UNDER = 0.04f, OVER = 0.06f;

    private static final int MAX_FACE = 2 * 128 + 1;

    /** What the gauge measures between its two terminals, and the resistance it puts there to do it. */
    public enum Quantity {
        /** Across the terminals, through the voltmeters' own high resistance. */
        VOLTAGE("voltage", 1_000_000),
        /** Through the terminals, as the drop across a small shunt. */
        CURRENT("current", 0.05);

        private final String id;
        private final double resistance;

        Quantity(String id, double resistance) {
            this.id = id;
            this.resistance = resistance;
        }

        public String id() {
            return id;
        }

        public double resistance() {
            return resistance;
        }

        /** The reading for a potential difference across the terminals. */
        public float reading(float volts) {
            return this == CURRENT ? (float) (volts / resistance) : volts;
        }

        public Quantity next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Component label() {
            return Component.translatable("gui." + CreateFlippies.MOD_ID + ".designed_gauge.quantity." + id);
        }

        public static Quantity of(String id) {
            for (Quantity quantity : values())
                if (quantity.id.equals(id))
                    return quantity;
            return VOLTAGE;
        }
    }

    public final Quantity quantity;
    /** The face printed on a rotary gauge; null on an edgewise one. */
    public final GaugeFaceDefinition dial;
    /** The face printed on an edgewise gauge; null on a rotary one. */
    public final EdgewiseFaceDefinition edgewise;
    /** The element key ("part#material") of the submesh drawn with the face, or "" for none yet. */
    public final String face;
    /** The rectangle of the face submesh's UV space the face fills: {u0, v0, u1, v1}. */
    private final float[] uv;
    /** Rotary: the dial angle, clockwise from 12 o'clock, the needle points at as modelled. */
    public final float needleZero;
    /** Rotary: the needle turns counter-clockwise as the reading rises, for a mirrored face. */
    public final boolean reverse;
    /** Edgewise: the needle slides between its poses rather than swinging about the pivot. */
    public final boolean slide;

    /** Exactly one of {@code dial} and {@code edgewise} is used, the edgewise face when it is given. */
    public GaugeDial(Quantity quantity, GaugeFaceDefinition dial, EdgewiseFaceDefinition edgewise, String face,
            float[] uv, float needleZero, boolean reverse, boolean slide) {
        this.quantity = quantity == null ? Quantity.VOLTAGE : quantity;
        if (edgewise != null) {
            this.dial = null;
            this.edgewise = edgewise;
        } else {
            this.dial = dial != null ? dial : defaultDial();
            this.edgewise = null;
        }
        this.face = face == null || face.length() > MAX_FACE || face.indexOf('#') <= 0
                || face.indexOf('#') == face.length() - 1 ? "" : face;
        this.uv = sanitiseUv(uv);
        this.needleZero = Float.isFinite(needleZero) ? Mth.clamp(needleZero, -360, 360) : 0;
        this.reverse = reverse;
        this.slide = slide;
    }

    /** A fresh gauge: a voltmeter reading 0 to 24 V on the face of its kind, on no submesh yet. */
    public static GaugeDial defaults(boolean edgewise) {
        return edgewise
                ? new GaugeDial(Quantity.VOLTAGE, null, defaultEdgewise(), "", null, 0, false, true)
                : new GaugeDial(Quantity.VOLTAGE, defaultDial(), null, "", null, 0, false, true);
    }

    /** The 24 V Square voltmeter's scale. */
    static GaugeFaceDefinition defaultDial() {
        return GaugeFaceDefinition.builder(0, 24).units("V").majorTickInterval(4).minorTicksPerMajor(3).build();
    }

    /** Laid out as the edgewise voltmeters are. */
    static EdgewiseFaceDefinition defaultEdgewise() {
        return EdgewiseFaceDefinition.builder(0, 24).units("V").majorTickInterval(4).minorTicksPerMajor(3)
                .scale(0.05f, 0.86f).tickEdge(0.3f).band(0.3f, 0.12f).labelSize(0.24f).textSize(0.26f).build();
    }

    /**
     * This gauge with the face of the other kind, when it is asked for one it does
     * not have: the scale, units, colours and bands carry over, and everything else
     * is the other kind's default. Itself when it already has that face.
     */
    public GaugeDial withKind(boolean edgewise) {
        if (edgewise == isEdgewise())
            return this;
        if (edgewise) {
            GaugeFaceDefinition from = dial;
            EdgewiseFaceDefinition to = defaultEdgewise().toBuilder(from.minValue, from.maxValue)
                    .displayName(from.displayName).units(from.units).majorTickInterval(from.majorTickInterval)
                    .minorTicksPerMajor(from.minorTicksPerMajor).labelFormat(from.labelFormat)
                    .labelMultiplier(from.labelMultiplier).faceColor(from.faceColor)
                    .markingColor(from.markingColor).bands(from.bands).build();
            return new GaugeDial(quantity, null, to, face, uv, needleZero, reverse, slide);
        }
        EdgewiseFaceDefinition from = this.edgewise;
        GaugeFaceDefinition to = defaultDial().toBuilder(from.minValue, from.maxValue)
                .displayName(from.displayName).units(from.units).majorTickInterval(from.majorTickInterval)
                .minorTicksPerMajor(from.minorTicksPerMajor).labelFormat(from.labelFormat)
                .labelMultiplier(from.labelMultiplier).faceColor(from.faceColor).markingColor(from.markingColor)
                .bands(from.bands).build();
        return new GaugeDial(quantity, to, null, face, uv, needleZero, reverse, slide);
    }

    public boolean isEdgewise() {
        return edgewise != null;
    }

    /** Whether a submesh has been given the face. */
    public boolean hasFace() {
        return !face.isEmpty();
    }

    /** Whether the needle moves in a straight line: an edgewise one set to slide. A rotary needle always turns. */
    public boolean slides() {
        return isEdgewise() && slide;
    }

    public float[] uv() {
        return uv.clone();
    }

    // ------------------------------------------------------------- the scale

    public float minValue() {
        return isEdgewise() ? edgewise.minValue : dial.minValue;
    }

    public float maxValue() {
        return isEdgewise() ? edgewise.maxValue : dial.maxValue;
    }

    public String units() {
        return isEdgewise() ? edgewise.units : dial.units;
    }

    public float majorTickInterval() {
        return isEdgewise() ? edgewise.majorTickInterval : dial.majorTickInterval;
    }

    /** Identifies the printed face, for caching its texture. */
    public String faceCacheKey() {
        return isEdgewise() ? edgewise.cacheKey() : "rotary|" + dial.cacheKey();
    }

    /**
     * A reading as a fraction of the scale, a little past each end when it is off
     * the scale so the needle is seen to peg, the way the procedural gauges' is.
     */
    public float progress(float reading) {
        float span = maxValue() - minValue();
        if (span == 0 || !Float.isFinite(reading))
            return 0;
        float progress = (reading - minValue()) / span;
        if (progress > 1)
            return 1 + OVER;
        return Mth.clamp(progress, -UNDER, 1f);
    }

    /**
     * A rotary needle's two poses, at the minimum and at the maximum, as Euler
     * degrees about the vertical (see {@code DesignMotion}): the dial's angles, which
     * are clockwise seen from the front, less the angle the needle is modelled at.
     * Turning about +Y by a positive angle is counter-clockwise seen from the front,
     * hence the sign. Null on an edgewise gauge, whose poses are the design's own.
     */
    public float[][] rotaryPoses() {
        if (isEdgewise())
            return null;
        float sign = reverse ? 1 : -1;
        return new float[][] { { 0, sign * (dial.startAngle - needleZero), 0 },
                { 0, sign * (dial.endAngle - needleZero), 0 } };
    }

    /**
     * The material UV transform that stretches the face over {@link #uv}: the
     * shader samples {@code uv * tiling + offset}, so the rectangle's corners land on
     * the texture's. Returned as {tilingU, tilingV, offsetU, offsetV}.
     */
    public float[] uvTransform() {
        float du = uv[2] - uv[0], dv = uv[3] - uv[1];
        return new float[] { 1 / du, 1 / dv, -uv[0] / du, -uv[1] / dv };
    }

    /**
     * Why this gauge cannot be built, as a translation key under the workbench's
     * strings, or null when it can.
     */
    public String error() {
        float min = minValue(), max = maxValue(), interval = majorTickInterval();
        if (!(max > min))
            return "gauge.error.range";
        if (!(interval > 0) || (max - min) / interval > GaugeScale.MAX_MAJORS)
            return "gauge.error.ticks";
        return null;
    }

    // -------------------------------------------------------------------- nbt

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Quantity", quantity.id());
        if (isEdgewise())
            tag.put("Edgewise", edgewise.toNbt());
        else
            tag.put("Dial", dial.toNbt());
        if (hasFace())
            tag.putString("Face", face);
        ListTag list = new ListTag();
        for (float value : uv)
            list.add(FloatTag.valueOf(value));
        tag.put("Uv", list);
        tag.putFloat("NeedleZero", needleZero);
        tag.putBoolean("Reverse", reverse);
        tag.putBoolean("Slide", slide);
        return tag;
    }

    /**
     * Reads a gauge and makes it safe to use, whoever wrote it. {@code edgewise} says
     * which face the kind it was read for prints; a tag holding the other one, or
     * none, gets that kind's face carried over ({@link #withKind}) or its default.
     */
    public static GaugeDial read(CompoundTag tag, boolean edgewise) {
        GaugeDial base = faces(tag);
        float[] uv = null;
        ListTag list = tag.getList("Uv", Tag.TAG_FLOAT);
        if (list.size() == 4)
            uv = new float[] { list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3) };
        return new GaugeDial(base.quantity, base.dial, base.edgewise, tag.getString("Face"), uv,
                tag.getFloat("NeedleZero"), tag.getBoolean("Reverse"),
                !tag.contains("Slide", Tag.TAG_BYTE) || tag.getBoolean("Slide")).withKind(edgewise);
    }

    /**
     * Reads what is still meaningful of a gauge saved with a generated case (the
     * {@link #LEGACY_TAG} tag): what it measured and the face printed on it. The
     * case's size and colours have no place on an imported model and are dropped;
     * the case itself is the design's model, as it always was, but it has no face
     * submesh or needle until it is opened in the workbench and given them.
     */
    public static GaugeDial readLegacy(CompoundTag tag, boolean edgewise) {
        GaugeDial base = faces(tag);
        return new GaugeDial(base.quantity, base.dial, base.edgewise, "", null, 0, false, true).withKind(edgewise);
    }

    /** The quantity and whichever face a tag holds, the dial when it holds neither. */
    private static GaugeDial faces(CompoundTag tag) {
        Quantity quantity = Quantity.of(tag.getString("Quantity"));
        if (tag.contains("Edgewise", Tag.TAG_COMPOUND))
            return new GaugeDial(quantity, null, EdgewiseFaceDefinition.fromNbt(tag.getCompound("Edgewise")), "",
                    null, 0, false, true);
        GaugeFaceDefinition dial = tag.contains("Dial", Tag.TAG_COMPOUND)
                ? GaugeFaceDefinition.fromNbt(tag.getCompound("Dial"))
                : defaultDial();
        return new GaugeDial(quantity, dial, null, "", null, 0, false, true);
    }

    /**
     * A UV rectangle held to something a material can be made from: finite, ordered,
     * not degenerate, and within a few texture squares of the origin. The whole
     * square when there is nothing usable.
     */
    static float[] sanitiseUv(float[] uv) {
        if (uv == null || uv.length != 4)
            return new float[] { 0, 0, 1, 1 };
        float u0 = clampUv(Math.min(uv[0], uv[2])), u1 = clampUv(Math.max(uv[0], uv[2]));
        float v0 = clampUv(Math.min(uv[1], uv[3])), v1 = clampUv(Math.max(uv[1], uv[3]));
        if (!(u1 - u0 >= 1e-4f) || !(v1 - v0 >= 1e-4f))
            return new float[] { 0, 0, 1, 1 };
        return new float[] { u0, v0, u1, v1 };
    }

    /** NaN stays NaN, so the rectangle it is in is refused. */
    private static float clampUv(float value) {
        return Float.isNaN(value) ? value : Mth.clamp(value, -64, 64);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GaugeDial dial && write().equals(dial.write());
    }

    @Override
    public int hashCode() {
        return write().hashCode();
    }
}
