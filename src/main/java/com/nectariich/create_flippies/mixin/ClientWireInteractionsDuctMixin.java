package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.wire.WireDuctInput;

import net.minecraft.client.Minecraft;

import org.patryk3211.powergrid.electricity.wire.ClientWireInteractions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WIRE DUCTS 2: Power Grid swallows a sneaking right click with its wire cutters
 * before the game sees it, to clear a half-chosen cut. Aimed at a placeable wire
 * that runs through ducts (or at a duct holding one), that click cuts the whole
 * wire instead ({@link WireDuctInput#cutDuctedWire}). Anywhere else Power Grid
 * handles it as before.
 */
@Mixin(value = ClientWireInteractions.class, remap = false)
public class ClientWireInteractionsDuctMixin {

    @Inject(method = "cutClearCheck(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true)
    private static void create_flippies$cutDuctedWire(Minecraft client, CallbackInfoReturnable<Boolean> cir) {
        if (WireDuctInput.cutDuctedWire(client))
            cir.setReturnValue(true);
    }
}
