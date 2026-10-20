package com.nectariich.create_flippies.mixin;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;

import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WIRE BENDS 2: hits a bent cord along its legs, as {@link HangingWireEntityClientMixin}
 * does a bent hanging wire. Power Grid's own test measures against the one curve
 * between the ends, which a bent cord no longer follows; its pull-off-the-socket
 * click goes by where this hit lands.
 *
 * Client only, because Power Grid strips this method from the dedicated server.
 */
@Mixin(value = CordEntity.class, remap = false)
public class CordEntityClientMixin {

    @Inject(method = "raycast(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)"
            + "Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void create_flippies$raycastBent(Vec3 from, Vec3 to, CallbackInfoReturnable<Vec3> cir) {
        BendableWire wire = (BendableWire) this;
        if (wire.bendPoints().isEmpty())
            return;
        List<Vec3> path = wire.bendPath();
        if (path != null)
            cir.setReturnValue(BendGeometry.raycast(from, to, path, wire.bendHorizontalCoefficient(),
                    wire.bendVerticalCoefficient(), wire.bendThickness()));
    }
}
