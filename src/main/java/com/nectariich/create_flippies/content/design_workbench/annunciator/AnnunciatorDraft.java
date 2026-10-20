package com.nectariich.create_flippies.content.design_workbench.annunciator;

import java.util.ArrayList;
import java.util.List;

/**
 * A rack being edited: the fields of an {@link AnnunciatorRack}, open to change one
 * at a time as the workbench's widgets and the CSV importer use them, and turned back
 * into a sanitised rack whenever the design is built.
 *
 * The windows are kept on the largest grid a rack can have, by row and column, and
 * the rack is whatever corner of it {@link #cellsX} by {@link #cellsY} covers. So
 * resizing never moves or loses a window: a rack that gets a column wider does not
 * shuffle every legend along one, and typing "12" into the width, which passes
 * through "1" on the way, does not throw away columns 2 to 12.
 */
public final class AnnunciatorDraft {

    /** One window, as the editor changes it. */
    public static final class TileDraft {
        public String legend = "";
        public LensColor color = LensColor.WHITE;
        public boolean blank;
        public String id = "";

        AnnunciatorRack.Tile build() {
            return new AnnunciatorRack.Tile(legend, color, blank, id);
        }
    }

    public int cellsX, cellsY;
    public float width, height, depth, bezel, rib, recess;
    public int frameColor, lensColor, legendColor;
    public float unlitTint, legendSize, legendPadding;
    public String font;

    /** Take the grid size from an imported CSV instead of making the CSV fit the rack. */
    public boolean resizeToCsv = true;

    private static final int SIDE = AnnunciatorRack.MAX_CELLS;

    /** Every window a rack could have, row-major on a {@link #SIDE}-wide grid. */
    private final TileDraft[] grid = new TileDraft[SIDE * SIDE];

    private AnnunciatorDraft() {
        for (int i = 0; i < grid.length; i++)
            grid[i] = new TileDraft();
    }

    public static AnnunciatorDraft of(AnnunciatorRack rack) {
        AnnunciatorDraft draft = new AnnunciatorDraft();
        draft.cellsX = rack.cellsX;
        draft.cellsY = rack.cellsY;
        draft.width = rack.width;
        draft.height = rack.height;
        draft.depth = rack.depth;
        draft.bezel = rack.bezel;
        draft.rib = rack.rib;
        draft.recess = rack.recess;
        draft.frameColor = rack.frameColor;
        draft.lensColor = rack.lensColor;
        draft.legendColor = rack.legendColor;
        draft.unlitTint = rack.unlitTint;
        draft.legendSize = rack.legendSize;
        draft.legendPadding = rack.legendPadding;
        draft.font = rack.font;
        for (int row = 0; row < rack.cellsY; row++)
            for (int column = 0; column < rack.cellsX; column++) {
                AnnunciatorRack.Tile tile = rack.tile(row, column);
                TileDraft out = draft.tile(row, column);
                out.legend = tile.legend();
                out.color = tile.color();
                out.blank = tile.blank();
                out.id = tile.id();
            }
        return draft;
    }

    /** The rack as a design holds it, sanitised, so the workbench shows exactly what a save will hold. */
    public AnnunciatorRack build() {
        List<AnnunciatorRack.Tile> built = new ArrayList<>();
        for (TileDraft tile : tiles())
            built.add(tile.build());
        return AnnunciatorRack.read(new AnnunciatorRack(cellsX, cellsY, width, height, depth, bezel, rib, recess,
                frameColor, lensColor, unlitTint, legendColor, legendSize, legendPadding, font, built).write());
    }

    /** The window at a cell. */
    public TileDraft tile(int row, int column) {
        ensureTiles();
        return grid[row * SIDE + column];
    }

    /** Holds the grid size to what a rack can be. Resizing needs nothing else: see the class notes. */
    public void ensureTiles() {
        cellsX = Math.max(1, Math.min(SIDE, cellsX));
        cellsY = Math.max(1, Math.min(SIDE, cellsY));
    }

    /** The rack's windows, row-major across its {@link #cellsX} columns. */
    public TileDraft[] tiles() {
        ensureTiles();
        TileDraft[] out = new TileDraft[cellsX * cellsY];
        for (int row = 0; row < cellsY; row++)
            System.arraycopy(grid, row * SIDE, out, row * cellsX, cellsX);
        return out;
    }
}
