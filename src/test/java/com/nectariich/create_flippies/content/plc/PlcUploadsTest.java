package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The per-player flash cooldown. Regression for upload spam: a client could finish a
 * one-chunk flash every tick and start twenty compiles a second.
 */
class PlcUploadsTest {

    @Test
    void oneFlashPerPlayerPerCooldown() {
        UUID player = UUID.randomUUID();
        assertTrue(PlcUploads.mayFlash(player, 1000));
        assertFalse(PlcUploads.mayFlash(player, 1001), "the next tick is too soon");
        assertFalse(PlcUploads.mayFlash(player, 1000 + PlcUploads.FLASH_COOLDOWN_TICKS - 1));
        assertTrue(PlcUploads.mayFlash(player, 1000 + PlcUploads.FLASH_COOLDOWN_TICKS), "a second later is fine");
    }

    @Test
    void playersDoNotShareACooldown() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(PlcUploads.mayFlash(a, 50));
        assertTrue(PlcUploads.mayFlash(b, 50));
    }

    @Test
    void aClockGoingBackwardsRefusesNobody() {
        UUID player = UUID.randomUUID();
        assertTrue(PlcUploads.mayFlash(player, 5000));
        assertTrue(PlcUploads.mayFlash(player, 10));
    }
}
