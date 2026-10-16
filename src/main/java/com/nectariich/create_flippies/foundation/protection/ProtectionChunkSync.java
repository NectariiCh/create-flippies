package com.nectariich.create_flippies.foundation.protection;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Ties the protection store to the chunks a player is watching, exactly as
 * {@link com.nectariich.create_flippies.foundation.grid.GridChunkSync} ties the
 * grid store to them.
 *
 * Two subscribers to the same event rather than one that seeds both stores: they
 * are separate stores with separate packets, and a subscriber that reached into
 * both would be the one place that had to be edited whenever either gained
 * anything. Forge posts the event to every listener regardless.
 *
 * The unload handler fires on both sides, so it is gated on the client one: a
 * server level unloading must not empty a mirror that belongs to the client
 * sharing its process in single player.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class ProtectionChunkSync {

    private ProtectionChunkSync() {}

    @SubscribeEvent
    public static void onChunkWatched(ChunkWatchEvent.Watch event) {
        ProtectionStore.of(event.getLevel()).sendChunk(event.getPlayer(), event.getPos());
    }

    @SubscribeEvent
    public static void onLevelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide())
            ClientProtectionStore.clear();
    }
}
