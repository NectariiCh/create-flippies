package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.procedural_gauge.MountedProceduralGauge;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterControlType;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeControlType;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * Draws a procedural gauge mounted inside a host block, through the same
 * {@link ProceduralGaugeDrawing} the standalone blocks go through.
 *
 * One renderer serves every mountable gauge. A voltmeter and a Square Procedural
 * Gauge differ only in where the needle's position comes from, which is settled
 * before anything is drawn, so the drawing is written against
 * {@link MountedProceduralGauge} rather than against either kind.
 *
 * Everything about where it goes is read off the control's {@link ControlMount}: the
 * face it was screwed to, the quarter turn it was screwed on at, and the displacement
 * that moves it off the middle of the cell. That is the same mount object the
 * terminals and the footprint are derived from, so a gauge cannot be drawn anywhere
 * its hitboxes are not.
 *
 * Unlike the standalone gauges this also draws the body, because a mounted control has
 * no block state for a block model to be baked from.
 */
public class MountedGaugeRenderer implements ControlRenderer {

    /** Stateless, and every kind of gauge is drawn by the same code. */
    private static final MountedGaugeRenderer INSTANCE = new MountedGaugeRenderer();

    private MountedGaugeRenderer() {}

    /** Claims every mountable gauge kind, mirroring the two control-type registrations. */
    public static void register() {
        for (VoltmeterRange range : VoltmeterRange.values())
            ControlRenderers.register(ProceduralVoltmeterControlType.of(range), INSTANCE);
        ControlRenderers.register(SquareProceduralGaugeControlType.INSTANCE, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof MountedProceduralGauge gauge))
            return;
        ControlMount mount = control.mount();
        ProceduralGaugeDrawing.drawBody(mount.face(), mount.yaw(), mount.rotation(), mount.offset(), ms, buffer,
                light);
        ProceduralGaugeDrawing.drawDial(gauge.faceDefinition(),
                Mth.lerp(partialTicks, gauge.prevDialState(), gauge.dialState()), mount.face(), mount.yaw(),
                mount.rotation(), mount.offset(), ms, buffer, light);
    }
}
