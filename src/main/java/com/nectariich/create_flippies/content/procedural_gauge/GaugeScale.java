package com.nectariich.create_flippies.content.procedural_gauge;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The numbered scale of an edgewise face: where its major and minor ticks fall and
 * what each major one is labelled. Port of the tick loop the Unity bar baker
 * (Editor/BarGaugeFaceBaker.cs) runs, kept apart from any drawing so the layout and
 * the tests read the same numbers.
 *
 * As in the Unity baker, the majors run from the minimum in whole steps and stop at
 * the last one that still fits the range; an interval that does not divide the
 * range evenly leaves the top of the scale unlabelled rather than squeezing a last
 * division in.
 */
public final class GaugeScale {

    /** More majors than this is a range typed with the wrong interval, not a scale. */
    public static final int MAX_MAJORS = 200;

    /** More minors per major than this cannot be told apart on any face this mod draws. */
    public static final int MAX_MINORS = 20;

    private GaugeScale() {}

    /**
     * How many major divisions fit the range: majors are numbered 0 to this count,
     * so there is one more major tick than this. Zero for a range or interval that
     * makes no scale.
     */
    public static int majorDivisions(float min, float max, float interval) {
        if (!(max > min) || !(interval > 0) || !Float.isFinite(max - min))
            return 0;
        // The 1e-4 is the Unity baker's: a range of 0..1 in steps of 0.2 must not
        // lose its last tick to the float error in 1 / 0.2
        double divisions = Math.floor((max - min) / interval + 1e-4);
        return (int) Math.min(MAX_MAJORS, Math.max(0, divisions));
    }

    /** The value of every major tick, from the minimum up. */
    public static float[] majorValues(float min, float max, float interval) {
        int divisions = majorDivisions(min, max, interval);
        if (divisions == 0 && !(max > min && interval > 0))
            return new float[0];
        float[] values = new float[divisions + 1];
        for (int i = 0; i <= divisions; i++)
            values[i] = min + i * interval;
        return values;
    }

    /** The value of every minor tick, between each major and the next one. */
    public static float[] minorValues(float min, float max, float interval, int minorsPerMajor) {
        int divisions = majorDivisions(min, max, interval);
        int minors = Math.max(0, Math.min(MAX_MINORS, minorsPerMajor));
        float step = interval / (minors + 1);
        float[] values = new float[divisions * minors];
        int at = 0;
        for (int i = 0; i < divisions; i++)
            for (int j = 1; j <= minors; j++)
                values[at++] = min + i * interval + j * step;
        return values;
    }

    /** The label printed beside each major tick: the value times the multiplier, in the format. */
    public static List<String> labels(float min, float max, float interval, String format, float multiplier) {
        DecimalFormat decimal = format(format);
        List<String> labels = new ArrayList<>();
        for (float value : majorValues(min, max, interval))
            labels.add(label(decimal, value * multiplier));
        return labels;
    }

    /** One label, without the "-0" a negative rounding to zero would otherwise print. */
    private static String label(DecimalFormat format, float value) {
        String text = format.format(value);
        return text.matches("-0(\\.0*)?") ? text.substring(1) : text;
    }

    /** A DecimalFormat for a pattern, falling back to whole numbers for one it cannot read. */
    public static DecimalFormat format(String pattern) {
        String safe = pattern == null || pattern.isEmpty() ? "0" : pattern;
        try {
            return new DecimalFormat(safe, DecimalFormatSymbols.getInstance(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        }
    }
}
