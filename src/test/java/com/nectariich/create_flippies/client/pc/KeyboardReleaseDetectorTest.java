package com.nectariich.create_flippies.client.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.client.pc.KeyboardReleaseDetector.Action;

import org.junit.jupiter.api.Test;

class KeyboardReleaseDetectorTest {

    private static final long WINDOW = 300;

    @Test
    void singleEscapeIsSent() {
        assertEquals(Action.SEND, new KeyboardReleaseDetector().press(true, 1000, WINDOW));
    }

    @Test
    void twoPressesInsideTheWindowRelease() {
        KeyboardReleaseDetector d = new KeyboardReleaseDetector();
        assertEquals(Action.SEND, d.press(true, 1000, WINDOW));
        assertEquals(Action.RELEASE, d.press(true, 1300, WINDOW));
    }

    @Test
    void outsideTheWindowBothAreSent() {
        KeyboardReleaseDetector d = new KeyboardReleaseDetector();
        assertEquals(Action.SEND, d.press(true, 1000, WINDOW));
        assertEquals(Action.SEND, d.press(true, 1301, WINDOW));
        // ...and the second one starts a new window
        assertEquals(Action.RELEASE, d.press(true, 1400, WINDOW));
    }

    @Test
    void anotherKeyBetweenResets() {
        KeyboardReleaseDetector d = new KeyboardReleaseDetector();
        assertEquals(Action.SEND, d.press(true, 1000, WINDOW));
        assertEquals(Action.SEND, d.press(false, 1050, WINDOW));
        assertEquals(Action.SEND, d.press(true, 1100, WINDOW));
    }

    @Test
    void theWindowComesFromTheCaller() {
        KeyboardReleaseDetector d = new KeyboardReleaseDetector();
        assertEquals(Action.SEND, d.press(true, 1000, 150));
        assertEquals(Action.SEND, d.press(true, 1200, 150));
        KeyboardReleaseDetector wide = new KeyboardReleaseDetector();
        assertEquals(Action.SEND, wide.press(true, 1000, 1000));
        assertEquals(Action.RELEASE, wide.press(true, 1900, 1000));
    }

    @Test
    void aThirdPressAfterAReleaseStartsOver() {
        KeyboardReleaseDetector d = new KeyboardReleaseDetector();
        d.press(true, 1000, WINDOW);
        assertEquals(Action.RELEASE, d.press(true, 1100, WINDOW));
        assertEquals(Action.SEND, d.press(true, 1200, WINDOW));
    }
}
