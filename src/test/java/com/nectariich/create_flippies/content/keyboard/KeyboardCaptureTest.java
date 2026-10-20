package com.nectariich.create_flippies.content.keyboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;

import org.junit.jupiter.api.Test;

class KeyboardCaptureTest {

    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @Test
    void oneTypistAtATime() {
        KeyboardCapture capture = new KeyboardCapture();
        assertTrue(capture.request(alice));
        assertFalse(capture.request(bob));
        assertTrue(capture.request(alice), "asking again keeps it");
        capture.release();
        assertTrue(capture.request(bob));
    }

    @Test
    void releaseSendsKeyUpsForHeldKeys() {
        KeyboardCapture capture = new KeyboardCapture();
        capture.request(alice);
        assertTrue(capture.accept(alice, Ps2Key.LEFT_SHIFT, true));
        assertTrue(capture.accept(alice, Ps2Key.A, true));
        assertTrue(capture.accept(alice, Ps2Key.A, false));
        assertTrue(capture.accept(alice, Ps2Key.UP, true));
        assertEquals(List.of(Ps2Key.LEFT_SHIFT, Ps2Key.UP), capture.release());
        assertNull(capture.typist());
        assertEquals(List.of(), capture.release());
    }

    @Test
    void eventsFromANonTypistAreIgnored() {
        KeyboardCapture capture = new KeyboardCapture();
        capture.request(alice);
        assertFalse(capture.accept(bob, Ps2Key.Q, true));
        assertEquals(List.of(), capture.release());
        assertFalse(capture.accept(alice, Ps2Key.Q, true), "nobody types after a release");
    }

    @Test
    void strayBreaksAndRepeatsAreDropped() {
        KeyboardCapture capture = new KeyboardCapture();
        capture.request(alice);
        assertFalse(capture.accept(alice, Ps2Key.ENTER, false), "up for a key never pressed");
        assertTrue(capture.accept(alice, Ps2Key.ENTER, true));
        assertFalse(capture.accept(alice, Ps2Key.ENTER, true), "already down");
    }

    @Test
    void eventEncodingRoundTrip() {
        for (Ps2Key key : Ps2Key.values())
            for (boolean down : new boolean[] {false, true}) {
                short event = KeyboardEvents.encode(key, down);
                assertEquals(key, KeyboardEvents.key(event));
                assertEquals(down, KeyboardEvents.down(event));
            }
        assertNull(KeyboardEvents.key((short) 0x7FFF));
    }
}
