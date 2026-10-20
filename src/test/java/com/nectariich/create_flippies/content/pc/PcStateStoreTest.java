package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nectariich.create_flippies.content.pc.PcStateStore.Restore;

import org.junit.jupiter.api.Test;

class PcStateStoreTest {

    @Test
    void roundTrip() {
        byte[] state = {1, 2, 3, 4, 5};
        PcStateStore.Saved saved = PcStateStore.decode(PcStateStore.encode(42, state));
        assertEquals(42, saved.generation());
        assertArrayEquals(state, saved.state());
        assertNull(PcStateStore.decode(new byte[3]));
        assertNull(PcStateStore.decode(null));
    }

    @Test
    void generationMatchLoads() {
        PcStateStore.Saved saved = PcStateStore.decode(PcStateStore.encode(7, new byte[] {9}));
        assertEquals(Restore.LOAD, PcStateStore.decide(7, saved, true, true));
        // Loading needs no power check: the supply is checked on the next tick as always
        assertEquals(Restore.LOAD, PcStateStore.decide(7, saved, true, false));
    }

    @Test
    void mismatchColdBootsWithPowerElseStaysOff() {
        PcStateStore.Saved stale = PcStateStore.decode(PcStateStore.encode(6, new byte[] {9}));
        assertEquals(Restore.COLD_BOOT, PcStateStore.decide(7, stale, true, true));
        assertEquals(Restore.STAY_OFF, PcStateStore.decide(7, stale, true, false));
        assertEquals(Restore.COLD_BOOT, PcStateStore.decide(7, null, true, true));
        assertEquals(Restore.STAY_OFF, PcStateStore.decide(7, null, true, false));
    }

    @Test
    void offStaysOff() {
        PcStateStore.Saved saved = PcStateStore.decode(PcStateStore.encode(7, new byte[] {9}));
        assertEquals(Restore.STAY_OFF, PcStateStore.decide(7, saved, false, true));
    }

    @Test
    void refusedStateIsAMismatch() {
        assertEquals(Restore.COLD_BOOT, PcStateStore.afterFailure(true));
        assertEquals(Restore.STAY_OFF, PcStateStore.afterFailure(false));
    }
}
