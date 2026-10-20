package com.nectariich.create_flippies.content.schematic_arrow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import org.junit.jupiter.api.Test;

class ArrowSnappingTest {

    private static final int STEP = ArrowSnapping.STEP;
    private static final double BLOCK = PanelPlane.UNITS_PER_BLOCK;

    @Test
    void theLatticeIsHalfAPixel() {
        assertEquals(PanelPlane.UNITS_PER_PIXEL / 2, STEP);
    }

    @Test
    void gridSnapRoundsToTheNearestHalfPixel() {
        // 0.3 blocks is 76.8 units, nearest multiple of eight is 80
        assertArrayEquals(new int[] { 80, 16 }, ArrowSnapping.snap(null, 0.3, 0.06, 0, 0, ArrowSnap.GRID));
        // The first point of an angled path has nothing to be angled from
        assertArrayEquals(new int[] { 80, 16 }, ArrowSnapping.snap(null, 0.3, 0.06, 0, 0, ArrowSnap.GRID_ANGLE));
    }

    @Test
    void theLatticeCountsFromThePanelsCorner() {
        // A panel whose corner is three units off the block lattice
        double origin = 3 / BLOCK;
        int[] point = ArrowSnapping.snap(null, 0.3, 0.3, origin, origin, ArrowSnap.GRID);
        assertEquals(3, Math.floorMod(point[0], STEP));
        assertEquals(3, Math.floorMod(point[1], STEP));
    }

    @Test
    void freeSnapKeepsTheUnit() {
        assertArrayEquals(new int[] { 77, 15 }, ArrowSnapping.snap(null, 0.3, 0.06, 0, 0, ArrowSnap.FREE));
        assertArrayEquals(new int[] { 77, 15 }, ArrowSnapping.snap(new int[] { 0, 0 }, 0.3, 0.06, 0, 0,
                ArrowSnap.FREE));
    }

    @Test
    void angleSnapStraightensANearlyLevelSegment() {
        int[] from = { 0, 0 };
        int[] to = ArrowSnapping.snap(from, 99 / BLOCK, 9 / BLOCK, 0, 0, ArrowSnap.GRID_ANGLE);
        assertEquals(0, to[1]);
        assertEquals(0, to[0] % STEP);
        assertEquals(96, to[0]);
    }

    @Test
    void angleSnapMakesDiagonalsExactlyFortyFive() {
        int[] from = { 16, 16 };
        int[] to = ArrowSnapping.snap(from, (16 - 70) / BLOCK, (16 + 60) / BLOCK, 0, 0, ArrowSnap.GRID_ANGLE);
        assertEquals(-(to[0] - 16), to[1] - 16);
        assertEquals(0, (to[0] - 16) % STEP);
        assertEquals(-64, to[0] - 16);
    }

    @Test
    void angleSnapCoversAllEightDirections() {
        int[] from = { 0, 0 };
        for (int octant = 0; octant < 8; octant++) {
            double angle = octant * Math.PI / 4 + 0.1;
            int[] to = ArrowSnapping.snap(from, 64 * Math.cos(angle) / BLOCK, 64 * Math.sin(angle) / BLOCK, 0, 0,
                    ArrowSnap.GRID_ANGLE);
            double snapped = Math.atan2(to[1], to[0]);
            assertEquals(0, Math.abs(Math.IEEEremainder(snapped - octant * Math.PI / 4, 2 * Math.PI)), 1e-9);
        }
    }

    @Test
    void aClickBehindThePreviousPointStaysOnIt() {
        int[] from = { 40, 40 };
        assertArrayEquals(from, ArrowSnapping.snap(from, 40 / BLOCK, 40 / BLOCK, 0, 0, ArrowSnap.GRID_ANGLE));
    }
}
