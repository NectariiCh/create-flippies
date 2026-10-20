package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a gauged wire its conductor's burn-out temperature.
 *
 * Power Grid heats a wire from the current through it against the thermal mass its
 * {@code wire_types} entry gives, but fails every wire at the same 175 degrees. A
 * gauged wire, and Power Grid's own four as its 12 AWG tier, fails instead at 80% of
 * its metal's melting point, which an entry cannot carry, so it is set here as the
 * wire's item is assigned. Power Plant Grid does the same for its wires, so with it
 * installed this stands aside.
 */
@Mixin(value = BaseWireEntity.class, remap = false)
public class BaseWireEntityGaugeMixin {

    @Shadow
    protected float overheatTemperature;

    @Inject(method = "setItem", at = @At("TAIL"))
    private void create_flippies$gaugeOverheatTemperature(Item item, int count, CallbackInfo ci) {
        if (WireGaugeCompat.ppgLoaded())
            return;
        Float temperature = WireGauges.overheatTemperature(BuiltInRegistries.ITEM.getKey(item).toString());
        if (temperature != null)
            overheatTemperature = temperature;
    }
}
