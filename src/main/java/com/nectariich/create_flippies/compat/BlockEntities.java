package com.nectariich.create_flippies.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Block entity methods whose signatures differ between the versions. */
public final class BlockEntities {

    private BlockEntities() {}

    /**
     * {@code be.getUpdateTag()}. 1.21.1 takes the registries, which a block entity in a
     * level reaches through that level.
     */
    public static CompoundTag updateTag(BlockEntity be) {
        //? if forge {
        return be.getUpdateTag();
        //?} else {
        /*return be.getUpdateTag(be.getLevel().registryAccess());
        *///?}
    }
}
