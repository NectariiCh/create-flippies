package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.contactor.ContactorControl;
import com.nectariich.create_flippies.content.contactor.ContactorType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Draws a DIN contactor clipped to a rail: one model per kind and nothing that
 * moves, drawn exactly as a terminal block is.
 */
public class ContactorControlRenderer implements ControlRenderer {

    private static final ContactorControlRenderer INSTANCE = new ContactorControlRenderer();

    private ContactorControlRenderer() {}

    /** Claims every kind, mirroring ContactorType.register. */
    public static void register() {
        for (ContactorType type : ContactorType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof ContactorControl contactor))
            return;
        PartialModel model = ModPartialModels.CONTACTORS.get(contactor.contactor());
        if (model == null)
            return;
        ControlMount mount = contactor.mount();

        // The PSU's chain, for the PSU's reasons
        CachedBuffers.partial(model, ModBlocks.DIN_RAIL.get().defaultBlockState())
                .translate(mount.offset())
                .rotateCentered(SurfaceMount.tipping(mount.yaw()))
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }
}
