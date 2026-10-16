package com.nectariich.create_flippies.foundation.grid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.grid.PlacementGrid.Problem;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The set of placement grids in one level, with the two indices that make the
 * questions asked of it cheap.
 *
 * The question asked constantly — every frame the overlay is up, and on every
 * click — is "which grids touch this block face". Grids are absolute rectangles
 * that may span blocks, so there is nothing to key them by directly, and a linear
 * sweep of every grid in the world is the naive answer. Instead each grid is
 * filed under every chunk its rectangle overlaps, which reduces the sweep to the
 * handful of grids near the player and needs no extra bookkeeping when a grid is
 * created: the chunks follow from the geometry. Filing under a single chunk would
 * be smaller but would lose exactly the grids the design exists to allow, the ones
 * that cross a chunk boundary.
 *
 * The second index is parent to children, which is what makes removal a subtree
 * operation. A child grid lives inside a parent cell, so a parent that vanishes
 * must take its children with it or they would be constrained by nothing.
 *
 * This class is the same on both sides. The server's copy is the authority and is
 * persisted by {@link GridStore}; the client's is a mirror filled from packets,
 * held by {@link ClientGridStore}. Keeping one implementation means hit-testing
 * asks the same questions of the same code wherever it runs.
 */
public class GridIndex {

    /** Nesting deeper than this is refused, so one face cannot grow an unbounded tree. */
    public static final int MAX_DEPTH = 4;

    private final Map<UUID, PlacementGrid> grids = new HashMap<>();
    private final Map<Long, Set<UUID>> byChunk = new HashMap<>();
    private final Map<UUID, Set<UUID>> byParent = new HashMap<>();

    /**
     * The index for whichever side this level is on: the persisted store on the
     * server, the packet-fed mirror on the client.
     */
    public static GridIndex of(Level level) {
        if (level instanceof ServerLevel server)
            return GridStore.of(server).index();
        return ClientGridStore.index();
    }

    public PlacementGrid get(UUID id) {
        return grids.get(id);
    }

    public boolean isEmpty() {
        return grids.isEmpty();
    }

    public Collection<PlacementGrid> all() {
        return Collections.unmodifiableCollection(grids.values());
    }

    public List<PlacementGrid> inChunk(long chunk) {
        Set<UUID> ids = byChunk.get(chunk);
        if (ids == null)
            return List.of();
        List<PlacementGrid> found = new ArrayList<>(ids.size());
        for (UUID id : ids)
            found.add(grids.get(id));
        return found;
    }

    /**
     * The grids ruled on one surface that cover part of one block, innermost last.
     * A grid is filed under every chunk it overlaps, so the block's own chunk holds
     * all the candidates however far the grid reaches beyond it.
     *
     * The surface rather than a block face, because a chamfer is a surface with no
     * face to name and the two clicks that rule a grid on one have nothing else to
     * agree about. A cardinal surface is a face stated another way, so nothing
     * changes for a grid on a wall.
     */
    public List<PlacementGrid> onSurface(BlockPos pos, PanelPlane surface) {
        List<PlacementGrid> found = new ArrayList<>();
        for (PlacementGrid grid : inChunk(ChunkPos.asLong(pos)))
            if (grid.touches(pos, surface))
                found.add(grid);
        found.sort((a, b) -> Integer.compare(depth(a.id()), depth(b.id())));
        return found;
    }

    public List<PlacementGrid> children(UUID parent) {
        Set<UUID> ids = byParent.get(parent);
        if (ids == null)
            return List.of();
        List<PlacementGrid> found = new ArrayList<>(ids.size());
        for (UUID id : ids)
            found.add(grids.get(id));
        return found;
    }

    /** How many parents a grid has above it; zero for a top-level grid. */
    public int depth(UUID id) {
        int depth = 0;
        PlacementGrid grid = grids.get(id);
        while (grid != null && grid.parent() != null && depth <= MAX_DEPTH) {
            grid = grids.get(grid.parent());
            depth++;
        }
        return depth;
    }

    /** Validates against what the store currently holds, then files the grid. */
    public Problem add(PlacementGrid grid) {
        if (grids.containsKey(grid.id()))
            return Problem.DUPLICATE;
        Problem problem = grid.check(grid.parent() == null ? null : grids.get(grid.parent()));
        if (!problem.ok())
            return problem;
        if (grid.isNested() && depth(grid.parent()) + 1 > MAX_DEPTH)
            return Problem.TOO_DEEP;
        file(grid);
        return Problem.NONE;
    }

