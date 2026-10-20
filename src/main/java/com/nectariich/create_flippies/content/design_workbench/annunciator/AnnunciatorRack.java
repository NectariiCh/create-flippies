package com.nectariich.create_flippies.content.design_workbench.annunciator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.content.design_workbench.DesignText;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * Data-driven description of one annunciator rack: how big it is, how many windows
 * it holds, what each window says and what colour it lights. Java port of the Unity
 * {@code AnnunciatorRackDefinition}, measured in authored pixels instead of metres.
 *
 * {@link AnnunciatorMesh} turns this into the model (frame, sockets, one lens per
 * window), and the legends are laid out here as the text a design prints, so nothing
 * about a rack is modelled by hand: change the grid or re-import a CSV and the model
 * and legends follow.
 *
 * Space: authored pixels, like every design. The rack stands on the plate with the
 * middle of its face over the middle of the cell ({@link #CENTRE}), its box rising
 * {@link #depth} out of the panel, and each lens sunk {@link #recess} into its
 * socket. Seen from the front (the workbench's top view), x runs right and z runs
 * down, so row 0 is the top row and column 0 the left one, which is how a CSV reads.
 *
 * Immutable and safe on both sides; {@link #read} sanitises anything a client sends.
 * Pure data, so it is also what the server keeps on an item and a placed control.
 */
public final class AnnunciatorRack {

    public static final String TAG = "Rack";

    /** Where the middle of the face sits on the cell, along x and z, in pixels. */
    public static final float CENTRE = 8;

    public static final int MAX_CELLS = 16;
    public static final float MAX_SIZE = 32, MAX_DEPTH = 8, MAX_BEZEL = 8, MAX_RIB = 4;
    public static final float MIN_LEGEND = 0.25f, MAX_LEGEND = 4, MAX_PADDING = 4;
    public static final int MAX_LEGEND_LENGTH = 64, MAX_LEGEND_LINES = 4, MAX_ID = 32;

    /** How much of a line's pitch a legend's capitals take at most, leaving the rest as leading. */
    private static final float LINE_FILL = 0.7f;

    /** How far in front of the lens the legend is printed. */
    private static final float LEGEND_LIFT = 0.01f;

    /** What a window looks like dark is mixed from this and its lit colour; see {@link #unlitRgb}. */
    private static final int UNLIT_BASE = 0x0D0D0D;

    /** One window. A blank one is frame with no window; an empty legend is a spare window. */
    public record Tile(String legend, LensColor color, boolean blank, String id) {

        public static final Tile EMPTY = new Tile("", LensColor.WHITE, false, "");

        public boolean isEmpty() {
            return blank || legend.isBlank();
        }
    }

    public final int cellsX, cellsY;
    public final float width, height, depth, bezel, rib, recess;
    public final int frameColor, lensColor, legendColor;
    public final float unlitTint, legendSize, legendPadding;
    public final String font;

    /** One per cell, row-major from the top left. */
    public final List<Tile> tiles;

    /** The legends as the text a design prints, laid out once. Empty while the layout is invalid. */
    private final List<DesignText> legends;

    public AnnunciatorRack(int cellsX, int cellsY, float width, float height, float depth, float bezel, float rib,
            float recess, int frameColor, int lensColor, float unlitTint, int legendColor, float legendSize,
            float legendPadding, String font, List<Tile> tiles) {
        this.cellsX = Mth.clamp(cellsX, 1, MAX_CELLS);
        this.cellsY = Mth.clamp(cellsY, 1, MAX_CELLS);
        this.width = clamp(width, 1, MAX_SIZE, 24);
        this.height = clamp(height, 1, MAX_SIZE, 12);
        this.depth = clamp(depth, 0.25f, MAX_DEPTH, 2);
        this.bezel = clamp(bezel, 0, MAX_BEZEL, 0.75f);
        this.rib = clamp(rib, 0, MAX_RIB, 0.25f);
        this.recess = clamp(recess, 0, MAX_DEPTH, 0.25f);
        this.frameColor = frameColor & 0xFFFFFF;
        this.lensColor = lensColor & 0xFFFFFF;
        this.unlitTint = clamp(unlitTint, 0.02f, 0.6f, 0.22f);
        this.legendColor = legendColor & 0xFFFFFF;
        this.legendSize = clamp(legendSize, MIN_LEGEND, MAX_LEGEND, 1);
        this.legendPadding = clamp(legendPadding, 0, MAX_PADDING, 0.25f);
        this.font = font != null && DesignText.BUILT_IN_FONTS.contains(font) ? font : DesignText.DEFAULT_FONT;

        // Exactly one tile per cell, so every lookup can index without checking
        List<Tile> laid = new ArrayList<>(this.cellsX * this.cellsY);
        for (int i = 0; i < this.cellsX * this.cellsY; i++)
            laid.add(tiles != null && i < tiles.size() && tiles.get(i) != null ? clean(tiles.get(i)) : Tile.EMPTY);
        this.tiles = List.copyOf(laid);
        this.legends = error() == null ? layOutLegends() : List.of();
    }

    /** The rack a fresh workbench starts from: a 6 x 3 rack of blank white windows. */
    public static AnnunciatorRack defaults() {
        return new AnnunciatorRack(6, 3, 24, 12, 2, 0.75f, 0.25f, 0.25f, 0x2E2E30, 0x9E9E96, 0.22f, 0x0F0F0F, 1,
                0.25f, DesignText.DEFAULT_FONT, List.of());
    }

    // ------------------------------------------------------------- geometry

    /**
     * Size of one window. Both come out of the face size, so a rack is described the
     * way it is ordered: this big, this many windows.
     */
    public float cellWidth() {
        return (width - 2 * bezel - (cellsX - 1) * rib) / cellsX;
    }

    public float cellHeight() {
        return (height - 2 * bezel - (cellsY - 1) * rib) / cellsY;
    }

    /** The left edge of a column's window, in authored pixels. */
    public float cellLeft(int column) {
        return CENTRE - width / 2 + bezel + column * (cellWidth() + rib);
    }

    /** The top edge (smallest z) of a row's window, in authored pixels. */
    public float cellTop(int row) {
        return CENTRE - height / 2 + bezel + row * (cellHeight() + rib);
    }

    /** The height of the lens plane above the panel. */
    public float lensY() {
        return depth - recess;
    }

    public int index(int row, int column) {
        return row * cellsX + column;
    }

    public Tile tile(int row, int column) {
        return tiles.get(index(row, column));
    }

    /** The box the rack takes on the panel, in authored pixels, as {minX, minY, minZ, maxX, maxY, maxZ}. */
    public double[] box() {
        return new double[] { CENTRE - width / 2, 0, CENTRE - height / 2, CENTRE + width / 2, depth,
                CENTRE + height / 2 };
    }

    /**
     * Why this rack cannot be built, as a translation key under the workbench's
     * strings, or null when it can.
     */
    public String error() {
        if (cellWidth() <= 0 || cellHeight() <= 0)
            return "rack.error.room";
        if (recess >= depth)
            return "rack.error.recess";
        return null;
    }

    // ---------------------------------------------------------------- colour

    /**
     * What a lens looks like with the lamp out, as 0xRRGGBB: the colourless lens for
     * a plain window, a dark version of its own colour for an x one.
     */
    public int unlitRgb(LensColor color) {
        if (!color.keepsColorUnlit())
            return lensColor;
        int lit = color.litRgb();
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            float base = (UNLIT_BASE >> shift & 0xFF), hue = (lit >> shift & 0xFF);
            out |= Math.round(Mth.lerp(unlitTint, base, hue)) << shift;
        }
        return out;
    }

    // ---------------------------------------------------------------- legends

    /** The legends as the text a design prints with them, printed on the lenses. */
    public List<DesignText> legends() {
        return legends;
    }

    /**
     * One text box per line of each legend, stacked down its window. A line gets an
     * equal share of the window's height and its capitals at most
     * {@link #legendSize}; the text renderer shrinks a long one to fit across, which
     * is what keeps a legend on its lens rather than spilling over the frame.
     */
    private List<DesignText> layOutLegends() {
        List<DesignText> out = new ArrayList<>();
        float across = cellWidth() - 2 * legendPadding;
        float down = cellHeight() - 2 * legendPadding;
        if (across <= 0 || down <= 0)
            return out;
        for (int row = 0; row < cellsY; row++) {
            for (int column = 0; column < cellsX; column++) {
                Tile tile = tile(row, column);
                if (tile.isEmpty())
                    continue;
                String[] lines = tile.legend().split("\n");
                float pitch = down / lines.length;
                float size = Math.min(legendSize, pitch * LINE_FILL);
                float left = cellLeft(column) + legendPadding;
                float top = cellTop(row) + legendPadding;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty())
                        continue;
                    out.add(DesignText.read(new DesignText(line, left, top + i * pitch, left + across,
                            top + (i + 1) * pitch, lensY() + LEGEND_LIFT, false, font, false, false, false, false,
                            legendColor, size, 1, 0, 0).write()));
                }
            }
        }
        return List.copyOf(out);
    }

    // -------------------------------------------------------------------- nbt

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("CellsX", cellsX);
        tag.putInt("CellsY", cellsY);
        tag.putFloat("Width", width);
        tag.putFloat("Height", height);
        tag.putFloat("Depth", depth);
        tag.putFloat("Bezel", bezel);
        tag.putFloat("Rib", rib);
        tag.putFloat("Recess", recess);
        tag.putInt("Frame", frameColor);
        tag.putInt("Lens", lensColor);
        tag.putFloat("Tint", unlitTint);
        tag.putInt("Legend", legendColor);
        tag.putFloat("LegendSize", legendSize);
        tag.putFloat("Padding", legendPadding);
        tag.putString("Font", font);
        ListTag list = new ListTag();
        for (Tile tile : tiles) {
            CompoundTag entry = new CompoundTag();
            // Short keys and defaults left out: a big rack is mostly short legends
            if (!tile.legend().isEmpty())
                entry.putString("L", tile.legend());
            if (tile.color() != LensColor.WHITE)
                entry.putString("C", tile.color().wireName());
            if (tile.blank())
                entry.putBoolean("B", true);
            if (!tile.id().isEmpty())
                entry.putString("I", tile.id());
            list.add(entry);
        }
        tag.put("Tiles", list);
        return tag;
    }

    /**
     * Reads a rack and makes it safe to build, whoever wrote it. A value the tag does
     * not have is the default rack's.
     */
    public static AnnunciatorRack read(CompoundTag tag) {
        AnnunciatorRack d = defaults();
        ListTag list = tag.getList("Tiles", Tag.TAG_COMPOUND);
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < list.size() && i < MAX_CELLS * MAX_CELLS; i++) {
            CompoundTag entry = list.getCompound(i);
            tiles.add(new Tile(entry.getString("L"), LensColor.of(entry.getString("C")), entry.getBoolean("B"),
                    entry.getString("I")));
        }
        return new AnnunciatorRack(i(tag, "CellsX", d.cellsX), i(tag, "CellsY", d.cellsY), f(tag, "Width", d.width),
                f(tag, "Height", d.height), f(tag, "Depth", d.depth), f(tag, "Bezel", d.bezel), f(tag, "Rib", d.rib),
                f(tag, "Recess", d.recess), i(tag, "Frame", d.frameColor), i(tag, "Lens", d.lensColor),
                f(tag, "Tint", d.unlitTint), i(tag, "Legend", d.legendColor), f(tag, "LegendSize", d.legendSize),
                f(tag, "Padding", d.legendPadding), tag.getString("Font"), tiles);
    }

    private static int i(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : fallback;
    }

    private static float f(CompoundTag tag, String key, float fallback) {
        return tag.contains(key, Tag.TAG_FLOAT) ? tag.getFloat(key) : fallback;
    }

    /** A tile with its legend bounded to a few short lines and its id to a slug-sized string. */
    private static Tile clean(Tile tile) {
        if (tile.blank())
            return new Tile("", tile.color() == null ? LensColor.WHITE : tile.color(), true, "");
        String legend = tile.legend() == null ? "" : tile.legend().replace("\r", "");
        String[] lines = legend.split("\n", -1);
        if (lines.length > MAX_LEGEND_LINES)
            legend = String.join("\n", java.util.Arrays.copyOf(lines, MAX_LEGEND_LINES));
        if (legend.length() > MAX_LEGEND_LENGTH)
            legend = legend.substring(0, MAX_LEGEND_LENGTH);
        String id = tile.id() == null ? "" : tile.id().trim();
        if (id.length() > MAX_ID)
            id = id.substring(0, MAX_ID);
        return new Tile(legend.strip(), tile.color() == null ? LensColor.WHITE : tile.color(), false, id);
    }

    /**
     * A value held to its range. A zero where zero is not allowed is a tag that never
     * had the value, so it falls back to the default rather than to the minimum.
     */
    private static float clamp(float value, float min, float max, float fallback) {
        if (!Float.isFinite(value) || value == 0 && min > 0)
            return fallback;
        return Mth.clamp(value, min, max);
    }

    /**
     * The readable id a window gets when the CSV does not name one: the legend upper
     * cased, with everything that is not a letter or digit collapsed into single
     * underscores. "RCP 1 / LO SPEED" becomes "RCP_1_LO_SPEED".
     */
    public static String slugFor(String legend) {
        if (legend == null || legend.isBlank())
            return "";
        StringBuilder slug = new StringBuilder(legend.length());
        boolean separator = false;
        for (char character : legend.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character)) {
                if (separator && !slug.isEmpty())
                    slug.append('_');
                separator = false;
                slug.append(character);
            } else {
                separator = true;
            }
        }
        return slug.length() > MAX_ID ? slug.substring(0, MAX_ID) : slug.toString();
    }
}
