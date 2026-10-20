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
 * Kept by the face's content, so every gauge printing the same face shares one
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
        String key = gauge.faceCacheKey();
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
            byte[] png = pixels.asByteArray();
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
}
