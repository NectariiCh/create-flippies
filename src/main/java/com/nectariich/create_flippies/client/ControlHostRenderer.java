package com.nectariich.create_flippies.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
 * Each control is lit by what is in front of it rather than by the light handed in,
 * which is the host cell's own. A large panel is usually built into a wall and lets
 * light through, so its cell carries the daylight from outside the wall; a control
 * drawn at that light glows in a dark room. So the light is read {@link #REACH} of a
 * block out from the control's plate on the operator side, the rule
 * {@link PanelQuadLight} lights the panel plate by.
 *
 * Generic in the host so that a block which hosts controls and is something else
 * besides — a run of DIN rail carrying breakers, a large panel cell on a chamfer —
 * can be drawn by this same renderer. A {@code BlockEntityRenderer} is invariant in
 * its block entity, so a renderer typed to one carrier could not be registered for
 * another.
 */
public class ControlHostRenderer<T extends BlockEntity & ControlRackHolder> implements BlockEntityRenderer<T> {

    /** How far in front of a control's plate its light is read, in blocks. */
    private static final double REACH = 0.5;

    public ControlHostRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(T host, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        // Every cell of a panelled wall is one of these, and nearly all of them are
        // empty. Asking the rack directly avoids wrapping its list once per cell per
        // frame for the overwhelming majority that have nothing to draw
        if (host.rack().isEmpty())
            return;
        List<HostedControl> controls = host.controls();
        int[] lights = new int[controls.size()];
        int index = 0;
        for (HostedControl control : controls)
            lights[index++] = lightInFront(host, control, light);
        index = 0;
        for (HostedControl control : controls) {
            ControlRenderer renderer = ControlRenderers.get(control.type());
            int lit = lights[index++];
            if (renderer == null)
                continue;
            ms.pushPose();
            renderer.render(control, partialTicks, ms, buffer, lit, overlay);
            ms.popPose();
        }
        index = 0;
        for (HostedControl control : controls) {
            ControlRenderer renderer = ControlRenderers.get(control.type());
            int lit = lights[index++];
            if (renderer == null)
                continue;
            ms.pushPose();
            renderer.renderLegend(control, partialTicks, ms, buffer, lit, overlay);
            ms.popPose();
        }
    }

    /**
     * The light in front of a control: the plate faces into the surface, so the
     * operator side is the opposite way. A solid block there says nothing about the
     * light falling on the control, and the host's own light is kept.
     */
    private static int lightInFront(BlockEntity host, HostedControl control, int fallback) {
        Level level = host.getLevel();
        if (level == null)
            return fallback;
        Vec3 point = Vec3.atLowerCornerOf(host.getBlockPos())
                .add(control.mount().centre())
                .subtract(control.mount().alongDepth().scale(REACH));
        BlockPos at = BlockPos.containing(point);
        if (at.equals(host.getBlockPos()))
            return fallback;
        BlockState state = level.getBlockState(at);
        if (state.isSolidRender(level, at))
            return fallback;
        return LevelRenderer.getLightColor(level, state, at);
    }
}
