package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.ProtrudingHitboxPicker;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link IProtrudingHitbox} blocks be targeted through the geometry they
 * stick out into. This is the single ray cast behind both the client's
 * crosshair (via {@code GameRenderer.pick}) and Power Grid's server-side
 * re-pick in {@code WireItem.useOn}, so patching it here keeps the two in
 * agreement.
 */
@Mixin(Entity.class)
public class EntityPickMixin {

    @Inject(method = "pick", at = @At("RETURN"), cancellable = true)
    private void create_flippies$pickProtrudingHitboxes(double reach, float partialTicks, boolean hitFluids,
            CallbackInfoReturnable<HitResult> cir) {
        HitResult vanilla = cir.getReturnValue();
        HitResult extended = ProtrudingHitboxPicker.extend((Entity) (Object) this, reach, partialTicks, vanilla);
        if (extended != vanilla)
            cir.setReturnValue(extended);
    }
}
