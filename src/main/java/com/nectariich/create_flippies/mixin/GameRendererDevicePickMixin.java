package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.device.DevicePicker;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a device the crosshair's target when it is the nearest thing under it, as a
 * block would be. Devices are not pickable (their bounding box is far larger than a
 * turned device), so vanilla's pick passes through them; this traces their turned
 * boxes once vanilla has picked, and puts the device in its place if it is nearer.
 */
@Mixin(GameRenderer.class)
public class GameRendererDevicePickMixin {

    // 1.21.1 overloads pick with a private (Entity, double, double, float) helper
    //? if forge {
    @Inject(method = "pick", at = @At("TAIL"))
    //?} else {
    /*@Inject(method = "pick(F)V", at = @At("TAIL"))
    *///?}
    private void create_flippies$pickDevices(float partialTicks, CallbackInfo ci) {
        DevicePicker.pickInto(partialTicks);
    }
}
