package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerModel;
import com.nectariich.create_flippies.content.circuit_breaker.CircuitBreakerType;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A circuit breaker's body and its switch, drawn where the rail put it.
 *
 * The two are separate models because only one of them moves. The body is drawn
 * at the mount and nothing else; the switch is drawn at the mount and then swung
 * about its hinge, which is a line inside the breaker and not the middle of the
 * block — so it needs the pivot spelt out rather than one of the {@code Centered}
 * helpers.
 *
 * Cutout, because the breaker's textures have transparent pixels.
 */
public final class CircuitBreakerDrawing {

    private static final double PIXEL = 1.0 / PlacementGrid.PIXELS_PER_BLOCK;

    private CircuitBreakerDrawing() {}

    /** {@code offset} is the displacement the control's {@link ControlMount} gives. */
    public static void draw(CircuitBreakerType breaker, Direction facing, int yaw, int rotation, float switchAngle,
            Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        BlockState reference = referenceState();
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());

        place(CachedBuffers.partial(ModPartialModels.CIRCUIT_BREAKER_BODIES.get(breaker), reference), facing, yaw, rotation, offset)
                .light(light)
                .renderInto(ms, vertices);

        // T(pivot) . Rx(angle) . T(-pivot), applied inside the orientation so the
        // switch swings about its own hinge wherever the breaker has been aimed.
        // The hinge runs along x, so only its y and z say anything: a two or three
        // pole bar turns about the same line as a one pole lever, however wide it
        // is, and that is exactly what ganging them means.
        place(CachedBuffers.partial(ModPartialModels.CIRCUIT_BREAKER_SWITCHES.get(breaker), reference), facing,
                yaw, rotation, offset)
                .translate(0, CircuitBreakerModel.PIVOT_Y * PIXEL, CircuitBreakerModel.PIVOT_Z * PIXEL)
                .rotateXDegrees(switchAngle)
                .translateBack(0, CircuitBreakerModel.PIVOT_Y * PIXEL, CircuitBreakerModel.PIVOT_Z * PIXEL)
                .light(light)
                .renderInto(ms, vertices);
    }

    /**
     * {@code T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin)}, which is the transform
     * {@link ControlMount#point} puts a hitbox corner through, so the drawn breaker
     * lands on the boxes it is picked and wired by.
     *
     * A SuperByteBuffer accumulates into a PoseStack and so applies its calls
     * outermost first: the displacement happens in the rail's space, after the
     * breaker has been aimed, so it comes first in the chain.
     */
    private static SuperByteBuffer place(SuperByteBuffer buffer, Direction facing, int yaw, int rotation,
            Vec3 offset) {
        return buffer
                .translate(offset)
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(facing, yaw))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(facing))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(rotation));
    }

    /**
     * The state the partials are buffered from. Catnip caches a buffered partial
     * under the partial alone, so the state the first caller passes is the state
     * every later caller silently gets — and a breaker is only ever drawn on a
     * rail, so the rail's own state is the one to bake it against.
     */
    private static BlockState referenceState() {
        return ModBlocks.DIN_RAIL.get().defaultBlockState();
    }
}
