package com.nectariich.create_flippies.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.wire.BentCordRenderer;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.patryk3211.powergrid.electricity.wire.powercord.CordRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE BENDS 2: draws a bent cord, power or string light, with {@link BentCordRenderer};
 * an unbent one is left to Power Grid. The plug and the hook a string light cord
 * hangs its bulbs from are Power Grid's own, handed over from here.
 *
 * As with {@link HangingWireRendererMixin}, the target is the typed overload, which
 * keeps its name in Power Grid's production jar, so this injection is not remapped.
 */
@Mixin(value = CordRenderer.class, remap = false)
public abstract class CordRendererMixin {

    @Shadow
    private static void renderPlug(PoseStack matrices, MultiBufferSource consumers, BlockState referenceState,
            Direction facing, double x, double y, double z, int light) {
        throw new AssertionError();
    }

    @Shadow
    protected abstract void segmentRenderHook(CordEntity entity, PoseStack matrices,
            MultiBufferSource vertexConsumers, double x1, double y1, double z1, double x2, double y2, double z2,
            double offset, double length, boolean first, boolean last, int light);

    @Inject(method = "render(Lorg/patryk3211/powergrid/electricity/wire/powercord/CordEntity;FF"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void create_flippies$renderBent(CordEntity entity, float yaw, float partialTicks, PoseStack ms,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (((BendableWire) entity).bendPoints().isEmpty())
            return;
        if (BentCordRenderer.render(entity, ms, buffers,
                (state, facing, x, y, z, lit) -> renderPlug(ms, buffers, state, facing, x, y, z, lit),
                (x1, y1, z1, x2, y2, z2, offset, length, first, last, lit) -> segmentRenderHook(entity, ms, buffers,
                        x1, y1, z1, x2, y2, z2, offset, length, first, last, lit)))
            ci.cancel();
    }
}
