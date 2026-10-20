package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.Clint.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The RV32I machine timer (spec 4.2). */
class ClintTest {

    private DeviceRig rig;
    private Clint clint;
    private final DeviceRig.Line msip = new DeviceRig.Line();
    private final DeviceRig.Line mtip = new DeviceRig.Line();

    private void setUp(int hz) {
        rig = new DeviceRig();
        clint = new Clint(rig.clock, hz);
        clint.setOutputs(msip, mtip);
        rig.map(clint, SIZE);
    }

    private void setCmp(long v) {
        rig.w32(MTIMECMP_LO, (int) v);
        rig.w32(MTIMECMP_HI, (int) (v >>> 32));
    }

    @Test
    void mtimeRunsAtOneMegahertz() {
        setUp(10_000_000);
        assertEquals(0, rig.r32(MTIME_LO));
        rig.at(10_000_000);
        assertEquals(1_000_000, rig.r32(MTIME_LO));
        rig.at(10_000_000L * 5000);
        long us = 5_000_000_000L;
        assertEquals((int) us, rig.r32(MTIME_LO));
        assertEquals((int) (us >>> 32), rig.r32(MTIME_HI));
        rig.w32(MTIME_LO, 0);
        assertEquals((int) us, rig.r32(MTIME_LO), "mtime is read-only");
    }

    @Test
    void mtimeAtAClockThatIsNotWholeMegahertz() {
        setUp(4_772_727);
        rig.at(4_772_727L * 3);
        assertEquals(3_000_000, rig.r32(MTIME_LO));
        rig.at(4_772_727L * 3 + 4);
        assertEquals(3_000_000, rig.r32(MTIME_LO), "4 cycles is less than a microsecond");
        rig.at(4_772_727L * 3 + 5);
        assertEquals(3_000_001, rig.r32(MTIME_LO));
    }

    @Test
    void mtimecmpResetsToAllOnes() {
        setUp(10_000_000);
        assertEquals(0xFFFF_FFFF, rig.r32(MTIMECMP_LO));
        assertEquals(0xFFFF_FFFF, rig.r32(MTIMECMP_HI));
        assertFalse(mtip.level);
        assertEquals(Long.MAX_VALUE, clint.nextEventCycle());
    }

    @Test
    void mtipIsALevelWhileMtimeReachesMtimecmp() {
        setUp(10_000_000);
        setCmp(100);
        assertFalse(mtip.level);
        assertEquals(1000, clint.nextEventCycle(), "exact");
        clint.advanceTo(999);
        assertFalse(mtip.level);
        clint.advanceTo(1000);
        assertTrue(mtip.level);
        assertEquals(Long.MAX_VALUE, clint.nextEventCycle(), "already high");
        rig.at(1000);
        setCmp(200);
        assertFalse(mtip.level, "re-arming lowers it at once");
        assertEquals(2000, clint.nextEventCycle());
        setCmp(50);
        assertTrue(mtip.level, "a compare in the past raises it at once");
    }

    @Test
    void nextEventCycleAtAnOddClock() {
        setUp(4_772_727);
        setCmp(1);
        assertEquals(5, clint.nextEventCycle());
        setCmp(1_000_000);
        assertEquals(4_772_727, clint.nextEventCycle());
        setCmp(-2);
        assertEquals(Long.MAX_VALUE, clint.nextEventCycle(), "negative as a signed long: never");
    }

    @Test
    void halvesAreWrittenIndependently() {
        setUp(10_000_000);
        rig.w32(MTIMECMP_LO, 0x1234_5678);
        assertEquals(0xFFFF_FFFF_1234_5678L, clint.mtimecmp());
        rig.w32(MTIMECMP_HI, 0x0000_0001);
        assertEquals(0x0000_0001_1234_5678L, clint.mtimecmp());
        rig.w16(MTIMECMP_LO + 2, 0xABCD);
        assertEquals(0x0000_0001_ABCD_5678L, clint.mtimecmp());
        assertEquals(0xABCD_5678, rig.r32(MTIMECMP_LO));
        assertEquals(1, rig.r32(MTIMECMP_HI));
    }

    @Test
    void anMtimecmpWriteLowersTheHorizon() {
        setUp(10_000_000);
        rig.at(50);
        rig.clock.setHorizon(1_000_000);
        setCmp(10);
        assertEquals(100, rig.clock.horizon());
    }

    @Test
    void msipDrivesItsPin() {
        setUp(10_000_000);
        rig.w32(MSIP, 1);
        assertTrue(msip.level);
        assertEquals(1, rig.r32(MSIP));
        rig.w32(MSIP, 2);
        assertFalse(msip.level, "only bit 0");
        rig.w32(MSIP, 1);
        clint.reset();
        assertFalse(msip.level);
        assertEquals(-1L, clint.mtimecmp());
    }
}
