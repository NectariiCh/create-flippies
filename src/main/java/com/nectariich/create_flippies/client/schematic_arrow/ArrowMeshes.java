package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;

/**
 * The flat meshes of the arrows this client has drawn lately, by arrow.
 *
 * An arrow is the same in every cell it crosses and every time its chunk is
 * rebuilt, and working its outline out and triangulating it is the expensive part of
 * drawing it, so it is done once and kept. Keyed by the whole arrow rather than its
 * id, so an arrow that has been moved or restyled is simply a different key and
 * nothing has to be told to forget anything. The oldest are let go past a budget.
 *
 * Read from chunk build threads, hence the lock.
 */
public final class ArrowMeshes {

    private static final int BUDGET = 256;

    private static final Map<PanelArrow, ArrowMesh> MESHES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PanelArrow, ArrowMesh> eldest) {
            return size() > BUDGET;
        }
    };

    private ArrowMeshes() {}

    /** The arrow's mesh, or null for a stamped one whose picture has not arrived. Any thread. */
    public static ArrowMesh of(PanelArrow arrow) {
        synchronized (MESHES) {
            ArrowMesh mesh = MESHES.get(arrow);
            if (mesh != null)
                return mesh;
        }
        ArrowMesh mesh = ArrowMesh.of(arrow, arrow.isStamped() ? ClientDecals.art(arrow.art()) : null);
        if (mesh == null)
            return null;
        synchronized (MESHES) {
            MESHES.put(arrow, mesh);
        }
        return mesh;
    }
}
