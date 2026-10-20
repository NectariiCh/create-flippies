package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Rtc.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The real-time clock (spec 3.4). */
class RtcTest {

    private final long[] host = { 1_700_000_000L };
    private final Rtc rtc = new Rtc(() -> host[0]);
    private final DeviceRig rig = new DeviceRig().map(rtc, 0x1000);

    @Test
    void readsTheFixedSourceWithLatchedHigh() {
        assertEquals((int) 1_700_000_000L, rig.r32(SECONDS_LO));
        assertEquals(0, rig.r32(SECONDS_HI));
        host[0] = 5L << 32 | 9;
        assertEquals(0, rig.r32(SECONDS_HI), "latched by the last SECONDS_LO read");
        assertEquals(9, rig.r32(SECONDS_LO));
        assertEquals(5, rig.r32(SECONDS_HI));
    }

    @Test
    void setHiSetsAnOffset() {
        rig.w32(SET_LO, 0x10);
        assertEquals((int) 1_700_000_000L, rig.r32(SECONDS_LO), "nothing until SET_HI");
        rig.w32(SET_HI, 1);
        assertEquals(0x10, rig.r32(SECONDS_LO));
        assertEquals(1, rig.r32(SECONDS_HI));
        host[0] += 5;
        assertEquals(0x15, rig.r32(SECONDS_LO), "the clock keeps running from the new time");
        assertEquals((1L << 32) + 0x15, rtc.seconds());
    }

    @Test
    void setHiAsTwoHalvesActsOnTheHighHalf() {
        rig.w32(SET_LO, 100);
        rig.w16(SET_HI, 0);
        assertEquals((int) 1_700_000_000L, rig.r32(SECONDS_LO));
        rig.w16(SET_HI + 2, 0);
        assertEquals(100, rig.r32(SECONDS_LO));
    }

    @Test
    void resetKeepsTheOffsetPowerOnClearsIt() {
        rig.w32(SET_LO, 42);
        rig.w32(SET_HI, 0);
        rtc.reset();
        assertEquals(42, rig.r32(SECONDS_LO));
        rtc.powerOn();
        assertEquals((int) 1_700_000_000L, rig.r32(SECONDS_LO));
    }

    @Test
    void defaultSourceIsHostTime() {
        Rtc real = new Rtc(null);
        long now = System.currentTimeMillis() / 1000;
        long s = real.seconds();
        assertEquals(true, Math.abs(s - now) <= 2);
    }
}
