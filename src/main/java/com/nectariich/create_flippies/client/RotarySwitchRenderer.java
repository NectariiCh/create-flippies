package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.foundation.SurfaceMount;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a standalone rotary switch. Both halves are partials rather than a baked
 * block model: a block state variant cannot reach every orientation the switch can
 * be placed in, and cannot turn the handle 45 degrees at all — see
 * {@link RotarySwitchBlock}.
 *
 * The geometry itself is {@link RotarySwitchDrawing}'s, shared with the mounted
 * form of the same switch. All this adds is where a block's switch sits: centred
 * on its own cell, aimed by the block state, in the orientation
 * {@link SurfaceMount} defines — so the handle lands on the plate the block's
 * hitboxes and terminals were built from.
 */
public class RotarySwitchRenderer implements BlockEntityRenderer<RotarySwitchBlockEntity> {

    public RotarySwitchRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(RotarySwitchBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof RotarySwitchBlock))
            return;
        RotarySwitchDrawing.draw(be.appearance(), state.getValue(RotarySwitchBlock.FACING), 0,
                state.getValue(RotarySwitchBlock.ROTATION),
                Mth.lerp(partialTicks, be.prevHandleAngle, be.handleAngle), Vec3.ZERO, ms, buffer, light);
    }
}
