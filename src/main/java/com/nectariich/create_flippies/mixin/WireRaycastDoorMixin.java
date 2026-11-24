package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.content.cabinet.CabinetDoorWires;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A wire to a control on a shut cabinet door is not drawn ({@link CabinetDoorWires}),
 * and is not hit either: it would hang, unseen, where the door stands open.
 */
@Mixin(value = { BlockWireEntity.class, HangingWireEntity.class }, remap = false)
public class WireRaycastDoorMixin {

    @Inject(method = "raycast", at = @At("HEAD"), cancellable = true)
    private void create_flippies$missWhenShut(Vec3 from, Vec3 to, CallbackInfoReturnable<Vec3> cir) {
        if (CabinetDoorWires.hidden((Entity) (Object) this))
            cir.setReturnValue(null);
    }
}
