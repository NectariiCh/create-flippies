package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * Data-driven description of an analog gauge face: scale, sweep, ticks, color
 * bands and styling. Java port of the Unity GaugeDefinition ScriptableObject.
 *
 * The client-side baker (client/GaugeFaceBaker) turns this into a dial-face
 * texture at runtime; the renderer maps values through {@link #valueToAngle},
 * so the needle and the printed markings can never disagree about the scale.
 *
 * Angle convention: degrees, 0 = 12 o'clock, positive = clockwise when looking
 * at the dial. A typical 270-degree gauge runs -135 .. +135.
 *
 * Colors are packed ARGB. All radii/lengths/sizes are fractions of the face
 * radius. This class is safe to use on both logical sides (pure data).
 */
public final class GaugeFaceDefinition {

    public static final int MAX_BANDS = 4;

    /** Colored arc along the scale (normal / caution / danger zone), in scale values. */
    public record ColorBand(float fromValue, float toValue, int argb) {}

    // Identity
    public final String displayName;

    // Scale
    public final float minValue;
    public final float maxValue;
    /** Printed below the centre of the dial, e.g. "V" or "x100 RPM". */
    public final String units;
    /** Value step between numbered (major) ticks. */
    public final float majorTickInterval;
    /** Number of minor ticks between two major ticks. */
    public final int minorTicksPerMajor;
    /** DecimalFormat pattern for tick labels, e.g. "0" or "0.0". */
    public final String labelFormat;
    /** Labels show value * multiplier — for "x1000" style dials. */
    public final float labelMultiplier;

    // Sweep
    /** Needle angle at minValue. 0 = 12 o'clock, clockwise positive. */
    public final float startAngle;
    /** Needle angle at maxValue. */
    public final float endAngle;

    // Color bands
    public final ColorBand[] bands;

    // Face style
    public final int faceColor;
    public final int markingColor;
    public final float tickOuterRadius;
    public final float majorTickLength;
    public final float minorTickLength;
    public final float majorTickWidth;
    public final float minorTickWidth;
    /** Radius the number labels are centred on. */
    public final float labelRadius;
    public final float labelSize;
    /** Size of the display name / units text. */
    public final float textSize;
    /** Color bands run between these two radii (default: just outside the ticks). */
    public final float bandOuterRadius;
    public final float bandInnerRadius;
    /** Print the display name on the upper half of the face. */
    public final boolean drawDisplayName;

    // Baking
    public final int bakeResolution;

    private GaugeFaceDefinition(Builder b) {
        displayName = b.displayName;
        minValue = b.minValue;
        maxValue = b.maxValue;
        units = b.units;
        majorTickInterval = b.majorTickInterval;
        minorTicksPerMajor = b.minorTicksPerMajor;
        labelFormat = b.labelFormat;
        labelMultiplier = b.labelMultiplier;
        startAngle = b.startAngle;
        endAngle = b.endAngle;
        bands = b.bands;
        faceColor = b.faceColor;
        markingColor = b.markingColor;
        tickOuterRadius = b.tickOuterRadius;
        majorTickLength = b.majorTickLength;
        minorTickLength = b.minorTickLength;
        majorTickWidth = b.majorTickWidth;
        minorTickWidth = b.minorTickWidth;
        labelRadius = b.labelRadius;
        labelSize = b.labelSize;
        textSize = b.textSize;
        bandOuterRadius = b.bandOuterRadius;
        bandInnerRadius = b.bandInnerRadius;
        drawDisplayName = b.drawDisplayName;
        bakeResolution = b.bakeResolution;
    }

    /** Value -> needle angle (degrees, 0 = 12 o'clock, clockwise). Clamped. */
    public float valueToAngle(float value) {
        return Mth.lerp(normalize(value), startAngle, endAngle);
    }

    /** Value -> 0..1 progress along the scale. Clamped. */
    public float normalize(float value) {
        float span = maxValue - minValue;
        if (span == 0)
            return 0;
        return Mth.clamp((value - minValue) / span, 0, 1);
    }

    /** Progress (may slightly exceed 0..1 for overswing) -> needle angle in degrees. */
    public float progressToAngle(float progress) {
        return startAngle + (endAngle - startAngle) * progress;
    }

    /** Label decimal places implied by the DecimalFormat pattern. */
    public int labelDecimals() {
        int dot = labelFormat.indexOf('.');
        return dot < 0 ? 0 : labelFormat.length() - dot - 1;
    }

    /** DecimalFormat pattern printing {@code decimals} places. */
    public static String formatForDecimals(int decimals) {
        StringBuilder sb = new StringBuilder("0");
        if (decimals > 0)
            sb.append('.');
        for (int i = 0; i < decimals; i++)
            sb.append('0');
        return sb.toString();
    }

    /** Uniquely identifies the rendered output; used as the texture cache key. */
    public String cacheKey() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(displayName).append('|').append(minValue).append('|').append(maxValue).append('|')
                .append(units).append('|').append(majorTickInterval).append('|').append(minorTicksPerMajor)
                .append('|').append(labelFormat).append('|').append(labelMultiplier).append('|')
                .append(startAngle).append('|').append(endAngle).append('|')
                .append(Integer.toHexString(faceColor)).append('|').append(Integer.toHexString(markingColor))
                .append('|').append(tickOuterRadius).append('|').append(majorTickLength).append('|')
                .append(minorTickLength).append('|').append(majorTickWidth).append('|').append(minorTickWidth)
                .append('|').append(labelRadius).append('|').append(labelSize).append('|').append(textSize)
                .append('|').append(bandOuterRadius).append('|').append(bandInnerRadius).append('|')
                .append(drawDisplayName).append('|').append(bakeResolution);
        for (ColorBand band : bands)
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
        tag.putFloat("StartAngle", startAngle);
        tag.putFloat("EndAngle", endAngle);
        tag.putInt("FaceColor", faceColor);
        tag.putInt("MarkingColor", markingColor);
        tag.putBoolean("DrawName", drawDisplayName);
        ListTag bandList = new ListTag();
        for (ColorBand band : bands) {
            CompoundTag bandTag = new CompoundTag();
            bandTag.putFloat("From", band.fromValue());
            bandTag.putFloat("To", band.toValue());
            bandTag.putInt("Color", band.argb());
            bandList.add(bandTag);
        }
        tag.put("Bands", bandList);
        return tag;
    }

    public static GaugeFaceDefinition fromNbt(CompoundTag tag) {
        Builder builder = builder(sane(tag.getFloat("Min"), 0), sane(tag.getFloat("Max"), 100));
        builder.displayName(tag.getString("Name"))
                .units(tag.getString("Units"))
                .majorTickInterval(sane(tag.getFloat("MajorTick"), 10))
                .minorTicksPerMajor(Mth.clamp(tag.getInt("MinorTicks"), 0, 20))
                .labelFormat(sanitizeFormat(tag.getString("LabelFormat")))
                .labelMultiplier(sane(tag.getFloat("LabelMultiplier"), 1))
                .sweep(Mth.clamp(sane(tag.getFloat("StartAngle"), -135), -330, 330),
                        Mth.clamp(sane(tag.getFloat("EndAngle"), 135), -330, 330))
                .faceColor(tag.getInt("FaceColor") | 0xFF000000)
                .markingColor(tag.getInt("MarkingColor") | 0xFF000000)
                .drawDisplayName(tag.getBoolean("DrawName"));
        ListTag bandList = tag.getList("Bands", Tag.TAG_COMPOUND);
        ColorBand[] bands = new ColorBand[Math.min(bandList.size(), MAX_BANDS)];
        for (int i = 0; i < bands.length; i++) {
            CompoundTag bandTag = bandList.getCompound(i);
            bands[i] = new ColorBand(sane(bandTag.getFloat("From"), 0), sane(bandTag.getFloat("To"), 0),
                    bandTag.getInt("Color") | 0xFF000000);
        }
        builder.bands(bands);
        return builder.build();
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(displayName, 24);
        buffer.writeFloat(minValue);
        buffer.writeFloat(maxValue);
        buffer.writeUtf(units, 24);
        buffer.writeFloat(majorTickInterval);
        buffer.writeVarInt(minorTicksPerMajor);
        buffer.writeVarInt(labelDecimals());
        buffer.writeFloat(labelMultiplier);
        buffer.writeFloat(startAngle);
        buffer.writeFloat(endAngle);
        buffer.writeInt(faceColor);
        buffer.writeInt(markingColor);
        buffer.writeBoolean(drawDisplayName);
        buffer.writeVarInt(Math.min(bands.length, MAX_BANDS));
        for (int i = 0; i < Math.min(bands.length, MAX_BANDS); i++) {
            buffer.writeFloat(bands[i].fromValue());
            buffer.writeFloat(bands[i].toValue());
            buffer.writeInt(bands[i].argb());
        }
    }

    /** Reads and sanitizes a definition sent by a client. */
    public static GaugeFaceDefinition read(FriendlyByteBuf buffer) {
        String name = buffer.readUtf(24);
        float min = sane(buffer.readFloat(), 0);
        float max = sane(buffer.readFloat(), 100);
        String units = buffer.readUtf(24);
        float majorTick = sane(buffer.readFloat(), 10);
        int minorTicks = Mth.clamp(buffer.readVarInt(), 0, 20);
        int decimals = Mth.clamp(buffer.readVarInt(), 0, 3);
        float multiplier = sane(buffer.readFloat(), 1);
        float startAngle = Mth.clamp(sane(buffer.readFloat(), -135), -330, 330);
        float endAngle = Mth.clamp(sane(buffer.readFloat(), 135), -330, 330);
        int faceColor = buffer.readInt() | 0xFF000000;
        int markingColor = buffer.readInt() | 0xFF000000;
        boolean drawName = buffer.readBoolean();
        int bandCount = Mth.clamp(buffer.readVarInt(), 0, MAX_BANDS);
        ColorBand[] bands = new ColorBand[bandCount];
        for (int i = 0; i < bandCount; i++)
            bands[i] = new ColorBand(sane(buffer.readFloat(), 0), sane(buffer.readFloat(), 0),
                    buffer.readInt() | 0xFF000000);
        return builder(min, max)
                .displayName(name)
                .units(units)
                .majorTickInterval(majorTick)
                .minorTicksPerMajor(minorTicks)
                .labelFormat(formatForDecimals(decimals))
                .labelMultiplier(multiplier)
                .sweep(startAngle, endAngle)
                .faceColor(faceColor)
                .markingColor(markingColor)
                .drawDisplayName(drawName)
                .bands(bands)
                .build();
    }

    private static float sane(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static String sanitizeFormat(String format) {
        return switch (format) {
            case "0", "0.0", "0.00", "0.000" -> format;
            default -> "0";
        };
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
        Builder builder = new Builder(newMin, newMax);
        builder.displayName = displayName;
        builder.units = units;
        builder.majorTickInterval = majorTickInterval;
        builder.minorTicksPerMajor = minorTicksPerMajor;
        builder.labelFormat = labelFormat;
        builder.labelMultiplier = labelMultiplier;
        builder.startAngle = startAngle;
        builder.endAngle = endAngle;
        builder.bands = bands;
        builder.faceColor = faceColor;
        builder.markingColor = markingColor;
        builder.tickOuterRadius = tickOuterRadius;
        builder.majorTickLength = majorTickLength;
        builder.minorTickLength = minorTickLength;
        builder.majorTickWidth = majorTickWidth;
        builder.minorTickWidth = minorTickWidth;
        builder.labelRadius = labelRadius;
        builder.labelSize = labelSize;
        builder.textSize = textSize;
        builder.bandOuterRadius = bandOuterRadius;
        builder.bandInnerRadius = bandInnerRadius;
        builder.drawDisplayName = drawDisplayName;
        builder.bakeResolution = bakeResolution;
        return builder;
    }

    /**
     * Picks pleasant tick spacing and label precision for an arbitrary user range
     * ("nice numbers": major step is 1/2/2.5/5 x 10^k, about 5 major divisions).
     */
    public static Builder autoScaled(float minValue, float maxValue) {
        Builder builder = new Builder(minValue, maxValue);
        float span = Math.abs(maxValue - minValue);
        if (span <= 0 || !Float.isFinite(span)) {
            builder.majorTickInterval(1).minorTicksPerMajor(4).labelFormat("0");
            return builder;
        }
        double raw = span / 5.0;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double normalized = raw / magnitude;
        double step;
        int minorPer;
        if (normalized < 1.5) {
            step = magnitude;
            minorPer = 4;
        } else if (normalized < 2.25) {
            step = 2 * magnitude;
            minorPer = 3;
        } else if (normalized < 3.5) {
            step = 2.5 * magnitude;
            minorPer = 4;
        } else if (normalized < 7) {
            step = 5 * magnitude;
            minorPer = 4;
        } else {
            step = 10 * magnitude;
            minorPer = 4;
        }
        String format = step >= 1 ? "0" : step >= 0.1 ? "0.0" : step >= 0.01 ? "0.00" : "0.000";
        builder.majorTickInterval((float) step).minorTicksPerMajor(minorPer).labelFormat(format);
        return builder;
    }

    public static final class Builder {
        private String displayName = "";
        private final float minValue;
        private final float maxValue;
        private String units = "";
        private float majorTickInterval = 10;
        private int minorTicksPerMajor = 4;
        private String labelFormat = "0";
        private float labelMultiplier = 1;
        private float startAngle = -135;
        private float endAngle = 135;
        private ColorBand[] bands = new ColorBand[0];
        private int faceColor = 0xFFEDEDE5;
        private int markingColor = 0xFF000000;
        private float tickOuterRadius = 0.90f;
        private float majorTickLength = 0.14f;
        private float minorTickLength = 0.08f;
        private float majorTickWidth = 0.022f;
        private float minorTickWidth = 0.010f;
        private float labelRadius = 0.62f;
        private float labelSize = 0.12f;
        private float textSize = 0.10f;
        private float bandOuterRadius = 0.965f;
        private float bandInnerRadius = 0.905f;
        private boolean drawDisplayName = true;
        private int bakeResolution = 256;

        private Builder(float minValue, float maxValue) {
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        public Builder displayName(String value) { displayName = sanitize(value); return this; }
        public Builder units(String value) { units = sanitize(value); return this; }
        public Builder majorTickInterval(float value) { majorTickInterval = value; return this; }
        public Builder minorTicksPerMajor(int value) { minorTicksPerMajor = value; return this; }
        public Builder labelFormat(String value) { labelFormat = sanitizeFormat(value); return this; }
        public Builder labelMultiplier(float value) { labelMultiplier = value; return this; }
        public Builder sweep(float start, float end) { startAngle = start; endAngle = end; return this; }
        public Builder bands(ColorBand... value) { bands = value; return this; }
        public Builder faceColor(int argb) { faceColor = argb; return this; }
        public Builder markingColor(int argb) { markingColor = argb; return this; }
        public Builder drawDisplayName(boolean value) { drawDisplayName = value; return this; }
        public Builder bakeResolution(int value) { bakeResolution = Mth.clamp(value, 32, 512); return this; }

        public GaugeFaceDefinition build() {
            return new GaugeFaceDefinition(this);
        }

        private static String sanitize(String value) {
            if (value == null)
                return "";
            String cleaned = value.replaceAll("[\\p{Cntrl}|]", "").trim();
            return cleaned.length() > 24 ? cleaned.substring(0, 24) : cleaned;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GaugeFaceDefinition[%s..%s %s]", minValue, maxValue, units);
    }
}
