package com.nectariich.create_flippies.content.procedural_gauge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The edgewise scale: majors in whole steps from the minimum, stopping at the last
 * one that fits as the Unity bar baker does, minors evenly between them, and labels
 * in the face's format and multiplier.
 */
class GaugeScaleTest {

    @Test
    void stepsFromTheMinimumAndKeepsTheLastTickDespiteFloatError() {
        assertEquals(5, GaugeScale.majorDivisions(0, 1, 0.2f));
        assertArrayEquals(new float[] { 0, 0.2f, 0.4f, 0.6f, 0.8f, 1 }, GaugeScale.majorValues(0, 1, 0.2f), 1e-6f);
        assertArrayEquals(new float[] { -10, -5, 0, 5, 10 }, GaugeScale.majorValues(-10, 10, 5), 0);
    }

    @Test
    void anIntervalThatDoesNotDivideTheRangeLeavesTheTopUnlabelled() {
        // The DHE sample face: -150 to 60 in thirties ends on 60 exactly
        assertArrayEquals(new float[] { -150, -120, -90, -60, -30, 0, 30, 60 },
                GaugeScale.majorValues(-150, 60, 30), 0);
        // 0 to 24 in fives stops at 20 rather than squeezing a last division in
        assertArrayEquals(new float[] { 0, 5, 10, 15, 20 }, GaugeScale.majorValues(0, 24, 5), 0);
        // An interval wider than the range still marks the minimum
        assertArrayEquals(new float[] { 0 }, GaugeScale.majorValues(0, 1, 5), 0);
    }

    @Test
    void makesNoScaleOfANonsenseRange() {
        assertEquals(0, GaugeScale.majorValues(5, 5, 1).length);
        assertEquals(0, GaugeScale.majorValues(10, 0, 1).length);
        assertEquals(0, GaugeScale.majorValues(0, 10, 0).length);
        assertEquals(0, GaugeScale.majorValues(0, 10, -1).length);
        assertEquals(0, GaugeScale.majorValues(0, Float.NaN, 1).length);
        assertEquals(0, GaugeScale.minorValues(0, 10, 0, 4).length);
        // A step too small for the range is capped, not a million ticks
        assertEquals(GaugeScale.MAX_MAJORS, GaugeScale.majorDivisions(0, 1000, 0.001f));
    }

    @Test
    void putsMinorsEvenlyBetweenMajorsAndNoneAfterTheLast() {
        float[] minors = GaugeScale.minorValues(0, 2, 1, 4);
        assertArrayEquals(new float[] { 0.2f, 0.4f, 0.6f, 0.8f, 1.2f, 1.4f, 1.6f, 1.8f }, minors, 1e-6f);
        assertEquals(0, GaugeScale.minorValues(0, 2, 1, 0).length);
        // Held to the most a face can show
        assertEquals(2 * GaugeScale.MAX_MINORS, GaugeScale.minorValues(0, 2, 1, 500).length);
        assertEquals(0, GaugeScale.minorValues(0, 2, 1, -3).length);
    }

    @Test
    void labelsInTheFormatTimesTheMultiplier() {
        assertEquals(List.of("0", "5", "10"), GaugeScale.labels(0, 10, 5, "0", 1));
        assertEquals(List.of("0.0", "0.5", "1.0"), GaugeScale.labels(0, 1, 0.5f, "0.0", 1));
        assertEquals(List.of("0", "250", "500"), GaugeScale.labels(0, 0.5f, 0.25f, "0", 1000));
        // A value that rounds to zero from below prints as zero, not "-0"
        assertEquals(List.of("-1", "0", "1"), GaugeScale.labels(-1, 1, 1, "0", 1));
        assertEquals("0.0", GaugeScale.labels(-0.04f, 1, 1, "0.0", 1).get(0));
        // A pattern that is not one reads as whole numbers
        assertEquals(List.of("0", "2"), GaugeScale.labels(0, 2, 2, "#,##0.0.0", 1));
    }
}
