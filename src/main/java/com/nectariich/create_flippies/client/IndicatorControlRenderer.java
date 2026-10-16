package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.indicator.IndicatorControl;
import com.nectariich.create_flippies.content.indicator.IndicatorControlType;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Draws an indicator lamp mounted inside a host block, through the same
 * {@link IndicatorDrawing} the standalone block goes through.
 *
 * Everything about where it goes is read off the control's {@link ControlMount}:
 * the face it was screwed to, the quarter turn it was screwed on at, and the
 * displacement that moves it off the middle of the cell. That is the same mount
 * object {@code IndicatorControlType.terminals} and the footprint are derived from,
 * so the lamp cannot be drawn anywhere its hitboxes are not.
 */
public class IndicatorControlRenderer implements ControlRenderer {

    /** Stateless, and every lamp and every lens is drawn by the same code. */
    private static final IndicatorControlRenderer INSTANCE = new IndicatorControlRenderer();

    private IndicatorControlRenderer() {}

    /** Claims every kind, mirroring {@code IndicatorControlType.register}. */
    public static void register() {
        for (IndicatorKind kind : IndicatorKind.values())
            ControlRenderers.register(IndicatorControlType.of(kind), INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof IndicatorControl lamp))
            return;
        ControlMount mount = lamp.mount();
        IndicatorDrawing.draw(lamp.size(), lamp.color(), lamp.isLit(), lamp.isPressed(),
                mount.face(), mount.yaw(), mount.rotation(), mount.offset(), ms, buffer, light);
    }

    @Override
    public void renderLegend(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        if (!(control instanceof IndicatorControl lamp))
            return;
        ControlMount mount = lamp.mount();
        IndicatorDrawing.legend(lamp.size(), lamp.legend(), lamp.isPressed(), mount.face(), mount.yaw(),
                mount.rotation(), mount.offset(), ms, buffer, light);
    }
}
