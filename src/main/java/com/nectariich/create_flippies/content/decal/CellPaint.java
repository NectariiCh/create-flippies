package com.nectariich.create_flippies.content.decal;

import java.util.List;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;

import net.minecraft.core.BlockPos;

/**
 * What one panel cell hands its model so the paint on it can be drawn: the paint,
 * and the plates it may lie on.
 *
 * The plates travel with the paint rather than being read back from the tiles the
 * model already has, because a tile says where material goes and not which panel
 * it belongs to, and paint has to be clipped to the panels of its own surface and
 * no others. Copies, since the model reads them on a chunk build thread while the
 * block entity carries on being changed on the client thread.
 */
public record CellPaint(BlockPos cell, List<PanelDefinition> panels, List<PanelDecal> decals) {

    public CellPaint {
        panels = List.copyOf(panels);
        decals = List.copyOf(decals);
    }
}
