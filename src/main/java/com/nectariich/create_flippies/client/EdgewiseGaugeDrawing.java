package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseLayout;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterBlock;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlock;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * An edgewise gauge's runtime-baked face and its pointer, for the edgewise
 * voltmeter block, its mounted form, and the edgewise gauges made at the Design
 * Workbench.
 *
 * The face is one quad with the texture {@link EdgewiseFaceBaker} bakes; the
 * pointer is a flat quad a hair in front of it, sliding straight down the face to
 * where the definition puts the reading. That is the Unity gauge's look without its
 * curved face: there the pointer swung a short arc behind a face curved about its
 * pivot so that it read as a straight vertical scale, which a flat face and a
 * sliding pointer are directly.
 *
 * The block and the mounted form are placed exactly as {@link ProceduralGaugeDrawing}
 * places the Square gauges, since the model is authored facing north in the same
 * way; see that class for the chain.
 */
public final class EdgewiseGaugeDrawing {

    /** How far in front of the face the pointer is drawn, in pixels. */
    private static final float POINTER_LIFT = 0.05f;

    /** The face of the voltmeter's own model, in pixels: a twentieth of a pixel in front of its plate. */
    private static final GaugeFacePlane BLOCK_FACE = new GaugeFacePlane(
            new float[] { EdgewiseVoltmeterBlock.PLATE_MAX_X, EdgewiseVoltmeterBlock.PLATE_MAX_Y, 14.95f },
            new float[] { EdgewiseVoltmeterBlock.PLATE_MIN_X - EdgewiseVoltmeterBlock.PLATE_MAX_X, 0, 0 },
            new float[] { 0, EdgewiseVoltmeterBlock.PLATE_MIN_Y - EdgewiseVoltmeterBlock.PLATE_MAX_Y, 0 },
            new float[] { 0, 0, -1 });

    private EdgewiseGaugeDrawing() {}

    /** The face and pointer of an edgewise voltmeter, block or mounted. {@code offset} is in block units. */
    public static void drawDial(EdgewiseFaceDefinition face, float progress, Direction facing, int yaw, int rotation,
            Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        ms.pushPose();
        place(ms, facing, yaw, rotation, offset);
        ms.scale(1 / 16f, 1 / 16f, 1 / 16f);
        drawFace(face, progress, BLOCK_FACE, ms, buffer, light);
        ms.popPose();
    }

    /** The bezel, barrel and studs, for the mounted form that has no block model of its own. */
    public static void drawBody(Direction facing, int yaw, int rotation, Vec3 offset, PoseStack ms,
            MultiBufferSource buffer, int light) {
        ms.pushPose();
        place(ms, facing, yaw, rotation, offset);
        CachedBuffers.partial(ModPartialModels.EDGEWISE_PROCEDURAL_GAUGE_BODY, referenceState())
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
        ms.popPose();
    }

    /**
     * The face on {@code plane} and the pointer at {@code progress} along the scale
     * (a little past either end when pegged; the pointer stops at the face's edge).
     * The plane is in whatever units the stack is in; the pointer's lift is a
     * twentieth of one of them.
     */
    public static void drawFace(EdgewiseFaceDefinition face, float progress, GaugeFacePlane plane, PoseStack ms,
            MultiBufferSource buffer, int light) {
        float[] out = plane.out();
        GaugeQuads.textured(ms, buffer, EdgewiseFaceBaker.getOrBake(face), light, plane.point(0, 0, 0),
                plane.point(1, 0, 0), plane.point(1, 1, 0), plane.point(0, 1, 0), out[0], out[1], out[2]);

        float at = Mth.clamp(face.progressToFraction(progress), 0, 1);
        float[] span = EdgewiseLayout.pointerSpan(face);
        float top, bottom;
        if (face.pointerStyle == EdgewiseFaceDefinition.PointerStyle.BAR) {
            // Filled up from the bottom of the scale; nothing at all below it
            bottom = face.scaleBottom;
            top = Math.min(at, bottom);
        } else {
            // The pointer's width is a fraction of the face width, as the ticks' are
            float half = face.pointerWidth * plane.width() / Math.max(1e-4f, plane.height()) * 0.5f;
            top = at - half;
            bottom = at + half;
        }
        if (bottom - top <= 0)
            return;
        GaugeQuads.flat(ms, buffer, face.pointerColor, light, plane.point(span[0], top, POINTER_LIFT),
                plane.point(span[1], top, POINTER_LIFT), plane.point(span[1], bottom, POINTER_LIFT),
                plane.point(span[0], bottom, POINTER_LIFT), out[0], out[1], out[2]);
    }

    /** As {@link ProceduralGaugeDrawing}'s: the gauge's mount, then its north-facing model turned plate-down. */
    private static void place(PoseStack ms, Direction facing, int yaw, int rotation, Vec3 offset) {
        ms.translate(offset.x, offset.y, offset.z);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(facing, yaw)));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(facing)));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.spinDegrees(rotation)));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(ProceduralGaugeBlock.MOUNTED_AS)));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(ProceduralGaugeBlock.MOUNTED_AS)));
        ms.translate(-0.5, -0.5, -0.5);
    }

    /** One state for every edgewise gauge's body partial, for the reason {@link ProceduralGaugeDrawing} gives. */
    private static BlockState referenceState() {
        return ModBlocks.EDGEWISE_PROCEDURAL_VOLTMETERS.values().iterator().next().get().defaultBlockState();
    }
}
