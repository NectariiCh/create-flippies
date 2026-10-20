package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.IntervalTimer.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The interval timer (spec 3.3), at 10 MHz: 10 cycles per microsecond. */
class IntervalTimerTest {

    private DeviceRig rig;
    private IntervalTimer timer;
    private final DeviceRig.Line line = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        timer = new IntervalTimer(rig.clock, 10_000_000, line);
        rig.map(timer, 0x1000);
    }

    @Test
    void periodicExpiryStaysOnTheGrid() {
        rig.w32(PERIOD, 100);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        assertEquals(1000, timer.nextEventCycle());
        rig.at(999);
        assertEquals(0, rig.r32(STATUS));
        assertFalse(line.level);
        rig.at(1000);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
        assertTrue(line.level);
        rig.w32(STATUS, STATUS_EXPIRED);
        assertFalse(line.level, "W1C clears it");
        assertEquals(2000, timer.nextEventCycle());

        rig.at(2550);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
        rig.w32(STATUS, STATUS_EXPIRED);
        assertEquals(3000, timer.nextEventCycle(), "late catch-up keeps the original grid");
        rig.at(5_000);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
        rig.w32(STATUS, STATUS_EXPIRED);
        assertEquals(6000, timer.nextEventCycle(), "several periods missed at once");
    }

    @Test
    void writingZeroToStatusClearsNothing() {
        rig.w32(PERIOD, 1);
        rig.w32(CTRL, CTRL_ENABLE);
        rig.at(10);
        rig.w32(STATUS, 0);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
    }

    @Test
    void oneShotClearsEnable() {
        rig.w32(PERIOD, 50);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE | CTRL_ONE_SHOT);
        rig.at(500);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
        assertEquals(CTRL_IRQ_ENABLE | CTRL_ONE_SHOT, rig.r32(CTRL));
        rig.w32(STATUS, STATUS_EXPIRED);
        assertEquals(Long.MAX_VALUE, timer.nextEventCycle());
        rig.at(5000);
        assertEquals(0, rig.r32(STATUS), "no second expiry");
    }

    @Test
    void enableRestartsFromNow() {
        rig.at(1234);
        rig.w32(PERIOD, 10);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        assertEquals(1230 + 100, timer.nextEventCycle(), "from now in whole microseconds (123 us)");
        rig.at(1300);
        rig.w32(PERIOD, 20);
        assertEquals(1300 + 200, timer.nextEventCycle(), "PERIOD written while enabled restarts");
    }

    @Test
    void irqDisabledExpiresWithoutTheLine() {
        rig.w32(PERIOD, 100);
        rig.w32(CTRL, CTRL_ENABLE);
        assertEquals(Long.MAX_VALUE, timer.nextEventCycle(), "no line change to wait for");
        rig.at(1000);
        assertEquals(STATUS_EXPIRED, rig.r32(STATUS));
        assertFalse(line.level);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        assertTrue(line.level, "line = EXPIRED and IRQ_ENABLE");
    }

    @Test
    void periodZeroStops() {
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        rig.at(100_000);
        assertEquals(0, rig.r32(STATUS));
        assertEquals(Long.MAX_VALUE, timer.nextEventCycle());
    }

    @Test
    void counterLowLatchesHigh() {
        long us = (1L << 32) + 5;
        rig.at(us * 10);
        assertEquals(5, rig.r32(COUNTER_LO));
        rig.at(us * 10 + 10L * (1L << 32));
        assertEquals(1, rig.r32(COUNTER_HI), "still the latched value");
        assertEquals(5, rig.r32(COUNTER_LO));
        assertEquals(2, rig.r32(COUNTER_HI));
        rig.at(us * 10 + 10L * (1L << 32) + 70);
        assertEquals(12, rig.r16(COUNTER_LO), "a 16-bit read of the low half latches");
        assertEquals(0, rig.r16(COUNTER_LO + 2));
    }

    @Test
    void timerWritesLowerTheHorizon() {
        rig.clock.setHorizon(Long.MAX_VALUE);
        rig.w32(PERIOD, 3);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        assertEquals(30, rig.clock.horizon());
    }

    @Test
    void resetStopsAndLowersTheLine() {
        rig.w32(PERIOD, 1);
        rig.w32(CTRL, CTRL_ENABLE | CTRL_IRQ_ENABLE);
        rig.at(10);
        rig.r32(STATUS);
        assertTrue(line.level);
        timer.reset();
        assertFalse(line.level);
        assertEquals(0, rig.r32(CTRL));
        assertEquals(0, rig.r32(PERIOD));
    }
}
