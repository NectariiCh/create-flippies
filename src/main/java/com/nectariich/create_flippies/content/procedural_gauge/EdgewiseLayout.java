package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything printed on an edgewise face goes, in the pixels of a face
 * texture: the colour bands, then the spine and ticks over them, the labels beside
 * the major ticks, the name above the scale or stacked down the side, and the units
 * below it. Port of the layout half of the Unity BarGaugeFaceBaker; the client baker
 * only rasters what this lays out, so the layout can be tested without a font or a
 * texture.
 *
 * Pixel space is y-down with the image covering the whole face, row 0 the top of the
 * face (the Unity baker worked y-up and flipped; the positions are the same).
 * Text is measured through a {@link Measure} so that shrinking a name to fit works
 * against whatever font the caller draws with.
 */
public final class EdgewiseLayout {

    /** Line spacing of the stacked side name, as a multiple of its size. The Unity baker's. */
    public static final float STACK_PITCH = 1.15f;

    /** How much of the face width a name or units line may take before it shrinks. */
    public static final float MAX_TEXT_WIDTH = 0.92f;

    /** Measures text: the advance width of {@code text} at a size of {@code px} pixels. */
    @FunctionalInterface
    public interface Measure {
        float width(String text, float px);
    }

    /** A filled rectangle, corners ordered so x0 <= x1 and y0 <= y1. */
    public record Rect(float x0, float y0, float x1, float y1, int argb) {
        static Rect of(float xa, float ya, float xb, float yb, int argb) {
            return new Rect(Math.min(xa, xb), Math.min(ya, yb), Math.max(xa, xb), Math.max(ya, yb), argb);
        }
    }

    /** How a text's {@code x} is read: its left edge, its right edge, or its centre. */
    public enum Align {
        LEFT, RIGHT, CENTER
    }

    /** One string, {@code y} its vertical centre, {@code px} its size. */
    public record Text(String text, float x, float y, float px, Align align, int argb) {}

    public final int width;
    public final int height;
    /** Bands first, then the spine and the ticks, which draw over them. */
    public final List<Rect> rects;
    public final List<Text> texts;

    private EdgewiseLayout(int width, int height, List<Rect> rects, List<Text> texts) {
        this.width = width;
        this.height = height;
        this.rects = List.copyOf(rects);
        this.texts = List.copyOf(texts);
    }

    /** The texture size for a face: {@link EdgewiseFaceDefinition#bakeResolution} tall, as wide as its aspect makes it. */
    public static int[] size(EdgewiseFaceDefinition def) {
        int height = Math.max(16, def.bakeResolution);
        int width = Math.max(8, Math.round(height / def.faceAspect));
        return new int[] { width, height };
    }

