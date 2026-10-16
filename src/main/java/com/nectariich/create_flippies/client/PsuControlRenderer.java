package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.psu.PsuControl;
import com.nectariich.create_flippies.content.psu.PsuType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a DIN-rail power supply clipped to a rail.
 *
 * One model per rating and nothing that moves, drawn exactly as the DIN PLC is:
 * everything about where it goes is read off the control's {@link ControlMount},
 * the same mount its footprint and terminals are built from, so it cannot be drawn
 * anywhere its hitboxes are not.
 */
public class PsuControlRenderer implements ControlRenderer {

    /** Stateless; every supply is drawn by the same code. */
    private static final PsuControlRenderer INSTANCE = new PsuControlRenderer();

    private PsuControlRenderer() {}

    /** Claims every rating, mirroring PsuType.register. */
    public static void register() {
        for (PsuType type : PsuType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof PsuControl psu))
            return;
        PartialModel model = ModPartialModels.PSUS.get(psu.psu());
        if (model == null)
            return;
        ControlMount mount = psu.mount();

        // The PLC's chain, for the PLC's reasons: T(offset) . Ry(yaw) . Rx(tilt) .
        // Ry(spin), outermost first, which is the transform ControlMount.point puts a
        // hitbox corner through. Cutout, like the PLC, so a painted hole reads as one.
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());
        CachedBuffers.partial(model, referenceState())
                .translate(mount.offset())
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw()))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(mount.face()))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(mount.rotation()))
                .light(light)
                .renderInto(ms, vertices);
    }

    /** The rail's state, for the reason PlcSmallControlRenderer.referenceState gives. */
    private static BlockState referenceState() {
        return ModBlocks.DIN_RAIL.get().defaultBlockState();
    }
}
