package com.nectariich.create_flippies.content.display;

import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.content.label.PanelLabelFont;
import com.nectariich.create_flippies.content.label.PanelLabelPrint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * How one alphanumeric display sets whatever it is sent: which face, whether it is
 * bolded or slanted, how tall the characters are, which way round they read and what
 * colour they glow.
 *
 * <h2>Why the height is fixed by default and not fitted</h2>
 *
 * A panel label is written once and then read, so it defaults to shrinking its
 * lettering until the whole legend is on the strip — see {@link PanelLabelPrint#FIT},
 * which this keeps and offers. A display is the opposite case: it is driven by a
 * display link and what it shows changes while somebody is watching it, so a fitted
 * display would resize its own characters every time a reading gained or lost a
 * digit. That is unreadable in a way a static label never is. So a display ships at
 * a fixed height, {@link #defaultFor} picks one that suits the glass it is printed
 * on, and anything that does not fit at that height is cut.
 *
 * <h2>The cut</h2>
 *
 * Cutting rather than wrapping or shrinking is what a borderless display requires.
 * There is no bezel for an overrun to disappear under, so lettering that ran past the
 * glass would simply hang in the air beside the device, over whatever is mounted next
 * to it and pickable nowhere — and the device's footprint is the glass, so it would
 * be drawing outside the space it reserved. The height is capped to the glass for the
 * same reason, before the string is ever measured.
 *
 * <h2>The arithmetic</h2>
 *
 * The unit is {@link PanelLabelPrint}'s, a tenth of a pixel of cap height, and the
 * scale is literally that class's, so lettering of a given height is the same
 * lettering whether it is on a label, a cap or a display. What differs is only what
 * it is measured against: a label's strip is a rectangle the player drew and is
 * counted in half-pixel spans, while a display's glass is the model's and is in
 * authored pixels. Hence the conversion below is stated in pixels and
 * {@link PanelLabelPrint}'s span-based limits are not reused — it is the same
 * arithmetic against a different unit, and borrowing the span version would mean
 * pretending a model's glass had been drawn by somebody.
 */
public record DisplayPrint(PanelLabelFont font, boolean bold, boolean italic, int size, int quarters, int colour) {

    /** Shrink the characters until the whole line is on the glass; see the class comment. */
    public static final int FIT = PanelLabelPrint.FIT;

    /** Tenths of a pixel of cap height, so a size of 10 draws one pixel tall. */
    public static final int UNITS_PER_PIXEL = PanelLabelPrint.UNITS_PER_PIXEL;

    /** The smallest height that can be asked for, and the step between sizes. */
    public static final int MIN_SIZE = PanelLabelPrint.MIN_SIZE;

    /**
     * The most a stored height may be. Not the real limit — the glass is, and the
     * glass is applied every time the display is drawn — so this only stops a save or
     * a packet claiming a character the height of the world.
     */
    public static final int MAX_SIZE = PanelLabelPrint.MAX_SIZE;

    /** Quarter turns of the lettering on the glass; see {@link PanelLabelPrint#TURNS}. */
    public static final int TURNS = PanelLabelPrint.TURNS;

    /**
     * Size units of drawn width per font unit of measured width, which is the whole
     * of the width arithmetic here: text {@code n} font units wide printed at size
     * {@code s} covers {@code n * s / PER_FONT_UNIT} pixels.
     *
     * It follows from the unit rather than being chosen — a glyph is
     * {@link PanelLabel#GLYPH_HEIGHT} font units tall and prints one pixel of cap
     * height per {@link #UNITS_PER_PIXEL} — which is why it is derived here instead
     * of written down, and why it cannot drift from the scale the renderer uses.
     */
    private static final double PER_FONT_UNIT = UNITS_PER_PIXEL * (double) PanelLabel.GLYPH_HEIGHT;

    /**
     * What most of the default font's glyphs advance by: five columns of ink and one
     * of spacing. Used to pick a default height and to tell a display link how many
     * characters will fit, both of which are promises about a typical string rather
     * than about every string.
     */
    private static final int NOMINAL_ADVANCE = 6;

    /**
     * How many characters a display shows at its default height.
     *
     * Few, deliberately. These are readouts — a reading, a count, a state — and a
     * display set to show twenty characters of a line it will usually be sent six of
     * is a display nobody can read from across the room. The player can type any
     * height they like; this is only where it starts.
     */
    private static final int DEFAULT_COLUMNS = 5;

    /** White, because the glass behind it is very nearly black on every form. */
    public static final int DEFAULT_COLOUR = 0xFFFFFFFF;

    /**
     * What a display of this form ships with: the game's own lettering, upright, at a
     * height that puts about {@link #DEFAULT_COLUMNS} characters across the glass.
     *
     * Per form rather than one constant, because a bigger display should not start by
     * showing the same five characters in the same tiny type — the point of a bigger
     * window is a bigger line.
     */
    public static DisplayPrint defaultFor(DisplayForm form) {
        return new DisplayPrint(PanelLabelFont.FALLBACK, false, false, defaultSize(form), 0, DEFAULT_COLOUR);
    }

    /** Whether the characters are shrunk to fit rather than printed at a fixed height. */
    public boolean fitted() {
        return size == FIT;
    }

    /**
     * The tallest lettering that fits on this form's glass, in size units.
     *
     * A hard cap and not a preference: the glass is the display's footprint and
     * therefore its hitbox, and a character drawn taller than it would hang over
     * whatever is mounted alongside while being pickable nowhere. Measured across the
     * line, so a turn on a glass that is not square changes it.
     */
    public static int heightLimit(DisplayForm form, int quarters) {
        return Math.max(MIN_SIZE, (int) (UNITS_PER_PIXEL * form.usableAcross(quarters)));
    }

    /**
     * The height this display prints at on that form, before the line itself is taken
     * into account: the height that was asked for, cut down to the glass, or the whole
     * of the glass when fitting.
     */
    public int sizeOn(DisplayForm form) {
        int limit = heightLimit(form, quarters);
        return fitted() ? limit : Mth.clamp(size, MIN_SIZE, limit);
    }

    /**
     * The largest size at which a line that wide in font units still fits between the
     * edges of the glass. Zero for a line with no width, which is the caller's cue
     * that there is nothing to fit.
     */
    public static int widthLimit(DisplayForm form, int quarters, double textUnits) {
        if (textUnits <= 0)
            return 0;
        return (int) (form.usableAlong(quarters) * PER_FONT_UNIT / textUnits);
    }

    /**
     * How wide the lettering may be in font space at a given size, which is what a
     * line too long for the glass is cut to.
     */
    public static int textWidthUnits(DisplayForm form, int quarters, int size) {
        return (int) (form.usableAlong(quarters) * PER_FONT_UNIT / Math.max(MIN_SIZE, size));
    }

    /** Blocks per unit of font space; the label's own scale, for the reason above. */
    public static float scale(int size) {
        return PanelLabelPrint.scale(size);
    }

    /**
     * Characters a display link is told will fit.
     *
     * Measured at the height this display is actually set to, which — unlike a
     * label's, where a fitted strip cannot be measured before the string arrives — is
     * exactly right here, because a display's height does not depend on what it is
     * sent. A fitted one is the exception and reports what its glass affords at full
     * height, which is the fewest it will ever show.
     */
    public int columns(DisplayForm form) {
        return Math.max(1, textWidthUnits(form, quarters, sizeOn(form)) / NOMINAL_ADVANCE);
    }

    /**
     * The same estimate for a box given in tenths of a pixel rather than a form's
     * glass, already turned (along the line, then across it), which is how a screen
     * element states its room. The arithmetic is {@link #columns(DisplayForm)}'s
     * exactly: a form's usable glass in pixels times ten is a whole number of tenths
     * on every form, so an element over the whole viewport promises a link exactly
     * what the display did before it had elements.
     */
    public static int columns(int alongTenths, int acrossTenths, int size) {
        int limit = Math.max(MIN_SIZE, acrossTenths);
        int printed = size == FIT ? limit : Mth.clamp(size, MIN_SIZE, limit);
        return Math.max(1, alongTenths * PanelLabel.GLYPH_HEIGHT / Math.max(MIN_SIZE, printed) / NOMINAL_ADVANCE);
    }

    /** A size in tenths from the height in pixels the editor speaks in. */
    public static int sizeFromPixels(double pixels) {
        return Mth.clamp((int) Math.round(pixels * UNITS_PER_PIXEL), MIN_SIZE, MAX_SIZE);
    }

    /** The same the other way, for putting a stored height back into the editor. */
    public static double pixels(int size) {
        return size / (double) UNITS_PER_PIXEL;
    }

    public DisplayPrint withFont(PanelLabelFont font) {
        return new DisplayPrint(font, bold, italic, size, quarters, colour);
    }

    public DisplayPrint withBold(boolean bold) {
        return new DisplayPrint(font, bold, italic, size, quarters, colour);
    }

    public DisplayPrint withItalic(boolean italic) {
        return new DisplayPrint(font, bold, italic, size, quarters, colour);
    }

    public DisplayPrint withSize(int size) {
        return new DisplayPrint(font, bold, italic, sanitiseSize(size), quarters, colour);
    }

    /** The turn wraps rather than clamps, so one button can cycle the four. */
    public DisplayPrint withQuarters(int quarters) {
        return new DisplayPrint(font, bold, italic, size, Math.floorMod(quarters, TURNS), colour);
    }

    public DisplayPrint withColour(int colour) {
        return new DisplayPrint(font, bold, italic, size, quarters, PanelLabel.sanitiseColour(colour));
    }

    public void write(CompoundTag tag) {
        tag.putString("Font", font.id());
        tag.putBoolean("Bold", bold);
        tag.putBoolean("Italic", italic);
        tag.putInt("TextSize", size);
        tag.putByte("Quarters", (byte) quarters);
        tag.putInt("Colour", colour);
    }

    /**
     * What a tag describes, defaulting anything it does not carry to what a display
     * of that form ships with — which is the whole of it for a display saved before
     * any of this was stored.
     */
    public static DisplayPrint read(CompoundTag tag, DisplayForm form) {
        DisplayPrint fallback = defaultFor(form);
        if (!tag.contains("TextSize"))
            return fallback;
        return new DisplayPrint(PanelLabelFont.byId(tag.getString("Font")), tag.getBoolean("Bold"),
                tag.getBoolean("Italic"), sanitiseSize(tag.getInt("TextSize")),
                Math.floorMod(tag.getByte("Quarters"), TURNS),
                tag.contains("Colour") ? PanelLabel.sanitiseColour(tag.getInt("Colour")) : fallback.colour());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeByte(font.ordinal());
        buffer.writeBoolean(bold);
        buffer.writeBoolean(italic);
        buffer.writeVarInt(size);
        buffer.writeByte(quarters);
        buffer.writeInt(colour);
    }

    /** Guards every field, because all six arrive from a client that may say anything. */
    public static DisplayPrint decode(FriendlyByteBuf buffer) {
        return new DisplayPrint(PanelLabelFont.byOrdinal(buffer.readByte()), buffer.readBoolean(),
                buffer.readBoolean(), sanitiseSize(buffer.readVarInt()),
                Math.floorMod(buffer.readByte(), TURNS), PanelLabel.sanitiseColour(buffer.readInt()));
    }

    /** Fitting, or a height between a tenth of a pixel and the largest there can be. */
    private static int sanitiseSize(int size) {
        return size == FIT ? FIT : Mth.clamp(size, MIN_SIZE, MAX_SIZE);
    }

    /** The height that puts {@link #DEFAULT_COLUMNS} typical characters across the glass. */
    private static int defaultSize(DisplayForm form) {
        int wanted = (int) (form.usableAlong(0) * PER_FONT_UNIT / (DEFAULT_COLUMNS * (double) NOMINAL_ADVANCE));
        return Mth.clamp(wanted, MIN_SIZE, heightLimit(form, 0));
    }
}
