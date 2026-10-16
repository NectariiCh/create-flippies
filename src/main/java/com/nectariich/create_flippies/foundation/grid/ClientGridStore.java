package com.nectariich.create_flippies.foundation.grid;

import java.util.List;
import java.util.UUID;

/**
 * The client's copy of the placement grids, kept for the overlay to draw and for
 * hit-testing to consult.
 *
 * There is nothing to attach it to. A block entity's update tag would be synced,
 * persisted and scoped for us, but grids belong to faces rather than to blocks, so
 * the mirror is a single static index fed entirely by
 * {@link com.nectariich.create_flippies.network.SyncGridChunkPacket} and the two
 * delta packets. Nothing here reads or writes the world, and only common types
 * appear in the signatures, so no client-only class is ever loaded through it — a
 * dedicated server can touch this file without noticing.
 *
 * The mirror is not pruned when a chunk stops being watched. It could be, but the
 * bookkeeping — which of a spanning grid's chunks are still resident — would exist
 * to reclaim a few dozen bytes per grid, and the snapshot on the next watch
 * already discards anything stale. It is emptied when the level unloads, which
 * covers a dimension change and a disconnect alike.
 */
public final class ClientGridStore {

    private static final GridIndex INDEX = new GridIndex();

    private ClientGridStore() {}

    public static GridIndex index() {
        return INDEX;
    }

    public static void acceptChunk(long chunk, List<PlacementGrid> snapshot) {
        INDEX.replaceChunk(chunk, snapshot);
    }

    public static void accept(PlacementGrid grid) {
        INDEX.add(grid);
    }

    public static void forget(List<UUID> ids) {
        for (UUID id : ids)
            INDEX.remove(id);
    }

    public static void clear() {
        INDEX.clear();
    }
}
