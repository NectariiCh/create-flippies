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
 * Draws a small PLC, clipped to a rail or screwed to a panel.
 *
 * One model per mounting and nothing that moves, so unlike the breaker there is no
 * separate drawing class to share between a block form and a mounted form: neither
 * mounting has a block form, and both are drawn from here.
 *
 * Everything about where it goes is read off the control's {@link ControlMount}:
 * the face it is on, the quarter turn it is placed at, and the displacement that
 * puts it on one module of one rail or at the spot on a plate it was screwed to,
 * which is the same mount its footprint and hitboxes are built from, so it cannot
 * be drawn anywhere it is not.
 */
public class PlcSmallControlRenderer implements ControlRenderer {

    /** Stateless; every PLC is drawn by the same code. */
    private static final PlcSmallControlRenderer INSTANCE = new PlcSmallControlRenderer();

    private PlcSmallControlRenderer() {}

    public static void register() {
        ControlRenderers.register(PlcSmallControlType.DIN, INSTANCE);
        ControlRenderers.register(PlcSmallControlType.PANEL, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof PlcSmallControl plc))
            return;
        ControlMount mount = plc.mount();
        boolean panel = plc.type() == PlcSmallControlType.PANEL;

        // T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin), the transform ControlMount.point
        // puts a hitbox corner through. A SuperByteBuffer accumulates into a
        // PoseStack and so applies its calls outermost first, which is why the
        // displacement — which happens in the rail's space, after the PLC has been
        // aimed — comes first in the chain.
        //
        // Cutout rather than solid: the body texture is a placeholder waiting to be
        // painted, and the first thing anyone drills into a front panel is a hole.
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        CachedBuffers.partial(panel ? ModPartialModels.PLC_S32F103_D_PANEL : ModPartialModels.PLC_S32F103_D_DIN,
                referenceState(panel))
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, vertices);
    }

    /**
     * The state a partial is buffered from. Catnip caches a buffered partial
     * under the partial alone, so the state the first caller passes is the state
     * every later caller silently gets. Each mounting has its own partial and so
     * its own cache entry, baked against one fixed state rather than whichever host
     * came into view first. The DIN one is meant for a rail and is baked against the
     * rail's state, though nothing stops it being screwed to a panel too, where it
     * draws the same; the panel one is drawn on a bare host or a panel cell, so it is
     * baked against the bare host's, as the labels are.
     */
    private static BlockState referenceState(boolean panel) {
        return panel ? ModBlocks.CONTROL_HOST.get().defaultBlockState() : ModBlocks.DIN_RAIL.get().defaultBlockState();
    }
}
