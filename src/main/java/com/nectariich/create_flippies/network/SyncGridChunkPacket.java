package com.nectariich.create_flippies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.grid.ClientGridStore;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

/**
 * Everything the client needs to know about one chunk's placement grids, sent
 * when a player starts watching that chunk.
 *
 * Grids are not block entities, so nothing carries them to the client for free:
 * the vanilla chunk packet has room for heightmaps, block data and block entity
 * update tags, and no third place to put anything else. This is the seed that
 * makes up for it.
 *
 * It is a whole snapshot rather than a set of additions because a snapshot is
 * idempotent and self-correcting. A player who was away while grids came and went
 * is brought back into line by the snapshot alone, with no replay and no version
 * counter, and a delta that went missing costs nothing beyond the next time the
 * chunk is watched.
 */
public class SyncGridChunkPacket {

    private final long chunk;
    private final List<PlacementGrid> grids;

    public SyncGridChunkPacket(ChunkPos chunk, List<PlacementGrid> grids) {
        this.chunk = chunk.toLong();
        this.grids = grids;
    }

    public SyncGridChunkPacket(FriendlyByteBuf buffer) {
        chunk = buffer.readLong();
        grids = buffer.readCollection(ArrayList::new, PlacementGrid::read);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(chunk);
        buffer.writeCollection(grids, (target, grid) -> grid.write(target));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        // Geometry arrives unvalidated and is checked as the mirror files it, so a
        // malformed grid is dropped rather than becoming a cell nobody can click
        context.get().enqueueWork(() -> ClientGridStore.acceptChunk(chunk, grids));
        context.get().setPacketHandled(true);
    }
}
