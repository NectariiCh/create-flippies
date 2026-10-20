package com.nectariich.create_flippies.client.pc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.content.pc.PcSlot;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws what changes on a PC case (spec 11.2.1): the front panel while it is on,
 * every installed part, the inserted floppy disk, the two buttons (pushed in
 * 0.125 px for 4 ticks after a press), the two lights, and Power Grid's cord's
 * plug while one is in the socket. The shell is the baked block model.
 *
 * Every part model keeps the coordinates it was authored at in the case model, so
 * the only transform is the block state's turn. A lit light is drawn full bright in
 * full colour; a dark one in world light at about a third of its colour.
 */
public class PcCaseRenderer implements BlockEntityRenderer<PcCaseBlockEntity> {

    /** How far a pressed button sinks, blocks: 0.125 px. */
    private static final float PRESS_DEPTH = 0.125f / 16;
    /** A dark light's colour, out of 255: about 0.35 of full. */
    private static final int DARK = 89;

    public PcCaseRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(PcCaseBlockEntity pc, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        BlockState state = pc.getBlockState();
        float yRot = -PcCaseGeometry.yRotationDegrees(pc.facing());
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());

        for (PcSlot slot : pc.filled())
            draw(ModPartialModels.pcPart(slot), state, yRot, 0, light, 255, ms, vertices);

        long now = pc.getLevel() == null ? 0 : pc.getLevel().getGameTime();
        draw(ModPartialModels.PC_POWER_BUTTON, state, yRot, pressed(pc.powerPressTick(), now), light, 255, ms, vertices);
        draw(ModPartialModels.PC_RESET_BUTTON, state, yRot, pressed(pc.resetPressTick(), now), light, 255, ms, vertices);
        drawLight(ModPartialModels.PC_POWER_LED, pc.powerLit(), state, yRot, light, ms, vertices);
        drawLight(ModPartialModels.PC_ERROR_LED, pc.errorLit(), state, yRot, light, ms, vertices);

        if (pc.socketWired())
            draw(ModPartialModels.PC_POWER_PLUG, state, yRot, 0, light, 255, ms, vertices);
    }

    private static float pressed(long pressTick, long now) {
        long since = now - pressTick;
        return since >= 0 && since < PcCaseBlockEntity.PRESS_TICKS ? -PRESS_DEPTH : 0;
    }

    private static void drawLight(PartialModel model, boolean lit, BlockState state, float yRot, int light,
            PoseStack ms, VertexConsumer vertices) {
        draw(model, state, yRot, 0, lit ? LightTexture.FULL_BRIGHT : light, lit ? 255 : DARK, ms, vertices);
    }

    private static void draw(PartialModel model, BlockState state, float yRot, float dy, int light, int shade,
            PoseStack ms, VertexConsumer vertices) {
        CachedBuffers.partial(model, state)
                .rotateYCenteredDegrees(yRot)
                .translate(0, dy, 0)
                .light(light)
                .color(shade, shade, shade, 255)
                .renderInto(ms, vertices);
    }
}
