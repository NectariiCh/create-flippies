package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlock;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a standalone Square procedural gauge's dial face and needle over the bezel
 * its own block model provides.
 *
 * The geometry itself is {@link ProceduralGaugeDrawing}'s, shared with the mounted
 * form of the same gauge. All this adds is where a block's gauge sits: centred on
 * its own cell, with its plate against whatever the block state faces away from —
 * a gauge's FACING is where the dial looks, so the plate points the other way, which
 * is the direction a mount is named by.
 */
public class ProceduralGaugeRenderer<T extends ProceduralGaugeBlockEntity> implements BlockEntityRenderer<T> {

    public ProceduralGaugeRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(T be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof ProceduralGaugeBlock))
            return;
        ProceduralGaugeDrawing.drawDial(be.getFaceDefinition(),
                Mth.lerp(partialTicks, be.prevDialState, be.dialState),
                state.getValue(ProceduralGaugeBlock.FACING).getOpposite(), 0, 0, Vec3.ZERO, ms, buffer, light);
    }
}
