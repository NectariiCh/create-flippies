package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names Power Grid's own four wires as the "(12 AWG)" tier of the gauged family.
 *
 * A lang override would compete with Power Grid's own for the same key, so the gauge
 * is appended to the name Power Grid translates. A renamed stack keeps its name.
 * Power Plant Grid appends the same suffix, so with it installed this stands aside.
 */
@Mixin(ItemStack.class)
public class ItemStackWireGaugeMixin {

    @Inject(method = "getHoverName()Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
    private void create_flippies$appendGauge(CallbackInfoReturnable<Component> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        if (!(stack.getItem() instanceof WireItem) || WireGaugeCompat.ppgLoaded())
            return;
        if (stack.hasCustomHoverName())
            return;
        if (!WireGauges.isPowerGrid12Awg(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
            return;
        Component base = Component.translatable(stack.getItem().getDescriptionId(stack));
        cir.setReturnValue(base.copy().append(Component.literal(" (12 AWG)")));
    }
}
