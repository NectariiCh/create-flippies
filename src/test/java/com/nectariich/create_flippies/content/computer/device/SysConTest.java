package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.SysCon.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The system controller (spec 3.1). */
class SysConTest {

    private DeviceRig rig;
    private SysCon sys;

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        sys = new SysCon(rig.clock, 0x8000_0000, 256 * 1024, 10_000_000, 0x2000_0000, 0x8_0000);
        sys.setPresentSource(() -> PRESENT_HDD | PRESENT_MOUSE | PRESENT_COM2);
        rig.map(sys, 0x1000);
    }

    @Test
    void readValues() {
        assertEquals(0x8000_0000, rig.r32(RAM_BASE));
        assertEquals(256 * 1024, rig.r32(RAM_SIZE));
        assertEquals(10_000_000, rig.r32(CPU_HZ));
        assertEquals(0x43504C46, rig.r32(MACHINE_ID));
        assertEquals('F', rig.r8(MACHINE_ID));
        assertEquals('L', rig.r8(MACHINE_ID + 1));
        assertEquals('P', rig.r8(MACHINE_ID + 2));
        assertEquals('C', rig.r8(MACHINE_ID + 3));
        assertEquals(0x2000_0000, rig.r32(VRAM_BASE));
        assertEquals(0x8_0000, rig.r32(VRAM_SIZE));
        assertEquals(0b10101, rig.r32(PRESENT));
        assertEquals(0x8000, rig.r16(RAM_BASE + 2), "high half of RAM_BASE");
        assertEquals(0, rig.r32(POWER));
        assertEquals(0, rig.r32(0x800), "unused offsets read 0");
    }

    @Test
    void powerOffIsRecordedAndStopsTheCpuNow() {
        rig.at(500);
        rig.clock.setHorizon(Long.MAX_VALUE);
        rig.w32(POWER, 0x5555);
        assertEquals(PowerRequest.OFF, sys.powerRequest());
        assertEquals(0, sys.exitCode());
        assertEquals(500, rig.clock.horizon(), "requestStopAt(now)");
    }

    @Test
    void powerFailCarriesTheExitCode() {
        rig.w32(POWER, 0x3333 | (0xDEAD << 16));
        assertEquals(PowerRequest.OFF, sys.powerRequest());
        assertEquals(0xDEAD, sys.exitCode());
    }

    @Test
    void powerResetAndIgnoredValues() {
        rig.clock.setHorizon(Long.MAX_VALUE);
        rig.w32(POWER, 0x1234);
        assertEquals(PowerRequest.NONE, sys.powerRequest());
        assertEquals(Long.MAX_VALUE, rig.clock.horizon(), "an ignored value does not stop the CPU");
        rig.w32(POWER, 0x7777);
        assertEquals(PowerRequest.RESET, sys.powerRequest());
        assertTrue(sys.hasRequest());
        sys.clearRequests();
        assertEquals(PowerRequest.NONE, sys.powerRequest());
        assertFalse(sys.hasRequest());
    }

    @Test
    void powerAsTwoHalvesActsOnTheHighHalf() {
        rig.w16(POWER, 0x3333);
        assertEquals(PowerRequest.NONE, sys.powerRequest(), "nothing until byte 3 is written");
        rig.w16(POWER + 2, 0x0042);
        assertEquals(PowerRequest.OFF, sys.powerRequest());
        assertEquals(0x42, sys.exitCode());
    }

    @Test
    void waitAndRomOverlayRequests() {
        rig.at(77);
        rig.clock.setHorizon(Long.MAX_VALUE);
        rig.w32(WAIT, 1);
        assertTrue(sys.waitRequested());
        assertEquals(77, rig.clock.horizon());
        rig.w8(ROM_OVERLAY_OFF, 0);
        assertTrue(sys.romOverlayOffRequested());
        sys.reset();
        assertFalse(sys.waitRequested());
        assertFalse(sys.romOverlayOffRequested());
    }

    @Test
    void debugOutCollectsLowBytes() {
        rig.w32(DEBUG_OUT, 0x1234_5648);
        rig.w8(DEBUG_OUT, 'i');
        rig.w8(DEBUG_OUT + 1, 'X');
        rig.w16(DEBUG_OUT, 0x0A21);
        assertEquals("Hi!", sys.debugLog());
        sys.reset();
        assertEquals("Hi!", sys.debugLog(), "the host log survives a guest reset");
        sys.clearDebugLog();
        assertEquals("", sys.debugLog());
    }

    @Test
    void debugLogIsBounded() {
        for (int i = 0; i < DEBUG_LOG_LIMIT + 10; i++)
            rig.w8(DEBUG_OUT, 'a' + i % 26);
        assertTrue(sys.debugLog().length() <= DEBUG_LOG_LIMIT);
        assertTrue(sys.debugLog().endsWith(String.valueOf((char) ('a' + (DEBUG_LOG_LIMIT + 9) % 26))));
    }
}
