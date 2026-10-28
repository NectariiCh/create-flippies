package com.nectariich.create_flippies.content.design_workbench.blueprint;

import com.nectariich.create_flippies.compat.SavedDataCompat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The world's blueprint section, saved with the overworld.
 *
 * A blueprint is small (its model is only named, by hash, and lives in the design
 * store), so the whole section sits in one compound and is read with the world.
 * Kept on the overworld whatever dimension a player saves from, because blueprints
 * belong to the world rather than to a place in it.
 *
 * Every change goes through {@link BlueprintService}, which checks who may make it
 * and tells the browsers that are open; this class only holds the index and marks
 * it for saving.
 */
public class WorldBlueprints extends SavedData {

    private static final String NAME = "create_flippies_blueprints";

    private BlueprintIndex index = new BlueprintIndex();

    public static WorldBlueprints of(MinecraftServer server) {
        return SavedDataCompat.get(server.overworld().getDataStorage(), WorldBlueprints::loaded, WorldBlueprints::new,
                NAME);
    }

    public BlueprintIndex index() {
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("Index", index.write());
        return tag;
    }

    private static WorldBlueprints loaded(CompoundTag tag) {
        WorldBlueprints blueprints = new WorldBlueprints();
        blueprints.index = BlueprintIndex.read(tag.getCompound("Index"));
        return blueprints;
    }
}
