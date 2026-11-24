package com.nectariich.create_flippies.client.pc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.content.pc.PcCaseEntity;
import com.nectariich.create_flippies.content.pc.PcSlot;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws a PC case (spec 11.2.1): the shell, turned as it stands, and what changes on
 * it: the front panel while it is on, every installed part, the inserted floppy disk,
 * the two buttons (pushed in 0.125 px for 4 ticks after a press), the two lights, and
 * Power Grid's cord's plug while one is in the socket.
 *
 * Every part model keeps the coordinates it was authored at in the case model, so
 * each is drawn in the model's frame as it is. A lit light is drawn full bright in
 * full colour; a dark one in world light at about a third of its colour.
 */
public class PcCaseRenderer extends DeviceRenderer<PcCaseEntity> {

    /** How far a pressed button sinks, blocks: 0.125 px. */
    private static final float PRESS_DEPTH = 0.125f / 16;
    /** A dark light's colour, out of 255: about 0.35 of full. */
    private static final int DARK = 89;

    public PcCaseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return ModPartialModels.PC_CASE;
    }

    @Override
    protected void renderModelSpace(PcCaseEntity pc, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light) {
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());

        for (PcSlot slot : pc.filled())
            draw(ModPartialModels.pcPart(slot), 0, light, 255, ms, vertices);

        long now = pc.level().getGameTime();
        draw(ModPartialModels.PC_POWER_BUTTON, pressed(pc.powerPressTick(), now), light, 255, ms, vertices);
        draw(ModPartialModels.PC_RESET_BUTTON, pressed(pc.resetPressTick(), now), light, 255, ms, vertices);
        drawLight(ModPartialModels.PC_POWER_LED, pc.powerLit(), light, ms, vertices);
        drawLight(ModPartialModels.PC_ERROR_LED, pc.errorLit(), light, ms, vertices);

        if (pc.socketWired())
            draw(ModPartialModels.PC_POWER_PLUG, 0, light, 255, ms, vertices);
    }

    private static float pressed(long pressTick, long now) {
        long since = now - pressTick;
        return since >= 0 && since < PcCaseEntity.PRESS_TICKS ? -PRESS_DEPTH : 0;
    }

    private static void drawLight(PartialModel model, boolean lit, int light, PoseStack ms, VertexConsumer vertices) {
        draw(model, 0, lit ? LightTexture.FULL_BRIGHT : light, lit ? 255 : DARK, ms, vertices);
    }

    private static void draw(PartialModel model, float dy, int light, int shade, PoseStack ms,
            VertexConsumer vertices) {
        CachedBuffers.partial(model, Blocks.AIR.defaultBlockState())
                .translate(0, dy, 0)
                .light(light)
                .color(shade, shade, shade, 255)
                .renderInto(ms, vertices);
    }
}
