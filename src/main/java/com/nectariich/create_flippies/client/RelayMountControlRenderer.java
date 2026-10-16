package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.relay.RelayMountControl;
import com.nectariich.create_flippies.content.relay.RelayMountType;
import com.nectariich.create_flippies.content.relay.RelayType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Draws a relay mount clipped to a rail: the empty mount always, and the relay
 * fitted in it on top. The relay partial is drawn in the export's own frame, so it
 * lands in the socket by being put through the same transform as the mount.
 *
 * Relays go through the translucent buffer: the 8-pin relay's case is clear
 * plastic, and cutout would draw it solid. An opaque relay looks the same either way.
 */
public class RelayMountControlRenderer implements ControlRenderer {

    private static final RelayMountControlRenderer INSTANCE = new RelayMountControlRenderer();

    private RelayMountControlRenderer() {}

    public static void register() {
        for (RelayMountType type : RelayMountType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof RelayMountControl mount))
            return;
        PartialModel body = ModPartialModels.RELAY_MOUNTS.get((RelayMountType) mount.type());
        if (body != null)
            draw(body, mount.mount(), ms, buffer.getBuffer(RenderType.cutout()), light);
        RelayType relay = mount.relay();
        PartialModel fitted = relay == null ? null : ModPartialModels.RELAYS.get(relay);
        if (fitted != null)
            draw(fitted, mount.mount(), ms, buffer.getBuffer(RenderType.translucent()), light);
    }

    /** The PSU's chain, for the PSU's reasons. */
    private static void draw(PartialModel model, ControlMount mount, PoseStack ms, VertexConsumer vertices,
            int light) {
        CachedBuffers.partial(model, ModBlocks.DIN_RAIL.get().defaultBlockState())
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, vertices);
    }
}
