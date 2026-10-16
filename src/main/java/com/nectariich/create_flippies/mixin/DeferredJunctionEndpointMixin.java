package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import net.minecraft.nbt.CompoundTag;

import org.patryk3211.powergrid.electricity.wire.DeferredJunctionWireEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the offset of a junction along a wire segment in the same unit as the
 * segment itself.
 *
 * This endpoint remembers where one wire met another as a segment index and an
 * offset along it, and hands that offset both to {@code BlockWireEntity.split} and
 * to its own distance arithmetic. The offset is compared against a segment's
 * length, so it was always in Power Grid's pixel unit and has to move with it, and
 * it is persisted, so it needs the same legacy fallback that a segment length gets.
 */
@Mixin(value = DeferredJunctionWireEndpoint.class, remap = false)
public class DeferredJunctionEndpointMixin {

    @ModifyConstant(
            method = "getExactPosition(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/phys/Vec3;",
            constant = @Constant(floatValue = 16.0f))
    private float create_flippies$fineUnitsPerBlock(float pixelsPerBlock) {
        return BlockWireGeometry.UNITS_PER_BLOCK;
    }

    /** Ordinal 1 is the {@code Point} offset; ordinal 0 is the segment index. */
    @Redirect(
            method = "write(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;putInt(Ljava/lang/String;I)V",
                    ordinal = 1, remap = true))
    private void create_flippies$writeFineOffset(CompoundTag tag, String key, int units) {
        BlockWireGeometry.writeUnits(tag, key, units);
    }

    @Redirect(
            method = "read(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;getInt(Ljava/lang/String;)I",
                    ordinal = 1, remap = true))
    private int create_flippies$readFineOffset(CompoundTag tag, String key) {
        return BlockWireGeometry.readUnits(tag, key);
    }
}
