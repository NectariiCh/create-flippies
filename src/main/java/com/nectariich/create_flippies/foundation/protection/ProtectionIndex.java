package com.nectariich.create_flippies.foundation.protection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The protected areas in one level, filed by chunk.
 *
 * The question asked of it is asked on every swing of every tool: is this block
 * protected. That has to be cheap and it has to be answerable on both sides — the
 * client so the swing is stopped before the block visibly breaks, the server so
 * stopping it is not merely a client's opinion. So this is the same class on both,
 * exactly as {@link com.nectariich.create_flippies.foundation.grid.GridIndex} is:
 * the server's copy is persisted by {@link ProtectionStore}, the client's is a
 * mirror filled from packets and held by {@link ClientProtectionStore}.
 *
 * Areas are filed under every chunk they overlap rather than under one, for the
 * same reason grids are: a box may cross chunk boundaries, and the lookup wants
 * every area over a block using only that block's own chunk.
 *
 * Unlike grids they do not nest and are free to overlap. Nesting exists for grids
 * because a cell of one is where the next is ruled; protection has nothing of the
 * sort to express — two overlapping areas simply both say no, and taking one away
 * leaves the other saying it.
 */
public class ProtectionIndex {

    private final Map<UUID, BreakArea> areas = new HashMap<>();
    private final Map<Long, Set<UUID>> byChunk = new HashMap<>();

    /**
     * The index for whichever side this level is on: the persisted store on the
     * server, the packet-fed mirror on the client.
     */
    public static ProtectionIndex of(Level level) {
        if (level instanceof ServerLevel server)
            return ProtectionStore.of(server).index();
        return ClientProtectionStore.index();
    }

    public BreakArea get(UUID id) {
        return areas.get(id);
    }

    public Collection<BreakArea> all() {
        return Collections.unmodifiableCollection(areas.values());
    }

    public List<BreakArea> inChunk(long chunk) {
        Set<UUID> ids = byChunk.get(chunk);
        if (ids == null)
            return List.of();
        List<BreakArea> found = new ArrayList<>(ids.size());
        for (UUID id : ids)
            found.add(areas.get(id));
        return found;
    }

    /**
     * The area protecting a block, or null if none does.
     *
     * The smallest one, when several overlap. Any of them would answer the question
     * — the block is protected either way — but the caller shows what it found, and
     * outlining the whole room when the player is aiming at a protected panel inside
     * it says less than outlining the panel.
     */
    public BreakArea at(BlockPos pos) {
        BreakArea smallest = null;
        for (BreakArea area : inChunk(ChunkPos.asLong(pos))) {
            if (!area.contains(pos))
                continue;
            if (smallest == null || area.blocks() < smallest.blocks())
                smallest = area;
        }
        return smallest;
    }

    /** Everything filed in the chunks around a block, for the overlay to draw. */
    public List<BreakArea> near(BlockPos pos, int chunkRadius) {
        Set<UUID> seen = new HashSet<>();
        List<BreakArea> found = new ArrayList<>();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        for (int x = chunkX - chunkRadius; x <= chunkX + chunkRadius; x++)
            for (int z = chunkZ - chunkRadius; z <= chunkZ + chunkRadius; z++)
                for (BreakArea area : inChunk(ChunkPos.asLong(x, z)))
                    if (seen.add(area.id()))
                        found.add(area);
        return found;
    }

    /** @return whether it was filed, which it is not if the id is already here */
    public boolean add(BreakArea area) {
        if (areas.containsKey(area.id()))
            return false;
        areas.put(area.id(), area);
        area.forEachChunk(chunk -> byChunk.computeIfAbsent(chunk, key -> new HashSet<>()).add(area.id()));
        return true;
    }

    public boolean remove(UUID id) {
        BreakArea area = areas.remove(id);
        if (area == null)
            return false;
        area.forEachChunk(chunk -> {
            Set<UUID> ids = byChunk.get(chunk);
            if (ids != null && ids.remove(id) && ids.isEmpty())
                byChunk.remove(chunk);
        });
        return true;
    }

    /**
     * Brings one chunk's contents into line with a snapshot of it, which is the
     * whole of the client's re-sync story. An area spanning two chunks appears in
     * the snapshot of both, so a snapshot for one never mistakes it for deleted.
     */
    public void replaceChunk(long chunk, Collection<BreakArea> snapshot) {
        Set<UUID> present = new HashSet<>();
        for (BreakArea area : snapshot)
            present.add(area.id());
        for (UUID id : List.copyOf(byChunk.getOrDefault(chunk, Set.of())))
            if (!present.contains(id))
                remove(id);
        for (BreakArea area : snapshot) {
            if (area.equals(areas.get(area.id())))
                continue;
            remove(area.id());
            add(area);
        }
    }

    public void clear() {
        areas.clear();
        byChunk.clear();
    }

    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BreakArea area : areas.values())
            list.add(area.save());
        tag.put("Areas", list);
    }

    public void load(CompoundTag tag) {
        clear();
        for (Tag entry : tag.getList("Areas", Tag.TAG_COMPOUND)) {
            BreakArea area = BreakArea.load((CompoundTag) entry);
            if (area != null)
                add(area);
        }
    }
}
