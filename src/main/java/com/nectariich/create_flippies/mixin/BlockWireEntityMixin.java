package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Anchors a new block wire on the terminal it starts from, and keeps the wire's
 * own idea of a grid point in step with the finer segment unit.
 *
 * This is the overload {@code WireItem.connect} uses, and the position it sets is
 * where the wire is drawn from, so Power Grid's {@code alignPosition} call here is
 * the near-end gap itself. The same aligned value was separately handed to the
 * path finder, which is left alone: only the entity's position is moved, and the
 * chain the router produced is then lengthened to match by
 * {@link BlockWireGeometry#snapFarEnd}.
 */
@Mixin(value = BlockWireEntity.class, remap = false)
public class BlockWireEntityMixin {

    @Redirect(
            method = "create(Lnet/minecraft/world/level/Level;"
                    + "Lorg/patryk3211/powergrid/electricity/wire/IWireEndpoint;"
                    + "Lnet/minecraft/world/item/ItemStack;Ljava/util/List;)"
                    + "Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity;",
            at = @At(value = "INVOKE",
                    target = "Lorg/patryk3211/powergrid/utility/BlockTrace;"
                            + "alignPosition(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 create_flippies$anchorOnTerminal(Vec3 endpointPosition) {
        return BlockWireGeometry.snap(endpointPosition);
    }

    /**
     * The offset a wire is split at is counted in the same unit as a segment's
     * length — it is compared against and subtracted from one — so the divisor that
     * turns it back into a distance has to follow the unit.
     */
    @ModifyConstant(
            method = "split(II)Lorg/patryk3211/powergrid/electricity/wire/JunctionWireEndpoint;",
            constant = @Constant(floatValue = 16.0f))
    private float create_flippies$fineUnitsPerBlock(float pixelsPerBlock) {
        return BlockWireGeometry.UNITS_PER_BLOCK;
    }
}
