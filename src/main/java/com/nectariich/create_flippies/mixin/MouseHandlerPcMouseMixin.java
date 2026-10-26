package com.nectariich.create_flippies.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.nectariich.create_flippies.client.pc.KeyboardCaptureScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * While a keyboard and mouse's capture screen gives the mouse to the PC (spec
 * 11.2.15), the movement that would turn the player goes to the screen instead,
 * and the camera stays still.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerPcMouseMixin {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void create_flippies$moveThePcMouse(CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof KeyboardCaptureScreen screen && screen.takesMouse()) {
            screen.mouseMovedBy(accumulatedDX, accumulatedDY);
            accumulatedDX = 0;
            accumulatedDY = 0;
            ci.cancel();
        }
    }
}
