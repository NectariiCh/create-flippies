package com.nectariich.create_flippies.compat;

import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * {@code DimensionDataStorage.computeIfAbsent} on both versions. 1.21.1 takes a
 * {@code SavedData.Factory} whose loader is also handed the registries, which none of
 * Flippies' saved data needs.
 */
public final class SavedDataCompat {

    private SavedDataCompat() {}

    /** The data saved as {@code name}, loaded with {@code load}, or a new one from {@code create}. */
    public static <T extends SavedData> T get(DimensionDataStorage storage, Function<CompoundTag, T> load,
            Supplier<T> create, String name) {
        //? if forge {
        return storage.computeIfAbsent(load, create, name);
        //?} else {
        /*return storage.computeIfAbsent(new SavedData.Factory<>(create, (tag, registries) -> load.apply(tag)), name);
        *///?}
    }
}
