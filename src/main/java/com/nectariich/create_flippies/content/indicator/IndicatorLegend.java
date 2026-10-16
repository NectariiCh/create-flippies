package com.nectariich.create_flippies.content.indicator;

import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What is printed on the cap of one indicator: a word, the colour it is printed in,
 * and which way up it reads.
 *
 * A pushbutton with nothing written on it is a mystery, so this is the other half of
 * making these buttons rather than lamps. It is deliberately not a
 * {@link com.nectariich.create_flippies.content.label.PanelLabelControl}: a label is
 * a strip of panel the player draws and can put anywhere, and this is ink on the cap
 * itself, which moves with the cap when it is pressed, turns with the unit when it is
 * turned, and takes up no panel space of its own because it is inside the footprint
 * the cap already claimed.
 *
 * What it does borrow from a label is how the ink comes out. The font is drawn
 * unshaded at full brightness, so a colour taken straight from a picker reads as a
 * lit sign rather than as lettering — {@link PanelLabel#ink} is the same quarter off
 * that sits a legend down onto the panel, and using it rather than a second constant
 * here is what keeps a caption on a cap the same colour as the same hex on the label
 * beside it.
 *
 * The turn is remembered because a cap can be read from any side. Four quarters, no
 * free rotation, for the reason
 * {@link com.nectariich.create_flippies.content.label.PanelLabelPrint#TURNS} gives:
 * a quarter turn lands the glyph raster back on the material's own texel grid, and a
 * legend at eleven degrees is not a panel legend.
 *
 * How large the text comes out is never stored. A cap is one pixel across on the
 * medium unit and one and a half on the large one, so there is exactly one useful
 * answer — as large as fits — and a stored height would only ever be a way of
 * getting it wrong.
 */
public record IndicatorLegend(String text, int colour, int quarters) {

    /** Quarter turns, counted anticlockwise as the reader sees them. */
    public static final int TURNS = 4;

    /**
     * The most a cap may carry. Far shorter than a label's, because a cap is not a
     * strip: everything written on one is fitted to a pixel or so across, so a long
     * word is not trimmed but shrunk, and past about this many characters it shrinks
     * to something nobody can read at any distance. STOP, START and E-STOP are what
     * these are for.
     */
    public static final int MAX_LENGTH = 16;

    /** White, because the cap behind it is whatever colour lens was fitted. */
    public static final int DEFAULT_COLOUR = 0xFFFFFFFF;

    /** A cap with nothing printed on it, which is how every indicator starts. */
    public static final IndicatorLegend BLANK = new IndicatorLegend("", DEFAULT_COLOUR, 0);

    /**
     * Kept off the edges of the cap, in authored pixels, so that lettering does not
     * run into the moulded rim around the lens. A twentieth of a pixel on a cap that
     * is one pixel across — proportionally what {@link PanelLabel#TEXT_INSET} is on a
     * label, which is where the number comes from.
     */
    private static final double INSET = 0.05;

    /** Whether there is anything to draw at all. */
    public boolean isBlank() {
        return text.isEmpty();
    }

    /** The colour it is actually printed in; see {@link PanelLabel#ink}. */
    public int ink() {
        return PanelLabel.ink(colour);
    }

    public IndicatorLegend withText(String text) {
        return new IndicatorLegend(PanelLabel.sanitise(trim(text)), colour, quarters);
    }

    public IndicatorLegend withColour(int colour) {
        return new IndicatorLegend(text, PanelLabel.sanitiseColour(colour), quarters);
    }

    /** The turn wraps rather than clamps, so one button can cycle the four. */
    public IndicatorLegend withQuarters(int quarters) {
        return new IndicatorLegend(text, colour, Math.floorMod(quarters, TURNS));
    }

    /**
     * How much of a cap that size may be printed on, in authored pixels.
     *
     * Square, so one number serves as both the length the lettering runs along and
     * the height it may not exceed — which is what makes a quarter turn cost nothing
     * here, unlike on a label, where the two spans differ and a turn exchanges them.
     */
    public static double usable(IndicatorSize size) {
        return Math.max(0, size.capWidth() - 2 * INSET);
    }

    /**
     * Blocks per unit of font space for a legend this wide on a cap that size: as
     * large as fits between the rims, and never so tall that the lettering stands
     * outside the cap it is printed on.
     *
     * @param textUnits the legend's measured width in font units, which only the
     *                  client can ask the font for
     */
    public static float scale(IndicatorSize size, double textUnits) {
        double available = usable(size) / PlacementGrid.PIXELS_PER_BLOCK;
        double byHeight = available / PanelLabel.GLYPH_HEIGHT;
        if (textUnits <= 0)
            return (float) byHeight;
        return (float) Math.min(byHeight, available / textUnits);
    }

    public void write(CompoundTag tag) {
        tag.putString("Legend", text);
        tag.putInt("LegendColour", colour);
        tag.putByte("LegendQuarters", (byte) quarters);
    }

    /**
     * What a tag describes, defaulting anything it does not carry — which is all of
     * it for an indicator saved before a cap could be written on, and which reads
     * back as the blank cap it was.
     */
    public static IndicatorLegend read(CompoundTag tag) {
        if (!tag.contains("Legend"))
            return BLANK;
        return new IndicatorLegend(PanelLabel.sanitise(trim(tag.getString("Legend"))),
                PanelLabel.sanitiseColour(tag.contains("LegendColour") ? tag.getInt("LegendColour") : DEFAULT_COLOUR),
                Math.floorMod(tag.getByte("LegendQuarters"), TURNS));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(text, MAX_LENGTH * 4);
        buffer.writeInt(colour);
        buffer.writeByte(quarters);
    }

    /** Guards every field, because all three arrive from a client that may say anything. */
    public static IndicatorLegend decode(FriendlyByteBuf buffer) {
        return new IndicatorLegend(PanelLabel.sanitise(trim(buffer.readUtf(MAX_LENGTH * 4))),
                PanelLabel.sanitiseColour(buffer.readInt()), Math.floorMod(buffer.readByte(), TURNS));
    }

    /**
     * A legend cut to what a cap will hold. Done before
     * {@link PanelLabel#sanitise}, which enforces its own much longer limit, so that
     * the two never disagree about where the cut is.
     */
    private static String trim(String text) {
        if (text.length() <= MAX_LENGTH)
            return text;
        // Cutting between the halves of a surrogate pair leaves a string that is not
        // text at all, so the pair goes rather than half of it
        int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
        return text.substring(0, end);
    }
}
