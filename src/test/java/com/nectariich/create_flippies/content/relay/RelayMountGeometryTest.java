package com.nectariich.create_flippies.content.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Each mount's pins, click boxes and body, against each other and the export. */
class RelayMountGeometryTest {

    private static final List<RelayMountGeometry> ALL =
            List.of(RelayMountGeometry.FIVE_PIN, RelayMountGeometry.EIGHT_PIN);

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    @Test
    void terminalCounts() {
        assertEquals(5, RelayMountGeometry.FIVE_PIN.terminals());
        assertEquals(8, RelayMountGeometry.EIGHT_PIN.terminals());
        assertEquals(1, RelayMountType.FIVE_PIN.modules());
        assertEquals(0.5, RelayMountType.FIVE_PIN.width());
        assertEquals(1, RelayMountType.EIGHT_PIN.modules());
        assertEquals(1.0, RelayMountType.EIGHT_PIN.width());
    }

    @Test
    void noTwoClickBoxesOverlap() {
        for (RelayMountGeometry g : ALL)
            for (int a = 0; a < g.terminals(); a++)
                for (int b = a + 1; b < g.terminals(); b++)
                    assertFalse(overlap(g.contactBox(a), g.contactBox(b)),
                            g.pins() + "-pin terminals " + a + " and " + b + " overlap");
    }

    @Test
    void clickBoxesStayOutOfTheBody() {
        for (RelayMountGeometry g : ALL)
            for (int t = 0; t < g.terminals(); t++)
                for (double[] body : g.bodyBoxes())
                    assertFalse(overlap(g.contactBox(t), body), g.pins() + "-pin terminal " + t + " inside the case");
    }

    @Test
    void clickBoxesContainTheirPins() {
        for (RelayMountGeometry g : ALL)
            for (int t = 0; t < g.terminals(); t++) {
                double[] pin = g.pin(t);
                double[] box = g.contactBox(t);
                for (int axis = 0; axis < 3; axis++)
                    assertTrue(box[axis] <= pin[axis] && pin[axis + 3] <= box[axis + 3],
                            g.pins() + "-pin terminal " + t);
            }
    }

    @Test
    void neighboursDoNotShareAClick() {
        for (RelayMountGeometry g : ALL)
            for (int t = 0; t < g.terminals(); t++) {
                double[] box = g.contactBox(t);
                assertTrue(box[0] > g.minX() && box[3] < g.maxX());
            }
    }

    @Test
    void fivePinPinsAreWhereTheExportDrawsThem() {
        // As tools/gen_relay.py prints them; the 5-pin order is frozen
        RelayMountGeometry g = RelayMountGeometry.FIVE_PIN;
        assertArrayEquals(new double[] { 7.875, 1.1, 5.375, 8.125, 1.35, 5.5 }, g.pin(0), 1e-9);
        assertArrayEquals(new double[] { 7.875, 1.9, 5.875, 8.125, 2.15, 6 }, g.pin(1), 1e-9);
        assertArrayEquals(new double[] { 7.875, 1.1, 10.5, 8.125, 1.35, 10.625 }, g.pin(2), 1e-9);
        assertArrayEquals(new double[] { 7.875, 1.9, 10, 8.125, 2.15, 10.125 }, g.pin(3), 1e-9);
        assertArrayEquals(new double[] { 7.875, 2.65, 9.5, 8.125, 2.9, 9.625 }, g.pin(4), 1e-9);
    }

    @Test
    void eightPinPolesAreTheTwoColumns() {
        RelayMountGeometry g = RelayMountGeometry.EIGHT_PIN;
        for (int pole = 0; pole < 2; pole++) {
            double x = pole == 0 ? 7.625 : 8.125;
            assertEquals(x, g.pin(RelayMountGeometry.common(pole))[0], 1e-9);
            assertEquals(x, g.pin(RelayMountGeometry.no(pole))[0], 1e-9);
            assertEquals(x, g.pin(RelayMountGeometry.nc(pole))[0], 1e-9);
            // NO at the bottom, C in the middle, NC at the top
            assertEquals(1.0, g.pin(RelayMountGeometry.no(pole))[1], 1e-9);
            assertEquals(1.5, g.pin(RelayMountGeometry.common(pole))[1], 1e-9);
            assertEquals(2.0, g.pin(RelayMountGeometry.nc(pole))[1], 1e-9);
        }
    }
}
