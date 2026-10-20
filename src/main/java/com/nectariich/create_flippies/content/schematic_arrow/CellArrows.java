package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * What one panel cell hands its model so the arrows on it can be drawn. A copy, for
 * the reason {@code CellPaint} is one: the model reads it on a chunk build thread.
 */
public record CellArrows(BlockPos cell, List<PanelArrow> arrows) {

    public CellArrows {
        arrows = List.copyOf(arrows);
    }
}
