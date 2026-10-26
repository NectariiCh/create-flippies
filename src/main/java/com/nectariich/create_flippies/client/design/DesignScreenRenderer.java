package com.nectariich.create_flippies.client.design;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.display.ScreenElementRenderer;
import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Puts a designed screen's elements on its glass, on a placed control and in the
 * workbench preview alike.
 *
 * <h2>The pose</h2>
 *
 * The glass is wherever the design's model has it, so the elements are posed by the
 * surface's viewport frame ({@link ScreenSurface#viewportFrame}) rather than by any
 * fixed turn: from authored pixels, its columns take the renderer's pixels, +x right
 * and +y down from the viewport's top left, onto the glass, and the third axis is the
 * unit normal out of the glass's front (the UV frame's dP/du × dP/dv points into it,
 * the other way round from the font's usual frame). The whole is lifted {@link #LIFT}
 * out of the glass so the glass cannot hide it. Rotation, flips and scale are all
 * inside the frame, which is why a turned screen's lettering is never sheared.
 *
 * The normal comes from the UV frame rather than from right × down because one flip
 * (X or Y, not both) mirrors the pose: right × down then points out of the glass, and
 * a normal taken from it would put the content behind the glass, facing into the
 * model. Mirrored, the lettering's quads also wind the other way as seen from the
 * front, which is why the font's types are drawn without culling
 * ({@link com.nectariich.create_flippies.client.display.ScreenRenderTypes#noDepthWrite}).
 *
 * <h2>When placed screens are drawn</h2>
 *
 * For the reason {@link DesignTextRenderer} gives for printed text: the glass is part
 * of a Hypergraphical model, which is drawn after the block entity pass, so content
 * drawn in that pass would be painted over by the very glass it sits on, or blend its
 * soft edges with whatever was behind the model. So during the level's own pass
 * {@link #drawPlaced} only records each screen with its pose and what it shows, and
 * {@link #flushPlaced} draws them all from the same hook the printed text is flushed
 * from, after translucent blocks. Outside the level's pass (a shader pack's shadow
 * pass, a scene drawn in a GUI) a screen is drawn at once, as the text is.
 *
 * Drawn into the game's main buffer source, whose every switch of render type ends
 * the batch before it, so each screen's elements still reach the screen back to
 * front, as {@link ScreenElementRenderer} requires, and two overlapping screens on
 * one panel layer by depth like any other geometry: each ends with its viewport's
 * depth, and the elements themselves only test it.
 */
public final class DesignScreenRenderer {

    private static final float PIXEL = 1 / 16f;

    /** How far a screen's content stands off its glass, in pixels, so the glass cannot hide it. */
    public static final float LIFT = 0.02f;

    /** One placed screen, posed at its viewport's top left in the block entity pass and waiting to be drawn. */
    private record ScreenDeferred(Matrix4f pose, Matrix3f normal, Object owner, List<ScreenElement> snapshot,
            Viewport viewport, boolean powered, boolean backlit, int light) {}

    private static final List<ScreenDeferred> DEFERRED = new ArrayList<>();

    /** Whether the level's pass is between the sky and the flush; see {@link DesignTextRenderer}. */
    private static boolean deferring;

    private DesignScreenRenderer() {}

    /** The level's pass has begun: from here until {@link #flushPlaced}, placed screens are recorded. */
    public static void beginLevel() {
        DEFERRED.clear();
        deferring = true;
    }

    /**
     * Draws the placed screens recorded since {@link #beginLevel} into {@code buffers}
     * and flushes them. Called after Hypergraphical's opaque pass, so the elements land
     * on the finished glass.
     */
    public static void flushPlaced(MultiBufferSource.BufferSource buffers) {
        deferring = false;
        if (DEFERRED.isEmpty())
            return;
        PoseStack ms = new PoseStack();
        for (ScreenDeferred deferred : DEFERRED) {
            ms.last().pose().set(deferred.pose());
            ms.last().normal().set(deferred.normal());
            ScreenElementRenderer.draw(ms, buffers, deferred.owner(), deferred.snapshot(), deferred.viewport(),
                    deferred.powered(), deferred.backlit(), deferred.light(), false);
        }
        DEFERRED.clear();
        buffers.endBatch();
    }

    /**
     * A placed screen's elements: recorded to be drawn after the model during the
     * level's pass, drawn at once otherwise. {@code ms} stands at the min corner of the
     * (displaced) cell the screen is mounted in, in blocks; {@code mount} is the mount's
     * whole rotation about the middle of the cell, as for the model and its text.
     * {@code snapshot} must not change afterwards ({@code ScreenElements.snapshot()});
     * {@code owner} is the live elements it was taken from. A dark or blank screen
     * records nothing and asks for no buffer.
     */
    public static void drawPlaced(PoseStack ms, MultiBufferSource buffer, ScreenSurface surface, Quaternionf mount,
            Object owner, List<ScreenElement> snapshot, Viewport viewport, boolean powered, boolean backlit,
            int light) {
        if (!ScreenElementRenderer.hasVisible(owner, snapshot, powered))
            return;
        ms.pushPose();
        if (toPlacedViewport(ms, surface, mount)) {
            if (!deferring || DEFERRED.size() >= DesignTextRenderer.MAX_DEFERRED) {
                ScreenElementRenderer.draw(ms, buffer, owner, snapshot, viewport, powered, backlit, light, false);
            } else {
                PoseStack.Pose last = ms.last();
                DEFERRED.add(new ScreenDeferred(new Matrix4f(last.pose()), new Matrix3f(last.normal()), owner,
                        snapshot, viewport, powered, backlit, light));
            }
        }
        ms.popPose();
    }

    /**
     * Poses {@code ms}, standing at the min corner of the (displaced) cell in blocks,
     * at a placed screen's viewport as {@link #toViewport} does; false, and {@code ms}
     * turned and scaled but not onto the glass, when there is no glass to pose onto.
     */
    public static boolean toPlacedViewport(PoseStack ms, ScreenSurface surface, Quaternionf mount) {
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(mount);
        ms.translate(-0.5, -0.5, -0.5);
        ms.scale(PIXEL, PIXEL, PIXEL);
        return toViewport(ms, surface);
    }

    /**
     * Poses {@code ms}, standing in authored pixels, at the surface's viewport: its top
     * left corner lifted {@link #LIFT} out of the glass, a unit one viewport pixel, +x
     * right, +y down and +z out of the glass. False, and {@code ms} untouched, when the
     * frame spans no area (nothing can be drawn on it).
     */
    public static boolean toViewport(PoseStack ms, ScreenSurface surface) {
        double[] f = surface.viewportFrame();
        // The front is taken from the UV frame's own axes, not from the viewport's
        // right and down: a single flip reverses one of those (and with it right ×
        // down), but the glass faces the same way whether its content is mirrored or not
        float[] uv = surface.frame();
        double nx = uv[4] * uv[8] - uv[5] * uv[7], ny = uv[5] * uv[6] - uv[3] * uv[8],
                nz = uv[3] * uv[7] - uv[4] * uv[6];
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double rx = f[4] * f[8] - f[5] * f[7], ry = f[5] * f[6] - f[3] * f[8], rz = f[3] * f[7] - f[4] * f[6];
        if (!(length > 0) || !(rx * rx + ry * ry + rz * rz > 0))
            return false;
        nx /= length;
        ny /= length;
        nz /= length;
        // dP/du × dP/dv points into the glass, so the front is the other way
        float ox = (float) (f[0] - LIFT * nx), oy = (float) (f[1] - LIFT * ny), oz = (float) (f[2] - LIFT * nz);
        ms.mulPoseMatrix(new Matrix4f((float) f[3], (float) f[4], (float) f[5], 0, (float) f[6], (float) f[7],
                (float) f[8], 0, (float) -nx, (float) -ny, (float) -nz, 0, ox, oy, oz, 1));
        return true;
    }
}
