package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.contactor.OpenContactorCoilControl;
import com.nectariich.create_flippies.content.contactor.OpenContactorCoilType;
import com.nectariich.create_flippies.content.contactor.OpenContactorControl;
import com.nectariich.create_flippies.content.contactor.OpenContactorGeometry;
import com.nectariich.create_flippies.content.contactor.OpenContactorType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * Draws the open contactor's modules and coil terminals.
 *
 * A module is its piece's body — single, left end, right end or centre, from what
 * is beside it — and the plunger on top, slid towards the plate by how far in it
 * is. A coil terminal is one still model.
 */
public class OpenContactorControlRenderer implements ControlRenderer {

    private static final OpenContactorControlRenderer INSTANCE = new OpenContactorControlRenderer();

    private OpenContactorControlRenderer() {}

    public static void register() {
        for (OpenContactorType type : OpenContactorType.all())
            ControlRenderers.register(type, INSTANCE);
        for (OpenContactorCoilType type : OpenContactorCoilType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        if (control instanceof OpenContactorCoilControl coil) {
            PartialModel model = ModPartialModels.CONTACTOR_OPEN_COILS.get(coil.coilType());
            if (model != null)
                place(model, coil.mount()).light(light).renderInto(ms, vertices);
            return;
        }
        if (!(control instanceof OpenContactorControl module))
            return;
        PartialModel body = ModPartialModels.CONTACTOR_OPEN_BODIES.get(module.piece());
        PartialModel plunger = ModPartialModels.CONTACTOR_OPEN_PLUNGERS.get(module.piece());
        if (body != null)
            place(body, module.mount()).light(light).renderInto(ms, vertices);
        if (plunger != null) {
            float in = Mth.lerp(partialTicks, module.prevPlunger, module.plunger);
            place(plunger, module.mount())
                    .translate(0, -in * OpenContactorGeometry.PLUNGER_TRAVEL / PlacementGrid.PIXELS_PER_BLOCK, 0)
                    .light(light)
                    .renderInto(ms, vertices);
        }
    }

    /** The PSU's chain, for the PSU's reasons. */
    private static SuperByteBuffer place(PartialModel model, ControlMount mount) {
        return CachedBuffers.partial(model, ModBlocks.DIN_RAIL.get().defaultBlockState())
                .translate(mount.offset())
                .rotateCentered(SurfaceMount.tipping(mount.yaw()))
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()));
    }
}
