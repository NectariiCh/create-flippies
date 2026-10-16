package com.nectariich.create_flippies.foundation.protection;

import java.util.List;
import java.util.UUID;

/**
 * The client's copy of the protected areas, kept so that a swing can be stopped
 * before the block visibly breaks and so the overlay can draw them.
 *
 * The same arrangement, and for the same reasons, as
 * {@link com.nectariich.create_flippies.foundation.grid.ClientGridStore}: there is
 * no block entity to hang an area on, so the mirror is a static index fed entirely
 * by packets. Nothing here reads or writes the world and only common types appear
 * in the signatures, so a dedicated server can touch this file without loading
 * anything client-only through it.
 *
 * It is emptied when the level unloads, which covers a dimension change and a
 * disconnect alike, and not when a chunk stops being watched — the snapshot on the
 * next watch discards anything stale.
 */
public final class ClientProtectionStore {

    private static final ProtectionIndex INDEX = new ProtectionIndex();

    private ClientProtectionStore() {}

    public static ProtectionIndex index() {
        return INDEX;
    }

    public static void acceptChunk(long chunk, List<BreakArea> snapshot) {
        INDEX.replaceChunk(chunk, snapshot);
    }

    public static void accept(BreakArea area) {
        INDEX.add(area);
    }

    public static void forget(UUID id) {
        INDEX.remove(id);
    }

    public static void clear() {
        INDEX.clear();
    }
}
