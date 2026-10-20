package com.nectariich.create_flippies.content.crt;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

/**
 * Turns a {@link CrtImage} into pixels (spec 11.2.14), one source pixel row at a
 * time, as 0xAARRGGBB. Pure; the client copies the rows into its texture.
 *
 * Text mode: 8x16 cells from {@link CrtFont}; attribute bits 3..0 the foreground,
 * bits 6..4 the background, bit 7 blink (CTRL bit 3) or a bright background. A
 * blinking character shows its background in the off phase. The cursor, when CTRL
 * enables it, is the cell's bottom two rows in the foreground colour, blinking.
 * Colours go through palette entries 0..15. Graphics modes: 8 bpp through the
 * palette, 4 bpp with the high nibble the left pixel. Display off (CTRL bit 0
 * clear): black.
 */
public final class CrtRaster {

    public static final int BLACK = 0xFF000000;
    /** The cursor's rows in a cell. */
    public static final int CURSOR_FIRST_ROW = 14;

    private CrtRaster() {}

    /** Source pixel rows for the image's mode: text rows times the cell height, or the height. */
    public static int pixelRows(CrtFrameCodec.Header h) {
        return h.mode() == CrtImage.MODE_TEXT ? h.height() * CrtFont.HEIGHT : h.height();
    }

    /** Source pixels per row. */
    public static int pixelColumns(CrtFrameCodec.Header h) {
        return h.mode() == CrtImage.MODE_TEXT ? h.width() * CrtFont.WIDTH : h.width();
    }

    /** The first and one past the last source pixel row a display row (a text row in text mode) covers. */
    public static int firstPixelRow(CrtFrameCodec.Header h, int displayRow) {
        return h.mode() == CrtImage.MODE_TEXT ? displayRow * CrtFont.HEIGHT : displayRow;
    }

    public static int endPixelRow(CrtFrameCodec.Header h, int displayRow) {
        return h.mode() == CrtImage.MODE_TEXT ? (displayRow + 1) * CrtFont.HEIGHT : displayRow + 1;
    }

    /**
     * Writes source pixel row {@code y} into {@code out} (at least {@link #pixelColumns}
     * long). {@code blinkOn} and {@code cursorOn} are the two blink phases.
     */
    public static void row(CrtImage image, CrtFont font, int y, boolean blinkOn, boolean cursorOn, int[] out) {
        CrtFrameCodec.Header h = image.header();
        int columns = pixelColumns(h);
        if ((h.ctrl() & VgaDisplay.CTRL_DISPLAY) == 0) {
            java.util.Arrays.fill(out, 0, columns, BLACK);
            return;
        }
        int base = y * h.pitch();
        switch (h.bitsPerPixel()) {
            case 8 -> {
                for (int x = 0; x < columns; x++)
                    out[x] = BLACK | image.paletteEntry(image.byteAt(base + x));
            }
            case 4 -> {
                for (int x = 0; x < columns; x++) {
                    int b = image.byteAt(base + (x >> 1));
                    out[x] = BLACK | image.paletteEntry((x & 1) == 0 ? b >>> 4 : b & 0xF);
                }
            }
            default -> text(image, font, y, blinkOn, cursorOn, out);
        }
    }

    private static void text(CrtImage image, CrtFont font, int y, boolean blinkOn, boolean cursorOn, int[] out) {
        CrtFrameCodec.Header h = image.header();
        int cellRow = y / CrtFont.HEIGHT, glyphRow = y % CrtFont.HEIGHT;
        boolean blinkMode = (h.ctrl() & VgaDisplay.CTRL_BLINK) != 0;
        boolean cursorHere = cursorOn && (h.ctrl() & VgaDisplay.CTRL_CURSOR) != 0 && h.cursorRow() == cellRow
                && glyphRow >= CURSOR_FIRST_ROW;
        int base = cellRow * h.pitch();
        for (int col = 0; col < h.width(); col++) {
            int ch = image.byteAt(base + col * 2), attr = image.byteAt(base + col * 2 + 1);
            int fg = BLACK | image.paletteEntry(attr & 0xF);
            int bg = BLACK | image.paletteEntry(blinkMode ? (attr >>> 4) & 7 : attr >>> 4);
            int bits = blinkMode && (attr & 0x80) != 0 && !blinkOn ? 0 : font.row(ch, glyphRow);
            if (cursorHere && col == h.cursorColumn())
                bits = 0xFF;
            int at = col * CrtFont.WIDTH;
            for (int x = 0; x < CrtFont.WIDTH; x++)
                out[at + x] = (bits & (0x80 >>> x)) != 0 ? fg : bg;
        }
    }
}
