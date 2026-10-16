package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a standalone alphanumeric display. The geometry and the lettering are
 * {@link AlphanumDisplayDrawing}'s, shared with the mounted form; all this adds is
 * where a block's device sits — centred on its own cell, aimed by the block state —
 * so the glass lands on the plate the block's hitboxes and terminals were built from.
 */
public class AlphanumDisplayRenderer implements BlockEntityRenderer<AlphanumDisplayBlockEntity> {

    public AlphanumDisplayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AlphanumDisplayBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof AlphanumDisplayBlock))
            return;
        Direction facing = state.getValue(AlphanumDisplayBlock.FACING);
        int rotation = state.getValue(AlphanumDisplayBlock.ROTATION);
        boolean backlit = be.isBacklit();
        // Geometry then lettering, which is the order they came out in when they were
        // one call. A standalone display is one device in one block, so it has nothing
        // to batch with and nothing to gain from the two passes a host draws in
        AlphanumDisplayDrawing.draw(be.getForm(), backlit, facing, 0, rotation, Vec3.ZERO, ms, buffer, light);
        AlphanumDisplayDrawing.print(be.getForm(), be.print(), be.displayedText(), backlit, facing, 0, rotation,
                Vec3.ZERO, ms, buffer, light);
    }
}
