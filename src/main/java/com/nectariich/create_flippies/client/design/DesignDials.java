package com.nectariich.create_flippies.client.design;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.EdgewiseFaceBaker;
import com.nectariich.create_flippies.client.GaugeFaceBaker;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;

import dev.nectariich.hypergraphical.api.HypergraphicalAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The textures designed gauges' printed faces are drawn with, as Hypergraphical
 * material maps: a designed gauge's dial is baked by the same generator its kind's
 * procedural gauge uses ({@link GaugeFaceBaker} for a rotary dial,
 * {@link EdgewiseFaceBaker} for an edgewise face), and the baked image is handed to
 * Hypergraphical as a runtime PNG, since its materials name their textures by
 * resource rather than by a texture already on the GPU.
 *
 * A gauge whose face is turned ({@link GaugeDial#rotation}) gets the baked image
 * turned here, since a material can be moved and scaled but not turned. Pixels the
 * turn brings in from past the image's edges repeat its nearest edge, so the face's
 * background carries on into the corners.
 *
 * Kept by the face's content and turn, so every gauge printing the same face shares one
 * file. The bakers keep their own textures for a while and may let them go; once
 * turned into a file here, a face stays until the game closes, which for the few
 * faces a world designs is a few hundred kilobytes.
 *
 * Render thread only, as the bakers are.
 */
public final class DesignDials {

    private static final String PREFIX = "design_dial/";

    private static final Map<String, ResourceLocation> FILES = new LinkedHashMap<>();

    private DesignDials() {}

    /** The face's texture as a material map, baking it the first time; null if it could not be read back. */
    public static ResourceLocation texture(GaugeDial gauge) {
        String key = gauge.faceCacheKey() + (gauge.rotation() != 0 ? "|turned " + gauge.rotation() : "");
        ResourceLocation known = FILES.get(key);
        if (known != null)
            return known;
        ResourceLocation baked = gauge.isEdgewise() ? EdgewiseFaceBaker.getOrBake(gauge.edgewise)
                : GaugeFaceBaker.getOrBake(gauge.dial);
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(baked);
        NativeImage pixels = texture instanceof DynamicTexture dynamic ? dynamic.getPixels() : null;
        if (pixels == null)
            return null;
        try {
            byte[] png;
            if (gauge.rotation() != 0) {
                try (NativeImage turned = rotate(pixels, gauge.rotation())) {
                    png = turned.asByteArray();
                }
            } else {
                png = pixels.asByteArray();
            }
            ResourceLocation file = CreateFlippies.asResource(PREFIX
                    + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "")
                    + ".png");
            HypergraphicalAPI.registerRuntimeFile(file, png);
            FILES.put(key, file);
            return file;
        } catch (Exception e) {
            CreateFlippies.LOGGER.warn("Could not hand a designed gauge's face to Hypergraphical", e);
            return null;
        }
    }

    /**
     * A copy of an image turned clockwise by {@code degrees} about its centre, the
     * same size, sampled bilinearly and clamped to the edges. The image is read with
     * Y down, so turning the sample point anticlockwise turns the picture clockwise.
     */
    private static NativeImage rotate(NativeImage source, float degrees) {
        int width = source.getWidth(), height = source.getHeight();
        NativeImage turned = new NativeImage(width, height, false);
        double radians = Math.toRadians(degrees), cos = Math.cos(radians), sin = Math.sin(radians);
        double cx = width / 2.0, cy = height / 2.0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                double sx = cos * dx + sin * dy + cx - 0.5, sy = -sin * dx + cos * dy + cy - 0.5;
                turned.setPixelRGBA(x, y, bilinear(source, sx, sy));
            }
        return turned;
    }

    /** The image at a point between pixel centres, each channel blended from the four around it. */
    private static int bilinear(NativeImage image, double x, double y) {
        int maxX = image.getWidth() - 1, maxY = image.getHeight() - 1;
        x = Math.max(0, Math.min(maxX, x));
        y = Math.max(0, Math.min(maxY, y));
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(maxX, x0 + 1), y1 = Math.min(maxY, y0 + 1);
        double fx = x - x0, fy = y - y0;
        int a = image.getPixelRGBA(x0, y0), b = image.getPixelRGBA(x1, y0), c = image.getPixelRGBA(x0, y1),
                d = image.getPixelRGBA(x1, y1);
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            double top = (a >>> shift & 0xFF) * (1 - fx) + (b >>> shift & 0xFF) * fx;
            double bottom = (c >>> shift & 0xFF) * (1 - fx) + (d >>> shift & 0xFF) * fx;
            result |= (int) Math.round(top * (1 - fy) + bottom * fy) << shift;
        }
        return result;
    }
}
