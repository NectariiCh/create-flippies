package com.nectariich.create_flippies.foundation.grid;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Ties the grid store to the chunks a player is watching.
 *
 * {@link ChunkWatchEvent.Watch} is Forge's hook for exactly this — its own
 * documentation offers it for sending extra chunk-related data — and it fires
 * server side with both the player and the chunk in hand, which is everything a
 * seed needs. There is no counterpart for {@link ChunkWatchEvent.UnWatch} here
 * because the mirror deliberately keeps what it has been told until the level
 * goes; see {@link ClientGridStore}.
 *
 * The unload handler is the mirror's only reset. It fires on both sides, so it is
 * gated on the client one: a server level unloading must not empty a store that
 * belongs to the client sharing its process in single player.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class GridChunkSync {

    private GridChunkSync() {}

    @SubscribeEvent
    public static void onChunkWatched(ChunkWatchEvent.Watch event) {
        GridStore.of(event.getLevel()).sendChunk(event.getPlayer(), event.getPos());
    }

    @SubscribeEvent
    public static void onLevelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide())
            ClientGridStore.clear();
    }
}
