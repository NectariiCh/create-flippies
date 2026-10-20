package com.nectariich.create_flippies.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.wire.BentWireRenderer;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;

import net.minecraft.client.renderer.MultiBufferSource;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.electricity.wire.HangingWireRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a bent hanging wire leg by leg; an unbent one is left to Power Grid.
 *
 * The target is the typed overload, which keeps its name in Power Grid's
 * production jar; only the erased bridge is renamed to its SRG name, so this
 * injection is not remapped.
 */
@Mixin(value = HangingWireRenderer.class, remap = false)
public class HangingWireRendererMixin {

    @Inject(method = "render(Lorg/patryk3211/powergrid/electricity/wire/HangingWireEntity;FF"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void create_flippies$renderBent(HangingWireEntity entity, float yaw, float partialTicks, PoseStack ms,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (((BendableWire) entity).bendPoints().isEmpty())
            return;
        if (BentWireRenderer.render(entity, ms, buffers))
            ci.cancel();
    }
}
