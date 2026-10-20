package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import org.junit.jupiter.api.Test;

class CrtRasterTest {

    private static final int BLACK = 0xFF000000;

    private static CrtImage image(CrtFrameCodec.Header h, byte[] frame) {
        BitSet all = new BitSet();
        all.set(0, h.height());
        CrtImage image = new CrtImage();
        image.apply(CrtFrameCodec.decode(CrtFrameCodec.encode(h, true, null, frame, all)));
        return image;
    }

    private static int colour(int index) {
        return BLACK | VgaDisplay.defaultPaletteEntry(index);
    }

    /** A font whose glyph 'A' (0x41) has its left column inked, others empty. */
    private static CrtFont font() {
        return CrtFont.fromSheet(128, 128, (x, y) -> x == 1 * 8 && y >= 4 * 8 && y < 5 * 8 ? 255 : 0);
    }

    @Test
    void fontSheetCellsBecomeDoubledRows() {
        CrtFont f = font();
        for (int row = 0; row < 16; row++)
            assertEquals(0x80, f.row(0x41, row));
        assertEquals(0, f.row(0x42, 0));
    }

    @Test
    void textCellForegroundBackgroundAndCursor() {
        CrtFrameCodec.Header h = new CrtFrameCodec.Header(0, 0, 80, 25, 160, 16, 1, 0,
                VgaDisplay.CTRL_DISPLAY | VgaDisplay.CTRL_CURSOR | VgaDisplay.CTRL_BLINK);
        byte[] frame = new byte[4000];
        frame[0] = 0x41;
        frame[1] = 0x1E; // yellow on blue
        frame[2] = 0x20;
        frame[3] = 0x07;
        CrtImage img = image(h, frame);
        int[] row = new int[640];
        CrtRaster.row(img, font(), 0, true, true, row);
        assertEquals(colour(14), row[0]);
        assertEquals(colour(1), row[1]);
        assertEquals(colour(0), row[8]);
        // The cursor is the bottom two rows of its cell, in the cell's foreground
        CrtRaster.row(img, font(), 15, true, true, row);
        for (int x = 8; x < 16; x++)
            assertEquals(colour(7), row[x]);
        CrtRaster.row(img, font(), 15, true, false, row);
        assertEquals(colour(0), row[8]);
    }

    @Test
    void blinkingCharactersShowTheirBackgroundInTheOffPhase() {
        CrtFrameCodec.Header h = new CrtFrameCodec.Header(0, 0, 80, 25, 160, 16, 0, 0,
                VgaDisplay.CTRL_DISPLAY | VgaDisplay.CTRL_BLINK);
        byte[] frame = new byte[4000];
        frame[0] = 0x41;
        frame[1] = (byte) 0x9F; // blink, blue background, white
        CrtImage img = image(h, frame);
        int[] row = new int[640];
        CrtRaster.row(img, font(), 3, true, true, row);
        assertEquals(colour(15), row[0]);
        CrtRaster.row(img, font(), 3, false, true, row);
        assertEquals(colour(1), row[0]);

        // Without blink mode bit 7 is a bright background: 0x9 is light blue
        CrtFrameCodec.Header bright = new CrtFrameCodec.Header(0, 0, 80, 25, 160, 16, 0, 0, VgaDisplay.CTRL_DISPLAY);
        CrtRaster.row(image(bright, frame), font(), 3, false, true, row);
        assertEquals(colour(15), row[0]);
        assertEquals(colour(9), row[1]);
    }

    @Test
    void fourBitPixelsHighNibbleFirst() {
        CrtFrameCodec.Header h = new CrtFrameCodec.Header(0, 2, 640, 480, 320, 4, 0, 0, VgaDisplay.CTRL_DISPLAY);
        byte[] frame = new byte[320 * 480];
        frame[320 * 2] = 0x4C;
        int[] row = new int[640];
        CrtRaster.row(image(h, frame), CrtFont.blank(), 2, true, true, row);
        assertEquals(colour(4), row[0]);
        assertEquals(colour(12), row[1]);
        assertEquals(colour(0), row[2]);
    }

    @Test
    void displayOffIsBlack() {
        CrtFrameCodec.Header h = new CrtFrameCodec.Header(0, 1, 320, 200, 320, 8, 0, 0, 0);
        byte[] frame = new byte[320 * 200];
        java.util.Arrays.fill(frame, (byte) 15);
        int[] row = new int[320];
        CrtRaster.row(image(h, frame), CrtFont.blank(), 0, true, true, row);
        for (int c : row)
            assertEquals(BLACK, c);
    }
}
