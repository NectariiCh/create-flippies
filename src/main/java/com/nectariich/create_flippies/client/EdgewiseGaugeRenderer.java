package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseGaugeBlock;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseGaugeBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a standalone edgewise gauge's face and pointer over the bezel its own block
 * model provides, placed as {@link ProceduralGaugeRenderer} places a Square gauge's
 * dial. Serves the voltmeters and the link-driven gauge alike.
 */
public class EdgewiseGaugeRenderer<T extends EdgewiseGaugeBlockEntity> implements BlockEntityRenderer<T> {

    public EdgewiseGaugeRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(T be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof EdgewiseGaugeBlock))
            return;
        EdgewiseGaugeDrawing.drawDial(be.getFaceDefinition(), Mth.lerp(partialTicks, be.prevDialState, be.dialState),
                state.getValue(EdgewiseGaugeBlock.FACING).getOpposite(), 0, 0, Vec3.ZERO, ms, buffer, light);
    }
}
