package com.nectariich.create_flippies.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseLayout;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Runtime port of the Unity edgewise baker (Editor/BarGaugeFaceBaker.cs): rasters
 * what {@link EdgewiseLayout} lays out for an {@link EdgewiseFaceDefinition} into a
 * tall face texture and registers it as a dynamic texture, cached by content and
 * evicted LRU, as {@link GaugeFaceBaker} does for the round dials.
 *
 * Also keeps a one-pixel white texture, which the pointers are drawn with and tinted
 * by vertex colour, so a pointer of any colour needs no texture of its own.
 *
 * Only ever call from the render thread (block entity renderers qualify).
 */
public final class EdgewiseFaceBaker {

    private static final int MAX_CACHED_FACES = 64;
    private static boolean bakeFailureLogged = false;
    private static ResourceLocation white;

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
        // AWT is only used for offscreen rasterization; see GaugeFaceBaker
        try {
            if (System.getProperty("java.awt.headless") == null)
                System.setProperty("java.awt.headless", "true");
        } catch (SecurityException ignored) {}
    }

    private EdgewiseFaceBaker() {}

    /** Returns the texture for this face, baking and caching it on first use. */
    public static ResourceLocation getOrBake(EdgewiseFaceDefinition def) {
        return CACHE.computeIfAbsent(def.cacheKey(), key -> {
            ResourceLocation rl = CreateFlippies.asResource("edgewise_face/" + hash(key));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(bakeSafely(def)));
            return rl;
        });
    }

    /** A one-pixel white texture, for flat shapes coloured by their vertices. */
    public static ResourceLocation white() {
        if (white == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            white = CreateFlippies.asResource("edgewise_face/white");
            Minecraft.getInstance().getTextureManager().register(white, new DynamicTexture(image));
        }
        return white;
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return new BigInteger(1, digest.digest(key.getBytes(StandardCharsets.UTF_8))).toString(16);
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private static NativeImage bakeSafely(EdgewiseFaceDefinition def) {
        try {
            return toNativeImage(bake(def));
        } catch (Throwable t) {
            if (!bakeFailureLogged) {
                bakeFailureLogged = true;
                CreateFlippies.LOGGER.error("Failed to bake edgewise face {}, using flat fallback", def, t);
            }
            NativeImage fallback = new NativeImage(NativeImage.Format.RGBA, 4, 16, false);
            fallback.fillRect(0, 0, 4, 16, argbToAbgr(def.faceColor));
            return fallback;
        }
    }

    static BufferedImage bake(EdgewiseFaceDefinition def) {
        int[] size = EdgewiseLayout.size(def);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Font base = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            EdgewiseLayout layout = EdgewiseLayout.of(def,
                    (text, px) -> g.getFontMetrics(base.deriveFont(Math.max(1f, px))).stringWidth(text));

            g.setColor(new Color(def.faceColor, true));
            g.fillRect(0, 0, layout.width, layout.height);
            for (EdgewiseLayout.Rect rect : layout.rects) {
                g.setColor(new Color(rect.argb(), true));
                g.fill(new Rectangle2D.Float(rect.x0(), rect.y0(), rect.x1() - rect.x0(), rect.y1() - rect.y0()));
            }
            for (EdgewiseLayout.Text text : layout.texts) {
                if (text.px() < 1 || text.text().isEmpty())
                    continue;
                g.setFont(base.deriveFont(text.px()));
                g.setColor(new Color(text.argb(), true));
                FontMetrics metrics = g.getFontMetrics();
                float w = metrics.stringWidth(text.text());
                float left = switch (text.align()) {
                    case LEFT -> text.x();
                    case RIGHT -> text.x() - w;
                    case CENTER -> text.x() - w / 2;
                };
                g.drawString(text.text(), left, text.y() + (metrics.getAscent() - metrics.getDescent()) / 2f);
            }
        } finally {
            g.dispose();
        }
        return image;
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
