package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnappingTest {

    private static final int[] LINES = Snapping.lines(200);

    @Test
    void linesAreTheEdgesAndCentre() {
        assertArrayEquals(new int[] { 0, 100, 200 }, LINES);
    }

    @Test
    void gridRoundsToTheNearestStep() {
        assertEquals(30, Snapping.grid(34, 10));
        assertEquals(40, Snapping.grid(35, 10));
        assertEquals(37, Snapping.grid(37.2, 1));
        assertEquals(-10, Snapping.grid(-12, 10));
    }

    @Test
    void aPointNearALineTakesTheLine() {
        Snapping.Snap snap = Snapping.point(97, 10, LINES, 5);
        assertEquals(100, snap.value());
        assertTrue(snap.onLine());
    }

    @Test
    void aPointFarFromLinesTakesTheGrid() {
        Snapping.Snap snap = Snapping.point(57, 10, LINES, 5);
        assertEquals(60, snap.value());
        assertFalse(snap.onLine());
        assertEquals(57, Snapping.point(57.4, 1, LINES, 5).value());
    }

    @Test
    void theNearestLineWins() {
        int[] close = { 10, 14 };
        assertEquals(14, Snapping.point(13, 10, close, 5).value());
    }

    @Test
    void aSpanSnapsByItsStartCentreOrEnd() {
        // End 198 is near 200
        assertEquals(160, Snapping.span(158, 40, 10, LINES, 5).value());
        // Centre 97 + 20 = 117: no; start 97 is near the centre line
        assertEquals(100, Snapping.span(97, 40, 10, LINES, 5).value());
        // Centre 78 + 20 = 98 is near 100
        assertEquals(80, Snapping.span(78, 40, 10, LINES, 5).value());
        Snapping.Snap free = Snapping.span(43, 40, 10, LINES, 5);
        assertEquals(40, free.value());
        assertFalse(free.onLine());
    }

    @Test
    void aSpanReportsTheLineItSnappedTo() {
        assertEquals(200, Snapping.span(158, 40, 10, LINES, 5).line());
    }

    @Test
    void diagonalTurnsToFortyFiveDegreeSteps() {
        assertArrayEquals(new double[] { 10, 0 }, Snapping.diagonal(10, 2), 1e-9);
        assertArrayEquals(new double[] { 0, -10 }, Snapping.diagonal(1, -10), 1e-9);
        double[] d = Snapping.diagonal(10, 9);
        assertEquals(d[0], d[1], 1e-9);
        assertEquals((10 + 9) / 2.0, d[0], 1e-9);
        double[] back = Snapping.diagonal(-10, 11);
        assertEquals(-back[0], back[1], 1e-9);
        assertArrayEquals(new double[] { 0, 0 }, Snapping.diagonal(0, 0), 1e-9);
    }

    @Test
    void keepAspectGrowsTheShortSideAndKeepsSigns() {
        assertArrayEquals(new double[] { 40, 20 }, Snapping.keepAspect(40, 10, 2), 1e-9);
        assertArrayEquals(new double[] { -60, 30 }, Snapping.keepAspect(-10, 30, 2), 1e-9);
        assertArrayEquals(new double[] { 25, -25 }, Snapping.keepAspect(25, -5, 1), 1e-9);
        assertArrayEquals(new double[] { 7, 3 }, Snapping.keepAspect(7, 3, 0), 1e-9);
    }

    @Test
    void fitFractionKeepsAVectorInside() {
        assertEquals(1, Snapping.fitFraction(50, 50, 10, 10, 100, 100), 1e-9);
        assertEquals(0.5, Snapping.fitFraction(50, 50, 100, 20, 100, 100), 1e-9);
        assertEquals(0.25, Snapping.fitFraction(50, 50, -40, -200, 100, 100), 1e-9);
    }

    @Test
    void clampPrefersTheMinimumWhenTheRangeIsEmpty() {
        assertEquals(5, Snapping.clamp(10, 0, 5));
        assertEquals(3, Snapping.clamp(1, 3, 2));
    }
}
