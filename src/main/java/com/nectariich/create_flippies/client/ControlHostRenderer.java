package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Draws everything mounted in one host cell.
 *
 * A host has no appearance of its own — its block model carries a particle
 * texture and not one face — so this is the only thing that ever draws a mounted
 * control. Without it a panel is a cell of working hitboxes and working terminals
 * with nothing visible in it, which is exactly how it looked before this existed.
 *
 * Every control is drawn from the pose this renderer was handed rather than from
 * wherever the previous one left the stack, because a control that moved the stack
 * would move all the controls mounted after it.
 *
 * It takes two passes over the controls and not one, and {@link ControlRenderer} says
 * why: geometry and printed lettering go into render types that share a buffer, so
 * interleaving them costs a draw call per control instead of a draw call per block.
 * The second pass is skipped for every kind that prints nothing, which is most of
 * them, but it is the two dozen labels on a panel that the pass exists for and they
 * all print.
 *
 * Generic in the host so that a block which hosts controls and is something else
 * besides — a run of DIN rail carrying breakers, a large panel cell on a chamfer —
 * can be drawn by this same renderer. A {@code BlockEntityRenderer} is invariant in
 * its block entity, so a renderer typed to one carrier could not be registered for
 * another.
 */
public class ControlHostRenderer<T extends BlockEntity & ControlRackHolder> implements BlockEntityRenderer<T> {

    public ControlHostRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(T host, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        // Every cell of a panelled wall is one of these, and nearly all of them are
        // empty. Asking the rack directly avoids wrapping its list once per cell per
        // frame for the overwhelming majority that have nothing to draw
        if (host.rack().isEmpty())
            return;
        for (HostedControl control : host.controls()) {
            ControlRenderer renderer = ControlRenderers.get(control.type());
            if (renderer == null)
                continue;
            ms.pushPose();
            renderer.render(control, partialTicks, ms, buffer, light, overlay);
            ms.popPose();
        }
        for (HostedControl control : host.controls()) {
            ControlRenderer renderer = ControlRenderers.get(control.type());
            if (renderer == null)
                continue;
            ms.pushPose();
            renderer.renderLegend(control, partialTicks, ms, buffer, light, overlay);
            ms.popPose();
        }
    }
}
