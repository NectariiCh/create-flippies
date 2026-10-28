package com.nectariich.create_flippies.compat;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
//? if neoforge {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
*///?}

/**
 * An item stack's own NBT, on both versions.
 *
 * 1.20.1 keeps it in the stack's tag; 1.21.1 has no stack tag and keeps it in
 * {@code DataComponents.CUSTOM_DATA} instead, with the same shape, so a stack saved on
 * either reads the same through this class. That is also where 1.21.1 puts the tag of
 * a stack from an upgraded 1.20.1 world.
 *
 * On 1.21.1 the custom data is immutable: a change only sticks when it goes through
 * {@link #edit}, {@link #put} or {@link #remove}. Never change what {@link #peek} or
 * {@link #sub} return.
 */
public final class StackData {

    private StackData() {}

    /** The stack's tag, or null when it has none. Read only. */
    @Nullable
    public static CompoundTag peek(ItemStack stack) {
        //? if forge {
        return stack.getTag();
        //?} else {
        /*CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.getUnsafe();
        *///?}
    }

    /** The compound under {@code key}, or null when there is none. Read only. */
    @Nullable
    public static CompoundTag sub(ItemStack stack, String key) {
        //? if forge {
        return stack.getTagElement(key);
        //?} else {
        /*CompoundTag tag = peek(stack);
        return tag != null && tag.contains(key, Tag.TAG_COMPOUND) ? tag.getCompound(key) : null;
        *///?}
    }

    /** Changes the stack's tag, creating it first if the stack has none. */
    public static void edit(ItemStack stack, Consumer<CompoundTag> edit) {
        //? if forge {
        edit.accept(stack.getOrCreateTag());
        //?} else {
        /*CustomData.update(DataComponents.CUSTOM_DATA, stack, edit);
        *///?}
    }

    /** {@code getOrCreateTag().put(key, value)}. */
    public static void put(ItemStack stack, String key, Tag value) {
        edit(stack, tag -> tag.put(key, value));
    }

    /** Removes {@code key}, dropping the tag altogether when that empties it. */
    public static void remove(ItemStack stack, String key) {
        //? if forge {
        stack.removeTagKey(key);
        //?} else {
        /*CompoundTag tag = peek(stack);
        if (tag != null && tag.contains(key))
            CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.remove(key));
        *///?}
    }
}
