package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControlType;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * Draws a rotary switch mounted inside a host block, through the same
 * {@link RotarySwitchDrawing} the standalone block goes through.
 *
 * Everything about where it goes is read off the control's {@link ControlMount}:
 * the face it was screwed to, the quarter turn it was screwed on at, and the
 * displacement that moves it off the middle of the cell. That is the same mount
 * object {@code RotarySwitchControlType.terminals} and the footprint are derived
 * from, so the switch cannot be drawn anywhere its hitboxes are not.
 *
 * The handle is interpolated exactly as the block form's is, from the angle
 * {@link RotarySwitchControl#tick()} eases towards the current detent on both
 * sides of the connection.
 */
public class RotarySwitchControlRenderer implements ControlRenderer {

    /** Stateless, and every switch and every appearance is drawn by the same code. */
    private static final RotarySwitchControlRenderer INSTANCE = new RotarySwitchControlRenderer();

    private RotarySwitchControlRenderer() {}

    /** Claims every switch, mirroring {@code RotarySwitchControlType.register}. */
    public static void register() {
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            ControlRenderers.register(RotarySwitchControlType.of(kind), INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof RotarySwitchControl rotary))
            return;
        ControlMount mount = rotary.mount();
        RotarySwitchDrawing.draw(rotary.appearance(), mount.face(), mount.yaw(), mount.rotation(),
                Mth.lerp(partialTicks, rotary.prevHandleAngle, rotary.handleAngle), mount.offset(), ms, buffer, light);
    }
}