    public static EdgewiseLayout of(EdgewiseFaceDefinition def, Measure measure) {
        int[] size = size(def);
        int width = size[0], height = size[1];
        List<Rect> rects = new ArrayList<>();
        List<Text> texts = new ArrayList<>();
        int mark = def.markingColor;

        // Bands first: later rectangles draw over earlier ones, so the spine and ticks sit on top
        for (GaugeFaceDefinition.ColorBand band : def.bands)
            rects.add(Rect.of(x(def, def.bandLeft, width), y(def, band.fromValue(), height),
                    x(def, def.bandLeft + def.bandWidth, width), y(def, band.toValue(), height), band.argb()));

        // The spine grows away from the ticks, so thickening it never covers them
        float majorWidth = def.majorTickWidth * width;
        if (def.spineWidth > 0) {
            float half = majorWidth * 0.5f; // squared off with the end ticks
            float top = def.spineFullHeight ? 0 : y(def, def.maxValue, height) - half;
            float bottom = def.spineFullHeight ? height : y(def, def.minValue, height) + half;
            rects.add(Rect.of(x(def, def.tickEdge - def.spineWidth, width), top, x(def, def.tickEdge, width), bottom,
                    mark));
        }

        float[] majors = GaugeScale.majorValues(def.minValue, def.maxValue, def.majorTickInterval);
        for (float value : majors)
            rects.add(tick(def, value, def.majorTickLength, majorWidth, width, height, mark));
        float minorWidth = def.minorTickWidth * width;
        for (float value : GaugeScale.minorValues(def.minValue, def.maxValue, def.majorTickInterval,
                def.minorTicksPerMajor))
            rects.add(tick(def, value, def.minorTickLength, minorWidth, width, height, mark));

        // Labels butt up against the major ticks: their near edge sits labelGap past
        // the tick ends, growing away from the ticks (leftwards when flipped)
        List<String> labels = GaugeScale.labels(def.minValue, def.maxValue, def.majorTickInterval, def.labelFormat,
                def.labelMultiplier);
        float labelPx = def.labelSize * width;
        float labelEdge = x(def, def.tickEdge + def.majorTickLength + def.labelGap, width);
        Align labelAlign = def.flipTicks ? Align.RIGHT : Align.LEFT;
        for (int i = 0; i < majors.length; i++)
            texts.add(new Text(labels.get(i), labelEdge, y(def, majors[i], height), labelPx, labelAlign, mark));

        String name = def.drawDisplayName ? def.displayName : "";
        float textPx = def.textSize * width;
        float maxTextWidth = width * MAX_TEXT_WIDTH;
        float centreX = width * 0.5f;
        if (!name.isEmpty()) {
            if (def.nameOnSide) {
                // Centred in the gap between the face edge and the spine's outer
                // edge, down the length of the scale
                float sideWidth = (def.tickEdge - def.spineWidth - 2 * def.sideNameMargin) * width;
                float sideHeight = (def.scaleBottom - def.scaleTop) * height;
                float namePx = fitStacked(measure, name, textPx, sideWidth, sideHeight);
                if (namePx >= 1) {
                    float pitch = namePx * STACK_PITCH;
                    float sideX = x(def, (def.tickEdge - def.spineWidth) * 0.5f, width);
                    float top = (def.scaleTop + def.scaleBottom) * 0.5f * height - (name.length() - 1) * pitch * 0.5f;
                    for (int i = 0; i < name.length(); i++)
                        texts.add(new Text(String.valueOf(name.charAt(i)), sideX, top + i * pitch, namePx,
                                Align.CENTER, mark));
                }
            } else {
                texts.add(new Text(name, centreX, def.scaleTop * 0.5f * height,
                        fit(measure, name, textPx, maxTextWidth), Align.CENTER, mark));
            }
        }
        if (!def.units.isEmpty())
            texts.add(new Text(def.units, centreX, (1 + def.scaleBottom) * 0.5f * height,
                    fit(measure, def.units, textPx, maxTextWidth), Align.CENTER, mark));
        return new EdgewiseLayout(width, height, rects, texts);
    }

    /**
     * Where the pointer runs across the face, as {left, right} fractions of its width:
     * a needle from the pointer-side edge in to the ticks' spine-side edge, or a bar
     * filling the band strip.
     */
    public static float[] pointerSpan(EdgewiseFaceDefinition def) {
        float a, b;
        if (def.pointerStyle == EdgewiseFaceDefinition.PointerStyle.BAR) {
            a = def.x(def.bandLeft);
            b = def.x(def.bandLeft + def.bandWidth);
        } else {
            a = def.x(0);
            b = def.x(def.tickEdge);
        }
        return new float[] { Math.min(a, b), Math.max(a, b) };
    }

    /** A horizontal tick from the tick edge outwards, centred on the value. */
    private static Rect tick(EdgewiseFaceDefinition def, float value, float length, float thickness, int width,
            int height, int argb) {
        float y = y(def, value, height);
        return Rect.of(x(def, def.tickEdge, width), y - thickness * 0.5f, x(def, def.tickEdge + length, width),
                y + thickness * 0.5f, argb);
    }

    private static float x(EdgewiseFaceDefinition def, float fraction, int width) {
        return def.x(fraction) * width;
    }

    private static float y(EdgewiseFaceDefinition def, float value, int height) {
        return def.valueToFraction(value) * height;
    }

    /** The size at which {@code text} fits {@code maxWidth}: {@code px}, or smaller when it is too wide. */
    public static float fit(Measure measure, String text, float px, float maxWidth) {
        float w = measure.width(text, px);
        return w > maxWidth && w > 0 ? px * maxWidth / w : px;
    }

    /**
     * The size at which a string stacked one character per line fits
     * {@code maxWidth} by {@code maxHeight}: the widest character across, and the
     * whole stack at {@link #STACK_PITCH} down.
     */
    public static float fitStacked(Measure measure, String text, float px, float maxWidth, float maxHeight) {
        if (text.isEmpty())
            return px;
        if (maxWidth <= 0 || maxHeight <= 0)
            return 0;
        float widest = 0;
        for (int i = 0; i < text.length(); i++)
            widest = Math.max(widest, measure.width(String.valueOf(text.charAt(i)), px));
        float scale = 1;
        if (widest > maxWidth)
            scale = maxWidth / widest;
        scale = Math.min(scale, maxHeight / (text.length() * px * STACK_PITCH));
        return Math.max(0, px * scale);
    }
}
