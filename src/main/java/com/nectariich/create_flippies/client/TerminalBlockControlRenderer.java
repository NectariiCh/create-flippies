package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockControl;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Draws a DIN terminal block clipped to a rail: one model per kind and nothing
 * that moves, drawn exactly as a power supply is.
 */
public class TerminalBlockControlRenderer implements ControlRenderer {

    /** Stateless; every terminal block is drawn by the same code. */
    private static final TerminalBlockControlRenderer INSTANCE = new TerminalBlockControlRenderer();

    private TerminalBlockControlRenderer() {}

    /** Claims every kind, mirroring TerminalBlockType.register. */
    public static void register() {
        for (TerminalBlockType type : TerminalBlockType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof TerminalBlockControl block))
            return;
        PartialModel model = ModPartialModels.TERMINAL_BLOCKS.get(block.terminalBlock());
        if (model == null)
            return;
        ControlMount mount = block.mount();

        // The PSU's chain, for the PSU's reasons
        CachedBuffers.partial(model, ModBlocks.DIN_RAIL.get().defaultBlockState())
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }
}
