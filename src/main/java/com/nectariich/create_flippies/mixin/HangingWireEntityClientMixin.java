package com.nectariich.create_flippies.mixin;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;

import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hits a bent hanging wire along its legs. Power Grid's own test measures against
 * the one straight curve between the ends, which a bent wire no longer follows, so
 * a wire cutter or multimeter aimed at a bent leg would miss it.
 *
 * Client only, because Power Grid strips this method from the dedicated server.
 */
@Mixin(value = HangingWireEntity.class, remap = false)
public class HangingWireEntityClientMixin {

    @Inject(method = "raycast(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)"
            + "Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void create_flippies$raycastBent(Vec3 from, Vec3 to, CallbackInfoReturnable<Vec3> cir) {
        BendableWire wire = (BendableWire) this;
        if (wire.bendPoints().isEmpty())
            return;
        List<Vec3> path = wire.bendPath();
        if (path != null)
            cir.setReturnValue(BendGeometry.raycast(from, to, path, wire.bendDuctLegs(), wire.bendHorizontalCoefficient(),
                    wire.bendVerticalCoefficient(), wire.bendThickness()));
    }
}
