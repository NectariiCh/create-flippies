package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.indicator.IndicatorBlock;
import com.nectariich.create_flippies.content.indicator.IndicatorBlockEntity;

import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a standalone indicator. The geometry is {@link IndicatorDrawing}'s, shared
 * with the mounted form; all this adds is where a block's unit sits — centred on its
 * own cell, aimed by the block state — so the cap lands on the plate the block's
 * hitboxes and terminals were built from.
 */
public class IndicatorRenderer implements BlockEntityRenderer<IndicatorBlockEntity> {

    public IndicatorRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(IndicatorBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof IndicatorBlock))
            return;
        Direction facing = state.getValue(IndicatorBlock.FACING);
        int rotation = state.getValue(IndicatorBlock.ROTATION);
        // Geometry then lettering, which is the order they came out in when they were
        // one call. A standalone unit is one control in one block, so it has nothing to
        // batch with and nothing to gain from the two passes a host draws in
        IndicatorDrawing.draw(be.size(), be.color(), be.isLit(), be.isPressed(), facing, 0, rotation, Vec3.ZERO,
                ms, buffer, light);
        IndicatorDrawing.legend(be.size(), be.legend(), be.isPressed(), facing, 0, rotation, Vec3.ZERO,
                ms, buffer, light);
    }
}
