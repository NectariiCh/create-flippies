package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlock;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Draws a standalone alarm speaker through {@link AlarmSpeakerDrawing}. */
public class AlarmSpeakerRenderer implements BlockEntityRenderer<AlarmSpeakerBlockEntity> {

    public AlarmSpeakerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AlarmSpeakerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof AlarmSpeakerBlock))
            return;
        AlarmSpeakerDrawing.draw(state.getValue(AlarmSpeakerBlock.FACING), 0,
                state.getValue(AlarmSpeakerBlock.ROTATION), Vec3.ZERO, ms, buffer, light);
    }
}
