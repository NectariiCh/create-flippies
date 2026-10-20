package com.nectariich.create_flippies.content.schematic_arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ArrowOutlineTest {

    private static final double TOLERANCE = 1e-9;

    private static ArrowOutline.Shape shape(double width, ArrowHead start, ArrowHead end, ArrowJoin join,
            double... points) {
        return ArrowOutline.of(points, width, start, end, join);
    }

    private static double area(ArrowOutline.Shape shape) {
        double sum = 0;
        for (double[] ring : shape.rings())
            sum += ArrowOutline.signedArea(ring);
        return sum;
    }

    @Test
    void aStraightStripIsARectangle() {
        ArrowOutline.Shape strip = shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER, 0, 0, 10, 0);
        assertTrue(strip.merged());
        assertEquals(1, strip.rings().size());
        assertEquals(8, strip.rings().get(0).length);
        assertEquals(20, area(strip), TOLERANCE);
        assertTrue(ArrowOutline.contains(strip.rings(), 5, 0.9));
        assertFalse(ArrowOutline.contains(strip.rings(), 5, 1.1));
    }

    @Test
    void aSharpRightAngleKeepsTheFullCorner() {
        // The square added outside the bend is the square cut away inside it
        ArrowOutline.Shape bend = shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER, 0, 0, 10, 0, 10, 10);
        assertTrue(bend.merged());
        assertEquals(12, bend.rings().get(0).length);
        assertEquals(40, area(bend), TOLERANCE);
        assertTrue(ArrowOutline.contains(bend.rings(), 10.9, -0.9));
    }

    @Test
    void aBevelCutsTheOuterCornerOff() {
        ArrowOutline.Shape bend = shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.BEVEL, 0, 0, 10, 0, 10, 10);
        assertEquals(14, bend.rings().get(0).length);
        assertEquals(40 - 0.5, area(bend), TOLERANCE);
        assertFalse(ArrowOutline.contains(bend.rings(), 10.9, -0.9));
    }

    @Test
    void aRoundBendIsBetweenTheTwo() {
        double round = area(shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.ROUND, 0, 0, 10, 0, 10, 10));
        assertTrue(round > 39.5 && round < 40, Double.toString(round));
        // A quarter circle of radius one in place of the square corner, to within the chord error
        assertEquals(40 - 1 + Math.PI / 4, round, 0.03);
    }

    @Test
    void aVerySharpBendIsBevelledEvenWhenMitred() {
        // Fifteen degrees between the segments: the miter would reach almost eight half widths out
        double angle = Math.toRadians(165);
        double[] path = { 0, 0, 20, 0, 20 + 20 * Math.cos(angle), 20 * Math.sin(angle) };
        double mitred = area(ArrowOutline.of(path, 1, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER));
        double bevelled = area(ArrowOutline.of(path, 1, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.BEVEL));
        assertEquals(bevelled, mitred, TOLERANCE);
        // While a right angle is within the limit and keeps its corner
        double[] square = { 0, 0, 10, 0, 10, 10 };
        assertTrue(area(ArrowOutline.of(square, 2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER))
                > area(ArrowOutline.of(square, 2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.BEVEL)));
    }

    @Test
    void aTriangleHeadEndsOnThePointClicked() {
        ArrowOutline.Shape arrow = shape(2, ArrowHead.NONE, ArrowHead.TRIANGLE, ArrowJoin.MITER, 0, 0, 20, 0);
        assertTrue(arrow.merged());
        double[] ring = arrow.rings().get(0);
        assertEquals(14, ring.length);
        // Shaft 16 long and 2 wide, head 4 long and 6 across
        assertEquals(16 * 2 + 4 * 6 / 2.0, area(arrow), TOLERANCE);
        double furthest = 0;
        for (int i = 0; i < ring.length; i += 2)
            furthest = Math.max(furthest, ring[i]);
        assertEquals(20, furthest, TOLERANCE);
        assertTrue(ArrowOutline.contains(arrow.rings(), 16.5, 2.5));
        assertFalse(ArrowOutline.contains(arrow.rings(), 17, 2.5));
    }

    @Test
    void headsAtBothEndsPointOutwards() {
        ArrowOutline.Shape both = shape(2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE, ArrowJoin.MITER, 0, 0, 20, 0);
        assertTrue(both.merged());
        assertEquals(12 * 2 + 2 * 12, area(both), TOLERANCE);
        assertTrue(ArrowOutline.contains(both.rings(), 3, 2));
        assertTrue(ArrowOutline.contains(both.rings(), 17, -2));
    }

    @Test
    void aChevronIsAnOpenV() {
        ArrowOutline.Shape chevron = shape(2, ArrowHead.NONE, ArrowHead.CHEVRON, ArrowJoin.MITER, 0, 0, 30, 0);
        assertTrue(chevron.merged());
        assertTrue(ArrowOutline.simple(chevron.rings().get(0)));
        // The tip and the arms are solid, the inside of the V between the arms is not
        assertTrue(ArrowOutline.contains(chevron.rings(), 29.5, 0));
        assertTrue(ArrowOutline.contains(chevron.rings(), 25.6, 4.0));
        assertFalse(ArrowOutline.contains(chevron.rings(), 25.5, 1.8));
    }

    @Test
    void aPathTooShortForItsHeadsHasNoOutline() {
        assertTrue(shape(2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE, ArrowJoin.MITER, 0, 0, 7, 0).isEmpty());
        assertFalse(ArrowOutline.fits(new double[] { 0, 0, 7, 0 }, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE));
        assertTrue(ArrowOutline.fits(new double[] { 0, 0, 9, 0 }, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE));
        assertTrue(shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER, 0, 0).isEmpty());
        // Repeated points are not segments
        assertTrue(shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER, 3, 3, 3, 3).isEmpty());
    }

    @Test
    void aStripThatCrossesItselfFallsBackToPieces() {
        ArrowOutline.Shape crossing = shape(2, ArrowHead.NONE, ArrowHead.TRIANGLE, ArrowJoin.MITER, 0, 0, 20, 0, 20,
                10, 10, -10);
        assertFalse(crossing.merged());
        // Three segments, two bends, one head
        assertEquals(6, crossing.rings().size());
        for (double[] ring : crossing.rings()) {
            assertTrue(ArrowOutline.signedArea(ring) > 0);
            assertTrue(ArrowOutline.simple(ring));
        }
        assertTrue(ArrowOutline.contains(crossing.rings(), 15, 0));
    }

    @Test
    void aStripThatFoldsBackFallsBackToPieces() {
        ArrowOutline.Shape folded = shape(2, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.ROUND, 0, 0, 10, 0, 5, 0);
        assertFalse(folded.merged());
        assertEquals(2, folded.rings().size());
    }

    @Test
    void aTightZigzagWhoseInsideOverrunsIsPieces() {
        // Segments shorter than the strip is wide: the inner corners cross the next segment
        ArrowOutline.Shape zigzag = shape(4, ArrowHead.NONE, ArrowHead.NONE, ArrowJoin.MITER, 0, 0, 1, 0, 1, 1, 0,
                1, 0, 2);
        assertFalse(zigzag.merged());
        for (double[] ring : zigzag.rings())
            assertTrue(ArrowOutline.signedArea(ring) > 0);
    }

    @Test
    void everyRingRunsCounterClockwiseWhicheverWayThePathIsDrawn() {
        List<double[]> paths = List.of(new double[] { 0, 0, 10, 0, 10, 10 }, new double[] { 10, 10, 10, 0, 0, 0 },
                new double[] { 0, 0, -10, -10, -20, -10 });
        for (double[] path : paths)
            for (ArrowJoin join : ArrowJoin.values())
                for (double[] ring : ArrowOutline.of(path, 2, ArrowHead.CHEVRON, ArrowHead.TRIANGLE, join).rings())
                    assertTrue(ArrowOutline.signedArea(ring) > 0);
    }

    @Test
    void theReachHoldsTheWholeOutline() {
        double width = 3;
        double reach = ArrowOutline.reach(width);
        double[] path = { 0, 0, 30, 0, 30, 30, 60, 0 };
        for (ArrowJoin join : ArrowJoin.values())
            for (double[] ring : ArrowOutline.of(path, width, ArrowHead.CHEVRON, ArrowHead.CHEVRON, join).rings())
                for (int i = 0; i < ring.length; i += 2) {
                    assertTrue(ring[i] >= -reach && ring[i] <= 60 + reach);
                    assertTrue(ring[i + 1] >= -reach && ring[i + 1] <= 30 + reach);
                }
    }

    // Sized heads

    private static ArrowOutline.Shape sized(double width, ArrowHead start, ArrowHead end, ArrowOutline.HeadSize size,
            double... points) {
        return ArrowOutline.of(points, width, start, end, ArrowJoin.MITER, size);
    }

    @Test
    void anUnsizedHeadIsTheHeadSizedFromTheWidth() {
        double[] path = { 0, 0, 30, 0, 30, 30 };
        for (ArrowHead head : ArrowHead.values()) {
            List<double[]> auto = ArrowOutline.of(path, 2, head, head, ArrowJoin.MITER).rings();
            List<double[]> sized = sized(2, head, head, ArrowOutline.HeadSize.AUTO, path).rings();
            assertEquals(auto.size(), sized.size());
            for (int i = 0; i < auto.size(); i++)
                assertTrue(java.util.Arrays.equals(auto.get(i), sized.get(i)));
        }
        assertEquals(ArrowOutline.reach(2), ArrowOutline.reach(2, ArrowOutline.HeadSize.AUTO), TOLERANCE);
    }

    @Test
    void aSizedTriangleHasTheLengthAndWidthAskedFor() {
        ArrowOutline.Shape arrow = sized(2, ArrowHead.NONE, ArrowHead.TRIANGLE, new ArrowOutline.HeadSize(8, 10, 0),
                0, 0, 20, 0);
        assertTrue(arrow.merged());
        // Shaft 12 long and 2 wide, head 8 long and 10 across
        assertEquals(12 * 2 + 8 * 10 / 2.0, area(arrow), TOLERANCE);
        assertTrue(ArrowOutline.contains(arrow.rings(), 12.5, 4.5));
        assertFalse(ArrowOutline.contains(arrow.rings(), 11.5, 4.5));
        assertFalse(ArrowOutline.contains(arrow.rings(), 12.5, 5.5));
    }

    @Test
    void aHeadIsNeverNarrowerThanTheShaft() {
        ArrowOutline.Shape narrow = sized(4, ArrowHead.NONE, ArrowHead.TRIANGLE, new ArrowOutline.HeadSize(0, 1, 0),
                0, 0, 40, 0);
        // The triangle is drawn as wide as the shaft: shaft 32 by 4, head 8 long and 4 across
        assertEquals(32 * 4 + 8 * 4 / 2.0, area(narrow), TOLERANCE);
    }

    @Test
    void aSizedChevronStaysAnOpenV() {
        ArrowOutline.HeadSize size = new ArrowOutline.HeadSize(12, 16, 3);
        ArrowOutline.Shape chevron = sized(2, ArrowHead.NONE, ArrowHead.CHEVRON, size, 0, 0, 40, 0);
        assertTrue(chevron.merged());
        assertTrue(ArrowOutline.simple(chevron.rings().get(0)));
        assertTrue(ArrowOutline.contains(chevron.rings(), 39.5, 0));
        // The arms reach out to the width asked for, the inside of the V is open
        assertTrue(ArrowOutline.contains(chevron.rings(), 28.5, 7.5));
        assertFalse(ArrowOutline.contains(chevron.rings(), 28.5, 8.5));
        assertFalse(ArrowOutline.contains(chevron.rings(), 29, 2));
        // A thicker stroke fills more of the V
        ArrowOutline.Shape thick = sized(2, ArrowHead.NONE, ArrowHead.CHEVRON, new ArrowOutline.HeadSize(12, 16, 5),
                0, 0, 40, 0);
        assertTrue(area(thick) > area(chevron));
    }

    @Test
    void aStrokeTooThickForTheVIsThinnedNotBroken() {
        for (double stroke : new double[] { 1, 4, 20, 1000 })
            for (double across : new double[] { 1, 4, 30 }) {
                ArrowOutline.Shape chevron = sized(2, ArrowHead.CHEVRON, ArrowHead.CHEVRON,
                        new ArrowOutline.HeadSize(6, across, stroke), 0, 0, 40, 0);
                assertFalse(chevron.isEmpty());
                assertTrue(chevron.merged());
                assertTrue(ArrowOutline.simple(chevron.rings().get(0)));
                assertTrue(ArrowOutline.signedArea(chevron.rings().get(0)) > 0);
            }
    }

    @Test
    void bigHeadsNeedALongerPath() {
        double[] path = { 0, 0, 20, 0 };
        assertTrue(ArrowOutline.fits(path, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE));
        ArrowOutline.HeadSize big = new ArrowOutline.HeadSize(12, 0, 0);
        assertFalse(ArrowOutline.fits(path, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE, big));
        assertTrue(sized(2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE, big, path).isEmpty());
        assertTrue(ArrowOutline.fits(path, 2, ArrowHead.NONE, ArrowHead.TRIANGLE, big));
        assertTrue(ArrowOutline.fits(new double[] { 0, 0, 25, 0 }, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE, big));
        // A bent path needs each end segment long enough for its own head
        assertFalse(ArrowOutline.fits(new double[] { 0, 0, 30, 0, 30, 10 }, 2, ArrowHead.NONE, ArrowHead.TRIANGLE,
                big));
        // A short head fits where the width's own would not
        assertTrue(ArrowOutline.fits(new double[] { 0, 0, 7, 0 }, 2, ArrowHead.TRIANGLE, ArrowHead.TRIANGLE,
                new ArrowOutline.HeadSize(2, 0, 0)));
    }

    @Test
    void theReachHoldsSizedHeadsToo() {
        double width = 2;
        double[] path = { 0, 0, 30, 0, 30, 30, 60, 0 };
        for (ArrowOutline.HeadSize size : List.of(new ArrowOutline.HeadSize(20, 30, 2),
                new ArrowOutline.HeadSize(30, 4, 1), new ArrowOutline.HeadSize(0, 40, 0))) {
            double reach = ArrowOutline.reach(width, size);
            for (ArrowHead head : List.of(ArrowHead.TRIANGLE, ArrowHead.CHEVRON))
                for (double[] ring : ArrowOutline.of(path, width, head, head, ArrowJoin.MITER, size).rings())
                    for (int i = 0; i < ring.length; i += 2) {
                        assertTrue(ring[i] >= -reach && ring[i] <= 60 + reach);
                        assertTrue(ring[i + 1] >= -reach && ring[i + 1] <= 30 + reach);
                    }
        }
    }
}
