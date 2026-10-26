package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.LegSag;
import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;

import net.minecraft.nbt.CompoundTag;

import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.powercord.CordEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two parts of wire bending that live on Power Grid's base wire entity, because
 * that is where it declares them; both act only on a wire that can bend (see
 * {@link HangingWireEntityBendMixin}) and has been.
 *
 * Power Grid prices a hanging wire in items by its straight span and derives the
 * resistance from the item count. A bent wire keeps its items and has its
 * resistance scaled by how much longer the bent path is than the straight one, so
 * bending a wire costs what lengthening it would. The cached value Power Grid keeps
 * is left alone: the scale is applied on the way out.
 */
@Mixin(value = BaseWireEntity.class, remap = false)
public class BaseWireEntityBendMixin {

    @Inject(method = "getResistance()F", at = @At("RETURN"), cancellable = true)
    private void create_flippies$bentResistance(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof BendableWire wire && !wire.bendPoints().isEmpty())
            cir.setReturnValue((float) (cir.getReturnValueF() * wire.bendResistanceScale()));
    }

    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"), remap = true)
    private void create_flippies$writeBends(CompoundTag tag, CallbackInfo ci) {
        if (!((Object) this instanceof BendableWire wire))
            return;
        WireBends.write(tag, wire.bendPoints());
        // WIRE SAG: a bent wire's legs under Flippies' key; an unbent wire's one value
        // under Power Plant Grid's, in its format, but only while Power Plant Grid is not
        // installed to write it itself (see HangingWireEntityBendMixin)
        float[] sags = wire.bendLegSags();
        if (sags == null)
            return;
        if (!wire.bendPoints().isEmpty())
            LegSag.write(tag, sags);
        else if (!WireGaugeCompat.ppgLoaded() && sags.length > 0)
            LegSag.writePpg(tag, sags[0]);
    }

    // WIRE BENDS 2: a cord's points follow its ends round when Power Grid swaps them.
    // The hanging wire does this in its own override (see HangingWireEntityBendMixin);
    // the cord has none, so it is done here, for cords alone.

    @Inject(method = "flipEndpoints()V", at = @At("HEAD"))
    private void create_flippies$flipCordBends(CallbackInfo ci) {
        if ((Object) this instanceof CordEntity && (Object) this instanceof BendableWire wire
                && !wire.bendPoints().isEmpty())
            wire.setBendPoints(WireBends.reversed(wire.bendPoints()));
    }

    /** Power Grid leaves a cord's terminal positions as they were on a swap; they are brought in line with the ends. */
    @Inject(method = "flipEndpoints()V", at = @At("TAIL"))
    private void create_flippies$syncFlippedCordBends(CallbackInfo ci) {
        if (!((Object) this instanceof CordEntity cord) || ((BendableWire) cord).bendPoints().isEmpty())
            return;
        if (cord.terminalPos1 != null && cord.getEndpoint1() != null && cord.getEndpoint2() != null)
            cord.refreshTerminalPositions();
        WireBendService.sync(cord);
    }
}
