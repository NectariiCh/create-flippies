package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.plc.PlcSmallControl;
import com.nectariich.create_flippies.content.plc.PlcSmallControlType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a small PLC clipped to a rail.
 *
 * One model and nothing that moves, so unlike the breaker there is no separate
 * drawing class to share between a block form and a mounted form: the panel PLC
 * is a block with a block state, and is drawn from that rather than from here.
 *
 * Everything about where it goes is read off the control's {@link ControlMount} —
 * the face the rail is on, the quarter turn its rails run at, and the displacement
 * that puts the PLC on one module of one rail — which is the same mount its
 * footprint and hitboxes are built from, so it cannot be drawn anywhere it is not.
 */
public class PlcSmallControlRenderer implements ControlRenderer {

    /** Stateless; every PLC is drawn by the same code. */
    private static final PlcSmallControlRenderer INSTANCE = new PlcSmallControlRenderer();

    private PlcSmallControlRenderer() {}

    public static void register() {
        ControlRenderers.register(PlcSmallControlType.INSTANCE, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof PlcSmallControl plc))
            return;
        ControlMount mount = plc.mount();

        // T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin), the transform ControlMount.point
        // puts a hitbox corner through. A SuperByteBuffer accumulates into a
        // PoseStack and so applies its calls outermost first, which is why the
        // displacement — which happens in the rail's space, after the PLC has been
        // aimed — comes first in the chain.
        //
        // Cutout rather than solid: the body texture is a placeholder waiting to be
        // painted, and the first thing anyone drills into a front panel is a hole.
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        CachedBuffers.partial(ModPartialModels.PLC_S32F103_D_DIN, referenceState())
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, vertices);
    }

    /**
     * The state the partial is buffered from. Catnip caches a buffered partial
     * under the partial alone, so the state the first caller passes is the state
     * every later caller silently gets — and this one is only ever drawn on a rail,
     * so the rail's own state is the one to bake it against.
     */
    private static BlockState referenceState() {
        return ModBlocks.DIN_RAIL.get().defaultBlockState();
    }
}
