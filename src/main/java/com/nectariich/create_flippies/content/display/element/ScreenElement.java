package com.nectariich.create_flippies.content.display.element;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.DisplayPrint;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * One thing drawn on a display: a run of lettering, a rectangle, an ellipse, a line,
 * an imported picture or a bar, in a box measured in tenths of a pixel from the top
 * left of the display's {@link Viewport}.
 *
 * <h2>What the record guarantees on its own</h2>
 *
 * Everything that can be checked without knowing the display: the id is a slot that
 * exists, the box has a size, the colour is opaque, the text is safe to draw, the font
 * is one that can be named, the size and turn are in range. Every element is built
 * through the compact constructor, so a value read off a save or a packet is already
 * all of that by the time anything holds it. Two things need more than the element
 * and are therefore {@link ScreenElements}'s job: fitting the box inside the viewport
 * ({@link #clampedTo}), and the text budget shared by every element of one display.
 *
 * Fields that mean nothing for the element's kind are pinned to their defaults here
 * rather than carried: a rectangle has no text, font or alignment, and only a frame,
 * an outlined ellipse and a line have a stroke. That keeps a save free of values
 * nobody can see or edit, and makes two elements that look the same compare equal,
 * which is what lets {@link ScreenElements#acceptLinked} skip a sync that would
 * change nothing. A fill's paint is the exception: its gradient stops and bands are
 * kept whatever type it is set to, so trying solid and going back loses nothing.
 *
 * <h2>Units</h2>
 *
 * Lengths are tenths of a pixel, stored as shorts. The text size is
 * {@link DisplayPrint}'s unit (tenths of a pixel of cap height per
 * {@link com.nectariich.create_flippies.content.label.PanelLabel#GLYPH_HEIGHT} font
 * units), so an element migrated from a display saved before elements existed prints
 * at exactly the height it did. {@link #FIT} (zero) means shrink to the box.
 *
 * <h2>Lines</h2>
 *
 * A line runs between two opposite corners of its box, which keeps every kind a box
 * that clamps, moves and selects the same way. {@link #flip} says which diagonal:
 * bit 0 starts it at the right edge, bit 1 at the bottom. A line's box may be no wider
 * or no taller than nothing, so a level or upright line is exactly that.
 *
 * <h2>Bars</h2>
 *
 * A bar holds text like lettering does, typed or written by a display link, and shows
 * the first number in it ({@link BarSettings#parse}) as a fill along its range.
 * {@link #value} is that number, kept when the text changes to something with no
 * number in it, so a source that briefly says "N/A" leaves the bar where it was; NaN
 * until a number has ever arrived. It is worked out here, from the text and the value
 * the element had, and never sent or typed.
 *
 * <h2>Z-order</h2>
 *
 * Not a field. Elements are drawn in the order {@link ScreenElements} holds them,
 * back to front, and moving one changes its position in that list and nothing about
 * the element itself, least of all its {@link #id}, which is what display links
 * address and must therefore never move.
 */
public record ScreenElement(int id, ElementKind kind, int x, int y, int w, int h, int colour, int highlight,
        int stroke, String text, String font, boolean bold, boolean italic, int size, int quarters, int hAlign,
        int vAlign, String name, boolean locked, int radius, boolean outline, int flip, int heads, int headStyle,
        int headLength, int headWidth, ElementPaint paint, String art, boolean tint, BarSettings bar, float value) {

    /** Slots per display; an id is always below this. */
    public static final int MAX_ID = 128;

    /** The most characters one element holds, typed or linked. */
    public static final int MAX_TEXT = 256;

    /** The longest name an element may be given. */
    public static final int MAX_NAME = 32;

    /** Shrink the lettering until it fits the box. */
    public static final int FIT = DisplayPrint.FIT;

    /** The tallest size a save or a packet may claim; the box caps it again at draw time. */
    public static final int MAX_SIZE = Math.min(DisplayPrint.MAX_SIZE, Short.MAX_VALUE);

    /** The thickest outline or line there may be, in tenths, which is what its byte key holds. */
    public static final int MAX_STROKE = Byte.MAX_VALUE;

    /** A frame's outline or a line when nobody has set one: one pixel. */
    public static final int DEFAULT_STROKE = Viewport.UNITS_PER_PIXEL;

    /** White, because every display's glass is very nearly black. */
    public static final int DEFAULT_COLOUR = 0xFFFFFFFF;

    /** A new bar's track, the part its fill has not reached: dark enough to read the fill against. */
    public static final int DEFAULT_TRACK = 0xFF303030;

    /** Alignment along either axis of the box: left or top, centre, right or bottom. */
    public static final int ALIGN_START = 0, ALIGN_CENTRE = 1, ALIGN_END = 2;

    /** A line's arrow heads: none, at its start, at its end, or both. */
    public static final int HEADS_NONE = 0, HEADS_START = 1, HEADS_END = 2, HEADS_BOTH = 3;

    /** An arrow head's shape: a filled triangle, or an open V of two strokes. */
    public static final int HEAD_TRIANGLE = 0, HEAD_OPEN = 1;

    /** A new arrow head's length and width, in tenths. */
    public static final int DEFAULT_HEAD_LENGTH = 40, DEFAULT_HEAD_WIDTH = 30;

    /** The longest and widest an arrow head may be, in tenths: twenty pixels. */
    public static final int MAX_HEAD = 200;

    /** {@link #flip} bits: the line starts at the box's right edge, at its bottom edge. */
    public static final int FLIP_X = 1, FLIP_Y = 2;

    // Bits of the field mask a menu edit carries, so an edit to one field never
    // overwrites what another player just set on a different one
    public static final int FIELD_X = 1, FIELD_Y = 1 << 1, FIELD_W = 1 << 2, FIELD_H = 1 << 3,
            FIELD_COLOUR = 1 << 4, FIELD_HIGHLIGHT = 1 << 5, FIELD_STROKE = 1 << 6, FIELD_TEXT = 1 << 7,
            FIELD_FONT = 1 << 8, FIELD_BOLD = 1 << 9, FIELD_ITALIC = 1 << 10, FIELD_SIZE = 1 << 11,
            FIELD_QUARTERS = 1 << 12, FIELD_H_ALIGN = 1 << 13, FIELD_V_ALIGN = 1 << 14, FIELD_NAME = 1 << 15,
            FIELD_LOCKED = 1 << 16, FIELD_RADIUS = 1 << 17, FIELD_OUTLINE = 1 << 18, FIELD_FLIP = 1 << 19,
            FIELD_ARROW = 1 << 20, FIELD_PAINT = 1 << 21, FIELD_ART = 1 << 22, FIELD_TINT = 1 << 23,
            FIELD_BAR = 1 << 24;

    /** How many field bits there are. */
    public static final int FIELD_COUNT = 25;

    /** Every field an edit can set. */
    public static final int ALL_FIELDS = (1 << FIELD_COUNT) - 1;

    /** The box: what a move or a resize sets. */
    public static final int BOX_FIELDS = FIELD_X | FIELD_Y | FIELD_W | FIELD_H;

    private static final int BOLD_FLAG = 1, ITALIC_FLAG = 2, LOCKED_FLAG = 4, OUTLINE_FLAG = 8, TINT_FLAG = 16;

    public ScreenElement {
        if (kind == null)
            throw new IllegalArgumentException("element kind");
        // A frame is a hollow rectangle, held as one so the same switch turns either
        // into the other; frames saved before then load this way too
        if (kind == ElementKind.FRAME) {
            kind = ElementKind.FILL;
            outline = true;
        }
        id = Mth.clamp(id, 0, MAX_ID - 1);
        x = Mth.clamp(x, 0, Short.MAX_VALUE);
        y = Mth.clamp(y, 0, Short.MAX_VALUE);
        // A line may be level or upright; everything else covers some area
        int least = kind == ElementKind.LINE ? 0 : 1;
        w = Mth.clamp(w, least, Short.MAX_VALUE);
        h = Mth.clamp(h, least, Short.MAX_VALUE);
        colour |= 0xFF000000;
        name = sanitiseName(name);

        if ((kind == ElementKind.FILL || kind == ElementKind.ELLIPSE) && outline) {
            // At most half the shorter side, past which the outline would cross itself
            // and the shape is a fill drawn the slow way
            int most = Math.max(1, Math.min(MAX_STROKE, (Math.min(w, h) + 1) / 2));
            stroke = Mth.clamp(stroke, 1, most);
        } else if (kind == ElementKind.LINE) {
            stroke = Mth.clamp(stroke, 1, MAX_STROKE);
        } else {
            stroke = DEFAULT_STROKE;
        }
        outline = (kind == ElementKind.FILL || kind == ElementKind.ELLIPSE) && outline;
        radius = kind == ElementKind.FILL ? Mth.clamp(radius, 0, Math.min(w, h) / 2) : 0;

        if (kind == ElementKind.LINE) {
            flip = flip & (FLIP_X | FLIP_Y);
            heads = Mth.clamp(heads, HEADS_NONE, HEADS_BOTH);
            headStyle = Mth.clamp(headStyle, HEAD_TRIANGLE, HEAD_OPEN);
            headLength = Mth.clamp(headLength, 1, MAX_HEAD);
            headWidth = Mth.clamp(headWidth, 1, MAX_HEAD);
        } else {
            flip = 0;
            heads = HEADS_NONE;
            headStyle = HEAD_TRIANGLE;
            headLength = DEFAULT_HEAD_LENGTH;
            headWidth = DEFAULT_HEAD_WIDTH;
        }

        if (paint == null)
            paint = ElementPaint.SOLID;
        if (kind == ElementKind.FILL || kind == ElementKind.ELLIPSE)
            paint = paint.forShape();
        else if (kind != ElementKind.BAR)
            paint = ElementPaint.SOLID;

        if (kind == ElementKind.SVG) {
            art = art != null && ControlDesign.isHash(art) ? art : "";
        } else {
            art = "";
            tint = false;
        }

        bar = kind == ElementKind.BAR ? bar == null ? BarSettings.DEFAULT : bar : BarSettings.DEFAULT;

        boolean lettering = kind == ElementKind.TEXT;
        if (kind.linked()) {
            highlight = (highlight >>> 24) == 0 ? 0 : highlight;
            text = sanitiseText(text);
        } else {
            highlight = 0;
            text = "";
        }
        quarters = kind.turns() ? Math.floorMod(quarters, DisplayPrint.TURNS) : 0;
        if (lettering) {
            font = font != null && DesignText.isValidFont(font) ? font : DesignText.DEFAULT_FONT;
            // A TTF family's weight is the face the font string already names
            bold = bold && !isTtf(font);
            size = size == FIT ? FIT : Mth.clamp(size, DisplayPrint.MIN_SIZE, MAX_SIZE);
            hAlign = Mth.clamp(hAlign, ALIGN_START, ALIGN_END);
            vAlign = Mth.clamp(vAlign, ALIGN_START, ALIGN_END);
        } else {
            font = DesignText.DEFAULT_FONT;
            bold = false;
            italic = false;
            size = FIT;
            hAlign = ALIGN_CENTRE;
            vAlign = ALIGN_CENTRE;
        }

        if (kind == ElementKind.BAR) {
            // The number the text holds now, or the one it last held
            float read = BarSettings.parse(text);
            value = Float.isNaN(read) ? Float.isFinite(value) ? value : Float.NaN : read;
        } else {
            value = Float.NaN;
        }
    }

    /** The element as every element used to be: the kinds and fields a display had before shapes and bars. */
    public ScreenElement(int id, ElementKind kind, int x, int y, int w, int h, int colour, int highlight, int stroke,
            String text, String font, boolean bold, boolean italic, int size, int quarters, int hAlign, int vAlign) {
        this(id, kind, x, y, w, h, colour, highlight, stroke, text, font, bold, italic, size, quarters, hAlign, vAlign,
                "", false, 0, false, 0, HEADS_NONE, HEAD_TRIANGLE, DEFAULT_HEAD_LENGTH, DEFAULT_HEAD_WIDTH,
                ElementPaint.SOLID, "", false, BarSettings.DEFAULT, Float.NaN);
    }

    /**
     * A new element of that kind in that slot: lettering over the whole viewport and
     * fitted to it, which is what a display has always shown, or a shape half the
     * viewport's size in its middle, so adding one does not black out everything
     * behind it. A bar starts empty on a dark track, a line runs left to right across
     * the middle.
     */
    public static ScreenElement create(int id, ElementKind kind, Viewport viewport) {
        int w = kind == ElementKind.TEXT ? viewport.width() : Math.max(1, viewport.width() / 2);
        int h = kind == ElementKind.TEXT ? viewport.height()
                : kind == ElementKind.LINE ? 0
                : kind == ElementKind.BAR ? Math.max(1, viewport.height() / 4) : Math.max(1, viewport.height() / 2);
        return create(id, kind, (viewport.width() - w) / 2, (viewport.height() - h) / 2, w, h);
    }

    /** A new element of that kind in that box, every other field at the default a new one has. */
    public static ScreenElement create(int id, ElementKind kind, int x, int y, int w, int h) {
        return new ScreenElement(id, kind, x, y, w, h, DEFAULT_COLOUR, kind == ElementKind.BAR ? DEFAULT_TRACK : 0,
                DEFAULT_STROKE, "", DesignText.DEFAULT_FONT, false, false, FIT, 0, ALIGN_CENTRE, ALIGN_CENTRE, "",
                false, 0, false, 0, HEADS_NONE, HEAD_TRIANGLE, DEFAULT_HEAD_LENGTH, DEFAULT_HEAD_WIDTH,
                ElementPaint.SOLID, "", false, BarSettings.DEFAULT, Float.NaN);
    }

    /**
     * Lettering as it is safe to store and to draw: vanilla's chat filter, which takes
     * out section signs and control characters (line breaks among them, so an element
     * is always one line), then cut to {@link #MAX_TEXT}. Its own cap rather than
     * {@link com.nectariich.create_flippies.content.label.PanelLabel#sanitise}, whose
     * 48 belongs to labels.
     */
    public static String sanitiseText(String text) {
        return cut(SharedConstants.filterText(text == null ? "" : text), MAX_TEXT);
    }

    /** A name as it is safe to keep: filtered as text is, trimmed, and cut to {@link #MAX_NAME}. */
    public static String sanitiseName(String name) {
        return cut(SharedConstants.filterText(name == null ? "" : name).strip(), MAX_NAME);
    }

    /**
     * The first {@code max} characters, never splitting a surrogate pair: a string cut
     * between the halves of one is not text at all.
     */
    public static String cut(String text, int max) {
        if (text.length() <= max)
            return text;
        if (max <= 0)
            return "";
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    public static boolean isTtf(String font) {
        return font.startsWith(DesignText.TTF_PREFIX);
    }

    /** The stored-file hash a TTF font names, or null for a built-in one. */
    public static String ttfHash(String font) {
        return isTtf(font) ? font.substring(DesignText.TTF_PREFIX.length()) : null;
    }

    public boolean fitted() {
        return size == FIT;
    }

    /** How far the box runs along a line of its lettering, in tenths: a quarter turn swaps the sides. */
    public int along() {
        return quarters % 2 == 0 ? w : h;
    }

    /** How far the box runs across a line of its lettering, the cap on its height. */
    public int across() {
        return quarters % 2 == 0 ? h : w;
    }

    /**
     * Characters a display link is told this element will show: the server's estimate
     * from a typical glyph's advance, since the server never measures a font. See
     * {@link DisplayPrint#columns(int, int, int)}. A bar reads one number, which a
     * dozen characters always hold with room for a unit.
     */
    public int columns() {
        return kind == ElementKind.BAR ? 12 : DisplayPrint.columns(along(), across(), size);
    }

    /** A line's start, in tenths: {x, y}. */
    public int[] lineStart() {
        return new int[] { (flip & FLIP_X) != 0 ? x + w : x, (flip & FLIP_Y) != 0 ? y + h : y };
    }

    /** A line's end, in tenths: {x, y}. */
    public int[] lineEnd() {
        return new int[] { (flip & FLIP_X) != 0 ? x : x + w, (flip & FLIP_Y) != 0 ? y : y + h };
    }

    /**
     * The fields that make a line run from one point to another, in tenths: its box
     * and which diagonal of it. Not yet clamped to any viewport.
     */
    public ScreenElement withEnds(int x1, int y1, int x2, int y2) {
        return toBuilder().box(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1))
                .flip((x1 > x2 ? FLIP_X : 0) | (y1 > y2 ? FLIP_Y : 0)).build();
    }

    /**
     * This element with its box moved and sized to lie inside the viewport.
     *
     * The size is cut first and the position second, so a box that has been pushed
     * past an edge slides back rather than shrinking (moving something into a corner
     * should not change its size), and only a box larger than the viewport loses any.
     */
    public ScreenElement clampedTo(Viewport viewport) {
        int nw = Math.min(w, viewport.width());
        int nh = Math.min(h, viewport.height());
        int nx = Mth.clamp(x, 0, viewport.width() - nw);
        int ny = Mth.clamp(y, 0, viewport.height() - nh);
        return nx == x && ny == y && nw == w && nh == h ? this : withBox(nx, ny, nw, nh);
    }

    /**
     * The values an edit carries, exactly as sent and not yet sanitised. Not a
     * {@link ScreenElement}, because the element constructor sanitises against the
     * element's own kind and box: a carrier of the wrong kind would reset the very
     * fields it was carrying (a text carrier pins the stroke to its default, a fill
     * carrier blanks the text), and a frame carrier would clamp the stroke to its own
     * placeholder box rather than the target's. An edit packet names only an id and a
     * mask, so whoever decodes it knows neither. These are sanitised exactly once, by
     * {@link #merged}, against the element they land on.
     */
    public record Fields(int x, int y, int w, int h, int colour, int highlight, int stroke, String text,
            String font, boolean bold, boolean italic, int size, int quarters, int hAlign, int vAlign, String name,
            boolean locked, int radius, boolean outline, int flip, int heads, int headStyle, int headLength,
            int headWidth, ElementPaint paint, String art, boolean tint, BarSettings bar) {

        /** The fields every element had before shapes and bars, the rest at their defaults. */
        public Fields(int x, int y, int w, int h, int colour, int highlight, int stroke, String text, String font,
                boolean bold, boolean italic, int size, int quarters, int hAlign, int vAlign) {
            this(x, y, w, h, colour, highlight, stroke, text, font, bold, italic, size, quarters, hAlign, vAlign, "",
                    false, 0, false, 0, HEADS_NONE, HEAD_TRIANGLE, DEFAULT_HEAD_LENGTH, DEFAULT_HEAD_WIDTH,
                    ElementPaint.SOLID, "", false, BarSettings.DEFAULT);
        }
    }

    /** This element's values as an edit would carry them, for setting several at once. */
    public Fields fields() {
        return new Fields(x, y, w, h, colour, highlight, stroke, text, font, bold, italic, size, quarters, hAlign,
                vAlign, name, locked, radius, outline, flip, heads, headStyle, headLength, headWidth, paint, art,
                tint, bar);
    }

    /**
     * This element with the fields named in {@code mask} taken from {@code values},
     * sanitised against this element's kind and its box after the edit (so a stroke
     * sent with a new size is held to the new size, not the old one). The id and kind
     * are never taken: an edit says which element by id, and a kind cannot be
     * changed, only replaced by deleting one element and adding another.
     */
    public ScreenElement merged(Fields values, int mask) {
        return new ScreenElement(id, kind,
                (mask & FIELD_X) != 0 ? values.x() : x,
                (mask & FIELD_Y) != 0 ? values.y() : y,
                (mask & FIELD_W) != 0 ? values.w() : w,
                (mask & FIELD_H) != 0 ? values.h() : h,
                (mask & FIELD_COLOUR) != 0 ? values.colour() : colour,
                (mask & FIELD_HIGHLIGHT) != 0 ? values.highlight() : highlight,
                (mask & FIELD_STROKE) != 0 ? values.stroke() : stroke,
                (mask & FIELD_TEXT) != 0 ? values.text() : text,
                (mask & FIELD_FONT) != 0 ? values.font() : font,
                (mask & FIELD_BOLD) != 0 ? values.bold() : bold,
                (mask & FIELD_ITALIC) != 0 ? values.italic() : italic,
                (mask & FIELD_SIZE) != 0 ? values.size() : size,
                (mask & FIELD_QUARTERS) != 0 ? values.quarters() : quarters,
                (mask & FIELD_H_ALIGN) != 0 ? values.hAlign() : hAlign,
                (mask & FIELD_V_ALIGN) != 0 ? values.vAlign() : vAlign,
                (mask & FIELD_NAME) != 0 ? values.name() : name,
                (mask & FIELD_LOCKED) != 0 ? values.locked() : locked,
                (mask & FIELD_RADIUS) != 0 ? values.radius() : radius,
                (mask & FIELD_OUTLINE) != 0 ? values.outline() : outline,
                (mask & FIELD_FLIP) != 0 ? values.flip() : flip,
                (mask & FIELD_ARROW) != 0 ? values.heads() : heads,
                (mask & FIELD_ARROW) != 0 ? values.headStyle() : headStyle,
                (mask & FIELD_ARROW) != 0 ? values.headLength() : headLength,
                (mask & FIELD_ARROW) != 0 ? values.headWidth() : headWidth,
                (mask & FIELD_PAINT) != 0 ? values.paint() : paint,
                (mask & FIELD_ART) != 0 ? values.art() : art,
                (mask & FIELD_TINT) != 0 ? values.tint() : tint,
                (mask & FIELD_BAR) != 0 ? values.bar() : bar,
                value);
    }

    /** The bits of the fields on which this element and {@code other} differ. */
    public int difference(ScreenElement other) {
        int mask = 0;
        if (x != other.x)
            mask |= FIELD_X;
        if (y != other.y)
            mask |= FIELD_Y;
        if (w != other.w)
            mask |= FIELD_W;
        if (h != other.h)
            mask |= FIELD_H;
        if (colour != other.colour)
            mask |= FIELD_COLOUR;
        if (highlight != other.highlight)
            mask |= FIELD_HIGHLIGHT;
        if (stroke != other.stroke)
            mask |= FIELD_STROKE;
        if (!text.equals(other.text))
            mask |= FIELD_TEXT;
        if (!font.equals(other.font))
            mask |= FIELD_FONT;
        if (bold != other.bold)
            mask |= FIELD_BOLD;
        if (italic != other.italic)
            mask |= FIELD_ITALIC;
        if (size != other.size)
            mask |= FIELD_SIZE;
        if (quarters != other.quarters)
            mask |= FIELD_QUARTERS;
        if (hAlign != other.hAlign)
            mask |= FIELD_H_ALIGN;
        if (vAlign != other.vAlign)
            mask |= FIELD_V_ALIGN;
        if (!name.equals(other.name))
            mask |= FIELD_NAME;
        if (locked != other.locked)
            mask |= FIELD_LOCKED;
        if (radius != other.radius)
            mask |= FIELD_RADIUS;
        if (outline != other.outline)
            mask |= FIELD_OUTLINE;
        if (flip != other.flip)
            mask |= FIELD_FLIP;
        if (heads != other.heads || headStyle != other.headStyle || headLength != other.headLength
                || headWidth != other.headWidth)
            mask |= FIELD_ARROW;
        if (!paint.equals(other.paint))
            mask |= FIELD_PAINT;
        if (!art.equals(other.art))
            mask |= FIELD_ART;
        if (tint != other.tint)
            mask |= FIELD_TINT;
        if (!bar.equals(other.bar))
            mask |= FIELD_BAR;
        return mask;
    }

    public ScreenElement withId(int id) {
        return toBuilder().id(id).build();
    }

    public ScreenElement withBox(int x, int y, int w, int h) {
        return toBuilder().box(x, y, w, h).build();
    }

    public ScreenElement withText(String text) {
        return toBuilder().text(text).build();
    }

    public ScreenElement withFont(String font) {
        return toBuilder().font(font).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * A copy of an element with some of its fields changed, built back into an element
     * (and so sanitised) at the end. Every field not set keeps the element's value.
     */
    public static final class Builder {
        private int id, x, y, w, h, colour, highlight, stroke, size, quarters, hAlign, vAlign, radius, flip, heads,
                headStyle, headLength, headWidth;
        private final ElementKind kind;
        private String text, font, name, art;
        private boolean bold, italic, locked, outline, tint;
        private ElementPaint paint;
        private BarSettings bar;
        private float value;

        private Builder(ScreenElement e) {
            id = e.id;
            kind = e.kind;
            x = e.x;
            y = e.y;
            w = e.w;
            h = e.h;
            colour = e.colour;
            highlight = e.highlight;
            stroke = e.stroke;
            text = e.text;
            font = e.font;
            bold = e.bold;
            italic = e.italic;
            size = e.size;
            quarters = e.quarters;
            hAlign = e.hAlign;
            vAlign = e.vAlign;
            name = e.name;
            locked = e.locked;
            radius = e.radius;
            outline = e.outline;
            flip = e.flip;
            heads = e.heads;
            headStyle = e.headStyle;
            headLength = e.headLength;
            headWidth = e.headWidth;
            paint = e.paint;
            art = e.art;
            tint = e.tint;
            bar = e.bar;
            value = e.value;
        }

        public Builder id(int id) {
            this.id = id;
            return this;
        }

        public Builder box(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        public Builder colour(int colour) {
            this.colour = colour;
            return this;
        }

        public Builder highlight(int highlight) {
            this.highlight = highlight;
            return this;
        }

        public Builder stroke(int stroke) {
            this.stroke = stroke;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder font(String font) {
            this.font = font;
            return this;
        }

        public Builder quarters(int quarters) {
            this.quarters = quarters;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder locked(boolean locked) {
            this.locked = locked;
            return this;
        }

        public Builder outline(boolean outline) {
            this.outline = outline;
            return this;
        }

        public Builder flip(int flip) {
            this.flip = flip;
            return this;
        }

        public Builder paint(ElementPaint paint) {
            this.paint = paint;
            return this;
        }

        public Builder art(String art) {
            this.art = art;
            return this;
        }

        public Builder value(float value) {
            this.value = value;
            return this;
        }

        public ScreenElement build() {
            return new ScreenElement(id, kind, x, y, w, h, colour, highlight, stroke, text, font, bold, italic, size,
                    quarters, hAlign, vAlign, name, locked, radius, outline, flip, heads, headStyle, headLength,
                    headWidth, paint, art, tint, bar, value);
        }
    }

    /**
     * The element as a save holds it: every key whose value is the default is left
     * out, since a dense panel holds a hundred of these per display and most of them
     * are mostly defaults. Width and height are the exception and always written,
     * because their default is the viewport's and that is not the element's to know.
     */
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("I", (byte) id);
        tag.putByte("K", (byte) kind.ordinal());
        if (x != 0)
            tag.putShort("X", (short) x);
        if (y != 0)
            tag.putShort("Y", (short) y);
        tag.putShort("W", (short) w);
        tag.putShort("H", (short) h);
        if (colour != DEFAULT_COLOUR)
            tag.putInt("C", colour);
        if (highlight != 0)
            tag.putInt("B", highlight);
        if (stroke != DEFAULT_STROKE)
            tag.putByte("Sw", (byte) stroke);
        if (!name.isEmpty())
            tag.putString("N", name);
        if (radius != 0)
            tag.putShort("Rr", (short) radius);
        if (kind == ElementKind.LINE) {
            if (flip != 0)
                tag.putByte("Lf", (byte) flip);
            if (heads != HEADS_NONE)
                tag.putByte("Lh", (byte) heads);
            if (headStyle != HEAD_TRIANGLE)
                tag.putByte("Ls", (byte) headStyle);
            if (headLength != DEFAULT_HEAD_LENGTH)
                tag.putShort("Ll", (short) headLength);
            if (headWidth != DEFAULT_HEAD_WIDTH)
                tag.putShort("Lw", (short) headWidth);
        }
        if (!paint.isDefault())
            tag.put("P", paint.write());
        if (!art.isEmpty())
            tag.putString("Art", art);
        if (kind == ElementKind.BAR) {
            CompoundTag settings = bar.write();
            if (!settings.isEmpty())
                tag.put("Bar", settings);
            if (Float.isFinite(value))
                tag.putFloat("V", value);
        }
        if (quarters != 0)
            tag.putByte("Q", (byte) quarters);
        int flags = (bold ? BOLD_FLAG : 0) | (italic ? ITALIC_FLAG : 0) | (locked ? LOCKED_FLAG : 0)
                | (outline ? OUTLINE_FLAG : 0) | (tint ? TINT_FLAG : 0);
        if (flags != 0)
            tag.putByte("Fl", (byte) flags);
        if (!text.isEmpty())
            tag.putString("T", text);
        if (kind != ElementKind.TEXT)
            return tag;
        if (!font.equals(DesignText.DEFAULT_FONT))
            tag.putString("F", font);
        if (size != FIT)
            tag.putShort("S", (short) size);
        if (hAlign != ALIGN_CENTRE)
            tag.putByte("Ah", (byte) hAlign);
        if (vAlign != ALIGN_CENTRE)
            tag.putByte("Av", (byte) vAlign);
        return tag;
    }

    /**
     * What a saved entry describes, or null when it cannot be one: an id outside the
     * slots or a kind this build does not know. A missing key is that key's default,
     * and a missing width or height is the whole viewport. Everything else is
     * sanitised by the constructor, so a hand-edited or hostile tag gives a drawable
     * element. The box is not yet fitted to the viewport; see {@link #clampedTo}.
     */
    public static ScreenElement read(CompoundTag tag, Viewport viewport) {
        int id = tag.getByte("I");
        ElementKind kind = ElementKind.byOrdinal(tag.getByte("K"));
        if (id < 0 || id >= MAX_ID || kind == null)
            return null;
        int flags = tag.getByte("Fl");
        return new ScreenElement(id, kind, tag.getShort("X"), tag.getShort("Y"),
                tag.contains("W") ? tag.getShort("W") : viewport.width(),
                tag.contains("H") ? tag.getShort("H") : viewport.height(),
                tag.contains("C") ? tag.getInt("C") : DEFAULT_COLOUR, tag.getInt("B"),
                tag.contains("Sw") ? tag.getByte("Sw") : DEFAULT_STROKE, tag.getString("T"),
                tag.contains("F") ? tag.getString("F") : DesignText.DEFAULT_FONT, (flags & BOLD_FLAG) != 0,
                (flags & ITALIC_FLAG) != 0, tag.getShort("S"), tag.getByte("Q"),
                tag.contains("Ah") ? tag.getByte("Ah") : ALIGN_CENTRE,
                tag.contains("Av") ? tag.getByte("Av") : ALIGN_CENTRE, tag.getString("N"), (flags & LOCKED_FLAG) != 0,
                tag.getShort("Rr"), (flags & OUTLINE_FLAG) != 0, tag.getByte("Lf"), tag.getByte("Lh"),
                tag.getByte("Ls"), tag.contains("Ll") ? tag.getShort("Ll") : DEFAULT_HEAD_LENGTH,
                tag.contains("Lw") ? tag.getShort("Lw") : DEFAULT_HEAD_WIDTH, ElementPaint.read(tag.getCompound("P")),
                tag.getString("Art"), (flags & TINT_FLAG) != 0, BarSettings.read(tag.getCompound("Bar")),
                tag.contains("V") ? tag.getFloat("V") : Float.NaN);
    }
}
