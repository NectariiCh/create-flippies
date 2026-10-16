package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import net.minecraft.nbt.CompoundTag;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Moves a wire segment's stored length from whole pixels to quarter pixels, so
 * that a chain of them can reach a terminal origin that does not sit on the pixel
 * lattice. See {@link BlockWireGeometry} for why the finer unit is what makes
 * both ends reachable at once.
 *
 * Nothing else about the segment changes: every other place Power Grid touches
 * {@code gridLength} either adds two of them together or subtracts one from
 * another, and is therefore indifferent to what a unit means. The places that do
 * turn a unit count into a distance are dealt with one by one, in the mixin that
 * owns each.
 */
@Mixin(value = BlockWireEntity.Point.class, remap = false)
public class BlockWirePointMixin {

    /**
     * The scale the factories below apply after {@code Math.abs} and before their
     * truncating cast to int. It is Power Grid's own pixel count, and it stays
     * where it is: {@link #create_flippies$toFineUnits} hands back a value already
     * divided by it.
     */
    private static final int FACTORY_SCALE = 16;

    @ModifyConstant(method = "length()F", constant = @Constant(floatValue = 16.0f))
    private float create_flippies$fineUnitsPerBlock(float pixelsPerBlock) {
        return BlockWireGeometry.UNITS_PER_BLOCK;
    }

    /**
     * Quantises an axis delta into the finer unit at the one point in each factory
     * where the sign has already been dealt with. Rounding here rather than letting
     * the factory's cast truncate matters twice over: it removes the systematic
     * shortfall of up to a unit per segment, and it stops a whole cell being lost
     * when a delta that ought to be an exact multiple of the cell size arrives as
     * something like 4.9999995 after the double-to-float narrowing.
     *
     * The returned count is pre-divided by {@link #FACTORY_SCALE} so that the
     * multiplication and cast that follow give it back unchanged; both steps are
     * exact in binary floating point for any count a wire can hold.
     */
    @Redirect(
            method = {
                    "x(F)Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity$Point;",
                    "y(F)Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity$Point;",
                    "z(F)Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity$Point;" },
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(F)F"))
    private static float create_flippies$toFineUnits(float delta) {
        return BlockWireGeometry.toUnits(Math.abs(delta)) / (float) FACTORY_SCALE;
    }

    /**
     * Ordinal 1 is the {@code Length} write; ordinal 0 is the direction. The read
     * below intercepts the matching ordinal in the matching order, and the two have
     * to be changed together.
     */
    @Redirect(
            method = "serialize()Lnet/minecraft/nbt/CompoundTag;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;putInt(Ljava/lang/String;I)V",
                    ordinal = 1, remap = true))
    private void create_flippies$writeFineLength(CompoundTag tag, String key, int units) {
        BlockWireGeometry.writeUnits(tag, key, units);
    }

    @Redirect(
            method = "<init>(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I",
                    ordinal = 1, remap = true))
    private int create_flippies$readFineLength(CompoundTag tag, String key) {
        return BlockWireGeometry.readUnits(tag, key);
    }
}
