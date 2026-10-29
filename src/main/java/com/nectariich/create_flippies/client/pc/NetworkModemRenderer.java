package com.nectariich.create_flippies.client.pc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the network modem's two lights (spec 12.4.5) the way the PC case draws its own: a lit
 * light full bright in full colour, a dark one in world light at about a third. The link light
 * blinks every 2 ticks while frames move; the blinking is local, so only the start and end of
 * activity are synced.
 */
public class NetworkModemRenderer implements BlockEntityRenderer<NetworkModemBlockEntity> {

    private static final int DARK = 89;

    public NetworkModemRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(NetworkModemBlockEntity modem, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        BlockState state = modem.getBlockState();
        float yRot = -PcCaseGeometry.yRotationDegrees(modem.facing());
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        long now = modem.getLevel() == null ? 0 : modem.getLevel().getGameTime();
        boolean link = modem.linkLit() && !(modem.activity() && (now / 2) % 2 == 1);
        draw(ModPartialModels.MODEM_POWER_LED, modem.powerLit(), state, yRot, light, ms, vertices);
        draw(ModPartialModels.MODEM_LINK_LED, link, state, yRot, light, ms, vertices);
    }

    private static void draw(PartialModel model, boolean lit, BlockState state, float yRot, int light, PoseStack ms,
            VertexConsumer vertices) {
        int shade = lit ? 255 : DARK;
        CachedBuffers.partial(model, state)
                .rotateYCenteredDegrees(yRot)
                .light(lit ? LightTexture.FULL_BRIGHT : light)
                .color(shade, shade, shade, 255)
                .renderInto(ms, vertices);
    }
}
