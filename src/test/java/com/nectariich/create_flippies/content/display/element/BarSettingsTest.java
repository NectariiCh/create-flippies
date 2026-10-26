package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A bar reads numbers as gauges do and holds its fill to its box. */
class BarSettingsTest {

    @Test
    void readsTheFirstNumberAsGaugesDo() {
        assertEquals(1234.5f, BarSettings.parse("Flow 1,234.5 mB/t"));
        assertEquals(-12f, BarSettings.parse("-12 C then 40"));
        assertTrue(Float.isNaN(BarSettings.parse("N/A")));
        assertTrue(Float.isNaN(BarSettings.parse("")));
    }

    @Test
    void progressIsHeldToTheBox() {
        BarSettings bar = new BarSettings(10, 20, BarSettings.LEFT_TO_RIGHT, true);
        assertEquals(0.5f, bar.progress(15));
        assertEquals(0, bar.progress(-100));
        assertEquals(1, bar.progress(1e6f));
        assertEquals(0, bar.progress(Float.NaN));
        assertEquals(0, new BarSettings(5, 5, 0, true).progress(5), "an empty range shows nothing");
        BarSettings reversed = new BarSettings(100, 0, BarSettings.TOP_TO_BOTTOM, false);
        assertEquals(0.25f, reversed.progress(75));
    }

    @Test
    void roundTrips() {
        BarSettings bar = new BarSettings(-3, 7.5f, BarSettings.BOTTOM_TO_TOP, false);
        assertEquals(bar, BarSettings.read(bar.write()));
        assertTrue(BarSettings.DEFAULT.write().isEmpty());
    }
}
