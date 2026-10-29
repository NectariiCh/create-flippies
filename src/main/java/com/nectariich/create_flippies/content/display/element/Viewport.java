package com.nectariich.create_flippies.content.display.element;

import com.nectariich.create_flippies.content.display.DisplayForm;

/**
 * The rectangle a display draws its elements in, in tenths of a pixel, origin at the
 * top left, +x right and +y down.
 *
 * Tenths because that is what an element's box is stored in (as shorts, see
 * {@link ScreenElement}), so clamping a box to its viewport is integer arithmetic with
 * no rounding to disagree about between server and client. Both sides are capped to
 * what a short holds (3276 pixels, far past anything a panel can carry) and kept
 * at least one tenth, so a box always has somewhere to go.
 */
public record Viewport(int width, int height) {

    /** Tenths of a pixel per pixel, the unit of every length an element stores. */
    public static final int UNITS_PER_PIXEL = 10;

    public Viewport {
        width = clampSide(width);
        height = clampSide(height);
    }

    /** A viewport of that many pixels across and down, rounded to the nearest tenth. */
    public static Viewport ofPixels(double width, double height) {
        return new Viewport(toUnits(width), toUnits(height));
    }

    /**
     * An alphanumeric display's usable glass, in the frame its lettering prints in at
     * no turn: along the glass's x, then along its z.
     */
    public static Viewport of(DisplayForm form) {
        return ofPixels(form.usableAlong(0), form.usableAlong(1));
    }

    public double widthPixels() {
        return width / (double) UNITS_PER_PIXEL;
    }

    public double heightPixels() {
        return height / (double) UNITS_PER_PIXEL;
    }

    private static int toUnits(double pixels) {
        return Double.isFinite(pixels) ? (int) Math.round(Math.min(pixels, Short.MAX_VALUE) * UNITS_PER_PIXEL) : 1;
    }

    private static int clampSide(int side) {
        return Math.max(1, Math.min(side, Short.MAX_VALUE));
    }
}
