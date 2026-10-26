package com.nectariich.create_flippies.client.display;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.client.renderer.RenderType;

/**
 * The render types screen elements are drawn in, all of them on the glass's own plane
 * with the font's polygon offset and none of them writing depth.
 *
 * <h2>Why nothing writes depth</h2>
 *
 * Every element of a display lies in one plane, so which of two overlapping elements
 * shows cannot be left to the depth test: two coplanar quads with different corners
 * interpolate very slightly different depths, and whichever wins does so pixel by
 * pixel, which at a grazing angle is speckle. With depth writes off the test still
 * runs (an element behind a wall stays hidden), but it never compares one element
 * with another, so the order they are submitted in is the order they are seen in:
 * painter's order, exact for all 128 layers with no lift between them.
 *
 * The font asks for its own types, one per glyph texture, and they write depth. So
 * {@link #noDepthWrite} makes a twin of any requested type that sets the original up
 * and then turns the depth mask off, and turns it back on after. Turning the mask off
 * here, inside the twin's own setup, is the only place it sticks: the original's
 * setup is what would otherwise turn it back on at draw time. The twin is cached per
 * original, which the font itself memoises per texture, so there are as many twins as
 * glyph atlases. It never sorts its quads on upload, because sorting by distance is
 * exactly what would undo the submission order this depends on.
 *
 * The twin also turns culling off, as {@link #FILL} has it off: a designed screen
 * mirrored by one flip draws its lettering in a mirrored pose, where every glyph
 * winds the other way as seen from the front and a culled type would drop it, while
 * the fills and frames under it still showed. The back of a display is never seen
 * through its glass or body, which win the depth test from behind, so nothing is
 * drawn that culling was hiding.
 *
 * <h2>The viewport's depth</h2>
 *
 * Once the elements are down, {@link #VIEWPORT_DEPTH} writes the depth of the whole
 * viewport (same plane, same offset) and no colour, so anything drawn there later on
 * the glass's plane loses the depth test instead of painting over the content. Only
 * once the glass is known to be down, though: glass still held back in a buffer would
 * lose that test too and leave a hole, which is why ScreenElementRenderer.drawNow
 * leaves it out when it cannot end the caller's batch.
 *
 * A subclass only so the vanilla state shards, which are protected, can be named.
 */
public final class ScreenRenderTypes extends RenderType {

    private static final int BUFFER_SIZE = 256;

    /**
     * A solid or translucent rectangle in vertex colour, lit by the lightmap like the
     * font is, so a fill and the lettering on it brighten and dim together.
     */
    public static final RenderType FILL = create(CreateFlippies.MOD_ID + ":screen_fill",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, VertexFormat.Mode.QUADS, BUFFER_SIZE, false, false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_TEXT_BACKGROUND_SHADER)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .setWriteMaskState(COLOR_WRITE)
                    .createCompositeState(false));

    /** The viewport's depth and nothing else; see the class notes. */
    public static final RenderType VIEWPORT_DEPTH = create(CreateFlippies.MOD_ID + ":screen_viewport_depth",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, BUFFER_SIZE, false, false,
            CompositeState.builder()
                    .setShaderState(POSITION_COLOR_SHADER)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .setWriteMaskState(DEPTH_WRITE)
                    .createCompositeState(false));

    private static final Map<RenderType, RenderType> TWINS = new HashMap<>();

    private ScreenRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
            boolean affectsCrumbling, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, false, setup, clear);
    }

    /**
     * That type, drawing exactly as it does but without writing depth or culling back
     * faces. The types made here already do neither, and are handed back as they are.
     * Render thread only.
     */
    public static RenderType noDepthWrite(RenderType type) {
        if (type == FILL || type == VIEWPORT_DEPTH || type instanceof ScreenRenderTypes)
            return type;
        return TWINS.computeIfAbsent(type, original -> new ScreenRenderTypes(
                CreateFlippies.MOD_ID + ":no_depth_write/" + original, original.format(), original.mode(),
                original.bufferSize(), original.affectsCrumbling(),
                () -> {
                    original.setupRenderState();
                    RenderSystem.depthMask(false);
                    RenderSystem.disableCull();
                },
                () -> {
                    RenderSystem.enableCull();
                    RenderSystem.depthMask(true);
                    original.clearRenderState();
                }));
    }
}
