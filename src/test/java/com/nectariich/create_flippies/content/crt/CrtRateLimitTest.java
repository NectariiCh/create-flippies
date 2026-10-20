package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrtRateLimitTest {

    @Test
    void framesPerSecondToTicks() {
        assertEquals(1, CrtRateLimit.intervalTicks(20));
        assertEquals(2, CrtRateLimit.intervalTicks(10));
        assertEquals(3, CrtRateLimit.intervalTicks(7));
        assertEquals(20, CrtRateLimit.intervalTicks(1));
        // Out of range values are clamped to 1..20 fps
        assertEquals(1, CrtRateLimit.intervalTicks(60));
        assertEquals(20, CrtRateLimit.intervalTicks(0));
    }

    @Test
    void due() {
        assertTrue(CrtRateLimit.due(10, 12, 2));
        assertFalse(CrtRateLimit.due(10, 11, 2));
        assertTrue(CrtRateLimit.due(Long.MIN_VALUE / 2, 0, 20));
    }

    @Test
    void bucketCapsTheAverageAndAllowsOneOverdraw() {
        CrtRateLimit.Bucket bucket = new CrtRateLimit.Bucket();
        int perTick = 1000;
        bucket.refill(perTick);
        assertTrue(bucket.canSend());
        // A frame bigger than the allowance still goes, then the player waits it off
        bucket.spend(3500);
        assertFalse(bucket.canSend());
        int waited = 0;
        while (!bucket.canSend()) {
            bucket.refill(perTick);
            waited++;
        }
        assertEquals(3, waited);
    }

    @Test
    void quietPlayersSaveUpOnlyAFewTicks() {
        CrtRateLimit.Bucket bucket = new CrtRateLimit.Bucket();
        for (int i = 0; i < 100; i++)
            bucket.refill(1000);
        assertEquals(1000L * CrtRateLimit.BURST_TICKS, bucket.credit());
    }

    @Test
    void longRunAverageStaysUnderTheAllowance() {
        CrtRateLimit.Bucket bucket = new CrtRateLimit.Bucket();
        long sent = 0;
        int ticks = 1000;
        for (int t = 0; t < ticks; t++) {
            bucket.refill(1000);
            while (bucket.canSend()) {
                bucket.spend(700);
                sent += 700;
            }
        }
        // At most the allowance per tick plus one frame's overdraw
        assertTrue(sent <= 1000L * ticks + 700, "sent " + sent);
        assertTrue(sent >= 1000L * ticks - 700 * 2, "sent " + sent);
    }
}
