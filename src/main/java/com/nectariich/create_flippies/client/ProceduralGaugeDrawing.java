package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlock;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * A Square procedural gauge's runtime-baked dial face and its needle, drawn once
 * for both forms the gauge comes in: the standalone block and the control mounted
 * inside a host cell.
 *
 * Shared rather than written twice because the two have to be the same gauge to look
 * at, and because a renderer that disagreed with the hitboxes about orientation is a
 * bug this project has had twice already. Both callers state where the gauge is the
 * same way — the face its plate points at, the quarter turn it was mounted on, and
 * how far off the middle of the cell it sits — and everything else is
 * {@link ProceduralGaugeBlock}'s authored geometry, which is what the footprint and
 * the terminals are built from as well. A standalone gauge is simply one centred on
 * its cell with no turn of its own.
 *
 * The body is drawn only for the mounted form. A gauge block gets its bezel and
 * barrel from its own baked block model through its block state; a mounted control
 * has no block state to be baked from, so its body has to come from a partial.
 */
public final class ProceduralGaugeDrawing {

    // The dial plate spans 5.5..10.5 in x and y at z 15..16 of the authored model,
    // framed by the bezel; the baked face is laid a twentieth of a pixel in front of
    // it so it is not fighting the plate's own texture for the same depth.
    private static final float PLATE_MIN = 5.5f / 16f;
    private static final float PLATE_MAX = 10.5f / 16f;
    private static final float DIAL_Z = 14.95f / 16f;

    /** The needle's pivot, on the axis of the dial. */
    private static final float PIVOT_X = 8 / 16f;
    private static final float PIVOT_Y = 8 / 16f;

    private ProceduralGaugeDrawing() {}

    /** {@code offset} is in block units and is {@link Vec3#ZERO} for a gauge centred on its cell. */
    public static void drawDial(GaugeFaceDefinition face, float progress, Direction facing, int yaw, int rotation,
            Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        ms.pushPose();
        place(ms, facing, yaw, rotation, offset);
        dialFace(face, ms, buffer, light);
        needle(face, progress, ms, buffer, light);
        ms.popPose();
    }

    /** The bezel, barrel and studs, for the mounted form that has no block model of its own. */
    public static void drawBody(Direction facing, int yaw, int rotation, Vec3 offset, PoseStack ms,
            MultiBufferSource buffer, int light) {
        ms.pushPose();
        place(ms, facing, yaw, rotation, offset);
        CachedBuffers.partial(ModPartialModels.SQUARE_PROCEDURAL_GAUGE_BODY, referenceState())
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
        ms.popPose();
    }

    /**
     * Puts the stack into the authored model's space, in two steps that are the same
     * arithmetic twice over. The gauge is oriented and displaced exactly as
     * {@link ControlMount#point} puts a hitbox corner, and before that its
     * north-facing model is turned into the plate-down frame that orientation is
     * measured in, which {@link ProceduralGaugeBlock#MOUNTED_AS} names as a facing of
     * its own and no turn about the mounting axis.
     *
     * A PoseStack applies the last call it was given to a point first, so the calls
     * come in the reverse of that order: the model's own turn is innermost, before
     * the gauge has been aimed anywhere, and the displacement is outermost, in the
     * host's own space.
     */
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

    private static void dialFace(GaugeFaceDefinition face, PoseStack ms, MultiBufferSource buffer, int light) {
        ResourceLocation texture = GaugeFaceBaker.getOrBake(face);
        VertexConsumer vertices = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        Matrix4f pose = ms.last().pose();
        Matrix3f normal = ms.last().normal();
        // The viewer of a north-facing dial looks south; +x appears on their left, so
        // u runs from x-max to x-min.
        vertex(vertices, pose, normal, light, PLATE_MAX, PLATE_MAX, 0, 0);
        vertex(vertices, pose, normal, light, PLATE_MIN, PLATE_MAX, 1, 0);
        vertex(vertices, pose, normal, light, PLATE_MIN, PLATE_MIN, 1, 1);
        vertex(vertices, pose, normal, light, PLATE_MAX, PLATE_MIN, 0, 1);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, Matrix3f normal, int light, float x, float y,
            float u, float v) {
        vertices.vertex(pose, x, y, DIAL_Z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0, 0, -1)
                .endVertex();
    }

    /**
     * The needle, turned about the dial axis. The turn is given to the buffer rather
     * than to the stack so that it happens in the model's own space, inside
     * everything {@link #place} has already applied.
     */
    private static void needle(GaugeFaceDefinition face, float progress, PoseStack ms, MultiBufferSource buffer,
            int light) {
        float angle = (float) Math.toRadians(face.progressToAngle(progress));
        CachedBuffers.partial(ModPartialModels.GAUGE_NEEDLE, referenceState())
                .translate(PIVOT_X, PIVOT_Y, 0)
                .rotate(angle, Direction.SOUTH)
                .translate(-PIVOT_X, -PIVOT_Y, 0)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }

    /**
     * The state the partials are buffered from, one for every gauge whichever form
     * and whichever range is being drawn. Catnip caches a buffered partial under the
     * partial alone, so the state the first caller passes is the state every later
     * caller silently gets — and left to each caller's own block that would be
     * whichever gauge, or whichever host block, happened to come into view first.
     */
    private static BlockState referenceState() {
        return ModBlocks.SQUARE_PROCEDURAL_GAUGE.get().defaultBlockState();
    }
}
