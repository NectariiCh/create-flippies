package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Locale;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * How a schematic arrow looks: the strip's width, what its ends finish in, how its
 * bends are cut, how far it stands off the panel and what colour it is.
 *
 * Lengths are in panel offset units ({@link PanelPlane#UNITS_PER_BLOCK} to the
 * block), the lattice everything else on a panel is measured in. Every constructor
 * path clamps, so a style read from a tag or a packet is always one that can be
 * drawn.
 *
 * @param width       the shaft's width
 * @param height      how far the top stands off the plate
 * @param colour      opaque ARGB, any 24-bit colour, or {@link #OWN_COLOURS} for an imported drawing's own colours
 * @param shadedSides whether the side walls are drawn a shade darker than the top
 * @param headLength  how far a head reaches back from its tip, or {@link #AUTO} for the length the shaft width gives
 * @param headWidth   how wide a head is across, or {@link #AUTO}
 * @param headStroke  how thick a chevron's arms are, or {@link #AUTO}
 */
public record ArrowStyle(double width, ArrowHead start, ArrowHead end, ArrowJoin join, double height, int colour,
        boolean shadedSides, int headLength, int headWidth, int headStroke) {

    /** The colour that is no colour: an imported drawing keeps its own, a drawn arrow takes {@link #PLAIN}. */
    public static final int OWN_COLOURS = 0;

    /** What a drawn arrow with no colour of its own is drawn in. */
    public static final int PLAIN = 0xFFF0F0F0;

    private static final int PIXEL = PanelPlane.UNITS_PER_PIXEL;

    /**
     * The widths the menu offers: a tenth of a pixel to eight pixels. Fractional offset
     * units, since a tenth of a pixel is not a whole one; the outline is worked in
     * doubles anyway.
     */
    public static final double[] WIDTHS = { PIXEL / 10.0, PIXEL / 4.0, PIXEL / 2, PIXEL, PIXEL * 3 / 2, 2 * PIXEL, 3 * PIXEL, 4 * PIXEL,
            6 * PIXEL, 8 * PIXEL };

    /** The heights the menu offers: a tenth of a pixel to two pixels. */
    public static final double[] HEIGHTS = { PIXEL / 10.0, PIXEL / 4, PIXEL / 2, PIXEL, PIXEL * 3 / 2, 2 * PIXEL };

    /** A head measurement that is not set: the head is sized from the shaft width, as every arrow once was. */
    public static final int AUTO = 0;

    /** The head lengths the menu offers: sized from the shaft, then one to twenty-four pixels. */
    public static final int[] HEAD_LENGTHS = { AUTO, PIXEL, 2 * PIXEL, 3 * PIXEL, 4 * PIXEL, 6 * PIXEL, 8 * PIXEL,
            12 * PIXEL, 16 * PIXEL, 24 * PIXEL };

    /** The head widths the menu offers: sized from the shaft, then two to thirty-two pixels across. */
    public static final int[] HEAD_WIDTHS = { AUTO, 2 * PIXEL, 3 * PIXEL, 4 * PIXEL, 6 * PIXEL, 8 * PIXEL,
            12 * PIXEL, 16 * PIXEL, 24 * PIXEL, 32 * PIXEL };

    /** The chevron strokes the menu offers: sized from the shaft, then half a pixel to four pixels. */
    public static final int[] HEAD_STROKES = { AUTO, PIXEL / 2, PIXEL, PIXEL * 3 / 2, 2 * PIXEL, 3 * PIXEL,
            4 * PIXEL };

    public static final int MIN_HEAD = PIXEL / 2;
    public static final int MAX_HEAD_LENGTH = HEAD_LENGTHS[HEAD_LENGTHS.length - 1];
    public static final int MAX_HEAD_WIDTH = HEAD_WIDTHS[HEAD_WIDTHS.length - 1];
    public static final int MAX_HEAD_STROKE = HEAD_STROKES[HEAD_STROKES.length - 1];

    public static final double MIN_WIDTH = WIDTHS[0];
    public static final double MAX_WIDTH = WIDTHS[WIDTHS.length - 1];
    public static final double MIN_HEIGHT = HEIGHTS[0];
    public static final double MAX_HEIGHT = HEIGHTS[HEIGHTS.length - 1];

    public static final ArrowStyle DEFAULT = new ArrowStyle(2 * PIXEL, ArrowHead.NONE, ArrowHead.TRIANGLE,
            ArrowJoin.MITER, PIXEL / 2, PLAIN, true);

    public ArrowStyle {
        width = Mth.clamp(width, MIN_WIDTH, MAX_WIDTH);
        height = Mth.clamp(height, MIN_HEIGHT, MAX_HEIGHT);
        start = start == null ? ArrowHead.NONE : start;
        end = end == null ? ArrowHead.NONE : end;
        join = join == null ? ArrowJoin.MITER : join;
        colour = colour == OWN_COLOURS ? OWN_COLOURS : colour & 0xFFFFFF | 0xFF000000;
        headLength = headSize(headLength, MAX_HEAD_LENGTH);
        headWidth = headSize(headWidth, MAX_HEAD_WIDTH);
        headStroke = headSize(headStroke, MAX_HEAD_STROKE);
    }

    /** A style with its heads sized from the shaft width. */
    public ArrowStyle(double width, ArrowHead start, ArrowHead end, ArrowJoin join, double height, int colour,
            boolean shadedSides) {
        this(width, start, end, join, height, colour, shadedSides, AUTO, AUTO, AUTO);
    }

    private static int headSize(int units, int max) {
        return units <= AUTO ? AUTO : Mth.clamp(units, MIN_HEAD, max);
    }

    /** The head measurements as the outline takes them, in the same offset units. */
    public ArrowOutline.HeadSize headSize() {
        return new ArrowOutline.HeadSize(headLength, headWidth, headStroke);
    }

    /**
     * A colour typed as hex: {@code #RRGGBB}, {@code RRGGBB}, {@code #RGB} or {@code RGB},
     * as opaque ARGB; null for anything else, including half-typed text.
     */
    public static Integer parseHex(String text) {
        if (text == null)
            return null;
        String digits = text.trim();
        if (digits.startsWith("#"))
            digits = digits.substring(1);
        if (digits.length() == 3 && digits.matches("[0-9a-fA-F]{3}"))
            digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1)
                    + digits.charAt(2) + digits.charAt(2);
        if (!digits.matches("[0-9a-fA-F]{6}"))
            return null;
        return 0xFF000000 | Integer.parseInt(digits, 16);
    }

    /** A colour as the hex box shows it, six digits behind a hash. */
    public static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    /** The colour of the top of a part drawn in {@code own} when the arrow has no colour of its own. */
    public int topColour(int own) {
        return colour == OWN_COLOURS ? own | 0xFF000000 : colour;
    }

    /** The colour of the walls under a top of that colour. */
    public int sideColour(int top) {
        return shadedSides ? shade(top, 0.72) : top;
    }

    /** A colour darkened by a factor, alpha kept. */
    public static int shade(int argb, double factor) {
        int r = (int) Math.round((argb >> 16 & 0xFF) * factor);
        int g = (int) Math.round((argb >> 8 & 0xFF) * factor);
        int b = (int) Math.round((argb & 0xFF) * factor);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** The next entry of a cycling list after a value, wrapping; a value not in it cycles to the first. */
    public static int cycle(int[] options, int value) {
        for (int i = 0; i < options.length; i++)
            if (options[i] == value)
                return options[(i + 1) % options.length];
        return options[0];
    }

    /** The same for a list of fractional measurements. */
    public static double cycle(double[] options, double value) {
        for (int i = 0; i < options.length; i++)
            if (options[i] == value)
                return options[(i + 1) % options.length];
        return options[0];
    }

    public ArrowStyle withWidth(double width) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withHeads(ArrowHead start, ArrowHead end) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withJoin(ArrowJoin join) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withHeight(double height) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withColour(int colour) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withShadedSides(boolean shadedSides) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withHeadLength(int headLength) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withHeadWidth(int headWidth) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public ArrowStyle withHeadStroke(int headStroke) {
        return new ArrowStyle(width, start, end, join, height, colour, shadedSides, headLength, headWidth, headStroke);
    }

    public void save(CompoundTag tag) {
        tag.putDouble("Width", width);
        tag.putByte("Start", (byte) start.ordinal());
        tag.putByte("End", (byte) end.ordinal());
        tag.putByte("Join", (byte) join.ordinal());
        tag.putDouble("Height", height);
        tag.putInt("Colour", colour);
        tag.putBoolean("Shaded", shadedSides);
        tag.putInt("HeadLength", headLength);
        tag.putInt("HeadWidth", headWidth);
        tag.putInt("HeadStroke", headStroke);
    }

    /**
     * The default for anything missing, so an old or damaged tag still reads as something
     * drawable. A tag from before heads could be sized has no head keys, which read as
     * {@link #AUTO}: the heads it was drawn with. Width and height were whole offset
     * units before tenths of a pixel; a number tag of either kind reads as a double.
     */
    public static ArrowStyle load(CompoundTag tag) {
        if (!tag.contains("Width"))
            return DEFAULT;
        return new ArrowStyle(tag.getDouble("Width"), ArrowHead.byIndex(tag.getByte("Start")),
                ArrowHead.byIndex(tag.getByte("End")), ArrowJoin.byIndex(tag.getByte("Join")), tag.getDouble("Height"),
                tag.getInt("Colour"), tag.getBoolean("Shaded"), tag.getInt("HeadLength"), tag.getInt("HeadWidth"),
                tag.getInt("HeadStroke"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeDouble(width);
        buffer.writeByte(start.ordinal());
        buffer.writeByte(end.ordinal());
        buffer.writeByte(join.ordinal());
        buffer.writeDouble(height);
        buffer.writeInt(colour);
        buffer.writeBoolean(shadedSides);
        buffer.writeVarInt(headLength);
        buffer.writeVarInt(headWidth);
        buffer.writeVarInt(headStroke);
    }

    public static ArrowStyle read(FriendlyByteBuf buffer) {
        return new ArrowStyle(buffer.readDouble(), ArrowHead.byIndex(buffer.readByte()),
                ArrowHead.byIndex(buffer.readByte()), ArrowJoin.byIndex(buffer.readByte()), buffer.readDouble(),
                buffer.readInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }
}
