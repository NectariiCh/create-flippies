package com.nectariich.create_flippies.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * A whole item stack to and from NBT, on both versions.
 *
 * 1.21.1 needs the registries to write a stack's components; 1.20.1 has no use for
 * them, so its forms ignore the argument. {@code HolderLookup.Provider} exists on
 * both, so the signatures are the same.
 */
public final class ItemNbt {

    private ItemNbt() {}

    /** {@code stack.save(new CompoundTag())} on 1.20.1. An empty stack saves as an empty tag on 1.21.1. */
    public static CompoundTag save(ItemStack stack, HolderLookup.Provider registries) {
        //? if forge {
        return stack.save(new CompoundTag());
        //?} else {
        /*return (CompoundTag) stack.saveOptional(registries);
        *///?}
    }

    /** {@code ItemStack.of(tag)} on 1.20.1: an unreadable tag gives an empty stack. */
    public static ItemStack load(CompoundTag tag, HolderLookup.Provider registries) {
        //? if forge {
        return ItemStack.of(tag);
        //?} else {
        /*return ItemStack.parseOptional(registries, tag);
        *///?}
    }
}
