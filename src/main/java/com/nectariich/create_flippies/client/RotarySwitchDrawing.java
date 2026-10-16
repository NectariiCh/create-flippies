package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAppearance;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The rotary switch's body and handle, drawn once for both forms the switch comes
 * in: the standalone block and the control mounted inside a host cell.
 *
 * Shared rather than written twice because the two have to be the same switch to
 * look at, and because a renderer that disagreed with the hitboxes about
 * orientation is a bug this project has had twice already. The two callers differ
 * in one argument. A block is centred on its cell and passes no displacement; a
 * mounted control passes the one its {@link ControlMount} gives, which is the
 * displacement its terminals and its footprint were built with.
 *
 * Cutout, because all three of the switch's textures have transparent pixels.
 */
public final class RotarySwitchDrawing {

    private RotarySwitchDrawing() {}

    /** {@code offset} is in block units and is {@link Vec3#ZERO} for a switch centred on its cell. */
    public static void draw(RotarySwitchAppearance appearance, Direction facing, int yaw, int rotation,
            float handleAngle, Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        BlockState reference = referenceState();
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        place(CachedBuffers.partial(ModPartialModels.rotarySwitchBody(appearance), reference), facing, yaw, rotation, 0,
                offset)
                .light(light)
                .renderInto(ms, vertices);
        place(CachedBuffers.partial(ModPartialModels.rotarySwitchHandle(appearance), reference), facing, yaw, rotation,
                handleAngle, offset)
                .light(light)
                .renderInto(ms, vertices);
    }

    /**
     * {@code T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin + handle)}, which is the
     * transform {@link ControlMount#point} puts a hitbox corner through, so the
     * drawn switch lands on the boxes it is picked and wired by.
     *
     * A SuperByteBuffer accumulates into a PoseStack and so applies its calls
     * outermost first. The spin about the switch's own mounting axis has to happen
     * in model space, before that axis is tilted anywhere, so it comes last in the
     * chain; the displacement happens in the host's space, after the switch has
     * been aimed, so it comes first.
     */
    private static SuperByteBuffer place(SuperByteBuffer buffer, Direction facing, int yaw, int rotation,
            float handleAngle, Vec3 offset) {
        return buffer
                .translate(offset)
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(facing, yaw))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(facing))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(rotation) + handleAngle);
    }

    /**
     * The state the partials are buffered from: one rotary switch block, whichever
     * form and whichever switch is being drawn.
     *
     * Catnip caches a buffered partial under the partial alone, so the state the
     * first caller passes is the state every later caller silently gets — and a host
     * block, which is a different block with a different render layer, would
     * otherwise be baking the standalone switch's geometry for it. Naming one block
     * here rather than the drawn switch's own settles that instead of racing it:
     * every rotary switch is the same block with the same properties as far as a
     * buffered partial can tell, so which one is named makes no difference.
     */
    private static BlockState referenceState() {
        return ModBlocks.ROTARY_SWITCHES.get(RotarySwitchKind.TWO_POSITION).get().defaultBlockState();
    }
}
