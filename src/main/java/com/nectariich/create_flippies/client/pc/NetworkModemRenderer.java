package com.nectariich.create_flippies.client.pc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.content.modem.NetworkModemEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws the network modem (spec 12.4.5): its shell, turned as it stands, and its two lights
 * the way the PC case draws its own: a lit light full bright in full colour, a dark one in world
 * light at about a third. The link light blinks every 2 ticks while frames move; the blinking is
 * local, so only the start and end of activity are synced.
 */
public class NetworkModemRenderer extends DeviceRenderer<NetworkModemEntity> {

    private static final int DARK = 89;

    public NetworkModemRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return ModPartialModels.NETWORK_MODEM;
    }

    @Override
    protected void renderModelSpace(NetworkModemEntity modem, float partialTicks, PoseStack ms,
            MultiBufferSource buffer, int light) {
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        long now = modem.level().getGameTime();
        boolean link = modem.linkLit() && !(modem.activity() && (now / 2) % 2 == 1);
        draw(ModPartialModels.MODEM_POWER_LED, modem.powerLit(), light, ms, vertices);
        draw(ModPartialModels.MODEM_LINK_LED, link, light, ms, vertices);
    }

    private static void draw(PartialModel model, boolean lit, int light, PoseStack ms, VertexConsumer vertices) {
        int shade = lit ? 255 : DARK;
        CachedBuffers.partial(model, Blocks.AIR.defaultBlockState())
                .light(lit ? LightTexture.FULL_BRIGHT : light)
                .color(shade, shade, shade, 255)
                .renderInto(ms, vertices);
    }
}
