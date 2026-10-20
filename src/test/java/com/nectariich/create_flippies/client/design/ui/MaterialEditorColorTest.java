package com.nectariich.create_flippies.client.design.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The material editor's colour conversions: hex in and out, and HSV both ways. */
class MaterialEditorColorTest {

    @Test
    void hexRoundTrips() {
        assertEquals("#0A1B2C", MaterialEditor.hexOf(0x0A1B2C));
        assertEquals(0x0A1B2C, MaterialEditor.parseHex("#0a1b2c"));
        assertEquals(0xFFFFFF, MaterialEditor.parseHex(" FFFFFF "));
        assertNull(MaterialEditor.parseHex("#FFF"));
        assertNull(MaterialEditor.parseHex("#GGGGGG"));
    }

    @Test
    void hsvRoundTripsEveryCorner() {
        int[] colors = { 0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x808080,
                0x33AAFF, 0xFF940F, 0x123456 };
        for (int rgb : colors) {
            float[] hsv = MaterialEditor.toHsv(rgb);
            assertEquals(rgb, MaterialEditor.fromHsv(hsv[0], hsv[1], hsv[2]), Integer.toHexString(rgb));
        }
        // Hue 1 is the same red as hue 0
        assertEquals(0xFF0000, MaterialEditor.fromHsv(1, 1, 1));
    }
}
