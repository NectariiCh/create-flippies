package com.nectariich.create_flippies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ClientProtectionStore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

/**
 * Everything the client needs to know about one chunk's protected areas, sent when
 * a player starts watching that chunk.
 *
 * The client needs them at all because a swing has to be stopped before the block
 * visibly breaks. The server would put the block back either way, but a block that
 * shatters and returns reads as lag rather than as a refusal, and in creative it
 * would be gone for the whole round trip.
 *
 * A whole snapshot rather than a set of additions, for the reason
 * {@link SyncGridChunkPacket} gives: a snapshot is idempotent and self-correcting,
 * so a player who was away while areas came and went is brought back into line by
 * the next one with no replay and no version counter.
 */
public class SyncAreaChunkPacket {

    private final long chunk;
    private final List<BreakArea> areas;

    public SyncAreaChunkPacket(ChunkPos chunk, List<BreakArea> areas) {
        this.chunk = chunk.toLong();
        this.areas = areas;
    }

    public SyncAreaChunkPacket(FriendlyByteBuf buffer) {
        chunk = buffer.readLong();
        areas = buffer.readCollection(ArrayList::new, BreakArea::read);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeLong(chunk);
        buffer.writeCollection(areas, (target, area) -> area.write(target));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> ClientProtectionStore.acceptChunk(chunk, areas));
        context.get().setPacketHandled(true);
    }
}
