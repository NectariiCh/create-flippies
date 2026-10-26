package com.nectariich.create_flippies.content.display.element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * What a bar element shows its value against: the range, which way it fills, and
 * whether it eases toward a new value or jumps to it.
 *
 * The value itself is read from the bar's text the way a procedural gauge reads a
 * display link's ({@code ProceduralGaugeDisplayTarget}): the first number in it, with
 * thousands separators taken out first. The fill is how far that value is along the
 * range, held to the box: unlike a gauge's needle a bar neither overshoots nor
 * trembles past its ends, because there is no further for it to go.
 */
public record BarSettings(float min, float max, int direction, boolean easing) {

    /** Which way the fill grows: from the left, the right, the bottom or the top. */
    public static final int LEFT_TO_RIGHT = 0, RIGHT_TO_LEFT = 1, BOTTOM_TO_TOP = 2, TOP_TO_BOTTOM = 3;

    public static final BarSettings DEFAULT = new BarSettings(0, 100, LEFT_TO_RIGHT, true);

    /** The first number in a piece of text, as gauges read one. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** Far past anything a link sends, and well inside what a float holds. */
    private static final float MAX_LEVEL = 1e9f;

    public BarSettings {
        min = finite(min, 0);
        max = finite(max, 100);
        direction = Mth.clamp(direction, LEFT_TO_RIGHT, TOP_TO_BOTTOM);
    }

    /** How far {@code value} is along the range, 0 to 1; 0 when the range is empty. */
    public float progress(float value) {
        if (Float.isNaN(value) || max == min)
            return 0;
        return Mth.clamp((value - min) / (max - min), 0, 1);
    }

    /** Whether the fill runs across the box rather than up or down it. */
    public boolean horizontal() {
        return direction == LEFT_TO_RIGHT || direction == RIGHT_TO_LEFT;
    }

    /** The first number in {@code text}, thousands separators ignored, or NaN when there is none. */
    public static float parse(String text) {
        if (text == null || text.isEmpty())
            return Float.NaN;
        Matcher matcher = NUMBER.matcher(text.replace(",", ""));
        if (!matcher.find())
            return Float.NaN;
        try {
            float value = Float.parseFloat(matcher.group());
            return Float.isFinite(value) ? value : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        if (min != DEFAULT.min)
            tag.putFloat("Mn", min);
        if (max != DEFAULT.max)
            tag.putFloat("Mx", max);
        if (direction != DEFAULT.direction)
            tag.putByte("D", (byte) direction);
        if (easing != DEFAULT.easing)
            tag.putBoolean("E", easing);
        return tag;
    }

    public static BarSettings read(CompoundTag tag) {
        if (tag == null || tag.isEmpty())
            return DEFAULT;
        return new BarSettings(tag.contains("Mn") ? tag.getFloat("Mn") : DEFAULT.min,
                tag.contains("Mx") ? tag.getFloat("Mx") : DEFAULT.max, tag.getByte("D"),
                !tag.contains("E") || tag.getBoolean("E"));
    }

    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? Mth.clamp(value, -MAX_LEVEL, MAX_LEVEL) : fallback;
    }
}
