package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControl;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControlType;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Draws an alphanumeric display mounted inside a host block, through the same
 * {@link AlphanumDisplayDrawing} the standalone blocks go through.
 *
 * One renderer serves every kind: what differs between them is the body the glass is
 * cut in and how much voltage the device wants, and both are settled before anything
 * is drawn. Everything about where it goes is read off the control's
 * {@link ControlMount} — the face it was screwed to, the quarter turn it went on at,
 * and the displacement that moves it off the middle of the cell — which is the same
 * mount object the terminals and the footprint are derived from, so a display cannot
 * be drawn anywhere its hitboxes are not.
 *
 * The lettering goes in the second pass, for the reason {@link ControlRenderer}
 * gives: a panel of readouts is exactly the case where interleaving the font with the
 * block geometry costs a draw call per device.
 */
public class AlphanumDisplayControlRenderer implements ControlRenderer {

    /** Stateless, and every kind of display is drawn by the same code. */
    private static final AlphanumDisplayControlRenderer INSTANCE = new AlphanumDisplayControlRenderer();

    private AlphanumDisplayControlRenderer() {}

    /** Claims every display kind, mirroring {@code AlphanumDisplayControlType.register}. */
    public static void register() {
        for (DisplayKind kind : DisplayKind.values())
            ControlRenderers.register(AlphanumDisplayControlType.of(kind), INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof AlphanumDisplayControl display))
            return;
        ControlMount mount = display.mount();
        AlphanumDisplayDrawing.draw(display.form(), display.isBacklit(), mount.face(), mount.yaw(),
                mount.rotation(), mount.offset(), ms, buffer, light);
    }

    @Override
    public void renderLegend(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        if (!(control instanceof AlphanumDisplayControl display))
            return;
        ControlMount mount = display.mount();
        AlphanumDisplayDrawing.print(display.form(), display.print(), display.displayedText(), display.isBacklit(),
                mount.face(), mount.yaw(), mount.rotation(), mount.offset(), ms, buffer, light);
    }
}
