package com.nectariich.create_flippies.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
//? if forge {
import net.minecraft.nbt.NbtUtils;
//?}

/**
 * NBT helpers whose vanilla forms changed between the versions.
 *
 * Block positions keep the 1.20.1 shape, a compound of {@code X}, {@code Y} and
 * {@code Z}, on both versions, so saved items and entities read the same. 1.21.1's
 * {@code NbtUtils.writeBlockPos} writes an int array instead.
 */
public final class NbtCompat {

    private NbtCompat() {}

    /** {@code NbtUtils.writeBlockPos} as 1.20.1 writes it. */
    public static CompoundTag writeBlockPos(BlockPos pos) {
        //? if forge {
        return NbtUtils.writeBlockPos(pos);
        //?} else {
        /*CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
        *///?}
    }

    /** {@code NbtUtils.readBlockPos} of a compound written by {@link #writeBlockPos}. */
    public static BlockPos readBlockPos(CompoundTag tag) {
        //? if forge {
        return NbtUtils.readBlockPos(tag);
        //?} else {
        /*return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        *///?}
    }

    /** {@code new NbtAccounter(quota)}: a size budget of {@code quota} bytes. */
    public static NbtAccounter accounter(long quota) {
        //? if forge {
        return new NbtAccounter(quota);
        //?} else {
        /*return NbtAccounter.create(quota);
        *///?}
    }
}
