package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerControl;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;

/**
 * Draws a breaker clipped to a rail.
 *
 * Everything about where it goes is read off the control's {@link ControlMount}:
 * the face the rail is on, the quarter turn its rails run at, and the
 * displacement that puts the breaker on one module of one rail. That is the same
 * mount {@code CircuitBreakerType.terminals} and the footprint are derived from,
 * so the breaker cannot be drawn anywhere its hitboxes are not.
 *
 * The switch is interpolated from the angle
 * {@link CircuitBreakerControl#tick()} eases towards on both sides of the
 * connection, so it swings rather than snapping between poses.
 */
public class CircuitBreakerControlRenderer implements ControlRenderer {

    /** Stateless; every breaker is drawn by the same code. */
    private static final CircuitBreakerControlRenderer INSTANCE = new CircuitBreakerControlRenderer();

    private CircuitBreakerControlRenderer() {}

    /** Claims every rating and pole count, mirroring CircuitBreakerType.register. */
    public static void register() {
        for (CircuitBreakerType type : CircuitBreakerType.all())
            ControlRenderers.register(type, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof CircuitBreakerControl breaker))
            return;
        ControlMount mount = breaker.mount();
        CircuitBreakerDrawing.draw(breaker.breaker(), mount.face(), mount.yaw(), mount.rotation(),
                Mth.lerp(partialTicks, breaker.prevSwitchAngle, breaker.switchAngle), mount.offset(), ms, buffer,
                light);
    }
}
