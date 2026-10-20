package com.nectariich.create_flippies.content.crt;

import java.util.function.IntBinaryOperator;

/**
 * The text mode font (spec 11.2.14): 256 glyphs of 8x16 pixels, one byte per
 * pixel row, bit 7 the leftmost pixel. Pure, so text rendering is tested with a
 * made-up font.
 *
 * The game's own font sheet ({@code minecraft:textures/font/ascii.png}) is laid out
 * in code page 437 order, the VGA's character set, so a text cell's character byte
 * is its glyph's index on the sheet as it is. Its 8x8 glyphs are doubled in height
 * to the VGA's 8x16 cell, which keeps box drawing characters joined.
 */
public final class CrtFont {

    public static final int GLYPHS = 256, WIDTH = 8, HEIGHT = 16;

    private final byte[] rows;

    private CrtFont(byte[] rows) {
        this.rows = rows;
    }

    /** A font with no ink: every cell shows its background. */
    public static CrtFont blank() {
        return new CrtFont(new byte[GLYPHS * HEIGHT]);
    }

    /**
     * Reads a sheet of 16 x 16 glyph cells ({@code sheetWidth} by {@code sheetHeight}
     * pixels; any cell size, sampled to 8x8), a pixel being ink where
     * {@code alpha.applyAsInt(x, y)} is over half (0..255). Each 8x8 row becomes two
     * rows of the 8x16 cell.
     */
    public static CrtFont fromSheet(int sheetWidth, int sheetHeight, IntBinaryOperator alpha) {
        if (sheetWidth < 16 || sheetHeight < 16)
            throw new IllegalArgumentException("font sheet " + sheetWidth + "x" + sheetHeight + " is too small");
        int cellW = sheetWidth / 16, cellH = sheetHeight / 16;
        byte[] rows = new byte[GLYPHS * HEIGHT];
        for (int glyph = 0; glyph < GLYPHS; glyph++) {
            int x0 = (glyph % 16) * cellW, y0 = (glyph / 16) * cellH;
            for (int y = 0; y < 8; y++) {
                int bits = 0;
                int sy = y0 + y * cellH / 8;
                for (int x = 0; x < 8; x++)
                    if (alpha.applyAsInt(x0 + x * cellW / 8, sy) > 127)
                        bits |= 0x80 >>> x;
                rows[glyph * HEIGHT + 2 * y] = (byte) bits;
                rows[glyph * HEIGHT + 2 * y + 1] = (byte) bits;
            }
        }
        return new CrtFont(rows);
    }

    /** Pixel row {@code row} (0..15) of {@code glyph}, bit 7 leftmost. */
    public int row(int glyph, int row) {
        return rows[(glyph & 0xFF) * HEIGHT + row] & 0xFF;
    }
}
