package com.nectariich.create_flippies.content.design_workbench.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/** Favourites are filed by section and world, and keep a snapshot that survives a file round trip. */
class FavoritesTest {

    @Test
    void worldFavouritesAreKeptPerWorldAndLocalOnesEverywhere() {
        Favorites favorites = new Favorites();
        Blueprint world = BlueprintIndexTest.blueprint("World one", "");
        Blueprint local = BlueprintIndexTest.blueprint("Local one", "");
        assertTrue(favorites.add(Favorites.WORLD, world, "sp:Alpha"));
        assertTrue(favorites.add(Favorites.LOCAL, local, "sp:Alpha"));

        assertTrue(favorites.contains(Favorites.WORLD, world.id(), "sp:Alpha"));
        assertFalse(favorites.contains(Favorites.WORLD, world.id(), "mp:server"));
        assertTrue(favorites.contains(Favorites.LOCAL, local.id(), "mp:server"), "local is the same everywhere");
        assertFalse(favorites.contains(Favorites.LOCAL, world.id(), "sp:Alpha"));

        favorites.add(Favorites.WORLD, world.withName("Again"), "sp:Alpha");
        assertEquals(2, favorites.entries().size(), "starring twice refreshes rather than duplicates");
        assertEquals("Again", favorites.find(Favorites.WORLD, world.id(), "sp:Alpha").snapshot().name());

        assertTrue(favorites.refresh(Favorites.WORLD, world.withName("Live"), "sp:Alpha"));
        assertFalse(favorites.refresh(Favorites.WORLD, world.withName("Live"), "sp:Alpha"), "nothing new");
        assertEquals("Live", favorites.find(Favorites.WORLD, world.id(), "sp:Alpha").snapshot().name());

        assertTrue(favorites.remove(Favorites.LOCAL, local.id(), "anything"));
        assertEquals(1, favorites.entries().size());
    }

    @Test
    void roundTripsAndDropsJunk() throws Exception {
        Favorites favorites = new Favorites();
        favorites.add(Favorites.WORLD, BlueprintIndexTest.blueprint("A", "f"), "mp:host");
        favorites.add(Favorites.LOCAL, BlueprintIndexTest.blueprint("B", ""), "");
        CompoundTag written = favorites.write();
        assertEquals(written, Favorites.read(written).write());

        ListTag list = written.getList("Favorites", 10).copy();
        CompoundTag strange = new CompoundTag();
        strange.putString("Section", "elsewhere");
        strange.putUUID("Id", UUID.randomUUID());
        list.add(strange);
        list.add(list.getCompound(0).copy());
        CompoundTag withJunk = new CompoundTag();
        withJunk.put("Favorites", list);
        assertEquals(2, Favorites.read(withJunk).entries().size(), "unknown sections and duplicates are dropped");
    }
}
