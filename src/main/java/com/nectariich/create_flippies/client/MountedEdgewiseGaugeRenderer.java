package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeControlType;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseVoltmeterControlType;
import com.nectariich.create_flippies.content.procedural_gauge.MountedEdgewiseGauge;
import com.nectariich.create_flippies.content.procedural_gauge.VoltmeterRange;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * Draws an edgewise gauge (a voltmeter or the link-driven gauge) mounted inside a
 * host block: its body from a partial, since a mounted control has no block state to
 * bake a model from, then its face and pointer, all placed from the control's {@link ControlMount} as
 * {@link MountedGaugeRenderer} places the Square gauges.
 */
public class MountedEdgewiseGaugeRenderer implements ControlRenderer {

    private static final MountedEdgewiseGaugeRenderer INSTANCE = new MountedEdgewiseGaugeRenderer();

    private MountedEdgewiseGaugeRenderer() {}

    public static void register() {
        for (VoltmeterRange range : VoltmeterRange.values())
            ControlRenderers.register(EdgewiseVoltmeterControlType.of(range), INSTANCE);
        ControlRenderers.register(EdgewiseProceduralGaugeControlType.INSTANCE, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof MountedEdgewiseGauge gauge))
            return;
        ControlMount mount = control.mount();
        EdgewiseGaugeDrawing.drawBody(mount.face(), mount.yaw(), mount.rotation(), mount.offset(), ms, buffer, light);
        EdgewiseGaugeDrawing.drawDial(gauge.faceDefinition(),
                Mth.lerp(partialTicks, gauge.prevDialState(), gauge.dialState()), mount.face(), mount.yaw(),
                mount.rotation(), mount.offset(), ms, buffer, light);
    }
}
