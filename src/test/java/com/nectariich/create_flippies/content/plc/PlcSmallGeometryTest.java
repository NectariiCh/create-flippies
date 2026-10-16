package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The DIN PLC's twenty terminals as Power Grid resolves a click against them.
 *
 * Regression for the boxes overlapping: Power Grid's six-coordinate
 * {@code TerminalBoundingBox} constructor grows every box by a further 0.1 px a side,
 * which on top of {@code PAD} made every neighbouring pair overlap by 0.15 px, VCC
 * and GND among them. These tests ask the real {@link TerminalBoundingBox#check} as
 * well as the arithmetic, so a Power Grid that changes its default again is caught.
 */
class PlcSmallGeometryTest {

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    @Test
    void noTwoClickBoxesOverlap() {
        for (int a = 0; a < PlcSmallGeometry.TERMINALS; a++)
            for (int b = a + 1; b < PlcSmallGeometry.TERMINALS; b++)
                assertFalse(overlap(PlcSmallGeometry.clickBox(a), PlcSmallGeometry.clickBox(b)),
                        "terminals " + a + " and " + b + " overlap");
    }

    @Test
    void neighboursKeepAVisibleGap() {
        // Same row, next pin: the boxes stay a twentieth of a pixel apart
        for (int t = 0; t < PlcSmallGeometry.TERMINALS; t++) {
            if (t % 10 == 9)
                continue;
            // Rows count down x, so the next pin is the one to the left
            double gap = PlcSmallGeometry.clickBox(t)[0] - PlcSmallGeometry.clickBox(t + 1)[3];
            assertEquals(0.05, gap, 1e-9, "between terminals " + t + " and " + (t + 1));
        }
    }

    @Test
    void powerGridResolvesEveryPinToItselfAndTheGapsToNothing() {
        ControlMount mount = ControlMount.centred(Direction.UP, 0);
        TerminalBoundingBox[] terminals = PlcSmallGeometry.terminals(mount);
        assertEquals(PlcSmallGeometry.TERMINALS, terminals.length);

        for (int t = 0; t < PlcSmallGeometry.TERMINALS; t++) {
            double[] pin = PlcSmallGeometry.pin(t);
            // The middle of the pin's top face, where anyone aiming at it clicks
            Vec3 centre = blockSpace(mount, (pin[0] + pin[3]) / 2, pin[4], (pin[2] + pin[5]) / 2);
            for (int other = 0; other < PlcSmallGeometry.TERMINALS; other++)
                assertEquals(other == t, terminals[other].check(centre),
                        "a click on terminal " + t + " tested against " + other);

            if (t % 10 == 9)
                continue;
            // Midway between this pin and the next, which neither may claim
            double[] next = PlcSmallGeometry.pin(t + 1);
            Vec3 gap = blockSpace(mount, (next[3] + pin[0]) / 2, pin[4], (pin[2] + pin[5]) / 2);
            for (int other = 0; other < PlcSmallGeometry.TERMINALS; other++)
                assertFalse(terminals[other].check(gap), "the gap after terminal " + t + " hits " + other);
        }
    }

    @Test
    void vccAndGndAreApartWhereverTheClickLands() {
        // Sweep across the VCC/GND gap in 0.01 px steps: never both at once
        ControlMount mount = ControlMount.centred(Direction.UP, 0);
        TerminalBoundingBox[] terminals = PlcSmallGeometry.terminals(mount);
        double[] vcc = PlcSmallGeometry.pin(PlcSmallGeometry.TERMINAL_VCC);
        double[] gnd = PlcSmallGeometry.pin(PlcSmallGeometry.TERMINAL_GND);
        double z = (vcc[2] + vcc[5]) / 2;
        for (double x = Math.min(vcc[0], gnd[0]); x <= Math.max(vcc[3], gnd[3]); x += 0.01) {
            Vec3 at = blockSpace(mount, x, vcc[4], z);
            assertFalse(terminals[PlcSmallGeometry.TERMINAL_VCC].check(at)
                    && terminals[PlcSmallGeometry.TERMINAL_GND].check(at), "both at x = " + x);
        }
        assertTrue(PlcSmallGeometry.CLICK_EXPAND == 0, "Power Grid adds nothing to PAD");
    }

    private static Vec3 blockSpace(ControlMount mount, double x, double y, double z) {
        double[] p = mount.point(x, y, z);
        return new Vec3(p[0] / 16, p[1] / 16, p[2] / 16);
    }
}
