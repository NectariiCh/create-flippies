package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.content.device.DeviceReach;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Widens the sections searched for entities near a box by however far a device has
 * reached from its position ({@link DeviceReach}), so a large cabinet is found by
 * collision, picking and placement wherever a section boundary falls.
 *
 * Only which sections are visited changes: the entities in them are still tested
 * against the box the caller asked about, which the section's own lookup was handed
 * separately.
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageReachMixin {

    @ModifyVariable(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), argsOnly = true)
    private AABB create_flippies$widen(AABB box) {
        double margin = DeviceReach.margin();
        return margin > 0 ? box.inflate(margin) : box;
    }
}
