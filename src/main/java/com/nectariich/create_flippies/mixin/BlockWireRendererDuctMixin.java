package com.nectariich.create_flippies.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.wire.DuctWireRenderer;

import net.minecraft.client.renderer.MultiBufferSource;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE DUCTS 2: draws a placeable wire that runs through ducts with its parts
 * inside them hidden and shown as strokes on the ducts' lids instead
 * ({@link DuctWireRenderer}). Any other wire is left to Power Grid exactly as it
 * draws it.
 */
@Mixin(value = BlockWireRenderer.class, remap = false)
public class BlockWireRendererDuctMixin {

    @Inject(method = "render(Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity;FF"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void create_flippies$renderDucted(BlockWireEntity entity, float yaw, float partialTicks, PoseStack ms,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (DuctWireRenderer.render(entity, ms, buffers))
            ci.cancel();
    }
}
