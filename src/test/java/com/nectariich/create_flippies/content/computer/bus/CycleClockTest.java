package com.nectariich.create_flippies.content.computer.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** {@link CycleClock} time conversions and horizon (spec 1.4, 8.3). */
class CycleClockTest {

    private static final int[] RATES = { 10_000_000, 4_772_727, 100_000_000 };

    private static long exactMicros(long cycles, int hz) {
        return BigInteger.valueOf(cycles).multiply(BigInteger.valueOf(1_000_000))
                .divide(BigInteger.valueOf(hz)).longValueExact();
    }

    @Test
    void cyclesToMicrosIsTheExactFloor() {
        assertEquals(1_000_000, CycleClock.cyclesToMicros(10_000_000, 10_000_000));
        assertEquals(0, CycleClock.cyclesToMicros(9, 10_000_000));
        assertEquals(1, CycleClock.cyclesToMicros(10, 10_000_000));
        assertEquals(1_000_000, CycleClock.cyclesToMicros(4_772_727, 4_772_727));
        assertEquals(0, CycleClock.cyclesToMicros(4, 4_772_727));
        assertEquals(1, CycleClock.cyclesToMicros(5, 4_772_727));
        assertEquals(2_000_000, CycleClock.cyclesToMicros(9_545_455, 4_772_727));
        assertEquals(1, CycleClock.cyclesToMicros(100, 100_000_000));
        Random rnd = new Random(1);
        for (int hz : RATES)
            for (int i = 0; i < 2000; i++) {
                long c = rnd.nextLong() & Long.MAX_VALUE;
                assertEquals(exactMicros(c, hz), CycleClock.cyclesToMicros(c, hz), c + " @ " + hz);
            }
        assertEquals(exactMicros(Long.MAX_VALUE, 10_000_000), CycleClock.cyclesToMicros(Long.MAX_VALUE, 10_000_000));
        assertEquals(exactMicros(Long.MAX_VALUE, 4_772_727), CycleClock.cyclesToMicros(Long.MAX_VALUE, 4_772_727));
    }

    @Test
    void microsToCyclesIsTheFirstCycleReachingTheTime() {
        assertEquals(10, CycleClock.microsToCycles(1, 10_000_000));
        assertEquals(5, CycleClock.microsToCycles(1, 4_772_727));
        assertEquals(100, CycleClock.microsToCycles(1, 100_000_000));
        assertEquals(0, CycleClock.microsToCycles(0, 4_772_727));
        Random rnd = new Random(2);
        for (int hz : RATES)
            for (int i = 0; i < 2000; i++) {
                long m = i < 1000 ? i : rnd.nextLong() >>> 30;
                long c = CycleClock.microsToCycles(m, hz);
                assertTrue(CycleClock.cyclesToMicros(c, hz) >= m, m + " @ " + hz);
                if (c > 0)
                    assertTrue(CycleClock.cyclesToMicros(c - 1, hz) < m, "smallest such cycle, " + m + " @ " + hz);
            }
    }

    @Test
    void microsToCyclesSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, CycleClock.microsToCycles(-1, 10_000_000), "negative micros (mtimecmp of all ones)");
        assertEquals(Long.MAX_VALUE, CycleClock.microsToCycles(Long.MIN_VALUE, 10_000_000));
        assertEquals(Long.MAX_VALUE, CycleClock.microsToCycles(Long.MAX_VALUE, 10_000_000));
        assertEquals(Long.MAX_VALUE, CycleClock.microsToCycles(Long.MAX_VALUE / 2, 100_000_000));
        long m = Long.MAX_VALUE / 100;
        long c = CycleClock.microsToCycles(m, 10_000_000);
        assertEquals(m * 10, c, "large but representable");
    }

    @Test
    void requestStopAtOnlyLowersAndNeverGoesBelowNow() {
        CycleClock clock = new CycleClock();
        assertEquals(Long.MAX_VALUE, clock.horizon());
        clock.setHorizon(1000);
        clock.requestStopAt(2000);
        assertEquals(1000, clock.horizon(), "a later cycle is ignored");
        clock.requestStopAt(500);
        assertEquals(500, clock.horizon());
        clock.advanceTo(300);
        clock.requestStopAt(10);
        assertEquals(300, clock.horizon(), "clamped to now");
        clock.advanceTo(200);
        assertEquals(300, clock.now(), "the clock never goes back");
        clock.reset();
        assertEquals(0, clock.now());
        assertEquals(Long.MAX_VALUE, clock.horizon());
    }
}
