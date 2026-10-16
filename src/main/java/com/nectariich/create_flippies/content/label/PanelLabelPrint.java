package com.nectariich.create_flippies.content.label;

import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * How the legend on one panel label is set: which face it is printed in, whether
 * it is bolded or slanted, and how tall it is drawn.
 *
 * Size is a number here rather than a handful of named steps. It used to be three
 * — small, medium, large — which was the right shape while every label claimed the
 * same seven-by-two strip and there were only three heights that fitted it. Now
 * that the player draws the strip, the useful sizes are whatever that strip
 * affords, and the same three names would mean something different on every label
 * on the panel.
 *
 * The unit is a tenth of a pixel of cap height, which is as fine as the editor
 * lets anyone ask for. Nothing between here and the glyph has a step in it — the
 * scale is a float and the text is drawn on a quad — so a tenth of a pixel is a
 * real difference on the wall rather than a number that rounds away.
 *
 * What a tenth gives up is a lattice. The old half-pixel unit put one font unit on
 * a whole number of 4096ths of a block, so the glyph raster landed on a regular
 * sub-lattice of the block's own texel grid and the stems of the letters stayed
 * even rather than dropping one column in three at some viewing distances. Every
 * size that is still a whole half — every multiple of five — lands on that same
 * lattice and looks exactly as it did; the sizes in between trade a little of that
 * evenness for fitting a legend to a strip that no half-pixel size fits.
 *
 * The turn is here for the same reason the size is: it is how the legend is set on
 * the strip rather than anything about the strip itself. See {@link #TURNS}.
 *
 * {@link #FIT} is the default and is not a size at all: it means take whatever the
 * strip affords, shrinking the text until the whole legend is on it. That is what
 * anyone drawing a rectangle around a legend actually wants, and it is only
 * possible because the strip is now the player's to choose. A fixed size still has
 * its place — a column of labels drawn to different widths reads better at one
 * height than at five — so it is a number in the editor, not a mode.
 */
public record PanelLabelPrint(PanelLabelFont font, boolean bold, boolean italic, int size, int quarters) {

    /** Fit the text to the strip rather than print it at a stated height. */
    public static final int FIT = 0;

    /**
     * Quarter turns of the legend within its strip, counted anticlockwise as the
     * reader sees it: nought reads across, one reads upwards, two is upside down and
     * three reads downwards.
     *
     * A turn rather than a second set of strip axes, because the strip is not turned
     * — it is the rectangle the player drew and it stays exactly where they drew it.
     * What turns is the lettering inside it, which is why a quarter turn swaps which
     * side of the strip caps the text and which side runs out of room: see
     * {@link #turned}.
     *
     * Four of them rather than free rotation. A legend at eleven degrees is not a
     * panel legend, and every quarter turn lands the glyph raster back on the
     * material's own texel grid, which is what keeps the stems of the letters even.
     */
    public static final int TURNS = 4;

    /** Tenths of a pixel of cap height, so a size of 10 draws one pixel tall. */
    public static final int UNITS_PER_PIXEL = 10;

    /** The smallest height that can be asked for, and the step between sizes. */
    public static final int MIN_SIZE = 1;

    /**
     * Font units to a block: a glyph is eight of them tall and prints one pixel of
     * cap height per {@link #UNITS_PER_PIXEL}, and a block is sixteen pixels.
     */
    private static final int FONT_UNITS_PER_BLOCK = PanelLabel.GLYPH_HEIGHT * PlacementGrid.PIXELS_PER_BLOCK;

    /** Denominator of the scale, which follows from the unit rather than being chosen. */
    private static final int SCALE_DENOMINATOR = UNITS_PER_PIXEL * FONT_UNITS_PER_BLOCK;

    /**
     * Size units of drawn width per font unit of measured width, which is the whole
     * of the width arithmetic below: text {@code n} font units wide printed at size
     * {@code s} covers {@code n * s / PER_FONT_UNIT} pixels.
     */
    private static final double PER_FONT_UNIT = SCALE_DENOMINATOR / (double) PlacementGrid.PIXELS_PER_BLOCK;

    /**
     * The largest text a strip could ever carry, in tenths of a pixel. A label may not
     * drawn larger than two blocks in the first place, and text taller than the
     * strip is cut down to it anyway, so this only bounds what a save or a packet
     * may claim.
     */
    public static final int MAX_SIZE = UNITS_PER_PIXEL * SpannedControlType.maxPixels();

    /**
     * What most of the default font's glyphs advance by: five columns of ink and one
     * of spacing. Used only to tell a display link how many characters will fit,
     * which is a promise about a typical string rather than about every string —
     * anything wider is trimmed on the way to the panel rather than refused.
     */
    private static final int NOMINAL_ADVANCE = 6;

    /** Plain lettering in the game's own face, fitted to whatever strip it is on. */
    public static final PanelLabelPrint DEFAULT = new PanelLabelPrint(PanelLabelFont.FALLBACK, false, false, FIT, 0);

    /** Whether the text is shrunk to fit the strip rather than printed at a fixed height. */
    public boolean fitted() {
        return size == FIT;
    }

    /**
     * Whether the legend runs across the strip's short way, which is what an odd
     * quarter turn does.
     *
     * Everything that measures a legend asks this rather than the turn itself,
     * because the only thing a turn changes about the arithmetic is which of the two
     * spans is the length the text has to fit along and which is the height it may
     * not exceed. A half turn changes neither — upside down is as long as the right
     * way up.
     */
    public boolean turned() {
        return (quarters & 1) != 0;
    }

    /** The span the legend runs along, given the strip it is on. */
    public int along(int width, int height) {
        return turned() ? height : width;
    }

    /** The span that caps how tall the legend may be printed. */
    public int across(int width, int height) {
        return turned() ? width : height;
    }

    /** Blocks per unit of font space, which is what the renderer scales its pose by. */
    public static float scale(int size) {
        return size / (float) SCALE_DENOMINATOR;
    }

    /**
     * The tallest text that fits between the long edges of a strip, in size units.
     *
     * This is a hard cap and not a preference: the strip is the label's footprint
     * and therefore its hitbox, and text drawn taller than the box it was reserved
     * would hang over whatever is mounted next to it while being pickable nowhere.
     */
    public static int heightLimit(int height) {
        return Math.max(MIN_SIZE, (int) (UNITS_PER_PIXEL * SpannedControlType.pixels(height)));
    }

    /**
     * The height this label prints at on a strip that tall, before the legend itself
     * is taken into account: the size that was asked for, cut down to the strip, or
     * the whole strip when fitting.
     */
    public int sizeOn(int height) {
        int limit = heightLimit(height);
        return fitted() ? limit : Mth.clamp(size, MIN_SIZE, limit);
    }

    /**
     * The largest size at which a legend that wide in font units still fits between
     * the ends of a strip that long. Zero for a legend with no width, which is the
     * caller's cue that there is nothing to fit.
     */
    public static int widthLimit(int width, double textUnits) {
        if (textUnits <= 0)
            return 0;
        double available = usableWidth(width);
        return (int) (available * PER_FONT_UNIT / textUnits);
    }

    /** How much of a strip that long is printable, in pixels. */
    public static double usableWidth(int width) {
        return Math.max(0, SpannedControlType.pixels(width) - 2 * PanelLabel.TEXT_INSET);
    }

    /**
     * How wide the text may be in font space at a given size, which is what a legend
     * too long for its strip is trimmed to.
     */
    public static int textWidthUnits(int width, int size) {
        return (int) (usableWidth(width) * PER_FONT_UNIT / Math.max(MIN_SIZE, size));
    }

    /**
     * Characters a display link is told will fit.
     *
     * Measured at the height the strip affords rather than at the height a fitted
     * label will end up printing at, because that height depends on the string and
     * the link is asking before it has sent one. It is therefore the fewest that
     * will fit rather than the most, which is the right way round: a link that sent
     * a line too long for the strip would have it trimmed, and one that sent a short
     * line simply has it printed larger.
     */
    public int columns(int width, int height) {
        return Math.max(1,
                textWidthUnits(along(width, height), sizeOn(across(width, height))) / NOMINAL_ADVANCE);
    }

    /** A size in tenths from the height in pixels the editor speaks in. */
    public static int sizeFromPixels(double pixels) {
        return Mth.clamp((int) Math.round(pixels * UNITS_PER_PIXEL), MIN_SIZE, MAX_SIZE);
    }

    /** The same the other way, for putting a stored size back into the editor. */
    public static double pixels(int size) {
        return size / (double) UNITS_PER_PIXEL;
    }

    public PanelLabelPrint withFont(PanelLabelFont font) {
        return new PanelLabelPrint(font, bold, italic, size, quarters);
    }

    public PanelLabelPrint withBold(boolean bold) {
        return new PanelLabelPrint(font, bold, italic, size, quarters);
    }

    public PanelLabelPrint withItalic(boolean italic) {
        return new PanelLabelPrint(font, bold, italic, size, quarters);
    }

    public PanelLabelPrint withSize(int size) {
        return new PanelLabelPrint(font, bold, italic, sanitiseSize(size), quarters);
    }

    /** The turn wraps rather than clamps, so one button can cycle the four. */
    public PanelLabelPrint withQuarters(int quarters) {
        return new PanelLabelPrint(font, bold, italic, size, Math.floorMod(quarters, TURNS));
    }

    public void write(CompoundTag tag) {
        tag.putString("Font", font.id());
        tag.putBoolean("Bold", bold);
        tag.putBoolean("Italic", italic);
        tag.putInt("TextSize", size);
        tag.putByte("Quarters", (byte) quarters);
    }

    /**
     * What a tag describes, defaulting anything it does not carry — which is the
     * whole of it for a label saved before any of this existed. Such a label read
     * back as the medium of three sizes, so its stored size is honoured if it is
     * there and it falls to fitting if it is not.
     */
    public static PanelLabelPrint read(CompoundTag tag) {
        return new PanelLabelPrint(PanelLabelFont.byId(tag.getString("Font")), tag.getBoolean("Bold"),
                tag.getBoolean("Italic"), sanitiseSize(tag.getInt("TextSize")),
                Math.floorMod(tag.getByte("Quarters"), TURNS));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeByte(font.ordinal());
        buffer.writeBoolean(bold);
        buffer.writeBoolean(italic);
        buffer.writeVarInt(size);
        buffer.writeByte(quarters);
    }

    /** Guards every field, because all five arrive from a client that may say anything. */
    public static PanelLabelPrint decode(FriendlyByteBuf buffer) {
        return new PanelLabelPrint(PanelLabelFont.byOrdinal(buffer.readByte()), buffer.readBoolean(),
                buffer.readBoolean(), sanitiseSize(buffer.readVarInt()),
                Math.floorMod(buffer.readByte(), TURNS));
    }

    /** Fitting, or a height between half a pixel and the largest strip there can be. */
    private static int sanitiseSize(int size) {
        return size == FIT ? FIT : Mth.clamp(size, MIN_SIZE, MAX_SIZE);
    }
}
