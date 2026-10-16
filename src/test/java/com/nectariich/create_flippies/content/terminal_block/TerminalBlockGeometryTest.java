package com.nectariich.create_flippies.content.terminal_block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Every terminal block's pins, click boxes and body, against each other and the export. */
class TerminalBlockGeometryTest {

    private static boolean overlap(double[] a, double[] b) {
        for (int axis = 0; axis < 3; axis++)
            if (!(a[axis] < b[axis + 3] && b[axis] < a[axis + 3]))
                return false;
        return true;
    }

    @Test
    void theFamilyIsRegistered() {
        List<String> ids = TerminalBlockType.all().stream().map(TerminalBlockType::id).toList();
        assertEquals(List.of("din_terminal_block_6p3g", "din_terminal_block_6p2g", "din_terminal_block_6p1g",
                "din_terminal_block_6p1g_ground", "din_terminal_block_4p2g", "din_terminal_block_4p1g",
                "din_terminal_block_4p1g_ground", "din_terminal_block_2p1g",
                "din_terminal_block_2p1g_ground"), ids);
    }

    @Test
    void pinsAreLabelledAndPlacedOnce() {
        for (TerminalBlockType type : TerminalBlockType.all()) {
            Set<String> labels = new HashSet<>();
            Set<String> places = new HashSet<>();
            for (TerminalBlockType.Pin pin : type.pins()) {
                assertTrue(labels.add(pin.label()), type.id() + " repeats " + pin.label());
                assertTrue(places.add(pin.level() + ":" + pin.side()), type.id() + " stacks two pins");
            }
            assertEquals(type.levels() * 2, type.terminalCount(), type.id());
        }
    }

    @Test
    void noTwoClickBoxesOverlap() {
        for (TerminalBlockType type : TerminalBlockType.all())
            for (TerminalBlockType.Pin a : type.pins())
                for (TerminalBlockType.Pin b : type.pins())
                    if (a != b)
                        assertFalse(overlap(TerminalBlockGeometry.contactBox(a), TerminalBlockGeometry.contactBox(b)),
                                type.id() + " " + a.label() + " and " + b.label() + " overlap");
    }

    @Test
    void clickBoxesStayOutOfTheBody() {
        for (TerminalBlockType type : TerminalBlockType.all())
            for (TerminalBlockType.Pin pin : type.pins())
                for (double[] body : TerminalBlockGeometry.bodyBoxes(type.levels()))
                    assertFalse(overlap(TerminalBlockGeometry.contactBox(pin), body),
                            type.id() + " " + pin.label() + " inside the body");
    }

    @Test
    void clickBoxesContainTheirPinsAndStayInTheHalfModule() {
        for (TerminalBlockType type : TerminalBlockType.all())
            for (TerminalBlockType.Pin pin : type.pins()) {
                double[] drawn = TerminalBlockGeometry.pin(pin);
                double[] box = TerminalBlockGeometry.contactBox(pin);
                for (int axis = 0; axis < 3; axis++)
                    assertTrue(box[axis] <= drawn[axis] && drawn[axis + 3] <= box[axis + 3], pin.label());
                assertTrue(box[0] > TerminalBlockGeometry.MIN_X && box[3] < TerminalBlockGeometry.MAX_X);
            }
    }

    @Test
    void pinsAreWhereTheExportDrawsThem() {
        // As tools/gen_terminal_blocks.py prints them for 6p3g
        List<TerminalBlockType.Pin> pins = TerminalBlockType.of("din_terminal_block_6p3g").pins();
        assertArrayEquals(new double[] { 7.875, 0.85, 5.375, 8.125, 1.1, 5.5 },
                TerminalBlockGeometry.pin(pins.get(0)), 1e-9);
        assertArrayEquals(new double[] { 7.875, 0.85, 10.5, 8.125, 1.1, 10.625 },
                TerminalBlockGeometry.pin(pins.get(1)), 1e-9);
        assertArrayEquals(new double[] { 7.875, 1.4, 5.875, 8.125, 1.65, 6 },
                TerminalBlockGeometry.pin(pins.get(2)), 1e-9);
        assertArrayEquals(new double[] { 7.875, 1.9, 9.5, 8.125, 2.15, 9.625 },
                TerminalBlockGeometry.pin(pins.get(5)), 1e-9);
    }
}