    /**
     * Adds whatever the batch will accept, retrying while progress is made. A
     * child cannot be validated before its parent is present, and neither a save
     * file nor a packet is worth trusting to arrive in that order; the loop makes
     * the order irrelevant, and it terminates because each pass either files at
     * least one grid or stops.
     *
     * @return how many were accepted
     */
    public int addAll(Collection<PlacementGrid> batch) {
        List<PlacementGrid> pending = new ArrayList<>(batch);
        int accepted = 0;
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (!add(pending.get(i)).ok())
                    continue;
                pending.remove(i);
                accepted++;
                progressed = true;
            }
        }
        return accepted;
    }

    /** Whether any grid is nested inside this one. */
    public boolean hasChildren(UUID id) {
        return !byParent.getOrDefault(id, Set.of()).isEmpty();
    }

    /**
     * Removes one grid and nothing else, which is what a click asks for.
     *
     * A grid with children is refused rather than orphaning them. A child is only
     * valid while the parent it names is in the store — {@link PlacementGrid#check}
     * answers {@code PARENT_MISSING} otherwise, and a grid failing that check is
     * dropped the next time the store loads. So the choice is between rewriting every
     * child to stand on its own and refusing outright; refusing is the one that never
     * silently restructures a face, and the children that have to go first are
     * already drawn by the overlay.
     *
     * @return whether it was removed
     */
    public boolean removeOne(UUID id) {
        if (!grids.containsKey(id) || hasChildren(id))
            return false;
        unfile(id);
        return true;
    }

    /**
     * Removes a grid and everything nested inside it. This is the client's re-sync
     * path alone — see {@link #replaceChunk}, where a grid missing from a chunk
     * snapshot really has taken its subtree with it.
     *
     * @return the ids removed, children before their parents
     */
    public List<UUID> remove(UUID id) {
        List<UUID> removed = new ArrayList<>();
        collectSubtree(id, removed);
        for (UUID victim : removed)
            unfile(victim);
        return removed;
    }

    /**
     * Brings one chunk's contents into line with a snapshot of it: anything filed
     * under the chunk that the snapshot does not mention has gone, and everything
     * the snapshot carries is (re)filed.
     *
     * This is the whole of the client's re-sync story, and it is idempotent, which
     * is why grids are sent as per-chunk snapshots rather than as a stream of edits
     * a client could miss. A grid spanning two chunks appears in the snapshot of
     * both, so a snapshot for one chunk never mistakes it for deleted.
     */
    public void replaceChunk(long chunk, Collection<PlacementGrid> snapshot) {
        Set<UUID> present = new HashSet<>();
        for (PlacementGrid grid : snapshot)
            present.add(grid.id());
        for (UUID id : List.copyOf(byChunk.getOrDefault(chunk, Set.of())))
            if (!present.contains(id))
                remove(id);

        List<PlacementGrid> incoming = new ArrayList<>();
        for (PlacementGrid grid : snapshot) {
            PlacementGrid held = grids.get(grid.id());
            if (grid.equals(held))
                continue;
            // Geometry is immutable in practice, so this branch is a safety net
            // rather than a path: a grid that has changed shape may no longer
            // contain its children, so they go with it and arrive again below.
            if (held != null)
                remove(grid.id());
            incoming.add(grid);
        }
        addAll(incoming);
    }

    public void clear() {
        grids.clear();
        byChunk.clear();
        byParent.clear();
    }

    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (PlacementGrid grid : grids.values())
            list.add(grid.save());
        tag.put("Grids", list);
    }

    public void load(CompoundTag tag) {
        clear();
        List<PlacementGrid> loaded = new ArrayList<>();
        for (Tag entry : tag.getList("Grids", Tag.TAG_COMPOUND)) {
            PlacementGrid grid = PlacementGrid.load((CompoundTag) entry);
            if (grid != null)
                loaded.add(grid);
        }
        addAll(loaded);
    }

    private void collectSubtree(UUID id, List<UUID> into) {
        if (!grids.containsKey(id))
            return;
        for (UUID child : List.copyOf(byParent.getOrDefault(id, Set.of())))
            collectSubtree(child, into);
        into.add(id);
    }

    private void file(PlacementGrid grid) {
        grids.put(grid.id(), grid);
        grid.forEachChunk(chunk -> byChunk.computeIfAbsent(chunk, key -> new HashSet<>()).add(grid.id()));
        if (grid.isNested())
            byParent.computeIfAbsent(grid.parent(), key -> new HashSet<>()).add(grid.id());
    }

    /**
     * Unfiles one grid, assuming it has no children left. {@link #remove} is the
     * only caller and it works up from the leaves, which is what makes that safe.
     */
    private void unfile(UUID id) {
        PlacementGrid grid = grids.remove(id);
        if (grid == null)
            return;
        grid.forEachChunk(chunk -> {
            Set<UUID> ids = byChunk.get(chunk);
            if (ids != null && ids.remove(id) && ids.isEmpty())
                byChunk.remove(chunk);
        });
        byParent.remove(id);
        if (!grid.isNested())
            return;
        Set<UUID> siblings = byParent.get(grid.parent());
        if (siblings != null && siblings.remove(id) && siblings.isEmpty())
            byParent.remove(grid.parent());
    }
}
