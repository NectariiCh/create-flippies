package com.nectariich.create_flippies.content.crt;

/**
 * The CRT's picture size for a source image (spec 11.2.14): always 11:9, the shape
 * of the screen, nominally 440x360 (the "360p equivalent"), and grown rather than
 * shrunk when the source does not fit.
 *
 * The rule:
 * 1. Scale: the largest whole number k >= 1 with k * source inside 440x360. A source
 *    larger than 440x360 in either direction stays at k = 1: it is never shrunk.
 * 2. Canvas: the smallest 11n x 9n with n >= 40 (so at least 440x360) that holds the
 *    scaled source.
 * 3. The source sits in the middle (rounded down to the top left), the rest is
 *    black: a letter or pillar box.
 *
 * The canvas's pixels are square, as the source's are, because both the canvas
 * and the screen are 11:9. Pure, so the rule is tested without a client.
 */
public record CrtCanvas(int width, int height, int scale, int offsetX, int offsetY, int imageWidth,
        int imageHeight) {

    /** The screen's shape: 11 px wide by 9 px tall in the model. */
    public static final int ASPECT_W = 11, ASPECT_H = 9;
    /** The nominal canvas, 11:9 at 360 lines. */
    public static final int NOMINAL_W = 440, NOMINAL_H = 360;
    /** A text cell, px: the font's 8x16 (spec 3.7). */
    public static final int CELL_W = 8, CELL_H = 16;

    public CrtCanvas {
        if (scale < 1 || imageWidth < 1 || imageHeight < 1 || offsetX < 0 || offsetY < 0
                || offsetX + imageWidth > width || offsetY + imageHeight > height)
            throw new IllegalArgumentException("canvas " + width + "x" + height + " does not hold the image");
    }

    /** The canvas for a source of {@code sourceWidth} x {@code sourceHeight} pixels. */
    public static CrtCanvas fit(int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1)
            throw new IllegalArgumentException("empty source " + sourceWidth + "x" + sourceHeight);
        int scale = Math.max(1, Math.min(NOMINAL_W / sourceWidth, NOMINAL_H / sourceHeight));
        int w = sourceWidth * scale, h = sourceHeight * scale;
        int n = Math.max(NOMINAL_W / ASPECT_W, Math.max(ceilDiv(w, ASPECT_W), ceilDiv(h, ASPECT_H)));
        int cw = ASPECT_W * n, ch = ASPECT_H * n;
        return new CrtCanvas(cw, ch, scale, (cw - w) / 2, (ch - h) / 2, w, h);
    }

    /**
     * The source's size in pixels for a display mode's geometry: text mode's cells
     * at the font's cell size (80x25 is 640x400), a graphics mode as it is.
     */
    public static CrtCanvas forMode(int mode, int width, int height) {
        return mode == CrtImage.MODE_TEXT ? fit(width * CELL_W, height * CELL_H) : fit(width, height);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
