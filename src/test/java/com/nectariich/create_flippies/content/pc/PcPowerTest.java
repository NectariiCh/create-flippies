package com.nectariich.create_flippies.content.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PcPowerTest {

    private static PcPower.Draw draw(PcCpuType cpu, double busy, PcRamType a, PcRamType b, boolean hdd,
            boolean hddActive, boolean floppyMotor) {
        return new PcPower.Draw(true, cpu, busy, a, b, hdd, hddActive, floppyMotor);
    }

    @Test
    void drawPerPart() {
        // Motherboard 8, idle RV32I 10 MHz 0.5, one 16 MiB stick 2.5, floppy drive idle 0.3
        assertEquals(11.3, PcPower.railWatts(draw(PcCpuType.RV32I_10MHZ, 0, PcRamType.RAM_16M, null, false, false,
                false)), 1e-9);
        // Every installed stick counts, usable or not
        assertEquals(8 + 0.8 + 4 + 4 + 0.3, PcPower.railWatts(draw(PcCpuType.M68000_8MHZ, 0, PcRamType.RAM_64M,
                PcRamType.RAM_64M, false, false, false)), 1e-9);
    }

    @Test
    void idleVersusBusyBlending() {
        assertEquals(0.5, PcPower.cpuWatts(PcCpuType.RV32I_10MHZ, 0), 1e-9);
        assertEquals(2, PcPower.cpuWatts(PcCpuType.RV32I_10MHZ, 1), 1e-9);
        assertEquals(1.25, PcPower.cpuWatts(PcCpuType.RV32I_10MHZ, 0.5), 1e-9);
        assertEquals(6, PcPower.cpuWatts(PcCpuType.M68040_33MHZ, 7), 1e-9);
        assertEquals(0, PcPower.cpuWatts(null, 1), 1e-9);
    }

    @Test
    void hddAndFloppyActivity() {
        double quiet = PcPower.railWatts(draw(PcCpuType.RV32I_10MHZ, 0, null, null, true, false, false));
        double busy = PcPower.railWatts(draw(PcCpuType.RV32I_10MHZ, 0, null, null, true, true, false));
        assertEquals(PcPower.HDD_ACTIVE_WATTS - PcPower.HDD_IDLE_WATTS, busy - quiet, 1e-9);
        double motor = PcPower.railWatts(draw(PcCpuType.RV32I_10MHZ, 0, null, null, true, false, true));
        assertEquals(PcPower.FLOPPY_MOTOR_WATTS - PcPower.FLOPPY_IDLE_WATTS, motor - quiet, 1e-9);
    }

    @Test
    void efficiencyAndStandby() {
        assertEquals(12.5, PcPower.inputWatts(true, 10), 1e-9);
        assertEquals(PcPower.STANDBY_WATTS, PcPower.inputWatts(false, 10), 1e-9);
    }

    @Test
    void resistanceClampAndDeadband() {
        assertEquals(230.0 * 230 / 50, PcPower.loadOhms(230, 50), 1e-6);
        assertEquals(PcPower.MIN_OHMS, PcPower.loadOhms(230, 1e6), 1e-9);
        assertEquals(PcPower.LEAK_OHMS, PcPower.loadOhms(0, 50), 1e-9);
        assertEquals(PcPower.LEAK_OHMS, PcPower.loadOhms(230, 0), 1e-9);
        assertEquals(PcPower.LEAK_OHMS, PcPower.loadOhms(1e5, 1e-6), 1e-9);
        assertFalse(PcPower.outsideDeadband(1000, 1005));
        assertTrue(PcPower.outsideDeadband(1000, 1011));
        assertTrue(PcPower.outsideDeadband(0, 5));
    }

    @Test
    void windowWithHysteresis() {
        assertFalse(PcPower.canStart(94));
        assertTrue(PcPower.canStart(95));
        assertTrue(PcPower.canStart(270));
        assertFalse(PcPower.canStart(271));
        // Once running it holds on from 90 to 280 V
        assertEquals(PcPower.Event.NONE, PcPower.check(92));
        assertEquals(PcPower.Event.NONE, PcPower.check(278));
        assertEquals(PcPower.Event.BROWN_OUT, PcPower.check(89.9));
        assertEquals(PcPower.Event.OVER_VOLTAGE, PcPower.check(280.1));
    }

    @Test
    void reversePolarity() {
        assertTrue(PcPower.canStart(-230));
        assertEquals(PcPower.Event.NONE, PcPower.check(-230));
        assertEquals(PcPower.loadOhms(230, 40), PcPower.loadOhms(-230, 40), 1e-9);
    }
}
