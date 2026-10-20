package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.InterruptController.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.IrqSink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The interrupt controller (spec 3.2). */
class InterruptControllerTest {

    private DeviceRig rig;
    private InterruptController pic;
    private final DeviceRig.Line[] out = new DeviceRig.Line[OUTPUTS];

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        pic = new InterruptController();
        rig.map(pic, 0x1000);
        for (int k = 0; k < OUTPUTS; k++) {
            out[k] = new DeviceRig.Line();
            pic.setOutput(k, out[k]);
        }
    }

    @Test
    void pendingReflectsLineLevels() {
        IrqSink l3 = pic.line(3);
        l3.set(true);
        pic.line(9).set(true);
        assertEquals(1 << 3 | 1 << 9, rig.r32(PENDING));
        l3.set(false);
        assertEquals(1 << 9, rig.r32(PENDING));
        rig.w32(PENDING, -1);
        assertEquals(1 << 9, rig.r32(PENDING), "PENDING is read-only");
    }

    @Test
    void claimPicksTheLowestEnabledLineWithoutSideEffect() {
        pic.line(5).set(true);
        pic.line(3).set(true);
        assertEquals(0, rig.r32(CLAIM), "nothing enabled");
        rig.w32(ENABLE, 1 << 5);
        assertEquals(5, rig.r32(CLAIM));
        rig.w32(ENABLE, 1 << 5 | 1 << 3);
        assertEquals(3, rig.r32(CLAIM));
        assertEquals(3, rig.r32(CLAIM), "reading CLAIM changes nothing");
        assertEquals(1 << 5 | 1 << 3, rig.r32(PENDING));
        rig.w32(ENABLE, -1);
        assertEquals(0xFFFF_FFFE, rig.r32(ENABLE), "line 0 is unused");
    }

    @Test
    void outputZeroFollowsEnable() {
        pic.line(4).set(true);
        assertFalse(out[0].level);
        rig.w32(ENABLE, 1 << 4);
        assertTrue(out[0].level);
        rig.w32(ENABLE, 0);
        assertFalse(out[0].level);
        rig.w32(ENABLE, 1 << 4);
        pic.line(4).set(false);
        assertFalse(out[0].level, "the output follows the line level too");
        assertEquals(4, out[0].changes);
    }

    @Test
    void routeMovesALineToAnotherOutput() {
        pic.line(6).set(true);
        rig.w32(ENABLE, 1 << 6);
        assertTrue(out[0].level);
        rig.w32(ROUTE0, 3 << 24);
        assertFalse(out[0].level, "output 0 drops");
        assertTrue(out[3].level, "output 3 rises");
        assertEquals(6, rig.r32(CLAIM_OUT0 + 4 * 3));
        assertEquals(0, rig.r32(CLAIM_OUT0));
        assertEquals(6, rig.r32(CLAIM), "CLAIM covers every output");
        assertTrue(pic.output(3));
    }

    @Test
    void routeRegistersPackFourBitsPerLine() {
        rig.w32(ROUTE0 + 4, 0x7 << 4);
        pic.line(9).set(true);
        rig.w32(ENABLE, 1 << 9);
        assertTrue(out[7].level, "line 9 is ROUTE1 bits 6..4");
        rig.w32(ROUTE0, 0xFFFF_FFFF);
        assertEquals(0x7777_7777, rig.r32(ROUTE0), "bit 3 of each field is reserved");
    }

    @Test
    void sixteenBitSplitAccessToRoute() {
        pic.line(6).set(true);
        rig.w32(ENABLE, 1 << 6);
        rig.w16(ROUTE0 + 2, 0x0300);
        assertEquals(0x0300_0000, rig.r32(ROUTE0));
        assertEquals(0x0300, rig.r16(ROUTE0 + 2));
        assertEquals(0, rig.r16(ROUTE0));
        assertTrue(out[3].level);
        rig.w16(ROUTE0, 0x0002);
        assertEquals(0x0300_0002, rig.r32(ROUTE0), "the other half is kept");
        rig.w8(ROUTE0 + 3, 0x05);
        assertEquals(0x0500_0002, rig.r32(ROUTE0));
        assertTrue(out[5].level);
    }

    @Test
    void vectorBaseAndAcknowledge() {
        assertEquals(VECTOR_BASE_RESET, rig.r32(VECTOR_BASE));
        pic.line(2).set(true);
        rig.w32(ENABLE, 1 << 2);
        assertEquals(0x0A, pic.acknowledgeVector());
        rig.w32(VECTOR_BASE, 0x70);
        assertEquals(0x72, pic.acknowledgeVector());
    }

    @Test
    void resetKeepsLevelsAndClearsTheRest() {
        pic.line(1).set(true);
        rig.w32(ENABLE, 2);
        rig.w32(ROUTE0, 0x10);
        rig.w32(VECTOR_BASE, 0x40);
        pic.reset();
        assertEquals(2, rig.r32(PENDING));
        assertEquals(0, rig.r32(ENABLE));
        assertEquals(0, rig.r32(ROUTE0));
        assertEquals(VECTOR_BASE_RESET, rig.r32(VECTOR_BASE));
        for (DeviceRig.Line l : out)
            assertFalse(l.level);
    }

    @Test
    void lineZeroAndOutOfRangeLinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> pic.line(0));
        assertThrows(IllegalArgumentException.class, () -> pic.line(32));
    }

    @Test
    void setOutputDrivesTheCurrentLevel() {
        pic.line(7).set(true);
        rig.w32(ENABLE, 1 << 7);
        DeviceRig.Line late = new DeviceRig.Line();
        pic.setOutput(0, late);
        assertTrue(late.level);
    }
}
