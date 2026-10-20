package com.nectariich.create_flippies.foundation.wire.bend;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoubleClickDetectorTest {

    @Test
    void twoClicksOnOnePointInsideTheWindow() {
        DoubleClickDetector clicks = new DoubleClickDetector(350);
        assertFalse(clicks.click(7, 2, 1000));
        assertTrue(clicks.click(7, 2, 1350));
    }

    @Test
    void tooSlowIsTwoSingleClicks() {
        DoubleClickDetector clicks = new DoubleClickDetector(350);
        assertFalse(clicks.click(7, 2, 1000));
        assertFalse(clicks.click(7, 2, 1351));
        // ...but the slow second click starts a window of its own
        assertTrue(clicks.click(7, 2, 1500));
    }

    @Test
    void anotherPointOrWireStartsAfresh() {
        DoubleClickDetector clicks = new DoubleClickDetector(350);
        assertFalse(clicks.click(7, 2, 1000));
        assertFalse(clicks.click(7, 3, 1100));
        assertFalse(clicks.click(8, 3, 1200));
        assertTrue(clicks.click(8, 3, 1300));
    }

    @Test
    void aDoubleClickIsConsumed() {
        DoubleClickDetector clicks = new DoubleClickDetector(350);
        clicks.click(1, 0, 0);
        assertTrue(clicks.click(1, 0, 100));
        assertFalse(clicks.click(1, 0, 200));
    }

    @Test
    void resetAndClockGoingBackwards() {
        DoubleClickDetector clicks = new DoubleClickDetector(350);
        clicks.click(1, 0, 1000);
        clicks.reset();
        assertFalse(clicks.click(1, 0, 1100));
        assertFalse(clicks.click(1, 0, 900));
    }
}
