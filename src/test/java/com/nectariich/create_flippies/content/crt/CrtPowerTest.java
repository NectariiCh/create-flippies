package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.pc.PcPower;

import org.junit.jupiter.api.Test;

class CrtPowerTest {

    @Test
    void sameWindowAsTheCase() {
        assertFalse(CrtPower.nextOn(false, 94));
        assertTrue(CrtPower.nextOn(false, 95));
        assertTrue(CrtPower.nextOn(false, 270));
        assertFalse(CrtPower.nextOn(false, 271));
        // Reverse polarity is fine (bridge rectifier)
        assertTrue(CrtPower.nextOn(false, -230));
    }

    @Test
    void hysteresisOnceOn() {
        assertTrue(CrtPower.nextOn(true, 91));
        assertTrue(CrtPower.nextOn(true, 279));
        assertFalse(CrtPower.nextOn(true, 89));
        assertFalse(CrtPower.nextOn(true, 281));
        // Off again: back to the start window
        assertFalse(CrtPower.nextOn(false, 91));
    }

    @Test
    void drawDependsOnThePicture() {
        assertEquals(60, CrtPower.inputWatts(true, true), 1e-9);
        assertEquals(15, CrtPower.inputWatts(true, false), 1e-9);
        assertEquals(0, CrtPower.inputWatts(false, false), 1e-9);
    }

    @Test
    void loadResistance() {
        // R = V^2 / P
        assertEquals(230.0 * 230 / 60, CrtPower.loadOhms(230, true, true), 1e-9);
        assertEquals(120.0 * 120 / 15, CrtPower.loadOhms(120, true, false), 1e-9);
        assertEquals(PcPower.LEAK_OHMS, CrtPower.loadOhms(230, false, false), 1e-9);
        assertEquals(PcPower.LEAK_OHMS, CrtPower.loadOhms(0, true, true), 1e-9);
    }
}
