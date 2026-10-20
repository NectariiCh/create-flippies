package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import org.junit.jupiter.api.Test;

class CrtCanvasTest {

    private static void assertCanvas(CrtCanvas c, int w, int h, int scale, int x, int y) {
        assertEquals(w, c.width(), "width");
        assertEquals(h, c.height(), "height");
        assertEquals(scale, c.scale(), "scale");
        assertEquals(x, c.offsetX(), "offset x");
        assertEquals(y, c.offsetY(), "offset y");
        // Always the screen's 11:9
        assertEquals(0, c.width() % 11);
        assertEquals(c.width() / 11 * 9, c.height());
    }

    @Test
    void nominalIsExact() {
        assertCanvas(CrtCanvas.fit(440, 360), 440, 360, 1, 0, 0);
    }

    @Test
    void mode320x200FitsTheNominalCanvasBoxedAllRound() {
        // 1.375x across and 1.8x down would fit, but only whole scales are used: 1
        CrtCanvas c = CrtCanvas.fit(320, 200);
        assertCanvas(c, 440, 360, 1, 60, 80);
        assertEquals(320, c.imageWidth());
        assertEquals(200, c.imageHeight());
    }

    @Test
    void mode640x480GrowsTheCanvasInsteadOfShrinking() {
        // n = max(40, ceil(640 / 11) = 59, ceil(480 / 9) = 54) = 59: 649x531, pillar 4 + 5, letter 25 + 26
        assertCanvas(CrtCanvas.fit(640, 480), 649, 531, 1, 4, 25);
    }

    @Test
    void textModeAtItsRenderedSize() {
        // 80x25 cells of 8x16 is 640x400; n = max(40, 59, ceil(400 / 9) = 45) = 59
        CrtCanvas c = CrtCanvas.forMode(VgaDisplay.MODE_TEXT, 80, 25);
        assertCanvas(c, 649, 531, 1, 4, 65);
        assertEquals(640, c.imageWidth());
        assertEquals(400, c.imageHeight());
        assertEquals(CrtCanvas.fit(640, 480), CrtCanvas.forMode(VgaDisplay.MODE_640X480X8, 640, 480));
    }

    @Test
    void smallSourcesTakeTheLargestWholeScale() {
        assertCanvas(CrtCanvas.fit(160, 100), 440, 360, 2, 60, 80);
        assertCanvas(CrtCanvas.fit(220, 180), 440, 360, 2, 0, 0);
        assertCanvas(CrtCanvas.fit(40, 40), 440, 360, 9, 40, 0);
    }

    @Test
    void wideAndTallSourcesAreBoxed() {
        // Wider than 11:9: letterbox; taller: pillarbox. Never scaled down
        assertCanvas(CrtCanvas.fit(800, 200), 803, 657, 1, 1, 228);
        assertCanvas(CrtCanvas.fit(200, 600), 737, 603, 1, 268, 1);
    }

    @Test
    void emptySourceRefused() {
        assertThrows(IllegalArgumentException.class, () -> CrtCanvas.fit(0, 10));
    }
}
