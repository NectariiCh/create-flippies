package com.nectariich.create_flippies.foundation.protection;

import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.network.AddAreaPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.RemoveAreaPacket;
import com.nectariich.create_flippies.network.SyncAreaChunkPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.network.PacketDistributor;

/**
 * The authoritative store of break-protected areas for one dimension, and the only
 * place that both writes them to disk and tells clients about them.
 *
 * A second {@link SavedData} beside the grid store rather than a corner of it. The
 * two hold different geometry for different questions — a rectangle on a face for
 * where a control may be screwed down, a box of blocks for what a screwdriver
 * refuses to mine — and the only thing they share is the mechanism, which is small.
 * Merging them would mean one index answering two unrelated lookups and one
 * snapshot packet carrying both, so that a player watching a chunk with fifty grids
 * and no protection paid for the protection anyway.
 *
 * Every mutation goes through here rather than through the index directly, because
 * an area written to disk without being sent, or sent without being marked dirty,
 * is an area the two sides will disagree about — and disagreeing about this one
 * means a client that breaks a block the server puts straight back.
 */
public class ProtectionStore extends SavedData {

    private static final String NAME = "create_flippies_break_protection";

    private final ProtectionIndex index = new ProtectionIndex();

    public static ProtectionStore of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ProtectionStore::loaded, ProtectionStore::new, NAME);
    }

    public ProtectionIndex index() {
        return index;
    }

    /** Adds an area, persists it and tells everyone watching. */
    public boolean protect(ServerLevel level, BreakArea area) {
        if (!index.add(area))
            return false;
        setDirty();
        broadcast(level, area, new AddAreaPacket(area));
        return true;
    }

    /**
     * Takes one area away. Areas do not nest, so this is the whole of removal —
     * blocks left protected by another area overlapping the same space stay
     * protected, which is what two areas over one block meant in the first place.
     *
     * @return whether there was such an area
     */
    public boolean release(ServerLevel level, UUID id) {
        BreakArea area = index.get(id);
        if (area == null || !index.remove(id))
            return false;
        setDirty();
        broadcast(level, area, new RemoveAreaPacket(id));
        return true;
    }

    /** Seeds one player with a chunk's areas, or sends nothing if it has none. */
    public void sendChunk(ServerPlayer player, ChunkPos chunk) {
        List<BreakArea> areas = index.inChunk(chunk.toLong());
        if (areas.isEmpty())
            return;
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncAreaChunkPacket(chunk, areas));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        index.save(tag);
        return tag;
    }

    private static ProtectionStore loaded(CompoundTag tag) {
        ProtectionStore store = new ProtectionStore();
        store.index.load(tag);
        return store;
    }

    /**
     * Sends a packet to everyone tracking any chunk the area overlaps. Chunks that
     * are not resident are skipped rather than loaded: nobody can be watching a
     * chunk the server has not got, so forcing one in just to address an empty
     * audience would be all cost.
     */
    private static <T> void broadcast(ServerLevel level, BreakArea area, T packet) {
        area.forEachChunk(key -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null)
                ModPackets.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), packet);
        });
    }
}
