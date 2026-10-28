package com.nectariich.create_flippies.foundation.grid;

import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.compat.SavedDataCompat;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid.Problem;
import com.nectariich.create_flippies.network.AddGridPacket;
import com.nectariich.create_flippies.network.FlippiesPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.RemoveGridsPacket;
import com.nectariich.create_flippies.network.SyncGridChunkPacket;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The authoritative store of placement grids for one dimension, and the only
 * place that both writes them to disk and tells clients about them.
 *
 * A grid attaches to the face of whatever block happens to be there, and bare
 * hands leave ordinary blocks, so there is no block entity of ours to hang the
 * data on — which is what every other device in this mod does. That leaves a
 * per-dimension {@link SavedData}, the same mechanism Power Grid keeps its
 * networks in. A chunk capability was the alternative and would bound memory by
 * unloading with its chunk, but it also makes a grid on an unloaded chunk simply
 * unreadable, loads asynchronously, and is dropped for chunks saved below full
 * status; a placement aid is not worth those three failure modes. The cost is that
 * this store never shrinks while the world is loaded.
 *
 * Every mutation goes through here rather than through the index directly, because
 * a grid that is written to disk without being sent, or sent without being marked
 * dirty, is a grid the two sides will disagree about.
 */
public class GridStore extends SavedData {

    private static final String NAME = "create_flippies_grids";

    private final GridIndex index = new GridIndex();

    public static GridStore of(ServerLevel level) {
        return SavedDataCompat.get(level.getDataStorage(), GridStore::loaded, GridStore::new, NAME);
    }

    public GridIndex index() {
        return index;
    }

    /** Adds a grid, persists it and tells everyone watching. */
    public Problem place(ServerLevel level, PlacementGrid grid) {
        Problem problem = index.add(grid);
        if (!problem.ok())
            return problem;
        setDirty();
        broadcast(level, grid, new AddGridPacket(grid));
        return Problem.NONE;
    }

    /**
     * Removes one grid, and refuses if anything is nested inside it — for the reason
     * {@link GridIndex#removeOne} gives. One click takes away one grid; a tree comes
     * apart from the inside out, deliberately, because the alternative is a mistimed
     * click costing a face's worth of ruling.
     *
     * @return whether it was removed
     */
    public boolean cut(ServerLevel level, UUID id) {
        PlacementGrid grid = index.get(id);
        if (grid == null || !index.removeOne(id))
            return false;
        setDirty();
        broadcast(level, grid, new RemoveGridsPacket(List.of(id)));
        return true;
    }

    /** Seeds one player with a chunk's grids, or sends nothing if it has none. */
    public void sendChunk(ServerPlayer player, ChunkPos chunk) {
        List<PlacementGrid> grids = index.inChunk(chunk.toLong());
        if (grids.isEmpty())
            return;
        ModPackets.sendToPlayer(player, new SyncGridChunkPacket(chunk, grids));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        index.save(tag);
        return tag;
    }

    private static GridStore loaded(CompoundTag tag) {
        GridStore store = new GridStore();
        store.index.load(tag);
        return store;
    }

    /**
     * Sends a packet to everyone tracking any chunk the grid overlaps. Chunks that
     * are not resident are skipped rather than loaded: nobody can be watching a
     * chunk the server has not got, so forcing one in just to address an empty
     * audience would be all cost.
     */
    private static void broadcast(ServerLevel level, PlacementGrid grid, FlippiesPacket packet) {
        grid.forEachChunk(key -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null)
                ModPackets.sendToTrackingChunk(chunk, packet);
        });
    }
}
