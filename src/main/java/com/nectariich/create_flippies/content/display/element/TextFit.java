package com.nectariich.create_flippies.content.display.element;

import java.util.List;

import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.label.PanelLabel;

/**
 * How large a text element prints and which lines of it are drawn, worked out against
 * its box with no idea how a font is actually measured.
 *
 * <h2>Why measuring is behind an interface</h2>
 *
 * Measuring needs the client's font machinery ({@code StringSplitter}, the design
 * fonts), which the server does not have and this package may not touch. The
 * arithmetic on top of the measurements is the part worth testing, and it is the same
 * on both sides, so it lives here and the client hands it a {@link Measurer}. The
 * server never lays text out; what it promises a display link is estimated from
 * {@link DisplayPrint#columns}'s nominal advance instead.
 *
 * <h2>The units</h2>
 *
 * A {@link Measurer} answers in font units, the space a glyph
 * {@link PanelLabel#GLYPH_HEIGHT} units tall is drawn in. A size is
 * {@link DisplayPrint}'s, tenths of a pixel per glyph height, and a box is in tenths
 * of a pixel, so at size {@code s} a glyph row is {@code s} tenths tall and a box
 * {@code b} tenths long holds {@code b * GLYPH_HEIGHT / s} font units. A TTF measurer
 * reports in the same units scaled so its cap height is the bitmap font's, which is
 * what makes one size number mean one visible height across fonts.
 *
 * The box is given as along and across the lines rather than as width and height, so
 * the caller has already swapped the two for a quarter turn, exactly as
 * {@code DisplayForm.usableAlong} does for the glass.
 */
public final class TextFit {

    /** Measures one font, at one weight and slant, in font units. */
    public interface Measurer {

        /** How wide that string draws. */
        double width(String text);

        /** The longest start of that string no wider than {@code maxWidth}. */
        String head(String text, double maxWidth);

        /** That string broken into lines no wider than {@code maxWidth}, as the font wraps. */
        List<String> split(String text, double maxWidth);

        /** From one line's top to the next one's, which is more than a glyph's height. */
        double lineHeight();
    }

    /** The size the lines print at, and the lines themselves, already cut or dropped to fit. */
    public record Layout(int size, List<String> lines) {
    }

    private static final double GLYPH = PanelLabel.GLYPH_HEIGHT;

    private TextFit() {}

    /**
     * The layout of {@code text} in a box {@code along} by {@code across} tenths.
     *
     * One line (the usual case, and every display that existed before elements): a
     * fitted element prints at the largest size that is no taller than the box and no
     * longer than it, and a fixed one at its size capped to the box's height. Either
     * way the line is then cut to the box's length, which only bites for a fixed size
     * or a line so long that even the smallest size overruns.
     *
     * Wrapped (only the workbench's preview text): a fitted element searches for the
     * largest size whose wrapped lines all fit; a fixed one wraps at its capped size.
     * Lines past the bottom of the box are dropped.
     */
    public static Layout layout(Measurer measurer, String text, int size, int along, int across, boolean wrap) {
        int tallest = Math.max(DisplayPrint.MIN_SIZE, Math.min(across, ScreenElement.MAX_SIZE));
        boolean fitted = size == ScreenElement.FIT;
        if (text.isEmpty())
            return new Layout(fitted ? tallest : clamp(size, tallest), List.of());
        if (!wrap) {
            int printed = fitted ? fitSingle(measurer.width(text), along, tallest) : clamp(size, tallest);
            return new Layout(printed, List.of(measurer.head(text, room(along, printed))));
        }
        int printed = fitted ? fitWrapped(measurer, text, along, across, tallest) : clamp(size, tallest);
        List<String> lines = measurer.split(text, room(along, printed));
        int shown = Math.min(lines.size(), linesThatFit(measurer.lineHeight(), across, printed));
        return new Layout(printed, List.copyOf(lines.subList(0, shown)));
    }

    /** Font units of line a box {@code along} tenths long holds at that size. */
    public static double room(int along, int size) {
        return along * GLYPH / Math.max(DisplayPrint.MIN_SIZE, size);
    }

    /** How many lines of that pitch fit {@code across} tenths at that size; at least one. */
    public static int linesThatFit(double lineHeight, int across, int size) {
        double units = across * GLYPH / Math.max(DisplayPrint.MIN_SIZE, size);
        return Math.max(1, 1 + (int) Math.floor((units - GLYPH) / Math.max(lineHeight, 1e-6)));
    }

    /** The largest size at which a line that wide still fits, and never taller than the box. */
    private static int fitSingle(double width, int along, int tallest) {
        if (width <= 0)
            return tallest;
        int longest = (int) (along * GLYPH / width);
        return Math.max(DisplayPrint.MIN_SIZE, Math.min(tallest, longest));
    }

    /**
     * The largest size whose wrapped lines all fit both ways, by bisection: a larger
     * size never holds more text, so whether it fits flips once on the way up. The
     * smallest size when not even that fits, and the overflow is then dropped.
     */
    private static int fitWrapped(Measurer measurer, String text, int along, int across, int tallest) {
        int low = DisplayPrint.MIN_SIZE, high = tallest;
        if (!wrappedFits(measurer, text, along, across, low))
            return low;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (wrappedFits(measurer, text, along, across, middle))
                low = middle;
            else
                high = middle - 1;
        }
        return low;
    }

    private static boolean wrappedFits(Measurer measurer, String text, int along, int across, int size) {
        double room = room(along, size);
        List<String> lines = measurer.split(text, room);
        for (String line : lines)
            if (measurer.width(line) > room)
                return false;
        double tall = ((lines.size() - 1) * measurer.lineHeight() + GLYPH) * size / GLYPH;
        return tall <= across;
    }

    private static int clamp(int size, int tallest) {
        return Math.max(DisplayPrint.MIN_SIZE, Math.min(size, tallest));
    }
}
