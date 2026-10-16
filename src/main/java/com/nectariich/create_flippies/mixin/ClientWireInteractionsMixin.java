package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import org.patryk3211.powergrid.electricity.wire.ClientWireInteractions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Measures where along a segment the player is pointing in the finer segment unit.
 *
 * This is the one producer of the offsets that cutting and junction attachment
 * send to the server, and it clamps what it measures against a segment's length,
 * so it has to count in the same unit as that length. The three occurrences of the
 * constant are the three axes of the same measurement; the other uses of 16 in
 * this class belong to a different method and are left alone.
 */
@Mixin(value = ClientWireInteractions.class, remap = false)
public class ClientWireInteractionsMixin {

    @ModifyConstant(
            method = "getSegment(Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity;"
                    + "Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/util/Tuple;",
            constant = @Constant(doubleValue = 16.0))
    private static double create_flippies$fineUnitsPerBlock(double pixelsPerBlock) {
        return BlockWireGeometry.UNITS_PER_BLOCK;
    }
}
