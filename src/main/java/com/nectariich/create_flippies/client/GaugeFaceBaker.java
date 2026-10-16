package com.nectariich.create_flippies.client;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Runtime port of the Unity editor baker (Editor/GaugeFaceBaker.cs): rasters a
 * {@link GaugeFaceDefinition} into a dial-face texture and registers it as a
 * dynamic texture. Results are cached by definition content and evicted LRU.
 *
 * Only ever call from the render thread (block entity renderers qualify).
 */
public final class GaugeFaceBaker {

    private static final int MAX_CACHED_FACES = 64;
    private static boolean bakeFailureLogged = false;

    private static final LinkedHashMap<String, ResourceLocation> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
                    if (size() <= MAX_CACHED_FACES)
                        return false;
                    Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                    return true;
                }
            };

    static {
        // AWT is only used for offscreen rasterization; never let it try to
        // attach a window toolkit (relevant on macOS).
        try {
            if (System.getProperty("java.awt.headless") == null)
                System.setProperty("java.awt.headless", "true");
        } catch (SecurityException ignored) {}
    }

    private GaugeFaceBaker() {}

    /** Returns the texture for this face, baking and caching it on first use. */
    public static ResourceLocation getOrBake(GaugeFaceDefinition def) {
        return CACHE.computeIfAbsent(def.cacheKey(), key -> {
            ResourceLocation rl = CreateFlippies.asResource("gauge_face/" + hash(key));
            NativeImage image = bakeSafely(def);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            return rl;
        });
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return new BigInteger(1, digest.digest(key.getBytes(StandardCharsets.UTF_8))).toString(16);
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static NativeImage bakeSafely(GaugeFaceDefinition def) {
        try {
            return toNativeImage(bake(def));
        } catch (Throwable t) {
            if (!bakeFailureLogged) {
                bakeFailureLogged = true;
                CreateFlippies.LOGGER.error("Failed to bake gauge face {}, using flat fallback", def, t);
            }
            NativeImage fallback = new NativeImage(NativeImage.Format.RGBA, 16, 16, false);
            fallback.fillRect(0, 0, 16, 16, argbToAbgr(def.faceColor));
            return fallback;
        }
    }

    private static BufferedImage bake(GaugeFaceDefinition def) {
        int res = Math.max(32, Math.min(1024, def.bakeResolution));
        BufferedImage image = new BufferedImage(res, res, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            float cx = res / 2f;
            float cy = res / 2f;
            float radius = res / 2f;

            g.setColor(new Color(def.faceColor, true));
            g.fillRect(0, 0, res, res);

            drawBands(g, def, cx, cy, radius);
            drawTicks(g, def, cx, cy, radius);
            drawLabels(g, def, cx, cy, radius);
            drawCaptions(g, def, cx, cy, radius);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawBands(Graphics2D g, GaugeFaceDefinition def, float cx, float cy, float radius) {
        float bandMid = (def.bandOuterRadius + def.bandInnerRadius) / 2 * radius;
        float bandWidth = Math.max(1, (def.bandOuterRadius - def.bandInnerRadius) * radius);
        g.setStroke(new BasicStroke(bandWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        for (GaugeFaceDefinition.ColorBand band : def.bands) {
            float a0 = def.valueToAngle(band.fromValue());
            float a1 = def.valueToAngle(band.toValue());
            if (a1 < a0) {
                float swap = a0;
                a0 = a1;
                a1 = swap;
            }
            g.setColor(new Color(band.argb(), true));
            // Gauge angles are clockwise from 12 o'clock; AWT arcs are
            // counterclockwise from 3 o'clock.
            g.draw(new Arc2D.Float(cx - bandMid, cy - bandMid, bandMid * 2, bandMid * 2,
                    90 - a0, -(a1 - a0), Arc2D.OPEN));
        }
    }

    private static void drawTicks(Graphics2D g, GaugeFaceDefinition def, float cx, float cy, float radius) {
        int majors = majorTickCount(def);
        if (majors < 1)
            return;
        int minorPer = Math.max(0, Math.min(20, def.minorTicksPerMajor));

        g.setColor(new Color(def.markingColor, true));
        float outer = def.tickOuterRadius * radius;
        float majorInner = (def.tickOuterRadius - def.majorTickLength) * radius;
        float minorInner = (def.tickOuterRadius - def.minorTickLength) * radius;
        BasicStroke majorStroke = new BasicStroke(Math.max(1, def.majorTickWidth * radius),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        BasicStroke minorStroke = new BasicStroke(Math.max(1, def.minorTickWidth * radius),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);

        for (int i = 0; i <= majors; i++) {
            float value = majorTickValue(def, i, majors);
            g.setStroke(majorStroke);
            drawRadialLine(g, cx, cy, def.valueToAngle(value), majorInner, outer);

            if (i == majors)
                break;
            float next = majorTickValue(def, i + 1, majors);
            g.setStroke(minorStroke);
            for (int j = 1; j <= minorPer; j++) {
                float minorValue = value + (next - value) * j / (minorPer + 1);
                drawRadialLine(g, cx, cy, def.valueToAngle(minorValue), minorInner, outer);
            }
        }
    }

    private static void drawLabels(Graphics2D g, GaugeFaceDefinition def, float cx, float cy, float radius) {
        int majors = majorTickCount(def);
        if (majors < 1)
            return;
        DecimalFormat format = labelFormat(def);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, Math.round(def.labelSize * radius)));
        g.setFont(font);
        g.setColor(new Color(def.markingColor, true));
        for (int i = 0; i <= majors; i++) {
            float value = majorTickValue(def, i, majors);
            double angle = Math.toRadians(def.valueToAngle(value));
            float px = cx + (float) Math.sin(angle) * def.labelRadius * radius;
            float py = cy - (float) Math.cos(angle) * def.labelRadius * radius;
            drawCentered(g, format.format(value * def.labelMultiplier), px, py);
        }
    }

    private static void drawCaptions(Graphics2D g, GaugeFaceDefinition def, float cx, float cy, float radius) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, Math.round(def.textSize * radius)));
        g.setFont(font);
        g.setColor(new Color(def.markingColor, true));
        if (def.drawDisplayName && !def.displayName.isEmpty())
            drawCentered(g, def.displayName, cx, cy - 0.30f * radius);
        if (!def.units.isEmpty())
            drawCentered(g, def.units, cx, cy + 0.32f * radius);
    }

    private static int majorTickCount(GaugeFaceDefinition def) {
        float span = def.maxValue - def.minValue;
        if (span <= 0 || def.majorTickInterval <= 0)
            return 0;
        return Math.min(200, Math.round(span / def.majorTickInterval));
    }

    /**
     * Value of the i-th major tick. The last tick is forced onto maxValue so
     * intervals that don't divide the range evenly still close the scale.
     */
    private static float majorTickValue(GaugeFaceDefinition def, int i, int majors) {
        if (i >= majors)
            return def.maxValue;
        return def.minValue + i * def.majorTickInterval;
    }

    private static DecimalFormat labelFormat(GaugeFaceDefinition def) {
        String pattern = def.labelFormat == null || def.labelFormat.isEmpty() ? "0" : def.labelFormat;
        try {
            return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        }
    }

    private static void drawRadialLine(Graphics2D g, float cx, float cy, float angleDeg, float r1, float r2) {
        double angle = Math.toRadians(angleDeg);
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        // Image space: 0 degrees points up, clockwise positive, y axis down
        g.draw(new Line2D.Float(cx + sin * r1, cy - cos * r1, cx + sin * r2, cy - cos * r2));
    }

    private static void drawCentered(Graphics2D g, String text, float px, float py) {
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D bounds = g.getFont().getStringBounds(text, frc);
        float ascent = g.getFontMetrics().getAscent();
        float descent = g.getFontMetrics().getDescent();
        g.drawString(text, px - (float) bounds.getWidth() / 2, py + (ascent - descent) / 2);
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage result = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                result.setPixelRGBA(x, y, argbToAbgr(image.getRGB(x, y)));
        return result;
    }

    private static int argbToAbgr(int argb) {
        int alpha = argb >>> 24;
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }
}
