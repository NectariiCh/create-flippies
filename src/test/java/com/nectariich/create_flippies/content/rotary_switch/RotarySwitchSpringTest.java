package com.nectariich.create_flippies.content.rotary_switch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The spring mask and the return-to-rest decision, which need no level to check. */
class RotarySwitchSpringTest {

    @Test
    void twoPositionSpringsOnlyFromItsQuarterTurn() {
        RotarySwitchKind kind = RotarySwitchKind.TWO_POSITION;
        assertEquals(List.of(1), RotarySwitchSpring.springable(kind));
        // The rest detent's bit and bits past the last detent are dropped
        assertEquals(0b10, RotarySwitchSpring.sanitise(kind, 0b11));
        assertEquals(0b10, RotarySwitchSpring.sanitise(kind, -1));
        assertEquals(0, RotarySwitchSpring.sanitise(kind, 0b1101));
        assertTrue(RotarySwitchSpring.springsFrom(kind, 0b10, 1));
        assertFalse(RotarySwitchSpring.springsFrom(kind, 0b11, 0));
    }

    @Test
    void threePositionSpringsFromEitherSideOfItsCentre() {
        for (RotarySwitchKind kind : new RotarySwitchKind[] { RotarySwitchKind.THREE_POSITION,
                RotarySwitchKind.LARGE_THREE_POSITION }) {
            assertEquals(1, kind.defaultPosition());
            assertEquals(List.of(0, 2), RotarySwitchSpring.springable(kind));
            assertEquals(0b101, RotarySwitchSpring.sanitise(kind, 0b111));
            assertEquals(0b101, RotarySwitchSpring.sanitise(kind, Integer.MIN_VALUE | 0xFF));
            assertEquals(0b100, RotarySwitchSpring.sanitise(kind, 0b1110));
            assertFalse(RotarySwitchSpring.springsFrom(kind, 0b111, 1));
            assertTrue(RotarySwitchSpring.springsFrom(kind, 0b001, 0));
            assertFalse(RotarySwitchSpring.springsFrom(kind, 0b001, 2));
            // Out-of-range positions never spring
            assertFalse(RotarySwitchSpring.springsFrom(kind, 0b101, -1));
            assertFalse(RotarySwitchSpring.springsFrom(kind, 0b101, 3));
        }
    }

    @Test
    void togglesOneBitAtATime() {
        RotarySwitchKind kind = RotarySwitchKind.THREE_POSITION;
        int mask = RotarySwitchSpring.with(kind, 0, 2, true);
        assertEquals(0b100, mask);
        mask = RotarySwitchSpring.with(kind, mask, 0, true);
        assertEquals(0b101, mask);
        mask = RotarySwitchSpring.with(kind, mask, 2, false);
        assertEquals(0b001, mask);
        // The rest detent cannot be given a spring
        assertEquals(0b001, RotarySwitchSpring.with(kind, mask, 1, true));
    }

    @Test
    void returnsOnlyFromASpringingDetentNobodyHolds() {
        RotarySwitchKind kind = RotarySwitchKind.THREE_POSITION;
        int cwOnly = 0b100;
        assertTrue(RotarySwitchSpring.shouldReturn(kind, cwOnly, 2, false));
        assertFalse(RotarySwitchSpring.shouldReturn(kind, cwOnly, 2, true));
        // Counter-clockwise latches under this mask, and the centre is already rest
        assertFalse(RotarySwitchSpring.shouldReturn(kind, cwOnly, 0, false));
        assertFalse(RotarySwitchSpring.shouldReturn(kind, cwOnly, 1, false));
        // No mask, no return
        assertFalse(RotarySwitchSpring.shouldReturn(RotarySwitchKind.TWO_POSITION, 0, 1, false));
        assertTrue(RotarySwitchSpring.shouldReturn(RotarySwitchKind.TWO_POSITION, 0b10, 1, false));
    }

    @Test
    void releaseBelongsToWhoeverTurnedTheHandle() {
        // With no holder recorded, nobody's release lets go; the server tick does
        RotarySwitchSpring spring = new RotarySwitchSpring();
        assertFalse(spring.release(RotarySwitchKind.TWO_POSITION, 0b10, 1, null));
    }
}
