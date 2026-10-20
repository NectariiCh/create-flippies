package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * Data-driven description of an edgewise (vertical bar) gauge face: a tall, narrow
 * face with horizontal ticks down a vertical scale, the labels beside them, the name
 * above (or stacked down the side behind the spine) and the units below, and colour
 * bands as a vertical strip under the ticks. Java port of the Unity
 * BarGaugeDefinition, with the scale fields it inherits from GaugeDefinition.
 *
 * The client-side baker (client/EdgewiseFaceBaker) rasters this through
 * {@link EdgewiseLayout}; the renderer moves the pointer through
 * {@link #progressToFraction}, so the pointer and the printed ticks agree.
 *
 * Face coordinates: fractions of the face, 0 = top, 1 = bottom down the face, and
 * 0 = left, 1 = right across it. The pointer comes in from the left edge to the
 * ticks' spine-side edge; {@link #flipTicks} mirrors every horizontal position,
 * pointer included. Horizontal lengths and sizes are fractions of the face WIDTH,
 * as in the Unity definition. Colours are packed ARGB.
 *
 * What the Unity definition held about its mesh is not here: the face's UV rect
 * and flips, and the arc the curved face spans around the pointer's pivot. Those
 * only mapped a baked texture onto one particular curved fbx face and turned a
 * fraction of it into a pointer angle; here the face is flat and the pointer slides
 * straight along it, which is what the curved face was built to look like.
 *
 * Safe to use on both logical sides (pure data).
 */
public final class EdgewiseFaceDefinition {

    public static final int MAX_BANDS = GaugeFaceDefinition.MAX_BANDS;

    /** How the reading is shown: a thin pointer across the ticks, or a bar filled up to it. */
    public enum PointerStyle {
        NEEDLE("needle"), BAR("bar");

        private final String id;

        PointerStyle(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public PointerStyle next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static PointerStyle of(String id) {
            for (PointerStyle style : values())
                if (style.id.equals(id))
                    return style;
            return NEEDLE;
        }
    }

    // Identity
    public final String displayName;

    // Scale
    public final float minValue;
    public final float maxValue;
    public final String units;
    public final float majorTickInterval;
    public final int minorTicksPerMajor;
    public final String labelFormat;
    public final float labelMultiplier;

    // Color bands, as a vertical strip under the ticks
    public final GaugeFaceDefinition.ColorBand[] bands;

    // Face style
    public final int faceColor;
    public final int markingColor;
    public final boolean drawDisplayName;

    // Face geometry
    /** Face height / width, so text is not stretched. */
    public final float faceAspect;

    // Scale layout, fractions of the face
    /** Where maxValue sits, from the top of the face. */
    public final float scaleTop;
    /** Where minValue sits, from the top of the face. */
    public final float scaleBottom;
    /** Mirror the ticks, spine, bands, side name, labels and pointer left/right. */
    public final boolean flipTicks;
    /** Spine-side edge of the ticks, from the left. */
    public final float tickEdge;
    public final float majorTickLength;
    public final float minorTickLength;
    public final float majorTickWidth;
    public final float minorTickWidth;
    /** Vertical line joining the ticks along their spine-side edge, growing away from them. 0 = none. */
    public final float spineWidth;
    /** Run the spine the full height of the face instead of stopping at the end ticks. */
    public final boolean spineFullHeight;
    /** Gap between the end of the major ticks and the near edge of the labels. */
    public final float labelGap;
    /** Label height. */
    public final float labelSize;
    /** Display name / units height; shrinks to fit when the text is too wide. */
    public final float textSize;
    /** Print the name as upright letters stacked down the space behind the spine. */
    public final boolean nameOnSide;
    /** Clear space kept on each side of the side name. */
    public final float sideNameMargin;
    /** Where the colour band strip starts, from the left, and how wide it is. */
    public final float bandLeft;
    public final float bandWidth;

    // Pointer, which is drawn over the face rather than printed on it
    public final int pointerColor;
    public final PointerStyle pointerStyle;
    /** Pointer thickness, as a fraction of the face width. */
    public final float pointerWidth;

    // Baking
    /** Texture height in pixels; the width follows from {@link #faceAspect}. */
    public final int bakeResolution;

    private EdgewiseFaceDefinition(Builder b) {
        displayName = b.displayName;
        minValue = b.minValue;
        maxValue = b.maxValue;
        units = b.units;
        majorTickInterval = b.majorTickInterval;
        minorTicksPerMajor = b.minorTicksPerMajor;
        labelFormat = b.labelFormat;
        labelMultiplier = b.labelMultiplier;
        bands = b.bands;
        faceColor = b.faceColor;
        markingColor = b.markingColor;
        drawDisplayName = b.drawDisplayName;
        faceAspect = b.faceAspect;
        scaleTop = b.scaleTop;
        scaleBottom = b.scaleBottom;
        flipTicks = b.flipTicks;
        tickEdge = b.tickEdge;
        majorTickLength = b.majorTickLength;
        minorTickLength = b.minorTickLength;
        majorTickWidth = b.majorTickWidth;
        minorTickWidth = b.minorTickWidth;
        spineWidth = b.spineWidth;
        spineFullHeight = b.spineFullHeight;
        labelGap = b.labelGap;
        labelSize = b.labelSize;
        textSize = b.textSize;
        nameOnSide = b.nameOnSide;
        sideNameMargin = b.sideNameMargin;
        bandLeft = b.bandLeft;
        bandWidth = b.bandWidth;
        pointerColor = b.pointerColor;
        pointerStyle = b.pointerStyle;
        pointerWidth = b.pointerWidth;
        bakeResolution = b.bakeResolution;
    }

    /** Value -> 0..1 progress along the scale. Clamped. */
    public float normalize(float value) {
        float span = maxValue - minValue;
        if (span == 0 || !Float.isFinite(value))
            return 0;
        return Mth.clamp((value - minValue) / span, 0, 1);
    }

    /** Value -> fraction down the face (linear, clamped). The Unity ValueToFraction. */
    public float valueToFraction(float value) {
        return progressToFraction(normalize(value));
    }

    /** Progress (may slightly exceed 0..1 for overswing) -> fraction down the face. */
    public float progressToFraction(float progress) {
        return scaleBottom + (scaleTop - scaleBottom) * progress;
    }

    /** A horizontal face fraction as it lands with {@link #flipTicks} applied. */
    public float x(float fraction) {
        return flipTicks ? 1 - fraction : fraction;
    }

    /** Label decimal places implied by the DecimalFormat pattern. */
    public int labelDecimals() {
        int dot = labelFormat.indexOf('.');
        return dot < 0 ? 0 : labelFormat.length() - dot - 1;
    }

    /** Uniquely identifies the printed face; used as the texture cache key. The pointer is not printed. */
    public String cacheKey() {
        StringBuilder sb = new StringBuilder(192);
        sb.append("edgewise|").append(displayName).append('|').append(minValue).append('|').append(maxValue)
                .append('|').append(units).append('|').append(majorTickInterval).append('|')
                .append(minorTicksPerMajor).append('|').append(labelFormat).append('|').append(labelMultiplier)
                .append('|').append(Integer.toHexString(faceColor)).append('|')
                .append(Integer.toHexString(markingColor)).append('|').append(drawDisplayName).append('|')
                .append(faceAspect).append('|').append(scaleTop).append('|').append(scaleBottom).append('|')
                .append(flipTicks).append('|').append(tickEdge).append('|').append(majorTickLength).append('|')
                .append(minorTickLength).append('|').append(majorTickWidth).append('|').append(minorTickWidth)
                .append('|').append(spineWidth).append('|').append(spineFullHeight).append('|').append(labelGap)
                .append('|').append(labelSize).append('|').append(textSize).append('|').append(nameOnSide)
                .append('|').append(sideNameMargin).append('|').append(bandLeft).append('|').append(bandWidth)
                .append('|').append(bakeResolution);
        for (GaugeFaceDefinition.ColorBand band : bands)
            sb.append('|').append(band.fromValue()).append(',').append(band.toValue()).append(',')
                    .append(Integer.toHexString(band.argb()));
        return sb.toString();
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", displayName);
        tag.putFloat("Min", minValue);
        tag.putFloat("Max", maxValue);
        tag.putString("Units", units);
        tag.putFloat("MajorTick", majorTickInterval);
        tag.putInt("MinorTicks", minorTicksPerMajor);
        tag.putString("LabelFormat", labelFormat);
        tag.putFloat("LabelMultiplier", labelMultiplier);
        tag.putInt("FaceColor", faceColor);
        tag.putInt("MarkingColor", markingColor);
        tag.putBoolean("DrawName", drawDisplayName);
        tag.putFloat("Aspect", faceAspect);
        tag.putFloat("ScaleTop", scaleTop);
        tag.putFloat("ScaleBottom", scaleBottom);
        tag.putBoolean("Flip", flipTicks);
        tag.putFloat("TickEdge", tickEdge);
        tag.putFloat("MajorLength", majorTickLength);
        tag.putFloat("MinorLength", minorTickLength);
        tag.putFloat("MajorWidth", majorTickWidth);
        tag.putFloat("MinorWidth", minorTickWidth);
        tag.putFloat("Spine", spineWidth);
        tag.putBoolean("SpineFull", spineFullHeight);
        tag.putFloat("LabelGap", labelGap);
        tag.putFloat("LabelSize", labelSize);
        tag.putFloat("TextSize", textSize);
        tag.putBoolean("NameOnSide", nameOnSide);
        tag.putFloat("SideMargin", sideNameMargin);
        tag.putFloat("BandLeft", bandLeft);
        tag.putFloat("BandWidth", bandWidth);
        tag.putInt("PointerColor", pointerColor);
        tag.putString("Pointer", pointerStyle.id());
        tag.putFloat("PointerWidth", pointerWidth);
        tag.putInt("Resolution", bakeResolution);
        ListTag bandList = new ListTag();
        for (GaugeFaceDefinition.ColorBand band : bands) {
            CompoundTag bandTag = new CompoundTag();
            bandTag.putFloat("From", band.fromValue());
            bandTag.putFloat("To", band.toValue());
            bandTag.putInt("Color", band.argb());
            bandList.add(bandTag);
        }
        tag.put("Bands", bandList);
        return tag;
    }

    /**
     * Reads and sanitises a face, whoever wrote it. A value the tag does not have is
     * the builder's default, and everything goes through the builder's clamps.
     */
    public static EdgewiseFaceDefinition fromNbt(CompoundTag tag) {
        Builder d = builder(0, 100);
        Builder builder = builder(f(tag, "Min", 0), f(tag, "Max", 100))
                .displayName(tag.getString("Name"))
                .units(tag.getString("Units"))
                .majorTickInterval(f(tag, "MajorTick", d.majorTickInterval))
                .minorTicksPerMajor(tag.contains("MinorTicks", Tag.TAG_INT) ? tag.getInt("MinorTicks")
                        : d.minorTicksPerMajor)
                .labelFormat(tag.getString("LabelFormat"))
                .labelMultiplier(f(tag, "LabelMultiplier", 1))
                .faceColor(i(tag, "FaceColor", d.faceColor))
                .markingColor(i(tag, "MarkingColor", d.markingColor))
                .drawDisplayName(!tag.contains("DrawName") || tag.getBoolean("DrawName"))
                .faceAspect(f(tag, "Aspect", d.faceAspect))
                .scale(f(tag, "ScaleTop", d.scaleTop), f(tag, "ScaleBottom", d.scaleBottom))
                .flipTicks(tag.getBoolean("Flip"))
                .tickEdge(f(tag, "TickEdge", d.tickEdge))
                .tickLengths(f(tag, "MajorLength", d.majorTickLength), f(tag, "MinorLength", d.minorTickLength))
                .tickWidths(f(tag, "MajorWidth", d.majorTickWidth), f(tag, "MinorWidth", d.minorTickWidth))
                .spine(f(tag, "Spine", d.spineWidth), tag.getBoolean("SpineFull"))
                .labelGap(f(tag, "LabelGap", d.labelGap))
                .labelSize(f(tag, "LabelSize", d.labelSize))
                .textSize(f(tag, "TextSize", d.textSize))
                .nameOnSide(tag.getBoolean("NameOnSide"))
                .sideNameMargin(f(tag, "SideMargin", d.sideNameMargin))
                .band(f(tag, "BandLeft", d.bandLeft), f(tag, "BandWidth", d.bandWidth))
                .pointerColor(i(tag, "PointerColor", d.pointerColor))
                .pointerStyle(PointerStyle.of(tag.getString("Pointer")))
                .pointerWidth(f(tag, "PointerWidth", d.pointerWidth))
                .bakeResolution(tag.contains("Resolution", Tag.TAG_INT) ? tag.getInt("Resolution")
                        : d.bakeResolution);
        ListTag bandList = tag.getList("Bands", Tag.TAG_COMPOUND);
        GaugeFaceDefinition.ColorBand[] bands = new GaugeFaceDefinition.ColorBand[Math.min(bandList.size(),
                MAX_BANDS)];
        for (int index = 0; index < bands.length; index++) {
            CompoundTag bandTag = bandList.getCompound(index);
            bands[index] = new GaugeFaceDefinition.ColorBand(sane(bandTag.getFloat("From"), 0),
                    sane(bandTag.getFloat("To"), 0), bandTag.getInt("Color") | 0xFF000000);
        }
        return builder.bands(bands).build();
    }

    private static float f(CompoundTag tag, String key, float fallback) {
        return tag.contains(key, Tag.TAG_FLOAT) ? sane(tag.getFloat(key), fallback) : fallback;
    }

    private static int i(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : fallback;
    }

    private static float sane(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    public static Builder builder(float minValue, float maxValue) {
        return new Builder(minValue, maxValue);
    }

    /** Builder pre-loaded with this definition's values, for editing. */
    public Builder toBuilder() {
        return toBuilder(minValue, maxValue);
    }

    /** Builder pre-loaded with this definition's style but a new scale. */
    public Builder toBuilder(float newMin, float newMax) {
        Builder b = new Builder(newMin, newMax);
        b.displayName = displayName;
        b.units = units;
        b.majorTickInterval = majorTickInterval;
        b.minorTicksPerMajor = minorTicksPerMajor;
        b.labelFormat = labelFormat;
        b.labelMultiplier = labelMultiplier;
        b.bands = bands;
        b.faceColor = faceColor;
        b.markingColor = markingColor;
        b.drawDisplayName = drawDisplayName;
        b.faceAspect = faceAspect;
        b.scaleTop = scaleTop;
        b.scaleBottom = scaleBottom;
        b.flipTicks = flipTicks;
        b.tickEdge = tickEdge;
        b.majorTickLength = majorTickLength;
        b.minorTickLength = minorTickLength;
        b.majorTickWidth = majorTickWidth;
        b.minorTickWidth = minorTickWidth;
        b.spineWidth = spineWidth;
        b.spineFullHeight = spineFullHeight;
        b.labelGap = labelGap;
        b.labelSize = labelSize;
        b.textSize = textSize;
        b.nameOnSide = nameOnSide;
        b.sideNameMargin = sideNameMargin;
        b.bandLeft = bandLeft;
        b.bandWidth = bandWidth;
        b.pointerColor = pointerColor;
        b.pointerStyle = pointerStyle;
        b.pointerWidth = pointerWidth;
        b.bakeResolution = bakeResolution;
        return b;
    }

    /**
     * A builder whose tick spacing and label precision are picked for the range, the
     * way {@link GaugeFaceDefinition#autoScaled} picks them for a dial.
     */
    public static Builder autoScaled(float minValue, float maxValue) {
        GaugeFaceDefinition picked = GaugeFaceDefinition.autoScaled(minValue, maxValue).build();
        return builder(minValue, maxValue).majorTickInterval(picked.majorTickInterval)
                .minorTicksPerMajor(picked.minorTicksPerMajor).labelFormat(picked.labelFormat);
    }

    /**
     * Defaults are the Unity definition's own, apart from the face aspect, which is
     * the edgewise voltmeter's plate rather than the fbx face, and a scale that
     * leaves room above and below it for the name and units.
     */
    public static final class Builder {
        private String displayName = "";
        private final float minValue;
        private final float maxValue;
        private String units = "";
        private float majorTickInterval = 10;
        private int minorTicksPerMajor = 4;
        private String labelFormat = "0";
        private float labelMultiplier = 1;
        private GaugeFaceDefinition.ColorBand[] bands = new GaugeFaceDefinition.ColorBand[0];
        private int faceColor = 0xFFEDEDE5;
        private int markingColor = 0xFF000000;
        private boolean drawDisplayName = true;
        private float faceAspect = 4;
        private float scaleTop = 0.07f;
        private float scaleBottom = 0.93f;
        private boolean flipTicks = false;
        private float tickEdge = 0.25f;
        private float majorTickLength = 0.22f;
        private float minorTickLength = 0.12f;
        private float majorTickWidth = 0.022f;
        private float minorTickWidth = 0.011f;
        private float spineWidth = 0.015f;
        private boolean spineFullHeight = false;
        private float labelGap = 0.03f;
        private float labelSize = 0.2f;
        private float textSize = 0.17f;
        private boolean nameOnSide = false;
        private float sideNameMargin = 0.01f;
        private float bandLeft = 0.25f;
        private float bandWidth = 0.1f;
        private int pointerColor = 0xFFD03018;
        private PointerStyle pointerStyle = PointerStyle.NEEDLE;
        private float pointerWidth = 0.06f;
        private int bakeResolution = 256;

        private Builder(float minValue, float maxValue) {
            this.minValue = Float.isFinite(minValue) ? minValue : 0;
            this.maxValue = Float.isFinite(maxValue) ? maxValue : 100;
        }

        public Builder displayName(String value) { displayName = sanitize(value); return this; }
        public Builder units(String value) { units = sanitize(value); return this; }
        public Builder majorTickInterval(float value) { majorTickInterval = Float.isFinite(value) ? value : 10; return this; }
        public Builder minorTicksPerMajor(int value) { minorTicksPerMajor = Mth.clamp(value, 0, GaugeScale.MAX_MINORS); return this; }
        public Builder labelFormat(String value) { labelFormat = sanitizeFormat(value); return this; }
        public Builder labelMultiplier(float value) { labelMultiplier = Float.isFinite(value) && value != 0 ? value : 1; return this; }
        public Builder bands(GaugeFaceDefinition.ColorBand... value) { bands = value == null ? new GaugeFaceDefinition.ColorBand[0] : value; return this; }
        public Builder faceColor(int argb) { faceColor = argb | 0xFF000000; return this; }
        public Builder markingColor(int argb) { markingColor = argb | 0xFF000000; return this; }
        public Builder drawDisplayName(boolean value) { drawDisplayName = value; return this; }
        public Builder faceAspect(float value) { faceAspect = clamp(value, 0.25f, 16, 4); return this; }
        public Builder flipTicks(boolean value) { flipTicks = value; return this; }
        public Builder tickEdge(float value) { tickEdge = fraction(value, 0.25f); return this; }
        public Builder labelGap(float value) { labelGap = fraction(value, 0.03f); return this; }
        public Builder labelSize(float value) { labelSize = clamp(value, 0.01f, 1, 0.2f); return this; }
        public Builder textSize(float value) { textSize = clamp(value, 0.01f, 1, 0.17f); return this; }
        public Builder nameOnSide(boolean value) { nameOnSide = value; return this; }
        public Builder sideNameMargin(float value) { sideNameMargin = fraction(value, 0.01f); return this; }
        public Builder pointerColor(int argb) { pointerColor = argb | 0xFF000000; return this; }
        public Builder pointerStyle(PointerStyle value) { pointerStyle = value == null ? PointerStyle.NEEDLE : value; return this; }
        public Builder pointerWidth(float value) { pointerWidth = clamp(value, 0.005f, 0.5f, 0.06f); return this; }
        public Builder bakeResolution(int value) { bakeResolution = Mth.clamp(value, 64, 1024); return this; }

        /** Where maxValue and minValue sit, from the top; held in order, max above min. */
        public Builder scale(float top, float bottom) {
            float a = fraction(top, 0.07f), b = fraction(bottom, 0.93f);
            scaleTop = Math.min(a, b);
            scaleBottom = Math.max(a, b);
            return this;
        }

        public Builder tickLengths(float major, float minor) {
            majorTickLength = fraction(major, 0.22f);
            minorTickLength = fraction(minor, 0.12f);
            return this;
        }

        public Builder tickWidths(float major, float minor) {
            majorTickWidth = clamp(major, 0.001f, 0.5f, 0.022f);
            minorTickWidth = clamp(minor, 0.001f, 0.5f, 0.011f);
            return this;
        }

        public Builder spine(float width, boolean fullHeight) {
            spineWidth = fraction(width, 0.015f);
            spineFullHeight = fullHeight;
            return this;
        }

        public Builder band(float left, float width) {
            bandLeft = fraction(left, 0.25f);
            bandWidth = fraction(width, 0.1f);
            return this;
        }

        public EdgewiseFaceDefinition build() {
            return new EdgewiseFaceDefinition(this);
        }

        private static float fraction(float value, float fallback) {
            return clamp(value, 0, 1, fallback);
        }

        private static float clamp(float value, float min, float max, float fallback) {
            return Float.isFinite(value) ? Mth.clamp(value, min, max) : fallback;
        }

        private static String sanitize(String value) {
            if (value == null)
                return "";
            String cleaned = value.replaceAll("[\\p{Cntrl}|]", "").trim();
            return cleaned.length() > 24 ? cleaned.substring(0, 24) : cleaned;
        }

        private static String sanitizeFormat(String format) {
            return switch (format == null ? "" : format) {
                case "0", "0.0", "0.00", "0.000" -> format;
                default -> "0";
            };
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "EdgewiseFaceDefinition[%s..%s %s]", minValue, maxValue, units);
    }
}
