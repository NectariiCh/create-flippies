package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The inspector's typed values: what a half-typed field sends (nothing) and what a whole one means. */
class InspectorValuesTest {

    @Test
    void pixelsRoundTripThroughTenths() {
        assertEquals("12", InspectorValues.tenths(120));
        assertEquals("12.5", InspectorValues.tenths(125));
        assertEquals(125, InspectorValues.parseTenths("12.5"));
        assertEquals(120, InspectorValues.parseTenths("12."));
        assertNull(InspectorValues.parseTenths(""));
        assertNull(InspectorValues.parseTenths("."));
    }

    @Test
    void coloursNeedAllTheirDigits() {
        assertNull(InspectorValues.opaque("FF00"));
        assertEquals(0xFFFF0000, InspectorValues.opaque("#FF0000"));
        assertEquals(0, InspectorValues.translucent(""));
        assertEquals(0xFF102030, InspectorValues.translucent("102030"));
        assertEquals(0x80102030, InspectorValues.translucent("80102030"));
        assertNull(InspectorValues.translucent("8010203"));
        assertEquals("", InspectorValues.argb(0));
        assertEquals("80102030", InspectorValues.argb(0x80102030));
    }

    @Test
    void numbersDropTrailingZeros() {
        assertEquals("100", InspectorValues.number(100f));
        assertEquals("2.5", InspectorValues.number(2.5f));
        assertEquals("-0.125", InspectorValues.number(-0.125));
        assertEquals("0", InspectorValues.number(-0.0));
        assertNull(InspectorValues.parseNumber("-"));
        assertNull(InspectorValues.parseNumber("-."));
        assertEquals(-3.5f, InspectorValues.parseNumber("-3.5"));
    }

    @Test
    void aMovedStopTakesTheOthersAlongStably() {
        // Stops at 0, 500, 1000; the first moves to 700, past the second
        assertArrayEquals(new int[] { 1, 0, 2 }, InspectorValues.reorder(List.of(0, 500, 1000), 0, 700));
        // Moving onto another stop's position keeps the earlier one first
        assertArrayEquals(new int[] { 0, 1, 2 }, InspectorValues.reorder(List.of(0, 500, 1000), 1, 1000));
        assertArrayEquals(new int[] { 0, 2, 1 }, InspectorValues.reorder(List.of(0, 500, 1000), 2, 400));
    }

    @Test
    void levelsConvertBetweenUnitsAndPercent() {
        assertEquals(50f, InspectorValues.toPercent(75, 50, 100));
        assertEquals(75f, InspectorValues.fromPercent(50, 50, 100));
        assertEquals(7f, InspectorValues.toPercent(7, 5, 5));
    }
}
