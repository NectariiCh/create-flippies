package com.nectariich.create_flippies.content.psu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The supply's five terminals as Power Grid resolves a click against them, and the
 * footprint that has to contain them. Asks the real {@link TerminalBoundingBox#check}
 * as well as the arithmetic, as {@code PlcSmallGeometryTest} does.
 */
class PsuGeometryTest {

    private static final PsuType PSU = PsuType.of(230, 24, 100, 3);

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    @Test
    void theWholeMatrixIsRegistered() {
        assertNotNull(PSU);
        assertEquals("psu_dcdc_230v_24v_100w_3m", PSU.id());
        assertEquals(3 * 4 * 4, PsuType.all().size(), "inputs x outputs x sizes");
        assertEquals(3, PSU.modules());
        assertEquals(100.0 / 24, PSU.ratedAmps(), 1e-12);
        assertEquals("psu_dcdc_48v_5v_30w_1m", PsuType.of(48, 5, 30, 1).id());
        assertEquals("psu_dcdc_120v_12v_60w_2m", PsuType.of(120, 12, 60, 2).id());
        assertEquals("psu_dcdc_230v_48v_150w_4m", PsuType.of(230, 48, 150, 4).id());
    }

    @Test
    void onlyThreeModulesAndUpHaveDcOk() {
        assertEquals(4, PsuGeometry.terminalCount(1));
        assertEquals(4, PsuGeometry.terminalCount(2));
        assertEquals(5, PsuGeometry.terminalCount(3));
        assertEquals(5, PsuGeometry.terminalCount(4));
        assertEquals(4, PsuType.of(230, 24, 60, 2).terminals(ControlMount.centred(Direction.UP, 0)).length);
    }

    @Test
    void oneModuleStacksEachPairPlusOverMinus() {
        // As tools/gen_psu.py prints them for PSU-DCDC-230V-24V-30W-1M
        double[] vcc = PsuGeometry.pin(1, PsuGeometry.TERMINAL_VCC);
        double[] gnd = PsuGeometry.pin(1, PsuGeometry.TERMINAL_GND);
        double[] vPlus = PsuGeometry.pin(1, PsuGeometry.TERMINAL_V_PLUS);
        double[] vMinus = PsuGeometry.pin(1, PsuGeometry.TERMINAL_V_MINUS);
        for (double[] pin : new double[][] { vcc, gnd, vPlus, vMinus })
            assertEquals(7.75, pin[0], 1e-12);
        assertEquals(2.25, vcc[1], 1e-12);
        assertEquals(1.25, gnd[1], 1e-12);
        assertEquals(2.25, vPlus[1], 1e-12);
        assertEquals(1.25, vMinus[1], 1e-12);
        assertEquals(10.5, vcc[2], 1e-12);
        assertEquals(5.25, vMinus[2], 1e-12);
        for (int a = 0; a < 4; a++)
            for (int b = a + 1; b < 4; b++)
                assertFalse(overlap(PsuGeometry.clickBox(1, a), PsuGeometry.clickBox(1, b)),
                        "1M terminals " + a + " and " + b + " overlap");
        for (int t = 0; t < 4; t++)
            for (double[] body : PsuGeometry.bodyBoxes(1))
                assertFalse(overlap(PsuGeometry.contactBox(1, t), body), "1M terminal " + t + " inside the case");
    }

    @Test
    void noTwoClickBoxesOverlap() {
        for (int a = 0; a < PsuGeometry.TERMINALS; a++)
            for (int b = a + 1; b < PsuGeometry.TERMINALS; b++)
                assertFalse(overlap(PsuGeometry.clickBox(3, a), PsuGeometry.clickBox(3, b)),
                        "terminals " + a + " and " + b + " overlap");
    }

    @Test
    void clickBoxesStayOutOfTheBody() {
        for (int t = 0; t < PsuGeometry.TERMINALS; t++)
            for (double[] body : PsuGeometry.bodyBoxes(3))
                assertFalse(overlap(PsuGeometry.contactBox(3, t), body), "terminal " + t + " inside the case");
    }

    @Test
    void pinsAreWhereTheExportDrawsThem() {
        // As tools/gen_psu.py prints them for PSU-DCDC-230V-24V-100W-3M
        assertPin(PsuGeometry.TERMINAL_VCC, 6.75, 10.5);
        assertPin(PsuGeometry.TERMINAL_GND, 7.75, 10.5);
        assertPin(PsuGeometry.TERMINAL_V_PLUS, 6.75, 5.25);
        assertPin(PsuGeometry.TERMINAL_V_MINUS, 7.75, 5.25);
        assertPin(PsuGeometry.TERMINAL_DC_OK, 8.75, 5.25);
    }

    private static void assertPin(int terminal, double minX, double minZ) {
        double[] pin = PsuGeometry.pin(3, terminal);
        assertEquals(minX, pin[0], 1e-12);
        assertEquals(1.25, pin[1], 1e-12);
        assertEquals(minZ, pin[2], 1e-12);
        assertEquals(minX + 0.5, pin[3], 1e-12);
        assertEquals(1.75, pin[4], 1e-12);
        assertEquals(minZ + 0.25, pin[5], 1e-12);
    }

    @Test
    void wiresLandOnTheOuterFaceOfThePin() {
        double[] vcc = PsuGeometry.origin(3, PsuGeometry.TERMINAL_VCC);
        assertEquals(7, vcc[0], 1e-12);
        assertEquals(1.5, vcc[1], 1e-12);
        assertEquals(10.75, vcc[2], 1e-12);
        double[] dcOk = PsuGeometry.origin(3, PsuGeometry.TERMINAL_DC_OK);
        assertEquals(9, dcOk[0], 1e-12);
        assertEquals(5.25, dcOk[2], 1e-12);
    }

    @Test
    void powerGridResolvesEveryPinToItselfAndTheGapsToNothing() {
        for (Direction face : Direction.values()) {
            ControlMount mount = ControlMount.centred(face, 0);
            TerminalBoundingBox[] terminals = PSU.terminals(mount);
            assertEquals(PsuGeometry.TERMINALS, terminals.length);
            for (int t = 0; t < PsuGeometry.TERMINALS; t++) {
                double[] o = PsuGeometry.origin(3, t);
                // Just off the pin's outer face, where anyone aiming at it clicks
                double out = o[2] < 8 ? -0.05 : 0.05;
                Vec3 at = blockSpace(mount, o[0], o[1], o[2] + out);
                for (int other = 0; other < PsuGeometry.TERMINALS; other++)
                    assertEquals(other == t, terminals[other].check(at),
                            face + ": a click on terminal " + t + " tested against " + other);
            }
            // Midway between neighbouring pins on each face, which neither may claim
            for (int[] pair : new int[][] { { 0, 1 }, { 2, 3 }, { 3, 4 } }) {
                double[] a = PsuGeometry.pin(3, pair[0]);
                double[] b = PsuGeometry.pin(3, pair[1]);
                double z = a[2] < 8 ? a[2] : a[5];
                Vec3 gap = blockSpace(mount, (a[3] + b[0]) / 2, 1.5, z);
                for (int other = 0; other < PsuGeometry.TERMINALS; other++)
                    assertFalse(terminals[other].check(gap),
                            face + ": gap " + pair[0] + "/" + pair[1] + " hits " + other);
            }
        }
    }

    @Test
    void theFootprintHoldsEveryContactBox() {
        // The contact box, not the click box: Power Grid's expansion must reach a
        // hair past the shape, or a ray stopping on a max face misses the terminal
        double[][] boxes = PSU.footprint().boxes();
        for (int t = 0; t < PsuGeometry.TERMINALS; t++) {
            double[] click = PsuGeometry.contactBox(3, t);
            boolean held = false;
            for (double[] box : boxes)
                held |= box[0] <= click[0] && box[1] <= click[1] && box[2] <= click[2]
                        && box[3] >= click[3] && box[4] >= click[4] && box[5] >= click[5];
            assertTrue(held, "terminal " + t);
        }
        assertTrue(PsuGeometry.CLICK_EXPAND > 0, "the boxes need some expansion to pass the half-open check");
    }

    @Test
    void theBodySpansThreeModulesAboutTheMiddle() {
        for (double[] box : PsuGeometry.bodyBoxes(3)) {
            assertEquals(6.5, box[0], 1e-12);
            assertEquals(9.5, box[3], 1e-12);
        }
    }

    private static Vec3 blockSpace(ControlMount mount, double x, double y, double z) {
        double[] p = mount.point(x, y, z);
        return new Vec3(p[0] / 16, p[1] / 16, p[2] / 16);
    }
}
