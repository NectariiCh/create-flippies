package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ZoomControlTest {

    @Test
    void stepsToTheNextStopEitherWay() {
        assertEquals(150, ZoomControl.next(100, 1));
        assertEquals(67, ZoomControl.next(100, -1));
        assertEquals(100, ZoomControl.next(80, 1), "from between stops, to the next one");
        assertEquals(67, ZoomControl.next(80, -1));
        assertEquals(150, ZoomControl.next(100.3, 1), "a hair off a stop counts as on it");
        assertEquals(67, ZoomControl.next(99.7, -1));
    }

    @Test
    void stopsAtTheEndsOfTheLadder() {
        assertEquals(0, ZoomControl.next(1600, 1));
        assertEquals(0, ZoomControl.next(25, -1));
        assertEquals(1600, ZoomControl.next(1500, 1));
        assertEquals(25, ZoomControl.next(30, -1));
    }
}
